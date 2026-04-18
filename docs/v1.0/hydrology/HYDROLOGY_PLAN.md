# v1.0 Hydrology Track — 总览规划

> 文档状态：active
> 最后更新：2026-04-17
> 用途：v1.0 水文 track 的方向判断、范围收敛与 step 拆分的中间层规划文档。
> 非目标：不是 step-plan 实施细则；不替代 [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)、[`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) 等当前真值文档。

---

## 1. 当前系统现状（工程视角）

### 1.1 已有水文能力

水文能力并非从零起步。以下基础链路已在线：

- **S-57 ENC 数据层**：后端 PostGIS 持有 `enc_lndare / enc_depare / enc_coalne / enc_soundg` 与未纳入渲染的 `enc_obstrn`（已在白名单 [`S57TileRepository.java:25-31`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L25-L31) 但 composite tile 未拼装）。
- **MVT 瓦片服务**：[`S57Controller.java:32`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java#L32) 暴露 `/api/s57/tiles/{z}/{x}/{y}.pbf?safety_contour=…`；composite tile 已能在单次响应里拼装 `DEPARE / LNDARE / COALNE / DEPCNT / SOUNDG`。
- **前端渲染**：[`MapContainer.tsx:119`](../../../frontend/src/components/Map/MapContainer.tsx#L119) 直接加载 [`layerStyles.ts:74`](../../../frontend/src/config/layerStyles.ts#L74) 中静态定义的 `s57Sources / s57Layers`，按水深分带（deep `#b3d9ff` / medium `#80bfff` / shallow `#ffcccc`）平铺填充，DEPCNT 虚线、SOUNDG 数字标签、COALNE 海岸线已出图。Step 1 的改造点是 `layerStyles.ts` 的 source/layer 定义与 `MapContainer.tsx` 的图层控制逻辑。
- **安全等深线参数**：`environment_context.safety_contour_val` 默认 10 m（[`RiskObjectMetaProperties.java:13`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/config/RiskObjectMetaProperties.java#L13)），通过 `RiskObjectMetaAssembler.java:46` 注入 SSE payload，前端在 [`MapContainer.tsx:80`](../../../frontend/src/components/Map/MapContainer.tsx#L80) 消费并写入瓦片 URL 参数。**这是当前唯一从 `environment_context` 联动到视觉层的字段**。

### 1.2 空白项

以下是 v1.0 水文 track 要补齐的边界：

- **视觉层停留在 2D flat 填充**。水深分带是平铺色块，没有 fill-extrusion / 体积感 / 光晕；危险浅区仅靠浅水色区分，没有专题高亮。该缺口已由本 track 的 Step 1 承接，需要在 `v1.0` 内交付。
- **`enc_obstrn` 未出图**：障碍物表已存在且白名单允许（[`S57TileRepository.java:24-31`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L24-L31)），但实现缺口分散于四处：`getTableName()` 无 `OBSTRN` 映射（[`S57TileRepository.java:34-42`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L34-L42)）、`buildMultiLayerSQL` composite tile 未拼装（[`S57TileRepository.java:249-307`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L249-L307)）、前端无 source 定义（[`layerStyles.ts:74-105`](../../../frontend/src/config/layerStyles.ts#L74-L105)）、前端无 layer 定义（[`layerStyles.ts:247-256`](../../../frontend/src/config/layerStyles.ts#L247-L256)）。Step 1 需覆盖后端 table 映射、SQL 拼装与前端 source/layer 配置，是 Step 1 中工作量最大的单项。
- **风险引擎未消费水文**：[`RiskAssessmentEngine.consume`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java#L42) 签名只接收 `ShipStatus / CPA / Domain / CvPrediction / Encounter`，没有水文参数。本船进入浅区或 OBSTRN 邻近时风险分不变。
- **LLM 未消费水文**：`LlmRiskContext`（[`LlmRiskContext.java`](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/dto/LlmRiskContext.java)）只有 `ownShip` 与 `targets` 字段，没有 hydrology 结构；agent loop 的 `AgentToolRegistry` 也无水文查询工具。
- **`environment_context.active_alerts` 永远是空列表**（[`RiskObjectMetaAssembler.java:47`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject/RiskObjectMetaAssembler.java#L47) 硬编码 `List.of()`），浅区/障碍物告警没有载体。

---

## 2. Track 方向与边界

### 2.1 Track Goal

水文 track 在 v1.0 内交付三件事，对应用户提出的三项终态：

1. **前端 2.5D 精美渲染**：`fill-extrusion` 水深负高度 + 危险浅区体积发光 + `enc_obstrn` 专题符号 + 交互式 safety contour 调节
2. **风险引擎接入**：水文 geofence 惩罚项并入 `RiskAssessmentEngine`，浅区/障碍物邻近性修正 `riskScore`
3. **LLM 接入**：通过 agent loop 的 `QueryBathymetryTool` 提供可审计的水文事实，供 advisory `evidence_items` 引用

### 2.2 Release impact

水文 track 不阻塞 `v1.0` 主版本完成（主版本收口以 agent 主线为准）。Step 1–3 已属于当前规划链，因而不写入 [`../../TODO.md`](../../TODO.md)；若 `v1.0` 关闭时 Step 2 / Step 3 仍未完成，应先迁移到后续 milestone / step 链，只有失去明确 owner 的剩余项才回收至 TODO。

### 2.3 Non-goals

- 不扩展到 S-101 / IHO B-100 新一代标准
- 不接入潮汐时变、实时水位或水深动态刷新
- 不重做 S-57 数据导入链路（ENC 解析已在仓库外预处理到 PostGIS，不在本 track 范围）
- 不把全部水深要素直接压进 `RISK_UPDATE`，只注入经服务端裁剪的摘要字段

---

## 3. 关键设计决策

### 3.1 数据源锁定在 PostGIS `enc_*`

本 track 不再新增数据源。所有水文消费方（engine / LLM / 前端）最终读同一份 PostGIS 表，通过两条路径访问：

- **前端**：继续走 MVT tile（`/api/s57/tiles/...pbf`），延迟由瓦片缓存吸收
- **engine / LLM**：走新建的 `HydrologyContextService`（Step 2），直接对 PostGIS 做 ST_DWithin / ST_Distance 查询，结果进入 `AgentSnapshot` 与 `environment_context`

两条路径同源，避免 tile 与 context 不一致。

### 3.2 EnvironmentContext 扩展契约（跨 track 共享）

v1.0 引入 `environment_context.hydrology` 子字段。**此契约与天气 track 共享同一个 `environment_context` 顶层节点**，任何一方修改顶层结构必须同步 [`../weather/WEATHER_PLAN.md`](../weather/WEATHER_PLAN.md) §3.2。

```json
"environment_context": {
  "safety_contour_val": 10.0,                 // 现有
  "active_alerts": ["SHOAL_PROXIMITY"],        // 现有字段，本 track 首次填充
  "hydrology": {                               // 本 track 新增
    "own_ship_min_depth_m": 8.3,
    "nearest_shoal_nm": 0.42,
    "nearest_obstruction": {
      "category": "WRECK",
      "distance_nm": 0.71,
      "bearing_deg": 037
    }
  }
}
```

字段约束：

- 只注入"本船位置 + 一跳邻近"的摘要，不下发完整几何
- 缺字段用 `null` 而不是 `0`，避免与真实零读数混淆
- 不直接下发 OBSTRN 列表；完整几何仅通过 MVT tile 或 Step 3 的 agent tool 访问

### 3.3 `active_alerts` 语义化约定（跨 track 共享）

定义枚举 `EnvAlertCode`，本 track 贡献项：

注：该枚举已由 weather track Step 1 首次写入天气类告警；hydrology 在后续 step 仅做追加，不重定义顶层结构。

- `SHOAL_PROXIMITY`：本船在 `safety_contour_val` 内或紧邻浅区
- `OBSTRUCTION_NEARBY`：距离已知 OBSTRN `< 0.5 nm`（阈值可配）
- `DEPTH_DATA_MISSING`：查询点位落在 ENC 覆盖范围外

后端 `HydrologyContextService` 在注入 `environment_context.hydrology` 的同时向 `active_alerts` 追加 code。天气 track 贡献 `LOW_VISIBILITY / STRONG_CURRENT_SET` 等，两方共用同一数组。

### 3.4 2.5D 视觉升级方向

目标是让海图从“可用”提升为具备明确展示效果的专题视图，**而不是简单增加图层开关**。具体手段：

- **水深拔深**：`enc_depare` 从 `fill` 改为 `fill-extrusion`，`drval1` 深值映射到负 height（地面下方），营造海底地形感
- **危险浅区体积发光**：`drval1 < safety_contour_val` 的区域叠加 `fill-extrusion-ambient-occlusion` 或发光边缘（参考 deck.gl 已在前端依赖链）
- **OBSTRN 专题符号**：按 `CATOBS` 分类（wreck / rock / pile），用 symbol icon + 红色警戒圈
- **safety_contour 交互滑块**：驾驶台场景下让用户调节安全深度，瓦片 URL 参数联动重拉 tile。当前该值是配置常量，Step 1 要让它变成前端 UI 可控

### 3.5 风险引擎接入形态：geofence 惩罚项

在 `RiskAssessmentEngine` 引入 `hydrologyPenalty`，由 `HydrologyContextService` 查询本船位置得到：

- 本船在 `safety_contour_val` 以浅区域 → `finalRiskScore` 附加 penalty（上限可配）
- 本船距 OBSTRN `< threshold` → penalty 随距离线性衰减

不改 CPA / TCPA / encounter 主干逻辑，只在 `buildTargetAssessment` 之外新增 `computeEnvironmentalPenalty(ownShip)` → 融合进 `finalRiskScore`。**惩罚项独立于目标**，因为"船靠近浅区"不依赖具体目标船。

此项不在目标维度调整 `riskLevel`，避免 SAFE / CAUTION 分类规则被水文因素污染。仅修正 `riskScore` 数值用于排序与 advisory 触发门控。

### 3.6 LLM 接入形态：agent tool，不走静态 context

本 track **不**把水文结构写进 `LlmRiskContext`。理由：

- 水文信息高度空间局部化，只有在具体查询（"我准备右转 20 度，避让方向水深如何？"）时才有价值；静态注入会稀释风险上下文密度
- agent loop 已在 AGENT_LOOP_PLAN §3.8 约定了 evidence_items 的事实提供方式

本 track 贡献 agent 工具：

- `QueryBathymetryTool(lon, lat, radius_nm)` → `{ min_depth_m, nearest_shoal_nm, obstructions: [...] }`
- `EvaluateManeuverHydrologyTool(course_change_deg, lookahead_min)` → 评估假设机动路径是否穿越浅区（供 `EvaluateManeuverTool` 的水文维度补全）

---

## 4. Step 拆分

### Step 1：2.5D 视觉升级 + OBSTRN 出图 + safety contour 交互

**状态**：已完成（2026-04-18）

**目标**：在现有 MVT 链路上完成前端专题视觉升级，并让 `enc_obstrn` 首次可见。

**主要工作**：

- 后端：`getTableName()` 补充 `OBSTRN` 映射（[`S57TileRepository.java:34-42`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L34-L42)）；`buildMultiLayerSQL`（[`S57TileRepository.java:249-307`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L249-L307)）补充 `OBSTRN` 图层拼装
- 后端：`S57Controller.generateCompositeTile()` 的 fallback layer 列表同步追加 `OBSTRN`，避免 composite 查询异常时障碍物整层消失
- 前端：Step 1 保持现有单图层 vector source 渲染链路，不切换到 composite tile；[`layerStyles.ts`](../../../frontend/src/config/layerStyles.ts) 新增 `enc_obstrn` VectorSource 与 symbol layer 定义（含 `CATOBS` 分类 icon 与红色警戒圈）
- `enc_depare` 从 `fill` 升级到 `fill-extrusion`，深值负高度映射
- 危险浅区（`drval1 < safety_contour_val`）体积发光层
- 前端新增 safety contour 滑块 UI（[`MapContainer.tsx`](../../../frontend/src/components/Map/MapContainer.tsx) 状态上提），采用“本地 override + 恢复实时值”语义；滑动时瓦片 URL 变化触发重拉
- 视觉不得压制 ALARM / WARNING 告警符号：专题渲染层级应在风险图层之下

**验收**：`enc_obstrn` 中至少一个已知障碍物可见；safety contour 滑动在 3 秒内刷新瓦片；危险浅区在 2.5D 视角下有明显体积区分

### Step 2：`HydrologyContextService` + `environment_context.hydrology` 注入

**目标**：把水文信息从"只有前端知道"升级为"后端管线级可消费"。

**主要工作**：

- 新建 `com.whut.map.map_service.chart.service.HydrologyContextService`，复用 `JdbcTemplate` 对 `enc_depare / enc_obstrn` 做 ST_DWithin 查询
- 把 Step 1 的 safety contour slider 值写回后端，收敛为服务端可见配置，并作为 `HydrologyContextService` 查询输入
- 扩展 `RiskObjectMetaAssembler` 把 hydrology 摘要注入 `environment_context`
- 至少一条 LLM 可消费上下文链路接入该值：优先进入 `environment_context.hydrology` 并被 LLM 消费，或等价进入 agent / LLM 上下文摘要
- 填充 `active_alerts`：`SHOAL_PROXIMITY / OBSTRUCTION_NEARBY / DEPTH_DATA_MISSING`
- 查询结果按本船位置缓存（同一 RiskObject 内多次调用只查一次）
- 前端 `[useRiskStore.ts:83](../../../frontend/src/store/useRiskStore.ts#L83)` 的 `environment` 类型扩展 `hydrology` 可选字段

**验收**：本船进入 `safety_contour_val` 以浅区时，SSE `RISK_UPDATE` 的 `environment_context.hydrology.own_ship_min_depth_m` 正确反映；`active_alerts` 包含 `SHOAL_PROXIMITY`

### Step 3：风险引擎消费 + agent tool

**目标**：让水文真正影响 `riskScore` 与 agent advisory 的可验证依据。

**主要工作**：

- `RiskAssessmentEngine` 新增 `environmentalPenalty` 组合项（独立于目标维度的 `finalRiskScore` 修正）
- 注册 agent 工具 `QueryBathymetryTool` 与 `EvaluateManeuverHydrologyTool`（见 §3.6）
- advisory prompt 中允许 `evidence_items` 引用水文事实项，但需显式来源标记（`source: hydrology`）
- 配置开关：`risk.hydrology.penalty.enabled` 默认关，灰度放量

**验收**：启用惩罚项后，本船进入浅区的 `riskScore` 增加；agent loop 能调用 `QueryBathymetryTool` 并在 advisory `evidence_items` 里出现水深事实项

---

## 5. 风险、取舍与跨 track 同步

### 5.1 ST_DWithin 查询性能

`enc_depare / enc_obstrn` 单帧查询在密集 tick 下可能成为瓶颈。Step 2 实施时必须：

- 以本船 `risk_object_id` 为 key 做 per-frame 去重（一帧只查一次）
- 查询字段只返回必要属性（`drval1`, `geometry` 的最近点投影），不拉完整 geometry

若性能不达标，降级为 in-memory 预加载一次 ENC 极简索引（bbox），用 JTS 本地查询。

### 5.2 2.5D 渲染与现有交互冲突

`fill-extrusion` 会改变 pitch > 0 下的点击命中区域，可能影响 `TargetsPanel` 的目标点选。Step 1 必须回归测试：点击目标船、拖拽地图、缩放在 pitch 30°/60° 下仍工作。

若出现冲突，降级策略：`fill-extrusion` 仅在 pitch > 45° 时启用，平视保持 flat fill。

### 5.3 前端渲染链路的后续收敛

Step 1 明确保持现有单图层 vector source 方案，以控制改动面。若后续需要统一切到 composite tile 作为前端主渲染链路，应在独立后续事项中完成，不与 Step 1 的可见化目标混做。

### 5.4 `active_alerts` 与天气 track 的约束

两条 track 共写 `active_alerts` 数组。**共享约束**：

- 每条 alert 必须是 `EnvAlertCode` 枚举值，不得自由文本
- 枚举定义单点放置于 `com.whut.map.map_service.shared.domain.EnvAlertCode`，两 track 同时 import
- 追加顺序无保证（不得依赖）

### 5.5 需要同步更新的真值文档

以下文档在 Step 实施前需同步更新：

- [`../../TODO.md`](../../TODO.md) 仅跟踪未挂入 Step 1–3 的后续 backlog；hydrology 主线事项不重复记录于 TODO。当前已回收到 TODO 的未挂载项包括 composite tile 主渲染链路收敛、`FAIRWY` / `RESARE` 出图与 `DEPCNT` 深值标签补全
- [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) `environment_context` 段：Step 2 发版时扩展 `hydrology` 字段与 `active_alerts` 枚举
- [`../weather/WEATHER_PLAN.md`](../weather/WEATHER_PLAN.md) §3.2：顶层 `environment_context` 结构变更时双边同步
- [`../agent/AGENT_LOOP_PLAN.md`](../agent/AGENT_LOOP_PLAN.md) §3.8：Step 3 完成后将 `QueryBathymetryTool` 纳入工具目录真值

---

## 6. 依赖与对外关系

- **上游依赖**：PostGIS `enc_*` 表（已存在）、MVT tile 服务（已存在）、MapLibre 前端渲染（已存在）
- **下游消费方**：`RiskAssessmentEngine`（Step 3）、agent `AgentToolRegistry`（Step 3，见 AGENT_LOOP_PLAN §3.8）、前端 `MapContainer`（Step 1）、前端 `useRiskStore.environment`（Step 2）
- **跨 track 关系**：与天气 track 共享 `environment_context` 顶层结构与 `EnvAlertCode` 枚举，不共享实现
