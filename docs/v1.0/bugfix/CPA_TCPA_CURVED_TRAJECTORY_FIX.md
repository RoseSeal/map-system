# CPA/TCPA Curved Trajectory Rendering Fix

> 文档状态：active
> 最后更新：2026-04-22

## 1. 背景

当前系统同时存在两套与相遇几何相关的输出：

1. 风险判定链路中的线性 `CPA/TCPA`
2. 前端地图上基于 `CvPredictionEngine` 生成的目标船预测轨迹

当目标船存在明显转向时，`CvPredictionEngine` 会输出弧形预测轨迹；但当前 `graphic_cpa_line` 仍按目标船当前 `SOG/COG` 进行线性外推。因此，在地图上可见如下问题：

- `CPA/TCPA` 点位落在船首前方
- `CPA` 连线不位于预测轨迹上
- 预测轨迹与 `CPA/TCPA` 图形表达互相矛盾

该问题首先表现为渲染语义不一致，但其根因位于碰撞几何计算模型不一致。

## 2. 当前根因

当前实现中：

- `risk.engine.trajectoryprediction.CvPredictionEngine` 为目标船输出离散预测轨迹点
- `risk.pipeline.assembler.riskobject.RiskVisualizationAssembler` 生成 `graphic_cpa_line`
- `graphic_cpa_line` 的落点基于 `TargetRiskAssessment.tcpaSeconds` 和当前速度向量直接线性外推

因此，系统出现以下模型分裂：

- 风险判定使用线性 `CPA/TCPA`
- 目标船可视化轨迹使用弧形预测
- 地图上的 `CPA` 图形点位仍使用线性结果

只要目标船发生稳定转向，渲染出的 `CPA` 点位就会偏离预测轨迹。

## 3. 修复目标

本次修复不直接替换现有风险评估所依赖的线性 `CPA/TCPA`，而是在 `engine` 层新增一套基于预测轨迹的并行 `CPA/TCPA` 结果，当前仅用于前端渲染，后续再评估是否纳入风险评分。

目标如下：

1. 保持现有 `RiskAssessmentEngine` 的阈值、评分和解释语义不变
2. 使地图上的 `graphic_cpa_line` 与目标船预测轨迹保持几何一致
3. 将新增能力放入 `engine` 层，而不是放入 assembler 或前端
4. 为后续将预测型 `CPA/TCPA` 接入风险评估预留稳定扩展点

## 4. 方案概述

### 4.1 总体思路

在 `risk.engine.collision` 下新增一套“预测型 `CPA/TCPA`”计算器。该计算器复用已有的目标船预测轨迹，并在同一时间轴上计算本船与目标船的最小会遇距离。

当前阶段采用如下运动学定义：

- 本船：线性外推
- 目标船：使用 `CvPredictionResult` 输出的预测轨迹
- 时间轴：以预测轨迹点的 `offset_seconds` 为采样基准

该结果的语义应明确为：

- `predicted_cpa`
- `predicted_tcpa`
- 或 `trajectory_sampled_cpa/tcpa`

其语义不同于现有线性 `CPA/TCPA`，因此不得覆盖现有 `cpa_metrics` 含义。

### 4.2 为什么不放在前端

该计算不应在前端实现，原因如下：

1. 前端职责应保持为消费协议并渲染，不应复制碰撞几何计算
2. 若前后端各算一套，后续容易再次出现几何漂移
3. 预测轨迹与 `CPA/TCPA` 属于后端态势导出结果，应由后端统一给出

### 4.3 为什么不放在 assembler

assembler 的职责应保持为组装 DTO 和协议字段，不应承担新的碰撞几何求解逻辑。若将“预测型 `CPA/TCPA`”放入 assembler，会造成：

- 组装层越权计算
- 计算语义难以复用
- 后续接入风险评估时需要再次搬迁逻辑

因此，该能力应定义在 `engine` 层，assembler 仅消费其输出。

## 5. 推荐落点

### 5.1 新增计算器

建议新增：

- `backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/collision/PredictedCpaTcpaCalculator.java`

职责：

- 输入：`ShipStatus ownShip`、`CvPredictionResult targetPrediction`
- 输出：预测型 `CPA/TCPA` 结果对象

该类应复用现有 `PredictedCpaCalculator` 的核心思想，但不再返回匿名 `double[]`，而是返回具备明确语义的结果对象。

### 5.2 新增结果对象

建议新增：

- `backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/collision/PredictedCpaTcpaResult.java`

建议字段：

```java
@Data
@Builder
public class PredictedCpaTcpaResult {
    private String targetMmsi;
    private double cpaDistanceMeters;
    private double tcpaSeconds;
    private boolean approaching;
    private double ownCpaLatitude;
    private double ownCpaLongitude;
    private double targetCpaLatitude;
    private double targetCpaLongitude;
}
```

说明：

- `cpaDistanceMeters` / `tcpaSeconds` 表示预测型结果
- `tcpaSeconds` 表示采样时间轴上最小会遇距离对应的时刻
- `approaching` 是后续风险消费者的元数据，当前 `graphic_cpa_line` 仍由线性评估结果的 `approaching` 控制是否输出
- 四个坐标字段直接为前端图形落点服务，避免 assembler 再次计算

### 5.3 Pipeline 扩展

建议扩展 `ShipDerivedOutputs`，增加一份并行预测结果：

- 现有：`cpaResults`
- 新增：`predictedCpaResults`

推荐结构：

```java
record ShipDerivedOutputs(
        ShipDomainResult shipDomainResult,
        Map<String, CvPredictionResult> cvPredictionResults,
        Map<String, CpaTcpaResult> cpaResults,
        Map<String, PredictedCpaTcpaResult> predictedCpaResults,
        Map<String, EncounterClassificationResult> encounterResults
) {
}
```

`ShipDispatcher` 中的处理顺序建议为：

1. 批量生成目标船预测轨迹
2. 计算线性 `CPA/TCPA`
3. 基于预测轨迹计算 `predictedCpaResults`
4. 线性结果继续用于风险评估
5. 预测型结果仅供图形字段组装

该顺序可以保证：

- 当前风险评估逻辑不变
- 新结果自然依赖现有 `cvPredictionResults`
- 后续若需要纳入风险评分，可直接从 `ShipDerivedOutputs` 取得

## 6. 协议与组装策略

当前阶段建议保持以下边界：

- `risk_assessment.cpa_metrics`：继续表示线性 `CPA/TCPA`
- `risk_assessment.graphic_cpa_line`：改为消费预测型 `CPA/TCPA` 的图形落点
- 当预测型结果不可用时：回退到现有线性 `graphic_cpa_line` 计算

即：

1. 不修改 `cpa_metrics` 的既有含义
2. 不让前端根据轨迹重新计算 `CPA`
3. 不让 assembler 根据轨迹求解最小会遇距离
4. assembler 仅把 `PredictedCpaTcpaResult` 映射为协议字段

若需要提高协议可读性，可在后续版本中补充渲染来源标记，例如：

- `graphic_cpa_line.source_model=predicted_trajectory`

当前阶段该字段不是必须项，优先保证最小改动。

## 7. 与现有 `PredictedCpaCalculator` 的关系

当前已有：

- `backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/PredictedCpaCalculator.java`

该类已经具备以下特征：

- 本船按线性外推
- 目标船按预测轨迹采样
- 找到最小距离与对应时间

但该类当前存在两个限制：

1. 所在包为 `risk.engine.risk`，语义上更接近风险评分辅助，而不是碰撞几何主输出
2. 返回值为 `double[]`，不适合作为正式并行结果在 pipeline 中传递

因此建议处理方式为：

1. 将核心算法迁移或抽取到 `risk.engine.collision`
2. 以 `PredictedCpaTcpaResult` 作为正式返回模型
3. 保留 `PredictedCpaCalculator` 仅作为过渡实现，后续再收敛或删除

## 8. 当前阶段为何只用于渲染

当前将预测型 `CPA/TCPA` 仅用于渲染，而不立刻用于风险评估，原因如下：

1. 现有风险阈值是围绕线性 `DCPA/TCPA` 建立的
2. 风险说明与测试基线均基于线性语义
3. 当前预测模型仅升级了目标船，尚未引入本船转向预测
4. 若直接替换主判定，会同时影响阈值、解释与回归结果

因此，本阶段定位应为：

- 先修复几何显示不一致
- 再观察预测型指标稳定性
- 最后再评估是否接入风险评分或替代现有线性指标

## 9. 后续演进路径

推荐演进分三步：

### Step 1：渲染修复

- 新增 `PredictedCpaTcpaCalculator`
- 新增 `PredictedCpaTcpaResult`
- Pipeline 传递并行预测型结果
- `graphic_cpa_line` 使用预测型结果落点
- `RiskAssessmentEngine` 保持不变

### Step 2：辅助风险评估

在不替换主判定的前提下，可将预测型 `CPA/TCPA` 作为辅助因子，用于：

- 风险分数微调
- 解释文本补充
- 异常趋势提示

### Step 3：评估主判定替换

若后续验证表明预测型结果稳定且更符合业务预期，再讨论：

- 是否将 `predicted_cpa/tcpa` 纳入主阈值比较
- 是否引入本船弧形预测
- 是否统一线性与预测型结果的语义体系

## 10. 验收标准

完成 Step 1 后，应满足以下验收条件：

1. 目标船转向时，`graphic_cpa_line` 的目标点位位于预测轨迹上
2. 地图上的 `CPA` 连线不再明显漂移到船首前方
3. `risk_assessment.cpa_metrics` 数值保持现有线性语义
4. 风险等级、告警阈值与解释链路行为不发生非预期变化
5. 前端不新增碰撞几何计算逻辑
6. assembler 不新增新的 `CPA/TCPA` 求解逻辑

## 11. 结论

针对“弧形预测轨迹下 `CPA/TCPA` 图形点位偏离预测轨迹”的问题，推荐方案不是将计算下沉到前端，也不是让 assembler 承担新的几何求解，而是在 `engine` 层新增一套并行的预测型 `CPA/TCPA` 结果。

该方案具有以下优点：

- 分层清晰
- 与现有线性风险评估兼容
- 当前即可修复前端渲染问题
- 后续可平滑演进至风险评估链路

因此，`v1.0` 阶段推荐先按该方案完成 Step 1。
