# Step 4 执行方案：多轮上下文管理

## Context

Steps 1–3 已完成：LLM 客户端抽象、外部化 prompt、实时风险摘要注入、选中目标定向注入。当前每轮对话无状态——用户第二轮提问时 LLM 看不到第一轮的问答，无法做指代消解。

Step 4 引入 `conversationId` 维度的对话历史管理，使连续提问可引用前文上下文。

## 行业前沿上下文管理策略对比

| 策略 | 原理 | 优势 | 劣势 | 适用场景 |
|------|------|------|------|----------|
| **Full History** | 全部历史消息原样注入 | 零信息损失 | token 消耗线性增长，超限后截断会丢关键信息 | 短会话（<5 轮） |
| **Sliding Window** | 保留最近 N 轮，丢弃更早消息 | 实现简单，成本可控 | 丢失早期上下文，可能丢掉关键初始约束 | 实时问答、客服 |
| **Summary Buffer** | 近期保留原文，远期压缩为摘要 | 兼顾近期精度和远期覆盖 | 摘要需额外 LLM 调用，引入延迟和失真 | 长对话、研究助手 |
| **RAG-based** | 历史向量化存储，按语义检索相关片段 | 跨会话记忆，支持海量历史 | 需向量库基建，检索延迟，语义漂移 | 知识密集型多会话 |
| **Hierarchical Memory** | 工作记忆 + 短期 + 长期分层 | 模拟人类记忆，信息保留最优 | 架构复杂度高，调参难 | Agent 系统 |

### 本项目适配分析

本系统的对话特征：
- **短会话为主**：操作人员围绕当前航行态势提问，典型 3–5 轮即完成一个话题
- **风险上下文已占 token 预算**：system prompt + 风险摘要已占 500–1500 字符
- **实时性要求高**：操作人员需秒级响应，不能引入额外 LLM 摘要调用
- **单体部署**：无向量库基建，不宜引入 RAG

**选型决策：Sliding Window + Token Budget 截断**

理由：
1. 实现简单，无外部依赖，符合项目单体架构
2. 短会话场景下 sliding window 几乎等同于 full history，信息损失极小
3. Token budget 截断作为安全网：当风险上下文膨胀或用户偶尔长对话时，优先保护 system prompt 和风险上下文，从最旧的历史开始裁剪
4. 未来如需升级为 summary buffer，只需在截断策略处插入摘要生成，不影响整体架构

---

## 一、新增 ConversationMemory

**文件**: `backend/.../service/llm/ConversationMemory.java`
**包位置**: `service.llm`（与 `RiskContextHolder` 同级，均为 LLM 服务层的内部状态管理）

### 职责

按 `conversationId` 维护多轮对话消息历史。纯内存存储，不涉及持久化。

### 数据结构

```java
@Component
public class ConversationMemory {

    private final int maxTurns;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, ConversationEntry> store = new ConcurrentHashMap<>();

    // 通过 LlmProperties 注入配置
    public ConversationMemory(LlmProperties llmProperties) {
        this.maxTurns = llmProperties.getConversationMaxTurns();
        this.ttlMillis = llmProperties.getConversationTtlMinutes() * 60_000L;
    }
}
```

**`ConversationEntry`**（包级私有静态内部类）：

```java
static class ConversationEntry {
    private final Semaphore permit = new Semaphore(1);
    private final Deque<LlmChatMessage> messages = new ArrayDeque<>();
    private volatile long lastAccessTimeMillis = System.currentTimeMillis();

    boolean tryAcquire() {
        lastAccessTimeMillis = System.currentTimeMillis();
        return permit.tryAcquire();
    }

    void release() {
        lastAccessTimeMillis = System.currentTimeMillis();
        permit.release();
    }

    synchronized void append(LlmChatMessage message, int maxMessages) {
        messages.addLast(message);
        lastAccessTimeMillis = System.currentTimeMillis();
        while (messages.size() > maxMessages) {
            messages.pollFirst();
            messages.pollFirst(); // 成对移除，保持 USER+ASSISTANT 对齐
        }
    }

    synchronized List<LlmChatMessage> snapshot() {
        lastAccessTimeMillis = System.currentTimeMillis();
        return List.copyOf(messages);
    }
}
```

**并发控制职责分离**：

| 机制 | 归属 | 职责 | 粒度 |
|------|------|------|------|
| `synchronized(this)` | `ConversationEntry` 内部 | 保护 `ArrayDeque` 读写不并发 | 单次 `append`/`snapshot` 调用 |
| `Semaphore permit` | `ConversationEntry` 字段 | 保证同一会话仅允许一个 in-flight LLM 请求 | `LlmChatService.handleChat` 全流程 |

之所以不使用 `ReentrantLock`，是因为 `handleChat` 在请求线程获取并发控制标记，但在 `CompletableFuture.whenComplete` 的异步线程中释放；`ReentrantLock` 要求加锁与解锁发生在同一线程，跨线程释放会触发 `IllegalMonitorStateException`。`Semaphore` 的 `tryAcquire/release` 不受此约束，更适合“请求线程提交、回调线程释放”的异步模型。

同步策略：`ConcurrentHashMap` 保证 entry 级别的原子查找/插入，entry 内部的 `append` 和 `snapshot` 通过 `synchronized(this)` 互斥，确保读写不会在同一个 `ArrayDeque` 上并发；`Semaphore` 保证同一 `conversationId` 下最多存在一个 in-flight 请求。粒度为单个会话，不同 `conversationId` 之间无竞争。

### 核心方法

| 方法 | 签名 | 行为 |
|------|------|------|
| `append` | `void append(String conversationId, LlmChatMessage message)` | 仅在 entry 已存在时委托 `entry.append()`；不复活已被 `clear()` 移除的会话 |
| `getHistory` | `List<LlmChatMessage> getHistory(String conversationId)` | 委托 `entry.snapshot()`，返回不可变副本。不存在则返回空 list |
| `tryAcquire` | `ConversationPermit tryAcquire(String conversationId)` | 通过 `computeIfAbsent` 获取或创建 entry；若当前无 in-flight 请求则返回 permit，否则返回 `null` |
| `ConversationPermit.close` | `void close()` | 释放对应 entry 的并发 permit；通过 permit 自身关闭，避免 `ConversationMemory` 暴露额外释放接口 |
| `clear` | `void clear(String conversationId)` | `store.remove(conversationId)`，直接移除整个 entry（消息与 permit 一起回收） |
| `evictExpired` | `void evictExpired()` | 遍历 store，移除 lastAccessTime 距当前超过 ttlMillis 的条目（entry 连同 permit 一起回收） |

### 设计约束

- `maxTurns` 表示保留的**轮数**（一轮 = 一组 USER + ASSISTANT），默认 10 轮 = 20 条消息
- 存储上限通过 deque size 硬控，不依赖外部 token 计数
- `append` 内部按轮对齐裁剪：当 `messages.size() > maxTurns * 2` 时，连续 `pollFirst()` 两次（移除一个完整轮次）
- 不在 `getHistory` 中做 TTL 检查（读路径保持轻量），TTL 清理由定时任务处理
- `clear` 使用 `store.remove()` 而非 entry 内部清空；`append` 仅对已存在 entry 生效，避免 late reply 在会话被清理后重建历史

---

## 二、TTL 过期清理

**文件**: `backend/.../service/llm/ConversationMemory.java`（定时任务注册在同类中）

使用 Spring `@Scheduled` 定时清理过期会话，避免引入新类：

```java
@Scheduled(fixedDelayString = "${llm.conversation-evict-interval-ms:60000}")
public void evictExpired() {
    long now = System.currentTimeMillis();
    store.entrySet().removeIf(entry -> {
        ConversationEntry value = entry.getValue();
        return value.permit.availablePermits() > 0
                && (now - value.lastAccessTimeMillis) >= ttlMillis;
    });
}
```

实现约束：`evictExpired()` 只清理空闲会话，不清理 permit 已被占用的 in-flight 会话。否则若一次 LLM 调用耗时超过 TTL，entry 会在回调写回前被移除，导致历史静默丢失。

### 设计决策：为什么不绑定 WebSocket 生命周期

- 用户刷新页面后 WebSocket 重建，但业务会话（`conversationId`）可能延续
- 绑定连接生命周期会导致刷新后丢失上下文，用户体验断裂
- TTL 过期 + 用户主动清理双通道，已足够防止内存泄漏

---

## 三、用户主动清理入口

### 协议扩展

`ChatMessageType` 新增 `CLEAR_HISTORY` 类型。

**上行请求**（client → server）：

```json
{
  "type": "CLEAR_HISTORY",
  "source": "client",
  "payload": {
    "conversation_id": "xxx"
  }
}
```

上行 payload 需携带 `event_id`，用于 ACK 关联。

**下行确认**（server → client）：

```json
{
  "type": "CLEAR_HISTORY_ACK",
  "source": "server",
  "sequence_id": "...",
  "payload": {
    "event_id": "...",
    "conversation_id": "xxx",
    "reply_to_event_id": "...",
    "timestamp": "..."
  }
}
```

`reply_to_event_id` 回填上行请求的 `event_id`，与现有 `CHAT_REPLY` / `SPEECH_TRANSCRIPT` 的请求-响应关联模式保持一致。

### 后端处理

**文件**: `backend/.../dto/websocket/ChatMessageType.java`
- 新增 `CLEAR_HISTORY("CLEAR_HISTORY")`、`CLEAR_HISTORY_ACK("CLEAR_HISTORY_ACK")`

**文件**: `backend/.../transport/chat/ChatWebSocketHandler.java`
- `routeMessage` 的 switch 新增 `case CLEAR_HISTORY -> handleClearHistory(session, envelope.getPayload())`

```java
private void handleClearHistory(WebSocketSession session, JsonNode payloadNode) {
    ClearHistoryPayload payload = objectMapper.treeToValue(payloadNode, ClearHistoryPayload.class);
    if (!StringUtils.hasText(payload.getEventId())) {
        sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST,
                  "event_id is required for CLEAR_HISTORY.");
        return;
    }
    if (!StringUtils.hasText(payload.getConversationId())) {
        sendError(session, payload.getEventId(), ChatErrorCode.INVALID_CHAT_REQUEST,
                  "conversation_id is required for CLEAR_HISTORY.");
        return;
    }
    if (!conversationMemory.clear(payload.getConversationId())) {
        sendError(session, payload.getEventId(), ChatErrorCode.CONVERSATION_BUSY,
                  "Previous request in this conversation is still processing.");
        return;
    }
    sendClearHistoryAck(session, payload.getConversationId(), payload.getEventId());
}
```

`CLEAR_HISTORY` 只允许在会话空闲时生效；若同一 `conversation_id` 仍有 in-flight 请求，后端返回 `CONVERSATION_BUSY`，避免清空期间创建新 entry 并导致旧回复回流到新历史。

**文件**: `backend/.../transport/protocol/ProtocolFields.java`
- 新增常量 `CONVERSATION_ID = "conversation_id"`

### 协议文档

**文件**: `docs/EVENT_SCHEMA.md`
- 变更记录新增一行
- Chat 连接章节新增 `CLEAR_HISTORY` 上行消息和 `CLEAR_HISTORY_ACK` 下行消息说明

---

## 四、LlmChatService 接入对话历史

**文件**: `backend/.../service/llm/LlmChatService.java`

### 新增依赖

构造注入 `ConversationMemory`。

### buildMessages 改造

改造前（当前）：
```
SYSTEM:  system prompt
USER:    [风险上下文 + 用户问题]
```

改造后：
```
SYSTEM:      system prompt
USER:        [风险上下文]              ← 仅当存在风险上下文时
USER:        历史用户消息 1            ← 从 ConversationMemory 取出
ASSISTANT:   历史助手回复 1
...
USER:        当前用户提问              ← 不再与风险上下文拼接
```

关键变更：**风险上下文与用户消息拆分为独立消息**。

理由：
1. 风险上下文是每轮请求时的实时态势快照，不应混入用户提问
2. 拆分后历史消息只包含纯用户提问和助手回复，不会把旧态势数据作为"历史"累积
3. 避免截断历史时意外截断风险上下文

```java
private List<LlmChatMessage> buildMessages(ChatRequestPayload request) {
    List<LlmChatMessage> messages = new ArrayList<>();

    // 1. System prompt（固定，始终保留）
    messages.add(new LlmChatMessage(ChatRole.SYSTEM,
            promptTemplateService.getSystemPrompt(PromptScene.CHAT)));

    // 2. 风险上下文（实时快照，始终保留）
    String riskContext = resolveRiskContext(request);
    if (StringUtils.hasText(riskContext)) {
        messages.add(new LlmChatMessage(ChatRole.USER, riskContext));
    }

    // 3. 对话历史（可被截断）
    List<LlmChatMessage> history = conversationMemory.getHistory(request.getConversationId());
    messages.addAll(history);

    // 4. 当前用户提问
    messages.add(new LlmChatMessage(ChatRole.USER, request.getContent()));

    return messages;
}
```

### 风险上下文注入语义

Step 4 的最终语义不是“会话开始时注入一次风险上下文”，而是“**每条消息都重新注入该条消息发送时刻的最新风险快照**”。

- 风险上下文来源于 `RiskContextHolder` 的当前快照
- 每次 `handleChat()` 调用都会重新取一次快照并组装到本轮消息列表
- 风险上下文**不会写入** `ConversationMemory`
- `ConversationMemory` 只保存纯用户文本与助手回复

因此，历史消息延续的是语言上下文；风险数据延续的是实时快照，而不是历史累积。

### 合并风险快照（summary + selected targets）

`resolveRiskContext()` 不再采用“有 `selectedTargetIds` 时只注入选中目标，否则只注入 summary”的 `either/or` 逻辑，而是统一调用 `RiskContextFormatter.formatConsolidated(...)`：

```java
private String resolveRiskContext(ChatRequestPayload request) {
    var context = riskContextHolder.getCurrent();
    var updatedAt = riskContextHolder.getUpdatedAt();
    return riskContextFormatter.formatConsolidated(context, request.getSelectedTargetIds(), updatedAt);
}
```

`formatConsolidated(...)` 的行为：

1. 始终先展示整体态势头部（更新时间 + 本船信息）
2. 展示非 `SAFE` 目标的 Top-N 摘要
3. 若用户选中了目标船，则把选中目标补充进同一份快照
4. 若选中的目标已在 Top-N 中，则升级为详情展示，而不是重复输出两次
5. 若选中的目标不在 Top-N 中，则追加到 `【用户关注目标】` 小节

这意味着用户选中目标后，LLM 既保留整体态势感知，又能拿到用户关注目标的完整详情，不再丢失全局视角。

### handleChat 改造：写回历史

在 `onSuccess` 回调中，将用户消息和助手回复写回 `ConversationMemory`：

```java
CompletableFuture
    .supplyAsync(() -> llmClient.chat(messages), llmExecutor)
    .orTimeout(llmProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
    .whenComplete((responseText, throwable) -> {
        if (throwable == null) {
            // 写回历史：仅保存纯用户提问和助手回复
            conversationMemory.append(conversationId,
                    new LlmChatMessage(ChatRole.USER, request.getContent()));
            conversationMemory.append(conversationId,
                    new LlmChatMessage(ChatRole.ASSISTANT, responseText));
            onSuccess.accept(new ChatReplyResult(responseText, resolveProviderName()));
            return;
        }
        // ... 错误处理不变
    });
```

**注意**：写入历史的是 `request.getContent()`（纯用户文本），不是拼接了风险上下文的消息。

### VoiceChatService 无需改动

`VoiceChatService.forwardTranscriptToLlm()` 构造 `ChatRequestPayload` 时已经传入 `conversationId`，调用 `llmChatService.handleChat()` 后会自动走上述逻辑。

### 最终历史消息管理模式

最终发送给 LLM 的消息序列为：

```text
SYSTEM:      system prompt
USER:        本轮请求时刻的实时风险快照
USER:        历史用户消息 1
ASSISTANT:   历史助手回复 1
...
USER:        当前用户问题
```

最终写回 `ConversationMemory` 的只有：

```text
USER:      当前用户问题
ASSISTANT: 当前助手回复
```

不会写入历史的内容：

- system prompt
- 风险摘要
- 选中目标详情
- 其他运行时上下文

---

## 五、Token 预算控制

**文件**: `backend/.../service/llm/LlmChatService.java`

### 策略

消息组装完成后，检查总字符数是否超过预算。超出时按以下优先级裁剪。

优先级（从高到低，不可裁剪 → 可裁剪）：
1. **System prompt** — 不裁剪
2. **风险上下文** — 不裁剪
3. **当前用户提问** — 不裁剪
4. **对话历史** — 从最旧轮次开始成对移除

### 不可裁剪部分超限的退化行为

如果 system prompt + 风险上下文 + 当前用户提问本身已超过预算（历史为空仍然超限），`trimToTokenBudget` 不会丢弃不可裁剪消息——它返回裁剪掉全部历史后的消息列表，即退化为无历史的单轮请求。超限的根因在于风险上下文（selected-target 详情）或超长用户输入，这些不应在此处静默截断。

LLM provider 若因 token 超限拒绝请求，会走现有的 `LLM_REQUEST_FAILED` 错误路径返回前端。日志中记录总字符数以便排查。

如果此场景频繁出现，应调大 `conversation-token-budget` 或在 `RiskContextFormatter` 中限制 selected-target 的总字符数，而非在 token budget 层做不可裁剪消息的截断。

### 实现

新增私有方法 `trimToTokenBudget`：

```java
private List<LlmChatMessage> trimToTokenBudget(List<LlmChatMessage> messages,
                                                int historyStartIndex,
                                                int historyEndIndex) {
        int totalChars = messages.stream().mapToInt(m -> m.content().length()).sum();
        int budgetChars = llmProperties.getConversationTokenBudget();

    if (totalChars <= budgetChars) {
        return messages;
    }

    // 从 historyStartIndex 开始成对移除（USER + ASSISTANT）
    List<LlmChatMessage> result = new ArrayList<>(messages);
    int removeIndex = historyStartIndex;

    // 历史消息按 USER/ASSISTANT 成对写入；若出现奇数条历史，视为异常状态，此处不裁单条以免进一步破坏对齐
    while (totalChars > budgetChars && removeIndex < historyEndIndex - 1) {
        totalChars -= result.get(removeIndex).content().length();
        totalChars -= result.get(removeIndex + 1).content().length();
        result.remove(removeIndex);
        result.remove(removeIndex); // 移除后下一条自动前移
        historyEndIndex -= 2;
    }

    return result;
}
```

### Token 估算

不引入 tokenizer 依赖。使用字符数直接作为预算单位：
- 配置项 `llm.conversation-token-budget` 单位为字符数，默认 6000（约 4000 token，覆盖中文约 1.5 字/token 的估算）
- 这是一个保守估计，目的是防止超出模型上下文窗口，不追求精确

---

## 六、并发控制

### 问题

同一 `conversationId` 下，如果用户在上一轮回复返回前连续发送多条消息：
1. 两个并发请求基于同一旧历史发起 LLM 调用，谁先返回谁先写回不可控
2. 最终历史顺序可能与用户实际提问顺序不一致
3. 后续轮次将建立在错误的上下文上——这不是体验降级，而是语义错误

总规划（LLM_ENHANCEMENT_PLAN Step 4）明确约束："同一 `conversationId` 下的请求按顺序处理"。

### 策略：per-conversationId 串行化

并发 permit 归属于 `ConversationEntry`（见第一节），`LlmChatService` 通过 `conversationMemory.tryAcquire(conversationId)` 获取。permit 的生命周期与 entry 绑定，TTL 过期时 entry 被移除，permit 随之回收，无泄漏风险。

`handleChat` 在提交异步任务前尝试获取 permit，在 `whenComplete` 回调中关闭 permit：

```java
public void handleChat(ChatRequestPayload request, ...) {
    // ... validation ...

    String conversationId = request.getConversationId();
    ConversationPermit permit = conversationMemory.tryAcquire(conversationId);

    if (permit == null) {
        onError.accept(ChatErrorCode.CONVERSATION_BUSY,
                "Previous request in this conversation is still processing.");
        return;
    }

    List<LlmChatMessage> messages = buildMessages(request);
    CompletableFuture
        .supplyAsync(() -> llmClient.chat(messages), llmExecutor)
        .orTimeout(llmProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
        .whenComplete((responseText, throwable) -> {
            try {
                if (throwable == null) {
                    conversationMemory.append(conversationId, ...);
                    onSuccess.accept(...);
                } else {
                    // ... 错误处理 ...
                }
            } finally {
                permit.close();
            }
        });
}
```

要点：
- `tryAcquire()` 非阻塞：若同一会话上一轮请求尚未完成，立即返回 `CONVERSATION_BUSY` 错误，而非排队等待
- permit 归属 `ConversationEntry`，生命周期由 `ConversationMemory` 的 TTL 统一管理，无需 `LlmChatService` 额外维护会话级 busy map；释放通过 `ConversationPermit.close()` 完成
- 粒度为单个 conversationId，不同会话之间完全并行

### ChatErrorCode 扩展

新增 `CONVERSATION_BUSY("CONVERSATION_BUSY")`，前端收到后应在 UI 提示"请等待上一条消息回复"。

---

## 七、配置项

**文件**: `backend/.../config/properties/LlmProperties.java`

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `llm.conversation-max-turns` | int | 10 | 每个会话保留的最大轮数（1 轮 = USER + ASSISTANT） |
| `llm.conversation-ttl-minutes` | long | 30 | 会话无活动后自动清理的 TTL（分钟） |
| `llm.conversation-evict-interval-ms` | long | 60000 | TTL 清理定时任务间隔（毫秒） |
| `llm.conversation-token-budget` | int | 6000 | 消息序列总字符数预算，超出后从历史最旧端裁剪 |

**文件**: `backend/.../resources/application.properties`

```properties
llm.conversation-max-turns=10
llm.conversation-ttl-minutes=30
llm.conversation-evict-interval-ms=60000
llm.conversation-token-budget=6000
```

---

## 八、LLM 模块耦合收敛

当前 LLM 模块存在的耦合问题：

| 问题 | 现状 | 改进 |
|------|------|------|
| `LlmTriggerService` 直接持有 `RiskStreamPublisher` | LLM 服务层通过构造注入直接引用 transport 层的 publisher | 本次不改动。完整解耦需将 `RiskStreamPublisher` 的调用改为回调参数传入，属于 Step 5 的收敛范围 |
| `ChatWebSocketHandler` 直接依赖 `LlmChatService` + `VoiceChatService` | Handler 同时依赖两个服务 | 保持现状。Handler 是编排层，聚合两个服务是其职责 |
| `LlmExplanationService` 和 `LlmChatService` 各自创建 `ExecutorService` | 两个独立的 virtual thread executor，重复的 `@PreDestroy` shutdown 逻辑 | 抽取为共享配置 bean |

### 8.1 ExecutorService 共享

**文件**: `backend/.../config/llm/LlmExecutorConfig.java`（新增）

```java
@Configuration
public class LlmExecutorConfig {

    public static final String LLM_EXECUTOR = "llmExecutor";

    @Bean(name = LLM_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService llmExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

`LlmChatService`、`LlmExplanationService`、`VoiceChatService` 均改为注入 `@Qualifier(LLM_EXECUTOR) ExecutorService`，移除各自的 `@PreDestroy` 方法。

### 8.2 unwrap 方法消重

`LlmChatService`、`LlmExplanationService`、`VoiceChatService` 三个类中存在相同的 `unwrap(Throwable)` 方法。

**方案**：不抽取工具类。三处调用均是 private 方法，各自一行代码，抽取为 static 工具方法的认知收益极低，反而增加了间接层。保持现状。

---

## 九、前端适配

Step 4 后端引入了服务端对话历史，前端需要适配三个变更点：CLEAR_HISTORY 协议对接、CONVERSATION_BUSY 错误处理、发送互斥覆盖 SPEECH 路径。

### 9.1 类型定义扩展

**文件**: `frontend/src/types/schema.d.ts`

```typescript
// ChatUplinkType 新增
type ChatUplinkType = 'PING' | 'CHAT' | 'SPEECH' | 'CLEAR_HISTORY';

// ChatDownlinkType 新增
type ChatDownlinkType = 'PONG' | 'CHAT_REPLY' | 'SPEECH_TRANSCRIPT' | 'ERROR' | 'CLEAR_HISTORY_ACK';

// 新增 payload 类型
interface ClearHistoryPayload {
  conversation_id: string;
  event_id: string;
}

interface ClearHistoryAckPayload {
  event_id: string;
  conversation_id: string;
  reply_to_event_id: string;
  timestamp: string;
}
```

### 9.2 chatWsService 扩展

**文件**: `frontend/src/services/chatWsService.ts`

**发送支持**：复用现有 `send()` 的 overload 模式。`buildEnvelope` 已统一生成 `event_id`，`CLEAR_HISTORY` 的 payload 只需传 `conversation_id`，`event_id` 由 `buildEnvelope` 自动补入：

```typescript
// send overload 新增
send(type: 'CLEAR_HISTORY', payload: Omit<ClearHistoryPayload, 'event_id'>): string | null;

// 便捷方法（内部调用 send）
sendClearHistory(conversationId: string): string | null {
  return this.send('CLEAR_HISTORY', { conversation_id: conversationId });
}
```

`buildEnvelope` 的 payload 类型联合新增 `Omit<ClearHistoryPayload, 'event_id'>`，无需特殊分支。

**下行路由**：`handleMessage` 的 switch 新增 `'CLEAR_HISTORY_ACK'` 分支，调用新增的 `onClearHistoryAck` 回调。

```typescript
// 新增回调注册
onClearHistoryAck(callback: (payload: ClearHistoryAckPayload) => void): void
```

### 9.3 resetConversation 对接服务端清理

**文件**: `frontend/src/store/useAiCenterStore.ts`

当前 `resetConversation()` 仅清本地状态并生成新 `conversationId`。改造后在清理前先通知服务端释放旧会话的历史：

```typescript
resetConversation: () => {
  const oldConversationId = get().conversationId;
  const newConversationId = createConversationId();

  // 1. 通知服务端清理旧会话历史（fire-and-forget，不阻塞本地重置）
  chatWsService.sendClearHistory(oldConversationId);

  // 2. 本地状态重置
  set((state) => ({
    conversationId: newConversationId,
    chatMessages: [],
    chatInput: '',
    pendingChatEventIds: {},
    chatErrorByEventId: {},
    latestChatError: null,
    // ... 其余字段不变
  }));
};
```

设计要点：
- **Fire-and-forget**：不等待 `CLEAR_HISTORY_ACK`。本地已生成新 `conversationId`，旧会话即使服务端清理失败也会被 TTL 回收
- `CLEAR_HISTORY_ACK` 回调注册但暂不做 UI 处理，仅用于日志确认。未来如需"清理中"状态指示再扩展

### 9.3.1 旧会话异步回包防护

当前 `appendChatReply` / `appendSpeechTranscript` / `appendChatError` 通过 `reply_to_event_id` 关联消息，但不校验 `conversation_id`。当用户在旧会话 LLM 尚未回复时点击"清空会话"：

1. 本地 `chatMessages` 被清空，`conversationId` 已切换为新值
2. 旧会话的 `CHAT_REPLY` 稍后到达，`reply_to_event_id` 在新的 `chatMessages` 中匹配不到任何消息
3. 但 `appendChatReply` 仍会将 assistant 消息 `push` 到 `chatMessages`——一条孤立的 assistant 消息出现在新会话中

**修正**：在 `appendChatReply`、`appendSpeechTranscript`、`appendChatError` 入口处增加 `conversation_id` 校验，丢弃与当前 `conversationId` 不匹配的回包：

```typescript
appendChatReply: (payload: ChatReplyPayload) => {
  set((state) => {
    // 丢弃旧会话的回包
    if (payload.conversation_id !== state.conversationId) {
      return {};
    }
    // ... 现有逻辑不变
  });
},
```

`appendSpeechTranscript` 和 `appendChatError` 同理。`appendChatError` 中 `conversation_id` 目前不在 `ChatErrorPayload` 中，但可通过 `reply_to_event_id` 在 `chatMessages` 中查找来间接判断——如果找不到对应消息且 `chatMessages` 为空，说明会话已被重置，应丢弃。

### 9.4 CONVERSATION_BUSY 错误处理

**文件**: `frontend/src/components/Dashboard/ChatMessageList.tsx`

服务端返回 `error_code: "CONVERSATION_BUSY"` 时，含义为同一会话上一轮请求尚未完成。

当前错误处理流程已覆盖此场景——`appendChatError` 会将消息标记为 `status: 'error'`，`ChatMessageList` 会展示 `error_message` 并显示"重试"按钮。无需特殊处理。

但需确认一点：`CONVERSATION_BUSY` 的 `reply_to_event_id` 指向被拒绝的请求，前端现有的 `appendChatError` 通过 `reply_to_event_id` 关联到对应消息并标记错误，该路径可正常工作。

### 9.5 发送互斥补全（仅 direct 语音模式）

**文件**: `frontend/src/store/useAiCenterStore.ts`

当前 `selectIsChatSending` 仅检查 `request_type === 'CHAT'` 的 pending 消息：

```typescript
// 现有实现
export const selectIsChatSending = (state: AiCenterState) => state.chatMessages.some(
  (message) => message.role === 'user'
    && message.request_type === 'CHAT'
    && state.pendingChatEventIds[message.event_id],
);
```

**preview 模式无此问题**：preview 在收到 `SPEECH_TRANSCRIPT` 时即将 `pendingChatEventIds[eventId]` 置为 false，转录文本写入 `chatInput` 供用户修改后作为全新 CHAT 请求发送，不涉及并发。

**direct 模式存在窗口**：direct 在收到 `SPEECH_TRANSCRIPT` 后 `voiceCaptureState` 变为 `'sent'`（输入框解禁），但 LLM 仍在处理（`pendingChatEventIds[eventId]` 仍为 true）。由于 `selectIsChatSending` 过滤了 `request_type === 'CHAT'`，不会匹配 SPEECH 消息，`disabled` prop 为 false，用户理论上可在此窗口内输入文本并发送，触发并发请求。

实际触发概率极低（需要在转录完成到 LLM 回复之间快速打字并发送），且服务端 `CONVERSATION_BUSY` 会拒绝并发请求。但前端应主动防御，避免用户看到不必要的错误提示。

**修正**：扩展 selector 覆盖 SPEECH direct 路径：

```typescript
export const selectIsChatSending = (state: AiCenterState) => state.chatMessages.some(
  (message) => message.role === 'user'
    && state.pendingChatEventIds[message.event_id],
);
```

移除 `request_type === 'CHAT'` 过滤，任何 pending 的用户消息（CHAT 或 SPEECH direct）均禁用发送。preview 模式的 pending 在转录完成时已清除，不受影响。

同时，`RiskExplanationPanel` 传给 `ChatComposer` 的 `disabled` prop 也需绑定 `isChatSending`，否则即使 selector 已修正，输入框和发送按钮仍不会真正禁用：

```tsx
<ChatComposer
  value={chatInput}
  disabled={isChatSending}
  isSending={isChatSending}
  ...
/>
```

---

## 十、完整消息序列示例

### 场景：用户连续三轮提问

**第 1 轮**（无历史）：
```
SYSTEM:    [system prompt]
USER:      [风险上下文快照 T1]
USER:      "当前最危险的目标是哪艘？"
```
→ ASSISTANT: "目标船 413999001 风险等级为 ALARM..."
→ 写入历史: USER("当前最危险的目标是哪艘？") + ASSISTANT("目标船 413999001...")

**第 2 轮**（1 轮历史）：
```
SYSTEM:    [system prompt]
USER:      [风险上下文快照 T2]           ← 实时更新的态势
USER:      "当前最危险的目标是哪艘？"     ← 历史
ASSISTANT: "目标船 413999001 风险等级..." ← 历史
USER:      "它的航向是多少？"             ← 当前提问
```
→ ASSISTANT: "413999001 当前航向为 45.0°..."
→ 写入历史

**第 3 轮**（2 轮历史）：
```
SYSTEM:    [system prompt]
USER:      [风险上下文快照 T3]
USER:      "当前最危险的目标是哪艘？"
ASSISTANT: "目标船 413999001 风险等级..."
USER:      "它的航向是多少？"
ASSISTANT: "413999001 当前航向为 45.0°..."
USER:      "如果它不改变航向会怎样？"
```

---

## 十、变更清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `service/llm/ConversationMemory.java` | 对话历史存储与 TTL 清理 |
| `config/llm/LlmExecutorConfig.java` | LLM 共享线程池 |
| `dto/websocket/ClearHistoryPayload.java` | CLEAR_HISTORY 请求 payload |
| `dto/websocket/ClearHistoryAckPayload.java` | CLEAR_HISTORY_ACK 响应 payload |

### 修改文件

| 文件 | 变更 |
|------|------|
| `config/properties/LlmProperties.java` | 新增 4 个配置字段 |
| `service/llm/LlmChatService.java` | 注入 ConversationMemory，改造 buildMessages + handleChat 写回逻辑 |
| `dto/websocket/ChatMessageType.java` | 新增 CLEAR_HISTORY / CLEAR_HISTORY_ACK |
| `dto/websocket/ChatErrorCode.java` | 新增 CONVERSATION_BUSY |
| `transport/chat/ChatWebSocketHandler.java` | 注入 ConversationMemory，新增 handleClearHistory + sendClearHistoryAck，路由分支 |
| `transport/protocol/ProtocolFields.java` | 新增 CONVERSATION_ID 常量 |
| `service/llm/LlmExplanationService.java` | 改为注入共享 ExecutorService，移除 @PreDestroy |
| `service/llm/VoiceChatService.java` | 改为注入共享 ExecutorService，移除 @PreDestroy |
| `resources/application.yml` | 新增 conversation 配置块 |
| `docs/EVENT_SCHEMA.md` | 新增 CLEAR_HISTORY / CLEAR_HISTORY_ACK 说明 |
| `frontend/src/types/schema.d.ts` | 新增 CLEAR_HISTORY 相关类型，扩展 ChatUplinkType / ChatDownlinkType |
| `frontend/src/services/chatWsService.ts` | 新增 sendClearHistory 方法，下行路由新增 CLEAR_HISTORY_ACK |
| `frontend/src/store/useAiCenterStore.ts` | resetConversation 发送 CLEAR_HISTORY；selectIsChatSending 覆盖 SPEECH 路径 |

### 不修改

| 文件 | 理由 |
|------|------|
| `LlmClient.java` | 接口不变，已支持 `List<LlmChatMessage>` |
| `GeminiLlmClient.java` / `ZhipuLlmClient.java` | 客户端实现不变，天然支持多消息列表 |
| `ChatRequestPayload.java` | 已有 `conversationId`，无需扩展 |
| `LlmChatMessage.java` | record 结构不变 |
| `ChatRole.java` | 已有 USER / ASSISTANT / SYSTEM |

---

## 十一、测试策略

### 单元测试

| 测试文件 | 覆盖点 |
|----------|--------|
| `ConversationMemoryTest.java` | append 追加与滑动窗口裁剪、getHistory 返回副本、clear 清空、evictExpired TTL 过期、并发 append 安全性 |
| `LlmChatServiceTest.java`（扩展） | buildMessages 含历史消息的正确序列、写回历史的时机和内容、token budget 截断行为、无历史时退化为现有行为 |
| `ChatWebSocketHandlerTest.java`（扩展） | CLEAR_HISTORY 路由与响应、缺少 conversationId 的错误处理 |

### 集成验证

手动验证场景（对应 LLM_ENHANCEMENT_PLAN 中的验证方式）：
1. 连续提问："当前最危险的目标是哪艘？" → "它的航向是多少？" → "如果它不改变航向会怎样？"
2. 验证第二、三轮回答能正确引用前文
3. 发送 CLEAR_HISTORY 后再次提问，验证上下文已清空
4. 等待 TTL 过期后提问，验证历史已自动清理
5. 语音输入走 VoiceChatService 路径，验证对话历史同样生效

---

## 十二、风险与边界

| 风险 | 缓解 |
|------|------|
| 内存泄漏（大量未清理会话） | TTL 自动清理 + 滑动窗口 cap |
| Token 超限导致 LLM 报错 | token budget 截断兜底 |
| 风险上下文被截断 | 风险上下文在 token budget 中标记为不可裁剪 |
| 历史消息顺序错乱 | per-conversationId `tryLock` 串行化，并发请求返回 `CONVERSATION_BUSY` |
| 不可裁剪消息超 token budget | 退化为无历史单轮请求，超限由 LLM provider 报错走现有错误路径 |
| `@Scheduled` 需要 `@EnableScheduling` | 确认主配置类已有此注解，否则需添加 |

---

## 十三、实现顺序

建议按以下顺序实现，每步可独立编译和测试：

1. **LlmProperties 新增配置字段** + application.yml
2. **LlmExecutorConfig** 共享线程池 + 三个 Service 改为注入
3. **ConversationMemory** + 单元测试
4. **LlmChatService 改造** buildMessages + 写回逻辑 + token budget + 单元测试
5. **CLEAR_HISTORY 协议** DTO + ChatMessageType + ChatErrorCode + Handler 路由
6. **前端适配** schema.d.ts 类型 → chatWsService 协议 → store 逻辑
7. **EVENT_SCHEMA.md 更新**
