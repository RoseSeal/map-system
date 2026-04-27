# Agent Step 4B：Resolved Risk Explanation Lifecycle

> 文档状态：active
> 最后更新：2026-04-27
> 执行状态：completed
> 所属 track：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md)
> 对应总览：`AGENT_LOOP_PLAN.md` Appendix A.6
> 目标：将已解除风险的 LLM explanation 从立即删除改为限时限量保留，并在前端提供“已解除”状态、自动沉底、透明态、过期清理入口与后续提问上下文保护。

---

## 1. Summary

Step 4B 是 Step 4A 与 Step 5 之间的插入步骤。当前系统在目标恢复 `SAFE` 或不再出现在当前风险快照时，会在后端 `ExplanationCache` 与前端 `useRiskStore.explanationsByTargetId` 中直接移除对应 explanation。该行为让 UI 更干净，但也让用户无法回看“刚才为什么报警”，并且在后续追问时缺少明确的“该风险已解除”标签。

本步骤把 explanation 生命周期拆成两类：

- `ACTIVE`：目标仍在当前快照中，且风险等级为非 `SAFE`
- `RESOLVED`：目标的最后一条 explanation 已不再对应当前活动风险，原因是目标恢复 `SAFE`、目标从快照移除，或前端按最新快照判断其风险已解除

resolved explanation 不是永久缓存。前后端均保留最多 `N = 20` 条 resolved explanation，最长保留 `X = 30` 分钟。驱逐规则一致：先按 `resolved_at` 删除过期条目，再在超出上限时删除最早解除的条目；时间相同则按 `event_id` 稳定排序。

当用户基于 resolved 卡片继续提问时，后端注入两类信息：

1. 原 explanation 的历史内容与解除时间
2. 当前目标状态或缺失状态，并显式标记“该风险已解除”

该规则防止 LLM 把历史 explanation 误读为仍在发生的当前风险。

---

## 2. Current State And Step Delta

### 2.1 Backend Current State

`ExplanationCache` 当前按 `targetId -> CachedEntry(text, timestamp)` 保存最近 explanation，并维护 `trackedTargetIds` 与 `nonSafeTargetIds`。`refreshTargetState(...)` 会删除以下条目：

- 不在当前快照中的目标
- 仍在当前快照中但风险等级已经是 `SAFE` 的目标

`LlmRiskEventListener` 在收到新 explanation 后调用 `explanationCache.shouldAccept(...)`，若目标已不再是当前非 `SAFE` 目标，则丢弃该迟到 explanation。

该行为对避免 stale explanation 下发是正确的，但它没有区分“丢弃迟到结果”和“保留已解除历史”。Step 4B 保留迟到防护，同时把已存在的活动 explanation 迁移到 resolved 区。

### 2.2 Frontend Current State

`useRiskStore.setRiskUpdate(...)` 当前根据最新 `RISK_UPDATE` 构建 `activeRiskTargetIds`，并过滤 `explanationsByTargetId`：

```ts
Object.entries(state.explanationsByTargetId)
  .filter(([targetId]) => activeRiskTargetIds.has(targetId))
```

因此，当目标恢复 `SAFE` 或被移出快照时，前端会直接删除卡片。Step 4B 改为：

- 非 `SAFE` 目标继续保留在活动 explanation map
- 解除风险的目标从活动 map 移到 `resolvedExplanations`
- UI 在同一 explanation 列表内将 resolved 卡片自动排在活动卡片下方，降低透明度并显示 `已解除` 字样

### 2.3 Chat Context Current Gap

当前 chat 上下文只注入当前风险快照和活动 explanation。resolved 卡片不是可引用上下文。如果用户围绕已解除卡片追问，后端只能看到当前风险状态，无法知道用户正在引用哪条历史 explanation，也无法明确告诉 LLM“这条风险已经解除”。

Step 4B 增加 explanation reference，使前端可以在从 resolved 卡片发起提问时把 `target_id` 与 `explanation_event_id` 传给后端。后端再从 `ExplanationCache` 读取 resolved 条目，并合并当前目标状态。

---

## 3. In Scope

### 3.1 Backend

- `ExplanationCache` 增加 `ACTIVE` / `RESOLVED` 生命周期状态
- `ExplanationCache.refreshTargetState(...)` 不再直接删除已存在的解除风险条目，而是迁移到 resolved 区
- 后端 resolved 区保留上限：`resolvedMaxEntries = 20`
- 后端 resolved 区 TTL：`resolvedTtlMinutes = 30`
- 后端提供过期清理接口，返回本次清理的 resolved explanation `event_id` 列表，作为前端主动清理的权威结果
- 新增读取接口，支持按 `targetId + explanationEventId` 获取可注入的 explanation 上下文
- `RiskContextFormatter` 或独立 formatter 在 chat context 中注入 resolved explanation 标签、历史内容和当前状态
- 迟到 explanation 仍必须被拒绝：若 LLM 返回时目标已不是当前非 `SAFE` 目标，不发布新的活动 explanation
- 后端单元测试覆盖迁移、驱逐、迟到拒绝与 resolved chat context 注入

### 3.2 Frontend

- `ExplanationPayload` 的前端存储模型增加 `status = ACTIVE | RESOLVED`
- `useRiskStore` 增加 `resolvedExplanations`
- `setRiskUpdate(...)` 将解除风险的活动 explanation 移入 `resolvedExplanations`
- `resolvedExplanations` 执行同一保留策略：最多 20 条，最长 30 分钟
- `RiskExplanationPanel` 在同一 explanation 列表内渲染活动卡片与 resolved 卡片；resolved 卡片只做自动沉底、透明度降低和 `已解除` 标记
- 前端提供“清理过期风险解释”按钮；按钮不清空全部 resolved 条目，只请求后端清理已过期条目，并按后端返回的 `event_id` 同步删除本地条目
- 从 resolved 卡片发起追问时，`CHAT` payload 携带 explanation reference
- 前端单元测试覆盖状态迁移、排序和驱逐

### 3.3 Documentation And Schema

- 更新 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)：记录 explanation status、resolved retention rule 与 chat explanation reference
- 更新 [`../../../frontend/src/types/schema.d.ts`](../../../frontend/src/types/schema.d.ts)：同步 TypeScript 类型
- 本文作为 Step 4B 的实施边界
- [`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md) Appendix A 记录插入步骤

---

## 4. Out Of Scope

- not doing：为每个 resolved explanation 重新触发 LLM 总结；resolved 卡片复用原 explanation 文本
- not doing：把 resolved explanation 作为新的 SSE 事件类型；状态迁移由最新 `RISK_UPDATE` 与本地 store 推导
- not doing：改变 `EXPLANATION` 的触发策略；Step 0 / Step 3 已定义的语义触发和迟到丢弃规则保持不变
- not doing：显式新增“已解除”区域、独立面板或区域标题；resolved 卡片只在现有 explanation 列表内沉底展示
- not doing：前端本地无通知清空全部 resolved explanation；主动清理只处理已过期条目，并以后端回执为准
- not doing：多用户隔离下的 per-user resolved 清理；当前系统仍按单操作员运行台语义处理
- deferred：若后续引入多客户端会话隔离，resolved 清理应迁移到会话级偏好或用户级状态，触发条件为“会话隔离与鉴权”进入实施链
- deferred：活动 explanation 卡片作为通用对话上下文的完整选择器仍保留在 `docs/TODO.md`；本步骤只覆盖从 resolved 卡片发起追问所需的上下文保护

---

## 5. Lifecycle Contract

### 5.1 Explanation Status

前端持久化模型使用以下状态：

```ts
export type ExplanationLifecycleStatus = 'ACTIVE' | 'RESOLVED';

export interface StoredExplanation extends ExplanationPayload {
  status: ExplanationLifecycleStatus;
  resolved_at?: string;
  resolved_reason?: 'TARGET_SAFE' | 'TARGET_MISSING';
  current_risk_level?: RiskLevel | null;
}
```

后端 `ExplanationCache` 使用同等语义，但不要求直接复用前端类型名：

```java
enum ExplanationLifecycleStatus {
    ACTIVE,
    RESOLVED
}
```

`ACTIVE -> RESOLVED` 的触发条件：

| 条件 | resolved_reason |
|---|---|
| 目标仍在最新快照中，但风险等级为 `SAFE` | `TARGET_SAFE` |
| 目标已不在最新快照中 | `TARGET_MISSING` |

`RESOLVED -> ACTIVE` 不做原地反转。若同一目标再次进入非 `SAFE` 并生成新 explanation，新 explanation 作为新的 `ACTIVE` 条目保存；旧 resolved 条目继续按 N/X 规则保留，直到因 TTL、总量限额或主动清理过期条目而被驱逐。

### 5.2 Retention Rule

默认值：

| 名称 | 默认值 | 说明 |
|---|---:|---|
| `resolvedMaxEntries` | 20 | resolved 区最多保留条目数 |
| `resolvedTtlMinutes` | 30 | resolved 条目最长保留时间 |

统一驱逐顺序：

1. 每次插入、状态迁移、读取前，先删除 `now - resolved_at > resolvedTtlMinutes` 的条目
2. 若剩余条目数仍超过 `resolvedMaxEntries`，按 `resolved_at` 升序删除最早解除的条目
3. 若 `resolved_at` 相同，按 `event_id` 升序稳定删除

前端和后端必须使用相同默认值，并在测试中覆盖默认值。前端不能在 selector 读取路径中触发 Zustand `set(...)`，因此前端自动驱逐只在写路径执行：`setRiskUpdate(...)` 迁移 resolved 条目时，以及接收新 resolved 条目时。前端接受“下一次写操作前，已过 TTL 的 resolved 条目可能仍暂时留在 store 中”的运行态语义；UI 渲染不得在 selector 内尝试清理过期项。

用户点击“清理过期风险解释”时，前端不得自行按本地时钟直接删除。它必须向后端发起清理请求；后端用同一 TTL 规则和服务端时间执行清理，并返回被删除的 `event_id` 列表。前端只删除回执中列出的本地条目，以保持前后端 resolved cache 一致。

若后续将该策略改为后端下发配置，前端常量应退化为 fallback 值，而不是继续作为第二套真值。

### 5.3 Late Explanation Guard

迟到 explanation 不进入 resolved 区。规则如下：

- 若 LLM 在目标解除风险之后返回新 explanation，`LlmRiskEventListener` 仍通过 `explanationCache.shouldAccept(targetId)` 拒绝发布
- 只有已经发布并处于活动区的 explanation，才会在后续风险快照中迁移到 resolved 区

该规则避免“已经解除后才生成的解释”被伪装为历史解释。

---

## 6. Backend Design

### 6.1 `ExplanationCache`

建议将缓存值从 `CachedEntry(text, timestamp)` 扩展为不可变 record：

```java
record CachedExplanationEntry(
        String eventId,
        String targetId,
        RiskLevel riskLevel,
        String provider,
        String text,
        String timestamp,
        ExplanationLifecycleStatus status,
        String resolvedAt,
        ExplanationResolutionReason resolvedReason
) {}
```

内部数据结构：

- active 区继续使用 `ConcurrentHashMap<String, CachedExplanationEntry>`，key 为 `targetId`
- resolved 区使用 `ArrayList<CachedExplanationEntry>` 或 `LinkedList<CachedExplanationEntry>`，并由一个 `ReentrantLock` 保护
- 所有涉及 resolved 区的插入、迁移、排序驱逐和 `findForChatContext(...)` 读取都必须在同一把 lock 内完成

不使用 `CopyOnWriteArrayList`，因为 resolved 区会在风险帧更新时执行插入、过期删除和按时间排序驱逐，写放大会在高频风险更新下产生不必要开销。

核心接口：

```java
public void putActive(ExplanationPayload payload);

public boolean shouldAccept(String targetId);

public void refreshTargetState(
        Set<String> currentTargetIds,
        Set<String> currentNonSafeTargetIds,
        Map<String, RiskLevel> currentRiskLevels,
        Instant now
);

public Optional<CachedExplanationEntry> findForChatContext(String targetId, String eventId);

public List<String> clearExpiredResolvedExplanations(Instant now);
```

`refreshTargetState(...)` 执行三件事：

1. 更新 tracked / non-safe 集合
2. 将不再 non-safe 的 active 条目迁移为 resolved
3. 对 resolved 区执行统一驱逐

`shouldAccept(...)` 只判断当前快照中的非 `SAFE` 状态，不接受 resolved 状态作为新 explanation 的写入依据。

`putActive(...)` 必须在方法内部重复执行当前 tracked / non-safe 集合检查。调用方仍可先调用 `shouldAccept(...)` 做快速拒绝，但 `putActive(...)` 是最终写入防线。若 `shouldAccept(...)` 与 `putActive(...)` 之间恰好发生 `refreshTargetState(...)`，`putActive(...)` 必须拒绝写入，避免已解除目标被永久挂在 active 区。

`findForChatContext(...)` 只查询 resolved 区。若请求引用 active explanation 的 `event_id`，该方法返回 `Optional.empty()`，调用方退化为普通 chat context；后端不依赖前端只从 resolved 卡片发起引用。

`clearExpiredResolvedExplanations(...)` 只删除 TTL 已过期的 resolved 条目，不执行“清空全部”。返回值为本次删除的 `event_id` 列表，供前端精确同步本地 `resolvedExplanations`。总量限额驱逐仍在插入、迁移和普通 prune 流程中执行；手动按钮只负责清理过期项。

### 6.2 `LlmRiskEventListener`

`buildExplanationPayload(...)` 仍负责生成 `event_id`、`risk_object_id`、`target_id`、`risk_level`、`provider`、`text`、`timestamp`。在通过 `shouldAccept(...)` 后，写入方式改为：

```java
explanationCache.putActive(payload);
riskStreamPublisher.publishExplanation(payload);
```

`EXPLANATION` SSE 本身仍表示“新活动 explanation 已生成”。解除状态不通过新的 SSE explanation 事件广播，而由风险快照驱动前端迁移。

### 6.3 Expired Explanation Cleanup

前端主动清理过期 explanation 使用 WebSocket 控制消息，不新增独立 UI 区域：

```ts
export interface ClearExpiredExplanationsPayload {
  event_id: string;
}

export interface ExpiredExplanationsClearedPayload {
  event_id: string;
  reply_to_event_id: string;
  removed_event_ids: string[];
  cutoff_time: string;
  timestamp: string;
}
```

建议新增 `ChatUplinkType = CLEAR_EXPIRED_EXPLANATIONS` 与 `ChatDownlinkType = EXPIRED_EXPLANATIONS_CLEARED`。后端收到请求后调用 `ExplanationCache.clearExpiredResolvedExplanations(now)`，并返回删除列表。前端只按 `removed_event_ids` 更新本地 resolved list；若后端返回空列表，前端不做本地删除。

该路径是前后端缓存一致性的关键：手动清理不能只在前端执行，否则用户继续基于已被前端隐藏但后端仍缓存的 explanation 发起引用时，会出现上下文不一致。

### 6.4 Resolved Chat Context

`CHAT` payload 增加可选字段：

```ts
export interface ExplanationReference {
  target_id: string;
  explanation_event_id: string;
}

export interface ChatRequestPayload {
  conversation_id: string;
  event_id: string;
  content: string;
  agent_mode?: ChatAgentMode;
  selected_target_ids?: string[];
  selected_explanation_refs?: ExplanationReference[];
  edit_last_user_message?: boolean;
}
```

后端在 `LlmChatService` 构建普通 chat prompt 或 agent chat prompt 前，读取 `selected_explanation_refs`：

- 若引用存在且缓存未过期，注入历史 explanation 与状态标签
- 若引用已被驱逐，注入简短说明：“用户引用的历史风险解释已过期，当前仅可依据实时态势回答”
- 若目标当前仍存在，注入当前风险等级、DCPA / TCPA 等现状摘要
- 若目标当前不存在，注入“目标当前不在最新风险快照中”

resolved context 的固定标签必须包含：

```text
【已解除风险解释】该 explanation 对应的风险已解除；不得把它当作当前活动风险。
```

对 agent chat path，该标签放入初始 user context；工具仍可读取最新 `AgentSnapshot`。对普通 chat path，该标签放入 `RiskContextFormatter` 拼接的上下文段落。

---

## 7. Frontend Design

### 7.1 Store Shape

`useRiskStore` 增加：

```ts
resolvedExplanations: StoredExplanation[];
```

`explanationsByTargetId` 也升级为 `Record<string, StoredExplanation>`，但其中条目的 `status` 固定为 `ACTIVE`。`resolvedExplanations` 保存解除风险后的历史条目。这样活动卡片和 resolved 卡片可以复用同一渲染模型，只在排序、视觉权重和操作按钮上分支。

### 7.2 State Transition In `setRiskUpdate`

处理顺序：

1. 从最新 `payload.targets` 计算 active risk target set、tracked target set 和当前风险等级 map
2. 找出 `state.explanationsByTargetId` 中不再属于 active risk set 的条目
3. 将这些条目从 active map 移除，并转成 `RESOLVED`
4. 将 resolved 条目追加到 `resolvedExplanations`
5. 执行 N/X 驱逐

若目标从 `WARNING` 降为 `SAFE`，resolved 卡片保留原 `risk_level = WARNING`，并补充 `current_risk_level = SAFE`。这样 UI 能同时展示“历史风险等级”和“当前已解除”。

### 7.3 Rendering

`RiskExplanationPanel` 展示规则：

1. 活动 explanation 卡片与 resolved explanation 卡片使用同一个列表，不新增“已解除”标题区或独立面板
2. 活动 explanation 卡片按当前风险等级和时间排序
3. resolved 卡片始终排在活动卡片之后，降低透明度，并在卡片内显示 `已解除` 字样
4. resolved 卡片文本仍可展开查看

resolved 卡片交互：

- 显示 `已解除` badge
- 显示解除时间 `resolved_at`
- 显示原风险等级与当前状态
- 提供“追问”入口，同时发送 `selected_explanation_refs` 与同一 `target_id` 的 `selected_target_ids`

列表工具栏提供“清理过期风险解释”按钮。该按钮只触发后端过期清理请求，不新增独立 resolved 区域，不清空未过期的 resolved 卡片。

resolved 追问同时发送 `selected_target_ids` 的原因是：若目标当前仍在快照中，普通 chat path 可以沿用现有选中目标上下文补充当前目标详情；若目标已消失，后端 resolved context 仍会显式注入“目标当前不在最新风险快照中”。`selected_explanation_refs` 是历史 explanation 引用，`selected_target_ids` 是当前态势查询提示，两者语义不同。

---

## 8. File And Module Impact

### 8.1 Backend Modified Files

| 文件 | 改动 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/context/ExplanationCache.java` | 增加 lifecycle status、resolved list、N/X 驱逐与 chat context 查询 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java` | 写入 `putActive(payload)`；保持迟到 explanation 拒绝 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextFormatter.java` | 注入 selected resolved explanation 标签与当前状态 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java` | 从 request 读取 selected explanation refs 并传入 context formatter / prompt builder |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatRequest.java` | 增加 selected explanation refs |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatRequestPayload.java` | 增加 `selected_explanation_refs` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatMessageType.java` | 新增 `CLEAR_EXPIRED_EXPLANATIONS` / `EXPIRED_EXPLANATIONS_CLEARED` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java` | 处理前端清理过期 explanation 请求，并返回后端删除列表 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/chat/ChatAgentPromptBuilder.java` | agent chat 初始消息注入 resolved explanation 标签 |

### 8.2 Backend New Files

| 文件 | 用途 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/context/ExplanationLifecycleStatus.java` | `ACTIVE` / `RESOLVED` 状态枚举 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/context/ExplanationResolutionReason.java` | `TARGET_SAFE` / `TARGET_MISSING` 原因枚举 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/SelectedExplanationRef.java` | chat request 内部引用类型 |

### 8.3 Frontend Modified Files

| 文件 | 改动 |
|---|---|
| `frontend/src/types/schema.d.ts` | 增加 `ExplanationReference` 与 explanation lifecycle 类型 |
| `frontend/src/types/aiCenter.ts` | 存储模型增加 lifecycle 字段 |
| `frontend/src/store/useRiskStore.ts` | 增加 resolved explanations、迁移和驱逐逻辑 |
| `frontend/src/store/useAiCenterStore.ts` | 从 resolved 卡片发起追问时携带 explanation reference |
| `frontend/src/services/chatWsService.ts` | `CHAT` payload 序列化 `selected_explanation_refs`；发送过期 explanation 清理请求并消费回执 |
| `frontend/src/components/Dashboard/RiskExplanationPanel.tsx` | 在同一 explanation 列表中渲染 resolved 卡片的沉底排序、透明态、`已解除` 标记、追问入口和“清理过期风险解释”按钮 |

### 8.4 Documentation Files

| 文件 | 改动 |
|---|---|
| `docs/EVENT_SCHEMA.md` | 记录 resolved explanation lifecycle 与 chat reference |
| `docs/v1.0/agent/AGENT_LOOP_PLAN.md` | Appendix A 记录 Step 4B 插入 |
| `docs/TODO.md` | 移除已挂入 Step 4B 的“已解除风险解释生命周期” backlog |

---

## 9. Implementation Order

### Phase 1：后端 cache 生命周期

- 扩展 `ExplanationCache` entry
- 实现 `ACTIVE -> RESOLVED` 迁移
- 实现 N/X 驱逐
- 实现 `clearExpiredResolvedExplanations(...)`，返回被删除的 `event_id` 列表
- 保持 `shouldAccept(...)` 的非 `SAFE` 防护
- 补齐 `ExplanationCache` 单元测试

### Phase 2：前端 store 生命周期

- 扩展前端存储类型
- `setRiskUpdate(...)` 从删除改为迁移
- 实现 resolved list 驱逐
- 实现按后端 `removed_event_ids` 删除本地 resolved 条目
- 补齐 `useRiskStore` 单元测试

### Phase 3：UI 展示

- `RiskExplanationPanel` 保持单一 explanation 列表，不新增 resolved 专区
- 增加 `已解除` badge、沉底排序和透明度
- 增加“清理过期风险解释”按钮，按钮只清理后端判定过期的 resolved 条目
- 增加 resolved 卡片追问入口

### Phase 4：清理协议与 Chat context 注入

- 扩展 WebSocket `CLEAR_EXPIRED_EXPLANATIONS` / `EXPIRED_EXPLANATIONS_CLEARED`
- 扩展 `CHAT` payload 与后端 DTO
- `LlmChatService` 读取 selected explanation refs
- 普通 chat 与 agent chat 均注入 resolved 标签和当前状态
- 补齐 prompt / formatter 测试

若 Step 4B 先于 Step 4C 实施，`ChatAgentPromptBuilder` 的改动必须只追加 resolved context 参数和拼接逻辑，不改写 provider 选择或 orchestrator 返回值；Step 4C 后续会在 `AgentLoopResult.Completed` 中增加 provider 字段，并调整 `AgentLoopOrchestrator` 的 client 选择。两步在 rebase 时需要保留 Step 4B 的 resolved context 注入，避免被 Step 4C 的 provider routing patch 覆盖。

### Phase 5：文档与协议收口

- 更新 `EVENT_SCHEMA.md`
- 更新 `schema.d.ts`
- 从 `docs/TODO.md` 移除本步骤接管事项

---

## 10. Test Plan

### 10.1 Backend Unit Tests

- active explanation 在目标仍为非 `SAFE` 时保留 active
- active explanation 在目标变为 `SAFE` 时迁移为 resolved，`resolved_reason = TARGET_SAFE`
- active explanation 在目标从快照消失时迁移为 resolved，`resolved_reason = TARGET_MISSING`
- resolved 条目超过 30 分钟后被驱逐
- resolved 条目超过 20 条后按 `resolved_at` 最早优先驱逐
- `clearExpiredResolvedExplanations(now)` 只删除 TTL 过期条目，并返回删除的 `event_id`
- `shouldAccept(targetId)` 对 resolved 目标返回 `false`
- `putActive(payload)` 在 tracked / non-safe 集合已变化时拒绝写入
- LLM 迟到 explanation 不写入 active 或 resolved
- selected resolved explanation 可被 chat context 查询
- active explanation 的 `event_id` 传入 `findForChatContext(...)` 时返回 empty，并退化为普通 chat context
- selected explanation 过期后，chat context 注入“历史解释已过期”提示

### 10.2 Frontend Unit Tests

- `setRiskUpdate` 将降为 `SAFE` 的 active explanation 移到 `resolvedExplanations`
- `setRiskUpdate` 将从快照消失的 active explanation 移到 `resolvedExplanations`
- resolved 卡片保留原始 `risk_level`，并记录 `current_risk_level`
- resolved list 超过 20 条时驱逐最早条目
- resolved list 超过 30 分钟时驱逐过期条目
- resolved list TTL 驱逐只在写路径触发，不在 selector 读取路径调用 `set(...)`
- 点击“清理过期风险解释”后，前端发送后端清理请求
- 前端只删除后端回执 `removed_event_ids` 中列出的 resolved 条目
- 从 resolved 卡片追问时，`CHAT` payload 包含 `selected_explanation_refs`，并同时包含同一目标的 `selected_target_ids`

### 10.3 Manual Validation

1. 启动模拟器，使目标进入 `WARNING` 或 `ALARM` 并生成 explanation
2. 目标恢复 `SAFE` 后，原活动卡片仍留在同一 explanation 列表中，但自动沉到活动卡片之后
3. resolved 卡片降低透明度，并在卡片内显示 `已解除` 字样、原风险等级、解除时间和当前状态
4. 新活动风险卡片始终显示在 resolved 卡片上方
5. 未过 TTL / 未超总量时，resolved 卡片不会被无限保留之外的路径删除
6. 超过 TTL 或超过总量上限后，resolved 卡片被统一驱逐
7. 点击“清理过期风险解释”后，只有后端判定过期的 resolved 卡片从前端消失
8. 从 resolved 卡片发起追问，LLM 回复中不应把该历史 explanation 当作当前活动风险

---

## 11. Constraints And Risks

- **状态来源必须明确**：resolved 状态由最新风险快照推导，不由 LLM 自行判断
- **迟到结果不能进入历史区**：resolved 区只能保存已经发布过的 active explanation，不能接纳解除后才返回的 LLM 结果
- **N/X 规则必须前后端一致**：默认值、驱逐顺序和过期判断都需要测试覆盖
- **前端主动清理以后端为准**：清理过期 explanation 必须由后端返回删除列表，前端不能自行清空全部或按本地时钟删除未确认条目
- **当前状态必须一并注入**：resolved 卡片追问不能只注入历史 explanation，否则 LLM 容易把旧风险当作当前风险
- **风险重新出现不能复用旧状态**：同一目标再次进入风险状态时，应生成新的 active explanation；旧 resolved explanation 不得自动恢复 active

---

## 12. Deviations

本步骤是对 `AGENT_LOOP_PLAN.md` 的插入步骤，不来自原 §4 阶段拆分正文。对应偏离已记录在 `AGENT_LOOP_PLAN.md` Appendix A.6。

版本收敛时，`AGENT_LOOP_PLAN.md` 正文应把 Step 4B 纳入 Step 4A 与 Step 5 之间，并说明 resolved explanation 生命周期、统一 N/X 保留策略、同列表沉底展示规则和 resolved chat context 注入规则。
