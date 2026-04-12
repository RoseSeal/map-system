# Engine 增强实现规划

> 最后更新：2026-04-11
> 对应 ARCHITECTURE.md 中 P3 的 Engine 相关待办项

---

## 零、当前状态

| 能力 | 现状 |
| --- | --- |
| CPA/TCPA 碰撞检测 | 已实现，等距投影 + 相对运动模型，误差 < 0.5% |
| 风险等级分类 | 已实现，基于 DCPA/TCPA 双阈值四级分类（SAFE/CAUTION/WARNING/ALARM），阈值可配置 |
| 本船安全领域 | `ShipDomainEngine` 为空壳，`calculate()` 返回 null；`ShipDomainResult` 无字段 |
| 目标船航迹预测 | `CvPredictionEngine` 为空壳，`predict()` 返回 null；`CvPredictionResult` 无字段 |
| 会遇态势识别 | 不存在，风险引擎不区分对遇/追越/交叉 |
| 数据质量校验 | `AisMessageMapper` 仅做 MMSI 格式校验和 heading=511 处理；`ShipStatus.confidence` 由配置文件静态赋值，未参与任何计算 |
| Assembler 硬编码 | 安全领域尺寸（0.5/0.1/0.2/0.2 nm）、OZT 扇区角度（±10°）、平台健康状态（"NORMAL"）、治理元信息（trust_factor=0.99）、环境上下文（safety_contour=10.0）均为固定值 |

**核心瓶颈**：

1. `ShipDomainEngine` 和 `CvPredictionEngine` 虽已在 `ShipDispatcher` 管线中被调用，但返回 null，下游 assembler 全部退化为硬编码默认值。
2. `RiskAssessmentEngine.consume()` 签名已接收 `ShipDomainResult` 和 `CvPredictionResult`，但实现中完全未使用这两个参数——风险评估仅依赖 CPA/TCPA。
3. 缺乏会遇类型识别，风险解释无法区分对遇、追越、交叉等场景，与内河避碰规则脱节。

---

## 一、实现步骤总览

```
Step 1  本船安全领域                          ← 动态船舶域替代硬编码尺寸
Step 2  目标船航迹预测（CV 模型）              ← 航迹外推轨迹，为前端可视化和 Step 4 提供输入
Step 3  会遇态势识别                          ← 对遇/追越/交叉分类
Step 4  多因子风险评估增强                     ← 域侵入 + pairwise 预测 CPA + 会遇修正 → 综合风险分
Step 5A AIS 协议层质量校验                    ← Mapper 来源特定校验 + qualityFlags
Step 5B AIS 运动学连续性校验                  ← 位置跳变/速度突变检测（依赖 ShipTrajectoryStore）
Step 6  Mock 清理与管线集成                    ← 消除全部硬编码，端到端集成
```

### 依赖关系

```
Step 1 ──→ Step 4
Step 2 ──→ Step 4
Step 2 ──→ Step 5B
Step 3 ──→ Step 4
                └──→ Step 6
Step 5A（独立，可与 Step 1-3 并行）──→ Step 6
Step 5B（依赖 Step 2）────────────────→ Step 6
```

| 步骤 | 预期改动量 | 前置依赖 |
| --- | --- | --- |
| Step 1 | 中（1 个引擎 + 1 个 Result + 配置类） | 无 |
| Step 2 | 中（1 个引擎 + 历史存储 + Result） | 无 |
| Step 3 | 小（1 个引擎 + 枚举 + GeoUtils 扩展） | 无 |
| Step 4 | 大（重构 RiskAssessmentEngine + pairwise 预测 CPA） | Step 1, 2, 3 |
| Step 5A | 小（Mapper 增强 + qualityFlags） | 无 |
| Step 5B | 小-中（运动学校验器，新增 qualityFlags 类型） | Step 2 |
| Step 6 | 中（assembler 全面替换 + 集成测试） | Step 4, 5A, 5B |
| Step 7 | 中（包结构全面重组，纯重构，无逻辑变更） | Step 6 |

---

## Step 7：包结构重组（垂直功能域化）

### 背景

当前包结构在 LLM 模块解耦完成后已做 Option A 小修缮（`event/`、`pipeline/assembler/` 等），但整体仍混用横向分层与纵向子模块两种范式。Step 6 完成后是最后一次大规模 assembler 改动，此后改做全面重组风险最小。

### 目标结构

```
map_service/
  ais/             ← mqtt/ + store/（AIS 数据入口与状态存储）
  chart/           ← api/S57Controller + repository/S57TileRepository（航图服务）
  risk/            ← engine/ + pipeline/ + event/（风险评估核心）
  llm/             ← 保持现有内部结构不变
  shared/          ← domain/ + dto/ + config/ + util/（跨域共享类型）
  api/             ← RiskSseController（保留，SSE 出口）
```

### 做什么

1. **新建顶层包**：`ais/`、`chart/`、`risk/`、`shared/`
2. **迁移**（纯包声明 + import 变更，不改逻辑）：
   - `mqtt/*` → `ais/mqtt/`，`store/*` → `ais/store/`
   - `repository/*`、`api/S57Controller` → `chart/`
   - `engine/*`、`pipeline/*`、`event/*` → `risk/engine/`、`risk/pipeline/`、`risk/event/`
   - `domain/*`、`dto/*`、`config/*`、`util/*` → `shared/`（或保留 `shared/` 前缀）
3. **批量 import 替换**（sed/IDE 重构工具），逐包验证编译

### 约束

- **纯重构**：零逻辑变更，每个文件只改 `package` 声明和 `import`
- **前置条件**：Step 6 已合并，所有 assembler 硬编码替换完成
- **验收**：全量单元测试通过，SSE/WS 端到端冒烟通过

---

## Step 1：本船安全领域

### 目标

实现动态船舶安全领域计算，替代 `OwnShipAssembler` 中硬编码的椭圆尺寸，为后续域侵入风险检测提供基础。

### 背景

船舶安全领域（Ship Domain）是指船舶周围不允许他船侵入的水域范围。内河航运中，安全领域受船速、船型、航道宽度等因素影响，不是固定值。

> **范围限定**：本轮安全领域**仅为本船建模**，不涉及目标船域。域侵入检测（Step 4）采用 own-ship-centric 视角：判断目标船是否进入本船安全领域。若后续需要双船责任判断（give-way/stand-on 基于域侵入），需另行引入目标船尺寸/船型信息；该能力不在本轮规划范围内。

### 模型选择

采用**四参数椭圆模型**（前/后/左/右四个半径），参数随船速动态缩放：

- 内河船舶在低速（< 5 kn）时安全领域显著缩小
- 纵向（前/后）半径通常大于横向（左/右）半径
- 左右不对称：按内河靠右航行惯例，左舷（来船侧）半径 > 右舷半径

公式结构：

```
fore  = baseFore  × speedFactor(sog)
aft   = baseAft   × speedFactor(sog)
port  = basePort  × speedFactor(sog)
stbd  = baseStbd  × speedFactor(sog)

speedFactor(sog) = clamp(sog / refSpeed, minFactor, maxFactor)
```

基础参数与缩放系数通过 `ShipDomainProperties` 配置，支持调优。

### 做什么

1. **定义 `ShipDomainResult`**

   ```java
   @Data @Builder
   public class ShipDomainResult {
       private double foreNm;     // 前方半径（海里）
       private double aftNm;      // 后方半径
       private double portNm;     // 左舷半径
       private double stbdNm;     // 右舷半径
       private String shapeType;  // "ellipse"
   }
   ```

2. **新增 `ShipDomainProperties`**

   ```java
   @ConfigurationProperties(prefix = "engine.ship-domain")
   public class ShipDomainProperties {
       private double baseForeNm = 0.5;
       private double baseAftNm = 0.15;
       private double basePortNm = 0.25;
       private double baseStbdNm = 0.15;
       private double referenceSpeedKn = 8.0;   // 参考航速
       private double minSpeedFactor = 0.5;     // 最小缩放
       private double maxSpeedFactor = 2.0;     // 最大缩放
   }
   ```

3. **实现 `ShipDomainEngine.calculate()`**

   - 输入：`ShipStatus`（取 sog）
   - 根据 `speedFactor = clamp(sog / refSpeed, min, max)` 计算四方向半径
   - 返回 `ShipDomainResult`

4. **`OwnShipAssembler` 消费 `ShipDomainResult`**

   - 当 `domainResult != null` 时使用计算值
   - 当 `domainResult == null` 时保留当前默认值作为 fallback

### 影响范围

- 修改 `ShipDomainResult`：从空类补全字段
- 修改 `ShipDomainEngine`：实现计算逻辑
- 新增 `ShipDomainProperties`
- 修改 `OwnShipAssembler`：消费真实计算结果

### 协议影响

`safety_domain.dimensions`（fore/aft/port/stbd）和 `shape_type` 字段已存在于 `own_ship` 协议结构中，当前为硬编码值。Step 1 完成后字段含义不变，仅数值变为动态计算。**不新增协议字段。**

### 验证方式

- 单元测试：不同航速下安全领域半径是否正确缩放
- 集成验证：前端渲染的本船安全领域椭圆是否随航速变化动态调整

---

## Step 2：目标船航迹预测（CV 模型）

### 目标

实现恒速（Constant Velocity）航迹预测模型，输出**每艘目标船**未来 N 分钟内的预测轨迹点序列，用于前端航迹可视化；同时建立 `ShipTrajectoryStore` 历史存储，作为 Step 4 pairwise 预测 CPA 计算和 Step 5B 运动学校验的基础设施。

> **接口边界约定**：`CvPredictionResult` 只描述单船轨迹（per-target），不涉及任何双船关系量。预测 CPA（pairwise 最小距离）是两船关系量，由 Step 4 的 `PredictedCpaCalculator` 负责，在消费 `CvPredictionResult` 时按需计算。

### 模型说明

CV 模型假设目标船在预测时间窗内保持当前航速和航向不变，沿当前方向做匀速直线外推。

适用性：
- 适用于短期预测（5-10 分钟），在内河直航段精度可接受
- 在弯道航段会偏离实际航道，但作为基线模型是合理的起步
- 后续可升级为 CT（Constant Turn）或基于航道约束的模型

### 做什么

1. **新增 `ShipTrajectoryStore`（历史轨迹存储）**

   - 按 ship ID 维护最近 N 条 `ShipStatus` 的时间序列
   - 采用 `ConcurrentHashMap<String, Deque<ShipStatus>>` 结构
   - 滑动窗口大小可配置（默认保留最近 20 条）
   - 由 `ShipDispatcher` 在 `prepareContext()` 中每次收到消息时写入

   > 本 Step 中 CV 模型仅使用最新状态做外推，不需要历史序列。但 `ShipTrajectoryStore` 是 Step 5B 运动学连续性校验（位置跳变检测）、后续 CT 模型和异常检测的前置基础设施，此处一并建立。

2. **定义 `CvPredictionResult`**

   ```java
   @Data @Builder
   public class CvPredictionResult {
       private String targetId;
       private List<PredictedPoint> trajectory;  // 时间序列预测点
       private Instant predictionTime;            // 预测发起时刻
       private int horizonSeconds;                // 预测时域

       @Data @Builder
       public static class PredictedPoint {
           private double latitude;
           private double longitude;
           private int offsetSeconds;  // 相对于 predictionTime 的偏移
       }
   }
   ```

3. **实现 `CvPredictionEngine.predict()`**

   - 输入：`ShipStatus`（当前位置、航速、航向）
   - 预测参数通过 `TrajectoryPredictionProperties` 配置：
     - `horizonSeconds`：预测时域（默认 600 秒 = 10 分钟）
     - `stepSeconds`：采样步长（默认 30 秒）
   - 外推算法：
     ```
     for t in [stepSeconds, 2*stepSeconds, ..., horizonSeconds]:
         dx = vx * t    // vx = sin(cog) * sog_m/s
         dy = vy * t    // vy = cos(cog) * sog_m/s
         predictedXY = currentXY + (dx, dy)
         predictedLatLon = inverseProject(predictedXY)
     ```
   - 逆投影 `inverseProject` 在 `GeoUtils` 中新增

4. **`GeoUtils` 扩展**

   新增 `fromXY(double x, double y, double refLat)` 方法，将平面坐标反投影为经纬度：
   ```java
   public static double[] fromXY(double x, double y, double refLat) {
       double lat = y / 111320.0;
       double lon = x / (Math.cos(Math.toRadians(refLat)) * 111320.0);
       return new double[]{lat, lon};
   }
   ```

5. **`ShipDispatcher` 联动**

   - 在 `prepareContext()` 中将当前消息写入 `ShipTrajectoryStore`
   - `CvPredictionEngine.consume()` 对每艘目标船执行预测

### 影响范围

- 修改 `CvPredictionResult`：从空类补全字段
- 修改 `CvPredictionEngine`：实现预测逻辑
- 新增 `ShipTrajectoryStore`
- 新增 `TrajectoryPredictionProperties`
- 修改 `GeoUtils`：新增 `fromXY` 方法
- 修改 `ShipDispatcher`：写入历史 + 批量预测

### 协议影响

新增目标船协议字段 `predicted_trajectory`（`target.predicted_trajectory`），包含预测点序列（经纬度 + 时间偏移）。该字段在 Step 6 由 `TargetAssembler` 填充。**现在确认：此字段进入 `RiskObjectDto`，前端可选消费，字段缺失时不影响已有渲染逻辑。**

### 验证方式

- 单元测试：已知航速航向下，预测点序列与手算值一致
- 单元测试：静止船只（sog=0）返回重复当前位置的序列
- 集成验证：前端渲染预测航迹线是否沿目标船当前航向延伸

---

## Step 3：会遇态势识别

### 目标

根据两船的相对方位和航向关系，将会遇态势分为对遇（Head-on）、追越（Overtaking）、交叉（Crossing）三类。为风险评估提供场景维度，并使 LLM 解释能引用具体会遇类型。

> **范围限定**：本轮**只做会遇类型分类（Head-on / Overtaking / Crossing），不做责任船判定**（give-way / stand-on）。责任船判定需要结合具体法规条文和水域类型，属于规则引擎或 RAG 的职责，不在本轮 engine 范围内。

### 分类规则

参考《内河避碰规则》和 IMO COLREGS 的判定逻辑：

| 会遇类型 | 判定条件 | 说明 |
| --- | --- | --- |
| **Head-on（对遇）** | 两船相对方位均在对方正前方 ±15° 范围内，且两船航向差在 170°-190° 之间 | 两船迎面相向行驶 |
| **Overtaking（追越）** | 目标船位于本船正后方 ±67.5° 范围内（从目标船视角看，本船在其尾光弧内） | 后船追上前船 |
| **Crossing（交叉）** | 不满足对遇和追越条件的其余情况 | 两船航线交叉 |

> 角度阈值作为配置项，支持按内河实际场景调优。

### 做什么

1. **新增 `EncounterType` 枚举**

   ```java
   public enum EncounterType {
       HEAD_ON,      // 对遇
       OVERTAKING,   // 追越
       CROSSING,     // 交叉
       UNDEFINED     // 数据不足无法判定
   }
   ```

2. **新增 `EncounterClassifier`**

   ```java
   @Component
   public class EncounterClassifier {
       public EncounterType classify(ShipStatus ownShip, ShipStatus targetShip) {
           // 1. 计算本船到目标船的真方位
           // 2. 计算目标船到本船的真方位
           // 3. 计算两船航向差
           // 4. 按规则判定
       }
   }
   ```

   - 复用 `GeoUtils.trueBearing()` 计算真方位
   - 新增 `GeoUtils.angleDifference(double a, double b)` 计算航向差（归一化到 0-360）

3. **新增 `EncounterClassificationResult`**

   ```java
   @Data @Builder
   public class EncounterClassificationResult {
       private String targetId;
       private EncounterType encounterType;
       private double relativeBearingDeg;   // 目标相对本船方位
       private double courseDifferenceDeg;  // 两船航向差
   }
   ```

4. **集成到 `ShipDispatcher` 管线**

   在 `runDerivations()` 中对每艘目标船调用 `EncounterClassifier`，结果存入 `ShipDerivedOutputs`。

### 影响范围

- 新增 `EncounterType`、`EncounterClassifier`、`EncounterClassificationResult`
- 新增 `EncounterProperties`（角度阈值配置）
- 修改 `GeoUtils`：新增 `angleDifference`
- 修改 `ShipDerivedOutputs`：增加会遇分类结果
- 修改 `ShipDispatcher.runDerivations()`：调用分类器

### 协议影响

新增目标船协议字段 `encounter_type`（`target.risk_assessment.encounter_type`，字符串枚举值：`HEAD_ON`/`OVERTAKING`/`CROSSING`/`UNDEFINED`）。该字段同时进入 `LlmRiskTargetContext`，使 LLM 解释能引用会遇类型。**现在确认：此字段进入 `RiskObjectDto` 和 `LlmRiskContext`，Step 6 由 `TargetAssembler` 和 `LlmRiskContextAssembler` 填充。**

### 验证方式

- 单元测试：典型对遇/追越/交叉场景的航向与方位组合，分类结果正确
- 边界测试：阈值附近的角度应归入正确类别

---

## Step 4：多因子风险评估增强

### 目标

将 `RiskAssessmentEngine` 从"仅 DCPA/TCPA 双阈值"升级为多因子综合评分模型，整合安全领域侵入检测、预测 CPA、会遇类型修正。

### 设计说明

#### 第一阶段：规则化加权评分

不引入贝叶斯或概率图模型。采用可解释的加权评分结构。

风险因子分为三组：

| 因子组 | 来源 | 因子 |
| --- | --- | --- |
| **几何运动因子** | CpaTcpaEngine | DCPA、TCPA、当前距离、闭合速度 |
| **域侵入因子** | ShipDomainEngine | 目标船是否进入/即将进入本船安全领域 |
| **会遇态势因子** | EncounterClassifier | 会遇类型（对遇 > 交叉 > 追越）、相对方位 |

综合评分结构：

```
geometryScore  = f(dcpa, tcpa, currentDistance, closingSpeed)   // 0.0 ~ 1.0
domainScore    = f(domainPenetrationRatio)                      // 0.0 ~ 1.0
encounterScore = f(encounterType)                               // 修正系数

riskScore = (w1 * geometryScore + w2 * domainScore) * encounterModifier
```

- 权重 `w1, w2` 和会遇修正系数通过配置类管理
- `riskLevel` 仍由阈值映射产生，保持四级分类
- 新增 `riskScore`（0.0-1.0 连续值）和 `riskConfidence`（受数据质量影响）

#### 域侵入检测

判断目标船当前位置是否落入本船安全领域椭圆内：

```
在本船坐标系下（以本船位置为原点，heading 为 y 轴正方向）：
将目标船位置旋转到本船 body frame
dx_body, dy_body = rotate(targetPos - ownPos, -heading)

若 dy_body >= 0: 纵向半径 = foreNm; 否则 aftNm
若 dx_body >= 0: 横向半径 = stbdNm; 否则 portNm

penetration = (dx_body / lateralR)^2 + (dy_body / longitudinalR)^2
domainPenetrationRatio = 1.0 - penetration  // > 0 表示已侵入
```

#### 预测 CPA 整合

预测 CPA 是两船关系量，由新增的 `PredictedCpaCalculator` 负责计算：

**输入**：
- 本船当前状态（`ShipStatus`，CV 模型在本层内联外推）
- 目标船 `CvPredictionResult`（Step 2 产出的预测轨迹点序列）

**算法**：遍历预测轨迹的各时间步，用等距投影计算本船外推位置与目标船预测位置之间的距离，取最小值作为 `predictedCpaNm`；最小值对应的时间步即为 `predictedTcpaSec`。

**消费方式**（`geometryScore` 修正）：
- 若 `predictedCpaNm` < 当前 DCPA → 局势恶化，提升 geometryScore
- 若 `predictedCpaNm` > 当前 DCPA → 局势缓解，适度降低 geometryScore
- 若 `CvPredictionResult` 为 null → 无预测修正，geometryScore 仅基于当前 DCPA/TCPA

`PredictedCpaCalculator` 作为独立组件，不修改 `CvPredictionEngine` 接口。

#### 兼容原则（强制）

多因子增强必须满足以下回退约束，否则首版实现风险不可控：

1. **关键输入缺失时强制回退**：当 `ShipDomainResult`、`CvPredictionResult`、`EncounterClassificationResult` 任一为 null 或 `UNDEFINED` 时，对应因子不参与评分，`riskLevel` 必须严格回退到当前 DCPA/TCPA 双阈值逻辑（与现有 `classifyRisk()` 行为完全一致）。
2. **`riskScore` 辅助排序，不替换 `riskLevel` 主判定**：首版中 `riskLevel`（SAFE/CAUTION/WARNING/ALARM）仍由 DCPA/TCPA 阈值映射产生，`riskScore` 作为同级别内的精细排序依据，不直接驱动等级判定。`riskScore` 替换 `riskLevel` 为主判定依据，留到规则化模型验证稳定后再推进。
3. **回归测试覆盖**：需要有测试用例显式验证：在仅提供 DCPA/TCPA（其他输入全为 null）时，新引擎输出的 `riskLevel` 与旧 `classifyRisk()` 完全一致。

#### 后续演进方向（本轮不实施）

当上述规则化模型运行稳定、积累足够样本后，可考虑升级为 **D-S 证据理论（Dempster-Shafer Evidence Theory）** 模型。

D-S 证据理论相比贝叶斯网络更适合本场景：
- 显式表达"不确定性"（既非高风险也非低风险），适合内河数据质量差的环境
- 不需要先验概率，各因子作为独立证据源给出信度函数后合成
- 在中国内河航运安全研究中有成熟应用案例

升级前提：因子体系稳定、质量体系完善、有历史险情数据做参数标定。

### 做什么

1. **扩展 `TargetRiskAssessment`**

   新增字段：
   ```java
   private double riskScore;          // 0.0-1.0 连续风险值
   private double riskConfidence;     // 受数据质量影响的置信度
   private EncounterType encounterType;
   private double domainPenetration;  // 域侵入比（> 0 表示已侵入）
   ```

2. **新增 `RiskScoringProperties`**

   ```java
   @ConfigurationProperties(prefix = "risk.scoring")
   public class RiskScoringProperties {
       private double geometryWeight = 0.6;
       private double domainWeight = 0.4;
       private double headOnModifier = 1.2;
       private double crossingModifier = 1.0;
       private double overtakingModifier = 0.8;
   }
   ```

3. **新增 `DomainPenetrationCalculator`**

   封装椭圆域侵入检测的几何计算。输入：本船位置/航向 + 安全领域 + 目标船位置。输出：penetration ratio。

4. **新增 `PredictedCpaCalculator`**

   计算两船的预测 CPA（pairwise 最小距离）。输入：本船当前 `ShipStatus` + 目标船 `CvPredictionResult`。输出：`predictedCpaNm`、`predictedTcpaSec`。
   - 本船外推轨迹在此组件内联计算（CV 模型，不调用 `CvPredictionEngine`）
   - 若 `CvPredictionResult` 为 null，返回 null（调用方按兼容原则处理）

5. **重构 `RiskAssessmentEngine`**

   - `consume()` 方法签名增加 `Map<String, EncounterClassificationResult> encounterResults`、`Map<String, CvPredictionResult> predictionResults` 参数
   - `buildTargetAssessment()` 内部按兼容原则执行：
     1. 计算 `geometryScore`（DCPA/TCPA；若 predictionResult 存在则叠加预测 CPA 修正）
     2. 计算 `domainScore`（若 domainResult 存在；否则 = 0）
     3. 获取 `encounterModifier`（若 encounterResult 有效；否则 = 1.0）
     4. 综合为 `riskScore`
     5. `riskLevel` 仍由 DCPA/TCPA 阈值映射（`classifyRisk()` 逻辑不变）

6. **`ShipDispatcher` 联动**

   将 `ShipDomainResult`、会遇分类结果、全部 `CvPredictionResult` 传入 `RiskAssessmentEngine`。

### 影响范围

- 修改 `TargetRiskAssessment`：新增字段
- 修改 `RiskAssessmentEngine`：核心评估逻辑重构
- 新增 `RiskScoringProperties`、`DomainPenetrationCalculator`、`PredictedCpaCalculator`
- 修改 `ShipDispatcher`：传递会遇分类结果和预测结果

### 验证方式

- 单元测试：域侵入场景下 riskScore 显著高于非侵入场景
- 单元测试：对遇场景的 riskScore > 追越场景（其余条件相同时）
- **回归测试（兼容原则）**：domain=null、encounter=null、prediction=null 时，新引擎输出的 `riskLevel` 与旧 `classifyRisk()` 在相同 DCPA/TCPA 输入下完全一致
- 单元测试：`PredictedCpaCalculator` 在已知轨迹下输出正确的预测 CPA 值

### 协议影响

新增目标船协议字段：
- `target.risk_assessment.risk_score`（`double`，0.0-1.0）
- `target.risk_assessment.risk_confidence`（`double`，0.0-1.0）

`encounter_type` 字段已在 Step 3 确认，Step 4 中复用。`risk_score` 首版作为辅助字段，前端用于同级别目标排序，不替换 `risk_level` 的警告触发逻辑。**现在确认：以上字段进入 `RiskObjectDto`。**

---

## Step 5A：AIS 协议层质量校验

### 目标

在 `AisMessageMapper` 中增加针对 AIS 协议特征的来源特定校验，产出 `qualityFlags` 和动态 `confidence`，为风险评估的置信度计算提供基础。

> **范围限定**：5A 只处理"看报文本身就能判断的问题"——协议合法性、字段缺失、取值越界，以及来源侧可直接识别的报文新鲜度/重复投递问题。需要上下文（历史状态）才能发现的"物理上不可信"问题（位置跳变、SOG 突变等）由 Step 5B 处理。

### 做什么

1. **`ShipStatus` 新增 `qualityFlags` 字段**

   ```java
   private Set<QualityFlag> qualityFlags;  // null 或空集表示无质量问题
   ```

2. **新增 `QualityFlag` 枚举**（5A + 5B 共用）

   ```java
   public enum QualityFlag {
       // 5A：协议层
       MISSING_HEADING,        // heading 缺失（AIS 511）
       SPEED_OUT_OF_RANGE,     // 航速超出合理范围（内河上限 30 kn）
       POSITION_OUT_OF_RANGE,  // 经纬度超出有效范围
       MISSING_TIMESTAMP,      // 时间戳缺失
       // 5B：运动学层
       POSITION_JUMP,          // 相邻帧位置跳变（超过合理位移）
       SOG_JUMP,               // 航速突变（超过合理加速度）
       COG_JUMP,               // 航向突变
       TIMESTAMP_REGRESSION    // 时间戳早于前一帧（时序倒退）
   }
   ```

3. **增强 `AisMessageMapper`**

   在 `toDomain()` 中增加 5A 校验逻辑，校验结果写入 `qualityFlags` 和 `confidence`：

   | 校验项 | 规则 | qualityFlag | confidence 影响 |
   | --- | --- | --- | --- |
   | Heading 缺失 | heading == 511 或 null | `MISSING_HEADING` | -0.1 |
   | 航速异常 | sog > 30 kn 或 sog < 0 | `SPEED_OUT_OF_RANGE` | -0.3 |
   | 位置越界 | lon 不在 [-180, 180] 或 lat 不在 [-90, 90] | `POSITION_OUT_OF_RANGE` | -0.5 |
   | 时间戳缺失 | msgTime == null | `MISSING_TIMESTAMP` | -0.2 |
   | 来源侧重复报文 | 同一 MMSI 连续收到与当前已接收状态 `msgTime` 相同的报文 | 复用 `MISSING_TIMESTAMP` 或新增 `DUPLICATE_REPORT` | 不提升 confidence；用于后续阻止重复旧包刷新活跃时间 |

   `confidence` 从基础值 1.0 开始按质量问题扣减，clamp 到 [0.0, 1.0]。

4. **`ShipStateStore` 增加重复报文接受策略**

   对同一 MMSI 的相同 `msgTime` 重复报文，按"来源侧重复投递/新鲜度不足"处理，而不是默认视为新的有效状态更新：
   - 不刷新目标的存活时间窗口，避免重复旧包导致 ghost ship 长期留存
   - 不在 Step 5A 中引入运动学推断；这里只定义 store 接受策略
   - 若后续确认某数据源存在"同时间戳但字段仍会修正"的语义，再单独细化覆盖规则

5. **`ShipStateStore.snapshotOf()` 传播 qualityFlags**

### 影响范围

- 修改 `ShipStatus`：新增 `qualityFlags` 字段
- 新增 `QualityFlag` 枚举
- 修改 `AisMessageMapper`：增加 5A 校验逻辑
- 修改 `ShipStateStore`：补充重复报文接受策略，避免相同 `msgTime` 重复刷新活跃时间
- 修改 `ShipStateStore.snapshotOf()`：传播 qualityFlags

### 协议影响

`qualityFlags` 是 `ShipStatus` 内部字段，不直接进入前端协议。其下游影响通过 `tracking_status`（`target`）和 `platform_health.status`（`own_ship`）体现，这两个字段在 Step 6 由 assembler 派生。**不新增协议字段。**

### 验证方式

- 单元测试：各类协议异常（航速越界、位置越界、时间戳缺失）正确标记 qualityFlags 并降低 confidence
- 单元测试：正常 AIS 数据 confidence = 1.0，qualityFlags 为空
- 单元测试：同一 MMSI 的相同 `msgTime` 重复报文不会被视为新鲜更新，不会无限刷新过期窗口

---

## Step 5B：AIS 运动学连续性校验

### 目标

在协议合法性校验（5A）之上，增加基于历史状态的运动学连续性校验，检测"看起来合法但物理上不可信"的数据——内河 AIS 最常见的质量问题恰恰是这类。

### 前置依赖

需要 `ShipTrajectoryStore`（Step 2 建立），用于读取同一目标的前一帧状态。

### 做什么

1. **新增 `ShipKinematicQualityChecker`**

   在 `ShipDispatcher.prepareContext()` 中，将当前消息写入 `ShipTrajectoryStore` 后，立即调用此 checker，对比当前帧与前一帧，检测以下情形：

   | 校验项 | 规则 | qualityFlag | confidence 影响 |
   | --- | --- | --- | --- |
   | 位置跳变 | Δdist / Δt 对应速度 > sog × K（K 可配置，默认 3） | `POSITION_JUMP` | -0.4 |
   | SOG 突变 | \|Δsog\| > sogJumpThreshold（默认 10 kn / 帧） | `SOG_JUMP` | -0.2 |
   | COG 突变 | \|Δcog\| > cogJumpThreshold（默认 90°/帧，对无助推轮船不合理） | `COG_JUMP` | -0.15 |
   | 时间戳倒退 | newMsgTime < prevMsgTime | `TIMESTAMP_REGRESSION` | -0.3 |

   校验结果追加到 `ShipStatus.qualityFlags`（合并 5A 已有 flags），并更新 `confidence`。

   > `ShipKinematicQualityChecker` 不修改已入库的历史状态，只消费 `ShipTrajectoryStore.getLatest()` 做对比。

2. **阈值配置**

   检测参数归入 `AisQualityProperties`（与 5A 参数一起），支持按实际场景调优。

3. **`RiskAssessmentEngine` 消费 confidence**（5A/5B 共同完成后生效）

   ```
   riskConfidence = min(ownShip.confidence, targetShip.confidence)
   ```

   低 confidence 不压低 riskLevel（低质量 ≠ 低风险），降低 riskConfidence 表达"不确定性"。

### 影响范围

- 新增 `ShipKinematicQualityChecker`、`AisQualityProperties`
- 修改 `ShipDispatcher.prepareContext()`：写入历史后调用 kinematic checker
- 修改 `RiskAssessmentEngine`：消费 confidence（可与 Step 4 同步实施）

### 协议影响

同 Step 5A。运动学校验结果通过 `qualityFlags` 传播，不新增协议字段。

### 验证方式

- 单元测试：构造连续两帧位置跳变数据，`POSITION_JUMP` 被标记，confidence 降低
- 单元测试：时间戳倒退帧正确标记 `TIMESTAMP_REGRESSION`
- 单元测试：正常连续帧不产生额外 flags

---

## Step 6：Mock 清理与管线集成

### 目标

消除 assembler 包中全部硬编码默认值，将 Step 1-5 的引擎输出端到端串联到前端协议。

### 做什么

1. **`OwnShipAssembler` 集成 `ShipDomainResult`**

   - 安全领域尺寸（fore/aft/port/stbd）使用 `ShipDomainResult` 实际计算值
   - `shape_type` 由 `ShipDomainResult.shapeType` 驱动
   - `future_trajectory` 字段：若有本船预测数据则填入预测点序列，否则保持 `"linear"` 标记
   - `platform_health.status`：基于 `qualityFlags` 判定（无质量问题 → NORMAL；有 flags → DEGRADED）

2. **`TargetAssembler` 增加会遇类型和预测轨迹**

   - 在 `risk_assessment` 中新增 `encounter_type` 字段
   - 新增 `predicted_trajectory` 字段（来自 `CvPredictionResult`）
   - `tracking_status`：根据 `qualityFlags` 判定（有 STALE_TIMESTAMP → `"stale"`；否则 → `"tracking"`）

3. **`RiskVisualizationAssembler` OZT 扇区角度动态化**

   - 当前硬编码 ±10° → 改为基于安全领域和会遇类型计算
   - 对遇场景使用更宽扇区，追越场景使用窄扇区

4. **`RiskObjectMetaAssembler` 清理**

   - `buildGovernance()`：`mode` 保留配置化；`trust_factor` 偏向数据链路可信度，可引用 `riskConfidence`，但注意 `trust_factor` 是整体态势信任度，不等同于单对目标的 `riskConfidence`——实现时需明确语义，避免直接一对一映射
   - `buildEnvironmentContext()`：`safety_contour_val` 配置化（移入 properties）；`active_alerts` 保留空列表（无外部告警源时）

   > `platform_health.status`（系统/传感器健康状态）与 `trust_factor`（数据置信度）语义不同，不能直接等同。前者偏系统层面，后者偏数据层面。实现时分别处理：`platform_health.status` 基于 `qualityFlags` 中的系统类 flag 推断；`trust_factor` 基于 confidence 均值或 riskConfidence。

5. **`LlmRiskContextAssembler` 增强**

   - 将会遇类型、域侵入比、riskScore 注入 `LlmRiskTargetContext`，使 LLM 解释能引用更丰富的态势信息

6. **`ShipDispatcher` 管线对齐**

   - 确保 `runDerivations()` 产出的所有引擎结果完整传递到 assembler 层
   - `refreshAfterCleanup()` 中的简化路径（当前传 null）改为传递实际计算结果

### 影响范围

- 修改 `OwnShipAssembler`、`TargetAssembler`、`RiskVisualizationAssembler`、`RiskObjectMetaAssembler`
- 修改 `LlmRiskContextAssembler`、`LlmRiskTargetContext`
- 修改 `ShipDispatcher.refreshAfterCleanup()`
- **前端协议新增字段**（已在各 Step 协议影响中确认）：
  - `target.risk_assessment.encounter_type`（Step 3）
  - `target.predicted_trajectory`（Step 2）
  - `target.risk_assessment.risk_score`（Step 4）
  - `target.risk_assessment.risk_confidence`（Step 4）

### 验证方式

- 端到端集成测试：启动模拟器，验证前端接收到的 RISK_UPDATE 事件中安全领域、预测轨迹、会遇类型等字段均为动态计算值
- 回归测试：协议向后兼容，新增字段不影响已有前端功能
- 检查所有 assembler 类中不再包含硬编码业务值

---

## 当前硬编码清单（Step 6 消除目标）

| 文件 | 位置 | 硬编码值 | 替换来源 |
| --- | --- | --- | --- |
| `OwnShipAssembler` | L33-36 | 安全领域尺寸 0.5/0.1/0.2/0.2 nm | `ShipDomainResult` |
| `OwnShipAssembler` | L39 | `shape_type = "ellipse"` | `ShipDomainResult.shapeType` |
| `OwnShipAssembler` | L26 | `platform_health.status = "NORMAL"` | `qualityFlags` 推断 |
| `OwnShipAssembler` | L30 | `prediction_type = "linear"` | `CvPredictionResult` 是否存在 |
| `OwnShipAssembler` | L23 | `rot = 0.0` | 保留（AIS 消息中 ROT 字段当前未接入） |
| `RiskVisualizationAssembler` | L32-33 | OZT 扇区 ±10° | 动态计算 |
| `RiskObjectMetaAssembler` | L37 | `trust_factor = 0.99` | `riskConfidence` |
| `RiskObjectMetaAssembler` | L37 | `mode = "adaptive"` | 配置化 |
| `RiskObjectMetaAssembler` | L43 | `safety_contour_val = 10.0` | 配置化 |
| `TargetAssembler` | L20 | `tracking_status = "tracking"` | `qualityFlags` 推断 |

---

## 后续方向（本轮不实施）

以下能力依赖 Step 1-6 完成后再评估：

- **CT（Constant Turn）航迹预测模型**：在 `ShipTrajectoryStore` 积累足够历史后，利用历史转向率做曲线外推，提升弯道航段预测精度。
- **D-S 证据理论风险融合**：将规则加权评分升级为证据理论框架，更精确地处理不确定性和因子冲突。
- **航道约束建模**：引入电子航道图（S-57/S-101）数据，将航迹预测约束在可航水域内，同时支持弯道、桥区等特殊水域的风险修正。
- **多源数据融合（Fusion 层）**：当接入雷达或计算机视觉等第二输入源时，引入融合层。详见 ARCHITECTURE.md 中的 Fusion 层设计说明。
