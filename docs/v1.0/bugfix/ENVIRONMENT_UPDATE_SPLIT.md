# Environment Update Split Fix

> 文档状态：active
> 执行状态：completed
> 最后更新：2026-04-28

## 1. 背景

当前 risk SSE 的 `RISK_UPDATE` 同时承载两类状态：

1. 船舶风险态势：本船、目标船、CPA / TCPA、风险等级、governance
2. 环境态势：`safety_contour_val`、`weather`、`weather_zones`、`hydrology`、`active_alerts`

该结构在 AIS 持续推送时可以工作，但当 AIS 停止时，非 AIS 来源的环境变化无法继续下发到前端。已观察到的问题包括：

- weather MQTT 仍在更新，但前端不收到新的环境状态
- safety contour 通过前端 HTTP 写回后，后端状态已更新，但前端需要等待下一帧 `RISK_UPDATE` 才能拿到服务端回显
- weather stale 到期后，如果没有 AIS 帧触发新快照，前端仍可能显示旧天气

该问题不是单个 safety contour 接口的局部缺陷，而是 `RISK_UPDATE` 承担了过多状态同步职责。

相关决策见 [`../../ADR_AND_REVIEW_FINDINGS.md`](../../ADR_AND_REVIEW_FINDINGS.md) `ADR-009`。

## 2. 修复目标

本次修复将 risk SSE 中的风险状态与环境状态拆分为两个事件：

- `RISK_UPDATE`：只表达风险管线产物
- `ENVIRONMENT_UPDATE`：只表达环境状态快照

目标如下：

1. weather、safety contour、weather stale 等环境变化不再依赖 AIS 帧触发
2. safety contour 保持 HTTP command 写入，SSE 只负责广播后端权威状态
3. 前端不再从 `RISK_UPDATE` 中读取 `environment_context`
4. 后端只保留一个环境组装入口，避免 `RiskObjectAssembler`、`S57Controller`、`WeatherMessageHandler` 各自拼装环境 payload
5. 新 SSE 连接可以 replay 最新 risk snapshot 与最新 environment snapshot

## 3. 非目标

- 不将 safety contour 写入改为 WebSocket
- 不引入 per-client safety contour；当前仍为全局运行时状态
- 不将环境更新继续拆成 `WEATHER_UPDATE`、`HYDROLOGY_UPDATE`、`SAFETY_CONTOUR_UPDATE`
- 不在前端做字段级 patch merge；`ENVIRONMENT_UPDATE` 到达后整体替换 environment state
- 不改造 risk SSE 为 delta 协议

## 4. 协议边界

### 4.1 `RISK_UPDATE`

`RISK_UPDATE` 继续通过 `/api/v2/risk` SSE 下发，但不再携带 `environment_context`。

目标结构：

```json
{
  "event_id": "uuid",
  "risk_object_id": "ownShip-...",
  "timestamp": "2026-04-28T06:00:00Z",
  "environment_state_version": 17,
  "governance": {
    "mode": "rule-based",
    "trust_factor": 0.9
  },
  "own_ship": {},
  "targets": []
}
```

`environment_state_version` 表示该风险快照引用的环境版本。前端不通过该字段重建环境，只用于调试、断言和后续一致性校验。

### 4.2 `ENVIRONMENT_UPDATE`

新增 SSE 事件类型：

```text
event: ENVIRONMENT_UPDATE
```

目标结构：

```json
{
  "event_id": "uuid",
  "timestamp": "2026-04-28T06:00:00Z",
  "environment_state_version": 17,
  "reason": "SAFETY_CONTOUR_UPDATED",
  "changed_fields": ["safety_contour_val", "hydrology", "active_alerts"],
  "environment_context": {
    "safety_contour_val": 2.0,
    "active_alerts": [],
    "weather": null,
    "weather_zones": null,
    "hydrology": null
  }
}
```

`reason` 首批支持：

- `WEATHER_UPDATED`
- `WEATHER_EXPIRED`
- `SAFETY_CONTOUR_UPDATED`
- `SAFETY_CONTOUR_RESET`
- `OWN_SHIP_ENV_REEVALUATED`

`changed_fields` 仅用于前端优化和调试提示，不作为语义真值。后端固定写入本次事件可能涉及的全部字段名，不做精确 diff，以避免引入额外的快照比较逻辑。前端收到事件后应整体替换 `environment`。

`weather` 与 `weather_zones` 具有联动语义：`weather` 为 `null`（过期或尚无数据）时，`weather_zones` 同步置 `null`。

## 5. 后端设计

### 5.1 环境组装服务

新增或等价引入：

- `backend/map-service/src/main/java/com/whut/map/map_service/risk/environment/EnvironmentContextService.java`

职责：

- 读取 `SafetyContourStateHolder`
- 读取 `WeatherContextHolder`
- 读取本船位置（需要一个缓存持有者，如 `OwnShipPositionHolder`，由 `ShipDispatcher` 在每帧 AIS 后写入；weather / safety contour / stale 触发时无 AIS 帧，必须从该持有者读取，不能将 `ownShip` 作为参数传入）
- 调用 `RegionalWeatherResolver` 计算 effective weather
- 调用 `HydrologyContextService` 计算 hydrology 摘要
- 统一生成 `environment_context`
- 统一计算 `active_alerts`
- 维护单调递增的 `environment_state_version`（JVM 内 `AtomicLong` 从 0 计数，不持久化；服务重启后从 0 重新计数）

该服务是 `environment_context` 的唯一权威组装点。

`refresh()` 的职责边界：只负责组装并缓存新快照，返回组装结果；发布到 SSE 的动作由调用方（`WeatherMessageHandler`、`S57Controller`、`ShipDispatcher`、stale 定时器）显式调用 `RiskStreamPublisher.publishEnvironmentUpdate()`，而不是由 `EnvironmentContextService` 内部调用。如果 `EnvironmentContextService` 直接依赖 `RiskStreamPublisher`，则服务层反向依赖传输层，应避免。

### 5.2 风险组装链路

`RiskObjectAssembler` 不再直接组装 `environment_context`，而是从 `EnvironmentContextService` 获取当前环境版本引用。

处理顺序建议：

1. `ShipDispatcher` 收到 AIS 并完成 risk 计算
2. 使用当前 own ship 位置请求环境重评估
3. 若环境快照内容或版本发生变化，先发布 `ENVIRONMENT_UPDATE`
4. 组装并发布 `RISK_UPDATE`，其中 `environment_state_version` 指向当前环境版本

经确认，`LlmRiskContextAssembler` 从原始 `ShipStatus` 和 `RiskAssessmentResult` 独立组装 LLM 上下文，不经过 `RiskObjectDto.environmentContext`；内部模型与 SSE payload 路径已天然分离，DTO 拆分不影响 LLM 流水线。

实现后，`RiskObjectAssembler` 已不再注入 `HydrologyContextService` 和 `SafetyContourStateHolder`，`RiskObjectMetaAssembler.buildEnvironmentContext()` 已删除；`EnvironmentContextService` 是 `environment_context` 的唯一组装入口。

### 5.3 weather 触发

`WeatherMessageHandler` 在成功解析并写入 `WeatherContextHolder` 后，应触发环境发布：

```text
weather MQTT payload
  -> WeatherContextHolder.update(...)
  -> EnvironmentContextService.refresh(reason=WEATHER_UPDATED)
  -> RiskStreamPublisher.publishEnvironmentUpdate(...)
```

如果当前尚无 own ship，仍应发布包含全局 weather / weather_zones 与 `hydrology=null` 的环境快照。待 own ship 出现或位置变化后，再发布 `OWN_SHIP_ENV_REEVALUATED`。

### 5.4 safety contour 触发

`S57Controller` 保持 HTTP command 语义：

- `PUT /api/s57/safety-contour?depth=...`
- `POST /api/s57/safety-contour/reset`

成功更新 `SafetyContourStateHolder` 后：

```text
HTTP command success
  -> EnvironmentContextService.refresh(reason=SAFETY_CONTOUR_UPDATED / SAFETY_CONTOUR_RESET)
  -> RiskStreamPublisher.publishEnvironmentUpdate(...)
  -> HTTP response returns current contour payload
```

HTTP response 作为发起方 ack；SSE 作为所有客户端的后端权威状态广播。前端发起方也应以后续 SSE 到达后的 `environment.safety_contour_val` 作为最终显示真值。

### 5.5 stale 触发

weather stale 不应只在下一次 risk snapshot 中被动体现。后端应增加轻量定时检查：

- 若上一次 fresh weather 已超过 stale threshold（配置项 `engine.risk-meta.weather-alert.stale-threshold-seconds`，默认 60 s；定时检查间隔应显著小于该值，建议不超过 threshold 的 1/3）
- 且当前已发布环境快照中仍包含 weather
- 发布 `ENVIRONMENT_UPDATE(reason=WEATHER_EXPIRED)`

该定时器只处理状态过期，不做 risk 重算。

### 5.6 SSE publisher

`RiskStreamPublisher` 需要维护两类 latest snapshot：

- latest risk snapshot
- latest environment snapshot

新 SSE emitter 注册后应 replay 两类当前态。推荐顺序：

1. `ENVIRONMENT_UPDATE`
2. `RISK_UPDATE`

由于 `RiskStreamPublisher` 使用 `newSingleThreadExecutor`，所有 `submit()` 任务串行执行（含 `beforePublish` 回调），此顺序为强制保证——前提是新增的 `publishEnvironmentUpdate` 同样通过 `submit()` 提交，不得使用独立线程。

## 6. 前端设计

### 6.1 SSE service

`frontend/src/services/riskSseService.ts` 增加：

- `ENVIRONMENT_UPDATE` listener
- `onEnvironmentUpdate(cb)`
- disconnect 时移除对应 listener

测试需覆盖：

- `ENVIRONMENT_UPDATE` 可被解析并转发
- disconnect 清理 listener
- 非法 JSON 不影响其它事件分发

### 6.2 Store

`frontend/src/store/useRiskStore.ts` 增加 environment update setter：

```ts
setEnvironmentUpdate(payload: EnvironmentUpdatePayload): void
```

该 setter 只更新：

- `environment`
- `lastEnvironmentUpdateTime`
- 与 safety contour pending 相关的回显状态

不得更新：

- `ownShip`
- `targets`
- `governance`
- explanation lifecycle
- advisory state

`setRiskUpdate` 不再写入 `environment`。

### 6.3 Map 与 Dashboard

现有消费点继续从 store 的 `environment` 读取：

- `MapContainer`：`safety_contour_val`、`weather_zones`、`weather.visibility_nm`
- `RiskExplanationPanel`：safety contour slider live value
- `StatusPanel` / `MergedLeftPanel`：weather 摘要与 alerts

组件不应关心该 environment 来自 `RISK_UPDATE` 还是 `ENVIRONMENT_UPDATE`。

## 7. 文档同步

本修复落地时必须同步更新：

- [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)
  - 新增 `ENVIRONMENT_UPDATE`
  - 从 `RISK_UPDATE` payload 中移除 `environment_context`
  - 增加 `environment_state_version`
  - 更新 replay 语义
- [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md)
  - 将 safety contour 回显从 `RISK_UPDATE.environment_context` 改为 `ENVIRONMENT_UPDATE.environment_context`
- [`../hydrology/step2.md`](../hydrology/step2.md)
  - 更新验收项和前端回显语义
- [`../weather/WEATHER_PLAN.md`](../weather/WEATHER_PLAN.md)
  - 将 weather / weather_zones 的下发入口改为 `ENVIRONMENT_UPDATE`
- [`../weather/step2.md`](../weather/step2.md)
  - 更新区域天气 SSE 验收点
- [`../README.md`](../README.md)
  - 说明 bugfix track 中存在跨 hydrology / weather 的协议切分修复

## 8. 实施步骤

### Phase 1：协议与 DTO

- 新增后端 `EnvironmentUpdatePayload`
- 扩展 `SseEventType`，增加 `ENVIRONMENT_UPDATE`
- 修改 `RiskUpdatePayload`，移除 `environment_context`，新增 `environment_state_version`
- 更新前端 `schema.d.ts`
- 更新 fixtures 与 SSE service tests

### Phase 2：环境组装服务

- 新增 `EnvironmentContextService`（含 own ship 位置持有者，如 `OwnShipPositionHolder`）
- 从 `RiskObjectMetaAssembler` 中迁出 environment 构造逻辑；迁出后删除 `buildEnvironmentContext()` 方法
- 删除 `RiskObjectAssembler` 中对 `HydrologyContextService` 和 `SafetyContourStateHolder` 的注入
- 统一 weather alert 与 hydrology alert 计算入口
- 为无 own ship、无 weather、无 hydrology 数据场景补测试

### Phase 3：发布与 replay

- `RiskStreamPublisher` 增加 latest environment snapshot 缓存
- 新连接 replay latest environment + latest risk
- AIS 风险发布前先完成 environment reevaluate
- weather ingest、safety contour command、weather stale 定时器均触发 environment publish

### Phase 4：前端消费

- `riskSseService` 增加 environment listener
- `useRiskStore` 拆分 `setRiskUpdate` 与 `setEnvironmentUpdate`
- safety contour pending 状态以 `ENVIRONMENT_UPDATE` 回显清除
- 地图与面板保持读取统一 `environment` store

### Phase 5：文档与回归

- 更新协议文档与 hydrology / weather 规划文档
- 补充端到端或集成验证脚本说明

## 9. 验收标准

### 9.1 safety contour

1. 停止 AIS simulator，仅保持前后端运行
2. 在前端调整 safety contour
3. HTTP 写入成功
4. 前端收到 `ENVIRONMENT_UPDATE`
5. `environment.safety_contour_val` 更新
6. 地图 DEPARE filter 与 DEPCNT source 按新值刷新

### 9.2 weather update

1. 停止 AIS simulator
2. 单独推送 weather MQTT payload
3. 前端收到 `ENVIRONMENT_UPDATE(reason=WEATHER_UPDATED)`
4. weather 摘要、weather zones 与 active alerts 更新

### 9.3 weather stale

1. 推送一次 fog weather
2. 停止 weather publisher
3. 等待 stale threshold 到期
4. 前端收到 `ENVIRONMENT_UPDATE(reason=WEATHER_EXPIRED)`
5. `environment.weather == null`
6. fog overlay / weather zones 清除

### 9.4 risk / environment 顺序

1. AIS 正常推送
2. 本船进入或离开 weather zone
3. 服务端先发布 `ENVIRONMENT_UPDATE(reason=OWN_SHIP_ENV_REEVALUATED)`
4. 随后的 `RISK_UPDATE.environment_state_version` 指向当前环境版本

## 10. 实施结果

本修复已按该规划落地，当前实现状态如下：

- 后端新增 `EnvironmentContextService`、`OwnShipPositionHolder`、`EnvironmentUpdatePayload` 与 `ENVIRONMENT_UPDATE` SSE 事件。
- `RISK_UPDATE` 已移除 `environment_context`，并新增 `environment_state_version`。
- `RiskObjectAssembler` 不再组装环境 payload；`RiskObjectMetaAssembler.buildEnvironmentContext()` 已删除。
- `RiskStreamPublisher` 同时缓存 latest environment snapshot 与 latest risk snapshot，新 SSE 连接按 `ENVIRONMENT_UPDATE`、`RISK_UPDATE` 顺序 replay。
- weather MQTT、safety contour HTTP command、AIS 后环境重评估、weather stale 定时器均会触发环境刷新与发布。
- 前端 `riskSseService` 已监听 `ENVIRONMENT_UPDATE`；`useRiskStore.setRiskUpdate` 不再写入 `environment`，`setEnvironmentUpdate` 整体替换 environment state。

实现中的等价调整：

- safety contour pending 状态仍由既有 `RiskExplanationPanel` effect 根据 live `environment.safety_contour_val` 清除，而不是在 `useRiskStore.setEnvironmentUpdate` 内直接操作 `useMapSettingsStore`。该处理避免 store 间交叉写入，保留“以后端 SSE 回显作为最终真值”的行为。
- weather stale 检查使用配置项 `engine.risk-meta.weather-alert.stale-check-interval-ms`，默认 20000 ms；默认 stale threshold 为 60 s 时满足“不超过 threshold 的 1/3”的约束。

验证记录：

- 后端：`./mvnw -q test`
- 前端：`npm run test -- --run`
- 前端构建：`npm run build`

## 11. 风险与边界

- 该修复是协议变更，不应与其它 UI 重构混合提交。
- 前后端需要同批落地；旧前端仍从 `RISK_UPDATE.environment_context` 读取，但该字段拆分后不再下发，结果是 environment 区域静止（功能降级），而非崩溃。
- 若未来引入多客户端独立 safety contour，本方案需要升级为 session-level environment state；当前不做该扩展。
- 若 weather_zones 后续变成高频大 payload，可再评估从完整快照升级为独立 replay 或 delta，但当前不提前设计。
