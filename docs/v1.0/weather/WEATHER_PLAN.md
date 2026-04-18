# v1.0 Weather Track — 总览规划

> 文档状态：active
> 最后更新：2026-04-17
> 用途：v1.0 天气 track 的方向判断、范围收敛与 step 拆分的中间层规划文档。
> 非目标：不是 step-plan 实施细则；不替代 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)、[`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) 等当前真值文档。

---

## 1. 当前系统现状（工程视角）

### 1.1 天气能力基线：尚未建立

当前仓库中，`weather / visibility / wind / current` 仅出现在 simulator Python 代码中的非气象语义字段，以及 [`../../TODO.md`](../../TODO.md) 的规划条目中。**系统主链路尚未建立天气相关能力**：

- Simulator 仅发布 AIS 报文（`usv/AisMessage`），未提供气象 topic
- 后端无气象 ingest、无 `WeatherContextHolder`、无气象 DTO
- `environment_context` 仅携带 `safety_contour_val` + `active_alerts`（后者是空列表，[`RiskObjectMetaAssembler.java:47`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject/RiskObjectMetaAssembler.java#L47) 硬编码）
- `RiskAssessmentEngine.consume` 签名无环境参数，分级与评分完全基于几何
- `LlmRiskContext` 无 weather 字段，prompt 未注入天气语义
- 前端无 weather layer、无 weather state slice

因此，weather track 属于从零起步的专题能力建设。与已有海图与 ENC 基础链路的 hydrology track（参见 [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §1.1）相比，两者的工程起点明显不同，这也是 [`../README.md`](../README.md) 将两条 track 并列而不合并的原因。

### 1.2 可复用的上下游基础设施

尽管 weather track 本身尚无现成实现，但以下基础设施可以复用，从而降低建设成本：

- **MQTT 基础链路**：simulator 已有 `mqtt_publisher_base.py`，后端已有 MQTT 订阅配置（[`source/ais/config/`](../../../backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config) 与 `source/ais/mqtt/`）；增加一个 `usv/Weather` topic 是对现有模式的复用，不是新建 transport 层
- **`environment_context` 注入点**：[`RiskObjectMetaAssembler.buildEnvironmentContext`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject/RiskObjectMetaAssembler.java#L46) 是单一注入点，本 track 可在此基础上完成扩展
- **前端 `environment` 消费点**：[`useRiskStore.ts:83`](../../../frontend/src/store/useRiskStore.ts#L83) 已把 `environment_context` 原封接入 state，状态入口可复用；但 [`schema.d.ts:133-136`](../../../frontend/src/types/schema.d.ts#L133-L136) 的 `EnvironmentContext` 类型仅定义 `safety_contour_val` 与 `active_alerts` 两个字段，weather / hydrology 一旦进入 SSE，`schema.d.ts`、测试夹具和前端消费代码均需同步扩展
- **Agent 工具注册框架**：agent loop 已约定 `AgentToolRegistry`（AGENT_LOOP_PLAN §3.8），本 track 注册新工具是标准扩展点

---

## 2. Track 方向与边界

### 2.1 Track Goal

天气 track 在 v1.0 内交付三件事，对应用户提出的三项终态：

1. **风险引擎接入**：能见度缩放 CPA 有效阈值；高海况条件下引入额外 weather penalty；强流对动作建议裕量产生约束
2. **LLM 接入**：agent loop 通过 `GetWeatherContextTool` 消费当前气象事实，advisory `evidence_items` 可引用"能见度不足、建议减速"等机动建议
3. **前端 2.5D 精美渲染**：低能见度雾化 overlay、降雨粒子层、风场动态箭头、水流矢量，在不压制主 HUD 的前提下交付演示级视觉冲击

### 2.2 Release impact

天气 track 不阻塞 `v1.0` 主版本完成（主版本收口以 agent 主线为准）。若 Step 2 / Step 3 未在 `v1.0` 内收口，未完项回收到 [`../../TODO.md`](../../TODO.md)。

### 2.3 Non-goals

- 不接入真实气象 API（OpenWeatherMap / NOAA / ECMWF）；相关许可与精度评估不在 v1.0 范围
- 不做气象数据的时空插值、预报或同化
- 不构建天气知识图谱或天气专用 GraphRAG（与 agent track 的 COLREGS GraphRAG 解耦）
- 不把气象源扩展到 HTTP 拉取或文件加载，统一走 MQTT（保持输入模型单一）

---

## 3. 关键设计决策

### 3.1 输入源：simulator 独立 MQTT topic

v1.0 选择 **simulator 新增 `usv/Weather` topic → 后端订阅 → `shared.context.WeatherContextHolder` → `environment_context.weather`** 链路。

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

v1.0 引入 `environment_context.weather` 子字段。**此契约与水文 track 共享同一个 `environment_context` 顶层节点**，任何一方修改顶层结构必须同步 [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §3.2。

```json
"environment_context": {
  "safety_contour_val": 10.0,                 // 现有
  "active_alerts": ["LOW_VISIBILITY"],         // 现有字段，本 track 首次填充
  "weather": {                                 // 本 track 新增
    "weather_code": "FOG",                     // CLEAR / FOG / RAIN / SNOW / STORM
    "visibility_nm": 0.8,
    "precipitation_mm_per_hr": 0.0,
    "wind": { "speed_kn": 12.0, "direction_from_deg": 225 },
    "surface_current": { "speed_kn": 1.4, "set_deg": 090 },
    "sea_state": 4,                            // Beaufort-like 0..9
    "updated_at": "2026-04-17T10:22:15Z"
  }
}
```

字段约束：

- 所有子字段可选，模拟器可按场景只填部分；缺字段用 `null`
- `wind.direction_from_deg`、`surface_current.set_deg` 遵守航海惯例（风向=风的来向，流向=流的去向）
- `visibility_nm` 单位与 CPA 体系一致（nm），避免引擎消费时二次换算
- 不在 payload 里下发气象原始时序，只下发当前帧快照

### 3.3 `active_alerts` 语义化约定（跨 track 共享）

本 track 贡献项：

- `LOW_VISIBILITY`：`visibility_nm < 2.0`（阈值可配）
- `HIGH_WIND`：`wind.speed_kn > 25`
- `HEAVY_PRECIPITATION`：`precipitation_mm_per_hr > 10`
- `STRONG_CURRENT_SET`：`surface_current.speed_kn > 2.5`

与水文 track 共用同一枚举 `EnvAlertCode`（定义点 [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §3.3）。

### 3.4 风险引擎接入形态：标量修正 + 独立 penalty

**与水文 track 的 geofence 形态不同**：水文是"区域级约束"，天气是"全局标量修正"。

具体三条独立修正路径：

- **visibility → effective threshold 缩放**：能见度不足时，COLREGS 规则 19 要求"以安全航速"，对应工程实现是把 `cautionDcpaNm / warningDcpaNm / alarmDcpaNm` 按能见度系数线性放大。`visibility_nm < 2.0` 时系数 1.5x，`< 0.5` 时 2.0x
- **surface_current → 动作建议置信度修正**：当前 `environment_context.weather.surface_current` 是单个全局流矢量，同时作用于本船与目标船时相对速度不变，CPA/TCPA 不会因此改变，直接将该字段用于 CPA 修正在当前建模下不成立。正确语义是：强流环境下机动指令的执行误差增大（如计划右转 20 度、实际因流场偏出 3–5 度），体现在 advisory `evidence_items` 中为"当前流速 1.4 节，建议机动量适当加大裕量"，而非修正预测几何。[`RiskAssessmentEngine.java:135`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java#L135) 已有的微调路径在此场景下不适用
- **weather penalty**：`weather_code == STORM` 或 `sea_state >= 7` 时，独立的 `environmentalWeatherPenalty` 并入 `finalRiskScore`（与水文 penalty 并列，不共享计算）

实施约束：

- 三条路径默认关，由 `risk.weather.*.enabled` 配置项分别开启，灰度放量
- 不改 `classifyRisk` 的阈值硬编码；通过 per-frame 计算"生效阈值"传入替代，保持规则侧纯净
- 不在 `TargetRiskAssessment.ruleExplanation` 里把天气推理文本拼进去；天气修正的解释路径走 LLM

### 3.5 LLM 接入形态：static context + agent tool 双通道

**与水文 track 的"只走 agent tool"不同**：天气是"全局状态"而非"空间局部状态"。LLM 在每次推理时几乎都需要知道当前能见度、风、流，因此天气需要进入 `LlmRiskContext` 的静态注入：

- `LlmRiskContext` 扩展 `weather: LlmRiskWeatherContext` 可选字段
- `RiskContextFormatter` 生成一段简短的"当前气象：能见度 0.8 nm，有雾；风 SW 12 节；水流 090 1.4 节"
- 同时注册 agent 工具 `GetWeatherContextTool()` → 返回完整结构体，用于 LLM 需要精确数值时

advisory `evidence_items` 在 `weather_code != CLEAR` 场景下应包含至少一条气象事实项（prompt 约束）。

### 3.6 2.5D 视觉升级方向

目标是形成具备明确展示效果的专题视觉表达，包含三类独立视觉元素：

- **低能见度 fog overlay**：全屏半透明灰白 canvas 层，`visibility_nm` 越低 opacity 越高；在最低能见度场景下，可见距离收缩至本船周边数倍船长范围
- **降雨粒子层**：deck.gl / Three.js 粒子系统，密度绑定 `precipitation_mm_per_hr`；粒子方向根据风向倾斜
- **风场箭头**：在海图空白处均匀分布的动态箭头，长度绑定风速，方向绑定 `wind.direction_from_deg`
- **水流矢量**：沿海岸与航道用蓝色箭头表达 `surface_current`，动画速率 = `speed_kn`

**视觉优先级硬约束**：ALARM / WARNING 符号必须在所有天气 overlay 之上保持可见。fog overlay 的 mask 需位于风险图层之下。

### 3.7 与 agent `EvaluateManeuverTool` 的协作

agent track 的 `EvaluateManeuverTool`（AGENT_LOOP_PLAN §3.8）当前只评估 CPA 几何。本 track 贡献扩展输入：

- `EvaluateManeuverWithWeatherTool(course_change_deg, speed_change_kn, lookahead_min)` → 评估在当前气象下该机动的"可行性"（能见度不足时是否建议减速幅度、强流下是否需要加大 course change）

此工具由 agent 从 `AgentSnapshot.riskContext.weather` 读取，不与水文工具合并。

---

## 4. Step 拆分

### Step 1：MQTT topic + 后端承载 + 前端视觉基础

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

### Step 2：风险引擎消费天气

**目标**：让天气因素以受控方式修正风险分级与评分。

**主要工作**：

- `RiskAssessmentEngine.consume` 签名扩展接收 `WeatherContext` 或通过构造函数注入 `shared.context.WeatherContextHolder`
- 实现 §3.4 中与引擎直接相关的两条修正路径（visibility 阈值缩放 / storm penalty）；强流约束保留为 advisory 与机动评估层消费，不直接进入当前 CPA 修正链路
- 引入 `risk.weather.*.enabled` 配置项，默认全关
- 回归测试：关闭全部开关时，所有现有单测必须保持绿色，且行为不发生变化

**验收**：

- `--scene fog` + `visibility.enabled=true` 下，同一目标在能见度 0.8 nm 时 `riskLevel` 比晴天提前一级
- 所有开关关闭时与 Step 1 完成后行为保持一致

### Step 3：LLM static context + agent tool + advisory 消费

**目标**：让 advisory 在低能见度等场景下给出受气象约束的机动建议。

**主要工作**：

- `LlmRiskContext` 新增 `weather` 字段，`LlmRiskContextAssembler` 填充
- `RiskContextFormatter` 追加气象段落（仅当 `weather_code != CLEAR` 或 alerts 非空时注入，避免稀释 prompt 密度）
- 注册 agent 工具 `GetWeatherContextTool`、`EvaluateManeuverWithWeatherTool`
- advisory prompt 约束：`weather_code != CLEAR` 时 `evidence_items` 至少包含一条气象事实
- 前端 advisory card 支持气象事实项的 icon 展示（fog / rain / wind 三图标）

**验收**：

- `--scene fog` 下触发的 advisory `evidence_items` 含 "能见度 0.8 nm，低于 2.0 nm 阈值" 事实项
- agent 能调用 `GetWeatherContextTool` 并得到正确数值

---

## 5. 风险、取舍与跨 track 同步

### 5.1 MQTT 丢包与陈旧气象

气象是时变信号，网络抖动可能让最后一次更新停留数十秒。约束：

- `shared.context.WeatherContextHolder` 持有 `updated_at`；下游消费时若 `now - updated_at > staleThresholdSec`（默认 60s）视为不可用
- 不可用时 `environment_context.weather = null`（完整缺失），而不是填 `CLEAR`
- 引擎与 LLM 在 `weather == null` 时的行为：引擎退化为无气象修正；LLM prompt 不注入气象段

### 5.2 引擎修正的反直觉行为

visibility 缩放 caution 阈值会让同一目标在雾天"更早"跳 CAUTION。风险分级陡然提前可能让用户误认为系统报警异常。缓解：

- 默认开关关，开启时必须在 `StatusPanel` 显示"气象修正已启用"
- 解释文本（LLM 路径）必须显式说明"由于能见度不足，CAUTION 阈值已由 1.5 nm 放宽至 2.25 nm"
- 本条在 Step 2 实施时作为验收点之一

### 5.3 2.5D 粒子层性能

密集降雨/雪粒子层在中端设备上可能显著拉低 FPS。约束：

- 粒子数量上限 5000（可配）
- 检测 FPS < 45 连续 3 秒时自动降级（减半粒子或切回简化 CSS 动画）
- 本项验收在 Step 1 与 Step 3 视觉上线时分别做

### 5.4 需要同步更新的真值文档

- [`../../TODO.md`](../../TODO.md) 第 2 节"环境语义模型（天气 / 能见度 / 风流）接入与消费"：Step 3 完成后从 TODO 移除
- [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) `environment_context` 段：Step 1 发版时扩展 `weather` 字段与 `active_alerts` 枚举
- [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §3.2：顶层 `environment_context` 结构变更时双边同步
- [`../agent/AGENT_LOOP_PLAN.md`](../agent/AGENT_LOOP_PLAN.md) §3.8：Step 3 完成后将 `GetWeatherContextTool`、`EvaluateManeuverWithWeatherTool` 纳入工具目录真值
- [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)：Step 1 新增 MQTT `usv/Weather` 订阅链路时在"数据来源"段补一条

---

## 6. 依赖与对外关系

- **上游依赖**：simulator 新增 weather publisher、后端现有 MQTT 订阅框架、`RiskObjectMetaAssembler` 注入点
- **下游消费方**：`RiskAssessmentEngine`（Step 2）、`LlmRiskContext` / agent `AgentToolRegistry`（Step 3）、前端 fog / particle / wind / current 层（Step 1, 3）
- **跨 track 关系**：与水文 track 共享 `environment_context` 顶层结构与 `EnvAlertCode` 枚举，不共享实现、不共享刷新链路
