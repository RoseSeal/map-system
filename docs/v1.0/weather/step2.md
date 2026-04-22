# Weather Step 2：区域化天气建模 + simulator 驱动 weather zones

> 文档状态：active
> 最后更新：2026-04-21
> 执行状态：pending
> 所属 track：[`WEATHER_PLAN.md`](./WEATHER_PLAN.md)
> 目标：在不破坏 Step 1 已落地链路的前提下，把天气从单帧全局快照升级为区域化天气，并继续由 simulator 驱动。

---

## 1. Summary

Step 2 的核心变化是引入 `weather_zones`：simulator 下发带有 GeoJSON 几何的天气区块，后端按本船当前位置求解 `effective weather`，`environment_context.weather` 的语义从"全局快照"收敛为"本船当前位置命中的天气摘要"，前端 fog / rain 等天气图层改为按 `weather_zones` 分区渲染。

**兼容性是硬约束**：旧的 `--scene fog` 单快照 payload（无 `weather_zones`）必须继续运行，行为退化为 Step 1 语义，不要求前后端同步升级才能工作。风险引擎与 LLM 的消费不在本步骤内修改，分别转 Step 3 / Step 4。

---

## 2. In Scope

### 2.1 Simulator 端

- 扩展现有 [`simulator/weather_mqtt_publisher.py`](../../../simulator/weather_mqtt_publisher.py)：在原有场景 payload 上追加可选 `weather_zones[]`
- 新增 `zoned_fog` 场景（`--scene zoned_fog`）：全图天气码 `CLEAR`，附带一个 FOG zone（GeoJSON Polygon 覆盖演示区域的局部海图），作为"一区雾、一区晴"的标准演示场景
- 现有 `--scene clear|fog|rain|storm` 继续发布不含 `weather_zones` 的单快照 payload，不受本 step 影响
- 同一帧 `weather_zones` 建议不超过 4 个（payload 体积约束）；`zone_id` 与 `geometry` 为有效 zone 的必需字段，各气象字段可按场景只填部分，缺失字段为 `null`
- 同一帧内 `zone_id` 不得重复（simulator 侧保证，后端不强校验）
- Step 2 有效 payload 约束：同一帧内各 zone 几何不得重叠；若 simulator 误下发重叠 zone，后端仍按数组顺序取首个命中作为防御性退化，而不把数组顺序定义为正式业务语义

### 2.2 后端

- 新增 `WeatherZoneContext` record（包 `source/weather/dto`）：承载单个 zone 的气象字段与 GeoJSON 几何（见 §4.2）
- 扩展 `WeatherContextHolder.Snapshot`，追加 `List<WeatherZoneContext> zones` 字段；`update` 方法同时接受全局天气与 zones 列表，二者在同一 `volatile` 写入内保持原子性
- 扩展 `WeatherMessageHandler`：从 MQTT payload 反序列化可选 `weather_zones[]`；若 payload 不含该字段，将 zones 设为空列表（兼容 Step 1 消息格式）
- 新增 `RegionalWeatherResolver`（包 `source/weather`）：按本船位置检索第一个命中 zone，支持 `Polygon` 与 `MultiPolygon` 几何（见 §4.3）
- 修改 [`RiskObjectMetaAssembler.buildEnvironmentContext`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject/RiskObjectMetaAssembler.java#L64) 签名为 `buildEnvironmentContext(ShipStatus ownShip)`；zones 非空时调用 `RegionalWeatherResolver` 求 effective weather，zones 为空时退化为 Step 1 全局快照语义（见 §4.4）
- `active_alerts` 仅根据 `effective weather` 生成，不对所有 zone 做并集告警
- `environment_context.weather_zones` 作为可选字段写入 SSE payload：zones 非空时输出完整有效 zone 数组（含 geometry），否则输出 `null`；geometry 缺失或类型非法的 zone 不进入 SSE payload；此字段仅供前端图层消费，引擎与 LLM 不读取
- 同步修改 [`RiskObjectAssembler:59`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/RiskObjectAssembler.java#L59) 调用点，传入 `ownShip`

### 2.3 前端

- [`schema.d.ts`](../../../frontend/src/types/schema.d.ts) 新增 `WeatherZoneContext` 类型；`EnvironmentContext` 扩展 `weather_zones?: WeatherZoneContext[] | null`（见 §4.5）
- [`MapContainer`](../../../frontend/src/components/Map/MapContainer.tsx) 天气图层逻辑：
  - 有 `weather_zones` 时：为每个非 `CLEAR` zone 渲染独立 GeoJSON source + fill layer，颜色与 opacity 由 `weather_code` 决定（见 §4.6）
  - `weather_zones` 为 null 或空时：退化为 Step 1 的全图雾化逻辑（使用全球 bbox + `weather.visibility_nm` 映射 opacity）
  - 天气图层优先级：高于底图与 ENC，低于目标船 / 轨迹 / 告警符号
- [`StatusPanel`](../../../frontend/src/components/Dashboard/RiskExplanationPanel.tsx)（或等价面板）无变化，继续只展示 `effective weather`（`environment_context.weather`），不枚举 zones

---

## 3. Out of Scope

- **not doing**：降雨粒子层、风场箭头、水流矢量；转 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md)
- **not doing**：`Polygon` hole（内环）支持、自交检测、拓扑修复
- **deferred**：风险引擎接入 weather 修正（visibility 阈值缩放 / storm penalty），转 **Step 3**
- **deferred**：`LlmRiskContext` 扩展与 advisory 消费，转 **Step 4**
- **deferred**：agent tool 注册（`GetWeatherContextTool`、`EvaluateManeuverWithWeatherTool`），转 **Step 4**
- **post-v1.0 / 无明确 owner**：真实气象 API 接入；已回收到 [`../../TODO.md`](../../TODO.md)

---

## 4. 关键实现细节

### 4.1 MQTT payload 契约扩展

`weather_zones` 为可选字段，缺失或 null 均视为 Step 1 单快照语义。`zoned_fog` 场景下的典型 payload：

```json
{
  "weather_code": "CLEAR",
  "visibility_nm": 10.0,
  "precipitation_mm_per_hr": 0.0,
  "wind": { "speed_kn": 5.0, "direction_from_deg": 270 },
  "surface_current": { "speed_kn": 0.3, "set_deg": 90 },
  "sea_state": 2,
  "timestamp_utc": "2026-04-21T08:00:00Z",
  "weather_zones": [
    {
      "zone_id": "fog-bank-east",
      "weather_code": "FOG",
      "visibility_nm": 0.8,
      "precipitation_mm_per_hr": 0.0,
      "wind": { "speed_kn": 3.0, "direction_from_deg": 225 },
      "surface_current": { "speed_kn": 0.4, "set_deg": 90 },
      "sea_state": 2,
      "geometry": {
        "type": "Polygon",
        "coordinates": [[[114.30, 30.52], [114.34, 30.52], [114.34, 30.56], [114.30, 30.56], [114.30, 30.52]]]
      }
    }
  ]
}
```

约束：
- `zone_id` 与 `geometry` 为有效 zone 的必需字段；zone 的气象子字段可缺失为 `null`
- `geometry.type` 仅支持 `Polygon` 与 `MultiPolygon`；其它类型后端忽略，且该 zone 不进入 SSE `weather_zones`
- GeoJSON 坐标格式：`[longitude, latitude]`（与 RFC 7946 一致）；ray-casting 实现须注意与 Java 惯用 `(lat, lon)` 的参数顺序区分
- 同一帧内 zone 几何不得重叠；Step 2 不以数组顺序承载正式优先级语义

### 4.2 Java 类型：`WeatherZoneContext`

新建 record（包 `source/weather/dto`）：

```java
public record WeatherZoneContext(
    String zoneId,
    String weatherCode,
    Double visibilityNm,
    Double precipitationMmPerHr,
    WeatherContext.Wind wind,
    WeatherContext.SurfaceCurrent surfaceCurrent,
    Integer seaState,
    Instant updatedAt,
    ZoneGeometry geometry
) {
    public record ZoneGeometry(String type, Object coordinates) {}
}
```

`ZoneGeometry.coordinates` 用 `Object` 接收 Jackson 反序列化结果（`Polygon` 为 `List<List<List<Double>>>`，`MultiPolygon` 为 `List<List<List<List<Double>>>>`），不引入第三方 GeoJSON 库。后端 PIP 计算时按 `type` 字段强转。`updatedAt` 记录该 zone 对应 weather frame 的生效时间，默认与当前 snapshot 时间一致；若 MQTT payload 后续显式携带 zone 级时间戳，可优先使用该值。

`WeatherContextHolder.Snapshot` 调整为：

```java
record Snapshot(WeatherContext globalContext, List<WeatherZoneContext> zones, Instant updatedAt) {}
```

`WeatherContextHolder.update` 方法签名调整：

```java
public void update(WeatherContext globalContext, List<WeatherZoneContext> zones)
```

`zones` 为 null 时存为空列表，保证下游不需要空值判断。`getFreshContext` 保持现有语义不变；新增：

```java
public List<WeatherZoneContext> getFreshZones(Duration staleThreshold)
```

若 snapshot stale 或无 snapshot，返回空列表。

### 4.3 `RegionalWeatherResolver` 设计

新建 `@Component RegionalWeatherResolver`（包 `source/weather`）：

```java
public Optional<WeatherZoneContext> resolve(double lat, double lon, List<WeatherZoneContext> zones)
```

实现要点：
- Step 2 正式契约要求 zone 几何互斥；`resolve` 仍按 zones 列表顺序遍历并返回第一个命中 zone，仅作为异常输入下的防御性退化
- `containsPoint(lon, lat, geometry)` 对 `Polygon`：对 exterior ring（`coordinates[0]`）做 ray-casting PIP；不检测 hole（`coordinates[1+]` 忽略）
- `containsPoint` 对 `MultiPolygon`：遍历所有成员 Polygon，任意命中则返回 true
- 其它 geometry type 一律返回 false
- zone 的 `geometry` 为 null 时跳过

Ray-casting 参数传入 GeoJSON 惯例的 `(lon, lat)` 顺序（与 `geometry.coordinates` 格式一致）。`resolve` 方法的外部调用参数保持 `(lat, lon)` 顺序（与 Java 惯例一致），内部传给 `containsPoint` 时对调。

### 4.4 `buildEnvironmentContext` 改造

签名变更：

```java
public Map<String, Object> buildEnvironmentContext(ShipStatus ownShip)
```

逻辑（伪代码）：

```
snapshot = weatherContextHolder.getFreshSnapshot(staleThreshold)
if snapshot == null:
    globalContext = null
    zones = []
else:
    globalContext = snapshot.globalContext()
    zones = snapshot.zones()

if zones 非空 && ownShip 非 null:
    matchedZone = resolver.resolve(ownShip.lat, ownShip.lon, zones)
    effectiveWeather = matchedZone 存在
        ? zoneToWeatherContext(matchedZone, snapshot.updatedAt())
        : globalContext
    sourceZoneId = matchedZone 存在 ? matchedZone.zoneId() : null
else:
    effectiveWeather = globalContext
    sourceZoneId = null

alerts = effectiveWeather != null ? evaluateWeatherAlerts(effectiveWeather) : []
weatherPayload = effectiveWeather != null
    ? toWeatherPayload(effectiveWeather) + { "source_zone_id": sourceZoneId }
    : null
zonesPayload = zones 非空 ? toZonesPayload(zones) : null

→ environmentContext = { safety_contour_val, active_alerts: alerts, weather: weatherPayload, weather_zones: zonesPayload }
```

`zoneToWeatherContext(WeatherZoneContext zone, Instant snapshotUpdatedAt)` 把 zone 字段映射为等价 `WeatherContext`，以复用现有 `evaluateWeatherAlerts` 和 `toWeatherPayload` 方法，不为 zone 路径单独复制告警逻辑。其 `updated_at` 取 `zone.updatedAt()`，若 zone 未显式携带则回退到 `snapshotUpdatedAt`。

`source_zone_id` 直接追加到 `weatherPayload` Map 中，不修改后端 `WeatherContext` record；但需同步扩展 SSE/前端类型契约，因为该字段会被前端消费与验收断言读取。

`toZonesPayload` 把 `List<WeatherZoneContext>` 序列化为 `List<Map<String, Object>>`，geometry 与 `updated_at` 一并写出；`geometry == null` 或类型非法的 zone 在进入该步骤前即被过滤，避免向前端传播不可渲染数据。

`WeatherContextHolder` 同步新增 `getFreshSnapshot(Duration)` 方法，返回完整 `Optional<Snapshot>`，供 `buildEnvironmentContext` 在一次调用内同时取得 `globalContext` 与 `zones`（避免两次读取之间发生 volatile 替换）。

### 4.5 前端类型扩展

`schema.d.ts` 新增：

```ts
interface WeatherContext {
  weather_code: WeatherCode | null;
  visibility_nm: number | null;
  precipitation_mm_per_hr: number | null;
  wind: WeatherWind;
  surface_current: WeatherSurfaceCurrent;
  sea_state: number | null;
  source_zone_id?: string | null;  // Step 2 新增，未命中 zone 时为 null
  updated_at: string;
}

interface WeatherZoneContext {
  zone_id: string;
  weather_code: WeatherCode | null;
  visibility_nm: number | null;
  precipitation_mm_per_hr: number | null;
  wind: WeatherWind | null;
  surface_current: WeatherSurfaceCurrent | null;
  sea_state: number | null;
  updated_at: string | null;
  geometry: { type: 'Polygon' | 'MultiPolygon'; coordinates: unknown };
}
```

`EnvironmentContext` 扩展：

```ts
interface EnvironmentContext {
  safety_contour_val: number;
  active_alerts: string[];
  weather?: WeatherContext | null;
  weather_zones?: WeatherZoneContext[] | null;  // Step 2 新增
}
```

### 4.6 前端 MapContainer 天气图层渲染

`MapContainer` 的天气图层由"全图 bbox fill"改为"按 zone 渲染"：

- 有 `weather_zones` 且非空：为每个 `weather_code != CLEAR` 的 zone 以 `zone_id` 为 source/layer 键注册 GeoJSON source + fill layer，颜色与 opacity 映射：
  - `FOG`：fill-color `#d2d7dc`，fill-opacity 0.50
  - `RAIN`：fill-color `#4a6fa5`，fill-opacity 0.35
  - `STORM`：fill-color `#2c2c4a`，fill-opacity 0.55
  - `SNOW`：fill-color `#e8eef5`，fill-opacity 0.40
- Step 2 不引入 `fill-blur`、粒子或纹理特效；若需要减弱硬边，仅允许追加低透明度 outline line layer，进一步视觉增强继续转 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md)
- `weather_zones` 为 null 或空数组：保留 Step 1 全图雾化逻辑（以全球 bbox 作为 GeoJSON source，`visibility_nm` 映射 opacity，上限 0.65）
- 天气图层层级：高于底图与 ENC，低于目标船 / 轨迹 / 告警符号（与 Step 1 fog overlay 层级规则一致）
- zone 数量变化时（如 simulator 换场景）：移除不再存在 `zone_id` 对应的旧 source/layer，添加新的；不整体重建所有图层

---

## 5. Validation

### 5.1 区域天气场景（核心验收）

- `python simulator/weather_mqtt_publisher.py --scene zoned_fog` 启动后，前端海图仅在 fog zone 多边形内出现雾化，多边形外区域保持清晰
- SSE `RISK_UPDATE` 的 `environment_context.weather_zones` 含 `fog-bank-east` zone 数据与 geometry

### 5.2 本船进出区域切换

- 本船 AIS 位置位于 fog zone 内：`environment_context.weather.weather_code == "FOG"`，`active_alerts` 含 `LOW_VISIBILITY`，`weather.source_zone_id == "fog-bank-east"`
- 本船位置离开 fog zone：`weather.weather_code` 切回全局天气值（`CLEAR`），`active_alerts` 不含 `LOW_VISIBILITY`，`weather.source_zone_id == null`
- 命中 zone 时，`environment_context.weather.updated_at` 与对应 `weather_zones[*].updated_at` 保持同帧一致；若 zone 未单独携带时间戳，则回退为 weather snapshot 时间

### 5.3 向后兼容（Step 1 场景）

- `--scene fog`（无 `weather_zones`）：后端正常解析，`environment_context.weather_zones == null`，前端退化为全图雾化；SSE 行为与 Step 1 完成后一致
- 停止 simulator 后 90 秒，全图 fog overlay 与区域 zones overlay 均消失

### 5.4 回归

- 所有现有后端单测保持绿色；`buildEnvironmentContext` 签名变更后，`RiskObjectMetaAssemblerTest` 同步更新（传入 mock `ShipStatus`）
- 前端 `schema.d.ts` 变更后，现有测试夹具（如 `useRiskStore.test.ts`）需同步覆盖 `weather.source_zone_id` 与 `weather_zones` 的 Step 2 契约；显式构造完整 `EnvironmentContext` 字面量的夹具需一并更新
- 天气 zone 区域内的目标仍可点选，地图拖拽不受天气层遮挡（回归点击测试）

---

## 6. Deferred

- 引擎接入 weather 修正（visibility 缩放 / storm penalty）：Step 3
- LLM `LlmRiskContext` 扩展与 advisory 消费：Step 4
- agent tool 注册（`GetWeatherContextTool`、`EvaluateManeuverWithWeatherTool`）：Step 4
- 降雨 / 风场 / 水流矢量等增强天气视觉效果：转 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md)

---

## 7. 需同步修改的文档

- [`step1.md`](./step1.md)：§3 与 §6 中 "引擎消费：Step 2" / "LLM 消费：Step 3" 编号有误（应分别为 Step 3 / Step 4，与 `WEATHER_PLAN.md` 一致）；需同步修正
- [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)：追加 `environment_context.weather_zones` 字段结构与兼容性说明
- [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)：补充 `RegionalWeatherResolver` 在数据处理链路中的位置（MQTT ingest → zone 解析 → effective weather → SSE）
- [`WEATHER_PLAN.md`](./WEATHER_PLAN.md)：Step 2 段标注"已完成"（step 完成后执行）
