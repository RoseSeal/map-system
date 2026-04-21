# v1.0 Weather Track — 总览规划

> 文档状态：active
> 最后更新：2026-04-19
> 用途：v1.0 天气 track 的方向判断、范围收敛与 step 拆分的中间层规划文档。
> 非目标：不是 step-plan 实施细则；不替代 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)、[`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) 等当前真值文档。

---

## 1. 当前系统现状（工程视角）

### 1.1 天气能力基线：Step 1 已完成，主链路已建立

截至 2026-04-18，weather Step 1 已完成，系统已经具备从 simulator 到前端展示的基础天气链路：

- Simulator 已可发布 `usv/Weather`
- 后端已具备 weather ingest、`WeatherContextHolder` 与气象 DTO，当前承载形态仍是单帧天气快照
- `environment_context` 已可携带 `weather` 与天气触发的 `active_alerts`
- `RiskAssessmentEngine.consume` 签名无环境参数，分级与评分完全基于几何
- `LlmRiskContext` 无 weather 字段，prompt 未注入天气语义
- 前端已具备 fog overlay 与天气摘要展示，但更进一步的增强天气视觉效果仍未收口

因此，weather track 已从“从零起步”进入“Step 1 完成、Step 2–4 待继续”的状态。与已有海图与 ENC 基础链路的 hydrology track（参见 [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §1.1）相比，两者的工程起点曾经不同，但目前都已进入“基础视觉链路已落地”的阶段。

### 1.2 可复用的上下游基础设施

尽管 weather track 本身尚无现成实现，但以下基础设施可以复用，从而降低建设成本：

- **MQTT 基础链路**：simulator 已有 `mqtt_publisher_base.py`，后端已有 MQTT 订阅配置（[`source/ais/config/`](../../../backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config) 与 `source/ais/mqtt/`）；增加一个 `usv/Weather` topic 是对现有模式的复用，不是新建 transport 层
- **`environment_context` 注入点**：[`RiskObjectMetaAssembler.buildEnvironmentContext`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject/RiskObjectMetaAssembler.java#L46) 是单一注入点，本 track 可在此基础上完成扩展
- **前端 `environment` 消费点**：[`useRiskStore.ts:83`](../../../frontend/src/store/useRiskStore.ts#L83) 已把 `environment_context` 原封接入 state，状态入口可复用；但 [`schema.d.ts:133-136`](../../../frontend/src/types/schema.d.ts#L133-L136) 的 `EnvironmentContext` 类型仅定义 `safety_contour_val` 与 `active_alerts` 两个字段，weather / hydrology 一旦进入 SSE，`schema.d.ts`、测试夹具和前端消费代码均需同步扩展
- **Agent 工具注册框架**：agent loop 已约定 `AgentToolRegistry`（AGENT_LOOP_PLAN §3.8），本 track 注册新工具是标准扩展点
- **视觉入口已落地**：`StatusPanel` 与地图 fog overlay 已接入真实 weather 字段；与更强天气表现相关的后续视觉收口转由 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md) 承接。历史占位与接线演进见 [`../VISUAL_UPGRADE_REFERENCE.md`](../VISUAL_UPGRADE_REFERENCE.md)

---

## 2. Track 方向与边界

### 2.1 Track Goal

天气 track 在 v1.0 内交付三件事，对应用户提出的三项终态：

1. **风险引擎接入**：能见度缩放 CPA 有效阈值；高海况条件下引入额外 weather penalty；强流对动作建议裕量产生约束
2. **LLM 接入**：agent loop 通过 `GetWeatherContextTool` 消费当前气象事实，advisory `evidence_items` 可引用"能见度不足、建议减速"等机动建议
3. **前端天气展示接入与协同**：完成天气数据到前端展示的基础接线，并与整体视觉专题协同；更强的天气表现形式由 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md) 继续迭代

### 2.2 Release impact

天气 track 不阻塞 `v1.0` 主版本完成（主版本收口以 agent 主线为准）。Step 1–4 已属于当前规划链，因而不写入 [`../../TODO.md`](../../TODO.md)；若 `v1.0` 关闭时 Step 2 / Step 3 / Step 4 仍未完成，应先迁移到后续 milestone / step 链，只有失去明确 owner 的剩余项才回收至 TODO。

### 2.3 Non-goals

- 不接入真实气象 API（OpenWeatherMap / NOAA / ECMWF）；相关许可与精度评估不在 v1.0 范围
- 不做气象数据的时空插值、预报或同化
- 不构建天气知识图谱或天气专用 GraphRAG（与 agent track 的 COLREGS GraphRAG 解耦）
- 不在 `v1.0` 内扩展 HTTP 拉取或文件加载等额外输入实现；Step 1 仅采用 simulator MQTT feed，保持输入模型单一

---

## 3. 关键设计决策

### 3.1 输入源：simulator 独立 MQTT topic，先单快照后区域化

v1.0 Step 1 选择 **simulator 新增 `usv/Weather` topic → 后端订阅 → `shared.context.WeatherContextHolder` → `environment_context.weather`** 作为首个输入实现。

在此基础上，新增的 Step 2 不改变输入通道，仍由 simulator 通过 MQTT 驱动；变化点是把单帧全局天气快照升级为**区域化天气 payload**。后端据此解析 `weather_zones`，再为本船当前位置解算一份 `effective weather`，继续向下游暴露稳定的 `environment_context.weather` 摘要，避免 Step 1 已落地的消费方发生契约回归。

备选方案对比：

| 方案 | 真实性 | 成本 | 能否支撑引擎/LLM | 选择 |
|---|---|---|---|---|
| 前端静态 mock | 低 | 极低 | 否（无法驱动后端修正） | ✗ |
| simulator MQTT 独立 topic | 可控仿真 | 低（复用 MQTT 基础设施） | 是 | **✓** |
| 真实气象 API | 高 | 高（许可 / 限流 / schema 适配） | 是 | ✗（v1.0 后） |

选择 MQTT 的关键理由：

- **单一真值源**：前端与后端引擎/LLM 读同一信号，避免 mock 与后端结果对不上
- **复用既有基础设施**：`mqtt_publisher_base.py` + 后端 `source/ais/mqtt` 已是成熟链路
- **可控演示**：通过 simulator 命令行参数切换"晴/雾/雨/大风"四套场景，便于录屏与评审演示
- **未来可替换**：若后续接真实 API，仅需新增一个 `WeatherIngestAdapter` 实现，无需改动下游消费方

### 3.2 EnvironmentContext 扩展契约（跨 track 共享）

v1.0 Step 1 已引入 `environment_context.weather` 子字段。新增的 Step 2 将把其语义从“全局天气快照”收敛为“**当前 risk snapshot / 本船位置生效的天气摘要**”，并可选追加 `environment_context.weather_zones` 供区域天气视觉层消费。**此契约与水文 track 共享同一个 `environment_context` 顶层节点**，任何一方修改顶层结构必须同步 [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §3.2。

```json
"environment_context": {
  "safety_contour_val": 10.0,                 // 现有
  "active_alerts": ["LOW_VISIBILITY"],         // 现有字段，本 track 首次填充
  "weather": {                                 // 当前本船位置生效天气摘要
    "weather_code": "FOG",                     // CLEAR / FOG / RAIN / SNOW / STORM
    "visibility_nm": 0.8,
    "precipitation_mm_per_hr": 0.0,
    "wind": { "speed_kn": 12.0, "direction_from_deg": 225 },
    "surface_current": { "speed_kn": 1.4, "set_deg": 090 },
    "sea_state": 4,                            // Beaufort-like 0..9
    "source_zone_id": "fog-bank-east",
    "updated_at": "2026-04-17T10:22:15Z"
  },
  "weather_zones": [                           // Step 2 新增，可选
    {
      "zone_id": "fog-bank-east",
      "weather_code": "FOG",
      "visibility_nm": 0.8,
      "precipitation_mm_per_hr": 0.0,
      "wind": { "speed_kn": 12.0, "direction_from_deg": 225 },
      "surface_current": { "speed_kn": 1.4, "set_deg": 90 },
      "sea_state": 4,
      "geometry": {
        "type": "Polygon",
        "coordinates": [[[114.30, 30.52], [114.34, 30.52], [114.34, 30.56], [114.30, 30.56], [114.30, 30.52]]]
      },
      "updated_at": "2026-04-17T10:22:15Z"
    }
  ]
}
```

字段约束：

- `weather` 继续保留，作为下游引擎 / LLM / 面板的稳定消费入口；其值是区域求值后的生效摘要，不再等价于整张海图共享同一份天气
- `weather_zones` 为可选字段，仅 Step 2 及之后的区域天气场景使用；为控制 payload 体积，v1.0 仅支持 simulator 下发少量 zone（建议不超过 4 个）
- 所有子字段可选，模拟器可按场景只填部分；缺字段用 `null`
- `wind.direction_from_deg`、`surface_current.set_deg` 遵守航海惯例（风向=风的来向，流向=流的去向）
- `visibility_nm` 单位与 CPA 体系一致（nm），避免引擎消费时二次换算
- 区域几何仅承载当前帧有效区块，不下发气象原始时序
- Step 2 必须兼容 Step 1 的单快照 payload：若未提供 `weather_zones`，后端仍按单区全图语义处理，不打断已有链路

### 3.3 `active_alerts` 语义化约定（跨 track 共享）

本 track 贡献项：

- `LOW_VISIBILITY`：`visibility_nm < 2.0`（阈值可配）
- `HIGH_WIND`：`wind.speed_kn > 25`
- `HEAVY_PRECIPITATION`：`precipitation_mm_per_hr > 10`
- `STRONG_CURRENT_SET`：`surface_current.speed_kn > 2.5`

与水文 track 共用同一枚举 `EnvAlertCode`（定义点 [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §3.3）。截至当前计划版本，weather 已输出这些告警，但实现仍是字符串字面量；统一迁移到共享枚举的动作在 hydrology Step 2 一并落地。

### 3.4 风险引擎接入形态：区域求值后以标量修正 + 独立 penalty

**与水文 track 的 geofence 形态不同**：水文是稳定的区域级约束；天气在 Step 2 后虽然具备区域分布，但 Step 3 接入引擎时仍先解算为当前位置的 `effective weather`，再以标量方式修正风险，不把多边形直接塞进 CPA / TCPA 主干。

具体三条独立修正路径：

- **区域求值前置**：先由 `RegionalWeatherResolver` 或等价组件按本船当前位置从 `weather_zones` 求得 `effective weather`；若没有命中区域，则退化为 Step 1 的单快照 weather 或 `null`
- **visibility → effective threshold 缩放**：能见度不足时，COLREGS 规则 19 要求"以安全航速"，对应工程实现是把 `cautionDcpaNm / warningDcpaNm / alarmDcpaNm` 按能见度系数线性放大。`visibility_nm < 2.0` 时系数 1.5x，`< 0.5` 时 2.0x
- **surface_current → 动作建议置信度修正**：`environment_context.weather.surface_current` 在 Step 2 后表示“当前位置生效流场”，其正确语义仍是机动执行误差增大，而不是直接修正当前 CPA 几何。体现在 advisory `evidence_items` 中为"当前流速 1.4 节，建议机动量适当加大裕量"，而非修正预测几何。[`RiskAssessmentEngine.java:135`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java#L135) 已有的微调路径在此场景下不适用
- **weather penalty**：`weather_code == STORM` 或 `sea_state >= 7` 时，独立的 `environmentalWeatherPenalty` 并入 `finalRiskScore`（与水文 penalty 并列，不共享计算）

实施约束：

- 三条路径默认关，由 `risk.weather.*.enabled` 配置项分别开启，灰度放量
- 不改 `classifyRisk` 的阈值硬编码；通过 per-frame 计算"生效阈值"传入替代，保持规则侧纯净
- 不在 `TargetRiskAssessment.ruleExplanation` 里把天气推理文本拼进去；天气修正的解释路径走 LLM

### 3.5 LLM 接入形态：effective weather static context + agent tool 双通道

**与水文 track 的"只走 agent tool"不同**：即使 Step 2 把天气改成区域化输入，LLM 在每次推理时仍几乎都需要知道**当前位置生效的天气**，因此 `effective weather` 仍应进入 `LlmRiskContext` 的静态注入，而不是要求模型每轮都先查多边形：

- `LlmRiskContext` 扩展 `weather: LlmRiskWeatherContext` 可选字段
- `RiskContextFormatter` 生成一段简短的"当前气象：能见度 0.8 nm，有雾；风 SW 12 节；水流 090 1.4 节"
- 同时注册 agent 工具 `GetWeatherContextTool()` → 返回 `effective weather` 与命中 `zone_id`，用于 LLM 需要精确数值时

advisory `evidence_items` 在 `weather_code != CLEAR` 场景下应包含至少一条气象事实项（prompt 约束）。

### 3.6 前端展示边界

weather track 在 `v1.0` 内需要保证天气事实能够稳定进入前端展示，并为后续视觉专题提供真实数据来源。

当前已落地或已确定的边界：

- **fog overlay**：Step 1 已完成全图式基础天气可视化入口；Step 2 起升级为按 `weather_zones` 渲染的区域天气图层
- **天气摘要展示**：作为 Step 1 已完成的状态面板入口
- **视觉优先级硬约束**：ALARM / WARNING 符号必须在所有天气相关展示之上保持可见

面向 Step 2 的低复杂度前端支持约束：

- `weather_zones` 以“少量区块 + 明确样式”优先，不走高密度栅格、插值场或逐像素天气贴图；v1.0 建议每帧 2–4 个 zone 即可
- 几何优先支持简单 `Polygon`；`MultiPolygon` 仅用于少量离散天气块，不要求支持洞、自交或复杂拓扑修复
- 前端天气层只负责区域表现，不参与点击命中、拖拽交互或目标选择判定，避免压制现有海图交互
- 视觉增强优先使用“半透明填充 + 轻纹理 / 轻渐变边缘”的廉价方案，而不是粒子系统、风场插值或大规模动画
- `FOG` 优先表现为区域雾层与边缘羽化；`RAIN` 优先表现为冷色透明罩层叠加少量斜向纹理；`STORM` 优先表现为更深色罩层与低频强调效果
- 区域边界允许视觉羽化，但不要求数据层平滑，不引入额外后端几何细分或边界插值
- `StatusPanel` 与其它摘要入口继续只展示本船当前位置命中的 `effective weather`，不枚举所有 zone，避免 UI 信息过载
- 若天气层与风险符号出现竞争，优先压缩天气层 opacity / 特效强度，而不是调整告警层级

不在本 plan 内预写真值的内容：

- 降雨粒子层
- 风场箭头
- 水流矢量
- 其它仍需多轮试验的增强天气视觉效果

上述增强项统一交由 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md) 管理，不再在 weather track 内写死具体方案。

### 3.7 与 agent `EvaluateManeuverTool` 的协作

agent track 的 `EvaluateManeuverTool`（AGENT_LOOP_PLAN §3.8）当前只评估 CPA 几何。本 track 贡献扩展输入：

- `EvaluateManeuverWithWeatherTool(course_change_deg, speed_change_kn, lookahead_min)` → 评估在当前气象下该机动的"可行性"（能见度不足时是否建议减速幅度、强流下是否需要加大 course change）

此工具由 agent 从 `AgentSnapshot.riskContext.weather` 读取，不与水文工具合并。

---

## 4. Step 拆分

### Step 1：MQTT topic + 后端承载 + 前端视觉基础

状态：已完成（2026-04-18）

**目标**：建立端到端信号链路，交付首个可演示场景（低能见度 + 雾 overlay）。

**主要工作**：

- Simulator 新增 `weather_mqtt_publisher.py`（复用 `mqtt_publisher_base.py`），支持 `--scene clear|fog|rain|storm` 场景切换
- 后端新建 `source/weather/mqtt/WeatherMessageHandler` + `shared/context/WeatherContextHolder`（volatile Snapshot 模式，仿 `RiskContextHolder`）
- `RiskObjectMetaAssembler.buildEnvironmentContext` 注入 `weather` 字段与 `active_alerts`
- 前端新增 `useWeatherStore` slice（或直接扩展 `useRiskStore.environment.weather` 类型）
- 前端 fog overlay 层，`visibility_nm → opacity` 映射
- 前端 `StatusPanel` 新增当前气象摘要标签

**验收**：

- `--scene fog` 下，前端海图出现明显雾化；`environment_context.weather.weather_code == "FOG"` 在 SSE 下发
- `active_alerts` 包含 `LOW_VISIBILITY`
- 风险引擎未接入，分数与之前一致（本 step 不改 engine）

### Step 2：区域化天气建模 + simulator 驱动 weather zones

**目标**：在不破坏 Step 1 已落地链路的前提下，把天气从单帧全局快照升级为区域化天气，并继续由 simulator 驱动。

**主要工作**：

- simulator 扩展 `weather_mqtt_publisher.py` payload，支持下发 `weather_zones[]`，每个 zone 包含 `zone_id`、天气字段与 GeoJSON `Polygon` / `MultiPolygon`
- 保持 `usv/Weather` topic 不变，不新增额外 transport；Step 1 的单快照 scene 仍可运行
- 后端 weather ingest 扩展为可同时承载单快照 weather 与 `weather_zones`；新增 `RegionalWeatherResolver` 或等价解析逻辑，按本船当前位置求得 `effective weather`
- `environment_context.weather` 语义调整为“当前位置生效天气摘要”，新增可选 `environment_context.weather_zones` 供前端区域天气图层消费
- `active_alerts` 仅根据 `effective weather` 生成，不对所有 zone 做并集告警，避免本船未进入雾区却收到 `LOW_VISIBILITY`
- 前端 fog / rain 等天气图层改为按 `weather_zones` 局部渲染；未命中区域时保持地图其余区域清晰
- 前端实现收敛为“粗区域、多样式、弱计算”：以半透明区域层、轻纹理和边缘羽化为主，不引入粒子系统、风场箭头或逐像素混合
- 几何输入以简单 `Polygon` 为主；若支持 `MultiPolygon`，仅限少量离散天气块，不把复杂几何修复纳入 Step 2
- 兼容性约束：旧 payload 未带 `weather_zones` 时，系统退化回 Step 1 语义，不要求前后端同步切换才能运行

**验收**：

- simulator 提供“一区雾、一区晴”场景时，前端仅在雾区显示天气覆盖层，不再整图统一雾化
- 本船进入 fog zone 前后，`environment_context.weather.weather_code` 与 `active_alerts` 随位置切换；离开雾区后恢复为命中区域对应值
- 旧的 `--scene fog` 单快照场景在 Step 2 完成后仍可运行，不因未携带 `weather_zones` 而导致解析失败或前端空白
- 区域天气图层启用后，目标点选、地图拖拽与告警符号可见性不因天气层回归

### Step 3：风险引擎消费天气

**目标**：让天气因素以受控方式修正风险分级与评分。

**主要工作**：

- `RiskAssessmentEngine.consume` 签名扩展接收 `WeatherContext` 或通过构造函数注入 `shared.context.WeatherContextHolder`
- 实现 §3.4 中与引擎直接相关的两条修正路径（visibility 阈值缩放 / storm penalty）；强流约束保留为 advisory 与机动评估层消费，不直接进入当前 CPA 修正链路
- 引入 `risk.weather.*.enabled` 配置项，默认全关
- 回归测试：关闭全部开关时，所有现有单测必须保持绿色，且行为不发生变化

**验收**：

- `--scene fog` + `visibility.enabled=true` 下，同一目标在能见度 0.8 nm 时 `riskLevel` 比晴天提前一级
- 区域化天气启用但所有引擎开关关闭时，行为仅体现为 `effective weather` 与前端区域显示变化；风险分数与 Step 2 完成后保持一致
- 所有开关关闭时与 Step 2 完成后行为保持一致

### Step 4：LLM static context + agent tool + advisory 消费

**目标**：让 advisory 在低能见度等场景下给出受气象约束的机动建议。

**主要工作**：

- `LlmRiskContext` 新增 `weather` 字段，`LlmRiskContextAssembler` 填充
- `RiskContextFormatter` 追加气象段落（仅当 `weather_code != CLEAR` 或 alerts 非空时注入，避免稀释 prompt 密度）
- 注册 agent 工具 `GetWeatherContextTool`、`EvaluateManeuverWithWeatherTool`
- advisory prompt 约束：`weather_code != CLEAR` 时 `evidence_items` 至少包含一条气象事实
- 前端 advisory card 如需补充气象事实项展示，应仅承接 advisory 消费所需的最小 UI 变更；增强天气视觉效果不归入 Step 4

**验收**：

- `--scene fog` 下触发的 advisory `evidence_items` 含 "能见度 0.8 nm，低于 2.0 nm 阈值" 事实项
- agent 能调用 `GetWeatherContextTool` 并得到正确数值

---

## 5. 风险、取舍与跨 track 同步

### 5.1 MQTT 丢包与陈旧气象

气象是时变信号，网络抖动可能让最后一次更新停留数十秒。约束：

- `shared.context.WeatherContextHolder` 持有 `updated_at`；Step 2 引入 `weather_zones` 后，整组 zone snapshot 仍按同一个 freshness 语义处理；下游消费时若 `now - updated_at > staleThresholdSec`（默认 60s）视为不可用
- 不可用时 `environment_context.weather = null`（完整缺失），而不是填 `CLEAR`
- 引擎与 LLM 在 `weather == null` 时的行为：引擎退化为无气象修正；LLM prompt 不注入气象段

### 5.2 引擎修正的反直觉行为

visibility 缩放 caution 阈值会让同一目标在雾天"更早"跳 CAUTION。风险分级陡然提前可能让用户误认为系统报警异常。缓解：

- 默认开关关，开启时必须在 `StatusPanel` 显示"气象修正已启用"
- 解释文本（LLM 路径）必须显式说明"由于能见度不足，CAUTION 阈值已由 1.5 nm 放宽至 2.25 nm"
- 本条在 Step 3 实施时作为验收点之一

### 5.3 增强天气视觉效果的性能风险

若后续视觉专题引入粒子、箭头、矢量或其它增强天气效果，中端设备上可能出现 FPS 下滑。该类性能约束与降级策略由 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md) 后续专项迭代定义，不在当前 weather track 内预写死参数。

### 5.4 需要同步更新的真值文档

- [`../../TODO.md`](../../TODO.md) 仅跟踪未挂入 Step 1–4 的后续 backlog；weather 主线事项不重复记录于 TODO。当前已回收到 TODO 的未挂载项包括真实气象源接入，以及气象历史回放 / 导出 / 多源融合
- [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) `environment_context` 段：Step 1 发版时扩展 `weather` 字段与 `active_alerts` 枚举；Step 2 如引入 `weather_zones`，需同步补充区域天气契约与兼容性说明
- [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §3.2：顶层 `environment_context` 结构变更时双边同步
- [`../agent/AGENT_LOOP_PLAN.md`](../agent/AGENT_LOOP_PLAN.md) §3.8：Step 4 完成后将 `GetWeatherContextTool`、`EvaluateManeuverWithWeatherTool` 纳入工具目录真值
- [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)：Step 1 新增 MQTT `usv/Weather` 订阅链路时在"数据来源"段补一条；Step 2 若完成区域化天气求值，需补充 simulator zone payload 与后端 `effective weather` 解算关系

---

## 6. 依赖与对外关系

- **上游依赖**：simulator 新增 weather publisher、后端现有 MQTT 订阅框架、`RiskObjectMetaAssembler` 注入点
- **下游消费方**：前端天气展示入口（Step 1 / Step 2）、`RiskAssessmentEngine`（Step 3）、`LlmRiskContext` / agent `AgentToolRegistry`（Step 4）与 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md) 承接的增强天气视觉效果
- **跨 track 关系**：与水文 track 共享 `environment_context` 顶层结构与 `EnvAlertCode` 枚举，不共享实现、不共享刷新链路
