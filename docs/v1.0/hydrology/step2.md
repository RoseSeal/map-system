# Hydrology Step 2：`HydrologyContextService` + `environment_context.hydrology` 注入

> 文档状态：active
> 最后更新：2026-04-28
> 执行状态：planned
> 所属 track：[`HYDROLOGY_PLAN.md`](./HYDROLOGY_PLAN.md)
> 目标：把水文信息从“仅前端渲染可见”收敛为“服务端风险快照可见”，并让 safety contour 从前端本地 override 收敛为后端可见的运行时配置。

---

## 1. Summary

Step 2 不修改 `RiskAssessmentEngine` 的评分逻辑，也不让 LLM 直接消费水文。其职责是先完成“事实进入风险快照”的中间层闭环：

1. 后端基于本船位置查询 `enc_depare / enc_obstrn`
2. 结果以 `environment_context.hydrology` 形式进入 SSE `RISK_UPDATE`
3. `active_alerts` 首次承载水文告警
4. safety contour 不再只是前端本地 override，而是收敛为服务端可见的运行时值

本步骤完成后，前端、风险引擎与后续 agent tool 都读同一份 hydrology 摘要事实；真正的评分修正与 advisory 消费留给 Step 3。

---

## 2. Current State And Step Delta

当前代码状态已经决定了 Step 2 不能只”新增一个 service”：

- [`RiskObjectMetaAssembler`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject/RiskObjectMetaAssembler.java) 目前只注入 `safety_contour_val / active_alerts / weather / weather_zones`，`buildEnvironmentContext(ShipStatus ownShip)` 已接收 `ownShip`（weather track Step 2 为 zone 解析引入），但无 hydrology 查询挂点
- [`RiskObjectAssembler`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/RiskObjectAssembler.java) 已将 `ownShip` 传入 `buildEnvironmentContext`，但 hydrology 查询尚未在装配链中触发
- 前端 safety contour slider 仍停留在 [`useMapSettingsStore.ts`](../../../frontend/src/store/useMapSettingsStore.ts) 的本地状态，后端只提供只读的 [`S57Controller.getSafetyContour`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java#L200) 回显接口，没有写回能力
- `active_alerts` 当前由 weather 以字符串字面量填充，`EnvAlertCode` 尚未真正落地

因此 Step 2 的真实改动面包括：`buildEnvironmentContext` 签名追加 hydrology 参数、hydrology 查询服务、运行时 safety contour 状态、共享告警枚举、前端类型与 slider 写回链路，而不是单点补字段。

---

## 3. In Scope

### 3.1 后端水文上下文查询

- 新建 `com.whut.map.map_service.chart.service.HydrologyContextService`
- 新建 `com.whut.map.map_service.chart.dto.HydrologyContext` 及 `NearestObstructionSummary`（record 即可）
- 查询输入为：
  - 本船经纬度
  - 当前生效的 `safety_contour_val`
- 查询输出为：
  - `own_ship_min_depth_m`
  - `nearest_shoal_nm`
  - `nearest_obstruction`
- `HydrologyContextService` 只返回“本船点位 + 一跳邻近”摘要，不返回完整几何

### 3.2 风险快照装配链收敛

- `RiskObjectAssembler` 在组装 `RiskObjectDto` 时显式解析一次 hydrology 摘要，再传给 `RiskObjectMetaAssembler`
- `RiskObjectMetaAssembler.buildEnvironmentContext(...)` 在现有 `ownShip` 参数基础上追加：
  - 生效 safety contour 值（`double effectiveSafetyContourMeters`）
  - hydrology 摘要（`HydrologyContext hydrologyContext`）
- `environment_context` 扩展 `hydrology` 子字段，并在同一注入点继续承载 `weather`

### 3.3 safety contour 运行时写回

- 后端新增可写入口，使 Step 1 slider 不再停留于浏览器本地 override
- 运行时安全等深线值必须同时服务两类消费方：
  - MVT `/api/s57/tiles/...pbf?safety_contour=...`
  - `HydrologyContextService` 的浅区/邻近查询
- 前端 slider 交互调整为“写回后端 + 跟随 SSE 回显”，而非长期维持本地真值

### 3.4 前端协议与消费补齐

- [`schema.d.ts`](../../../frontend/src/types/schema.d.ts) 扩展 `EnvironmentContext.hydrology`
- [`useRiskStore.ts`](../../../frontend/src/store/useRiskStore.ts) 维持原有 `environment_context` 原封接入，不新增第二份 hydrology store
- Step 1 已存在的 slider 入口继续复用，但数据来源改为服务端生效值
- 测试夹具与前端消费类型同步更新，避免 hydrology 字段进入 SSE 后仍被视为 unknown payload

---

## 4. Out of Scope

- deferred：`RiskAssessmentEngine` 基于浅区 / 障碍物修正 `riskScore`，转 Step 3
- deferred：LLM static context 注入，转 Step 3；本 track 仍保持“水文不进入 `LlmRiskContext`”的总方向
- deferred：agent tool `QueryBathymetryTool` / `EvaluateManeuverHydrologyTool`，转 Step 3
- not doing：每客户端独立 safety contour 真值；在当前单路 SSE 广播模型下，Step 2 只支持全局运行时值
- not doing：完整 OBSTRN 列表、完整 geometry 或航线级扫描结果直接进入 `environment_context`
- not doing：`FAIRWY` / `RESARE` / composite tile 主渲染链路收敛；这些仍留在当前 hydrology step 链之外

---

## 5. Key Decisions

### 5.1 safety contour 在 Step 2 收敛为全局运行时值

Step 1 的本地 override 适合纯前端视觉演示，但不适合服务端水文查询。当前 `RISK_UPDATE` 是单路广播快照，`environment_context.safety_contour_val` 也是全局字段；若继续保留“每个浏览器各自 contour 值”，则同一时刻前端看到的浅区高亮与后端发出的 `hydrology` / `active_alerts` 将失去一致性。

因此 Step 2 明确采用：

- 后端持有单一运行时 safety contour 状态
- 前端 slider 修改该状态
- SSE 回显该状态，前端再跟随服务端值

这意味着 Step 1 的“长期本地 override 语义”在 Step 2 结束。

### 5.2 `HydrologyContextService` 返回摘要，不承担帧级缓存

Step 2 不在 service 内增加独立缓存层。原因是当前 hydrology 只在 `RiskObjectDto` 组装阶段消费一次；若再在 service 内维护位置缓存，会引入失效语义与阈值一致性负担，但收益有限。

本步骤采用更直接的收敛方式：

- 每次组装 `RiskObjectDto` 时只解析一次 hydrology 摘要
- 同一帧内通过显式参数传递复用该结果
- 后续若 Step 3 需要 engine / agent tool 复用，可再演进为显式 `HydrologyFrameContext`

### 5.3 `active_alerts` 在本步骤补齐共享枚举

当前 weather 已写入 `LOW_VISIBILITY` 等 alert，但实现仍是字符串字面量。Step 2 需要把这一隐含契约显式化，否则 hydrology 新增 `SHOAL_PROXIMITY / OBSTRUCTION_NEARBY / DEPTH_DATA_MISSING` 后，跨 track 告警会继续停留在“字符串碰运气”状态。

因此本步骤同步引入：

- `com.whut.map.map_service.shared.domain.EnvAlertCode`
- weather 现有 alerts 迁移为枚举值输出
- hydrology alerts 以同一枚举追加

序列化行为：`active_alerts` 以 `EnvAlertCode.name()` 字符串追加到 list，不引入枚举数字编码，不需要额外 `@JsonValue`（当前路径为 `Map<String, Object>` 直接 put 字符串值，与前端协议一致）。

### 5.4 水文事实先进入 `environment_context`，不提前接入 LLM

Hydrology 总览已经确定“水文的深度消费走 Step 3 agent tool”。Step 2 只负责把 hydrology 事实变成服务端真值，不在此阶段扩展 `LlmRiskContext` 或 `RiskContextFormatter`，避免与 track 既定方向冲突。

---

## 6. Detailed Design

### 6.1 `HydrologyContextService` 查询语义

`HydrologyContextService` 至少完成三项查询：

1. **本船所在 depth area**
   - 基于本船点位查询命中的 `enc_depare`
   - 输出 `own_ship_min_depth_m = DRVAL1`
   - 若未命中 ENC 覆盖，返回 `null` 并触发 `DEPTH_DATA_MISSING`

2. **最近浅区距离**
   - 查询 `DRVAL1 < effectiveSafetyContourVal` 的 `enc_depare`
   - `nearest_shoal_nm` 表示本船所在点到满足条件的最近浅区区域边界的距离（nm）
   - 若本船已在该区域内（即本船自身位置的水深即为浅区），则距离为 `0`，不用 `null` 伪装
   - 否则返回本船点到最近浅区边界的测量距离

3. **最近障碍物摘要**
   - 查询最近一个 `enc_obstrn`
   - 输出 `category / distance_nm / bearing_deg`
   - 当最近障碍物超出配置阈值时，`nearest_obstruction = null`

SQL 选型原则：

- 所在区域判断优先使用拓扑关系（`ST_Intersects` / `ST_Contains`）
- 邻近性查询使用 `ST_DWithin + ST_Distance`
- 查询字段只取 `DRVAL1 / CATOBS / VALSOU / geometry` 的必要投影，不拉完整属性集

### 6.2 运行时 safety contour 状态

新增轻量运行时状态组件，例如 `SafetyContourStateHolder` 或等价命名服务，职责如下：

- 启动时以 `engine.risk-meta.safety-contour-val` 作为默认值
- 使用 `AtomicReference<Double>` 保证并发安全；并发写入（多个 slider 请求同时到达）采用 last-write-wins 策略，不需要悲观锁
- 提供 `getCurrentDepthMeters()` / `updateDepthMeters(double)` / `resetToDefault()` 接口
- 由 `S57Controller`、`RiskObjectAssembler` 上游装配逻辑与 `HydrologyContextService` 共用

Step 2 不直接修改 `application.properties` 的静态配置，也不把运行时写回持久化到磁盘。

### 6.3 风险对象装配链调整

`RiskObjectMetaAssembler.buildEnvironmentContext` 已接收 `ownShip`（用于 weather zone 解析），但 hydrology 查询尚未纳入装配链。Step 2 将查询步骤前移到 `RiskObjectAssembler`，以避免把 `JdbcTemplate` 或 chart 查询逻辑引入 assembler 内部：

1. `RiskObjectAssembler` 读取运行时 safety contour 值
2. 以 `ownShip` 调用 `HydrologyContextService`，得到 hydrology 摘要
3. 把 hydrology 摘要与 contour 值作为显式参数传给 `RiskObjectMetaAssembler`
4. `RiskObjectMetaAssembler` 只负责：
   - 组装 payload
   - 合并 weather / hydrology alerts
   - 保持 `environment_context` 字段顺序稳定

这样可以避免把 `JdbcTemplate` 或 chart 查询逻辑直接塞入 assembler。

### 6.4 `environment_context` 目标结构

Step 2 发版后的目标结构如下：

```json
“environment_context”: {
  “safety_contour_val”: 12.5,
  “active_alerts”: [“LOW_VISIBILITY”, “SHOAL_PROXIMITY”],
  “weather”: {
    “weather_code”: “FOG”,
    “visibility_nm”: 0.8,
    “precipitation_mm_per_hr”: 0.0,
    “wind”: { “speed_kn”: 3.2, “direction_from_deg”: 225 },
    “surface_current”: { “speed_kn”: 0.4, “set_deg”: 90 },
    “sea_state”: 2,
    “source_zone_id”: “zone-A”,
    “updated_at”: “2026-04-19T03:20:00Z”
  },
  “weather_zones”: [...],
  “hydrology”: {
    “own_ship_min_depth_m”: 8.3,
    “nearest_shoal_nm”: 0.0,
    “nearest_obstruction”: {
      “category”: “WRECK”,
      “distance_nm”: 0.71,
      “bearing_deg”: 37
    }
  }
}
```

字段约束：

- `weather_zones` 与 `weather` 字段由 weather track 引入，Step 2 保持不变
- `hydrology` 整体可为 `null`，仅在查询完全不可用时使用
- 子字段缺失统一用 `null`，不用 `0` 伪装”未知”
- `bearing_deg` 采用 `0..359` 整数（`int`），DTO 与 fixture 统一

### 6.5 前端 slider 写回语义

前端不再把 slider 永久绑定到 `useMapSettingsStore` 的本地 override。Step 2 改为：

- 用户拖动 slider
- 前端经 debounce 调用后端更新接口
- 本地只保留短暂 pending 态，用于避免拖动时 UI 抖动
- 新一帧 SSE 下发的 `environment_context.safety_contour_val` 作为最终真值
- “恢复实时值”按钮改为“恢复默认值”或等价语义，由后端把 contour 重置为启动默认值

若后端更新失败（HTTP 非 2xx 或请求超时），前端丢弃 pending 态，将 slider 回退到最近一次 SSE 下发的 `safety_contour_val`，并在 slider 旁给出短暂错误提示（例如 toast）。前端不重试，等待用户下次操作触发新请求。

---

## 7. Implementation Order

### Phase 1：共享契约与运行时 contour 状态

- 引入 `EnvAlertCode`
- 引入运行时 safety contour 状态组件
- 扩展 `S57Controller`，新增 contour 更新与默认值重置接口
- weather 现有 alert 逻辑迁移到枚举

### Phase 2：hydrology 查询与 SSE 注入

- 新建 `HydrologyContextService` 与 hydrology DTO
- `RiskObjectAssembler` 增加 hydrology 查询步骤
- `RiskObjectMetaAssembler` 扩展 `environment_context.hydrology`
- `active_alerts` 追加 hydrology alert

### Phase 3：前端写回与类型补齐

- `schema.d.ts`、fixture、store 相关测试补齐 hydrology 字段
- slider 从本地 override 收敛到后端写回语义
- 地图 source URL 与 SSE contour 值保持一致

### Phase 4：文档与协议真值同步

- 更新 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) 的 `environment_context` 段
- 更新 shared alert 枚举说明
- 校对 hydrology / weather 两条 track 对 `active_alerts` 与 `environment_context` 的描述一致性

---

## 8. File Impact

预期至少触达以下区域：

- 后端 hydrology 查询与 DTO：
  - `backend/map-service/src/main/java/com/whut/map/map_service/chart/service/`
  - `backend/map-service/src/main/java/com/whut/map/map_service/chart/dto/`
- 后端运行时状态与告警枚举：
  - `backend/map-service/src/main/java/com/whut/map/map_service/shared/domain/EnvAlertCode.java`
  - `backend/map-service/src/main/java/com/whut/map/map_service/risk/config/` 或等价配置包
- 风险对象装配：
  - [`RiskObjectAssembler.java`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/RiskObjectAssembler.java)
  - [`RiskObjectMetaAssembler.java`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject/RiskObjectMetaAssembler.java)
- S-57 控制器：
  - [`S57Controller.java`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java)
- 前端协议与 slider 消费：
  - [`schema.d.ts`](../../../frontend/src/types/schema.d.ts)
  - [`useMapSettingsStore.ts`](../../../frontend/src/store/useMapSettingsStore.ts)
  - [`MapContainer.tsx`](../../../frontend/src/components/Map/MapContainer.tsx)
  - [`s57Service.ts`](../../../frontend/src/services/s57Service.ts)

---

## 9. Validation

### 9.1 后端单测 / 组件测试

- `RiskObjectMetaAssemblerTest` 覆盖：
  - weather-only 场景仍保留现有字段
  - hydrology 注入后 `environment_context.hydrology` 结构正确
  - weather + hydrology alerts 能共存于同一 `active_alerts`
- 新增 `HydrologyContextServiceTest`：
  - 命中浅区
  - ENC 缺失
  - 最近障碍物命中 / 超阈值两类场景
- `S57Controller` 或等价 Web 层测试覆盖 contour 更新与默认值重置

### 9.2 前端测试

- `useRiskStore.test.ts` 与 fixture 更新后保持绿色
- slider 写回失败时回退到最近 SSE 值
- 收到新的 `environment_context.hydrology` 时，前端不因额外字段报错

### 9.3 端到端手工验收

- 本船进入 `safety_contour_val` 以浅区域时，`RISK_UPDATE.environment_context.hydrology.own_ship_min_depth_m` 返回正确 `DRVAL1`
- 同一场景下 `active_alerts` 包含 `SHOAL_PROXIMITY`
- 调整 slider 后：
  - 瓦片 URL 使用新 contour 值
  - SSE `environment_context.safety_contour_val` 同步变化
  - `nearest_shoal_nm` 依据新 contour 重新计算

---

## 10. Deferred

- `riskScore` penalty 接入：Step 3
- agent tool 与 advisory 事实消费：Step 3
- 若后续需要在同一帧内供多个模块重复消费 hydrology 结果，再引入显式 `HydrologyFrameContext`；当前步骤不预建该层

---

## 11. 需同步修改的文档

- [`HYDROLOGY_PLAN.md`](./HYDROLOGY_PLAN.md)：Step 2 说明同步为“服务端 hydrology 注入 + contour 运行时写回 + 共享枚举落地”
- [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)：`environment_context.hydrology` 与 `active_alerts` 枚举说明
- [`../weather/WEATHER_PLAN.md`](../weather/WEATHER_PLAN.md)：若共享 `EnvAlertCode` 的落地点或语义发生调整，需同步更正说明
- Step 2 属于现有 hydrology 实施链，不写入 [`../../TODO.md`](../../TODO.md)；本步骤未新增失去 owner 的后续项
