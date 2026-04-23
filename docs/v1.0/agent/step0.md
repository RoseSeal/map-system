# Step 1：触发策略收敛 + AgentSnapshot 基础设施

> 对应总览 AGENT_LOOP_PLAN.md §4 Step 0
> 文档状态：active
> 最后更新：2026-04-18

---

## 1. 当前状态

**`RiskContextHolder`**（`llm/context/RiskContextHolder.java`）：  
内部 `Snapshot` record 为 package-private，外部只能通过 `getCurrent()` / `getVersion()` / `getUpdatedAt()` 分散读取，无法原子捕获完整快照引用。Agent loop 启动时需要"捕获一次，全程只读"的能力，当前接口不提供。

**`LlmTriggerService`**（`llm/service/LlmTriggerService.java`）：  
触发门控为纯时间窗口 cooldown（`nextAllowedTimeMap<targetId, Instant>`，固定 `cooldownSeconds`）。不区分"同等级重复触发"与"风险等级升级"。Agent loop 开销是当前单次 explanation 的 2–5 倍，继续依赖固定时间窗口会导致：同一等级反复触发（浪费）或风险升级时因 cooldown 未到期而不触发（漏报）。

**Advisory 触发**：不存在。当前无 `SceneRiskStateTracker`、无场景级风险状态追踪、无 advisory 触发入口。

**快照冻结**：`LlmRiskContext` / `LlmRiskOwnShipContext` / `LlmRiskTargetContext` 均为 Lombok `@Data`（可变）。`TargetDerivedSnapshot` 是 record，但其 `CpaTcpaResult`、`EncounterClassificationResult`、`TargetRiskAssessment`、`CvPredictionResult` 均为 Lombok `@Data`（可变）。`DerivedTargetStateStore.getAll()` 返回 `unmodifiableMap`（live view，非副本）。因此，仅捕获引用不构成字段级冻结。

**`LlmProperties`**（`llm/config/LlmProperties.java`）：  
无 advisory 相关配置节。

---

## 2. 目标

本步骤完成 agent loop 的前置基础设施，不实现任何 advisory 生成逻辑。具体：

1. 定义 `AgentSnapshot` 不可变快照 record，作为后续步骤所有工具实现的唯一数据来源
2. 实现深拷贝组件（路径 B），在 DTO 保持 Lombok `@Data` 前提下实现字段级冻结
3. `RiskContextHolder` 新增 `getSnapshot()` 方法，支持原子捕获完整快照
4. 改造 `LlmTriggerService` explanation 触发为语义去重（等级升级优先，cooldown 作为兜底）
5. 新增 `SceneRiskStateTracker`：追踪场景最高风险等级与 advisory 生成状态，检测触发条件
6. 定义 `AdvisoryTriggerPort` 接口（Step 3 / step3.md 实现），本步骤注入 no-op stub

---

## 3. 非目标

| 事项 | 分类 | 后续归属 |
|---|---|---|
| `AgentLoopOrchestrator` 实现 | 延后 | step3.md（Step 3） |
| `chatWithTools` LlmClient 扩展 | 延后 | step1.md（Step 1） |
| Agent 消息模型（AgentMessage sealed） | 延后 | step1.md（Step 1） |
| 工具注册表 + 查询工具实现 | 延后 | step2.md（Step 2） |
| `ADVISORY` SSE 事件类型 + 协议 | 延后 | step3.md（Step 3） |
| 前端 advisory 消费组件 | 延后 | step3.md（Step 3） |
| Chat agent path（selection-constrained） | 延后 | step4.md（Step 4） |
| GraphRAG COLREGS 图谱 | 延后 | step5.md（Step 5） |
| 路径 A（LlmRisk DTO 改造为 record） | 暂不做 | 若路径 B 深拷贝成本显著，v1.0 内重评估；否则延后 v1.1 |

路径 A 暂不进入 `docs/TODO.md`，由本步骤完成后的性能测量结果决定是否回收。

---

## 4. 设计决策

### 4.1 快照冻结：路径 B（深拷贝组件）

总览 §3.2 已决议采用路径 B。`AgentSnapshot` 构建时由两个专用组件承担深拷贝：

- `LlmRiskContextDeepCopier`：拷贝 `LlmRiskContext`（含 `LlmRiskOwnShipContext` + `List<LlmRiskTargetContext>`）
- `TargetStateSnapshotDeepCopier`：拷贝 `Map<String, TargetDerivedSnapshot>` 的 value 层（`CpaTcpaResult`、`EncounterClassificationResult`、`TargetRiskAssessment`、`CvPredictionResult`）

两者均为 Spring `@Component`，注入至 `AgentSnapshotFactory`，后者负责从 `RiskContextHolder` + `DerivedTargetStateStore` 组装 `AgentSnapshot`。工厂方法同步执行，调用方负责决定在何处调用（Step 3 中由 `AdvisoryService` 调用）。

拒绝路径：若 `riskContextHolder.getSnapshot()` 返回 null（系统启动后尚未收到第一帧），`AgentSnapshotFactory.build()` 抛出 `IllegalStateException`，调用方捕获并跳过本次触发。

### 4.2 RiskContextHolder.Snapshot 可见性

当前 `Snapshot` 为 package-private record。将其改为 `public`，同时新增：

```java
public Snapshot getSnapshot() {
    return snapshot;
}
```

调用方通过 `Snapshot` 的 `version()`、`context()` 访问完整状态。`Snapshot` 的不可变性在整体替换层面成立；字段级冻结由 `AgentSnapshot` 构建时的深拷贝保证。

### 4.3 Explanation 触发：语义去重替代纯 cooldown

新增 `ConcurrentHashMap<String, RiskLevel> lastExplainedLevelMap` 作为等级语义 key。触发逻辑变为两层门控（顺序判断）：

1. **等级升级门控**（主门控）：`currentLevel > lastExplainedLevel` → 立即触发，不受 cooldown 约束
2. **同等级 cooldown 门控**（兜底）：等级未升级时，cooldown 作为防重复触发的保护；阈值从当前 5 秒大幅调高（建议 120 秒，由 `llm.cooldownSeconds` 配置）

`lastExplainedLevelMap` 更新时机：explanation 成功生成后，在 `onExplanation` 回调内更新（wrap 回调）。等级下降后，map 中的记录不清除——下次升级时自然触发。`activeTargetIds` 驱逐逻辑同步应用于 `lastExplainedLevelMap`（与现有 `nextAllowedTimeMap` 驱逐保持一致）。

### 4.3A Explanation Prompt 注入触发原因

仅修改 trigger gate 而不修改 explanation prompt，会让模型只能看到当前静态快照，看不到“为什么这次重新生成解释”。为保持文本语义与触发语义一致，`LlmTriggerService` 在选中待解释目标时同步生成内部触发原因，并透传给 `LlmExplanationService`。

当前定义两类触发原因：

1. `LEVEL_UPGRADE`：当前风险等级高于 `lastExplainedLevelMap[targetId]`
2. `COOLDOWN_REFRESH`：等级未升级，仅因 cooldown 兜底重新解释

`LlmExplanationService.buildMessages()` 在目标船事实块中新增 `触发原因` 行：

- 升级触发：`风险等级升级（CAUTION -> WARNING）`
- cooldown 触发：`冷却窗口到期，基于当前态势重新解释`

对应的 system prompt 约束：

- 升级触发时允许点明“风险较上一轮上升，需要重新关注”
- cooldown 触发时禁止臆造“风险刚刚升级”或未提供的历史变化
- 解释文本仍以当前确定性字段为主，触发原因仅作为语气与时序语义的约束，不替代事实依据

### 4.4 SceneRiskStateTracker 并发设计

```java
@Component
public class SceneRiskStateTracker {
    private volatile RiskLevel highestRiskLevel = RiskLevel.SAFE;
    private final AtomicBoolean generatingFlag = new AtomicBoolean(false);
}
```

`highestRiskLevel` 为 `volatile`（单写者：事件监听线程）。`generatingFlag` 为 `AtomicBoolean`（比较-设置，防止并发 advisory 启动）。

`onSceneUpdate(LlmRiskContext context)` 在 `LlmRiskEventListener` 调用链中同步执行，无锁要求。`clearGeneratingFlag()` 由 Step 3 的 `AdvisoryService` 在 advisory 生成结束后（成功或失败）调用。

触发条件（满足任一）：
1. 场景最高风险等级升级（`maxRiskLevelOf(context) > highestRiskLevel`）
2. 存在 approaching 目标且其 `tcpaSec < advisory.tcpaThresholdSeconds`（可配置，默认 300 秒）

`generatingFlag.compareAndSet(false, true)` 成功后才触发；已有 advisory 生成时直接跳过（不排队）。跳过的触发在下一帧自然重评。

### 4.5 AdvisoryTriggerPort：接口 + no-op stub

```java
@FunctionalInterface
public interface AdvisoryTriggerPort {
    void onAdvisoryTrigger(AgentSnapshot snapshot);
}
```

Step 1 中注入 no-op 实现，避免 Spring 上下文在 Step 3 实现到位前启动失败：

```java
@Component
@ConditionalOnMissingBean(AdvisoryTriggerPort.class)
class NoOpAdvisoryTriggerPort implements AdvisoryTriggerPort {
    @Override
    public void onAdvisoryTrigger(AgentSnapshot snapshot) {
        // stub until AdvisoryService is wired in step4
    }
}
```

Step 3 的 `AdvisoryService` 实现 `AdvisoryTriggerPort` 后，`@ConditionalOnMissingBean` 自动跳过 no-op。

### 4.6 LlmProperties 新增配置

在 `LlmProperties` 新增 `Advisory` 嵌套配置类：

```java
private Advisory advisory = new Advisory();

@Data
public static class Advisory {
    private boolean enabled = false;
    private int tcpaThresholdSeconds = 300;
    private int snapshotStalenessThreshold = 5;
}
```

`advisory.enabled = false` 默认关闭，允许独立灰度。`snapshotStalenessThreshold` 供 Step 3 的时效性检查使用（在此定义，Step 3 读取）。

---

## 5. 实现计划

### 阶段 A：新增类型与基础设施（无依赖）

**A-1：定义 `AgentSnapshot` record**

文件：`llm/agent/AgentSnapshot.java`

```java
public record AgentSnapshot(
    long snapshotVersion,
    LlmRiskContext riskContext,
    Map<String, TargetDerivedSnapshot> targetDetails
) {}
```

`targetDetails` 为不可变 map（`Map.copyOf` 或 `Collections.unmodifiableMap`，内部 value 已深拷贝）。

**A-2：实现 `LlmRiskContextDeepCopier`**

文件：`llm/agent/LlmRiskContextDeepCopier.java`

签名：
```java
@Component
public class LlmRiskContextDeepCopier {
    public LlmRiskContext copy(LlmRiskContext source);
}
```

实现：构造新 `LlmRiskContext`（builder），新 `LlmRiskOwnShipContext`（builder 复制所有字段），新 `List<LlmRiskTargetContext>`（每个元素单独 builder 复制）。全量字段复制，不遗漏。

**A-3：实现 `TargetStateSnapshotDeepCopier`**

文件：`llm/agent/TargetStateSnapshotDeepCopier.java`

签名：
```java
@Component
public class TargetStateSnapshotDeepCopier {
    public Map<String, TargetDerivedSnapshot> copyAll(Map<String, TargetDerivedSnapshot> source);
}
```

实现：遍历 source map，对每个 `TargetDerivedSnapshot` 用 builder 深拷贝其内部 `CpaTcpaResult`、`EncounterClassificationResult`、`TargetRiskAssessment`、`CvPredictionResult` 后构造新 record，写入新 `HashMap`，最终返回 `Collections.unmodifiableMap`。

**A-4：实现 `AgentSnapshotFactory`**

文件：`llm/agent/AgentSnapshotFactory.java`

```java
@Component
@RequiredArgsConstructor
public class AgentSnapshotFactory {

    private final RiskContextHolder riskContextHolder;
    private final DerivedTargetStateStore derivedTargetStateStore;
    private final LlmRiskContextDeepCopier contextCopier;
    private final TargetStateSnapshotDeepCopier targetCopier;

    public AgentSnapshot build();
}
```

`build()` 逻辑：
1. 捕获 `riskContextHolder.getSnapshot()`，若 null 抛 `IllegalStateException`
2. `contextCopier.copy(snapshot.context())`
3. `targetCopier.copyAll(derivedTargetStateStore.getAll())`
4. 组装并返回 `new AgentSnapshot(snapshot.version(), frozenCtx, frozenTargets)`

**A-5：定义 `AdvisoryTriggerPort` + no-op stub**

文件：`llm/agent/trigger/AdvisoryTriggerPort.java`、`llm/agent/trigger/NoOpAdvisoryTriggerPort.java`

---

### 阶段 B：修改现有类

**B-1：`RiskContextHolder`**

- 将 `record Snapshot` 改为 `public record Snapshot`
- 新增 `public Snapshot getSnapshot() { return snapshot; }`
- 其余接口不变

**B-2：`LlmProperties`**

新增 `advisory` 嵌套配置节（见 §4.6）。

**B-3：`LlmTriggerService`**

- 新增字段 `ConcurrentHashMap<String, RiskLevel> lastExplainedLevelMap`
- 在 stale 驱逐处（当前第 68 行附近）同步驱逐：`lastExplainedLevelMap.keySet().retainAll(activeTargetIds)`
- 新增私有方法 `isLevelUpgrade(LlmRiskTargetContext target) -> boolean`
- 修改 `triggerExplanationsIfNeeded`：在 `isExplainableTarget` 过滤后，优先检查 `isLevelUpgrade`；升级则跳过 `tryAcquireTrigger`；同等级则仍走 cooldown 门控
- 将 `onExplanation` 回调包装：explanation 返回后调用 `lastExplainedLevelMap.put(targetId, level)`；targetId 和 level 从 `LlmExplanation` 中读取

**B-4：`LlmRiskEventListener`**

注入 `SceneRiskStateTracker`，在 `onRiskAssessmentCompleted` 的 context 组装完成后，调用 `sceneRiskStateTracker.onSceneUpdate(context)`。调用位于 `riskContextHolder.update(...)` 之后、`llmTriggerService.triggerExplanationsIfNeeded(...)` 之前（确保 advisory 触发判断基于最新上下文）。

---

### 阶段 C：实现 `SceneRiskStateTracker`

文件：`llm/agent/trigger/SceneRiskStateTracker.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class SceneRiskStateTracker {

    private final LlmProperties llmProperties;
    private final AgentSnapshotFactory agentSnapshotFactory;
    private final AdvisoryTriggerPort advisoryTriggerPort;

    private volatile RiskLevel highestRiskLevel = RiskLevel.SAFE;
    private final AtomicBoolean generatingFlag = new AtomicBoolean(false);

    public void onSceneUpdate(LlmRiskContext context);
    public void clearGeneratingFlag();
}
```

`onSceneUpdate` 逻辑（伪代码）：

```
if !llmProperties.advisory.enabled → return
newHighest = maxRiskLevelOf(context.targets)
tcpaCrossed = anyApproachingTargetBelowThreshold(context.targets, advisory.tcpaThresholdSeconds)

levelUpgraded = newHighest > highestRiskLevel
highestRiskLevel = max(highestRiskLevel, newHighest)  // 单调追踪

if (levelUpgraded || tcpaCrossed) && generatingFlag.compareAndSet(false, true):
    try:
        snapshot = agentSnapshotFactory.build()
        advisoryTriggerPort.onAdvisoryTrigger(snapshot)
    catch Exception:
        generatingFlag.set(false)
        log.warn(...)
// else: skip, next frame will re-evaluate
```

`clearGeneratingFlag()`：`generatingFlag.set(false)`。Step 3 的 `AdvisoryService` 在 advisory 生成完成（成功或失败）后调用。

TCPA 穿越检测：目前只检测当前帧是否有 approaching 目标低于阈值，不维护"上一帧 TCPA"状态。当场景持续处于 TCPA < 阈值时，`generatingFlag` 防止重复触发（只有 advisory 生成完成并 `clearGeneratingFlag` 后才会再次触发）。这个行为在 Step 3 实际联调前是安全的（此时 `advisoryTriggerPort` 是 no-op）。

---

## 6. 文件改动一览

### 新增

| 文件 | 说明 |
|---|---|
| `llm/agent/AgentSnapshot.java` | 不可变快照 record |
| `llm/agent/LlmRiskContextDeepCopier.java` | LlmRiskContext 深拷贝组件 |
| `llm/agent/TargetStateSnapshotDeepCopier.java` | TargetDerivedSnapshot 深拷贝组件 |
| `llm/agent/AgentSnapshotFactory.java` | 快照构建工厂 |
| `llm/agent/trigger/AdvisoryTriggerPort.java` | 触发回调接口 |
| `llm/agent/trigger/NoOpAdvisoryTriggerPort.java` | no-op stub |
| `llm/agent/trigger/SceneRiskStateTracker.java` | 场景风险状态追踪与触发判断 |

### 修改

| 文件 | 改动摘要 |
|---|---|
| `llm/context/RiskContextHolder.java` | `Snapshot` 改 public；新增 `getSnapshot()` |
| `llm/config/LlmProperties.java` | 新增 `advisory` 配置节 |
| `llm/service/LlmTriggerService.java` | 新增语义去重（`lastExplainedLevelMap` + level upgrade gate） |
| `llm/service/LlmExplanationService.java` | 新增 explanation 内部触发请求类型，并向 prompt 注入 `triggerReason` |
| `llm/context/LlmRiskEventListener.java` | 注入 `SceneRiskStateTracker`，在 update 后调用 `onSceneUpdate` |
| `resources/prompts/system-risk-explanation.txt` | 增加“触发原因”语义约束，禁止 cooldown 场景伪造升级叙述 |

---

## 7. 测试计划

**`LlmRiskContextDeepCopierTest`（新增）**

- 修改 copy 的 `LlmRiskTargetContext` 字段，验证 source 不受影响
- `LlmRiskOwnShipContext` 同上

**`TargetStateSnapshotDeepCopierTest`（新增）**

- 修改 copy 中某个 `TargetDerivedSnapshot` 内部的 `CpaTcpaResult` 字段，验证 source map 中对应 value 不受影响
- 验证返回 map 为不可修改（`UnsupportedOperationException`）

**`LlmTriggerServiceTest`（扩展）**

- 当前等级 = SAFE，跳过（现有测试）
- 当前等级 > lastExplained → 立即触发，不受 cooldown 约束
- 当前等级 = lastExplained，cooldown 未到期 → 不触发
- 当前等级 = lastExplained，cooldown 已到期 → 触发
- explanation 生成后，`lastExplainedLevelMap` 更新；同等级再次调用不触发

**`LlmExplanationServiceTest`（扩展）**

- `LEVEL_UPGRADE` 触发时，用户消息包含 `触发原因: 风险等级升级（X -> Y）`
- `COOLDOWN_REFRESH` 触发时，用户消息包含 `触发原因: 冷却窗口到期，基于当前态势重新解释`
- system prompt 与 user message 的触发原因语义一致，不要求模型自行补全未注入的历史趋势

**`SceneRiskStateTrackerTest`（新增）**

- `advisory.enabled = false` → `onSceneUpdate` 无任何触发
- 初次调用，场景最高风险 = ALARM，`generatingFlag` 为 false → 触发，`generatingFlag` 变 true
- 第二次调用（`generatingFlag` = true）→ 跳过，不调用 port
- `clearGeneratingFlag()` 后，满足条件再次调用 → 触发
- TCPA 穿越触发（approaching 目标 tcpaSec < 阈值）→ 触发
- `agentSnapshotFactory.build()` 抛异常 → `generatingFlag` 复位

---

## 8. 约束与假设

- 所有新增的 `llm/agent/` 包内类不持有 live 数据源引用（`DerivedTargetStateStore` 的引用仅存在于 `AgentSnapshotFactory`，且在 `build()` 调用时同步读取一次后立即深拷贝）
- `SceneRiskStateTracker.onSceneUpdate` 与 `LlmRiskEventListener.onRiskAssessmentCompleted` 同线程调用，无并发写问题；`generatingFlag` 的并发保护针对多帧触发（连续事件）
- 本步骤结束后，系统行为变化对外不可见：advisory path 为 no-op，explanation 触发语义改变（更精准），不引入新的 SSE 事件
- `LlmExplanation` 对象须包含 `targetId` 和 `riskLevel`，供 `lastExplainedLevelMap` 更新使用；若当前 `LlmExplanation` 缺少 `riskLevel` 字段，须在本步骤补充（不引入新的外部依赖，仅添加字段）
