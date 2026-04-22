# v1.0 Milestone Sourcebook

> 文档状态：draft
> 最后更新：2026-04-17
> 用途：作为 `v1.0` 的共用原始材料索引稿，集中列出当前真值文档、历史材料、代码入口与待补外部资料。
> 非目标：本文不是架构总览，不处理 step 拆分，也不替代 `../ARCHITECTURE.md`、`../EVENT_SCHEMA.md`、`../frontend-design.md` 的当前真值。

## 1. 当前项目真值入口

- [`../../PROJECT_STATUS.md`](../../PROJECT_STATUS.md)：milestone 状态真值；当前记录为 `v0.9` 已完成、`v1.0` 进入 implementation milestone，`agent` 为主线，`hydrology` / `weather` 为并行 track。
- [`../../CURRENT_SYSTEM_OVERVIEW.md`](../../CURRENT_SYSTEM_OVERVIEW.md)：当前系统实现导览、模块定位与文档分层入口。
- [`../README.md`](../README.md)：`docs/` 下当前真值 / 规划 / 历史归档的边界说明。
- [`README.md`](./README.md)：`v1.0` 里程碑总览与 track 边界说明。
- [`VISUAL_UPGRADE_REFERENCE.md`](./VISUAL_UPGRADE_REFERENCE.md)：2026-04-18 前端视觉补丁的采纳与裁剪记录；用于追溯天气条占位与 agent 工具区未落地原因。
- [`../ARCHITECTURE.md`](../ARCHITECTURE.md)：系统稳定架构边界、主链路与模块职责。
- [`../EVENT_SCHEMA.md`](../EVENT_SCHEMA.md)：当前 `risk` SSE 与 `chat` WebSocket 协议真值。
- [`../frontend-design.md`](../frontend-design.md)：前端当前架构、AI 工作区边界、协议消费方式与交互约束。
- [`../ADR_AND_REVIEW_FINDINGS.md`](../ADR_AND_REVIEW_FINDINGS.md)：已沉淀的实现结论、review finding 与 trade-off。
- [`../TODO.md`](../TODO.md)：仅记录“未实现且未挂到当前有效实施链”的 backlog；已进入 `v1.0` 各 track plan / step 的事项不再重复登记。

## 2. 与 v1.0 直接相关的原始需求来源

- [`../TODO.md`](../TODO.md)：当前不再重复列出已挂载于 `v1.0` 的 agent / hydrology / weather 主线事项；与本里程碑直接相关的已挂载工作应分别以 [`agent/AGENT_LOOP_PLAN.md`](./agent/AGENT_LOOP_PLAN.md)、[`hydrology/HYDROLOGY_PLAN.md`](./hydrology/HYDROLOGY_PLAN.md)、[`weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md) 为准。
- [`../EVENT_SCHEMA.md`](../EVENT_SCHEMA.md)：当前协议尚未定义 agent/advisory 专用事件；后续若扩展，必须以现有 `CHAT_REPLY` / `EXPLANATION` / `ERROR` 边界为起点。
- [`../frontend-design.md`](../frontend-design.md)：当前前端已具备 AI 工作区壳层、解释卡片、消息编辑与目标选择交互，但尚无结构化 advisory、水文专题、天气专题的稳定消费契约。
- [`../ADR_AND_REVIEW_FINDINGS.md`](../ADR_AND_REVIEW_FINDINGS.md)：包含对上下文注入、解释缓存、消息编辑与前端消费边界的稳定结论，可直接作为 `v1.0` 各 track 的约束材料。

## 2.1 当前 track 对应关系

- [`agent/AGENT_LOOP_PLAN.md`](./agent/AGENT_LOOP_PLAN.md)：agent 主线总 plan。
- [`hydrology/HYDROLOGY_PLAN.md`](./hydrology/HYDROLOGY_PLAN.md)：水文并行 track 总 plan。
- [`weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md)：天气并行 track 总 plan。

## 3. 历史材料入口

### 3.1 v0.6 LLM Explanation

- [`../history/v0.6-llm-explanation/llm-design/llm-module-v1.md`](../history/v0.6-llm-explanation/llm-design/llm-module-v1.md)：早期 LLM 解释模块设计草案。
- [`../history/v0.6-llm-explanation/llm-design/llm-module-v2.md`](../history/v0.6-llm-explanation/llm-design/llm-module-v2.md)：包含 `LLM Semantic Advisory Layer` 等较早的 advisory 方向设想。

### 3.2 v0.7 LLM Enhancement

- [`../history/v0.7-llm-enhancement/LLM_ROADMAP.md`](../history/v0.7-llm-enhancement/LLM_ROADMAP.md)：`v0.7` 的阶段路线。
- [`../history/v0.7-llm-enhancement/LLM_ENHANCEMENT_PLAN.md`](../history/v0.7-llm-enhancement/LLM_ENHANCEMENT_PLAN.md)：`LlmClient`、prompt 管理、上下文注入、memory、解释链路的总体规划。
- [`../history/v0.7-llm-enhancement/llm-enhancement-step1.md`](../history/v0.7-llm-enhancement/llm-enhancement-step1.md)：`LlmClient` 抽象与 provider 解耦。
- [`../history/v0.7-llm-enhancement/llm-enhancement-step2.md`](../history/v0.7-llm-enhancement/llm-enhancement-step2.md)：prompt 模板外置与管理。
- [`../history/v0.7-llm-enhancement/llm-enhancement-step3-1.md`](../history/v0.7-llm-enhancement/llm-enhancement-step3-1.md)：风险上下文注入。
- [`../history/v0.7-llm-enhancement/llm-enhancement-step3-2.md`](../history/v0.7-llm-enhancement/llm-enhancement-step3-2.md)：多轮对话 memory。
- [`../history/v0.7-llm-enhancement/llm-enhancement-step4.md`](../history/v0.7-llm-enhancement/llm-enhancement-step4.md)：解释生成链路与解耦。
- [`../history/v0.7-llm-enhancement/llm-enhancement-step5.md`](../history/v0.7-llm-enhancement/llm-enhancement-step5.md)：后续增强项与收尾。
- [`../history/v0.7-llm-enhancement/llm-enhancement-step-final.md`](../history/v0.7-llm-enhancement/llm-enhancement-step-final.md)：阶段收口记录。

### 3.3 v0.9 Frontend Enhancement

- [`../history/v0.9-frontend-enhancement/FRONTEND_ENHANCEMENT_PALN.md`](../history/v0.9-frontend-enhancement/FRONTEND_ENHANCEMENT_PALN.md)：前端 AI 工作区与后续 advisory / generative UI 的前置准备。
- [`../history/v0.9-frontend-enhancement/step1.md`](../history/v0.9-frontend-enhancement/step1.md)：布局壳层与空间分配。
- [`../history/v0.9-frontend-enhancement/step2.md`](../history/v0.9-frontend-enhancement/step2.md)：状态、入口与交互边界。
- [`../history/v0.9-frontend-enhancement/step3.md`](../history/v0.9-frontend-enhancement/step3.md)：消息、解释卡片与上下文联动。
- [`../history/v0.9-frontend-enhancement/step4.md`](../history/v0.9-frontend-enhancement/step4.md)：最后一条消息编辑、选择目标注入与解释缓存消费边界。
- [`../history/v0.9-frontend-enhancement/visual-optimization.md`](../history/v0.9-frontend-enhancement/visual-optimization.md)：视觉层优化与展示留白。

## 4. 当前后端代码入口

### 4.1 WebSocket 入口与当前对话协议

- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java)：`CHAT` / `SPEECH` / `CLEAR_HISTORY` 的统一入口与分发点。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatUplinkEnvelope.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatUplinkEnvelope.java)：上行 envelope。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatRequestPayload.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatRequestPayload.java)：文本聊天 payload，已包含 `selected_target_ids` 与 `edit_last_user_message`。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/SpeechRequestPayload.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/SpeechRequestPayload.java)：语音请求 payload。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatDownlinkEnvelope.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatDownlinkEnvelope.java)：下行 envelope。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatReplyPayload.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatReplyPayload.java)：当前 `CHAT_REPLY` 载荷。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/SpeechTranscriptPayload.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/SpeechTranscriptPayload.java)：`SPEECH_TRANSCRIPT` 载荷。

### 4.2 LLM 调用编排

- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java)：当前 chat 主编排；负责 prompt、上下文、memory 与 `edit_last_user_message` 替换策略。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/VoiceChatService.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/VoiceChatService.java)：语音转写后进入 chat 的桥接层。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmTriggerService.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmTriggerService.java)：当前 explanation 触发点；可作为未来 advisory / agent 触发边界参考。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmExplanationService.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmExplanationService.java)：当前风险解释生成服务。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatRequest.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatRequest.java)：chat 服务内部请求对象。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmVoiceRequest.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmVoiceRequest.java)：voice 服务内部请求对象。

### 4.3 Prompt、上下文与记忆

- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/prompt/PromptTemplateService.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/prompt/PromptTemplateService.java)：当前 prompt 模板加载入口。
- [`../../backend/map-service/src/main/resources/prompts/system-chat.txt`](../../backend/map-service/src/main/resources/prompts/system-chat.txt)：当前 chat system prompt。
- [`../../backend/map-service/src/main/resources/prompts/system-risk-explanation.txt`](../../backend/map-service/src/main/resources/prompts/system-risk-explanation.txt)：当前 explanation system prompt。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextHolder.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextHolder.java)：最新风险上下文快照持有者。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextFormatter.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextFormatter.java)：风险上下文格式化。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/ExplanationCache.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/ExplanationCache.java)：解释缓存。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java)：消费风险事件并刷新上下文 / 缓存。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskContextAssembler.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskContextAssembler.java)：风险 DTO 到 LLM 上下文的装配。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/memory/ConversationMemory.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/memory/ConversationMemory.java)：多轮记忆、TTL、裁剪与最后一轮替换语义。

### 4.4 Provider 与未来 Agent Loop 扩展点

- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClient.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClient.java)：当前 provider 抽象；未来 function calling / tool invocation 的自然扩展边界。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/client/GeminiLlmClient.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/client/GeminiLlmClient.java)：Gemini 实现。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/client/ZhipuLlmClient.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/client/ZhipuLlmClient.java)：Zhipu 实现。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java)：provider、timeout、模型与开关配置入口。

### 4.5 Agent 未来可能读取的风险侧入口

- [`../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java)：实时风险主链路。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/RiskObjectAssembler.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/RiskObjectAssembler.java)：风险对象组装出口。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java)：当前风险流对外接口。
- [`../../backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/DerivedTargetStateStore.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/DerivedTargetStateStore.java)：目标派生状态缓存，可作为后续查询工具候选数据源。

## 5. 当前前端代码入口

- [`../../frontend/src/types/schema.d.ts`](../../frontend/src/types/schema.d.ts)：前端协议类型入口。
- [`../../frontend/src/services/chatWsService.ts`](../../frontend/src/services/chatWsService.ts)：聊天 WebSocket 客户端。
- [`../../frontend/src/services/riskSseService.ts`](../../frontend/src/services/riskSseService.ts)：风险 SSE 客户端。
- [`../../frontend/src/store/useAiCenterStore.ts`](../../frontend/src/store/useAiCenterStore.ts)：AI 工作区核心状态。
- [`../../frontend/src/store/useRiskStore.ts`](../../frontend/src/store/useRiskStore.ts)：风险对象与解释数据消费状态。
- [`../../frontend/src/components/Dashboard/ChatComposer.tsx`](../../frontend/src/components/Dashboard/ChatComposer.tsx)：消息输入、语音入口与消息编辑触发点。
- [`../../frontend/src/components/Dashboard/ChatMessageList.tsx`](../../frontend/src/components/Dashboard/ChatMessageList.tsx)：消息渲染与编辑交互。
- [`../../frontend/src/components/Dashboard/RiskExplanationPanel.tsx`](../../frontend/src/components/Dashboard/RiskExplanationPanel.tsx)：解释卡片展示。
- [`../../frontend/src/components/Dashboard/TargetsPanel.tsx`](../../frontend/src/components/Dashboard/TargetsPanel.tsx)：目标选择与面板联动。
- [`../../frontend/src/components/Dashboard/StatusPanel.tsx`](../../frontend/src/components/Dashboard/StatusPanel.tsx)：连接状态与基础运行状态展示。

## 6. 已知实现边界

- 当前协议只定义了 `RISK_UPDATE`、`EXPLANATION`、`CHAT_REPLY`、`SPEECH_TRANSCRIPT`、`ERROR`、`CLEAR_HISTORY_ACK`；尚无 agent / advisory 专用事件，见 [`../EVENT_SCHEMA.md`](../EVENT_SCHEMA.md)。
- 当前 `LlmClient` 抽象仍是“给定消息列表 -> 返回文本回复”；尚未提供 tool catalog、function calling 结果回填或多步 loop 编排，见 [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClient.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClient.java)。
- 当前 `LlmChatService` 已具备风险上下文注入、按 `selected_target_ids` 的目标补充、解释缓存拼接与最后一轮 user message 非破坏式替换语义，见 [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java)。
- 当前实时风险上下文刷新与 explanation cache 有稳定事实入口，但它们服务于 chat / explanation，不等于 agent query tool 已存在，见 [`../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java`](../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java)。
- 当前前端已经有 AI 工作区壳层与解释卡片消费位，但尚未消费结构化 advisory，也没有 generative UI 契约，见 [`../frontend-design.md`](../frontend-design.md) 与 [`../../frontend/src/store/useAiCenterStore.ts`](../../frontend/src/store/useAiCenterStore.ts)。
- 仓库内尚不存在法规语料索引、历史事件知识库、图数据库接入层或统一 retrieval API。

## 7. 仓库外仍需收集的原始材料

- COLREGS / 海事法规条文的可用文本源、许可范围与更新频率。
- 历史碰撞 / 近失事件数据集、字段规范与场景标签体系。
- GraphRAG 的实体与关系草案：船型、相遇态势、规则条款、操纵动作、案例证据、时间与海域上下文。
- 图存储 / 检索实现候选与部署约束。
- 外部知识的版本管理、可追溯引用与落库方式。

## 8. 当前检索方向约束

- `GraphRAG` 是默认目标，不以“先普通 RAG、以后再说”作为预设路线。
- 只有当普通 RAG 的切分、标识符、索引结构与检索接口可以无缝扩展为 GraphRAG 时，才允许将普通 RAG 作为过渡实施。
- 后续若写 step plan，应先证明“过渡方案不会导致重新建库、重新切 chunk、重新定义检索接口”。

## 9. 可由本文继续派生的后续文档

- `scope` 稿：明确 `v1.0` 的 in-scope / out-of-scope。
- step-plan 补齐稿：继续为 `agent` Step 0–5、`hydrology` Step 2–3、`weather` Step 2–3 补齐可执行 step 文档。
- advisory 协议草稿：确定是否扩展现有事件还是新增事件类型。
- 外部知识建模稿：明确 GraphRAG 所需语料、实体关系与引用策略。
