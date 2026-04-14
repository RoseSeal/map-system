# Step 6: Mock 清理与管线集成执行方案

> 文档状态：就绪
> 对应 ENGINE_ENHANCEMENT_PLAN.md Step 6
> 前置状态：Step 1–5C 全部完成

---

## 1. 命名与定位

本步骤命名为 **"Mock 清理与管线集成"**。

目标：
- 消除 assembler 包中残余的硬编码业务值，将 Step 1–5 的引擎输出端到端串联到前端协议；
- 统一提取 AIS 协议哨兵常量和 assembler 协议字面量（Step 2/3 明确延后到此步的技术债）；
- 补齐 LLM 上下文数据完整性；
- 同步协议文档和前端类型定义，消除值域遗漏。

---

## 2. 前置状态确认

以下条目**已在前序步骤完成**，Step 6 不再重复处理：

| 条目 | 完成于 | 现状 |
|---|---|---|
| `OwnShipAssembler` 消费 `ShipDomainResult`（fore/aft/port/stbd, shape_type） | Step 1 | 已完成 |
| `TargetAssembler` 输出 `risk_score`, `risk_confidence` | Step 4A | 已完成 |
| `TargetAssembler` 输出 `predicted_trajectory` | Step 4B | 已完成 |
| `TargetAssembler` 输出 `encounter_type` | Step 3 | 已完成 |
| `ShipDispatcher.refreshAfterCleanup()` 传递实际计算结果（非 null） | Step 5C | 已完成 |

以下条目属于 `ENGINE_ENHANCEMENT_PLAN.md` Step 6 的"跨步骤耦合收尾"项，代码**已在 qualityFlags 就位后提前实现**，Step 6 仅做确认性复核，不重新实现：

| 条目 | 原计划归属 | 代码现状 |
|---|---|---|
| `CvPredictionEngine.extractRotDegPerSec()` 过滤 `COG_JUMP`/`POSITION_JUMP` 帧 | Step 6（ENGINE_ENHANCEMENT_PLAN:771） | 已实现（`isValidForRot()` + `hasRotContaminatingFlag()`）|

> **归属说明**：`step4B.md:51` 原文写"待 Step 5B 引入 qualityFlags 后再补入 COG_JUMP 排除逻辑"，即该功能在 Step 5B 完成后即可落地。代码已在该条件满足后实现，不属于 Step 5C 成果，应归入 Step 6 收尾项（与总计划一致）。Step 6 复核内容：确认过滤逻辑覆盖 `COG_JUMP` 和 `POSITION_JUMP`，确认 null-safe，确认有单元测试覆盖。

---

## 3. 残余硬编码清单

### 3.1 Assembler 业务值

| 文件 | 位置 | 硬编码值 | 替换来源 |
|---|---|---|---|
| `OwnShipAssembler` | `assemble()` L25 | `platform_health.status = "NORMAL"` | `ownShip.getQualityFlags()` 推断 |
| `TargetAssembler` | 常量 `TRACKING_STATUS = "tracking"` L21 | `tracking_status` 固定为 `"tracking"` | `targetShip.getQualityFlags()` 推断 |
| `RiskVisualizationAssembler` | `buildOztSector()` L32-33 | OZT 扇区 ±10°（固定） | 基于 `EncounterType` 动态选择 |
| `RiskObjectMetaAssembler` | `buildGovernance()` L34 | `trust_factor = 0.99` | `RiskAssessmentResult` 均值置信度 |
| `RiskObjectMetaAssembler` | `buildGovernance()` L34 | `mode = "adaptive"` | `RiskObjectMetaProperties` 配置化 |
| `RiskObjectMetaAssembler` | `buildEnvironmentContext()` L39 | `safety_contour_val = 10.0` | `RiskObjectMetaProperties` 配置化 |

### 3.2 AIS 协议哨兵常量重复定义（Step 2/3 延后项）

| 文件 | 重复定义 |
|---|---|
| `ShipDomainEngine` L14 | `AIS_SOG_NOT_AVAILABLE_KN = 102.3` |
| `CvPredictionEngine` L23 | `AIS_SOG_NOT_AVAILABLE_KN = 102.3` |
| `PredictedCpaCalculator` L13 | `MAX_VALID_SOG_KNOTS = 102.3`（同值，命名不同） |
| `EncounterClassifier` L14-15 | `COG_NOT_AVAILABLE = 360.0` + `isInvalidCog()` |
| `CvPredictionEngine` L36,156 | 内联 `cog >= 360.0` / `cog < 360.0`（与 EncounterClassifier 不共享） |

目标：提取至共享类 `AisProtocolConstants`，各引用方改为使用共享定义。

### 3.3 Assembler 协议字面量（Step 3 延后项）

| 文件 | 字面量 | 语义 |
|---|---|---|
| `TargetAssembler.buildPredictedTrajectory()` | `"cv"` | `prediction_type` 值 |
| `OwnShipAssembler` | `"linear"` | `prediction_type` 值 |
| `TargetAssembler.resolveTrackingStatus()` （新增） | `"tracking"` / `"stale"` | `tracking_status` 值 |

目标：提取至 `AssemblerProtocolConstants`，assembler 类引用常量名而非裸字符串。

---

## 4. 子任务拆解

### 4A：`RiskObjectMetaAssembler` 配置化

**新增 `RiskObjectMetaProperties`**

```java
@Data @Component
@ConfigurationProperties(prefix = "engine.risk-meta")
public class RiskObjectMetaProperties {
    private String governanceMode   = "adaptive";
    private double safetyContourVal = 10.0;
}
```

**修改 `buildGovernance()`**

```java
// 签名变更：接收已计算好的 trustFactor
public Map<String, Object> buildGovernance(double trustFactor) {
    return Map.of("mode", props.getGovernanceMode(), "trust_factor", trustFactor);
}
```

**修改 `buildEnvironmentContext()`**

```java
public Map<String, Object> buildEnvironmentContext() {
    return Map.of(
        "safety_contour_val", props.getSafetyContourVal(),
        "active_alerts", List.of()
    );
}
```

**`RiskObjectAssembler.assembleRiskObject()` 计算 `trustFactor`**

```java
double trustFactor = computeAvgConfidence(riskResult);  // 全部目标 riskConfidence 均值；无有效 assessment 时回退 0.0
...
governance(riskObjectMetaAssembler.buildGovernance(trustFactor))
```

> **语义说明**：`trust_factor` 是整体态势信任度，此处以各目标 `riskConfidence` 均值作为代理，
> 语义可接受（目标数据质量越低，整体信任度越低）。若无目标或无有效 assessment，则回退 0.0，
> 表示当前缺少可支持的全局置信依据，而不是“最高可信”。

---

### 4B：`OwnShipAssembler` — `platform_health.status` 动态化

```java
// 签名不变，直接从 ownShip.getQualityFlags() 读取
Set<QualityFlag> flags = ownShip.getQualityFlags();
String healthStatus = (flags == null || flags.isEmpty()) ? "NORMAL" : "DEGRADED";
platformHealth.put("status", healthStatus);
platformHealth.put("description", flags == null ? "" : flags.toString());
```

> **推断规则**：本船质量标志集非空 → `"DEGRADED"`，否则 `"NORMAL"`。
> 全部 7 种 `QualityFlag` 均纳入判定，包括 `MISSING_HEADING`（虽有 COG fallback，但传感器状态不完整，应反映为 DEGRADED）。

---

### 4C：`TargetAssembler` — `tracking_status` 动态化

```java
private String resolveTrackingStatus(Set<QualityFlag> flags) {
    if (flags != null && flags.contains(QualityFlag.MISSING_TIMESTAMP)) {
        return AssemblerProtocolConstants.TRACKING_STATUS_STALE;
    }
    return AssemblerProtocolConstants.TRACKING_STATUS_TRACKING;
}
```

`assembleTarget()` 中：

```java
target.put("tracking_status", resolveTrackingStatus(targetShip.getQualityFlags()));
```

> **与 ENGINE_ENHANCEMENT_PLAN 偏差**：原计划写 `STALE_TIMESTAMP → "stale"`，
> 但 `QualityFlag` 枚举中仅有 `MISSING_TIMESTAMP`，无 `STALE_TIMESTAMP`。
> 映射 `MISSING_TIMESTAMP → "stale"`，语义一致（时间戳缺失无法判断时效性 → stale）。不新增枚举值。

---

### 4D：`RiskVisualizationAssembler` — OZT 扇区动态化

```java
public Map<String, Object> buildOztSector(
        ShipStatus targetShip, String riskLevel, EncounterType encounterType) {
    if (!RiskConstants.WARNING.equals(riskLevel) && !RiskConstants.ALARM.equals(riskLevel)) {
        return null;
    }
    double halfAngle = resolveOztHalfAngle(encounterType);
    Map<String, Object> oztSector = new LinkedHashMap<>();
    oztSector.put("start_angle_deg", targetShip.getCog() - halfAngle);
    oztSector.put("end_angle_deg",   targetShip.getCog() + halfAngle);
    oztSector.put("is_active", true);
    return oztSector;
}

private double resolveOztHalfAngle(EncounterType type) {
    if (type == null) return 10.0;
    return switch (type) {
        case HEAD_ON    -> 20.0;  // 对遇：正面碰撞风险高，告警扇区宽
        case OVERTAKING -> 8.0;   // 追越：目标在船尾，威胁集中，扇区窄
        case CROSSING   -> 12.0;  // 交叉：一般情况
        default         -> 10.0;  // UNDEFINED / fallback，与现网对齐
    };
}
```

`TargetAssembler.assembleTarget()` 传递 `encounterType`：

```java
EncounterType encType = encounterResult != null ? encounterResult.getEncounterType() : null;
Map<String, Object> oztSector = riskVisualizationAssembler.buildOztSector(targetShip, riskLevel, encType);
```

> 角度为初始默认值，后续可配置化。本步骤不引入新 Properties 类，避免为三个数值增加配置层。

---

### 4E：`LlmRiskTargetContext` 字段补充

`LlmRiskTargetContext` 新增：

```java
private Double riskScore;         // 综合风险分（Step 4A）
private Double domainPenetration; // 域侵入比（Step 4A；未发生侵入时为 null）
```

`LlmRiskContextAssembler.buildTargetContexts()` 补充赋值：

```java
.riskScore(assessment != null ? assessment.getRiskScore() : null)
.domainPenetration(assessment != null ? assessment.getDomainPenetration() : null)
```

---

### 4F：`AisProtocolConstants` 共享常量提取

新建 `util/AisProtocolConstants.java`：

```java
public final class AisProtocolConstants {
    private AisProtocolConstants() {}

    /** AIS SOG 不可用哨兵值（ITU-R M.1371），单位：海里/小时。*/
    public static final double SOG_NOT_AVAILABLE_KN = 102.3;

    /** AIS COG 不可用哨兵值（ITU-R M.1371），单位：度。*/
    public static final double COG_NOT_AVAILABLE_DEG = 360.0;

    /** COG 有效性谓词：[0, 360) 且非 NaN。*/
    public static boolean isValidCog(double cog) {
        return !Double.isNaN(cog) && cog >= 0.0 && cog < COG_NOT_AVAILABLE_DEG;
    }

    /** SOG 有效性谓词：非 NaN、非负、未达哨兵值。*/
    public static boolean isValidSog(double sog) {
        return !Double.isNaN(sog) && sog >= 0.0 && sog < SOG_NOT_AVAILABLE_KN;
    }
}
```

各引用方改动：

| 文件 | 改动 |
|---|---|
| `ShipDomainEngine` | 删除本地 `AIS_SOG_NOT_AVAILABLE_KN`，改用 `AisProtocolConstants.SOG_NOT_AVAILABLE_KN` |
| `CvPredictionEngine` | 删除本地 `AIS_SOG_NOT_AVAILABLE_KN`；内联 `cog >= 360.0` 替换为 `!AisProtocolConstants.isValidCog(cog)` |
| `PredictedCpaCalculator` | 删除本地 `MAX_VALID_SOG_KNOTS`，改用共享常量 |
| `EncounterClassifier` | 删除本地 `COG_NOT_AVAILABLE`；`isInvalidCog()` 改调 `!AisProtocolConstants.isValidCog(cog)` |

> `isValidCog()` 和 `isValidSog()` 作为谓词方法提供，调用方可直接使用，消除各处内联逻辑。

---

### 4G：`AssemblerProtocolConstants` 协议字面量提取

新建 `pipeline/assembler/AssemblerProtocolConstants.java`：

```java
public final class AssemblerProtocolConstants {
    private AssemblerProtocolConstants() {}

    // prediction_type 值
    public static final String PREDICTION_TYPE_CV     = "cv";
    public static final String PREDICTION_TYPE_LINEAR = "linear";

    // tracking_status 值
    public static final String TRACKING_STATUS_TRACKING = "tracking";
    public static final String TRACKING_STATUS_STALE    = "stale";
}
```

各引用方改动：

| 文件 | 替换位置 |
|---|---|
| `TargetAssembler.buildPredictedTrajectory()` | `"cv"` → `PREDICTION_TYPE_CV` |
| `OwnShipAssembler` | `"linear"` → `PREDICTION_TYPE_LINEAR` |
| `TargetAssembler.resolveTrackingStatus()` | `"tracking"` / `"stale"` → 对应常量 |

---

### 4H：协议文档与前端类型同步

**问题**：`tracking_status` 新增 `"stale"` 值，`prediction_type` 已在 Step 4B 引入 `"cv"` 但未同步到类型定义，存在两处值域遗漏。

**`schema.d.ts` 修改**

```typescript
export type TrackingStatus = 'tracking' | 'stale';
export type PredictionType = 'linear' | 'cv';
```

**`EVENT_SCHEMA.md` 修改**

更新枚举值域表（当前在"3. 协议枚举"或"常量"章节）：

| 类型 | 当前值域 | 更新后 |
|---|---|---|
| `TrackingStatus` | `tracking` | `tracking` / `stale` |
| `PredictionType` | `linear` | `linear` / `cv` |

> `PredictionType = 'linear' | 'cv'` 对应 Step 4B 已实现的功能（`TargetAssembler.buildPredictedTrajectory()` 输出 `"cv"`），属于 Step 4B 遗漏的协议同步，在 Step 6 统一补齐。
>
> 这两项均为**值域扩充**，不是新增字段，前端消费方需确认对未知 `tracking_status` 值有容错处理（建议前端 `RiskTarget` 组件在渲染 `tracking_status` 时加默认分支）。

---

### 4I：`extractRotDegPerSec()` 收尾确认

属于 `ENGINE_ENHANCEMENT_PLAN.md:771` 的跨步骤耦合收尾项。代码已在 `CvPredictionEngine` 中实现（`isValidForRot()` + `hasRotContaminatingFlag()`）。

Step 6 确认项：
- [ ] 确认 `hasRotContaminatingFlag()` 覆盖 `COG_JUMP` 和 `POSITION_JUMP`（已确认）
- [ ] 确认 `null qualityFlags` safe（已确认，`flags == null` 返回 false）
- [ ] 确认对应单元测试覆盖了携带 `COG_JUMP` 的历史点被过滤的场景

---

## 5. 改动范围汇总

| 文件 | 改动类型 | 描述 |
|---|---|---|
| `RiskObjectMetaProperties`（新建） | 新建 | `engine.risk-meta` 配置类 |
| `AisProtocolConstants`（新建） | 新建 | AIS 哨兵常量与有效性谓词统一定义 |
| `AssemblerProtocolConstants`（新建） | 新建 | Assembler 层协议字面量常量 |
| `RiskObjectMetaAssembler` | 修改 | `buildGovernance(trustFactor)` + 配置化 |
| `RiskObjectAssembler` | 修改 | 计算 `avgConfidence`，传入 `buildGovernance()` |
| `OwnShipAssembler` | 修改 | `platform_health.status` 从 qualityFlags 推断；`"linear"` 改引常量 |
| `TargetAssembler` | 修改 | `tracking_status` 动态化；OZT 传 encounterType；字面量改引常量 |
| `RiskVisualizationAssembler` | 修改 | `buildOztSector()` 接收 `EncounterType` |
| `ShipDomainEngine` | 修改 | 引用 `AisProtocolConstants.SOG_NOT_AVAILABLE_KN` |
| `CvPredictionEngine` | 修改 | 引用 `AisProtocolConstants` 常量与谓词 |
| `PredictedCpaCalculator` | 修改 | 引用 `AisProtocolConstants.SOG_NOT_AVAILABLE_KN` |
| `EncounterClassifier` | 修改 | 引用 `AisProtocolConstants.isValidCog()` |
| `LlmRiskTargetContext` | 修改 | 新增 `riskScore`, `domainPenetration` 字段 |
| `LlmRiskContextAssembler` | 修改 | 填入 `riskScore`, `domainPenetration` |
| `schema.d.ts` | 修改 | `TrackingStatus` 增加 `'stale'`；`PredictionType` 增加 `'cv'` |
| `EVENT_SCHEMA.md` | 修改 | 枚举值域表同步 `TrackingStatus` / `PredictionType` |

**不涉及改动的文件**：
- `ShipDispatcher`（refreshAfterCleanup 路径在 Step 5C 已修复）
- `CvPredictionEngine` 主逻辑（4F 中仅替换常量引用，不改算法）

---

## 6. 协议影响

**值从静态变为动态（字段已存在）**：

| 字段 | 现状 | Step 6 后 |
|---|---|---|
| `own_ship.platform_health.status` | 固定 `"NORMAL"` | 基于 ownShip qualityFlags 推断 |
| `target.tracking_status` | 固定 `"tracking"` | 有 MISSING_TIMESTAMP → `"stale"` |
| `target.risk_assessment.ozt_sector.start/end_angle_deg` | ±10° 固定 | 基于 EncounterType 动态 |
| `risk_object.governance.trust_factor` | 固定 `0.99` | avgRiskConfidence；无有效 assessment 时为 `0.0` |
| `risk_object.governance.mode` | 固定 `"adaptive"` | 配置化（默认值不变） |
| `risk_object.environment_context.safety_contour_val` | 固定 `10.0` | 配置化（默认值不变） |

**值域扩充（需同步类型定义）**：

| 类型 | 已存在值 | 新增值 | 同步文件 |
|---|---|---|---|
| `TrackingStatus` | `"tracking"` | `"stale"` | `schema.d.ts`、`EVENT_SCHEMA.md` |
| `PredictionType` | `"linear"` | `"cv"`（Step 4B 遗漏） | `schema.d.ts`、`EVENT_SCHEMA.md` |

---

## 7. 验证方式

**单元测试**

- `OwnShipAssembler`：qualityFlags 非空 → `"DEGRADED"`；空 → `"NORMAL"`
- `TargetAssembler`：包含 `MISSING_TIMESTAMP` → `"stale"`；否则 → `"tracking"`
- `RiskVisualizationAssembler`：HEAD_ON → ±20°；OVERTAKING → ±8°；CROSSING → ±12°；null → ±10°
- `RiskObjectMetaAssembler`：`trust_factor` 等于传入值；`mode`/`safety_contour_val` 等于配置值
- `AisProtocolConstants`：`isValidCog()` / `isValidSog()` 边界值（0.0、359.9、360.0、NaN、负值、102.3）
- `CvPredictionEngine.extractRotDegPerSec()`：携带 `COG_JUMP` 的历史点不参与 ROT 回归（补充或确认已有测试）

**集成验证（模拟器）**

- SSE 流中 `platform_health.status` 随本船质量标志变化
- 高风险目标的 `ozt_sector` 角度反映会遇类型
- `governance.trust_factor` 随目标数据质量浮动

**协议与类型一致性检查**

- `schema.d.ts` 中 `TrackingStatus` 和 `PredictionType` 与 `EVENT_SCHEMA.md` 枚举表一致
- 检查所有 assembler 类：不再包含业务硬编码字面量（`grep -r '"tracking"\|"cv"\|"linear"\|102\.3\|360\.0'`）

---

## 8. 范围外事项

- `OwnShipAssembler.future_trajectory.prediction_type`：本船 CV 预测不在本轮范围，`"linear"` 由常量 `PREDICTION_TYPE_LINEAR` 表示但值不变
- OZT 角度配置化：当前以常量内联实现；若业务需要调优再引入 Properties
- `RiskAssessmentCompletedEvent` 补充 `encounterResults`：`LlmRiskContextAssembler` 仍实时重算 encounterType，存在重复计算；不在本步骤消除，属 Step 7 范畴

**Deferred findings（本轮 review 记录，不在本次修复）**

- `EncounterClassifier` 与 `CvPredictionEngine` 各自保留私有 `normalizeAngle360`/`normalizeAngleDeg` 归一化逻辑：当前仅两处局部使用、无共享收益，暂不提取公共工具；待出现第三处调用点或需要统一角度语义时再处理。
- `RiskVisualizationAssembler` 仍使用 `RiskConstants.WARNING/ALARM` 而非 `RiskLevel`：这是已知迁移尾项。`RiskConstants` 风险等级常量已标记 `@Deprecated`，后续适合与 assembler/test 一起做一次小范围迁移清理，不混入本步 engine 增强 diff。
- `CvPredictionEngine` 中 O(n) 均值/回归与 `qualityFlags.contains(...)` 查询复杂度可接受：当前目标数和历史窗口都较小（<100 量级），未构成热点，不做微优化。
