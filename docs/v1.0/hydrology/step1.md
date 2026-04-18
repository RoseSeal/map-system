# Hydrology Step 1：2.5D 视觉升级 + OBSTRN 出图 + safety contour 交互

> 文档状态：active
> 最后更新：2026-04-17
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

- 扩展 [`S57TileRepository.buildMultiLayerSQL`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java#L249)，新增 `obstrn_mvt` CTE，拼接到 composite tile 输出
- 确认 `enc_obstrn` 表实际 schema（至少 `CATOBS`、`VALSOU` 两列可用于前端分级着色）
- 无需修改 `environment_context`（本步骤不触碰 SSE payload）

### 2.2 前端渲染改动

- `s57Service.ts` 不变；style.json 与图层定义可由前端直接扩展，无需新增后端接口
- 新增 MapLibre 图层：
  - `obstrn-symbol`：点符号（wreck / rock / pile / unknown 四类 icon）+ 红色警戒圈
  - `depare-extrusion`：替换现有 `depth-areas-*` 三层，使用 `fill-extrusion-base/height` 将 `drval1` 映射到负 height（例：`height = -drval1 * 2`）
  - `shoal-glow`：`drval1 < safety_contour_val` 的独立层，`fill-extrusion-color` 配合半透明高 opacity，营造体积发光
- `SOUNDG` 测深点符号保留，缩放阈值上调避免与 OBSTRN 符号视觉打架

### 2.3 交互改动

- `MapContainer` 内部挂载 safety contour slider（默认位于右上角次级工具栏，不进入主 HUD）
- 滑动值节流（300 ms debounce）后重建 `SOURCE` 或手动 reload，触发 MVT 重拉（URL 参数变化自然 invalidate 缓存）
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

- 短期（Step 1）：以前端 local state 持有，不回写后端
- 未来（Step 2）：同一值可作为 `HydrologyContextService` 查询参数的一部分（"本船进入哪档安全深度以内"），届时再决定是否经 `environment_context` 来回

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
- `S57Controller.getLayerMetadata` 响应新增 `OBSTRN` 条目，但已有字段不变

---

## 6. Deferred

- `environment_context.hydrology` 注入：Step 2
- `active_alerts` 填充：Step 2
- `RiskAssessmentEngine` 消费水文 penalty：Step 3
- agent tool 注册：Step 3
- 航道 `FAIRWY` / 受限区 `RESARE` 出图：留到 v1.0 后续 step 或后续 milestone
- `enc_depcnt` 深值标签（目前仅虚线无数值）：视觉优先级偏低，不在 Step 1 交付

---

## 7. 需同步修改的文档

本步骤实施完成时：

- [`HYDROLOGY_PLAN.md`](./HYDROLOGY_PLAN.md) §4 Step 1 状态更新为"已完成"
- [`../../TODO.md`](../../TODO.md) 第 4 节"2.5D 海图视觉增强与水文专题渲染"追加进度注记（不移除，因 Step 2/3 未完成）
- 不需要修改 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)（本步骤无协议变更）
