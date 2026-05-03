# Weather Step 4：LLM static context + agent tool + advisory 消费

> 文档状态：active
> 最后更新：2026-04-30
> 执行状态：completed
> 所属 track：[`WEATHER_PLAN.md`](./WEATHER_PLAN.md)
> 目标：让 advisory 在低能见度等场景下给出受气象约束的机动建议；LLM 在每次推理时能够感知当前生效天气并在 evidence_items 中显式引用气象事实。

---

## 1. Summary

Step 4 在三个层次完成天气信号向 LLM 的接入：

1. **Static context 注入**：`LlmRiskContext` 新增 `weather` 字段，由 `LlmRiskContextAssembler` 填充生效天气；`RiskContextFormatter` 在上下文字符串中追加一段气象摘要，使 LLM 在每轮推理时无需额外工具调用即可感知天气。
2. **Advisory prompt 约束**：advisory 系统提示词追加"weather_code != CLEAR 时 evidence_items 至少包含一条气象事实项"指令；`AdvisoryPromptBuilder` 在用户消息中动态注入当前气象段。
3. **Agent tool 注册**：注册两个新工具 `GetWeatherContextTool`（返回 snapshot 中的生效天气）与 `EvaluateManeuverWithWeatherTool`（评估给定机动在当前气象下的可行性并给出建议），均从 `snapshot.riskContext().getWeather()` 读取。

`surface_current → 机动建议置信度修正` 通过将流场数据纳入气象摘要并在 advisory prompt 中增加对应建议约束来实现，不修改 CPA 几何计算。

---

## 2. 当前状态（Step 3 完成后）

- `LlmRiskContext` 仅有 `ownShip` / `targets` 字段，无 `weather` 字段
- `LlmRiskContextAssembler.assemble()` 签名不含任何天气入参
- `RiskContextFormatter` 上下文字符串不包含气象段落
- `AdvisoryPromptBuilder` 仅将最高风险等级和非 SAFE 目标数写入用户消息
- `AgentToolRegistry` 现有六个工具，均无天气相关工具
- `WeatherContextHolder`、`RegionalWeatherResolver` 已存在（由 Step 1/2 落地），未被 LLM 路径消费

---

## 3. In Scope

### 3.1 新增 `LlmRiskWeatherContext` DTO

新建 `llm/dto/LlmRiskWeatherContext.java`，承载 LLM 消费的扁平化生效天气快照：

```java
@Data
@Builder
public class LlmRiskWeatherContext {
    private String weatherCode;              // CLEAR / FOG / RAIN / SNOW / STORM
    private Double visibilityNm;
    private Double windSpeedKn;
    private Integer windDirectionFromDeg;    // 风的来向，航海惯例
    private Double surfaceCurrentSpeedKn;
    private Integer surfaceCurrentSetDeg;    // 流的去向，航海惯例
    private Integer seaState;               // 0..9
    private String sourceZoneId;            // 命中 zone 的 zone_id；无 zone 时为 null
    private List<String> activeAlerts;      // 当前命中的 EnvAlertCode，来自 effective weather
}
```

此 DTO 是 `LlmRiskContext` 的内嵌子字段，不向 SSE 层直接暴露。

### 3.2 `LlmRiskContext` 扩展

在 `llm/dto/LlmRiskContext.java` 追加可选字段：

```java
@Data
@Builder
public class LlmRiskContext {
    private LlmRiskOwnShipContext ownShip;
    private List<LlmRiskTargetContext> targets;
    private LlmRiskWeatherContext weather;  // 新增；null 表示无气象信号或信号已过期
}
```

### 3.3 `LlmRiskContextAssembler` 扩展

#### 3.3.1 构造函数新增依赖

追加注入 `WeatherContextHolder`、`RegionalWeatherResolver`、`WeatherAlertProperties`（复用 Step 1 已有配置类，取 `staleThresholdSeconds`）。现有两参数构造函数保留用于测试。

#### 3.3.2 `assemble()` 调用点不变

`LlmRiskEventListener.onRiskAssessmentCompleted()` 中的调用签名 `assemble(ownShip, allShips, cpaResults, riskResult)` **不变**。天气解析在 assembler 内部完成，对外接口不膨胀。

#### 3.3.3 内部天气解析逻辑

```java
private LlmRiskWeatherContext resolveWeatherContext(ShipStatus ownShip) {
    // 1. 读取 fresh snapshot（同 RiskAssessmentEngine 的 stale threshold 配置）
    // 2. zones 非空时按 ownShip 位置 resolve；否则使用 global snapshot
    // 3. 映射为 LlmRiskWeatherContext
    // 4. holder 不可用 / stale 时返回 null
}
```

`resolveWeatherContext` 的映射规则与 Step 3 `RiskAssessmentEngine.resolveEffectiveWeather()` 保持同等语义，但在 assembler 内独立实现，不跨包调用引擎内部方法。`LlmRiskContextAssembler` 所在的 `llm/context` 包不反向依赖 `risk/engine` 包。

### 3.4 `LlmRiskContextDeepCopier` 扩展

`LlmRiskContextDeepCopier.copy()` 当前仅深拷贝 `ownShip` 与 `targets`。在 Step 4 后需要同时拷贝 `weather` 字段；`LlmRiskWeatherContext` 仅含 primitives 与 `List<String>`，浅拷贝安全（`activeAlerts` 列表用 `List.copyOf()`）。

### 3.5 `RiskContextFormatter` 气象段落

在 `formatSummary()` 与 `formatConsolidated()` 中，紧跟本船信息段落之后插入气象摘要行：

```
当前气象：有雾，能见度 0.8 nm；风 SW 12 节；水流 090° 1.4 节；海况 3 级；[LOW_VISIBILITY]
```

追加条件：`context.getWeather() != null && !"CLEAR".equals(context.getWeather().getWeatherCode())`；若 `weather_code == CLEAR` 且 `activeAlerts` 为空，则不追加气象段，避免稀释 prompt 密度。

私有方法 `appendWeather(StringBuilder, LlmRiskWeatherContext)` 负责格式化，规则：
- 仅输出非 null 的子字段
- `surfaceCurrentSpeedKn != null && surfaceCurrentSpeedKn >= 2.5` 时在摘要末尾追加 "（流场偏移较强）"
- 格式紧凑，单行，不超过 120 字符

### 3.6 Advisory prompt 扩展

#### 3.6.1 系统提示词（`PromptScene.ADVISORY`）

在 advisory 系统提示词的 evidence_items 约束段追加如下指令：

```
当当前气象段包含非 CLEAR 天气时，evidence_items 必须至少包含一条气象事实项，
例如"能见度 0.8 nm，低于 2.0 nm 安全阈值"或"水流 1.4 节，建议机动量加大裕量"。
```

指令位置：紧跟现有 evidence_items 说明，不改变其他 advisory 约束。

#### 3.6.2 用户消息（`AdvisoryPromptBuilder.buildUserPrompt()`）

在当前"最高风险等级 / 非 SAFE 目标数"格式化字符串之后追加气象小节。当 `snapshot.riskContext().getWeather()` 非 null 且 `weatherCode != CLEAR` 时，追加如下片段：

```
当前气象：[weatherCode]，能见度 [visibilityNm] nm，风 [windSpeedKn] 节，水流 [surfaceCurrentSpeedKn] 节 [surfaceCurrentSetDeg]°，海况 [seaState] 级。
```

值为 null 的子字段跳过输出。天气 CLEAR 且无告警时不追加该段。

### 3.7 新工具：`GetWeatherContextTool`

**包**：`llm/agent/tool/builtin/GetWeatherContextTool.java`  
**工具名**：`get_weather_context`（在 `AgentToolNames` 中新增常量 `GET_WEATHER_CONTEXT`）

功能：返回 snapshot 中本船位置生效的天气摘要，供 LLM 在需要精确数值时查询。

参数 schema：无必需参数（空 object schema）。

返回字段（从 `snapshot.riskContext().getWeather()` 映射）：

```json
{
  "status": "OK",
  "snapshot_version": 42,
  "weather_code": "FOG",
  "visibility_nm": 0.8,
  "wind_speed_kn": 12.0,
  "wind_direction_from_deg": 225,
  "surface_current_speed_kn": 1.4,
  "surface_current_set_deg": 90,
  "sea_state": 3,
  "source_zone_id": "fog-bank-east",
  "active_alerts": ["LOW_VISIBILITY"]
}
```

当 `snapshot.riskContext().getWeather()` 为 null 时，返回：

```json
{
  "status": "NO_WEATHER_DATA",
  "message": "Weather context unavailable: no fresh signal or stale snapshot"
}
```

### 3.8 新工具：`EvaluateManeuverWithWeatherTool`

**包**：`llm/agent/tool/builtin/EvaluateManeuverWithWeatherTool.java`  
**工具名**：`evaluate_maneuver_with_weather`（在 `AgentToolNames` 中新增常量 `EVALUATE_MANEUVER_WITH_WEATHER`）

功能：在给定拟议机动参数的情况下，评估当前气象对该机动可行性的影响，并给出气象约束建议。与 `EvaluateManeuverTool` 不同：本工具不计算 CPA 几何，仅评估气象约束；两者可联合使用。

参数 schema：

```json
{
  "type": "object",
  "properties": {
    "target_id": {
      "type": "string",
      "description": "Optional. Target ship ID the maneuver is intended for (for contextual description only; not used in geometry calculation)."
    },
    "course_change_deg": {
      "type": "number",
      "description": "Optional. Proposed course change in degrees; positive = starboard, negative = port."
    },
    "speed_change_kn": {
      "type": "number",
      "description": "Optional. Proposed speed change in knots; negative = decelerate."
    },
    "lookahead_min": {
      "type": "number",
      "description": "Optional. Reserved for future use; must be 0 or omitted in v1.0."
    }
  }
}
```

三个核心参数均可选；全部省略时工具仅返回当前天气状态与标志，不给出机动建议。

计算逻辑（伪代码）：

```
weather = snapshot.riskContext().getWeather()
if weather == null: return NO_WEATHER_DATA response

ownShip = snapshot.frozenOwnShip()
speedAfter = (ownShip.sog + speed_change_kn) if speed_change_kn provided else ownShip.sog
courseAfter = ((ownShip.cog + course_change_deg) % 360 + 360) % 360 if course_change_deg provided else ownShip.cog

weatherFlags:
  lowVisibility = weather.visibilityNm != null && weather.visibilityNm < weatherRiskProperties.visibility.lowVisNm
  strongCurrent = weather.surfaceCurrentSpeedKn != null && weather.surfaceCurrentSpeedKn > weatherAlertProperties.strongCurrentSetKn
  stormConditions = "STORM".equals(weather.weatherCode) || (weather.seaState != null && weather.seaState >= weatherRiskProperties.storm.seaStateThreshold)

recommendations (append if condition met):
  - lowVisibility && speedAfter > 0: "能见度 [x] nm，低于 [threshold] nm；建议将航速减至安全航速"
  - lowVisibility && speedAfter == 0: "航速已减为零，满足低能见度安全航速要求"
  - strongCurrent: "水流 [x] 节（[set]°）；建议机动量适当加大裕量以补偿流场偏移"
  - stormConditions: "当前海况 [seaState] 级；极端天气下建议减小机动幅度或等待改善"
```

阈值来源有两个不同的配置类：`WeatherRiskProperties`（`risk.weather.*`）提供 `visibility.lowVisNm` 与 `storm.seaStateThreshold`；`WeatherAlertProperties`（`engine.risk-meta.weather-alert.*`）提供 `strongCurrentSetKn`。`EvaluateManeuverWithWeatherTool` 同时注入两者，确保所有阈值均来自配置，不得硬编码。

返回结构：

```json
{
  "status": "OK",
  "snapshot_version": 42,
  "effective_weather": {
    "weather_code": "FOG",
    "visibility_nm": 0.8,
    "surface_current_speed_kn": 1.4,
    "surface_current_set_deg": 90,
    "sea_state": 3
  },
  "proposed_state": {
    "course_after_deg": 225.0,
    "speed_after_kn": 8.0
  },
  "weather_flags": {
    "low_visibility": true,
    "strong_current": false,
    "storm_conditions": false
  },
  "recommendations": [
    "能见度 0.8 nm，低于 2.0 nm 阈值；建议将航速减至安全航速"
  ]
}
```

`proposed_state` 仅在对应参数被传入时填充。`recommendations` 为空列表表示当前天气对拟议机动无重大约束。

### 3.9 `AgentToolNames` 扩展

在 `AgentToolNames` 追加两个常量：

```java
public static final String GET_WEATHER_CONTEXT = "get_weather_context";
public static final String EVALUATE_MANEUVER_WITH_WEATHER = "evaluate_maneuver_with_weather";
```

### 3.10 Advisory 可用工具列表更新

`user-advisory-context.txt` 第 6 行当前为：

```
可用工具：get_risk_snapshot、get_top_risk_targets、get_target_detail、get_own_ship_state。
```

Step 4 追加两个新工具后，更新为：

```
可用工具：get_risk_snapshot、get_top_risk_targets、get_target_detail、get_own_ship_state、get_weather_context、evaluate_maneuver_with_weather。
```

`system-advisory.txt` 的强制调用顺序（Rule 2）不变，新工具不加入强制调用链；LLM 可在需要精确天气数值时自主调用 `get_weather_context`，或在提议机动方案需要气象可行性评估时自主调用 `evaluate_maneuver_with_weather`。

---

## 4. Out of Scope

- **not doing**：修改 `AgentSnapshot` record 结构以新增 `weather` 字段；weather 已通过 `riskContext.weather` 进入 snapshot，不需要顶层字段
- **not doing**：advisory card 前端 UI 专项改造；Step 4 的前端交付仅为：若 advisory `evidence_items` 含气象事实项，现有 evidence_items 渲染逻辑即可展示，无需额外 UI 开发
- **not doing**：`EvaluateManeuverWithWeatherTool` 内执行 CPA 几何计算；该计算由 `EvaluateManeuverTool` 承担，两工具分工明确
- **not doing**：`LlmRiskContext.weather` 通过 SSE `ENVIRONMENT_UPDATE` 下发；天气已在 Step 1/2/3 通过 `environment_context.weather` 下发给前端，LLM 路径不重复下发
- **deferred**：`EvaluateManeuverWithWeatherTool` 的 `lookahead_min` 参数语义实现，转 weather track 后续 milestone

---

## 5. 文件与包影响

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `llm/dto/LlmRiskWeatherContext.java` | 新建 | LLM 消费的扁平化生效天气 DTO |
| `llm/dto/LlmRiskContext.java` | 修改 | 追加 `weather: LlmRiskWeatherContext` 可选字段 |
| `llm/context/LlmRiskContextAssembler.java` | 修改 | 注入 WeatherContextHolder / RegionalWeatherResolver / WeatherAlertProperties；内部解析 effective weather 并写入 `LlmRiskContext.weather` |
| `llm/agent/LlmRiskContextDeepCopier.java` | 修改 | deep copy 时拷贝 `weather` 字段 |
| `llm/context/RiskContextFormatter.java` | 修改 | 在 `formatSummary` / `formatConsolidated` 中追加气象摘要段 |
| `llm/agent/advisory/AdvisoryPromptBuilder.java` | 修改 | `buildUserPrompt()` 在非 CLEAR 天气时追加气象段 |
| advisory 系统提示词模板（`PromptScene.ADVISORY`） | 修改 | 追加 evidence_items 气象事实项约束指令 |
| `llm/agent/tool/AgentToolNames.java` | 修改 | 追加 `GET_WEATHER_CONTEXT` / `EVALUATE_MANEUVER_WITH_WEATHER` 常量 |
| `llm/agent/tool/builtin/GetWeatherContextTool.java` | 新建 | 实现 `AgentTool`，返回 snapshot 中的生效天气 |
| `llm/agent/tool/builtin/EvaluateManeuverWithWeatherTool.java` | 新建 | 实现 `AgentTool`，评估机动气象可行性；注入 `WeatherRiskProperties` + `WeatherAlertProperties` |
| `resources/prompts/user-advisory-context.txt` | 修改 | 可用工具列表追加 `get_weather_context`、`evaluate_maneuver_with_weather` |

不需要修改的文件：
- `LlmRiskEventListener`：`assemble()` 调用签名不变，listener 不感知天气
- `AgentSnapshotFactory`：`frozenCtx` 已拷贝 `LlmRiskContext`，`weather` 随 `LlmRiskContext` 一起进入 snapshot，无需改动
- `AgentToolRegistry`：自动发现 `@Component` 实现，新工具自动注册，无需手动修改
- Step 3 新增的 `WeatherRiskAdjustmentEvaluator` / `WeatherRiskProperties`：只读引用，不修改

---

## 6. 约束与不变量

- `LlmRiskContext.weather == null` 时，formatter 不追加气象段，两个新工具返回 `NO_WEATHER_DATA` 响应；LLM 行为退化为无气象接入，不影响现有推理路径
- `formatConsolidated` 现有行为（target 列表、explanation cache 引用）不受气象段影响；气象段始终追加在 `appendOwnShip()` 之后、第一个目标船之前
- `EvaluateManeuverWithWeatherTool` 不访问 `snapshot.frozenTargetShips()`，不执行 CPA 计算，不依赖 `CpaTcpaBatchCalculator`
- `GetWeatherContextTool` 不访问 `WeatherContextHolder`；weather 数据来源唯一为 `snapshot.riskContext().getWeather()`，保持 snapshot 视图一致性
- `EvaluateManeuverWithWeatherTool` 的阈值不得硬编码：`lowVisNm` 与 `seaStateThreshold` 来自 `WeatherRiskProperties`（`risk.weather.*`），`strongCurrentSetKn` 来自 `WeatherAlertProperties`（`engine.risk-meta.weather-alert.strong-current-set-kn`）
- advisory 系统提示词的气象指令是追加约束，不替换现有 COLREGS 推理指令；两者优先级：COLREGS 规则推理 > 气象约束
- `LlmRiskContextAssembler` 包（`llm/context`）不得反向引用 `risk/engine/risk` 包；weather holder 与 resolver 的引用来自 `shared/context` 或等价共享包

---

## 7. 协议与 SSE 影响

Step 4 不修改 SSE 事件协议。`ENVIRONMENT_UPDATE.environment_context.weather` 由 Step 3 已落地的链路下发，`ADVISORY` / `EXPLANATION` 类 SSE 事件的 payload 结构不变；advisory `evidence_items` 的内容由 LLM 生成，不影响前端 schema。

前端 `schema.d.ts` 无需修改。

---

## 8. Validation

### 8.1 `LlmRiskWeatherContext` 填充（assembler 层）

- 单测：注入 `visibility_nm=0.8` + `weatherCode=FOG` 的 snapshot，`assemble()` 返回 `LlmRiskContext.weather.weatherCode == "FOG"` 且 `visibilityNm == 0.8`
- 单测：holder stale / 无 snapshot 时，`assemble()` 返回 `LlmRiskContext.weather == null`
- 单测：zone 命中场景：本船坐标命中 `fog-bank-east`，`weather.sourceZoneId == "fog-bank-east"`
- 单测：zone 未命中场景：本船坐标不在任何 zone 内，回退为 global snapshot，`sourceZoneId == null`

### 8.2 RiskContextFormatter 气象段

- 单测：`weather_code=FOG` + `visibility_nm=0.8`，`formatSummary()` 输出包含"有雾，能见度 0.8 nm"
- 单测：`weather_code=CLEAR` + 空 alerts，`formatSummary()` 输出不包含气象段
- 单测：`weather == null`，输出不包含气象段
- 单测：`surfaceCurrentSpeedKn=3.0`，输出包含"（流场偏移较强）"

### 8.3 AdvisoryPromptBuilder 气象段

- 单测：`weather_code=STORM`，`buildUserPrompt()` 返回包含气象段
- 单测：`weather_code=CLEAR`，`buildUserPrompt()` 返回不包含气象段
- 单测：`weather == null`，`buildUserPrompt()` 返回不包含气象段

### 8.4 `GetWeatherContextTool`

- 单测：weather 非 null，返回 `status=OK` + 正确字段映射
- 单测：weather null，返回 `status=NO_WEATHER_DATA`
- 单测：`visibility_nm=0.8` + `activeAlerts=["LOW_VISIBILITY"]`，返回值中 `active_alerts` 包含 `"LOW_VISIBILITY"`

### 8.5 `EvaluateManeuverWithWeatherTool`（核心验收）

- 单测：`visibility_nm=0.8` + `speed_change_kn=-2` + 结果速度仍 > 0，`weather_flags.low_visibility=true`，`recommendations` 包含"安全航速"建议
- 单测：`surface_current_speed_kn=3.0` + `course_change_deg=-30`，`weather_flags.strong_current=true`，`recommendations` 包含"加大裕量"建议
- 单测：`weather_code=CLEAR` + 无告警，`recommendations` 为空列表
- 单测：`lookahead_min` 不为 0 时返回 `INVALID_ARGUMENT` 错误
- 单测：weather null，返回 `NO_WEATHER_DATA`
- 单测：所有参数省略时，仅返回 `effective_weather` 与 `weather_flags`，`proposed_state` 不出现

### 8.6 Advisory 端到端（集成验收）

- `--scene fog` 下触发 advisory，advisory 文本中 `evidence_items` 包含能见度相关事实项（如"能见度 0.8 nm，低于 2.0 nm 阈值"）；该事实项可来自 static context 注入，无需强制工具调用
- `get_weather_context` 与 `evaluate_maneuver_with_weather` 出现在 advisory 可用工具列表中；`AgentToolRegistry` 中已注册两工具，工具名称与 `AgentToolNames` 常量一致；可手动触发工具调用验证返回值正确
- `--scene clear` 下触发 advisory，advisory 文本中不强制包含气象事实项，COLREGS 推理不受影响
- `system-advisory.txt` Rule 2 强制调用顺序不变，仅 `user-advisory-context.txt` 可用工具列表发生变化；现有 advisory 回归测试保持通过

---

## 9. 开放风险

- **prompt 稀释**：气象段追加后，`formatConsolidated()` 的上下文字符串可能因目标数多而超出模型 token 限制。缓解：气象段控制在单行 120 字符以内；若触达 token 上限，优先裁剪气象段而非目标列表。
- **advisory 约束与现有指令冲突**：新增"气象 evidence_items 必含气象事实"指令可能与现有 COLREGS 推理指令产生优先级歧义。在系统提示词中明确优先级顺序（COLREGS > 气象约束）可缓解；若 LLM 输出出现退化，回滚气象约束指令为建议性措辞（"应包含"→"可包含"）。
- **阈值开关解耦**：`EvaluateManeuverWithWeatherTool` 读取 `WeatherRiskProperties`（visibility/storm 阈值）与 `WeatherAlertProperties`（`strongCurrentSetKn`）；`risk.weather.*.enabled` 若全关，工具仍按阈值判断标志位并给出建议，不受 enable 开关约束。这是预期行为，但需在工具 description 中说明"标志位独立于引擎修正开关"，避免用户误以为开关关闭时工具返回全绿。
- **deep copy 遗漏**：`LlmRiskContextDeepCopier` 若未同步更新，`AgentSnapshot` 内的 `weather` 字段为原始引用，在多线程 advisory 触发场景下可能被另一帧覆盖。`LlmRiskWeatherContext` 字段全为 primitives + `List<String>`，浅拷贝即安全（`List.copyOf()` 足够），不需要递归深拷贝。

---

## 10. Deferred

- `EvaluateManeuverWithWeatherTool` 的 `lookahead_min` 参数语义实现：weather track 后续 milestone，需配合轨迹预测能力；v1.0 范围内仅接受 0 或省略
- 增强天气视觉效果（降雨粒子、风场箭头、水流矢量）：转 [`../visual/VISUAL_UPGRADE_PLAN.md`](../visual/VISUAL_UPGRADE_PLAN.md)，不在 Step 4 内预写

---

## 11. 需同步修改的文档

- [`WEATHER_PLAN.md`](./WEATHER_PLAN.md)：Step 4 段标注"已完成"（step 执行后）
- [`step3.md`](./step3.md)：§9 Deferred 中以下三条标注为"已由 Step 4 完成"（step 执行后）：`surface_current → 机动建议置信度修正`、`LlmRiskContext.weather 扩展与 advisory prompt 约束`、`GetWeatherContextTool/EvaluateManeuverWithWeatherTool 注册`；"复杂天气修正解释、zone 明细展示与增强天气视觉"一条**保持 deferred**，归属 visual track
- [`../agent/AGENT_LOOP_PLAN.md`](../agent/AGENT_LOOP_PLAN.md)：§3.8 工具目录追加 `get_weather_context` 与 `evaluate_maneuver_with_weather` 的条目
