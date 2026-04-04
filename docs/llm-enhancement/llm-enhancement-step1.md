## Step 1 最小收口规划

### Summary
以 `LlmClient` 升级为切入点，先把 LLM 的公共输入模型和 provider 接口收口到 `llm/` 一级目录下；本步不引入 prompt 管理、上下文注入、记忆管理，也不做 transport/pipeline 解耦重构。

### Key Changes
- 新建包结构 `com.whut.map.map_service.llm.client` 与 `com.whut.map.map_service.llm.dto`。
- 在 `llm/dto` 新增：
  - `ChatRole { SYSTEM, USER, ASSISTANT }`
  - `ChatMessage(ChatRole role, String content)`
- 将现有 LLM 专用 DTO 一并迁入 `llm/dto`：
  - `LlmRiskContext`
  - `LlmRiskOwnShipContext`
  - `LlmRiskTargetContext`
  - `LlmExplanation`
- 将现有 LLM client 一并迁入 `llm/client`：
  - `LlmClient`
  - `GeminiLlmClient`
  - `ZhipuLlmClient`
- 扩展 `LlmClient` 接口：
  - 保留 `String generateText(String prompt)`
  - 新增 `String chat(List<ChatMessage> messages)`
  - `generateText` 改为默认兼容入口，内部委托为单条 `USER` 消息
- `GeminiLlmClient` 与 `ZhipuLlmClient` 实现 `chat(...)`，各自完成 `ChatMessage` 到 provider SDK 请求结构的映射。
- 当前 `service/llm/*` 包先不迁移到 `llm/service`；只改 import 和调用方式，避免 Step 1 变成大范围包重构。
- 当前 `dto/websocket/MessageRole` 保留，不复用到 LLM 内部模型。

### Constraints
- Step 1 不新增 `PromptTemplateService`、`RiskContextHolder`、`ConversationMemory`、发布回调接口等后续步骤类。
- Step 1 不修改事件协议，不修改 WebSocket/SSE payload 结构。
- Step 1 不改变 `LlmChatService`、`LlmExplanationService` 的对外行为，只替换底层调用入口到 `chat(...)`。
- 如果 Gemini SDK 的 `systemInstruction` 映射实现成本明显高于当前收益，本步允许先将多条消息线性映射为 contents，并记录为 Step 2 调整项；但 Zhipu 侧必须保留角色映射。

### Test Plan
- `LlmChatServiceTest` 继续通过，改为验证新接口兼容旧调用路径。
- 新增/调整 `LlmClient` 相关单测，覆盖：
  - `generateText()` 是否正确委托为单条 `USER` 消息
  - `ZhipuLlmClient.chat(...)` 的 role 映射
  - `GeminiLlmClient.chat(...)` 对多消息输入的基本处理
- 回归验证 `VoiceChatServiceTest`，确认语音转文本后聊天链路行为不变。
- 编译通过，包迁移后的 import 无残留。

### Assumptions
- “边增强边收口”的本步落点是先收口 `client + dto`，`service` 迁移延后到后续步骤。
- 现有 `dto/llm` 属于真正的 LLM 内部模型，允许在 Step 1 一次性迁入 `llm/dto`。
- `MessageRole` 仍是协议层角色枚举，不与 `ChatRole` 合并。
