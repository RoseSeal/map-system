# Step 4：交互增强

> 文档状态：active
> 最后更新：2026-04-16
> 对齐文档：[`FRONTEND_ENHANCEMENT_PALN.md`](./FRONTEND_ENHANCEMENT_PALN.md)

## 1. 摘要

Step 4 修补现有交互流的完备性缺口，涵盖三个独立子项：

- **4A**：在 `recording` 状态下提供显式取消录音按钮（纯前端本地，无后端协议变更）
- **4B**：实现"编辑最后一条用户消息并重新发送"能力（前后端联动；后端非破坏式替换最后一轮，前端内联编辑态扩展）
- **4C**：将选中目标的最近 LLM 解释文本注入 Chat 上下文（纯后端；前端无变更）

三个子项无先后强依赖，可独立交付。

---

## 2. 当前状态

### 2.1 4A 取消录音

- `voiceRecorderService.cancelRecording()` 已实现（[voiceRecorderService.ts:108](../../frontend/src/services/voiceRecorderService.ts#L108)）：停止 `MediaRecorder`、释放媒体流、清空缓冲区。
- `useVoiceCapture.ts` 已暴露 `cancelVoiceCapture()` 函数（[useVoiceCapture.ts:83](../../frontend/src/hooks/useVoiceCapture.ts#L83)）：调用上述方法后调用 `resetVoiceCapture()`，状态回退至 `idle`。
- `ChatComposer.tsx` 在 `recording` 状态下（[ChatComposer.tsx:151](../../frontend/src/components/Dashboard/ChatComposer.tsx#L151)）仅渲染"停止并直发"和"停止并预览"两个按钮，无取消入口。
- `ChatComposer` 有 `onStopVoiceRecording` 回调 prop，但无对应的 `onCancelVoiceRecording` prop。

### 2.2 4B 编辑最后一条用户消息

- `ChatMessageList.tsx` 无最后一条 user 消息的"编辑"按钮，无"消息卡片切换为可编辑 textarea"的渲染分支。
- `useAiCenterStore.ts`（[useAiCenterStore.ts:95](../../frontend/src/store/useAiCenterStore.ts#L95)）无最后一条 user 消息编辑态、编辑草稿或编辑提交逻辑。
- `schema.d.ts` 中 `ChatRequestPayload` 无 `edit_last_user_message` 字段。
- 后端 `ChatRequestPayload.java`（[ChatRequestPayload.java](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatRequestPayload.java)）无 `editLastUserMessage` 字段。
- `LlmChatRequest.java`（[LlmChatRequest.java](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatRequest.java)）为 record，无 `editLastUserMessage` 字段。
- `ConversationMemory.java` 提供 `append` / `getHistory` / `clear`，无"成功后替换最后一轮"能力。
- `LlmChatService.java`（[LlmChatService.java](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java)）在 `handleChat` 成功后追加 USER+ASSISTANT，无"编辑确认后替换最后一轮"路径。

### 2.3 4C 解释上下文注入

- `LlmRiskEventListener` 在 `onRiskAssessmentCompleted` 中发布 `EXPLANATION` SSE 事件（[LlmRiskEventListener.java](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java)），但未将解释文本持久化至任何缓存。
- `RiskContextFormatter.formatConsolidated`（[RiskContextFormatter.java:109](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextFormatter.java#L109)）注入选中目标的风险数据时，只读取 `LlmRiskTargetContext.ruleExplanation`（引擎规则文本），不含 LLM 生成的解释文本。
- `LlmChatService.resolveRiskContext`（[LlmChatService.java:135](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java#L135)）只传入当前风险上下文快照，无解释缓存注入。

---

## 3. 范围与非目标

### 本步骤完成

- `recording` 状态下渲染取消录音按钮，点击后调用现有的 `cancelVoiceCapture()`
- 最后一条 user 消息底部渲染"编辑"按钮；点击后该消息卡片切换为可编辑输入框，默认填入原消息内容
- 编辑确认成功：原子替换前端最后一组 USER/ASSISTANT 消息内容
- 编辑确认失败：恢复原最后一轮内容，重新打开编辑框并保留用户草稿，显示错误提示
- `ConversationMemory` 新增"读取最后一轮 / 成功后替换最后一轮"能力（非破坏式路径）
- 按 `targetId` 缓存最近一条 LLM 解释文本；`formatConsolidated` 为选中目标注入解释文本

### 本步骤不做

- 不实现"编辑任意历史消息"（延后：需消息 ID 体系 + 精确历史截断，参见 Step 4B 第二阶段预留说明）
- 不实现 `transcribing` 阶段的取消（不做：需 `CANCEL` 上行协议 + 后端转录任务注册表，已记录于 `docs/TODO.md §4`）
- 不实现真正取消 LLM 回复（不做：同上，见 `docs/TODO.md §4`）
- `4C` 无前端变更：不新增 UI 元素，不修改 `selectTarget` 协议

---

## 4. 子项设计

### 4A. 取消录音

#### 范围约束

仅覆盖 `recording → idle` 的本地取消路径。音频尚未发送到后端，不产生 `eventId`，不写入 `pendingChatEventIds`。

#### 实施变更

**`ChatComposer.tsx`**：

在 `ChatComposerProps` 中新增：

```ts
onCancelVoiceRecording?: () => void;
```

在 `recording` 状态的控件区（现有"停止并直发"/"停止并预览"按钮所在行），新增"取消录音"按钮：

```tsx
<button
  type="button"
  onMouseDown={handleMouseDown}
  onClick={onCancelVoiceRecording}
  className="px-2.5 py-1.5 rounded-md border border-slate-300 dark:border-white/10 bg-slate-100 dark:bg-slate-800/60 text-[11px] font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-700/60 transition-colors"
>
  取消
</button>
```

按钮紧跟"停止并预览"之后，视觉样式保持中性（不使用警告色，区别于停止按钮的绿/黄色调）。

**`RiskExplanationPanel.tsx`（`ChatComposer` 的调用处，[RiskExplanationPanel.tsx:314](../../frontend/src/components/Dashboard/RiskExplanationPanel.tsx#L314)）**：

将 `useVoiceCapture()` 已暴露的 `cancelVoiceCapture` 传入 `onCancelVoiceRecording` prop。该函数已在 `useVoiceCapture.ts:83` 中实现，无需任何 store 或 service 层改动。

#### 不改动

- `useVoiceCapture.ts`：无需修改，`cancelVoiceCapture` 已存在
- `useAiCenterStore.ts`：无需修改，`resetVoiceCapture` 已存在
- `voiceRecorderService.ts`：无需修改，`cancelRecording` 已存在

---

### 4B. 编辑最后一条用户消息

#### 4B.1 协议扩展（上行 CHAT payload）

**`schema.d.ts`**（前端类型）：

```ts
interface ChatRequestPayload {
  conversation_id: string;
  event_id: string;
  content: string;
  selected_target_ids?: string[];
  edit_last_user_message?: boolean;  // 新增：true 表示编辑后替换最后一轮
}
```

**`ChatRequestPayload.java`**（后端）：

```java
@JsonProperty("edit_last_user_message")
private Boolean editLastUserMessage;
```

`null` 与 `false` 等效，均走普通追加路径。

#### 4B.2 前端实现

**`useAiCenterStore.ts`**：

state 新增字段：

```ts
editingMessageEventId: string | null;        // 当前进入编辑态的最后一条 user 消息 event_id
editingDraft: string;                        // 用户正在编辑的草稿
editingBaselineUserContent: string | null;   // 原 user 消息内容
editingSubmitEventId: string | null;         // 编辑确认后发起的 CHAT event_id
editingSubmitError: string | null;           // 编辑提交失败提示
```

`initialState()` 中初始化为 `null`。`resetConversation` 和 `reset` 中清零。

新增 actions：

```ts
startEditingLastUserMessage: () => boolean;
updateEditingDraft: (value: string) => void;
cancelEditingLastUserMessage: () => void;
confirmEditingLastUserMessage: () => boolean;
clearEditingSubmitError: () => void;
```

`startEditingLastUserMessage()`：
1. 找到 `chatMessages` 中最后一条 `role === 'user'` 且 `request_type === 'CHAT'` 的消息
2. 校验该消息属于最后一组完整 USER/ASSISTANT，且当前无 pending 请求
3. 设置 `editingMessageEventId = message.event_id`
4. 设置 `editingDraft = message.content`
5. 设置 `editingBaselineUserContent = message.content`
6. 清空 `editingSubmitError`

说明：从 UI 语义上，若不存在最后一组完整 USER/ASSISTANT，用户本就无法点击“最后一条消息的编辑”入口；这里仍保留该检查作为防御性约束，避免迟到事件、异常状态恢复或后续重构导致 store 在不一致状态下误进入编辑流。

`confirmEditingLastUserMessage()`：
1. 校验 `editingDraft.trim()` 非空，且当前无 pending 请求
2. 向 WS 发送 `{ content: editingDraft.trim(), edit_last_user_message: true, ... }`
3. 记录 `editingSubmitEventId = newEventId`
4. 写入 `pendingChatEventIds[newEventId] = true`
5. 保留旧的最后一组 USER/ASSISTANT 在列表中，等待成功后整体替换
6. 清空 `editingSubmitError`

`appendChatReply` 新增分支：若 `payload.reply_to_event_id === editingSubmitEventId`，执行替换逻辑而非追加：
- 找到最后一条 `role === 'user'` 的消息：更新 `content = editingDraft.trim()`、`status = 'replied'`
- 找到最后一条 `role === 'assistant'` 的消息：更新 `content`、`event_id`（使用 reply 的 `event_id`）、`provider`、`timestamp`
- 清空 `editingMessageEventId`、`editingDraft`、`editingBaselineUserContent`、`editingSubmitEventId`
- `pendingChatEventIds[reply.reply_to_event_id] = false`

`appendChatError` 新增分支：若 `payload.reply_to_event_id === editingSubmitEventId`，执行恢复逻辑：
- `chatMessages` 保持原最后一组 USER/ASSISTANT 不变
- 将 `editingMessageEventId` 恢复为最后一条 user 消息的 `event_id`
- 保留 `editingDraft` 为本次提交的草稿，允许用户继续修改
- 清空 `editingSubmitEventId`
- `pendingChatEventIds[eventId] = false`
- 在 store 中记录 `editingSubmitError: string | null`

新增 action：

```ts
clearEditingSubmitError: () => set({ editingSubmitError: null }),
```

新增 selector：

```ts
export const selectEditingSubmitError = (state: AiCenterState) => state.editingSubmitError;
```

**`ChatMessageList.tsx`**（[ChatMessageList.tsx:9](../../frontend/src/components/Dashboard/ChatMessageList.tsx#L9)，渲染消息列表的组件）：

新增 props：

```ts
editingMessageEventId?: string | null;
editingDraft?: string;
editingSubmitPending?: boolean;
editingSubmitError?: string | null;
onStartEditingLastUserMessage?: () => void;
onUpdateEditingDraft?: (value: string) => void;
onConfirmEditingLastUserMessage?: () => void;
onCancelEditingLastUserMessage?: () => void;
onClearEditingSubmitError?: () => void;
```

- 在最后一条 `role === 'user'` 且 `request_type === 'CHAT'` 的消息底部渲染"编辑"按钮
- 显示条件：当前会话无 pending 请求、当前不处于编辑态、该消息属于最后一组完整 USER/ASSISTANT
- 点击"编辑"后，该条 user 消息卡片切换为 textarea，初始值为原消息内容
- textarea 下方渲染"确认"和"取消"按钮
- 点击"确认"调用 `onConfirmEditingLastUserMessage()`
- 点击"取消"调用 `onCancelEditingLastUserMessage()`，退出编辑态并恢复原消息卡片
- 当 `editingSubmitPending === true` 时，禁用"编辑"/"确认"/"取消"按钮
- 当 `editingSubmitError` 不为 null 时，在编辑框下方渲染单行错误提示，点击关闭按钮后调用 `onClearEditingSubmitError`

**`RiskExplanationPanel.tsx`（消费侧）**：
- 从 store 读取 `editingMessageEventId`、`editingDraft`、`editingSubmitEventId`、`editingSubmitError`
- 传入 `onStartEditingLastUserMessage`
- 传入 `onUpdateEditingDraft`
- 传入 `onConfirmEditingLastUserMessage`
- 传入 `onCancelEditingLastUserMessage`
- 传入 `onClearEditingSubmitError`

**`chatWsService.ts`**：

`send('CHAT', payload)` 中，payload 透传 `edit_last_user_message` 字段，无需额外处理。

#### 4B.3 后端实现

**`LlmChatRequest.java`**：

将 record 扩展为：

```java
public record LlmChatRequest(
    String conversationId,
    String eventId,
    String content,
    List<String> selectedTargetIds,
    boolean editLastUserMessage
) { ... }
```

**`ChatWebSocketHandler.java`**：

在 `handleChat` 中构造 `LlmChatRequest` 时，透传 `payload.getEditLastUserMessage() != null && payload.getEditLastUserMessage()`。

**`ConversationMemory.java`**：

在 `ConversationEntry` 中新增：

```java
/**
 * 返回最后一组 USER/ASSISTANT pair（只读副本）。
 * 若消息数不足 2 条，返回 null。
 */
synchronized List<LlmChatMessage> peekLastTurn() {
    if (messages.size() < 2) {
        return null;
    }
    List<LlmChatMessage> list = List.copyOf(messages);
    return list.subList(list.size() - 2, list.size());
}

/**
 * 用新的 USER/ASSISTANT pair 原子替换现有最后一组。
 * 若消息数不足 2 条（无完整轮次可替换），返回 false 并不修改状态。
 */
synchronized boolean replaceLastTurn(LlmChatMessage newUser, LlmChatMessage newAssistant) {
    if (messages.size() < 2) {
        return false;
    }
    // 移除最后两条（原 USER+ASSISTANT）
    List<LlmChatMessage> mutable = new java.util.ArrayList<>(messages);
    mutable.remove(mutable.size() - 1);
    mutable.remove(mutable.size() - 1);
    mutable.add(newUser);
    mutable.add(newAssistant);
    messages.clear();
    messages.addAll(mutable);
    lastAccessTimeMillis = System.currentTimeMillis();
    return true;
}
```

在 `ConversationMemory` 公共 API 中暴露对应方法，委托给 `ConversationEntry`。

**`LlmChatService.java`**：

在 `handleChat` 的 `whenComplete` 回调中，增加对 `request.editLastUserMessage()` 的分支：

```java
if (throwable == null) {
    if (request.editLastUserMessage()) {
        // 非破坏式替换语义：先生成，成功后再替换最后一组 USER/ASSISTANT
        boolean replaced = conversationMemory.replaceLastTurn(
            new LlmChatMessage(ChatRole.USER, request.content()),
            new LlmChatMessage(ChatRole.ASSISTANT, responseText)
        );
        if (!replaced) {
            // 无可替换轮次时降级为追加（容错路径）
            conversationMemory.append(conversationId, new LlmChatMessage(ChatRole.USER, request.content()));
            conversationMemory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, responseText));
        }
    } else {
        conversationMemory.append(conversationId, new LlmChatMessage(ChatRole.USER, request.content()));
        conversationMemory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, responseText));
    }
    onSuccess.accept(new ChatReplyResult(responseText, resolveProviderName()));
    return;
}
```

失败时行为不变：旧轮次保留，`onError` 照常回调。前端负责恢复编辑态和草稿。

**降级容错规则**：`replaceLastTurn` 返回 `false` 表示 `ConversationMemory` 中无完整轮次（如会话刚创建或历史被清空）；此时降级为普通追加，不抛出异常，日志记录 warn。

**第二阶段预留**：本版本 `edit_last_user_message: true` 语义固定为"编辑最后一条 user 消息并替换最后一轮"。若后续需支持编辑任意历史轮次，引入稳定 `message_id` 并扩展目标消息字段，不在单一布尔值上叠加新语义。

---

### 4C. 解释上下文注入

#### 无前端变更

`selected_target_ids` 协议保持不变；`selectTarget` 交互不变；用户操作路径不变。

#### 4C.1 ExplanationCache

**生命周期设计**：

与前端 `explanationsByTargetId` 保持严格对齐。前端的驱逐逻辑（[useRiskStore.ts:70](../../frontend/src/store/useRiskStore.ts#L70)）：每次 `RISK_UPDATE` 到达时，对 `explanationsByTargetId` 做 filter，只保留仍在追踪列表中的 targetId。后端缓存采用相同逻辑：每次 `RiskAssessmentCompletedEvent` 到达时，驱逐不再出现在当前追踪集合中的条目。

解释进入缓存的时刻：通过统一校验后，与 `EXPLANATION` 事件发布同步写入缓存（upsert，覆盖旧值）。  
解释离开缓存的时刻：目标从追踪列表消失，或目标当前风险等级降至 SAFE。  
目标降至 SAFE 时：不再保留缓存条目；这样前端展示、SSE 发布和 Chat 注入三条路径语义一致，系统中不存在“前端不展示但后端仍缓存可注入”的分叉状态。

不引入基于版本号或时间的阈值：解释的生命与目标的生命绑定，不额外缩短。

**统一发布校验规则**：

解释生成是异步的。若某目标已在较新的 `RiskAssessmentCompletedEvent` 中从追踪列表消失，或当前风险等级已降至 SAFE，先前异步任务返回的旧解释不得继续影响系统行为，否则会出现“前后端展示与上下文注入语义不一致”的问题。

因此 v0.9 改为在新解释抵达时做统一校验，校验通过后才允许：

- 发布 `EXPLANATION` SSE 事件
- 写入 `ExplanationCache`

统一校验条件：

- `targetId` 当前仍存在于追踪集合中
- `targetId` 当前风险等级仍非 SAFE

若任一条件不满足，则该解释整体丢弃：

- 不发布 SSE
- 不写入缓存
- 记录 debug/warn 日志

该规则同时覆盖“目标彻底消失”“目标降为 SAFE”“目标短暂消失后同 ID 重新出现前，旧解释晚到”三类场景。

新建 `backend/.../llm/context/ExplanationCache.java`：

```java
@Component
public class ExplanationCache {

    private record CachedEntry(String text, String timestamp) {}

    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Set<String>> trackedTargetIds = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<String>> nonSafeTargetIds = new AtomicReference<>(Set.of());

    /**
     * 缓存或更新解释文本。timestamp 取自 ExplanationPayload.timestamp，用于向 LLM 传递解释生成时间。
     * 只有目标当前仍被追踪且风险等级仍非 SAFE 时才允许写入。
     */
    public void put(String targetId, String text, String timestamp) {
        if (!StringUtils.hasText(targetId) || !StringUtils.hasText(text)) {
            return;
        }
        Set<String> currentTracked = trackedTargetIds.get();
        Set<String> currentNonSafe = nonSafeTargetIds.get();
        if (!currentTracked.contains(targetId) || !currentNonSafe.contains(targetId)) {
            return;
        }
        cache.put(targetId, new CachedEntry(text, timestamp));
    }

    /** 返回解释文本。无缓存条目时返回 null。 */
    public String getText(String targetId) {
        CachedEntry entry = cache.get(targetId);
        return entry == null ? null : entry.text();
    }

    public String getTimestamp(String targetId) {
        CachedEntry entry = cache.get(targetId);
        return entry == null ? null : entry.timestamp();
    }

    /**
     * 刷新当前追踪集合与非 SAFE 目标集合，并驱逐不满足条件的所有缓存条目。
     * 应在每次 RiskAssessmentCompletedEvent 处理完成后调用。
     */
    public void refreshTargetState(Set<String> currentTargetIds, Set<String> currentNonSafeTargetIds) {
        Set<String> tracked = currentTargetIds == null ? Set.of() : Set.copyOf(currentTargetIds);
        Set<String> nonSafe = currentNonSafeTargetIds == null ? Set.of() : Set.copyOf(currentNonSafeTargetIds);
        trackedTargetIds.set(tracked);
        nonSafeTargetIds.set(nonSafe);
        cache.keySet().removeIf(targetId -> !tracked.contains(targetId) || !nonSafe.contains(targetId));
    }

    public boolean shouldAccept(String targetId) {
        if (!StringUtils.hasText(targetId)) {
            return false;
        }
        return trackedTargetIds.get().contains(targetId) && nonSafeTargetIds.get().contains(targetId);
    }
}
```

#### 4C.2 LlmRiskEventListener 改动

注入 `ExplanationCache`，在 `onRiskAssessmentCompleted` 中做两件事：

**1. 刷新当前追踪状态并驱逐不满足条件的目标（在 `riskContextHolder.update` 之后立即执行）**：

```java
private final ExplanationCache explanationCache;

// riskContextHolder.update(event.snapshotVersion(), context) 之后：
Set<String> currentTargetIds = buildCurrentTargetIds(event);
Set<String> currentNonSafeTargetIds = buildCurrentNonSafeTargetIds(context);
explanationCache.refreshTargetState(currentTargetIds, currentNonSafeTargetIds);
```

```java
private Set<String> buildCurrentTargetIds(RiskAssessmentCompletedEvent event) {
    if (event.allShips() == null || event.ownShip() == null) {
        return Set.of();
    }
    String ownId = event.ownShip().getId();
    return event.allShips().stream()
        .filter(ship -> ship != null && ship.getId() != null && !ship.getId().equals(ownId))
        .map(ShipStatus::getId)
        .collect(java.util.stream.Collectors.toSet());
}
```

```java
private Set<String> buildCurrentNonSafeTargetIds(LlmRiskContext context) {
    if (context == null || context.getTargets() == null) {
        return Set.of();
    }
    return context.getTargets().stream()
        .filter(target -> target != null
            && target.getRiskLevel() != null
            && target.getRiskLevel() != RiskLevel.SAFE
            && StringUtils.hasText(target.getTargetId()))
        .map(LlmRiskTargetContext::getTargetId)
        .collect(java.util.stream.Collectors.toSet());
}
```

**2. 新解释抵达时统一校验，通过后才同时发布 SSE 并更新缓存**：

```java
// 在 buildExplanationPayload 后：
if (!explanationCache.shouldAccept(explanation.getTargetId())) {
    log.debug("Drop stale explanation for targetId={}", explanation.getTargetId());
    return;
}
explanationCache.put(explanation.getTargetId(), explanation.getText(), payload.getTimestamp());
riskStreamPublisher.publishExplanation(payload);
```

**调用顺序说明**：`refreshTargetState(...)` 在每次风险评估事件中都执行（与 `riskContextHolder.update` 绑定）；解释生成是异步的（由 `LlmTriggerService` 在 executor 上执行），因此晚到解释是客观存在的。v0.9 通过“新解释抵达时统一校验，只有目标仍被追踪且仍非 SAFE 时才同时发布 SSE 并写缓存”的规则消除该竞争。也就是说：

- 目标已消失：晚到解释被整体拒收，不发布 SSE，也不写缓存
- 目标已降为 SAFE：晚到解释被整体拒收，不发布 SSE，也不写缓存
- 目标仍在追踪且仍非 SAFE：解释允许发布并写入或覆盖旧值
- 若目标未来以相同 `targetId` 重新出现，则只有重新出现后的新解释才应进入系统；旧解释因返回时已不满足校验条件而被丢弃

#### 4C.3 RiskContextFormatter 改动

`formatConsolidated` 签名扩展（原有三参数重载保持不变，新增含 `ExplanationCache` 的版本）：

```java
public String formatConsolidated(
    LlmRiskContext context,
    List<String> selectedTargetIds,
    Instant updatedAt,
    ExplanationCache explanationCache   // nullable，null 时行为与原方法相同
)
```

在 `appendTargetDetail` 调用之后（仅针对选中目标），追加解释文本：

```java
if (explanationCache != null && target.getRiskLevel() != RiskLevel.SAFE) {
    String explanation = explanationCache.getText(target.getTargetId());
    if (StringUtils.hasText(explanation)) {
        String ts = explanationCache.getTimestamp(target.getTargetId());
        builder.append("（AI 解释，生成于 ").append(ts != null ? ts : "未知").append("）：")
               .append(explanation).append('\n');
    }
}
```

**SAFE 过滤**：目标当前风险等级为 SAFE 时不注入解释。v0.9 同时在解释抵达时就阻断 SAFE 目标的 SSE 发布和缓存写入；这里保留 SAFE 判断作为格式化层的防御性兜底，避免未来调用方绕过 `LlmRiskEventListener` 时出现不一致。

不修改 `appendTarget`（摘要视图中的目标不注入解释，避免上下文膨胀）。

原有三参数版本委托至新版本，`explanationCache = null`，行为不变。

#### 4C.4 LlmChatService 改动

注入 `ExplanationCache`：

```java
private final ExplanationCache explanationCache;
```

`resolveRiskContext` 直接传入 `explanationCache`，无需读取版本号：

```java
private String resolveRiskContext(LlmChatRequest request) {
    var context = riskContextHolder.getCurrent();
    var updatedAt = riskContextHolder.getUpdatedAt();
    return riskContextFormatter.formatConsolidated(
        context, request.selectedTargetIds(), updatedAt, explanationCache
    );
}
```

---

## 5. 影响文件

### 前端

| 文件 | 改动类型 | 子项 |
| --- | --- | --- |
| `frontend/src/types/schema.d.ts` | 修改（`ChatRequestPayload` 新增 `edit_last_user_message?: boolean`） | 4B |
| `frontend/src/store/useAiCenterStore.ts` | 修改（新增最后一条 user 消息编辑态、编辑草稿、编辑提交错误状态；新增开始编辑/确认编辑/取消编辑 actions） | 4B |
| `frontend/src/components/Dashboard/ChatComposer.tsx` | 修改（新增 `onCancelVoiceRecording` prop；渲染取消按钮） | 4A |
| `frontend/src/components/Dashboard/ChatMessageList.tsx` | 修改（渲染最后一条 user 消息的编辑按钮、内联编辑 textarea、确认/取消按钮、错误提示） | 4B |
| `frontend/src/components/Dashboard/RiskExplanationPanel.tsx` | 修改（传入 `onCancelVoiceRecording` 至 `ChatComposer`；传入编辑态相关 props 至 `ChatMessageList`） | 4A + 4B |

### 后端

| 文件 | 改动类型 | 子项 |
| --- | --- | --- |
| `ChatRequestPayload.java` | 修改（新增 `editLastUserMessage: Boolean`） | 4B |
| `LlmChatRequest.java` | 修改（record 新增 `boolean editLastUserMessage`） | 4B |
| `ChatWebSocketHandler.java` | 修改（`handleChat` 透传 `edit_last_user_message` 字段） | 4B |
| `ConversationMemory.java` | 修改（新增 `peekLastTurn` / `replaceLastTurn`） | 4B |
| `LlmChatService.java` | 修改（`handleChat` 支持 `edit_last_user_message=true` 替换语义；注入 `ExplanationCache`；调用新 `formatConsolidated`） | 4B + 4C |
| `ExplanationCache.java` | 新增（维护当前追踪集合/非 SAFE 集合，并提供统一校验） | 4C |
| `LlmRiskEventListener.java` | 修改（注入 `ExplanationCache`；每次风险评估事件时调用 `refreshTargetState`；新解释抵达时统一校验，满足条件才同时发布 SSE 并写缓存） | 4C |
| `RiskContextFormatter.java` | 修改（`formatConsolidated` 新增 `ExplanationCache` 参数重载） | 4C |

### 测试文件影响

- `useAiCenterStore.test.ts`：新增开始编辑最后一条 user 消息、确认成功替换、确认失败恢复编辑态的测试用例
- `chatWsService.test.ts`：验证 `edit_last_user_message` 字段透传至 WS payload
- `ChatComposer` 相关测试：验证 `recording` 状态下取消按钮可见且回调正确触发
- `ChatMessageList` 相关测试：验证最后一条 user 消息可切换为编辑 textarea，确认/取消按钮行为正确
- `ConversationMemory` 单元测试（后端）：验证 `replaceLastTurn` 成功路径、无轮次时的降级路径
- `LlmChatService` 测试（后端）：验证 `edit_last_user_message=true` 走替换路径，失败时旧轮次保留
- `ExplanationCache` 单元测试（后端）：验证 `put` / `getText` 基本读写；`refreshTargetState` 覆盖"目标消失被驱逐"、"目标降为 SAFE 被驱逐"、"目标仍在且非 SAFE 时保留"三条路径；`shouldAccept` 覆盖"已消失目标拒收"、"SAFE 目标拒收"、"非 SAFE 目标接受"三条路径
- `LlmRiskEventListener` 测试（后端）：验证晚到解释在目标已不被追踪或已降为 SAFE 时不会发布 SSE，也不会写缓存
- `RiskContextFormatter` 测试（后端）：验证注入解释文本时格式正确

---

## 6. 验证标准

**4A**：

1. 在 `recording` 状态下，录音控件区出现"取消"按钮，样式中性（区别于绿色/黄色停止按钮）
2. 点击取消后，`voiceCaptureState` 回到 `idle`，输入框可用，不发送任何 WS 消息
3. 取消后可立即重新录音或直接输入文本发送

**4B**：

4. 最后一条 user 消息底部出现"编辑"按钮；当有 pending 请求或当前已进入编辑态时按钮不可见
5. 点击编辑后，该条 user 消息卡片切换为 textarea，且 textarea 默认填入原消息内容；下方出现"确认"和"取消"按钮
6. 点击确认后，请求以 `edit_last_user_message: true` 发送；成功后，最后一组 USER/ASSISTANT 内容被新结果原子替换
7. 点击取消后，退出编辑态并恢复原消息卡片，不发送任何请求
8. 编辑确认失败后，原最后一轮内容保持不变，编辑框重新打开并保留用户草稿，显示错误提示；后端 `ConversationMemory` 旧轮次未受损

**4C**：

9. 选中一个已有解释文本的目标后发起 Chat，AI 回复内容体现出对该目标解释的感知（如引用解释中的具体风险描述）
10. 未选中目标时，Chat 上下文中不含解释文本（行为与 Step 3 之前一致）
11. 新解释仅在目标当前仍被追踪且仍非 SAFE 时才会发布为 `EXPLANATION` SSE；满足条件的新解释到达后，再次发起 Chat，AI 可见的是最新解释

**通用**：

12. 前端：`npm test` 全通过，无因本步骤改动而引入的新失败
13. 后端：`ConversationMemory`、`LlmChatService`、`ExplanationCache`、`LlmRiskEventListener`、`RiskContextFormatter` 相关测试类全通过；`replaceLastTurn` 覆盖"成功替换"和"无轮次降级追加"两条路径；`ExplanationCache` 覆盖"`refreshTargetState` 驱逐已消失目标"、"`refreshTargetState` 驱逐 SAFE 目标"、"`shouldAccept` 对非 SAFE 目标放行"、"`shouldAccept` 对已消失/SAFE 目标拒收"；`LlmRiskEventListener` 验证晚到解释在目标已消失或已降为 SAFE 时不会发布 SSE、不会写缓存；`RiskContextFormatter` 验证 SAFE 目标不注入解释、非 SAFE 目标注入解释含时间戳

---

## 7. 风险与实施注意事项

**4A**：
- `RiskExplanationPanel.tsx` 需同步传入 `onCancelVoiceRecording`（新增一行 prop 传递）；`ChatComposer` 将该 prop 设为 optional，无回调时不渲染取消按钮，避免无意义点击。
- 不影响 Step 1 已建立的 `voiceCaptureState` 状态机测试，取消路径（`recording → idle`）可用现有 `resetVoiceCapture` action 验证。

**4B 前端**：
- 编辑入口只对最后一条 `request_type === 'CHAT'` 的 user 消息开放，避免语音请求或历史消息进入错误编辑路径。
- `hasPendingConversationRequest` 检查在编辑确认发送期间自然阻断后续消息，保证同一时刻最多一个 pending 请求。
- 编辑失败时必须恢复编辑态并保留用户草稿，不能静默丢失本次修改内容。

**4B 后端**：
- `ConversationMemory.replaceLastTurn` 为 `synchronized` 方法，与现有的 `append`、`getHistory`、`tryAcquire` 竞争同一锁，正确性已由 `Semaphore` 保障（`tryAcquire` 确保同一时刻只有一个请求进入 LLM 路径）。
- `replaceLastTurn` 降级追加（`messages.size() < 2`）属边界容错，应在日志中记录以便排查。
- `LlmChatService` 对 `edit_last_user_message=true` 的处理必须保证"先生成、成功后替换、失败不破坏"——不得先删除旧轮次再生成。现有实现已在 `whenComplete` 中先检查 `throwable` 再操作 `ConversationMemory`，符合此约束。

**4C**：
- `ExplanationCache` 为无界 Map（按 `targetId` 键存储）。在实际目标数有限的场景下不存在内存问题；若未来目标数量显著增长，可引入容量上限或 TTL，不在 v0.9 引入。
- `RiskContextFormatter.formatConsolidated` 新增参数采用"可空参数 + 原三参数委托方法"模式，保持现有测试和调用点不需修改。
- 解释文本注入仅在 Chat（WS）链路生效，不影响 `RiskContextFormatter.formatSummary`；但 `EXPLANATION` 事件本身现在受统一校验约束，目标已消失或已降为 SAFE 时不再继续向前端发布晚到解释。
