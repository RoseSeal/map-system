# Agent Step 4：Chat Agent Path（次线，feature-flagged）

> 文档状态：active
> 最后更新：2026-04-24
> 执行状态：completed
> 所属 track：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md)
> 对应总览：`AGENT_LOOP_PLAN.md` §4 Step 4
> 目标：在 `selected_target_ids` 非空时启用 selection-constrained chat agent，通过 feature flag 独立灰度。

---

## 1. Summary

Step 4 在 `LlmChatService` 中增加第二条执行路径：当 `agentModeEnabled = true` 且请求携带非空 `selected_target_ids` 时，路由到 `AgentLoopOrchestrator`；否则维持现有单次生成路径不变。

切换条件：

```
agentModeEnabled = true  AND  request.selectedTargetIds() 非空
    → AgentLoopOrchestrator（新路径）
otherwise
    → llmClient.chat(messages)（现有路径，不变）
```

WS 下行协议不变：两条路径均通过 `CHAT_REPLY` 的 `content` 字段下发最终文本。`VoiceChatService` 以 `LlmChatRequest` 委托给 `LlmChatService.handleChat()`，因此 voice 路径自动受益于同一开关，不需要额外改动。

Step 3 成果（`AgentLoopOrchestrator`、`AgentSnapshotFactory`、`AgentToolRegistry`）全部复用，不修改。

---

## 2. Current State And Step Delta

**现有单次生成路径（`LlmChatService.handleChat`）**：

1. `conversationMemory.tryAcquire(conversationId)` 获取 per-conversation 互斥锁
2. `buildMessages(request)` 拼装 system prompt + 全量文本风险上下文（`riskContextFormatter.formatConsolidated(...)`）+ 历史轮次 + 用户问题
3. `CompletableFuture.supplyAsync(() -> llmClient.chat(messages), llmExecutor)`
4. `.orTimeout(timeoutMs, ...)` 超时守卫
5. 成功：写入 `(USER, ASSISTANT)` 对到 `ConversationMemory` → `onSuccess`
6. 失败：`onError` + permit 释放

**已具备的 Step 3 骨架**：

- `AgentLoopOrchestrator.run(snapshot, initialMessages, maxIterations)` 已实现
- `AgentSnapshotFactory.build()` 已实现
- `AgentToolRegistry`（4 个查询工具）已实现
- `LlmProperties.Advisory` 已有 `maxIterations = 5`

**Step 4 增量**：

- `LlmProperties` 新增 `agentModeEnabled` 字段（默认 `false`）
- 新增 `ChatAgentPromptBuilder`：为 chat agent 路径构造初始消息，不复用 `AdvisoryPromptBuilder`
- `LlmChatService` 新增分支：条件满足时切入 agent 路径，失败均映射到 `LlmErrorCode.LLM_FAILED`

---

## 3. In Scope

- `LlmProperties.agentModeEnabled`（默认 `false`）
- `LlmProperties.agentChatTimeoutMs`（默认 `18000ms`，低于前端 20s 阈值）
- `ChatAgentPromptBuilder`：system prompt + user message，包含选中目标 ID 列表作为上下文提示
- `LlmChatService` agent 分支：snapshot 构建、orchestrator 调用、超时、executor rejection、内存写入、permit 释放
- 错误映射：agent loop 所有失败变体均映射为 `LlmErrorCode.LLM_FAILED`
- 单元测试：路由分支、agent 路径成功与失败、executor rejection、内存写入边界

## 4. Out Of Scope

- not doing：WS 协议变更——`CHAT_REPLY` 的 `content` 字段语义不变
- not doing：前端功能变更——前端已消费 `content`，无需逻辑修改；`CHAT_REQUEST_TIMEOUT_MS`（当前 20s）无需随 Step 4 调整，因为 `agentChatTimeoutMs` 默认 18s 保持在该阈值以内
- not doing：在 `CHAT_REPLY` 中附带 advisory block 或工具调用轨迹
- not doing：`VoiceChatService` 独立改动——voice 路径已通过 `LlmChatRequest` 委托，自动继承 Step 4 的分支逻辑
- not doing：tool call 中间消息写入 `ConversationMemory`——只写最终 `(USER, ASSISTANT)` 对
- deferred：voice 路径特定 agent 提示词优化（当前 chat 和 voice 共用同一 `ChatAgentPromptBuilder`），如有需要延至后续版本

---

## 5. Key Decisions

### 5.1 "selection-constrained" 的语义边界

"selection-constrained"（总览 §3.7）指的是**激活条件约束**，不是**工具访问域约束**：

- **激活条件**：仅当用户已选中目标（`selected_target_ids` 非空）时，agent loop 才被激活；无选中目标的普通对话维持单次生成路径
- **工具访问域**：一旦激活，LLM 可通过 `AgentToolRegistry` 中的全部查询工具访问完整 `AgentSnapshot`，包括 `get_top_risk_targets`（全局排名）和 `get_target_detail`（任意 targetId）

选中目标仅作为 `ChatAgentPromptBuilder` 中的**上下文切入点**出现在 user message 里，不构成工具层的硬限制。这与总览的设计意图一致："以选中目标为切入点启动 agent loop，LLM 可进一步查询选中目标的详情和**相关场景数据**"。

不做工具层硬限制的理由：用户选中目标 A 提问，LLM 可能需要查询目标 B 的状态来给出完整答案（如"目标 A 正在接近，同时还有哪些目标需要注意？"）。硬限制会使这类问题无法回答，降低 chat agent 的实用性。

若后续需要在工具层实施选中目标范围约束，可以在 `AgentToolRegistry.execute()` 中注入 allowed target ID set，或引入独立的 scoped registry——但这属于 Step 4 之后的演进，不在本步骤范围内。

### 5.2 路由条件与 feature flag 语义

路由条件：

```java
boolean useAgentPath = llmProperties.isAgentModeEnabled()
        && !request.selectedTargetIds().isEmpty();
```

`agentModeEnabled` 为 `false` 时，不进入 agent 路径，无论 `selectedTargetIds` 是否非空。这保证了 flag 关闭时行为与当前版本完全一致，可安全灰度。

`agentModeEnabled` 属于顶级 `LlmProperties` 字段，不嵌套在 `Advisory` 内；两条路径（advisory、chat）共享同一 `maxIterations` 配置（`llmProperties.getAdvisory().getMaxIterations()`），无需为 chat 新增重复字段。

### 5.2 Snapshot 构建失败处理

`AgentSnapshotFactory.build()` 在系统未初始化（无风险上下文）时抛出 `IllegalStateException`。Chat agent 路径中捕获此异常，映射为 `LlmErrorCode.LLM_FAILED`，不静默回退到单次生成路径。

理由：静默回退会掩盖 agent 路径的启动失败，增加调试成本；用户通常在风险上下文已初始化后才会有选中目标，此异常为低概率边界情况，返回错误比行为不一致更可接受。

### 5.3 超时守卫与前端阈值对齐

前端 `CHAT_REQUEST_TIMEOUT_MS = 20000ms`（[constants.ts:191](../../../frontend/src/config/constants.ts#L191)，[useAiCenterStore.ts:813](../../../frontend/src/store/useAiCenterStore.ts#L813)）：前端等待超过该时间后将 pending 消息标记为 `CHAT_REQUEST_TIMEOUT`，后续到达的回复不再显示。

Agent 路径的后端总超时必须 **严格小于** 该阈值，否则"有效回复"可能在前端已超时后才到达，静默丢失。

因此，Step 4 引入独立配置字段 `llm.agent-chat-timeout-ms`，不用 `timeoutMs × maxIterations` 派生——派生值依赖两个可独立调整的参数，容易无声地超出前端阈值：

```java
// LlmProperties 新增字段
private long agentChatTimeoutMs = 18_000L;  // 默认 18s，低于前端 20s 阈值
```

```java
// 使用方
future.orTimeout(llmProperties.getAgentChatTimeoutMs(), TimeUnit.MILLISECONDS)
```

18s 留有 2s 余量用于 WS 传输和 JVM 调度抖动。如需更长等待，运维侧同步调整 `llm.agent-chat-timeout-ms` 和前端 `CHAT_REQUEST_TIMEOUT_MS`，Step 4 本身不要求二者绑定——但必须保证后端值 < 前端值。

`maxIterations`（步骤数上限）独立于超时：若 18s 内 orchestrator 完成了 5 轮，则按正常完成返回；若 18s 内仅完成了 2 轮，超时触发 `TimeoutException`，映射为 `LLM_TIMEOUT`。

### 5.4 toolCallCount == 0 在 chat 路径中的允许策略

Advisory 路径（Step 3 §7.2）中，`toolCallCount == 0` 视为 schema failure：evidence 必须追溯到工具结果。

Chat agent 路径**不施加此约束**：用户可能提出不需要工具查询的问题（如"你好"、"总结一下刚才的情况"），此时 LLM 直接返回 `FinalText` 是合法行为。Chat agent 的价值在于提供工具查询能力，而不是强制每次回复都必须调用工具。

结论：`Completed(finalText, iterations, toolCallCount)` 中 `toolCallCount` 不做门控，`toolCallCount == 0` 的 `Completed` 正常走成功路径。这与 `AdvisoryService` 的处理显式不同，需要在测试中覆盖该 case（`toolCallCount == 0` 时 `onSuccess` 被正常调用）。

### 5.5 ConversationMemory 写入边界

Agent 路径成功时，行为与单次生成路径完全相同：

- `editLastUserMessage = true`：调用 `conversationMemory.replaceLastTurn(conversationId, userMsg, assistantMsg)`
- `editLastUserMessage = false`：`conversationMemory.append` 两次（USER, ASSISTANT）

工具调用中间轮次（`ToolCallAgentMessage`、`ToolResultAgentMessage`）不进入 `ConversationMemory`，由 `AgentLoopOrchestrator` 在其内部 `List<AgentMessage>` 维护，循环结束后即丢弃。

### 5.6 Agent loop 失败的统一映射

所有失败变体均映射为 `LlmErrorCode.LLM_FAILED`，与现有单次路径失败行为一致：

| `AgentLoopResult` 变体 | 原因 | 处理 |
|---|---|---|
| `MaxIterationsExceeded` | LLM 连续请求工具未产出 `FinalText` | `LLM_FAILED`，log warn |
| `ProviderFailed` | provider SDK 异常 | `LLM_FAILED`，log warn |
| `ToolFailed` | 工具注册表非预期异常 | `LLM_FAILED`，log warn |

注意：`AgentLoopResult.MaxIterationsExceeded` 没有 `finalText` 字段，无法降级提取部分输出，必须以错误返回。

### 5.7 ChatAgentPromptBuilder 与 AdvisoryPromptBuilder 的分离

两者目标不同，不应复用或继承：

- `AdvisoryPromptBuilder`：系统驱动，要求强制按顺序调用三个工具，输出结构化 JSON
- `ChatAgentPromptBuilder`：用户驱动，回答用户自由问题，工具使用为辅助，输出为自然语言

`ChatAgentPromptBuilder.build(request, snapshot)` 构造两条消息：

1. **system message**：声明 agent 角色为航海态势助理；要求只引用工具返回的事实，不编造数值；输出为自然语言回复
2. **user message**：原始 `request.content()` + 选中目标 ID 列表的简短上下文提示

不注入 `riskContextFormatter.formatConsolidated(...)` 的完整文本输出，原因与 advisory 路径相同：chat agent evidence 必须可追溯到工具调用结果。

### 5.8 ConversationHistory 在 agent 路径中不再注入

单次生成路径的 `buildMessages(request)` 将历史轮次注入到 LLM 消息列表。Agent 路径中，`ChatAgentPromptBuilder` 不注入对话历史——历史轮次为普通文本，无法被 `chatWithTools` 用 tool call 形式使用，且会显著增大 token 消耗和延迟。

权衡：用户在 agent 模式下的多轮上下文连续性会有损失。这是 step-plan 阶段已知的限制，接受它比引入带历史的 tool-call 轮次混合消息格式更简单。后续如需支持，可在 user message 中附加"前几轮摘要"的方式处理，留待反馈驱动。

---

## 6. Component Design

### 6.1 `LlmProperties`

新增顶级字段：

```java
private boolean agentModeEnabled = false;
private long agentChatTimeoutMs = 18_000L;
```

`agentChatTimeoutMs` 默认 18000ms，低于前端 `CHAT_REQUEST_TIMEOUT_MS`（20000ms），保证后端回复在前端超时前到达。两个字段均无需写入 `application.properties`，需要时显式覆盖。

### 6.2 `ChatAgentPromptBuilder`

```java
@Component
public class ChatAgentPromptBuilder {

    public List<AgentMessage> build(LlmChatRequest request, AgentSnapshot snapshot);
}
```

- system message：`ChatRole.SYSTEM`，内容为航海助理角色声明与工具使用约束
- user message：`ChatRole.USER`，内容为 `request.content()` + 选中目标 ID 列表提示

不接受 `PromptTemplateService`；agent 路径的 prompt 在代码中定义（与 `AdvisoryPromptBuilder` 同等处理），不用文件加载。

### 6.3 `LlmChatService` 修改点

新增依赖注入：`AgentSnapshotFactory`、`AgentLoopOrchestrator`、`ChatAgentPromptBuilder`。

`handleChat(...)` 内判断路由：

```
if (agentModeEnabled && selectedTargetIds 非空):
    → runAgentChat(request, permit, onSuccess, onError)
else:
    → 现有 buildMessages → llmClient.chat 路径（不变）
```

`runAgentChat(...)` 伪代码：

```
try:
    snapshot = agentSnapshotFactory.build()
catch IllegalStateException:
    permit.close()
    onError(LLM_FAILED, "Agent mode: no risk context snapshot available.")
    return

initialMessages = chatAgentPromptBuilder.build(request, snapshot)
maxIterations = llmProperties.getAdvisory().getMaxIterations()

try:
    future = CompletableFuture.supplyAsync(
        () -> orchestrator.run(snapshot, initialMessages, maxIterations),
        llmExecutor
    )
catch RejectedExecutionException:
    permit.close()
    log.warn("LLM executor rejected agent chat request")
    onError(LLM_FAILED, "LLM request failed.")
    return

future.orTimeout(llmProperties.getAgentChatTimeoutMs(), TimeUnit.MILLISECONDS)
      .whenComplete((loopResult, throwable) -> {
          try:
              if throwable != null:
                  cause = unwrap(throwable)
                  if cause is TimeoutException: future.cancel(true); onError(LLM_TIMEOUT, ...)
                  else: onError(LLM_FAILED, ...)
                  return

              switch loopResult:
                  Completed(finalText, ...):
                      // toolCallCount == 0 is allowed for chat (see §5.4)
                      writeToMemory(request, finalText)
                      onSuccess(new ChatReplyResult(finalText, providerName()))
                  MaxIterationsExceeded, ProviderFailed, ToolFailed:
                      onError(LLM_FAILED, ...)
          finally:
              permit.close()
      })
```

注意：`agentSnapshotFactory.build()` 在 `runAgentChat` 入口调用，发生在 `supplyAsync` 之前，属于调用线程（WebSocket handler 线程）。`build()` 仅做内存拷贝，不涉及 I/O，延迟可接受（通常 < 1ms）。`RejectedExecutionException` 捕获必须在 `supplyAsync` 调用的外层，此时 `whenComplete` 回调尚未注册，只能在 catch 块内同步释放 permit。

---

## 7. File And Module Impact

### 7.1 Backend modified files

| 文件 | 改动 |
|---|---|
| [LlmProperties.java](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java) | 新增 `agentModeEnabled` 字段 |
| [LlmChatService.java](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java) | 注入三个新依赖；`handleChat` 增加路由判断；新增 `runAgentChat` 私有方法 |

### 7.2 Backend new files

| 文件 | 用途 |
|---|---|
| `llm/agent/chat/ChatAgentPromptBuilder.java` | chat agent 初始消息构造 |

建议包路径 `llm/agent/chat/`，与 `llm/agent/advisory/` 平级，保持 agent 相关子模块同层。

### 7.3 Backend test files modified

| 文件 | 改动 |
|---|---|
| [LlmChatServiceTest.java](../../../backend/map-service/src/test/java/com/whut/map/map_service/llm/service/LlmChatServiceTest.java) | 新增 agent 路径的路由、成功、失败、内存写入测试用例 |

### 7.4 Backend test files new

| 文件 | 用途 |
|---|---|
| `llm/agent/chat/ChatAgentPromptBuilderTest.java` | 验证消息结构（role 顺序、selectedTargetIds 出现在 user message）|

### 7.5 No frontend changes

CHAT_REPLY 的 `content` 字段语义不变，前端无需任何修改。

---

## 8. Implementation Order

### Phase 1：配置与类型

- `LlmProperties` 新增 `agentModeEnabled`
- 新建 `ChatAgentPromptBuilder`

### Phase 2：`LlmChatService` 路由

- 注入新依赖
- `handleChat` 增加路由条件判断
- 实现 `runAgentChat`（agent path）

### Phase 3：测试

- `ChatAgentPromptBuilderTest`：消息数量、角色顺序、选中目标 ID 出现在 user message
- `LlmChatServiceTest` 补充用例（见第 9 节）

---

## 9. Test Plan

### 9.1 路由分支

- `agentModeEnabled = false`，selectedTargetIds 非空 → 走单次路径（`llmClient.chat` 被调用，`orchestrator` 未被调用）
- `agentModeEnabled = true`，selectedTargetIds 为空 → 走单次路径
- `agentModeEnabled = true`，selectedTargetIds 非空 → 走 agent 路径（`orchestrator.run` 被调用，`llmClient.chat` 未被调用）

### 9.2 Agent 路径成功

- `orchestrator.run` 返回 `Completed(finalText, ...)` → `onSuccess` 被调用一次，content 为 finalText
- 成功时 `(USER, ASSISTANT)` 对被写入 `ConversationMemory`（`append` 调用验证）
- `editLastUserMessage = true` 时调用 `replaceLastTurn` 而非 `append`

### 9.3 Agent 路径失败

- `orchestrator.run` 返回 `MaxIterationsExceeded` → `onError(LLM_FAILED, ...)` 被调用，`ConversationMemory` 未被写入
- `orchestrator.run` 返回 `ProviderFailed` → `onError(LLM_FAILED, ...)`
- `orchestrator.run` 返回 `ToolFailed` → `onError(LLM_FAILED, ...)`
- `agentSnapshotFactory.build()` 抛出 `IllegalStateException` → `onError(LLM_FAILED, ...)`，permit 被释放

### 9.4 toolCallCount == 0 允许

- `orchestrator.run` 返回 `Completed(finalText, iterations=1, toolCallCount=0)` → `onSuccess` 被正常调用，`ConversationMemory` 写入 `(USER, ASSISTANT)` 对（与 advisory 路径的 schema failure 行为显式对比）

### 9.5 Executor rejection

- `llmExecutor.execute(...)` 抛出 `RejectedExecutionException` → `onError(LLM_FAILED, ...)` 被调用，permit 被释放，`ConversationMemory` 未被写入

### 9.6 Permit 释放

- 所有成功、失败、snapshot 异常、executor rejection、超时路径中，permit 必须被 released exactly once

### 9.7 超时

- `CompletableFuture` 超时触发 `TimeoutException` → `onError(LLM_TIMEOUT, ...)`（复用现有 timeout case 结构）

---

## 10. Constraints And Risks

- **用户感知延迟与前端阈值对齐**：agent 路径超时由独立字段 `agentChatTimeoutMs`（默认 18s）控制，低于前端 `CHAT_REQUEST_TIMEOUT_MS`（20s）。若运维调高 `agentChatTimeoutMs`，必须同步调整前端常量，否则后端有效回复会在前端超时后到达而被静默丢弃。feature flag 默认关闭是留出测量实际延迟的评估窗口
- **历史注入缺失**：agent 路径不注入对话历史（第 5.7 节），多轮对话连续性有损失。这是已知限制，需要在灰度阶段通过真实使用反馈决定是否补齐
- **Snapshot 可用性**：`agentSnapshotFactory.build()` 依赖 `RiskContextHolder` 已持有快照。若系统启动后未收到第一帧风险数据就触发 chat agent，会快速失败；这在正常使用场景下不是问题，但自动化测试环境需注意构造好前置状态
- **Voice 路径联动**：VoiceChatService 委托到 LlmChatService，voice + selectedTargetIds 也会进入 agent 路径。这是预期行为，但如果 voice 场景延迟更敏感，可考虑在 LlmVoiceMode 上增加独立 guard，留作 backlog

---

## 11. Deviations

本步骤与 `AGENT_LOOP_PLAN.md` §4 Step 4 的目标和范围保持一致，无已知偏离。

如实施中发现偏离，按附录 A 格式在 `AGENT_LOOP_PLAN.md` 中追加条目，并在此节补充说明。
