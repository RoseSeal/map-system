# Step 4A 执行计划：多因子风险评估增强

> 基于 ENGINE_ENHANCEMENT_PLAN.md §Step 4A，结合 Step 1-3 已实现的代码现状制定。

---

## 一、现状快照

### 当前 `RiskAssessmentEngine` 行为

- `consume()` 签名已接收 `ShipDomainResult` 和 `CvPredictionResult`，但**完全未使用**——内部仅调用 `buildTargetAssessment(targetId, CpaTcpaResult)`
- `classifyRisk(dcpaNm, tcpaSec)` 做 DCPA/TCPA 双阈值四级分类，逻辑正确
- `ShipDispatcher` 调用时 `CvPredictionResult` 参数**硬编码传 null**；`ShipDomainResult` 传了但未消费
- `encounterResults` 存在于 `ShipDerivedOutputs` 中，但**未传入** `RiskAssessmentEngine`

### Step 1-3 产出的可消费接口

| 来源 | 类型 | 获取方式 | 键 |
| --- | --- | --- | --- |
| Step 1 | `ShipDomainResult` | `outputs.shipDomainResult()` | 本船唯一 |
| Step 2 | `Map<String, CvPredictionResult>` | `outputs.cvPredictionResults()` | targetId |
| Step 3 | `Map<String, EncounterClassificationResult>` | `outputs.encounterResults()` | targetId |
| 已有 | `Map<String, CpaTcpaResult>` | `outputs.cpaResults()` | targetId |

### 当前 `TargetRiskAssessment` 字段

```
targetId, riskLevel, cpaDistanceMeters, tcpaSeconds, approaching,
explanationSource, explanationText
```

---

## 二、改动清单

按文件维度列出，标注依赖顺序。

### Phase A：新增组件（无外部依赖，可并行）

#### A1. `RiskScoringProperties`

- **路径**: `config/properties/RiskScoringProperties.java`
- **职责**: 多因子评分权重与会遇修正系数配置
- **字段**:
  ```
  prefix = "risk.scoring"
  geometryWeight = 0.6        // 几何运动因子权重
  domainWeight = 0.4          // 域侵入因子权重
  headOnModifier = 1.2        // 对遇修正系数
  crossingModifier = 1.0      // 交叉修正系数
  overtakingModifier = 0.8    // 追越修正系数
  undefinedModifier = 1.0     // 未定义会遇类型修正系数（透传）
  ```
- **注册**: `@Component` + `@ConfigurationProperties`，与现有 Properties 类一致

#### A2. `DomainPenetrationCalculator`

- **路径**: `engine/risk/DomainPenetrationCalculator.java`
- **职责**: 计算目标船对本船安全领域的侵入比
- **接口**:
  ```java
  @Component
  public class DomainPenetrationCalculator {
      /**
       * @return penetrationRatio: > 0 表示已侵入，0 表示边界上，< 0 表示域外。
       *         null 如果输入不足。
       */
      public Double calculate(
          ShipStatus ownShip,
          ShipStatus targetShip,
          ShipDomainResult domainResult
      )
  }
  ```
- **算法**:
  1. 计算 target 相对于 ownShip 的平面偏移 `(dx, dy)` — 复用 `GeoUtils.toXY()` 差值
  2. 以 ownShip heading（fallback to COG）为 y 轴正方向，旋转到 body frame:
     ```
     heading = ownShip.heading (若 heading 无效则用 cog)
     angle = toRadians(heading)
     dxBody = dx * cos(angle) - dy * sin(angle)
     dyBody = dx * sin(angle) + dy * cos(angle)
     ```
  3. 选取椭圆半径：
     ```
     longitudinalR = (dyBody >= 0) ? domainResult.foreNm : domainResult.aftNm
     lateralR      = (dxBody >= 0) ? domainResult.stbdNm : domainResult.portNm
     ```
     （注意：坐标系中 dx 正方向为右舷/starboard，负为左舷/port）
  4. 椭圆判定（NM 单位，偏移量需从米转 NM）:
     ```
     dxNm = GeoUtils.metersToNm(dxBody 对应的米值)  // 实际上 dx/dy 已经是米
     dyNm = GeoUtils.metersToNm(dyBody 对应的米值)
     ellipseValue = (dxNm / lateralR)^2 + (dyNm / longitudinalR)^2
     penetrationRatio = 1.0 - ellipseValue   // > 0 = 已侵入
     ```
  5. 边界处理：若 `domainResult == null` 或任一半径 ≤ 0 → 返回 `null`

- **注意**：`toXY()` 的平面坐标是基于 (lon, lat) 的等距投影，dx/dy 单位为米。需要先算出目标-本船的平面差值，再旋转。坐标系约定：x = east，y = north。旋转到 body frame（heading 为 north-up 顺时针）后 dxBody = starboard 方向，dyBody = fore 方向。

#### A3. `PredictedCpaCalculator`

- **路径**: `engine/risk/PredictedCpaCalculator.java`
- **职责**: 基于本船 CV 外推 + 目标船预测轨迹，计算 pairwise 预测 CPA
- **接口**:
  ```java
  @Component
  public class PredictedCpaCalculator {
      public PredictedCpaCalculator() {}

      /**
       * @return {predictedCpaNm, predictedTcpaSec}，或 null 如果无法计算
       */
      public double[] calculate(ShipStatus ownShip, CvPredictionResult targetPrediction)
  }
  ```
- **说明**: `TrajectoryPredictionProperties` 未注入——算法直接使用轨迹点自带的 `offsetSeconds`，不需要全局步长配置。
- **算法**:
  1. 若 `targetPrediction == null` 或 trajectory 为空 → 返回 `null`
  2. 若 ownShip SOG/COG 无效（SOG sentinel 102.3, COG 不在 [0,360)）→ 返回 `null`
  3. 计算本船速度矢量 `GeoUtils.toVelocity(ownSog, ownCog)`
  4. 遍历 `targetPrediction.trajectory` 的各时间步 `t`:
     - 本船外推位置: `GeoUtils.displace(ownLat, ownLon, vx*t, vy*t)`
     - 目标船预测位置: `point.latitude, point.longitude`
     - 距离: `GeoUtils.distanceMetersByXY(ownExtrap, targetPredicted)`
  5. 取最小距离对应的 `(distanceNm, offsetSeconds)`
  6. 返回 `{predictedCpaNm, predictedTcpaSec}`

### Phase B：扩展现有类型

#### B1. `TargetRiskAssessment` 新增字段

```java
private double riskScore;            // 0.0-1.0 连续风险值（默认 0.0）
private double riskConfidence;       // 置信度（默认 1.0）
private EncounterType encounterType; // 会遇类型（可 null）
private Double domainPenetration;    // 域侵入比（> 0 已侵入，null = 无数据）
```

- `riskScore` 和 `riskConfidence` 用原始类型 `double`，与现有 `cpaDistanceMeters`/`tcpaSeconds` 一致
- `encounterType` 用引用类型，允许 null（UNDEFINED 枚举值用于有效数据但不满足分类条件的场景；null 用于根本未计算的场景）
- `domainPenetration` 用 `Double`，null 表示无域数据

#### B2. `RiskAssessmentEngine` 签名重构

**新签名**:
```java
public RiskAssessmentResult consume(
    ShipStatus ownShip,
    Collection<ShipStatus> allShips,
    Map<String, CpaTcpaResult> cpaResults,
    ShipDomainResult shipDomainResult,
    Map<String, CvPredictionResult> cvPredictionResults,     // 替换单个 CvPredictionResult
    Map<String, EncounterClassificationResult> encounterResults  // 新增
)
```

**注入新依赖**:
- `RiskScoringProperties`
- `DomainPenetrationCalculator`
- `PredictedCpaCalculator`

**`buildTargetAssessment()` 签名变更**:
```java
private TargetRiskAssessment buildTargetAssessment(
    String targetId,
    CpaTcpaResult cpaResult,
    ShipStatus ownShip,                        // 新增：域侵入 + 预测 CPA 需要
    ShipStatus targetShip,                     // 新增：域侵入需要
    ShipDomainResult domainResult,             // 新增
    CvPredictionResult predictionResult,       // 新增
    EncounterClassificationResult encounterResult  // 新增
)
```

### Phase C：评分逻辑实现

在 `buildTargetAssessment()` 中执行以下评分流程：

#### C1. `riskLevel` 判定（不变）

`classifyRisk(dcpaNm, rawTcpaSec)` 逻辑完全保留，继续由 DCPA/TCPA 阈值驱动。这是兼容原则的核心保障。

补充边界：

- 当 `cpaResult.isCpaValid() == false` 时（相对速度过小，无法判定收敛趋势）：
  - `riskLevel = SAFE`——没有有效的收敛运动，不产生报警
  - `tcpaScore = 0`、`approaching = false`
  - `dcpaScore` 仍按当前距离正常计算，`geometryScore`、`domainScore`、`encounterModifier` 继续参与 `riskScore` 合成
  - 净效果：`riskScore` 反映当前空间接近度，但 `riskLevel` 不升级

#### C2. `geometryScore` 计算

```
归一化 DCPA 分量: dcpaScore = 1.0 - clamp(dcpaNm / cautionDcpaNm, 0, 1)
  // dcpaNm 越小（越近），dcpaScore 越高
归一化 TCPA 分量: tcpaScore = approaching ? (1.0 - clamp(tcpaSec / cautionTcpaSec, 0, 1)) : 0
  // tcpaSec 越小（越近），tcpaScore 越高；已远离则为 0
geometryScore = (dcpaScore + tcpaScore) / 2.0

预测 CPA 修正:
  predictedCpa = predictedCpaCalculator.calculate(ownShip, predictionResult)
  if (predictedCpa != null) {
      predictedCpaNm = predictedCpa[0]
      denominator = max(dcpaNm, 0.001)
      if (dcpaNm > 0) {
          if (predictedCpaNm < dcpaNm) {
              // 局势恶化：提升 geometryScore
              worseningRatio = clamp((dcpaNm - predictedCpaNm) / denominator, 0, 0.3)
              geometryScore = clamp(geometryScore + worseningRatio, 0, 1)
          } else {
              // 局势缓解：适度降低
              easingRatio = clamp((predictedCpaNm - dcpaNm) / denominator, 0, 0.15)
              geometryScore = clamp(geometryScore - easingRatio, 0, 1)
          }
      } else if (predictedCpaNm > 0) {
          // 当前 DCPA 已为 0，不再做比例修正，避免除零
          geometryScore = geometryScore
      }
  }
```

- `cautionDcpaNm` / `cautionTcpaSec` 取自现有 `RiskAssessmentProperties`（最宽阈值，用作归一化参考）
- 预测修正的上限(0.3/0.15)为内联常量，首版不需要配置化——效果确认后再决定是否提取

#### C3. `domainScore` 计算

```
penetration = domainPenetrationCalculator.calculate(ownShip, targetShip, domainResult)
if (penetration != null) {
    domainScore = clamp(penetration, 0, 1)   // 仅侵入时贡献分值
} else {
    domainScore = 0   // 无域数据时不参与
    // 权重重分配: geometryWeight 吸收 domainWeight
}
```

权重重分配逻辑：
- 当 `domainScore` 不可用时，`riskScore = geometryScore * encounterModifier`（不乘以权重，因为只剩一个因子）
- 当两者都可用时，`riskScore = (w1 * geometryScore + w2 * domainScore) * encounterModifier`

#### C4. `encounterModifier` 获取

```
if (encounterResult != null && encounterResult.encounterType != UNDEFINED) {
    encounterModifier = switch(encounterResult.encounterType) {
        HEAD_ON     -> properties.headOnModifier       // 1.2
        CROSSING    -> properties.crossingModifier      // 1.0
        OVERTAKING  -> properties.overtakingModifier    // 0.8
    }
} else {
    encounterModifier = properties.undefinedModifier    // 1.0（透传）
}
```

#### C5. 最终 `riskScore` 合成

```
if (domainResult != null && penetration != null) {
    riskScore = (geometryWeight * geometryScore + domainWeight * domainScore) * encounterModifier
} else {
    riskScore = geometryScore * encounterModifier
}
riskScore = clamp(riskScore, 0.0, 1.0)
```

#### C6. `riskConfidence`

```
riskConfidence = min(
    ownShip.confidence  != null ? ownShip.confidence  : 1.0,
    targetShip.confidence != null ? targetShip.confidence : 1.0
)
```

- 首版直接取两船 confidence 最小值
- Step 5A/5B 完成后，confidence 将受 qualityFlags 影响而自动传播

### Phase D：管线集成

#### D1. `ShipDispatcher` 调用变更

`buildRiskSnapshot()` 中 `riskAssessmentEngine.consume()` 调用改为传入全部输入：

```java
// 变更前
riskAssessmentEngine.consume(ownShip, allShips, cpaResults, domainResult, null)

// 变更后
riskAssessmentEngine.consume(
    ownShip, allShips, cpaResults,
    outputs.shipDomainResult(),
    outputs.cvPredictionResults(),
    outputs.encounterResults()
)
```

同时更新 `refreshAfterCleanup()` 中的调用。

#### D2. `TargetAssembler` 传播新字段

在 `riskAssessment` map 中新增：
```java
if (assessment != null) {
    riskAssessment.put("risk_score", assessment.getRiskScore());
    riskAssessment.put("risk_confidence", assessment.getRiskConfidence());
}
// encounter_type 已在 Step 3 填充，无需变更
```

#### D3. `LlmRiskTargetContext` 传播（可选，Step 4 暂不做）

`riskScore` 和 `riskConfidence` 是辅助排序字段，LLM prompt 中暂不需要。保持 `LlmRiskContextAssembler` 不变。

---

## 三、`MathUtils.clamp()` 提取

Step 1 已有一处内联 `Math.min(Math.max(...))`，Step 4 新增多处 clamp 操作。按照 ENGINE_ENHANCEMENT_PLAN.md Step 2 实现提示的约定，检查是否已提取 `MathUtils.clamp()`。

- 若已存在 → 直接复用
- 若不存在 → 新增 `util/MathUtils.java`，并替换 Step 1 中 `ShipDomainEngine` 的内联写法

---

## 四、CPA 无效状态处理

`CpaTcpaResult.isCpaValid() == false` 表示相对速度过小、无法判定收敛趋势（平行或静止船舶）。实现行为如下：

- `riskLevel = SAFE`——无有效收敛运动，不产生报警；静止/平行船只即使当前距离很近也不升级
- `approaching = false`，`tcpaSeconds = 0.0`
- `tcpaScore = 0`
- `dcpaScore` 按当前距离正常计算（`cpaDistance` 在 `cpaValid=false` 时等于当前实际距离）
- `geometryScore = dcpaScore / 2.0`（tcpaScore=0 使几何得分减半，体现”仅有空间接近、无时间收敛”的语义）
- `domainScore`、`encounterModifier`、`riskConfidence` 继续按正常流程计算
- 预测修正分支（`predictedCpaCalculator`）跳过——收敛趋势未知时预测无意义
- 净效果：`riskScore` 反映当前空间接近度（可用于排序），但 `riskLevel` 保持 SAFE

---

## 五、实现顺序

```
Phase A: A1 + A2 + A3（三者互相独立，可并行）
Phase B: B1 → B2（B2 依赖 B1 的新字段）
Phase C: C1-C6（在 B2 内部实现）
Phase D: D1 → D2（D1 完成后管线才能运行）
```

建议实施路径（单次实现）：
1. 新增 `RiskScoringProperties`、`DomainPenetrationCalculator`、`PredictedCpaCalculator`
2. 扩展 `TargetRiskAssessment` 新字段
3. 重构 `RiskAssessmentEngine`（签名 + 评分逻辑）
4. 更新 `ShipDispatcher` 调用点（`buildRiskSnapshot` + `refreshAfterCleanup`）
5. 更新 `TargetAssembler` 传播 `risk_score`、`risk_confidence`
6. 同步更新 `docs/EVENT_SCHEMA.md` 和 `frontend/src/types/schema.d.ts`
7. 检查/提取 `MathUtils.clamp()`

---

## 六、验证清单

### 回归测试（兼容原则，P0）

| 场景 | 预期 |
| --- | --- |
| domain=null, encounter=null, prediction=null | `riskLevel` 与旧 `classifyRisk()` 在相同 DCPA/TCPA 下完全一致 |
| cpaValid=false 且距离很近 | `riskLevel = SAFE`（无收敛趋势），`riskScore > 0`（dcpaScore 反映接近度） |
| ownShip=null 或 allShips=null | 返回 `RiskAssessmentResult.empty()` |

### 多因子测试

| 场景 | 预期 |
| --- | --- |
| 域侵入（penetration > 0） | `riskScore` 显著高于非侵入场景 |
| 对遇 vs 追越（其余条件相同） | 对遇的 `riskScore` > 追越的 `riskScore` |
| 预测 CPA < 当前 DCPA | `geometryScore` 上修 |
| 预测 CPA > 当前 DCPA | `geometryScore` 下修 |

### 域侵入计算测试

| 场景 | 预期 |
| --- | --- |
| 目标在本船正前方、距离 < foreNm | `penetration > 0` |
| 目标在本船正后方、距离 > aftNm | `penetration < 0` |
| domainResult 为 null | 返回 null |

### 预测 CPA 计算测试

| 场景 | 预期 |
| --- | --- |
| 已知直线轨迹 | `predictedCpaNm` 与手算值一致 |
| targetPrediction 为 null | 返回 null |
| ownShip SOG = 102.3（sentinel） | 返回 null |

---

## 七、协议影响

新增前端协议字段（`target.risk_assessment` 下）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `risk_score` | `double` | 0.0-1.0，多因子综合风险分 |
| `risk_confidence` | `double` | 0.0-1.0，受数据质量影响的置信度 |

除后端 `TargetAssembler` 外，还需同步更新：

- `docs/EVENT_SCHEMA.md`：补充 `risk_assessment.risk_score` / `risk_assessment.risk_confidence`
- `frontend/src/types/schema.d.ts`：扩展 `RiskAssessment` 类型定义

`encounter_type` 已在 Step 3 由 `TargetAssembler` 填充，Step 4 不重复。`risk_score` 首版为辅助字段，前端用于同级别目标排序，不替换 `risk_level` 的警告触发逻辑。

---

## 八、不做的事

- **不改 `classifyRisk()` 逻辑** — `riskLevel` 仍由 DCPA/TCPA 阈值驱动
- **不让 `riskScore` 驱动 `riskLevel`** — 待规则化模型验证稳定后再推进
- **不改 LLM context** — `riskScore` 是排序辅助，LLM prompt 不需要
- **不引入 D-S 证据理论** — 本轮为规则化加权评分，D-S 是后续演进方向
- **不做目标船安全领域** — 域侵入为 own-ship-centric，仅判断目标是否进入本船域

---

## 九、已知局限与后续注意事项

以下问题在首版中有意保留，待积累运行数据后再决定是否调整。

### encounterModifier 全局乘子可能压缩极端场景的风险上限

`encounterModifier` 直接作用于最终 `riskScore`，当系数 < 1（追越 0.8）时，即使几何风险和域侵入都已饱和，最终得分仍被压低至 0.8。首版中 `riskScore` 不驱动 `riskLevel`，此压缩仅影响排序，不影响报警。

若后续发现该行为导致排序失真，替代方案：将会遇类型前移到阈值层（影响容忍半径）或仅作用于 `geometryScore` 单项，而非全局乘总分。在无真实数据标定修正系数前，两种方案优劣无法比较。

### riskScore 与 riskLevel 的长期定位

首版明确定位：

- **`riskLevel`**：主判定，驱动前端报警 UI、LLM 解释触发、目标筛选。由 DCPA/TCPA 阈值规则产生。
- **`riskScore`**：辅助排序信号，前端可用于同 `riskLevel` 内的目标排序（如"WARNING 中哪个更危险"）。不参与报警触发，不进入 LLM prompt。

后续若要升级 `riskScore` 为主判定依据，需要：(1) 规则化模型在生产环境运行至少一个迭代周期，(2) 风险分布验证通过（无系统性偏高/偏低），(3) 阈值标定有数据支撑。在此之前，两者保持分离。
