# Hydrology Step 1：2.5D 视觉升级 + OBSTRN 出图 + safety contour 交互

> 文档状态：active
> 最后更新：2026-04-18
> 所属 track：[`HYDROLOGY_PLAN.md`](./HYDROLOGY_PLAN.md)
> 目标：在不破坏现有交互与告警视觉优先级的前提下，把现有 2D flat 水深渲染升级到驾驶台级 2.5D 效果，并让 `enc_obstrn` 首次可见。

---

## 1. Summary

Step 1 只改前端与 MVT 拼装，不动后端引擎与 LLM。重点有三：

1. **OBSTRN 出图**（最小改动、最高可见度）
2. **`DEPARE` 从 fill 升级到 fill-extrusion**（视觉冲击力核心）
3. **safety contour 前端可控**（把配置常量升级为驾驶台级交互）

本步骤的验收标准是"可截图演示"，不引入任何协议变更。

---

## 2. In Scope

### 2.1 后端修改（最小）

- 扩展 [`S57TileRepository.getTableName`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L34)，补充 `OBSTRN -> enc_obstrn` 映射，保证单图层调试端点与 composite fallback 都能取到障碍物数据
- 扩展 [`S57TileRepository.buildMultiLayerSQL`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L249)，新增 `obstrn_mvt` CTE，拼接到 composite tile 输出
- 更新 [`S57Controller.generateCompositeTile`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java#L75) 的 fallback layer 列表，把 `OBSTRN` 纳入降级路径，避免 composite 失败时障碍物整层消失
- 扩展单图层调试端点 [`S57Controller.getSingleLayerTile`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java#L55) 支持可选 `safety_contour` 查询参数，使现有单图层 `DEPCNT` source 能随 slider 重拉
- 确认 `enc_obstrn` 表实际 schema（至少 `CATOBS`、`VALSOU` 两列可用于前端分级着色）
- 无需修改 `environment_context`（本步骤不触碰 SSE payload）

### 2.2 前端渲染改动

- `s57Service.ts` 不变；style.json 与图层定义可由前端直接扩展，无需新增后端接口
- Step 1 保持现有单图层 vector source 链路，不切换到 composite tile 作为前端主渲染路径；`OBSTRN` 以新增独立 source/layer 的方式接入
- 新增 MapLibre 图层：
  - `obstrn-symbol`：点符号（wreck / rock / pile / unknown 四类 icon）+ 红色警戒圈
  - `depare-extrusion`：替换现有 `depth-areas-*` 三层，使用 `fill-extrusion-base/height` 将 `drval1` 映射到负 height（例：`height = -drval1 * 2`）
  - `shoal-glow`：`drval1 < safety_contour_val` 的独立层，`fill-extrusion-color` 配合半透明高 opacity，营造体积发光
- `SOUNDG` 测深点符号保留，缩放阈值上调避免与 OBSTRN 符号视觉打架

### 2.3 交互改动

- `MapContainer` 内部挂载 safety contour slider（默认位于右上角次级工具栏，不进入主 HUD）
- slider 初始值从 `environment.safety_contour_val` 读取；用户手动修改后，以本地 override 为准，不被后续 SSE 自动覆盖
- 提供“恢复实时值”入口，允许用户清除本地 override，重新跟随 SSE 下发的 `environment.safety_contour_val`
- 滑动值节流（300 ms debounce）后重建实际依赖服务端 contour 的 source（Step 1 实现收敛到 `DEPCNT`），其余 `DEPARE` / `OBSTRN` 图层由前端 filter / paint 即时更新
- 滑块区间 `[5m, 30m]`，默认从 `environment.safety_contour_val` 初始化

---

## 3. Out of Scope

- `environment_context.hydrology` 字段注入（Step 2）
- `HydrologyContextService` 建设（Step 2）
- 风险引擎对水文的消费（Step 3）
- `active_alerts` 填充（Step 2）
- agent tool 注册（Step 3）
- OBSTRN 完整属性集展开（本步只用 `CATOBS / VALSOU`，其它 S-57 标准字段延后）
- 潮汐时变、实时水位

---

## 4. 关键实现细节

### 4.1 OBSTRN composite tile 拼装

参考现有 CTE 写法（[`S57TileRepository.java:289`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L289) `depcnt_mvt` 段）补一段：

```sql
obstrn_mvt AS (
    SELECT COALESCE(ST_AsMVT(q.*, 'OBSTRN', 4096, 'geom'), ''::bytea) AS tile FROM (
        SELECT ST_AsMVTGeom(ST_Transform(t.geometry, 3857), bounds.geom, 4096, 256, true) AS geom,
            t."CATOBS" as catobs, t."VALSOU" as valsou
        FROM enc_obstrn t, bounds
        WHERE ST_Intersects(ST_Transform(t.geometry, 3857), bounds.geom)
    ) q
)
```

最终 `SELECT` 行里追加 `|| obstrn_mvt.tile`。同步更新 `S57Controller.getLayerMetadata` 返回值新增 `OBSTRN` 条目，便于前端感知可用图层。

同时补齐两处配套修改：

- `S57TileRepository.getTableName()` 增加 `OBSTRN` 映射，保证 `/api/s57/tiles/{z}/{x}/{y}/OBSTRN.pbf` 可用
- `S57Controller.generateCompositeTile()` 的 fallback layer 列表追加 `OBSTRN`，避免 composite 查询异常时前端完全失去障碍物出图能力

### 4.2 `fill-extrusion` 参数选型

- `fill-extrusion-base` 固定 `0`
- `fill-extrusion-height` = `-['get', 'drval1'] * depth_exaggeration`；`depth_exaggeration` 初始 `2.0`，Step 1 里可配成常量
- `fill-extrusion-opacity` 对深水 `0.45`，浅水 `0.7`，让浅区更显眼
- 仅在 `pitch > 25°` 启用（通过 `minzoom` 或 MapLibre filter 不能直接表达 pitch 条件，需在前端监听 `pitchend` 动态切换图层 visibility）

### 4.3 `shoal-glow` 层触发条件

- MapLibre `fill-extrusion` 不支持真实 AO/bloom，需要用半透明多层叠加模拟
- 实施方案：同一 `DEPARE` source-layer 上再叠一层过滤 `drval1 < safety_contour_val` 的 `fill-extrusion`，颜色 `#ff4444` + opacity `0.35`，小幅度上移 `base` 避免 z-fighting
- 若后续发现性能下滑明显，降级为 2D `fill` + `fill-pattern` 纹理

### 4.4 safety contour slider 状态所属

- 短期（Step 1）：以前端 local override 持有；初始值来自 SSE `environment.safety_contour_val`，用户修改后不被后续 SSE 自动覆盖
- Step 1 必须提供“恢复实时值”入口，清除 local override 后重新跟随服务端值
- 未来（Step 2）：slider 值写回后端，成为 `HydrologyContextService` 查询参数的一部分，并至少体现在 `environment_context.hydrology` / LLM 可消费上下文之一；届时前后端再收敛为单一真值

---

## 5. Validation

### 5.1 视觉

- 在仓库现有 Jamaica Bay 仿真数据下，能直接看到至少一个 OBSTRN 符号
- pitch 30°/60° 下水深层明显拔深，对比平视有可见立体感
- 危险浅区在正常光照下颜色/体积明显区别于普通浅水

### 5.2 交互

- safety contour 滑动后 3 秒内瓦片完成重载
- pitch 旋转不导致 `TargetsPanel` 点击目标失效
- 2.5D 图层不遮挡 ALARM / WARNING 目标符号

### 5.3 性能

- Chrome devtools Performance tab 采样 10 秒：FPS 不低于现有 2D 版本的 90%
- tile 响应时间不因新增 OBSTRN 拼装 CTE 显著增加（P95 < 现有 P95 × 1.2）

### 5.4 回归

- 现有 `useRiskStore.test.ts` 与其它前端测试全部通过
- backend 补充或更新至少一条 controller/repository 级测试，覆盖 `OBSTRN` layer metadata 或 tile 生成路径
- `S57Controller.getLayerMetadata` 响应新增 `OBSTRN` 条目，但已有字段不变

---

## 6. Deferred

- `environment_context.hydrology` 注入：Step 2
- `active_alerts` 填充：Step 2
- safety contour slider 写回后端并进入环境/LLM 上下文消费：Step 2
- `RiskAssessmentEngine` 消费水文 penalty：Step 3
- agent tool 注册：Step 3

---

## 7. Recovered To TODO

- 前端主渲染链路切换到 composite tile：不属于 Step 1–3 当前实现链，已回收到 [`../../TODO.md`](../../TODO.md)
- 航道 `FAIRWY` / 受限区 `RESARE` 出图：当前未挂入 `HYDROLOGY_PLAN.md` 的 Step 1–3，已回收到 [`../../TODO.md`](../../TODO.md)
- `enc_depcnt` 深值标签（目前仅虚线无数值）：当前未挂入明确后续 step，已回收到 [`../../TODO.md`](../../TODO.md)

---

## 8. 需同步修改的文档

本步骤实施完成时：

- [`HYDROLOGY_PLAN.md`](./HYDROLOGY_PLAN.md) §4 Step 1 状态更新为"已完成"
- Step 1 属于 hydrology 现有规划链内事项，本身不写入 [`../../TODO.md`](../../TODO.md)；但本步骤中明确排除且未挂入 Step 2 / Step 3 的后续项，应同步回收到 TODO
- 不需要修改 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)（本步骤无协议变更）
