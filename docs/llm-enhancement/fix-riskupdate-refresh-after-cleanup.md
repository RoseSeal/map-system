# Fix: Ship cleanup 后重新发布 riskUpdate，同步前端与 LLM 上下文

## Context

`ShipStateStore.purgeExpiredShips()` 移除过期船后，不产生任何下游副作用：
- 前端仍显示已被 store 移除的 ghost ship（SSE 只在 AIS 消息驱动时发布）
- `RiskContextHolder` 保留过期的 LLM 上下文，LLM 基于过时数据回答

前端已使用 **replace 语义**（`useRiskStore.ts:80` 的 `targets: payload.targets` 直接替换），且已有 `droppedTargetNotices` 通知用户船只消失。因此**不需要新增 SSE 事件类型或修改前端**，只需在 cleanup 移除船后重新发布一次 `riskUpdate`。

## Changes

### 1. `ShipStateStore.java` — 返回清理结果

- `purgeExpiredShips`: 返回 `boolean`（是否有船被移除），替代 `void`
- `triggerCleanupIfNeeded`: 返回 `boolean`，透传 `purgeExpiredShips` 结果；未触发清理时返回 `false`

### 2. `ShipDispatcher.java` — 新增 `refreshAfterCleanup()`

新增 package-private 方法，使用当前 store 状态重新构建并发布 risk snapshot：

```
void refreshAfterCleanup() {
    ShipStatus ownShip = shipStateStore.getOwnShip();
    if (ownShip == null) return;

    Collection<ShipStatus> allShips = shipStateStore.getAll().values();
    Map<String, CpaTcpaResult> cpaResults = cpaTcpaBatchCalculator.calculateAll(ownShip, allShips);

    RiskAssessmentResult riskResult = riskAssessmentEngine.consume(
            ownShip, allShips, cpaResults, null, null);

    RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
            ownShip, allShips, cpaResults, riskResult,
            Collections.emptyMap(), null, null);
    if (dto == null) return;

    Map<String, Double> currentDistancesNm = buildCurrentDistancesNm(ownShip, allShips);
    LlmRiskContext llmContext = llmRiskContextAssembler.assemble(
            ownShip, allShips, currentDistancesNm, cpaResults, riskResult);

    riskContextHolder.update(llmContext);
    riskStreamPublisher.publishRiskUpdate(dto);
}
```

关键设计点：
- `shipDomainResult` / `cvPredictionResult` 传 `null`（无新消息，不运行 per-message 引擎）。下游已正确处理 null（正常 dispatch 中这两个值也是条件性 null）
- **不调用** `llmTriggerService.triggerExplanationsIfNeeded()`（cleanup 不是新的风险事件，不应触发 LLM explanation）
- 复用已有的 `buildCurrentDistancesNm()`、各 engine/assembler

### 3. `SseKeepaliveScheduler.java` — 注入 ShipDispatcher，条件性刷新

```java
private final ShipStateStore shipStateStore;
private final ShipDispatcher shipDispatcher;

@Scheduled(initialDelay = 30000L, fixedRate = 30000L)
public void cleanupExpiredShips() {
    if (shipStateStore.triggerCleanupIfNeeded()) {
        shipDispatcher.refreshAfterCleanup();
    }
}
```

### 4. `ShipDispatcher.prepareContext()` — 无需改动

消息驱动路径中，`triggerCleanupIfNeeded()` 返回值可忽略。cleanup 发生后，后续 `getAll()` 已反映最新状态，正常 pipeline 继续执行即可。

## Files to modify

| File | Change |
|---|---|
| `store/ShipStateStore.java` | `purgeExpiredShips` / `triggerCleanupIfNeeded` 返回 `boolean` |
| `pipeline/ShipDispatcher.java` | 新增 `refreshAfterCleanup()` |
| `config/scheduling/SseKeepaliveScheduler.java` | 注入 `ShipDispatcher`，条件调用 refresh |