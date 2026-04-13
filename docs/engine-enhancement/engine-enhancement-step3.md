## Step 3 执行规划：会遇态势识别

### Summary

实现 `EncounterClassifier`，对每对目标船按相对几何态势分类（HEAD_ON / OVERTAKING / CROSSING / UNDEFINED），结果随 `ShipDerivedOutputs` 流向 `TargetAssembler`（写入 `risk_assessment.encounter_type`）和 `LlmRiskContextAssembler`（写入 `LlmRiskTargetContext.encounterType`）。

分类器为无状态组件，仅消费两船当前 `ShipStatus` 中的 COG 和位置，无历史依赖。`RiskAssessmentEngine` 在本步保持不变；会遇类型对风险评分的修正留待 Step 4。

**前置假设**：本文档中所有 `RiskObjectAssembler`、`TargetAssembler`、`ShipDerivedOutputs`、`ShipDispatcher` 签名均以 **Step 2 完成后**的状态为基准（`CvPredictionResult` 已变为 `Map<String, CvPredictionResult>`）。

---

### Key Changes

**1. 新增 `EncounterType` 枚举**

文件：`engine/encounter/EncounterType.java`（新建包）

```java
public enum EncounterType {
    HEAD_ON,    // 对遇：两船相向，互视对方为船头
    OVERTAKING, // 追越：目标在本船船尾弧内且航向近似同向
    CROSSING,   // 交叉：其余有效的相遇几何态势
    UNDEFINED   // 数据不足（COG 无效等），无法判定
}
```

**2. 新增 `EncounterClassificationResult`**

文件：`engine/encounter/EncounterClassificationResult.java`

```java
@Data @Builder
public class EncounterClassificationResult {
    private String targetId;
    private EncounterType encounterType;
    /** 目标相对本船船头的方位角，[0, 360)，顺时针，以本船 COG 为参考轴。 */
    private double relativeBearingDeg;
    /** 两船航向的最小角度差，[0, 180]。 */
    private double courseDifferenceDeg;
}
```

**3. 新增 `EncounterProperties`**

文件：`config/properties/EncounterProperties.java`（与 `ShipDomainProperties` 同包）

```java
@Data
@Component
@ConfigurationProperties(prefix = "engine.encounter")
public class EncounterProperties {
    /** 对遇：目标相对本船船头半角（°），默认 ±15°。 */
    private double headOnBowHalfAngleDeg       = 15.0;
    /** 对遇：两船航向差最小值（°），默认 170°（互为逆向 ±10°）。 */
    private double headOnCourseDiffMinDeg      = 170.0;
    /** 追越：目标相对本船船尾半角（°），默认 ±67.5°（COLREGS 22.5° abaft beam）。 */
    private double overtakingSternHalfAngleDeg = 67.5;
    /** 追越：两船航向差最大值（°），默认 112.5°（排除反向或大角度交叉）。 */
    private double overtakingCourseDiffMaxDeg  = 112.5;
}
```

在 `application.properties` 追加：

```properties
# Step 3: encounter classification
engine.encounter.head-on-bow-half-angle-deg=15.0
engine.encounter.head-on-course-diff-min-deg=170.0
engine.encounter.overtaking-stern-half-angle-deg=67.5
engine.encounter.overtaking-course-diff-max-deg=112.5
```

**4. `GeoUtils` 新增 `angleDifference`**

文件：`util/GeoUtils.java`

```java
/**
 * 返回两个航向角之间的最小角度差，范围 [0, 180]。
 * 示例：angleDifference(10, 350) == 20；angleDifference(0, 180) == 180。
 */
public static double angleDifference(double a, double b) {
    double diff = Math.abs(((a - b) % 360.0 + 360.0) % 360.0);
    return diff > 180.0 ? 360.0 - diff : diff;
}
```

**5. 实现 `EncounterClassifier`**

文件：`engine/encounter/EncounterClassifier.java`

分类优先级：HEAD_ON → OVERTAKING → CROSSING。

```java
@Component
public class EncounterClassifier {

    // AIS COG 无效哨兵：360.0 = "not available"（ITU-R M.1371）
    private static final double COG_NOT_AVAILABLE = 360.0;

    private final EncounterProperties props;

    public EncounterClassifier(EncounterProperties props) {
        this.props = props;
    }

    public EncounterClassificationResult classify(ShipStatus ownShip, ShipStatus targetShip) {
        double ownCog = ownShip.getCog();
        double tgtCog = targetShip.getCog();

        // COG 无效 → UNDEFINED（NaN / 负值 / >= 360.0）
        if (isInvalidCog(ownCog) || isInvalidCog(tgtCog)) {
            return EncounterClassificationResult.builder()
                    .targetId(targetShip.getId())
                    .encounterType(EncounterType.UNDEFINED)
                    .relativeBearingDeg(0.0)
                    .courseDifferenceDeg(0.0)
                    .build();
        }

        // 本船到目标船的真方位（[0, 360)）
        double bearingOwnToTarget = GeoUtils.trueBearing(
                ownShip.getLatitude(), ownShip.getLongitude(),
                targetShip.getLatitude(), targetShip.getLongitude());

        // 目标相对本船船头的方位角（[0, 360)，以本船 COG 为参考轴）
        double relBearing = normalizeAngle360(bearingOwnToTarget - ownCog);

        // 两船航向最小差（[0, 180]）
        double courseDiff = GeoUtils.angleDifference(ownCog, tgtCog);

        // ── HEAD-ON ──────────────────────────────────────────────────────────
        // 条件 1：两船航向差 >= headOnCourseDiffMinDeg（互为逆向）
        // 条件 2：目标在本船船头 ±headOnBowHalfAngleDeg 范围内
        // 条件 3：本船在目标船船头 ±headOnBowHalfAngleDeg 范围内（双向验证）
        if (courseDiff >= props.getHeadOnCourseDiffMinDeg()
                && isWithinBow(relBearing, props.getHeadOnBowHalfAngleDeg())) {
            double bearingTgtToOwn = normalizeAngle360(bearingOwnToTarget + 180.0);
            double relBearingFromTarget = normalizeAngle360(bearingTgtToOwn - tgtCog);
            if (isWithinBow(relBearingFromTarget, props.getHeadOnBowHalfAngleDeg())) {
                return build(targetShip.getId(), EncounterType.HEAD_ON, relBearing, courseDiff);
            }
        }

        // ── OVERTAKING ───────────────────────────────────────────────────────
        // 条件 1（几何）：目标在本船正后方 ±overtakingSternHalfAngleDeg
        // 条件 2（动力）：两船航向差 <= overtakingCourseDiffMaxDeg（大致同向）
        // 条件 2 排除"在船尾弧内但反向航行"的反向交叉场景
        if (isWithinStern(relBearing, props.getOvertakingSternHalfAngleDeg())
                && courseDiff <= props.getOvertakingCourseDiffMaxDeg()) {
            return build(targetShip.getId(), EncounterType.OVERTAKING, relBearing, courseDiff);
        }

        // ── CROSSING ─────────────────────────────────────────────────────────
        // 兜底：几何上不满足对遇或追越条件的其余有效态势
        return build(targetShip.getId(), EncounterType.CROSSING, relBearing, courseDiff);
    }

    // target 在本船船头 ±halfAngle 范围（relBearing ∈ [0, halfAngle] ∪ [360-halfAngle, 360)）
    private static boolean isWithinBow(double relBearing, double halfAngle) {
        return relBearing <= halfAngle || relBearing >= (360.0 - halfAngle);
    }

    // target 在本船船尾 ±halfAngle 范围（relBearing ∈ [180-halfAngle, 180+halfAngle]）
    private static boolean isWithinStern(double relBearing, double halfAngle) {
        return relBearing >= (180.0 - halfAngle) && relBearing <= (180.0 + halfAngle);
    }

    private static double normalizeAngle360(double angle) {
        return ((angle % 360.0) + 360.0) % 360.0;
    }

    private static boolean isInvalidCog(double cog) {
        return Double.isNaN(cog) || cog < 0.0 || cog >= COG_NOT_AVAILABLE;
    }

    private static EncounterClassificationResult build(
            String targetId, EncounterType type, double relBearing, double courseDiff) {
        return EncounterClassificationResult.builder()
                .targetId(targetId)
                .encounterType(type)
                .relativeBearingDeg(relBearing)
                .courseDifferenceDeg(courseDiff)
                .build();
    }
}
```

**6. `ShipDerivedOutputs` 增加会遇分类字段**

文件：`pipeline/ShipDerivedOutputs.java`

```java
record ShipDerivedOutputs(
        ShipDomainResult shipDomainResult,
        Map<String, CvPredictionResult> cvPredictionResults,
        Map<String, CpaTcpaResult> cpaResults,
        Map<String, EncounterClassificationResult> encounterResults   // 新增
) {}
```

**7. `ShipDispatcher` 联动**

文件：`pipeline/ShipDispatcher.java`

**(7a) 构造函数注入 `EncounterClassifier`：**

```java
private final EncounterClassifier encounterClassifier;
// 追加到构造函数参数列表
```

**(7b) 提取私有方法 `batchClassify()`：**

将批量分类逻辑封装为独立方法，供正常路径和 cleanup 路径共用，避免字段抖动：

```java
private Map<String, EncounterClassificationResult> batchClassify(
        ShipStatus ownShip, Collection<ShipStatus> allShips) {
    if (ownShip == null || allShips == null) {
        return Map.of();
    }
    Map<String, EncounterClassificationResult> results = new HashMap<>();
    String ownId = ownShip.getId();
    for (ShipStatus ship : allShips) {
        if (ship == null || ship.getId() == null || ship.getId().equals(ownId)) {
            continue;
        }
        results.put(ship.getId(), encounterClassifier.classify(ownShip, ship));
    }
    return results;
}
```

**(7c) `runDerivations()` 调用 `batchClassify()`：**

```java
Map<String, EncounterClassificationResult> encounterResults =
        batchClassify(context.ownShip(), context.allShips().values());

return new ShipDerivedOutputs(shipDomainResult, cvPredictionResults, cpaResults, encounterResults);
```

> `ownShipId` 局部变量复用 Step 2 已有声明，不重复声明。`context.hasOwnShip()` 已在 `batchClassify()` 内部 null-check 处理，外部无需再判断。

**(7d) `refreshAfterCleanup()` 调用 `batchClassify()`：**

```java
Map<String, EncounterClassificationResult> encounterResults = batchClassify(ownShip, allShips);

RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
        ownShip, allShips, cpaResults, riskResult, domainResult, Map.of(), encounterResults);
```

cleanup 路径不做批量 CV 预测（`Map.of()` for cvResults），但会遇分类与 cleanup 路径对称执行，消除字段抖动。

**(7e) `buildRiskSnapshot()` 传递 `encounterResults`：**

```java
RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
        context.ownShip(),
        context.allShips(),
        outputs.cpaResults(),
        riskResult,
        outputs.shipDomainResult(),
        outputs.cvPredictionResults(),
        outputs.encounterResults()    // 新增
);
```

**8. `RiskObjectAssembler` 路由 `encounterResults`**

文件：`pipeline/assembler/RiskObjectAssembler.java`

```java
public RiskObjectDto assembleRiskObject(
        ShipStatus ownShip,
        Collection<ShipStatus> allShips,
        Map<String, CpaTcpaResult> cpaResults,
        RiskAssessmentResult riskResult,
        ShipDomainResult domainResult,
        Map<String, CvPredictionResult> cvResults,
        Map<String, EncounterClassificationResult> encounterResults   // 新增
) {
    ...
    return RiskObjectDto.builder()
            ...
            .targets(targetAssembler.assembleTargets(
                    ownShip, allShips, cpaResults, riskResult, cvResults, encounterResults))
            ...
            .build();
}
```

**9. `TargetAssembler` 写入 `encounter_type`**

文件：`pipeline/assembler/riskobject/TargetAssembler.java`

**(9a) `assembleTargets()` 增加参数：**

```java
public List<Map<String, Object>> assembleTargets(
        ShipStatus ownShip,
        Collection<ShipStatus> allShips,
        Map<String, CpaTcpaResult> cpaResults,
        RiskAssessmentResult riskResult,
        Map<String, CvPredictionResult> cvResults,
        Map<String, EncounterClassificationResult> encounterResults   // 新增
) {
    ...
    EncounterClassificationResult encounterResult =
            encounterResults == null ? null : encounterResults.get(ship.getId());
    targets.add(assembleTarget(ownShip, ship, cpaResult, assessment, cvResult, encounterResult));
    ...
}
```

**(9b) `assembleTarget()` 写入 `risk_assessment.encounter_type`：**

```java
public Map<String, Object> assembleTarget(
        ShipStatus ownShip,
        ShipStatus targetShip,
        CpaTcpaResult cpaResult,
        TargetRiskAssessment assessment,
        CvPredictionResult cvResult,
        EncounterClassificationResult encounterResult   // 新增
) {
    ...
    // risk_assessment 末尾追加
    if (encounterResult != null) {
        riskAssessment.put("encounter_type", encounterResult.getEncounterType().name());
    }
    ...
}
```

**10. `LlmRiskTargetContext` 新增 `encounterType` 字段**

文件：`llm/dto/LlmRiskTargetContext.java`

```java
@Data @Builder
public class LlmRiskTargetContext {
    ...
    private EncounterType encounterType;   // 新增，null = 数据不足
}
```

**11. `LlmRiskContextAssembler` 注入分类器并统一参考轴**

文件：`llm/context/LlmRiskContextAssembler.java`

`LlmRiskContextAssembler` 的数据来源为 `RiskAssessmentCompletedEvent`，不携带 `ShipDerivedOutputs`。直接注入 `EncounterClassifier` 并按需调用，避免修改事件结构。

**(11a) 构造函数注入：**

```java
private final EncounterClassifier encounterClassifier;

public LlmRiskContextAssembler(EncounterClassifier encounterClassifier) {
    this.encounterClassifier = encounterClassifier;
}
```

**(11b) `buildTargetContexts()` 中调用分类器并统一参考轴：**

```java
EncounterClassificationResult enc = encounterClassifier.classify(ownShip, ship);

targets.add(LlmRiskTargetContext.builder()
        ...
        .encounterType(enc.getEncounterType())
        .build());
```

**(11c) `resolveRelativeBearingDeg()` 统一使用 COG：**

```java
// 修改前
double referenceHeading = ownShip.getHeading() != null ? ownShip.getHeading() : ownShip.getCog();
return (trueBearing - referenceHeading + 360.0) % 360.0;

// 修改后（与 EncounterClassifier 参考轴对齐，均以 COG 为准）
return (trueBearing - ownShip.getCog() + 360.0) % 360.0;
```

> `HDG != COG` 时（如强横流场景），此处是行为变更：`relativeBearingDeg` 从船体朝向变为实际运动方向。对 LLM 会遇解释而言，COG 是正确选择——描述的是运动轨迹的几何关系，而非船体指向。

**12. 协议文件更新**

**(12a) `docs/EVENT_SCHEMA.md`**

在 `target.risk_assessment` 示例 JSON 中，在 `ozt_sector` 之后追加：

```json
"encounter_type": "CROSSING"
```

补充字段说明：
```
encounter_type  string  可选。相对几何态势分类：HEAD_ON / OVERTAKING / CROSSING / UNDEFINED。
                        COG 无效或 refreshAfterCleanup 路径不做分类时该字段缺失。
```

**(12b) `frontend/src/types/schema.d.ts`**

在 `RiskAssessment` interface 中追加可选字段：

```typescript
export type EncounterType = 'HEAD_ON' | 'OVERTAKING' | 'CROSSING' | 'UNDEFINED';

export interface RiskAssessment {
  risk_level: RiskLevel;
  cpa_metrics: CpaMetrics;
  graphic_cpa_line?: GraphicCpaLine;
  ozt_sector?: OztSector;
  encounter_type?: EncounterType;   // 新增
}
```

`EncounterType` 类型定义放在 `RiskLevel` 附近。

---

### Constraints

- **本步不修改 `RiskAssessmentEngine`**：会遇类型对风险评分的修正（追越修正系数、对遇权重等）由 Step 4 实现，`RiskAssessmentEngine.consume()` 签名不变。
- **`EncounterClassifier` 为纯计算组件**：无状态、无 I/O、无历史存储依赖，允许在 `LlmRiskContextAssembler` 内直接调用（重复计算量可忽略：纯算术，< 1µs/对）。
- **`batchClassify()` 与 cleanup 路径对称**：正常消息路径和 `refreshAfterCleanup()` 均调用 `batchClassify()`，`encounter_type` 字段不会因 cleanup 刷新而抖动。CV 预测同样对称执行：`refreshAfterCleanup()` 亦调用 `batchPredict()`，保证 `predicted_trajectory` 字段不因 cleanup 刷新而消失（Step 2 约束复核修正，见"已知问题"）。
- **OVERTAKING 双条件**：必须同时满足"几何在船尾弧内"与"航向近似同向（courseDiff ≤ overtakingCourseDiffMaxDeg）"。单纯位于船尾弧但反向航行的场景归入 CROSSING，不误判为追越。
- **CROSSING 是几何兜底分类**：HEAD_ON 和 OVERTAKING 之外所有有效态势均归入 CROSSING。其含义为"相对几何不满足前两类条件"，不承诺完整的 COLREGS 规则覆盖（如责任船判定、水域类型区分）。
- **UNDEFINED 的适用范围**：仅限 COG 数据无效（NaN / 负值 / ≥ 360.0）。低速、远距、相对运动退化等场景不产生 UNDEFINED，统一归入 CROSSING。
- **COG 作为唯一参考轴**：`EncounterClassifier` 和 `LlmRiskContextAssembler.resolveRelativeBearingDeg()` 均以 COG 为参考轴，语义统一为"实际运动轨迹方向"。HDG 可用于船体朝向可视化，不参与碰撞几何计算。
- **COG 无效哨兵属于 Engine 层**：`EncounterClassifier` 内部的 COG 异常拦截（NaN / <0 / ≥360.0）是 Engine 层 defensive programming，独立于 Step 5A 协议层 `qualityFlags` 校验，两者职责不同，互不替代。
- **`normalizeAngle360` 不提取至 `GeoUtils`**：仅 `EncounterClassifier` 内部使用，保持私有静态方法即可。

---

### Protocol Impact

目标船协议结构 `target.risk_assessment` 新增可选字段 `encounter_type`：

```json
{
  "id": "413999001",
  "tracking_status": "tracking",
  "position": { "lon": 114.31, "lat": 30.51 },
  "vector": { "speed_kn": 8.0, "course_deg": 45.0 },
  "risk_assessment": {
    "risk_level": "WARNING",
    "cpa_metrics": { "dcpa_nm": 0.42, "tcpa_sec": 180.0 },
    "graphic_cpa_line": { "own_pos": [114.3, 30.5], "target_pos": [114.31, 30.51] },
    "ozt_sector": { "start_angle_deg": 35.0, "end_angle_deg": 55.0, "is_active": true },
    "encounter_type": "CROSSING"
  },
  "predicted_trajectory": { ... }
}
```

`encounter_type` 取值：`"HEAD_ON"` / `"OVERTAKING"` / `"CROSSING"` / `"UNDEFINED"`。
字段可选，COG 无效时缺失。前端类型定义在 `schema.d.ts` 的 `RiskAssessment` 中补充 `encounter_type?: EncounterType`。

LLM 侧：`LlmRiskTargetContext.encounterType` 新增，`RiskContextFormatter` 的 prompt 引用在下一轮优化时处理，本步不修改 formatter。

---

### Test Plan

- **`EncounterClassifierTest`**（新增）

  | 场景 | ownCOG | 目标方位（相对船头） | 目标 COG | courseDiff | 期望 |
  |------|--------|---------------------|---------|-----------|------|
  | 正对遇 | 0° | 0°（正前方） | 180° | 180° | HEAD_ON |
  | 准对遇（边界内） | 0° | 10°、目标 COG=175° | 175° | 175° | HEAD_ON |
  | 准对遇（边界外，方位超 15°） | 0° | 20°、目标 COG=175° | 175° | 175° | CROSSING |
  | 准对遇（边界外，courseDiff<170°） | 0° | 0°、目标 COG=155° | 155° | 155° | CROSSING |
  | 正追越（同向，目标在正后方） | 0° | 180° | 0° | 0° | OVERTAKING |
  | 追越（边界，relBearing=112.5°） | 0° | 112.5° | 350° | 10° | OVERTAKING |
  | 追越边界外（relBearing=111.4°） | 0° | 111.4° | 350° | 10° | CROSSING |
  | **追越误判修正：在船尾弧但反向** | 0° | 180° | 180° | 180° | CROSSING（courseDiff>112.5°） |
  | **追越误判修正：在船尾弧但大角度交叉** | 0° | 180° | 120° | 120° | CROSSING（courseDiff>112.5°） |
  | 交叉（右舷正横） | 0° | 90°、目标 COG=270° | 270° | 90° | CROSSING |
  | COG=360（sentinel） | 任意 | — | 360° | — | UNDEFINED |
  | COG=NaN | NaN | — | 任意 | — | UNDEFINED |
  | COG 负值 | 任意 | — | -1° | — | UNDEFINED |
  | targetId 传递 | 任意 | — | 任意 | — | result.targetId == 输入 targetId |
  | isWithinBow 边界包含 | 0° | relBearing=15.0° | 180° | 180° | HEAD_ON（边界含） |
  | isWithinStern 边界包含 | 0° | relBearing=112.5° | 0° | 0° | OVERTAKING（边界含） |

- **`GeoUtilsTest` 补充**

  | 用例 | 期望 |
  |------|------|
  | `angleDifference(10, 350)` | 20.0 |
  | `angleDifference(0, 180)` | 180.0 |
  | `angleDifference(90, 90)` | 0.0 |
  | `angleDifference(0, 270)` | 90.0（取最小角） |
  | `angleDifference(350, 10)` | 20.0（顺序无关） |

- **`TargetAssemblerTest` 补充**

  - 传入有效 `EncounterClassificationResult`（类型=CROSSING）→ `risk_assessment` 包含 `"encounter_type": "CROSSING"`
  - 传入 `encounterResult=null` → `risk_assessment` 不包含 `encounter_type` 键
  - 传入 `UNDEFINED` 类型 → 字段值为 `"UNDEFINED"`（枚举名称传递正确）

- **`ShipDispatcherTest` 补充场景**

  - **批量分类路径**：构造 ownShip + 2 艘目标船，验证 `runDerivations()` 返回的 `encounterResults` Map 包含 2 个 key，ownShipId 不出现其中
  - **cleanup 路径对称**：`refreshAfterCleanup()` 后发布的快照中，目标船 `encounter_type` 不缺失（与正常路径一致）

- **编译回归**：`./mvnw -q -DskipTests compile` 无错误，`ShipDispatcherTest` / `TargetAssemblerTest` / `OwnShipAssemblerTest` 原有测试继续通过。

---

### Assumptions

- **Step 2 已完成**：`ShipDerivedOutputs` 使用 `Map<String, CvPredictionResult>`，`TargetAssembler.assembleTargets()` 已接受该 Map 参数。`refreshAfterCleanup()` 调用 `batchPredict()` 后将结果传入 `RiskObjectAssembler`（Step 2 原约束要求对称；Step 3 实现时误传 `Map.of()`，已在 Step 2/3 合并实施阶段修复）。
- **`GeoUtils.trueBearing()` 已返回 [0, 360)**：当前实现 `bearing >= 0 ? bearing : bearing + 360.0` 确保这一点，`EncounterClassifier` 直接依赖此约定。
- **`ShipStatus.getCog()` 单位为度**：范围 [0.0, 359.9]，360.0 为 "not available"，负值和 NaN 视为数据异常；单位一致性由数据入口 `AisMessageMapper` 保证。
- **COG 为碰撞几何的正确参考轴**：内河场景中横流不是主要误差源，COG 与实际运动方向误差可接受；若未来需要区分 HDG 与 COG 的影响，应在 Step 4 中单独建模，不在本步处理。

---

### 已知问题与后续修复计划

代码审查发现以下问题，均属跨步骤技术债，在对应步骤实施时处理，Step 3 本身不修改。

| # | 问题 | 严重度 | 计划处理步骤 |
|---|------|--------|------------|
| 1 | `RiskAssessmentEngine.consume()` 第五参数 `cvPredictionResult` 在 engine 实现内从未读取，始终接收 `null` | P1 | **Step 4**：多因子风险评估重构时统一接线，将 `Map<String, CvPredictionResult>` 纳入评分逻辑，同步修改签名 |
| 2 | AIS 哨兵常量重复定义：`AIS_SOG_NOT_AVAILABLE_KN = 102.3` 在 `CvPredictionEngine` 与 `ShipDomainEngine` 各自独立声明；COG 无效谓词在 `EncounterClassifier`、`CvPredictionEngine` 各自实现 | P1 | **Step 6**：剩余 Mock 清理阶段统一提取至共享常量类（候选名 `AisProtocolConstants`），届时一次性消除所有副本 |
| 3 | `ShipTrajectoryStore.getHistory()` 在生产代码中无调用点；`append()` 每条消息均执行完整列表拷贝，存在无消费者的纯开销 | P1 | **Step 5B**：运动学连续性校验接入时连接 `getHistory()`；届时评估是否将存储结构从 copy-on-write `ArrayList` 改为 `ArrayDeque` 减少分配 |
| 4 | `TargetAssembler.buildPredictedTrajectory()` 中 `"prediction_type": "cv"` 与 `OwnShipAssembler` 中 `"prediction_type": "linear"` 均为裸字符串字面量，无常量定义，与协议键 `"encounter_type"` 同属字符串化协议约定 | P2 | **Step 6**：与 assembler 硬编码清理合并处理，提取为包级协议常量或枚举 |
