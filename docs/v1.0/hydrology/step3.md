# Hydrology Step 3：风险引擎消费 + agent tool

> 文档状态：pending review
> 最后更新：2026-05-03
> 执行状态：pending
> 所属 track：[`HYDROLOGY_PLAN.md`](./HYDROLOGY_PLAN.md)
> 目标：让水文真正影响 `riskScore` 与 agent advisory 的可验证依据。
> 验收：启用惩罚项后，本船进入浅区的 `riskScore` 增加；agent loop 能调用 `QueryBathymetryTool` 并在 advisory `evidence_items` 里出现水深事实项。

---

## 1. Summary

Step 3 在 Step 2 已建立的 `HydrologyContextService` 与 `environment_context.hydrology` 基础上完成两件事：

1. 风险引擎读取本船水文摘要，对每个目标的 `riskScore` 追加同一份本船环境惩罚项。
2. agent loop 暴露水文查询与假设机动水文评估工具，使 advisory 可以引用可审计的水深、浅区与障碍物事实。

本步骤不改变 CPA / TCPA、会遇分类、船域侵入或离散 `riskLevel` 的判定规则。水文只影响连续分值 `riskScore` 与 advisory 证据来源。

---

## 2. Current State And Step Delta

当前代码状态提供了 Step 3 的直接接入点：

- [`HydrologyContextService`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/service/HydrologyContextService.java) 已能基于经纬度与生效 `safety_contour_val` 返回 `HydrologyContext`。
- [`HydrologyContext`](../../../backend/map-service/src/main/java/com/whut/map/map_service/chart/dto/HydrologyContext.java) 已包含 `ownShipMinDepthM`、`nearestShoalNm` 与 `nearestObstruction`。
- [`RiskAssessmentEngine`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java) 当前已在 `finalRiskScore` 上叠加天气 `stormPenalty`，但没有水文 penalty。
- [`AgentToolRegistry`](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/AgentToolRegistry.java) 通过 Spring 注入的 `List<AgentTool>` 自动注册工具；新增水文工具不需要修改注册表主体。
- [`AgentSnapshot`](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentSnapshot.java) 已携带 `frozenOwnShip`，可供假设机动工具读取本船状态。

Step 3 的增量是：在风险评分链路中以开关控制方式消费 hydrology 摘要，并把同一服务能力暴露给 agent tool。水文事实仍不进入 `LlmRiskContext` 静态字段。

---

## 3. In Scope

### 3.1 风险引擎水文 penalty

- 新增 `HydrologyRiskProperties`，绑定 `risk.hydrology.penalty.*` 配置。
- 新增 `HydrologyRiskAdjustmentEvaluator`，把 `HydrologyContext` 转换为一个可叠加到 `riskScore` 的 penalty。
- `RiskAssessmentEngine.consume(...)` 每次风险评估最多解析一次本船 hydrology 摘要，并把结果传入单目标评估逻辑。
- `RiskAssessmentEngine.buildTargetAssessment(...)` 在既有几何 / 船域 / 天气评分之后追加 hydrology penalty。
- penalty 仅修改 `TargetRiskAssessment.riskScore`，不提升 `riskLevel`。

### 3.2 `QueryBathymetryTool`

- 新增 agent tool：`query_bathymetry`
- 输入为 `lon`、`lat`、`radius_nm`
- 输出当前点位的最小水深、最近浅区距离、最近障碍物摘要与 `source: hydrology`
- 工具只读 PostGIS hydrology 数据，不读写风险状态，不依赖 LLM 上下文

### 3.3 `EvaluateManeuverHydrologyTool`

- 新增 agent tool：`evaluate_maneuver_hydrology`
- 输入保持总览约定：`course_change_deg`、`lookahead_min`
- 工具从 `AgentSnapshot.frozenOwnShip()` 读取本船状态，按假设转向后的航向对未来路径做离散采样
- 每个采样点调用 hydrology 查询服务或其路径评估方法，返回路径范围内的最小水深、是否穿越浅区、最近障碍物与 assumptions
- 工具不修改本船状态，不写回 contour，不替代现有 `evaluate_maneuver` 的 CPA/TCPA 评估

### 3.4 Advisory evidence 约束

- advisory prompt 允许 `evidence_items` 引用水文事实。
- 任一水文 evidence 必须来自 `query_bathymetry` 或 `evaluate_maneuver_hydrology` 的 tool result。
- 当前 `AdvisoryPayload.evidence_items` 是 `string[]`，因此 Step 3 不改协议类型；来源标记以文本形式进入单条 evidence，例如：`[source: hydrology] 当前点最小水深 8.3 m，低于安全等深线 10.0 m`。

---

## 4. Out of Scope

- not doing：把 `hydrology` 静态注入 `LlmRiskContext`。本 track 已确定水文深度消费走 agent tool。
- not doing：通过水文因素直接提升 `riskLevel`。Step 3 只修正 `riskScore`，避免污染 CPA / TCPA 规则分级。
- not doing：把完整 OBSTRN 列表、完整几何或完整航线扫描结果塞进 SSE。
- not doing：潮汐、实时水位、S-101 或航道约束建模。
- deferred：非瞬时转向动力学、组合机动、多目标批量机动水文评估；这些应在 `evaluate_maneuver` 物理模型升级时一起重新设计。
- deferred：若 `HydrologyContextService` 查询在 agent 高频调用下成为瓶颈，再引入显式缓存或 `HydrologyFrameContext`；本步骤只要求风险引擎单帧内不按目标重复查询。

当前未新增失去 owner 的后续事项，因此不需要更新 [`../../TODO.md`](../../TODO.md)。

---

## 5. Key Decisions

### 5.1 penalty 与 `riskLevel` 解耦

水文代表本船周边环境约束，不代表本船与某个目标之间的 CPA / TCPA 收敛关系。若直接提升 `riskLevel`，会出现“无目标逼近但因浅区进入 WARNING / ALARM”的语义混杂。

因此 Step 3 只把水文写入连续 `riskScore`：

```text
finalRiskScore = clamp(existingRiskScore + weatherStormPenalty + hydrologyPenalty, 0.0, 1.0)
```

离散 `riskLevel` 仍由 CPA / TCPA、船域侵入与现有规则控制。

### 5.2 hydrology penalty 独立于目标，但在目标结果上体现

浅区与障碍物邻近性只依赖本船位置，因此同一帧内所有目标共享同一个 hydrology penalty。该 penalty 仍写入每个目标的 `riskScore`，因为当前排序、advisory 触发和前端风险对象都以目标结果为主要承载单位。

### 5.3 工具使用当前 `AgentTool` 自动注册模式

`AgentToolRegistry` 已通过 `List<AgentTool>` 自动收录工具。Step 3 只需要：

- 在 `AgentToolNames` 增加常量
- 新增两个 `@Component` tool
- 为两个 tool 提供 `ToolDefinition` schema 与单元测试

不新增 tool plugin SPI、动态扫描或独立 registry。

### 5.4 `evaluate_maneuver_hydrology` 只评估水文可行性

现有 `evaluate_maneuver` 已负责假设机动后的 CPA/TCPA 变化。`evaluate_maneuver_hydrology` 只回答“该假设航向在水深 / 浅区 / 障碍物方面是否可行”，不输出避碰效果，也不推荐最终操纵动作。

LLM 若要输出转向建议，应同时引用：

- `evaluate_maneuver` 的 CPA/TCPA 结果
- `evaluate_maneuver_hydrology` 的水文可行性结果

---

## 6. Detailed Design

### 6.1 Hydrology risk properties

新增配置类：

```java
@ConfigurationProperties(prefix = "risk.hydrology.penalty")
public class HydrologyRiskProperties {
    private boolean enabled = false;
    private double shoalMaxPenaltyScore = 0.12;
    private double obstructionMaxPenaltyScore = 0.08;
    private double shoalInfluenceNm = 0.5;
    private double obstructionInfluenceNm = 0.5;
}
```

默认开关必须为 `false`，与 `HYDROLOGY_PLAN.md` 的灰度要求一致。配置写入 [`application.properties`](../../../backend/map-service/src/main/resources/application.properties) 时只增加真实默认值，不写 mock / dummy LLM 配置。

### 6.2 Hydrology adjustment evaluator

新增 `com.whut.map.map_service.risk.hydrology.HydrologyRiskAdjustmentEvaluator`：

```java
public HydrologyRiskAdjustment evaluate(HydrologyContext hydrology, double safetyContourMeters);
```

返回对象：

```java
public record HydrologyRiskAdjustment(
        double penaltyScore,
        List<String> reasons
) {
}
```

计算规则：

- 配置关闭、`hydrology == null` 或 `HydrologyContextService` 不可用时，返回 `penaltyScore = 0.0`。
- `ownShipMinDepthM != null && ownShipMinDepthM < safetyContourMeters` 时，追加浅区 penalty。
- `nearestShoalNm != null` 时，按 `shoalInfluenceNm` 线性衰减；距离为 `0` 时达到浅区最大 penalty。
- `nearestObstruction != null && distanceNm != null` 时，按 `obstructionInfluenceNm` 线性衰减。
- 多个来源相加后 clamp 到 `shoalMaxPenaltyScore + obstructionMaxPenaltyScore`。
- `reasons` 至少区分 `SHOAL_DEPTH`、`SHOAL_PROXIMITY`、`OBSTRUCTION_PROXIMITY`，供测试和后续解释使用；Step 3 不要求把 reasons 下发到前端协议。

### 6.3 RiskAssessmentEngine 接入点

`RiskAssessmentEngine.consume(...)` 在循环目标前解析一次 hydrology：

```java
HydrologyContext hydrology = resolveHydrologyContext(ownShip);
HydrologyRiskAdjustment hydrologyAdjustment =
        hydrologyRiskAdjustmentEvaluator.evaluate(hydrology, safetyContourMeters);
```

然后把 `hydrologyAdjustment` 传入 `buildTargetAssessment(...)` 或等价私有 helper。若保留现有 public `buildTargetAssessment(...)` 供测试调用，可新增重载以避免大范围改动测试夹具：

```java
public TargetRiskAssessment buildTargetAssessment(
        String targetId,
        CpaTcpaResult cpaResult,
        ShipStatus ownShip,
        ShipStatus targetShip,
        ShipDomainResult domainResult,
        CvPredictionResult predictionResult,
        EncounterClassificationResult encounterResult
)
```

该 public 方法保持现有行为，内部委托给带 hydrology 参数的私有方法并传入 zero adjustment。`consume(...)` 使用私有方法传入真实 hydrology adjustment。

`finalRiskScore` 的顺序为：

1. 几何分与船域分按现有权重融合
2. 乘以 encounter modifier
3. clamp 到 `[0, 1]`
4. 追加 weather storm penalty
5. 追加 hydrology penalty
6. 最终 clamp 到 `[0, 1]`

该顺序保证配置关闭时完全行为保持，也保证 hydrology penalty 不影响 DCPA/TCPA 阈值判定。

### 6.4 Hydrology tool service extension

现有 `HydrologyContextService.resolve(latitude, longitude, effectiveSafetyContourMeters)` 可满足点位查询，但 agent tool 需要 radius 与路径评估能力。Step 3 在同一 service 内补充只读方法，避免新增并行查询层：

```java
public HydrologyContext resolve(
        double latitude,
        double longitude,
        double effectiveSafetyContourMeters,
        double searchRadiusNm
);

public HydrologyRouteAssessment evaluateRoute(
        List<GeoPoint> sampledPoints,
        double effectiveSafetyContourMeters,
        double searchRadiusNm
);
```

`resolve(latitude, longitude, safetyContour)` 保持为现有默认半径的便捷方法，以免破坏 Step 2 调用方。新增 overload 中的 `searchRadiusNm` 表示调用方请求半径，不直接覆盖现有两类查询上限。实际查询半径必须分别计算：

- 浅区查询：`effectiveShoalRadiusNm = Math.min(searchRadiusNm, MAX_SHOAL_SEARCH_NM)`，当前上限保持 `3.0 nm`
- 障碍物查询：`effectiveObstructionRadiusNm = Math.min(searchRadiusNm, MAX_OBSTRUCTION_SEARCH_NM)`，当前上限保持 `2.0 nm`

因此 `query_bathymetry.radius_nm = 5.0` 只扩大 caller 请求范围，不会让 OBSTRN 查询超过当前 `2.0 nm` 的设计边界。tool result 应同时返回 caller 请求半径与两类 effective radius，便于后续解释为何未命中更远障碍物。

`GeoPoint` 在现有代码库中不存在，Step 3 明确新建轻量 DTO：

```java
public record GeoPoint(
        double latitude,
        double longitude
) {
}
```

该 record 放在 `com.whut.map.map_service.chart.dto`，只表达路径采样点坐标，不承载速度、航向或时间。`HydrologyRouteAssessment` 至少包含：

```java
public record HydrologyRouteAssessment(
        Double minDepthM,
        boolean crossesShoal,
        Double nearestShoalNm,
        NearestObstructionSummary nearestObstruction,
        int sampleCount
) {
}
```

路径评估以有限采样为主，不拉完整几何到 tool result。采样间隔初始可取 30 秒或 `lookahead_min / 12` 的较小步长，样本数上限 24，避免一次工具调用造成过多 PostGIS 查询。

### 6.5 `query_bathymetry` tool contract

工具类：

```java
@Component
public class QueryBathymetryTool implements AgentTool {
}
```

`AgentToolNames` 常量：

```java
public static final String QUERY_BATHYMETRY = "query_bathymetry";
```

输入 schema：

```json
{
  "type": "object",
  "properties": {
    "lon": { "type": "number" },
    "lat": { "type": "number" },
    "radius_nm": { "type": "number" }
  },
  "required": ["lon", "lat", "radius_nm"]
}
```

校验规则：

- `lon` 范围 `[-180, 180]`
- `lat` 范围 `[-90, 90]`
- `radius_nm` 范围 `(0, 5]`

成功结果：

```json
{
  "status": "OK",
  "source": "hydrology",
  "position": { "lon": -73.91, "lat": 40.59 },
  "radius_nm": 1.0,
  "effective_search_radius_nm": {
    "shoal": 1.0,
    "obstruction": 1.0
  },
  "safety_contour_m": 10.0,
  "own_ship_min_depth_m": 8.3,
  "nearest_shoal_nm": 0.0,
  "nearest_obstruction": {
    "category": "WRECK",
    "distance_nm": 0.71,
    "bearing_deg": 37
  }
}
```

错误结果沿用现有工具模式：`status = ERROR`，并提供 `error_code` 与 `message`。

### 6.6 `evaluate_maneuver_hydrology` tool contract

工具类：

```java
@Component
public class EvaluateManeuverHydrologyTool implements AgentTool {
}
```

`AgentToolNames` 常量：

```java
public static final String EVALUATE_MANEUVER_HYDROLOGY = "evaluate_maneuver_hydrology";
```

输入 schema：

```json
{
  "type": "object",
  "properties": {
    "course_change_deg": { "type": "number" },
    "lookahead_min": { "type": "number" }
  },
  "required": ["course_change_deg", "lookahead_min"]
}
```

校验规则：

- `abs(course_change_deg) <= 180`
- `lookahead_min` 范围 `(0, 30]`
- `AgentSnapshot.frozenOwnShip()` 必须存在，且本船 `lat/lon/sog/cog` 可用

路径采样必须复用现有 [`GeoUtils`](../../../backend/map-service/src/main/java/com/whut/map/map_service/shared/util/GeoUtils.java) 的局部位移口径，不在 Step 3 新增另一套球面 destination-point 算法。采样步骤为：

1. `assumedCogDeg = normalize360(ownShip.cog + courseChangeDeg)`
2. `velocity = GeoUtils.toVelocity(ownShip.sog, assumedCogDeg)`，得到 east / north 方向速度分量
3. 按采样时间点 `tSeconds` 计算 `eastMeters = velocity[0] * tSeconds`、`northMeters = velocity[1] * tSeconds`
4. 调用 `GeoUtils.displace(ownShip.latitude, ownShip.longitude, eastMeters, northMeters)` 生成 `GeoPoint(latitude, longitude)`

该方法与当前风险侧局部平面近似保持一致。若后续需要长航程大圆推算，应另立导航几何工具升级，不在 Step 3 内引入。

成功结果：

```json
{
  "status": "OK",
  "source": "hydrology",
  "snapshot_version": 123,
  "maneuver": {
    "course_change_deg": 20.0,
    "lookahead_min": 10.0,
    "assumed_cog_deg": 92.0
  },
  "route_hydrology": {
    "min_depth_m": 8.3,
    "crosses_shoal": true,
    "nearest_shoal_nm": 0.0,
    "nearest_obstruction": {
      "category": "WRECK",
      "distance_nm": 0.42,
      "bearing_deg": 84
    },
    "sample_count": 12
  },
  "assumptions": [
    "instantaneous_course_change",
    "constant_speed",
    "target_state_ignored",
    "tidal_level_not_modeled"
  ]
}
```

该工具不得调用 `CpaTcpaBatchCalculator`，也不得输出 `delta_dcpa_nm`。CPA/TCPA 假设评估仍由 `evaluate_maneuver` 负责。

### 6.7 Prompt update

更新 [`system-advisory.txt`](../../../backend/map-service/src/main/resources/prompts/system-advisory.txt)：

- 保留现有“只能引用工具结果事实”的总规则。
- 增加水文规则：若 `recommended_action` 涉及转向且环境中存在 `SHOAL_PROXIMITY`、`OBSTRUCTION_NEARBY` 或水文工具可用，LLM 在输出该转向前应调用 `evaluate_maneuver_hydrology`。
- 若 `evidence_items` 引用水深、浅区或障碍物事实，必须包含 `[source: hydrology]` 标记，并引用 tool result 中的具体字段值。
- 若 `evaluate_maneuver_hydrology.route_hydrology.crosses_shoal == true`，不得把该转向描述为“水文安全”；应改为保守建议或说明该机动受浅区限制。

Chat agent prompt 不强制每轮调用水文工具。工具描述已经提供使用边界，用户明确询问水深、浅区、障碍物或转向水文可行性时，LLM 可自主调用。

---

## 7. Implementation Order

### Phase 1：配置与 risk penalty

- 新增 `HydrologyRiskProperties`
- 新增 `HydrologyRiskAdjustment` 与 `HydrologyRiskAdjustmentEvaluator`
- `RiskAssessmentEngine` 注入 hydrology service、contour 状态与 evaluator
- 配置 `risk.hydrology.penalty.enabled=false`
- 补齐风险引擎单测，证明默认关闭行为保持

### Phase 2：工具查询能力

- 扩展 `HydrologyContextService` 支持可配置 radius 与 route assessment
- 新增 `QueryBathymetryTool`
- 新增 `EvaluateManeuverHydrologyTool`
- `AgentToolNames` 增加两个常量
- 补齐 tool 单元测试与注册表可见性测试

### Phase 3：advisory prompt 与证据约束

- 更新 advisory system prompt
- 更新 advisory 相关测试，覆盖水文 evidence 只来自工具结果
- 确认 `AdvisoryPayload.evidence_items` 仍为 `string[]`，不引入协议迁移

### Phase 4：文档同步

- 更新 [`../agent/AGENT_LOOP_PLAN.md`](../agent/AGENT_LOOP_PLAN.md) §3.8 或工具目录真值，把 `query_bathymetry` 与 `evaluate_maneuver_hydrology` 纳入 agent tool 列表。
- 如 `EVENT_SCHEMA.md` 对 advisory evidence 来源有枚举说明，补充 hydrology source 的文字约束；不改变字段类型。
- 实施完成后将 [`HYDROLOGY_PLAN.md`](./HYDROLOGY_PLAN.md) Step 3 状态更新为已完成。

---

## 8. File Impact

预期至少触达以下区域：

| 区域 | 文件 / 包 | 目的 |
| --- | --- | --- |
| 风险配置 | `backend/map-service/src/main/java/com/whut/map/map_service/risk/config/HydrologyRiskProperties.java` | hydrology penalty 灰度配置 |
| 风险评估 | `backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java` | 追加 hydrology penalty |
| 风险评估 | `backend/map-service/src/main/java/com/whut/map/map_service/risk/hydrology/` | evaluator 与 adjustment record |
| 水文查询 | `backend/map-service/src/main/java/com/whut/map/map_service/chart/service/HydrologyContextService.java` | radius 查询与 route assessment |
| 水文 DTO | `backend/map-service/src/main/java/com/whut/map/map_service/chart/dto/` | route assessment / point 类型 |
| agent tools | `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/AgentToolNames.java` | 工具名常量 |
| agent tools | `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/QueryBathymetryTool.java` | 水深查询工具 |
| agent tools | `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/EvaluateManeuverHydrologyTool.java` | 假设机动水文评估工具 |
| prompt | `backend/map-service/src/main/resources/prompts/system-advisory.txt` | evidence 约束 |
| 配置 | `backend/map-service/src/main/resources/application.properties` | 默认关闭 hydrology penalty |
| 文档 | `docs/v1.0/agent/AGENT_LOOP_PLAN.md` | 工具目录同步 |
| 文档 | `docs/EVENT_SCHEMA.md` | advisory evidence 来源说明 |

---

## 9. Validation

### 9.1 Risk engine tests

- `HydrologyRiskAdjustmentEvaluatorTest`
  - 配置关闭时 penalty 为 `0`
  - 本船水深低于 safety contour 时产生 `SHOAL_DEPTH`
  - 最近浅区距离从 `0` 增至阈值边界时 penalty 单调递减
  - 最近障碍物超出阈值时不产生 obstruction penalty
  - `SHOAL_DEPTH` 与 `SHOAL_PROXIMITY` 同时触发、且障碍物也在近距阈值内时，最终 penalty clamp 到 `shoalMaxPenaltyScore + obstructionMaxPenaltyScore`

- `RiskAssessmentEngineTest`
  - 默认配置关闭时，既有 `riskScore` 与 `riskLevel` 保持不变
  - 启用 hydrology penalty 后，浅区场景 `riskScore` 增加
  - 启用 hydrology penalty 后，`riskLevel` 不因 hydrology 单独提升
  - hydrology 查询不可用时不抛异常，评分退化为既有逻辑

### 9.2 Agent tool tests

- `QueryBathymetryToolTest`
  - 合法 lon/lat/radius 返回 `source: hydrology`
  - 无数据源或 service 返回 `null` 时返回可解释错误或空事实，不编造水深
  - lon/lat/radius 越界返回 `INVALID_ARGUMENT`

- `EvaluateManeuverHydrologyToolTest`
  - 缺少 `frozenOwnShip` 时返回 `OWN_SHIP_UNAVAILABLE`
  - 合法 course change 生成 route assessment 与 assumptions
  - `course_change_deg` / `lookahead_min` 越界返回 `INVALID_ARGUMENT`
  - 工具不调用 CPA/TCPA 计算，不输出 `delta_dcpa_nm`

- `AgentToolRegistryTest`
  - `query_bathymetry` 与 `evaluate_maneuver_hydrology` 出现在 `getToolDefinitions()`
  - 重名检测仍有效

### 9.3 Advisory tests

- prompt / orchestrator 相关测试覆盖：当 hydrology evidence 出现时，内容包含 `[source: hydrology]`，且前序工具调用包含水文工具。
- 反例测试使用 mock LLM response 构造“final JSON 里包含水深数字 / `[source: hydrology]`，但 agent loop 历史中没有 `query_bathymetry` 或 `evaluate_maneuver_hydrology` tool call”的场景；断言 orchestrator / advisory 发布层拒绝发布该 advisory。该测试验证解析与发布门控，不验证真实 LLM 行为。
- 若 `evaluate_maneuver_hydrology` 返回 `crosses_shoal=true`，输出不得把该机动描述为水文安全。

### 9.4 Manual validation

- 设置 `risk.hydrology.penalty.enabled=true`，将本船置于低于 `safety_contour_val` 的水域，确认同一目标的 `riskScore` 高于关闭开关时的基线。
- 通过 agent chat 或 advisory 触发 `query_bathymetry`，确认 tool result 含 `source: hydrology` 与水深事实。
- 触发一次建议转向，确认 advisory `evidence_items` 中出现带 `[source: hydrology]` 的水文事实项。

---

## 10. Assumptions And Open Risks

- `HydrologyContextService` 查询仍依赖 PostGIS `enc_depare / enc_obstrn` 可用；无数据源模式只能返回空事实，不能验证真实水深。
- 当前 route assessment 使用离散采样，不等同于连续航迹几何相交证明；tool result 必须保留采样 assumptions。
- `evidence_items` 仍是 `string[]`，来源标记依赖 prompt 与测试约束。若后续需要机器可校验的 evidence source，应另立协议迁移步骤，把 evidence item 从 string 升级为结构化对象。
- hydrology penalty 与 weather storm penalty 均是 additive score adjustment。若两者同时启用导致高分集中，应在后续评分治理中统一环境因子权重，而不是在 Step 3 内重写评分模型。
