# Step 5C: 风险管线混合增量计算与性能观测执行方案

> 文档状态：就绪
> 对应 ENGINE_ENHANCEMENT_PLAN.md 中新增的 Step 5C
> 前置状态：Step 1–5A 已完成；Step 5B 待完成后再进入具体实现与复核

## 1. 命名与定位

本步骤命名为 **"风险管线混合增量计算与性能观测"**。

命名理由：

- "风险管线"明确改造对象是 `ShipDispatcher` 主链路，而非单一 engine 算法。
- "混合增量"明确不采用严格增量模型（仅重算更新那一条目标船），而采用分支策略：
  - `TARGET_SHIP` 更新时，优先只重算受影响目标的派生结果；
  - `OWN_SHIP` 更新时，回退为全量重算；
  - cleanup / remove / refresh 时，执行全量重建。
- "性能观测"强调本步骤不只是引入缓存，还需先补齐耗时与负载打点，避免在缺乏实测数据的情况下重构执行模型。

## 2. 目标与范围

本步骤旨在降低 `ShipDispatcher` 在目标船更新场景下的重复计算成本，但**不改变**现有风险协议语义和前端消费方式。

核心目标：

1. 为风险管线增加可观测性，区分计算耗时、组装耗时、推送耗时。
2. 在 `TARGET_SHIP` 更新场景中引入目标船增量重算路径，减少对未受影响目标的重复派生计算。
3. 保留 `OWN_SHIP`、cleanup、refresh 场景的全量重算能力，避免缓存漂移和一致性风险。
4. 在不修改 SSE 协议为 delta push 的前提下，以最小侵入方式优化后端计算路径。

**范围限定**：

- 不修改 `/api/v2/risk` 的全量快照协议。
- 不引入前端增量合并逻辑。
- 不在本步骤中重构 `RiskAssessmentEngine` 的风险语义。
- 不将 `ShipDispatcher` 改写为事件溯源、任务队列或批处理架构。

## 3. 为什么单做"严格增量"不合适

### 3.1 `OWN_SHIP` 更新会影响全部目标

多项派生结果以本船为参考系：

- CPA/TCPA：`ownShip` 的运动变化使其对所有目标的相对运动关系全部失效。
- 会遇类型：`EncounterClassifier` 的结果依赖双船相对航向与方位。
- 域侵入：目标是否侵入本船安全领域依赖本船位置、航向与域尺寸。
- Step 4A 风险分：依赖 `cpa + encounter + domain + prediction` 的组合结果。

因此，`OWN_SHIP` 更新不能简化为"只算消息对应那一条船"。

### 3.2 当前输出仍为全量快照

即使 engine 内部改成增量计算，当前下游仍然是：

- 全量 `RiskObjectAssembler` 组装；
- 全量 `RiskObjectDto` 输出；
- 全量 SSE frame 下发。

因此，本步骤的收益上限天然受限于后端派生层，不能夸大为端到端全链路优化。

## 4. 拟采用的策略

采用 **"目标船增量 + 本船全量回退 + 周期/清理全量校准"** 的混合策略。

### 4.1 分支策略

1. `TARGET_SHIP` 消息：
   - 仅重算该目标船相关的派生结果（详见 §5.3）。
   - 复用其他目标的最近派生缓存（`DerivedTargetStateStore`）。
   - 复用 dispatcher 层缓存的 `ShipDomainResult`（本船未变，无需重算）。

2. `OWN_SHIP` 消息：
   - 执行全量重算，更新所有目标的派生缓存与本船域缓存。
   - 原因：本船运动变化使所有 pairwise 关系失效。

3. cleanup / remove / `refreshAfterCleanup()`：
   - 执行全量重建。
   - 清理已移除目标的缓存，防止陈旧数据残留。

### 4.2 可观测性优先

在引入缓存前，先为以下阶段补充打点：

- `prepareContext`
- `runDerivations`
- `assembleRiskObject`
- `publishRiskSnapshot`

建议至少记录：

- 单次 dispatch 总耗时
- derivation 耗时（区分全量与增量）
- assembler 耗时
- 发布耗时
- 当前目标数
- 当前消息角色（`OWN_SHIP` / `TARGET_SHIP`）
- 是否命中增量路径

### 4.3 协议策略

本步骤保持现有 SSE 全量快照协议不变：

- `RiskObjectDto` 仍然输出完整 targets 列表；
- 前端无需感知本次是否为增量计算；
- 性能优化先收敛在后端派生层与 dispatcher 调度层。

## 5. 详细设计与实现步骤

### 5.1 Dispatcher 分支化

在 `ShipDispatcher` 中引入两条计算路径：

- `dispatchFull(ShipDispatchContext context)`：全量重算所有目标，更新全部缓存。
- `dispatchIncrementalForTarget(ShipDispatchContext context)`：仅重算指定目标，复用其余缓存。

判定规则：

| 场景 | 路径 |
|------|------|
| `OWN_SHIP` 消息 | 全量 |
| `TARGET_SHIP` 且本船存在且缓存完整 | 增量 |
| 本船不存在、缓存缺失、cleanup 刚发生 | 回退全量 |

`refreshAfterCleanup()` 应委托给 `dispatchFull()`，不再维护独立的派生逻辑。这是引入分支后的必要收敛，否则两条全量路径长期独立演化会产生行为漂移。

### 5.2 新增派生缓存

#### per-target 派生快照

新增面向目标的派生缓存，聚合为单一记录，而非散落四张 `Map`：

```java
public record TargetDerivedSnapshot(
    String targetId,
    CvPredictionResult predictionResult,
    CpaTcpaResult cpaResult,
    EncounterClassificationResult encounterResult,
    TargetRiskAssessment riskAssessment
) {}
```

由专门的 Store 管理：

```java
public class DerivedTargetStateStore {
    private final ConcurrentHashMap<String, TargetDerivedSnapshot> snapshots = new ConcurrentHashMap<>();

    public void put(String targetId, TargetDerivedSnapshot snapshot) { ... }
    public TargetDerivedSnapshot get(String targetId) { ... }
    public void remove(String targetId) { ... }
    public Map<String, TargetDerivedSnapshot> getAll() { ... }
}
```

`DerivedTargetStateStore` 应使用 `ConcurrentHashMap`，与 `ShipStateStore` 保持一致的线程安全契约，因为 `ShipDispatcher` 可能被多个 MQTT 回调并发调用。

设计原则：

- 缓存粒度为每个目标一份完整派生快照。
- `RiskAssessmentResult` 不作为整体缓存单元；它在组装全量输出时由 per-target 缓存重新拼装。
- 缓存仅保存最近一版派生结果，不做多版本存档。

#### dispatcher 层本船域缓存

`ShipDomainResult` 由 `shipDomainEngine.consume(ownShip)` 纯函数计算，仅依赖 `ownShip` 状态，与任何目标船无关。在 `TARGET_SHIP` 增量路径中，本船未发生变化，`ShipDomainResult` 无需重算，但 `RiskAssessmentEngine.buildTargetAssessment()` 仍需以其作为入参（用于 `DomainPenetrationCalculator`）。

因此，dispatcher 层需维护：

```java
private volatile ShipDomainResult cachedOwnShipDomainResult;
```

该字段在每次全量重算（`OWN_SHIP` 更新或 `refreshAfterCleanup()`）时刷新，增量路径直接复用，无需重新调用 `shipDomainEngine`。

### 5.3 增量路径的计算规则

**前置条件**：Store 更新（`ShipStateStore`、`ShipTrajectoryStore`）已在 `prepareContext()` 中完成，增量路径直接使用 store 中最新状态，不再重复更新。

当收到 `TARGET_SHIP` 消息并决定走增量路径时：

1. 从 `ShipStateStore` 获取最新 `ownShip` 与该目标船状态。
2. 从 dispatcher 缓存获取 `cachedOwnShipDomainResult`（本船域结果，无需重算）。
3. 仅对该目标船重算：
   - `cvPredictionEngine.consume(targetShip, trajectoryHistory)`
   - `cpaTcpaBatchCalculator.calculateOne(ownShip, targetShip)`（或直接调用 `CpaTcpaEngine.calculate()`）
   - `encounterClassifier.classify(ownShip, targetShip)`
   - `riskAssessmentEngine.buildSingleTargetAssessment(targetShip.getId(), cpaResult, ownShip, targetShip, cachedOwnShipDomainResult, predictionResult, encounterResult)`
4. 以新结果覆盖该目标在 `DerivedTargetStateStore` 中的缓存。
5. 基于缓存中所有目标的派生结果重新组装全量 `RiskObjectDto`。

说明：

- 本步骤的增量核心在于减少派生计算，不是减少最终输出内容。
- 若 `CpaTcpaBatchCalculator` 和 `RiskAssessmentEngine` 没有合适的单目标入口，可在其上增加局部方法，避免引入新的抽象层。`buildTargetAssessment` 在 `RiskAssessmentEngine` 中已为 private 方法，改为 package-private 即可在测试与 dispatcher 中复用，无需提升为独立接口。

### 5.4 全量回建与缓存校准

以下情况触发全量回建，并刷新 `cachedOwnShipDomainResult` 与 `DerivedTargetStateStore` 全部条目：

- `OWN_SHIP` 更新（本船变化使所有 pairwise 关系失效）
- `refreshAfterCleanup()` 调用（委托至 `dispatchFull()`）
- 检测到缓存缺失或 target 数量与 store 不一致
- 后续若引入定时校准，可按固定周期强制全量刷新一次

**缓存驱逐时机**：`DerivedTargetStateStore` 的目标条目驱逐应发生在 `prepareContext()` 内，与 `ShipTrajectoryStore.remove()` 并列执行：

```java
Set<String> removedIds = shipStateStore.triggerCleanupIfNeeded();
removedIds.forEach(shipTrajectoryStore::remove);
removedIds.forEach(derivedTargetStateStore::remove);   // ← 新增
```

这确保在分支决策之前，缓存与 store 的状态已同步，全量路径不会从缓存中读取已被驱逐目标的陈旧数据。

## 6. 影响范围评估

预计修改类：

- `ShipDispatcher`（分支化、引入 `cachedOwnShipDomainResult`、委托 `refreshAfterCleanup`）
- `CpaTcpaBatchCalculator` 或直接调用 `CpaTcpaEngine`（补单目标入口）
- `RiskAssessmentEngine`（`buildTargetAssessment` 改为 package-private，或增加 public 单目标入口）
- 新增 `DerivedTargetStateStore`
- 相关测试类

明确不修改：

- 风险协议结构
- 前端 risk SSE 消费逻辑
- Step 4A 风险评分公式
- Step 4B 预测轨迹协议结构

## 7. 实施顺序建议

建议在 **Step 5B 完成后** 再进入本步骤实施，顺序如下：

1. 完成 Step 5B，确保 `qualityFlags/confidence` 在轨迹与状态存储中的流转稳定。
2. 为 dispatcher 与 assembler 增加耗时打点，观察当前真实热点。
3. 落地 dispatcher 分支化：引入 `dispatchFull()` / `dispatchIncrementalForTarget()`，将 `refreshAfterCleanup()` 委托给 `dispatchFull()`。
4. 引入 `DerivedTargetStateStore` 与 `cachedOwnShipDomainResult`，在 `prepareContext()` 中添加缓存驱逐。
5. 实现增量路径，保留全量路径作为兜底。
6. 基于打点结果复核收益，再决定是否继续推进增量组装或协议增量化。

## 8. 测试与验证要求

1. `TARGET_SHIP` 更新仅重算对应目标的派生结果，其他目标直接复用缓存。
2. `OWN_SHIP` 更新时所有目标全量重算，结果与旧全量路径一致，`cachedOwnShipDomainResult` 同步刷新。
3. cleanup 删除目标后，其缓存在 `prepareContext()` 阶段被驱逐，`refreshAfterCleanup()` 能正确重建全量快照。
4. 增量路径与全量路径在相同输入下，对同一目标产出的风险结果一致（`ShipDomainResult` 来源相同）。
5. 新增打点后，可区分 derivation、assembly、publish 三段耗时，便于后续判断瓶颈位置。

## 9. 非目标与后续

本步骤不是终点，后续仍可继续评估：

- 是否需要将 `RiskObjectAssembler` 进一步拆分为"按目标局部组装 + 最终合并"；
- 是否需要将 SSE 从全量快照改为 delta 协议；
- 是否需要加入定时全量校准或版本校验机制；
- 是否需要在调度层为本船更新引入微小的防抖机制（例如仅当本船位移或时间间隔达到特定阈值时，才触发使得所有目标全量重算的操作），以防止本船高频推送信令导致增量计算的性能收益受损。

上述优化均应基于 Step 5C 的观测数据决定，而不是预设为必做项。
