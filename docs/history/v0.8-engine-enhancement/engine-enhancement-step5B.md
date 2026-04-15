# Step 5B: AIS 运动学连续性校验执行方案

> 文档状态：就绪
> 对应 ARCHITECTURE.md 与 ENGINE_ENHANCEMENT_PLAN.md 中的 Step 5B

## 1. 目标与范围

本执行方案在协议合法性校验（Step 5A）的基础上，引入基于历史状态的运动学连续性校验。通过前后帧比对，检测"单帧合法但物理上不可信"的异常（如位置跳变、航速突变、航向突变），并将其体现在数据置信度（`confidence`）与质量标志（`qualityFlags`）中。

**范围限定**：本方案（Step 5B）专注于多帧间的运动学逻辑校验，不包含单帧协议本身的校验（属于 5A 范畴），也不涉及复杂的证据理论模型。校验结果仅调整置信度，不干预 `riskLevel` 主判定。

> **关于时序倒退（TIMESTAMP_REGRESSION）**：时序倒退帧已由 `ShipStateStore.update()` 的现有逻辑在上游拦截（`newTime.isBefore(existingTime)` → 返回 false），`ShipDispatcher.prepareContext()` 随之返回 null，此类帧不会进入运动学校验阶段。因此，5B **不需要**处理时序倒退，该类型在 `QualityFlag` 中亦不必声明。

## 2. 详细设计与实现步骤

### 2.1 阈值配置管理

新增 `AisQualityProperties` 配置类，集中管理运动学校验阈值。5A 的协议层扣减量为协议规范常量，以 `private static final` 定义于 `AisMessageMapper` 中，不在此类管理。

```java
@ConfigurationProperties(prefix = "engine.ais-quality")
public class AisQualityProperties {
    private double positionJumpSpeedMultiplier = 3.0; // 位置跳变容忍倍数 (K)
    private double positionJumpMinSpeedKn = 0.5;      // SOG 下限保护值，防止静止船误触发 (kn)
    private double sogJumpThresholdKn = 10.0;         // 航速突变阈值 (kn/帧)
    private double cogJumpThresholdDeg = 90.0;        // 航向突变阈值 (度/帧)，使用圆弧差值
}
```

### 2.2 核心校验组件设计

新增 `ShipKinematicQualityChecker` 组件，封装运动学校验逻辑。该组件接收当前帧与前一帧状态作为输入，返回附加了质量标志和调整后置信度的新 `ShipStatus` 实例（不修改原对象，返回副本）。

**前一帧的获取方式**：使用 `ShipStateStore.get(shipId)` 读取当前存储中该船的最新状态（即前一帧），而非从 `ShipTrajectoryStore` 读取。这一读取操作必须在调用 `shipStateStore.update(currentFrame)` 之前执行，否则 Store 中的状态已被当前帧覆盖。

**无历史数据时的处理**：若 `ShipStateStore.get(shipId)` 返回 null（船舶首次出现），跳过所有运动学校验，直接返回原 `ShipStatus`。

**多维度校验规则**

触发时向当前帧的 `qualityFlags` 中追加对应标志（确保与 5A 产生的标志合并，而非覆盖），同时扣减 `confidence`（结果归一化到 `[0.0, 1.0]`）：

- **位置跳变**：计算两点间地理距离（Δdist）与时间差（Δt）换算出的实际位移速度（`V_actual`）。若 `V_actual > max(current.sog, positionJumpMinSpeedKn) × positionJumpSpeedMultiplier`，标记 `POSITION_JUMP`，扣减 `0.4`。
  - `positionJumpMinSpeedKn`（默认 0.5 kn）防止静止或近静止船因任意微小位移误触发。
  - 时间差 Δt 为 `current.msgTime - previous.msgTime` 的秒数。若 Δt ≤ 0，跳过此项检查。
- **航速突变**：若 `|current.sog - previous.sog| > sogJumpThresholdKn`，标记 `SOG_JUMP`，扣减 `0.2`。
- **航向突变**：计算圆弧差值 `Δcog = min(|current.cog - previous.cog|, 360 - |current.cog - previous.cog|)`，若 `Δcog > cogJumpThresholdDeg`，标记 `COG_JUMP`，扣减 `0.15`。

在 Checker 中定义以下命名常量：

```java
private static final double DEDUCTION_POSITION_JUMP = 0.4;
private static final double DEDUCTION_SOG_JUMP      = 0.2;
private static final double DEDUCTION_COG_JUMP      = 0.15;
```

### 2.3 管线集成

**`ShipDispatcher.prepareContext()` 集成顺序**

正确的调用顺序对置信度能否传递到风险引擎至关重要：

```
1. prevState = shipStateStore.get(message.getId())          // 读取前一帧（Store 尚未更新）
2. qualified = kinematicChecker.check(message, prevState)   // 运动学校验，返回更新了 flags/confidence 的副本
3. shipStateStore.update(qualified)                          // 写入 Store（含 5B 调整后的 confidence）
4. shipTrajectoryStore.append(qualified)                     // 写入轨迹库
5. return new ShipDispatchContext(qualified, ...)            // 向下游传递已完整修饰的状态
```

此顺序确保：5B 调整后的置信度被写入 `ShipStateStore`，下游 `context.allShips()` 通过 `shipStateStore.getAll()` 获取时已携带正确的质量信息。

> **轨迹库纯净性**：严重位置跳变（`POSITION_JUMP`）帧仍会被写入 `ShipTrajectoryStore`（附带 `POSITION_JUMP` 标志和低置信度）。CV 预测引擎（Step 2）应在预测时过滤置信度过低的历史点，保持轨迹预测的平滑性。如需要，可在 `ShipTrajectoryStore.append()` 前增加严重跳变帧的拒绝逻辑，但该优化不属于本步骤范围。

**`ShipTrajectoryStore.snapshotOf()` 补全**

与 `ShipStateStore` 同理，`ShipTrajectoryStore.snapshotOf()` 也需补充 `qualityFlags` 字段的拷贝，确保历史轨迹记录完整保留质量标志。

**`RiskAssessmentEngine` 消费置信度**

`RiskAssessmentEngine` 已实现 `riskConfidence = min(ownShip.confidence, targetShip.confidence)`，并在构建 `TargetRiskAssessment` 时以 null 安全的方式（null 默认视为 1.0）应用。Step 5B 无需修改 `RiskAssessmentEngine`；只需确保管线集成顺序正确（见上），使 5A/5B 联合计算后的 `confidence` 能够从 Store 正确流入引擎。

**`QualityFlag` 枚举补充**

在 Step 5A 建立的 `QualityFlag` 枚举中追加以下条目：

```java
// 5B：运动学层
POSITION_JUMP,   // 相邻帧位置跳变
SOG_JUMP,        // 航速突变
COG_JUMP         // 航向突变
```

## 3. 影响范围评估

- **新增类**：
  - `ShipKinematicQualityChecker`：封装运动学校验核心逻辑。
  - `AisQualityProperties`：承载运动学校验的阈值配置。
- **修改类**：
  - `QualityFlag`：追加 5B 的三个运动学标志。
  - `ShipDispatcher`：在 `prepareContext()` 中按正确顺序集成 kinematic checker。
  - `ShipTrajectoryStore`：补全 `snapshotOf()` 对 `qualityFlags` 的拷贝。
- **不修改**：`RiskAssessmentEngine`（已具备 confidence 消费逻辑）。
- **协议影响**：校验结果作为内部状态流转，不直接增加前端协议字段。

## 4. 测试与验证要求

1. **位置跳变检测**：
   - 构造正常时间间隔但地理距离极大的两个连续报文（换算位移速度远超 SOG × K），验证 `POSITION_JUMP` 标志生成且置信度显著下降。
   - 构造 SOG = 0 的静止船，相邻帧有轻微 GPS 漂移（Δdist 换算速度 < `positionJumpMinSpeedKn × K`），验证不误触发 `POSITION_JUMP`。
2. **运动学突变检测**：
   - 构造 SOG 突变超过阈值的相邻报文，验证 `SOG_JUMP` 标志生成。
   - 构造 COG 跨越 0°/360° 边界但实际差值合理（如 355°→5°，实际差 10°）的报文，验证不误触发 `COG_JUMP`。
   - 构造 COG 实际大幅突变（如 90°→200°）的报文，验证正确触发 `COG_JUMP`。
3. **正常轨迹平滑性**：
   - 构造真实合理的航迹序列，验证不产生任何 5B 质量异常标志，`confidence` 维持原有水平。
4. **管线集成与置信度传递**：
   - 验证 `ShipDispatcher` 调用链中，经 5B 修改后的置信度已写入 `ShipStateStore`，通过 `shipStateStore.getAll()` 能够获取到更新后的值。
   - 验证 `ShipTrajectoryStore.snapshotOf()` 正确保留历史帧的 `qualityFlags`。
