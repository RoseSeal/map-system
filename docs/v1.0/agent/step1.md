# Agent Step 1：Provider-neutral Agent Contract

> 文档状态：active
> 最后更新：2026-04-23
> 执行状态：completed
> 所属 track：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md)
> 对应总览：`AGENT_LOOP_PLAN.md` §4 Step 1
> 目标：扩展 `LlmClient` 接口支持工具调用，并定义 agent loop 的内部消息与步骤结果合同，同时保持现有 chat / explanation 路径不被破坏。

---

## 1. Summary

Step 1 是 agent track 的“合同层”步骤，不实现工具本身，也不实现 advisory 编排器。其职责是把 provider client 从“只会返回一段文本”提升为“能返回下一步动作”的统一接口。

本步骤完成后应具备以下能力：

1. `LlmClient` 对外同时提供：
   - 兼容现有路径的 `chat(List<LlmChatMessage>)`
   - 供 agent loop 使用的 `chatWithTools(List<AgentMessage>, List<ToolDefinition>)`
2. 系统内部具备明确的 agent 消息模型：
   - 文本消息
   - tool call 消息
   - tool result 消息
3. provider 返回值被统一归一化为：
   - `ToolCallRequest`
   - `FinalText`
4. 现有 `LlmChatService`、`LlmExplanationService` 无需改调用方式即可继续工作

总览中的完成边界在本文件中保持不变：

- 定义 `ToolDefinition`
- 定义 `AgentStepResult`
- 定义 `AgentMessage`
- 扩展 `LlmClient`
- 在 Gemini / Zhipu provider 中完成映射与单元测试

本步骤不包含工具注册、工具执行和 advisory 输出协议。这些仍分别归属于 Step 2 和 Step 3。

---

## 2. Current State And Step Delta

当前代码状态说明 Step 1 必须优先解决“合同缺失”，而不是直接进入工具实现：

- `LlmClient` 当前只有 `String chat(List<LlmChatMessage> messages)`，调用方只能拿到最终文本
- `LlmChatMessage` + `ChatRole` 只覆盖 `SYSTEM / USER / ASSISTANT` 三种文本轮次，不支持 tool call / tool result 历史
- `GeminiLlmClient` 当前只做两件事：
  - 把非 system 文本消息转为 `Content`
  - 把 system 文本折叠进 `GenerateContentConfig.systemInstruction`
- `ZhipuLlmClient` 当前只做纯文本对话映射，直接读取首条返回消息的 `content`
- `LlmChatService` 与 `LlmExplanationService` 都直接调用 `llmClient.chat(messages)`；现有服务层没有任何 tool-aware 分支

因此 Step 1 的增量不是“增加一组新 DTO”这么简单，而是要把 provider client 的职责从“纯文本生成器”提升为“单步决策器”，同时保证旧调用点不需要同步重写。

---

## 3. In Scope

### 3.1 统一合同类型

- `ToolDefinition`
- `ToolCall`
- `ToolResult`
- `AgentStepResult`
- `AgentMessage` 及其三个实现类型

### 3.2 `LlmClient` 接口扩展

- 新增 `chatWithTools(...)`
- 将现有 `chat(...)` 收敛为兼容 facade，而不是第二套独立实现

### 3.3 Provider 映射

- `GeminiLlmClient` 完成 tool-aware request / response 映射
- `ZhipuLlmClient` 完成 tool-aware request / response 映射，并保留总览规定的 degraded fallback

### 3.4 测试

- 合同类型基础校验
- `chat(...)` 兼容路径测试
- Gemini / Zhipu 两条 provider 分支测试

---

## 4. Out Of Scope

- deferred：`AgentTool`、`AgentToolRegistry` 与查询工具实现，归属 Step 2
- deferred：`AgentLoopOrchestrator`、工具执行循环、最大轮次治理，归属 Step 3
- deferred：`ADVISORY` 结构化输出、SSE 协议、前端 advisory 消费，归属 Step 3
- deferred：selection-constrained chat agent 接入 `LlmChatService`，归属 Step 4
- deferred：`QueryRegulatoryContextTool` 与 `EvaluateManeuverTool`，归属 Step 5
- not doing：修改 `ConversationMemory` 持久语义；Step 1 只定义内部消息，不改变历史写入规则
- not doing：修改 WebSocket message schema 或 SSE schema
- not doing：在 provider client 内直接执行工具，或持有任何 live 风险状态
- not doing：为 Zhipu 构造一套与 native tool call 平行的“提示词模拟工具协议”

Step 1 不需要向 `docs/TODO.md` 新增事项。所有延后项都已经由后续 step 或现有 TODO backlog 接管。

---

## 5. Key Decisions

### 5.1 `chat(...)` 保留为兼容 facade，`chatWithTools(...)` 成为唯一底层能力

现有 chat / explanation 路径已经广泛依赖 `LlmClient.chat(List<LlmChatMessage>)`。Step 1 不重写调用方，而是把 `chat(...)` 下沉为 `chatWithTools(..., emptyTools)` 的兼容 facade。

目标状态：

```java
public interface LlmClient {

    String NON_SYSTEM_MESSAGE_ERROR = "messages must contain at least one non-system message";

    default String generateText(String prompt) {
        return chat(List.of(new LlmChatMessage(ChatRole.USER, prompt)));
    }

    default String chat(List<LlmChatMessage> messages) {
        List<AgentMessage> agentMessages = toTextAgentMessages(messages);
        AgentStepResult result = chatWithTools(agentMessages, List.of());
        return requireFinalText(result);
    }

    AgentStepResult chatWithTools(
        List<AgentMessage> messages,
        List<ToolDefinition> tools
    );
}
```

`chat(...)` 的兼容语义：

1. 把 `LlmChatMessage` 列表转换为 `TextAgentMessage`
2. 以空工具目录调用 `chatWithTools(...)`
3. 仅接受 `FinalText`
4. 若空工具目录下 provider 返回 `ToolCallRequest`，视为实现错误并抛出 `IllegalStateException`

这样做的意义是：

- 现有调用方零侵入
- provider 只维护一套真正的请求/解析逻辑
- Step 3 / Step 4 可以直接复用同一底层合同
- Gemini 现有的 503 重试机制不得随着 `chat(...)` 下沉而丢失；该可靠性逻辑必须迁移到 provider 的 `chatWithTools(...)` 内部

### 5.2 `AgentMessage` 与 `LlmChatMessage` 并存，不污染现有用户可见对话模型

`LlmChatMessage` 继续服务于现有 chat / explanation 入口；`AgentMessage` 专门服务于 agent loop 的内部消息历史。两者职责分离：

- `LlmChatMessage`：外部服务层已有文本请求
- `AgentMessage`：内部工具增强推理轮次

Step 1 不废弃 `LlmChatMessage`，也不把 tool call 强塞进现有 `ChatRole` 枚举。这保持了当前服务层、memory、WebSocket 协议和测试的最小改动面。

### 5.3 JSON 结构载体统一使用 Jackson `ObjectNode`

为避免 provider-specific string protocol，Step 1 将 schema、tool args、tool result 统一固定为 Jackson `ObjectNode`：

```java
public record ToolDefinition(
    String name,
    String description,
    ObjectNode parametersJsonSchema
) {}

public record ToolCall(
    String callId,
    String toolName,
    ObjectNode arguments
) {}

public record ToolResult(
    String callId,
    String toolName,
    ObjectNode payload
) {}
```

选择 `ObjectNode` 的原因：

- 当前仓库已广泛使用 Jackson
- schema / args / result 的顶层本就应是 JSON object，而不是任意文本
- Step 2 工具注册表和 Step 3 orchestrator 都需要结构化载体，而不是再次 parse 字符串

### 5.4 `AgentStepResult` 只表达“下一步动作”，不内嵌执行逻辑

```java
public sealed interface AgentStepResult permits ToolCallRequest, FinalText {
}

public record ToolCallRequest(
    String callId,
    String toolName,
    ObjectNode arguments
) implements AgentStepResult {
}

public record FinalText(String text) implements AgentStepResult {
}
```

该密封层只回答一个问题：provider 这一轮是要“继续查工具”还是“给最终回答”。它不承载执行器、重试器或循环状态，这些都仍归 Step 3。

`callId` 的归一化规则在 Step 1 固定如下：

- provider response 自带 tool-call ID 时，直接沿用
- provider response 不带 tool-call ID 时，由 mapper 生成 UUID

这样可保证 Step 3 在回填 `ToolResultAgentMessage` 时，不需要再分 provider 判断 `callId` 来源。

### 5.5 Zhipu fallback 保持显式降级，不伪造 tool-call 语义

总览已经明确：Zhipu 若当前模型 / SDK 不支持稳定 function calling，则 fallback 到 JSON mode + `FinalText`。

Step 1 在实现层面保持这一边界：

- 原生 tool-call 能力可用：返回真正的 `ToolCallRequest` 或 `FinalText`
- 原生能力不可用：仅要求返回 `FinalText`
- 不在 Step 1 额外设计“提示词模拟 tool call → 再 parse 成 `ToolCallRequest`”的平行协议

这样做的代价是 provider 间能力不完全对称，但好处是：

- 保持 provider-neutral contract 简洁
- 避免第二套隐性协议污染 Step 3 / Step 4
- 符合总览中“fallback 到 JSON mode + FinalText”的既有边界

Step 3 / Step 4 是否对 agent 模式做 provider 能力灰度，由下游步骤决定，不在本步骤处理。

---

## 6. Detailed Design

### 6.1 类型与文件布局

```text
backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/
  ToolDefinition.java
  ToolCall.java
  ToolResult.java
  AgentStepResult.java
  ToolCallRequest.java
  FinalText.java
  AgentMessage.java
  TextAgentMessage.java
  ToolCallAgentMessage.java
  ToolResultAgentMessage.java
```

Step 1 不额外建立 `adapter/`、`bridge/` 或 `protocol/` 子层。当前 agent 包规模仍小，直接放在 `llm/agent/` 下最清晰。

### 6.2 `AgentMessage` 定义

```java
public sealed interface AgentMessage permits
    TextAgentMessage,
    ToolCallAgentMessage,
    ToolResultAgentMessage {
}
```

```java
public record TextAgentMessage(
    ChatRole role,
    String content
) implements AgentMessage {
}
```

```java
public record ToolCallAgentMessage(
    String callId,
    String toolName,
    ObjectNode arguments
) implements AgentMessage {
}
```

```java
public record ToolResultAgentMessage(
    String callId,
    String toolName,
    ObjectNode result
) implements AgentMessage {
}
```

约束：

- `TextAgentMessage` 继续复用 `ChatRole`
- 当前 `ChatRole` 枚举只有 `SYSTEM / USER / ASSISTANT` 三个取值，因此 Step 1 不需要额外写运行时 `switch` 校验；若未来枚举扩展，再由实现补充防御性约束
- `callId`、`toolName` 不得为空白
- `arguments` / `result` 不得为 `null`
- `ToolCallAgentMessage` 表示“模型上一轮请求了哪个工具”
- `ToolResultAgentMessage` 表示“系统把该工具的结构化结果回填给模型”

### 6.3 `ToolCall` / `ToolResult` 与 `AgentMessage` 的关系

`ToolCall` / `ToolResult` 是执行边界 carrier；`ToolCallAgentMessage` / `ToolResultAgentMessage` 是对话历史 carrier。两者字段相近，但用途不同：

- `ToolCall`：Step 2 `AgentToolRegistry.execute(...)` 的输入
- `ToolResult`：Step 2 registry / Step 3 orchestrator 的执行结果
- `ToolCallAgentMessage`：继续请求 provider 下一轮时，需要放回 message history 的 assistant tool-call 事件
- `ToolResultAgentMessage`：继续请求 provider 下一轮时，需要放回 message history 的 tool result 事件

Step 1 只定义类型，不在本步骤引入统一转换器；下游 orchestrator 直接在本地完成字段搬运即可。

### 6.4 `LlmClient` 接口变化

```java
public interface LlmClient {

    String NON_SYSTEM_MESSAGE_ERROR = "messages must contain at least one non-system message";

    default String generateText(String prompt) {
        return chat(List.of(new LlmChatMessage(ChatRole.USER, prompt)));
    }

    default String chat(List<LlmChatMessage> messages) {
        List<AgentMessage> agentMessages = toTextAgentMessages(messages);
        AgentStepResult result = chatWithTools(agentMessages, List.of());
        return requireFinalText(result);
    }

    AgentStepResult chatWithTools(
        List<AgentMessage> messages,
        List<ToolDefinition> tools
    );
}
```

实现要求：

- `generateText(...)` 语义保持不变，继续走 `chat(...)`
- `chat(...)` 由 interface default method 实现，不再要求 provider 重复实现一套旧逻辑
- 现有 provider 只需实现 `chatWithTools(...)`

`chat(...)` 的兼容伪代码：

```text
convert LlmChatMessage -> TextAgentMessage
call chatWithTools(agentMessages, emptyTools)
if result is FinalText -> return text
if result is ToolCallRequest -> throw IllegalStateException
```

### 6.5 Gemini 实现边界

`GeminiLlmClient` 保留现有“系统指令独立、其余内容转 `Content`”的模式，但扩展为 agent-aware：

- `TextAgentMessage(SYSTEM)`：继续折叠进 `GenerateContentConfig.systemInstruction`
- `TextAgentMessage(USER/ASSISTANT)`：转换为普通 `Content`
- `ToolCallAgentMessage`：映射为 Gemini function-call history part
- `ToolResultAgentMessage`：映射为 Gemini function-response history part
- `ToolDefinition`：映射为 Gemini tool / function declaration
- 当前 `chat()` 内的 503 重试循环整体迁移到 `chatWithTools(...)` 内部；重试次数、backoff 规则与现状保持一致，不因 Step 1 合同收敛而丢失
- Gemini function-call response 不提供稳定的内置 ID，因此 mapper 在解析 `ToolCallRequest` 时统一生成 UUID 作为 `callId`

建议拆分的 helper 方法：

```java
List<Content> buildContents(List<AgentMessage> messages);

GenerateContentConfig buildConfig(
    List<AgentMessage> messages,
    List<ToolDefinition> tools
);

AgentStepResult toAgentStepResult(GenerateContentResponse response);
```

`toAgentStepResult(...)` 的判定顺序：

1. 响应包含 provider-native tool call → 解析为 `ToolCallRequest`
2. 否则提取文本 → `FinalText`
3. 两者都不存在 → 抛出 `IllegalStateException`

现有 `buildContents(List<LlmChatMessage>)` 与 `buildConfig(List<LlmChatMessage>)` 测试可保留思路，但要迁移为 agent-aware 版本。

### 6.6 Zhipu 实现边界

`ZhipuLlmClient` 扩展方式与 Gemini 相同，但允许显式 degrade：

- `TextAgentMessage`：映射为 Zhipu chat messages
- `ToolCallAgentMessage` / `ToolResultAgentMessage`：在原生 tool 支持可用时映射为对应 tool history
- `ToolDefinition`：在原生支持可用时映射为 function/tool declaration
- 原生支持不可用时：不返回 `ToolCallRequest`，只返回 `FinalText`
- Zhipu response 若提供原生 `tool_call.id` 则直接沿用；若当前 SDK 返回结构未提供该字段，则同样由 mapper 生成 UUID 作为 `callId`
- 当前空消息校验错误文本从 `GeminiLlmClient.NON_SYSTEM_MESSAGE_ERROR` 的跨类引用中收敛出来，提到 `LlmClient` 公共常量或 `ZhipuLlmClient` 本类常量，避免 provider 之间直接耦合实现细节

建议拆分的 helper 方法：

```java
ChatCompletionCreateParams buildRequest(
    List<AgentMessage> messages,
    List<ToolDefinition> tools
);

List<ChatMessage> toZhipuMessages(List<AgentMessage> messages);

AgentStepResult toAgentStepResult(ChatCompletionResponse response);
```

为了保持 Step 1 边界，provider 能力检测应封装在 `ZhipuLlmClient` 内部，例如：

- 当前模型名是否在已知支持集合内
- 当前 SDK 返回结构中是否存在 tool-call 字段

判断细节不向上暴露成公共接口，也不新增全局配置项。

### 6.7 现有服务调用方的兼容边界

Step 1 不要求以下服务改成直接使用 `chatWithTools(...)`：

- `LlmChatService`
- `LlmExplanationService`

两者继续构造 `List<LlmChatMessage>` 并调用 `chat(...)`。这意味着：

- chat 路径行为应与当前保持一致
- explanation 路径行为应与当前保持一致
- VoiceChatService 通过 `LlmChatService` 间接保持不变

agent loop 的真正接线仍由 Step 3 / Step 4 完成。

---

## 7. File Impact

### 7.1 新增

| 文件 | 说明 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/ToolDefinition.java` | 工具 schema 合同 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/ToolCall.java` | 工具调用 carrier |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/ToolResult.java` | 工具结果 carrier |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentStepResult.java` | provider 单步结果密封接口 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/ToolCallRequest.java` | 继续调工具的结果类型 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/FinalText.java` | 最终文本结果类型 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentMessage.java` | agent 内部消息密封接口 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/TextAgentMessage.java` | 文本消息 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/ToolCallAgentMessage.java` | tool call 历史消息 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/ToolResultAgentMessage.java` | tool result 历史消息 |
| `backend/map-service/src/test/java/com/whut/map/map_service/llm/agent/AgentContractTypesTest.java` | 合同类型校验测试 |

### 7.2 修改

| 文件 | 改动摘要 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClient.java` | 新增 `chatWithTools(...)`，将 `chat(...)` 改为 default compatibility facade，并收敛空消息错误常量 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/client/GeminiLlmClient.java` | 改为实现 provider-neutral agent contract，并将现有 503 重试迁移到 `chatWithTools(...)` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/client/ZhipuLlmClient.java` | 改为实现 provider-neutral agent contract，保留 degraded fallback，并去除对 Gemini 实现类的错误常量跨类引用 |
| `backend/map-service/src/test/java/com/whut/map/map_service/llm/client/GeminiLlmClientTest.java` | 增加 tool-aware 映射与解析测试 |
| `backend/map-service/src/test/java/com/whut/map/map_service/llm/client/ZhipuLlmClientTest.java` | 增加 tool-aware 映射、降级与兼容测试 |

### 7.3 不在本步骤修改

| 文件 | 原因 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java` | 继续走 `chat(...)` 兼容路径 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmExplanationService.java` | 继续走 `chat(...)` 兼容路径 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/memory/ConversationMemory.java` | 内部 agent 消息暂不写入 memory |

---

## 8. Implementation Order

### Phase 1：合同类型落地

- 新增 `ToolDefinition`、`ToolCall`、`ToolResult`
- 新增 `AgentStepResult`、`ToolCallRequest`、`FinalText`
- 新增 `AgentMessage` 及三种 message record

### Phase 2：`LlmClient` 兼容 facade

- 修改 `LlmClient`
- 将 `chat(...)` 变为 default method
- 写基础 contract test，验证空工具目录下只接受 `FinalText`

### Phase 3：Gemini 适配

- 扩展 request 组装
- 扩展 response 解析
- 补齐 Gemini 测试

### Phase 4：Zhipu 适配

- 扩展 request 组装
- 实现 native tool / degraded final-text 两条路径
- 补齐 Zhipu 测试

实施顺序上先完成 Gemini，再处理 Zhipu，可降低第一次落地的复杂度。Step 1 的 DoD 以“两 provider 合同都可用”为准，不能只交付单 provider。

---

## 9. Test Plan

### 9.1 `AgentContractTypesTest`

- `ToolDefinition` 拒绝空 `name` / `description`
- `ToolCall` / `ToolResult` 拒绝空 `callId` / `toolName`
- `TextAgentMessage` 拒绝空文本
- `ToolCallAgentMessage` / `ToolResultAgentMessage` 拒绝 `null ObjectNode`

### 9.2 `LlmClient` 兼容测试

- fake provider 返回 `FinalText` 时，`chat(List<LlmChatMessage>)` 正常返回文本
- fake provider 在空工具目录下返回 `ToolCallRequest` 时，`chat(...)` 抛 `IllegalStateException`
- `generateText(...)` 仍通过 `chat(...)` 生效

### 9.3 `GeminiLlmClientTest`

- system 文本仍进入 `systemInstruction`
- `TextAgentMessage(USER/ASSISTANT)` 保留轮次顺序
- `ToolDefinition` 被映射进 Gemini tool declaration
- provider-native tool call 响应被解析为 `ToolCallRequest`
- Gemini tool-call response 的 `callId` 为 mapper 生成的 UUID
- 普通文本响应被解析为 `FinalText`
- 503 `ServerException` 在 `chatWithTools(...)` 路径下继续按现有策略重试
- 仅 system 文本且无其它输入时仍拒绝请求

### 9.4 `ZhipuLlmClientTest`

- `TextAgentMessage` 角色映射正确
- tool-enabled 请求在原生支持路径下可构造
- 原生 response 提供 tool-call ID 时，`ToolCallRequest.callId` 沿用该值；不提供时生成 UUID
- 原生支持不可用时，降级路径返回 `FinalText`
- 空消息列表继续拒绝请求

### 9.5 现有行为回归

- `LlmChatServiceTest` 不需要改行为断言；仅在必要时调整 mock 以适配 `LlmClient` default method
- `LlmExplanationServiceTest` 不需要改行为断言；仅确保其仍经 `chat(...)` 成功返回文本

---

## 11. Implementation Deviations

### 11.1 `GeminiLlmClient.callModel(...)` 抽取为 package-private 方法

- **偏离点**：计划未提及此方法抽取；实现中将 `geminiClient.models.generateContent(...)` 封装为 `callModel(model, contents, config)` 方法。
- **原因**：`Client` 是 `final` 类，无法使用 Mockito mock；抽取该方法允许测试通过子类覆写来验证 503 重试逻辑，不引入任何外部依赖。
- **类型**：`better-approach`
- **影响**：无接口变化，无跨步骤影响。

### 11.2 `LlmExplanationServiceTest` 同步更新

- **偏离点**：计划的 7.2 修改文件列表未包含 `LlmExplanationServiceTest`；实现中需向该文件的 `StubLlmClient` 添加 `chatWithTools(...)` 以满足接口编译。
- **原因**：`doc-code-inconsistency`——所有实现 `LlmClient` 的类必须实现新增的抽象方法，计划列表遗漏了该测试文件。
- **影响**：无行为变化，仅添加 `throw new UnsupportedOperationException(...)` 作为未使用路径的占位。

### 11.3 `ObjectMapper` 注入方式

- **偏离点**：计划未明确 `ObjectMapper` 的注入方式；实现采用 `@RequiredArgsConstructor` 构造函数注入，测试侧传入 `new ObjectMapper()`。
- **原因**：与项目现有 Spring 惯例一致，避免静态单例。
- **影响**：Gemini / Zhipu 两个 provider 的测试构造函数多一个参数。

---

## 10. Constraints And Risks

- Step 1 只统一合同，不保证两 provider 在 tool 调用能力上完全等价；Zhipu 允许 degraded final-text path，这是总览已接受的 trade-off
- 若把 `ToolDefinition.parametersJsonSchema` 设计成字符串而不是结构化对象，会把 Step 2 的 registry 和 Step 3 的 orchestrator 推回到重复 parse；因此本步骤必须把结构载体定死
- `chat(...)` default facade 是兼容关键路径；若 provider 仍保留各自的旧 `chat(...)` 实现，会重新分叉逻辑，后续维护成本会再次抬升
- `AgentMessage` 是内部消息模型，不得直接泄露到 WebSocket / SSE 协议或 `ConversationMemory`。否则会提前破坏 Step 4 的边界
