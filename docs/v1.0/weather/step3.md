# Weather Step 3：风险引擎消费天气

> 文档状态：active
> 最后更新：2026-04-29
> 执行状态：completed
> 所属 track：[`WEATHER_PLAN.md`](./WEATHER_PLAN.md)
> 目标：让天气因素以受控方式修正风险分级与评分；默认全关，通过配置项逐路径开启。

---

## 1. Summary

Step 3 在 `RiskAssessmentEngine` 内引入两条独立的天气修正路径，并补充一个最小前端状态提示：

1. **Visibility 阈值缩放**：能见度不足时，将 `alarmDcpaNm / warningDcpaNm / cautionDcpaNm` 按比例放大，使同一目标在雾天更早触发更高风险等级。
2. **Storm penalty**：极端海况（`weather_code == STORM` 或 `sea_state >= 7`）时向 `finalRiskScore` 叠加独立惩罚分。
3. **状态提示**：天气修正开关启用且当前天气触发修正时，前端显示“气象修正已启用”类最小提示，避免风险提前跳级缺少可见解释入口。

强流约束（`surface_current → 机动建议置信度修正`）**不在本 step** 实现，保留为 Step 4 advisory 消费路径。两条路径均默认关闭，通过配置项 `risk.weather.visibility.enabled` / `risk.weather.storm.enabled` 分别开启。

Step 2 已落地的 `effective weather` 解算（`RegionalWeatherResolver` → `buildEnvironmentContext`）由 `RiskAssessmentEngine` 在内部复用，不重复下发给调用方。

---

## 2. 当前状态（Step 2 完成后）

- `RiskAssessmentEngine.buildTargetAssessment` 签名：7 参数，不含任何天气入参
- `classifyRisk(double dcpaNm, double tcpaSec)` 直接读取 `riskProperties.getXxxDcpaNm()` 固定阈值
- `WeatherContextHolder` 与 `RegionalWeatherResolver` 已存在，未被引擎消费
- 风险分数计算链：`geometryScore → domainScore → encounterModifier → finalRiskScore`，无天气介入

---

## 3. In Scope

### 3.1 新增配置类

新建 `risk/config/WeatherRiskProperties.java`，`@ConfigurationProperties(prefix = "risk.weather")`：

```java
@Data
@Component
@ConfigurationProperties(prefix = "risk.weather")
public class WeatherRiskProperties {

    private VisibilityConfig visibility = new VisibilityConfig();
    private StormConfig storm = new StormConfig();

    @Data
    public static class VisibilityConfig {
        private boolean enabled = false;
        private double lowVisNm = 2.0;       // 低能见度阈值；< 此值应用 scaleLow
        private double veryLowVisNm = 0.5;   // 极低能见度阈值；< 此值应用 scaleVeryLow
        private double scaleLow = 1.5;       // 放大系数（低能见度）
        private double scaleVeryLow = 2.0;   // 放大系数（极低能见度）
    }

    @Data
    public static class StormConfig {
        private boolean enabled = false;
        private double penaltyScore = 0.15;  // 并入 finalRiskScore 的惩罚分
        private int seaStateThreshold = 7;   // sea_state >= 此值触发 penalty
    }
}
```

对应 `application.properties` 默认段（不写出时全部取默认值）：

```properties
risk.weather.visibility.enabled=false
risk.weather.visibility.low-vis-nm=2.0
risk.weather.visibility.very-low-vis-nm=0.5
risk.weather.visibility.scale-low=1.5
risk.weather.visibility.scale-very-low=2.0
risk.weather.storm.enabled=false
risk.weather.storm.penalty-score=0.15
risk.weather.storm.sea-state-threshold=7
```

### 3.2 `RiskAssessmentEngine` 改造

#### 3.2.1 构造函数注入

追加四个依赖：`WeatherContextHolder`、`RegionalWeatherResolver`、`WeatherAlertProperties`、`WeatherRiskAdjustmentEvaluator`。现有四个参数不变，构造函数追加四项。

`WeatherRiskProperties` 不直接由 `RiskAssessmentEngine` 读取，而是由共享 `WeatherRiskAdjustmentEvaluator` 持有并统一判定 visibility / storm adjustment。这样 `RiskAssessmentEngine` 与 `EnvironmentContextService` 不重复实现条件逻辑。

#### 3.2.2 `classifyRisk` 签名重构

当前 `classifyRisk(double dcpaNm, double tcpaSec)` 直接读取 `riskProperties`，不符合"通过 per-frame 计算生效阈值传入"的要求。重构为接受显式阈值参数：

```java
private String classifyRisk(
    double dcpaNm, double tcpaSec,
    double alarmDcpaNm, double alarmTcpaSec,
    double warningDcpaNm, double warningTcpaSec,
    double cautionDcpaNm, double cautionTcpaSec
)
```

内部逻辑不变（阈值比较逻辑原样保留），`riskProperties` 字段引用移除，由调用方显式传入 DCPA 与 TCPA 阈值。DCPA 阈值使用 weather-adjusted effective values；TCPA 阈值仍传入原始 `riskProperties` 值，不随天气缩放。
`classifyRisk` 不再直接读取 `riskProperties`，保持规则侧纯净。

#### 3.2.3 私有辅助方法

```java
// 从 WeatherContextHolder 解析本船当前位置命中的 effective weather。
// 若 holder stale / 无 snapshot，返回 Optional.empty()。
// zones 为空但 global weather 存在时，返回 global weather，保持 Step 1 单快照兼容。
private Optional<WeatherContext> resolveEffectiveWeather(ShipStatus ownShip)

// 将 WeatherZoneContext 映射为 WeatherContext。
// 规则与 EnvironmentContextService 保持一致，updated_at 优先取 zone.updated_at，
// 缺失时回退到 snapshot.updated_at。
private WeatherContext zoneToWeatherContext(WeatherZoneContext zone, Instant snapshotUpdatedAt)

// 从 WeatherAlertProperties 读取 stale threshold。
private Duration staleThreshold()
```

`resolveEffectiveWeather` 内部流程（伪代码）：

```
snapshot = weatherContextHolder.getFreshSnapshot(configured stale threshold)
if snapshot 为空: return Optional.empty()

zones = snapshot.zones()
if zones 非空 && ownShip 非 null:
    matchedZone = regionalWeatherResolver.resolve(ownShip.lat, ownShip.lon, zones)
    if matchedZone 存在: return Optional.of(zoneToWeatherContext(matchedZone))
    return Optional.of(snapshot.globalContext())
else:
    return Optional.of(snapshot.globalContext())
```

stale threshold 复用现有 `engine.risk-meta.weather-alert.stale-threshold-seconds` 配置，不在 `RiskAssessmentEngine` 中硬编码 60s。

`zoneToWeatherContext` 复用与 `EnvironmentContextService` 相同的 zone→`WeatherContext` 映射规则，但独立实现（不引用 environment / assembler 包），保持引擎包不反向依赖 pipeline / environment。

#### 3.2.4 `buildTargetAssessment` 改造点

在现有逻辑之前插入三行准备代码，之后调整 `classifyRisk` 与 `finalRiskScore` 计算：

```
// 1. 解析有效天气并计算共享 adjustment
Optional<WeatherContext> weatherOpt = resolveEffectiveWeather(ownShip)
WeatherContext weather = weatherOpt.orElse(null)
WeatherRiskAdjustment weatherAdjustment = weatherRiskAdjustmentEvaluator.evaluate(weather)

// 2. 计算生效 DCPA 阈值（visibility scaling）
double scale = weatherAdjustment.visibilityScale()
double effectiveAlarmDcpaNm   = riskProperties.getAlarmDcpaNm()   * scale
double effectiveWarningDcpaNm = riskProperties.getWarningDcpaNm() * scale
double effectiveCautionDcpaNm = riskProperties.getCautionDcpaNm() * scale

// 3. 调用重构后的 classifyRisk，传入生效阈值（替代原先的无参版本）
riskLevel = classifyRisk(dcpaNm, rawTcpaSec,
    effectiveAlarmDcpaNm, riskProperties.getAlarmTcpaSec(),
    effectiveWarningDcpaNm, riskProperties.getWarningTcpaSec(),
    effectiveCautionDcpaNm, riskProperties.getCautionTcpaSec())

// ... 原有 approaching / tcpaScore / dcpaScore / domainScore / encounterModifier 计算不变 ...
// dcpaScore 继续使用原始 cautionDcpaNm，避免坏天气下已有风险评分反向下降：
double dcpaScore = 1.0 - clamp(dcpaNm / riskProperties.getCautionDcpaNm(), 0, 1)

// 4. storm penalty 叠加（finalRiskScore 已 clamp 后再加）
double weatherPenalty = weatherAdjustment.stormPenalty()
finalRiskScore = clamp(finalRiskScore + weatherPenalty, 0.0, 1.0)
```

`consume()` 方法无需修改，它已按 `buildTargetAssessment` 委托；`buildTargetAssessment` 公开签名 **不变**，ShipDispatcher 调用方无需修改。

### 3.3 最小修正状态下发与前端提示

Step 3 增加一个最小修正状态字段和 UI 状态提示，不把详细解释文本写入引擎。

后端在 `EnvironmentContextService` 组装 `environment_context.weather` 时追加两个可选字段：

```json
{
  "weather_code": "FOG",
  "visibility_nm": 0.8,
  "risk_adjustment_active": true,
  "risk_adjustment_reasons": ["VISIBILITY"]
}
```

字段语义：

- `risk_adjustment_active`：当至少一条天气修正路径已启用且当前 effective weather 命中修正条件时为 `true`；否则为 `false`。
- `risk_adjustment_reasons`：仅包含已实际生效的修正原因，取值范围为 `VISIBILITY` / `STORM`。
- weather 为 `null` 时不输出该字段；前端按无天气修正处理。
- 该状态必须与 `RiskAssessmentEngine` 使用同一份 `WeatherRiskProperties` 和同一套 weather adjustment 判定，避免 UI 显示与实际风险修正漂移。
- 前端仅根据该字段显示“气象修正已启用”或等价短标签，不根据 `active_alerts` 推断修正状态；`active_alerts` 表示天气/水文事实告警，不等价于 `risk.weather.*.enabled`。
- 该提示仅表达“风险引擎正在消费天气修正”，不枚举所有 zone，不替代 Step 4 advisory `evidence_items`。

为避免引擎和 `EnvironmentContextService` 双写条件逻辑，实施时应优先抽出一个小型内部判定组件或静态辅助方法（例如 weather risk adjustment evaluator），同时服务于：

- `RiskAssessmentEngine`：读取 scale / penalty，用于实际风险修正。
- `EnvironmentContextService`：读取 active / reasons，用于下发最小状态字段。

该组件不持有上下游状态，不负责 zone 解析，只消费已解析出的 `WeatherContext` 与 `WeatherRiskProperties`。

---

## 4. Out of Scope

- **deferred**：`surface_current → 机动建议置信度修正`，转 **Step 4**（advisory `evidence_items`）；不进入当前 CPA 修正链路
- **deferred**：LLM `LlmRiskContext.weather` 扩展与 advisory prompt 约束，转 **Step 4**
- **deferred**：agent tool 注册（`GetWeatherContextTool`、`EvaluateManeuverWithWeatherTool`），转 **Step 4**
- **not doing**：TCPA 阈值随天气缩放；COLREGS 规则 19 语义的影响主要在 CPA 距离感知，TCPA 不做对等缩放
- **not doing**：`TargetRiskAssessment` 字段追加详细天气修正元数据（如 `visibilityScale`、`weatherPenalty`）；解释路径走 Step 4 LLM，不在引擎内拼文本
- **not doing**：复杂天气修正说明、zone 明细面板或视觉增强；Step 3 只做最小状态提示，增强展示在 Step 4 或 visual track 收口

---

## 5. 文件与包影响

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `risk/config/WeatherRiskProperties.java` | 新建 | `@ConfigurationProperties(prefix = "risk.weather")` |
| `risk/weather/WeatherRiskAdjustment.java` | 新建 | 承载 visibility scale、storm penalty 与生效原因 |
| `risk/weather/WeatherRiskAdjustmentEvaluator.java` | 新建 | 共享 visibility / storm adjustment 判定，避免引擎与环境下发双写逻辑 |
| `risk/engine/risk/RiskAssessmentEngine.java` | 修改 | 注入 weather holder / resolver / stale 配置 / evaluator；重构 `classifyRisk`；改造 `buildTargetAssessment` |
| `application.properties` | 修改 | 追加 `risk.weather.*` 默认关闭段 |
| `risk/environment/EnvironmentContextService.java` | 修改 | 在 `environment_context.weather` 中追加最小 weather risk adjustment 状态；复用同一套 adjustment 判定 |
| `frontend/src/types/schema.d.ts` | 修改 | 扩展 weather 类型，新增 `risk_adjustment_active` / `risk_adjustment_reasons` |
| `frontend/src/components/Dashboard/StatusPanel.tsx` | 修改 | 在当前天气摘要区域根据后端字段显示“气象修正已启用”最小状态提示 |
| `frontend/src/components/Dashboard/StatusPanel.test.tsx` | 修改 | 覆盖 active / inactive 两类状态提示 |
| `backend/map-service/src/test/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngineTest.java` | 修改 | 更新构造器辅助方法；新增 visibility / storm / stale 回归测试 |
| `backend/map-service/src/test/java/com/whut/map/map_service/risk/pipeline/ShipDispatcherTest.java` | 修改 | 更新 `RiskAssessmentEngine` stub 构造函数调用 |

不需要修改的文件：
- `ShipDispatcher`：`buildTargetAssessment` 公开签名不变，调用点不受影响
- `WeatherContextHolder`、`RegionalWeatherResolver`：接口不变，复用现有 API
- `RiskObjectMetaAssembler`：不负责 `environment_context.weather` 组装，本 step 不应把 weather adjustment 字段写入该类

---

## 6. 约束与不变量

- 所有 `risk.weather.*.enabled` 开关默认 `false`；新增任何配置不得改变默认行为
- `weather == null`（stale / 无信号）时，`WeatherRiskAdjustmentEvaluator` 返回 `visibilityScale=1.0`、`stormPenalty=0.0`、`reasons=[]`，行为等同无天气接入
- Storm penalty 叠加后必须保持 `finalRiskScore ∈ [0.0, 1.0]`（再次 clamp）
- Visibility 缩放不得降低基线 `riskScore`；离散 `riskLevel` 可因有效 DCPA 阈值放大而提前，几何评分仍以原始 CPA 阈值计算
- `classifyRisk` 重构只改参数来源，逻辑不变；risk level 硬边界（ALARM / WARNING / CAUTION / SAFE）不新增或移除等级
- `buildTargetAssessment` 公开签名不变，不需要更新 ShipDispatcher 调用点
- 引擎包（`risk/engine/risk`）不引用 `risk/pipeline/assembler` 包，避免循环依赖；`zoneToWeatherContext` 在引擎内独立实现
- 引擎与 `EnvironmentContextService` 的 adjustment 条件必须共享同一套 `WeatherRiskProperties` 与判定逻辑；不得让前端状态字段从 `active_alerts` 或独立阈值反推

---

## 7. Validation

### 7.1 回归（开关全关）

- 所有现有后端单测保持绿色；`RiskAssessmentEngine` 相关测试在未注入 weather 时行为与 Step 2 完成后完全一致
- 现有集成场景（`--scene clear` / `--scene fog`）在 `risk.weather.visibility.enabled=false` 下，风险等级与分数与 Step 2 完成后保持一致

### 7.2 Visibility 缩放（核心验收）

- 单测：选取目标 DCPA = `cautionDcpaNm × 1.1`（如 0.55 nm，基线为 SAFE），TCPA 选取 `cautionTcpaSec` 内的值（如 300s），注入 `visibility_nm=0.8` + `enabled=true`；期望 `riskLevel == CAUTION`
- 单测：同参数，`enabled=false`；期望 `riskLevel == SAFE`（与基线一致）
- 单测：`visibility.enabled=true` 时，低能见度不得使同一几何输入的 `riskScore` 低于开关关闭时的基线值
- 集成：`--scene fog` + `visibility.enabled=true` + 目标在边界 DCPA；SSE `risk_level` 从 `SAFE` 变 `CAUTION`

### 7.3 Storm penalty（核心验收）

- 单测：基线 `finalRiskScore=0.70`，注入 `weather_code=STORM` + `enabled=true`；期望 `finalRiskScore ≥ 0.85`（0.70 + 0.15）且 `≤ 1.0`
- 单测：同参数，`enabled=false`；期望 `finalRiskScore == 0.70`
- 单测：`sea_state=7`（非 STORM 天气码）+ `enabled=true`；期望同样触发 penalty

### 7.4 Stale / null weather

- 单测：`WeatherContextHolder` 无 snapshot 时，引擎行为与无 weather 注入完全一致
- 单测：snapshot 超过 `engine.risk-meta.weather-alert.stale-threshold-seconds` 配置阈值时，同上

### 7.5 区域化天气场景（Step 2 协同）

- 本船位于 fog zone 外（命中全局 CLEAR）+ `visibility.enabled=true`：visibility scale = 1.0，无修正
- 本船进入 fog zone（命中 FOG，`visibility_nm=0.8`）+ `visibility.enabled=true`：scale = 1.5，风险等级可提前一级

### 7.6 前端状态提示

- `risk.weather.visibility.enabled=true` 且 effective weather 命中低能见度阈值时，天气摘要区域显示“气象修正已启用”或等价短标签
- 仅存在 `LOW_VISIBILITY` alert 但 `risk.weather.visibility.enabled=false` 时，`risk_adjustment_active=false`，不显示“气象修正已启用”
- `risk.weather.storm.enabled=true` 且 effective weather 命中 `STORM` 或 `sea_state >= seaStateThreshold` 时，`risk_adjustment_reasons` 包含 `STORM`
- 该提示不依赖 `weather_zones` 数量；单快照 fog 与 zoned fog 命中路径均可显示

---

## 8. 开放风险

- **反直觉跳级**：`visibility.enabled=true` 下，目标可能在用户未注意时从 `SAFE` 跳 `CAUTION`，令用户误认为报警异常。缓解：Step 3 先提供“气象修正已启用”最小状态提示；Step 4 的 advisory `evidence_items` 必须进一步显式说明"由于能见度不足，CAUTION 阈值已由 0.5 nm 放宽至 0.75 nm"。
- **双重 zone 解析**：引擎与 `EnvironmentContextService` 各自调用 `RegionalWeatherResolver.resolve()`；同一帧内解析结果应一致（zone 几何未变化），但若 `WeatherContextHolder` 在两次调用之间发生 volatile 更新，两者读取的可能是不同 snapshot。此场景在当前架构下无法原子消除，后续可在 `ShipDispatcher` 统一解析并传递；本 step 接受此不一致窗口（帧更新频率远低于 MQTT 刷新频率，实际影响极小）。
- **状态字段漂移**：实际风险修正发生在 `RiskAssessmentEngine`，状态字段由 `EnvironmentContextService` 下发。若两处分别实现条件判断，前端可能显示“已启用”但引擎未修正，或相反。缓解：将 visibility / storm 判定收敛为共享 evaluator，并在后端测试中覆盖 active / inactive 状态。
- **reason 类型同步**：后端 `WeatherRiskAdjustmentEvaluator` 使用字符串 reason 常量，前端 `WeatherRiskAdjustmentReason` 使用 union type，当前仅有 `VISIBILITY` / `STORM` 时可接受。Step 4 若新增 `CURRENT` 等 reason，必须同步更新后端常量、前端类型与状态提示测试。
- **等级与评分语义分离**：visibility scaling 只提前离散等级边界，`dcpaScore` 仍使用原始 `cautionDcpaNm`，避免坏天气下相同几何输入评分反向下降。若后续需要把低能见度显式计入连续评分，应以非负 penalty / boost 方式追加，而不是扩大分母。

---

## 9. Deferred

- `surface_current → 机动建议置信度修正`：已由 Step 4 完成（advisory `evidence_items` 流场建议）
- `LlmRiskContext.weather` 扩展与 advisory prompt 约束：已由 Step 4 完成
- `GetWeatherContextTool`、`EvaluateManeuverWithWeatherTool` 注册：已由 Step 4 完成
- 复杂天气修正解释、zone 明细展示与增强天气视觉：deferred，转 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md)

---

## 10. 需同步修改的文档

- [`WEATHER_PLAN.md`](./WEATHER_PLAN.md)：Step 3 段标注"已完成"（step 执行后）
- [`step2.md`](./step2.md)：§6 Deferred 第一条"引擎接入 weather 修正"改为"已由 Step 3 完成"（step 执行后）
- `application.properties`：追加 `risk.weather.*` 注释说明默认行为
