# Agent Step 4C：Task-Level Model Routing And Frontend Provider Selection

> 文档状态：active
> 最后更新：2026-04-27
> 执行状态：planned
> 所属 track：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md)
> 对应总览：`AGENT_LOOP_PLAN.md` Appendix A.7
> 目标：将当前 `@Qualifier("zhipu"|"gemini")` 硬编码任务路由改为任务级动态路由，并在前端 AI 中心提供受后端能力声明约束的 provider 选择。

---

## 1. Summary

当前已完成阶段一任务分离：

- `risk explanation` 固定使用 Zhipu
- `chat / agent` 固定使用 Gemini
- `LlmExplanationService`、`LlmChatService`、`AgentLoopOrchestrator` 通过 `@Qualifier("zhipu"|"gemini")` 直接注入具体 `LlmClient`

Step 4C 实现第二阶段：任务级模型路由与前端 provider 选择。后端不再让业务服务直接持有某一个 provider client，而是通过 `LlmClientRegistry` 按任务类型选择 provider。前端不新增 `GET /api/llm/providers` 主路径；provider 可用性并入 Step 4A 已落地的 WebSocket `CAPABILITY` 消息。

最终交互模型：

1. WebSocket 建立后，后端发送一次扩展后的 `CAPABILITY`
2. `CAPABILITY` 同时声明 chat / agent / speech 能力，以及各 provider 对 `explanation`、`chat`、`agent` 任务的可用性
3. 前端 AI 中心设置面板按能力声明渲染 `chat` 与 `risk explanation` provider 选择器
4. 前端通过 WebSocket 设置消息提交 provider 选择
5. 后端在当前运行时选择中使用指定 provider；非法或不可用 provider 返回防御性错误，不静默回退

---

## 2. Current State And Step Delta

### 2.1 Backend Current State

当前配置文件已经记录阶段一语义：

```properties
# explanation -> zhipu; chat/agent -> gemini
# Phase 1 task routing is hard-coded, so both provider keys are required when llm.enabled=true.
```

实际注入点：

- `LlmExplanationService`：`@Qualifier("zhipu")`
- `LlmChatService`：`@Qualifier("gemini")`
- `AgentLoopOrchestrator`：`@Qualifier("gemini")`
- `AdvisoryService` 当前填充 `provider = "gemini"`

这些硬编码让阶段一可控，但无法在运行时切换，也无法让前端根据 provider 可用性展示选择器。

### 2.2 Frontend Current State

Step 4A 已实现连接级 `CAPABILITY` 握手：

- `ChatDownlinkType` 已包含 `CAPABILITY`
- `ChatCapabilityPayload` 包含 `chat_available`、`agent_available`、`speech_transcription_available`
- `chatWsService` 在连接建立后等待 capability，超时则关闭连接并重连
- `RiskExplanationPanel` 已消费 capability 来控制 Chat / Agent / speech 入口

因此 Step 4C 不再新增 `GET /api/llm/providers` 作为主路径。新增 REST API 会复制 capability 语义，并且仍不能替代 WebSocket readiness guard。Provider 能力声明应合并到现有 `CAPABILITY`。

### 2.3 API Alternative Decision

AI 建议中的 `GET /api/llm/providers` 不作为本步骤主方案。原因：

- provider 可用性直接影响 WebSocket chat / agent 入口，已经属于连接建立后的 capability 语义
- 前端在收到 `CAPABILITY` 前本来就不得发送 chat / agent 请求；把 provider 能力拆到 REST API 会引入第二套初始化顺序
- 当前系统没有独立的全站设置页必须在 WebSocket 连接前展示 provider 详情
- `CAPABILITY` 已经实现超时、重连和旧连接迟到消息防护，复用它比新增 API 更少协议面

REST API 只作为 deferred 选项：若后续出现“不建立 chat WebSocket 也要查看全局 LLM provider 状态”的后台管理页面，再新增只读 HTTP 端点，并以同一个 provider capability service 为数据源。

---

## 3. In Scope

### 3.1 Backend

- `LlmProperties` 增加 `explanationProvider` / `chatProvider`
- `chatProvider` 同时作为 chat agent / advisory agent 的默认 provider；本步骤不新增独立 `agentProvider`
- 新增 `LlmProvider` 枚举或等价值对象，固定合法值：`gemini`、`zhipu`
- 新增 `LlmTaskType`：`EXPLANATION`、`CHAT`、`AGENT`
- 新增 `LlmClientRegistry`，封装 provider id 到 `LlmClient` bean 的映射
- 移除业务服务对具体 provider 的 `@Qualifier` 依赖
- `LlmExplanationService` 按 `EXPLANATION` 任务选择 provider
- `LlmChatService` 按 `CHAT` 任务选择 provider
- `AgentLoopOrchestrator` 按 `AGENT` 任务选择 provider，或由调用方传入已选 client
- `CAPABILITY` payload 增加 provider availability、supported tasks、quota status 和当前有效选择
- 新增 WebSocket provider selection 设置消息与 ACK
- 配置校验从“两 provider key 都必须存在”改为“被配置为可选或当前选择的 provider 必须具备必要配置”

### 3.2 Frontend

- AI 中心设置面板增加 provider 选择器
- `chat` selector 控制普通 chat 与 agent chat 的 provider
- `risk explanation` selector 控制后续系统 explanation 的 provider
- selector 选项受 `CAPABILITY.llm_providers` 限制，不显示或禁用不可用 provider
- 切换 provider 后通过 WebSocket 发送设置消息
- 收到 ACK 后更新本地有效选择；收到 ERROR 时恢复上一次选择并显示错误状态

### 3.3 Documentation And Schema

- 更新 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)：扩展 `CAPABILITY`，新增 provider selection uplink / downlink
- 更新 [`../../../frontend/src/types/schema.d.ts`](../../../frontend/src/types/schema.d.ts)：同步协议类型
- 本文作为 Step 4C 的实施边界
- [`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md) Appendix A 记录插入步骤与 API 方案取舍

---

## 4. Out Of Scope

- not doing：新增 `GET /api/llm/providers` 主路径；provider 能力声明并入 `CAPABILITY`
- not doing：把 `CAPABILITY` 改造成 HTTP API；现有 WebSocket 握手继续保留
- not doing：为 advisory 单独增加 provider selector；advisory agent 暂时继承 `chatProvider`
- not doing：真正的 provider 配额实时探活；本步骤只声明配置可用性与静态任务支持，quota status 可为 `UNKNOWN`
- not doing：多用户隔离下的 per-user provider 偏好；当前前端选择按单操作员运行台语义更新运行时选择
- not doing：把 provider 选择写入 `application-local.properties` 或其它本地配置文件
- deferred：只读 `GET /api/llm/providers`，触发条件是后续出现不依赖 chat WebSocket 的后台管理视图
- deferred：多客户端 provider 选择冲突处理，触发条件是“会话隔离与鉴权”进入实施链

---

## 5. Backend Design

### 5.1 Configuration

`LlmProperties` 增加：

```java
private LlmProvider explanationProvider = LlmProvider.ZHIPU;
private LlmProvider chatProvider = LlmProvider.GEMINI;
```

配置语义：

| 字段 | 默认值 | 影响范围 |
|---|---|---|
| `llm.explanation-provider` | `zhipu` | `LlmExplanationService` |
| `llm.chat-provider` | `gemini` | 普通 chat、chat agent、advisory agent |

`llm.provider` 若仍存在，只保留为兼容旧配置的 fallback，不再作为任务级路由主字段。若同时配置了任务级字段和旧 `llm.provider`，任务级字段优先。

两个任务级字段应直接使用 `LlmProvider` 枚举类型，而不是 `String`。非法配置值应在 Spring Boot configuration binding 阶段以 `BindException` 失败，早于业务 validator。

### 5.2 Provider Registry

新增组件：

```java
@Component
public class LlmClientRegistry {

    public Optional<LlmClient> find(LlmProvider provider);

    public LlmClient requireForTask(LlmTaskType taskType);

    public LlmProvider resolveProviderForTask(LlmTaskType taskType);

    public List<LlmProviderCapability> describeCapabilities();
}
```

职责：

- 持有 `Map<LlmProvider, LlmClient>`
- 读取当前 provider selection
- 校验 provider 是否支持指定 task
- 为 `CAPABILITY` 构造 provider 描述
- 在不可用时抛出明确异常，由调用方映射为 `LLM_DISABLED` 或 `INVALID_CHAT_REQUEST`

Provider 支持矩阵：

| provider | EXPLANATION | CHAT | AGENT |
|---|---|---|---|
| `gemini` | true | true | true |
| `zhipu` | true | true | degraded |

`zhipu` 的 `AGENT` 支持沿用 Step 1 的 degraded fallback 语义：若当前模型不支持 function calling，可返回 final text，但能力声明应标注 `degraded = true`，供前端提示。

Provider client bean 需要可选注册：`GeminiConfig` 与 `ZhipuConfig` 应按对应 API key 是否存在注册 client bean；`LlmClientRegistry` 通过 `List<LlmClient>` 或可选注入收集实际可用 provider。这样只配置一个 provider key 时应用仍可启动，registry 再校验当前 selection 所需 provider 是否已注册。

### 5.3 Runtime Selection Store

新增运行时选择组件：

```java
@Component
public class LlmProviderSelectionStore {

    public LlmProvider getSelection(LlmTaskType taskType);

    public void updateSelection(LlmTaskType taskType, LlmProvider provider);
}
```

初始化时从 `LlmProperties.explanationProvider` 与 `LlmProperties.chatProvider` 读取默认值。前端通过 WebSocket 设置消息更新该 store。当前系统没有用户会话隔离，因此该 store 是运行时全局状态；这与现有全局 SSE risk explanation 语义一致。

`getSelection(LlmTaskType)` 的映射规则：

| task type | selection source |
|---|---|
| `EXPLANATION` | `explanationProvider` |
| `CHAT` | `chatProvider` |
| `AGENT` | `chatProvider` |

因此，当用户把 `chat_provider` 切换为 Zhipu 时，普通 chat、chat agent 和 advisory agent 都使用 Zhipu；其中 agent path 在 capability 中标记为 degraded。

如果未来引入 per-user SSE / WebSocket session，`LlmProviderSelectionStore` 应拆成 session-level selection 与 global default selection。本步骤不做该拆分。

### 5.4 Service Routing

`LlmExplanationService` 不再注入 `@Qualifier("zhipu") LlmClient`，改为：

```java
LlmClient client = llmClientRegistry.requireForTask(LlmTaskType.EXPLANATION);
LlmProvider provider = llmClientRegistry.resolveProviderForTask(LlmTaskType.EXPLANATION);
```

`LlmChatService` 普通 chat path 使用 `LlmTaskType.CHAT`。`AgentLoopOrchestrator` 支持 `LlmTaskType.AGENT`，推荐把 provider 选择放在 orchestrator 内部，避免每个调用方重复选择逻辑：

```java
AgentLoopResult run(
        LlmTaskType taskType,
        AgentSnapshot snapshot,
        List<AgentMessage> initialMessages,
        int maxIterations,
        AgentStepSink stepSink
);
```

`AdvisoryService` 调用 orchestrator 时传入 `LlmTaskType.AGENT`。因此 advisory 暂时继承 `chatProvider`，不会新增独立 selector。

`AgentLoopResult.Completed` 必须增加实际 provider 字段：

```java
record Completed(String finalText, int iterations, int toolCallCount, String finalizingStepId, String provider) {}
```

`AgentLoopOrchestrator` 在 loop 启动时从 `LlmClientRegistry` 解析 client 与 provider，并在 completed 结果中填入实际 provider。`AdvisoryService` 与 `LlmChatService` 使用该字段填充 `ADVISORY.provider` / `CHAT_REPLY.provider`，不得再硬编码 `"gemini"`。

### 5.5 Fallback Rule

运行时 fallback 只在 provider 不可用且存在明确 fallback provider 时发生。规则：

1. 若用户显式选择 provider，且该 provider 对任务不可用，返回错误，不静默 fallback
2. 若配置默认 provider 不可用，且另一个 provider 可用并支持该任务，可以 fallback，并在 payload provider 字段中填入实际 provider
3. fallback 必须记录 warn 日志
4. `CAPABILITY.effective_provider_selection` 必须反映当前实际选择或 fallback 后选择

该规则避免用户在 UI 中选择 Zhipu 后实际被静默路由到 Gemini。

---

## 6. Protocol Contract

### 6.1 Extend `CAPABILITY`

`ChatCapabilityPayload` 增加：

```ts
export type LlmProviderId = 'gemini' | 'zhipu';
export type LlmTaskType = 'explanation' | 'chat' | 'agent';
export type LlmQuotaStatus = 'UNKNOWN' | 'AVAILABLE' | 'LIMITED' | 'EXHAUSTED';

export interface LlmProviderCapability {
  provider: LlmProviderId;
  display_name: string;
  available: boolean;
  supported_tasks: LlmTaskType[];
  degraded_tasks?: LlmTaskType[];
  quota_status: LlmQuotaStatus;
  disabled_reason?: string;
}

export interface LlmProviderSelection {
  explanation_provider: LlmProviderId;
  chat_provider: LlmProviderId;
}

export interface ChatCapabilityPayload {
  event_id: string;
  chat_available: boolean;
  agent_available: boolean;
  speech_transcription_available: boolean;
  disabled_reasons?: Record<string, string | null>;
  llm_providers: LlmProviderCapability[];
  effective_provider_selection: LlmProviderSelection;
  provider_selection_mutable: boolean;
  timestamp: string;
}
```

发送规则保持 Step 4A 不变：WebSocket 连接建立后发送一次 `CAPABILITY`，前端在收到前不得发送依赖能力判断的请求。

本步骤应同时补齐当前前后端能力 payload 的不一致：TypeScript 已有 `disabled_reasons`，后端 `ChatCapabilityPayload` 也应增加对应字段，并在 chat / agent / speech 不可用时填入原因。

`provider_selection_mutable` 本步骤固定为 `true`。该字段预留给后续只读部署模式；在真正引入禁用运行时切换的配置前，不增加额外开关或复杂判断。

### 6.2 Provider Selection Uplink

新增 `ChatUplinkType`：

```ts
export type ChatUplinkType =
  | 'PING'
  | 'CHAT'
  | 'SPEECH'
  | 'CLEAR_HISTORY'
  | 'SET_LLM_PROVIDER_SELECTION';
```

Payload：

```ts
export interface SetLlmProviderSelectionPayload {
  event_id: string;
  explanation_provider?: LlmProviderId;
  chat_provider?: LlmProviderId;
}
```

兼容规则：

- 字段均可选，但至少必须提供一个
- 未提供的字段保持当前选择不变
- provider 不存在、不可用或不支持任务时，后端返回 `ERROR`，`error_code = INVALID_CHAT_REQUEST`
- 成功后后端发送 `LLM_PROVIDER_SELECTION` ACK

### 6.3 Provider Selection Downlink

新增 `ChatDownlinkType`：

```ts
export type ChatDownlinkType =
  | 'PONG'
  | 'CAPABILITY'
  | 'LLM_PROVIDER_SELECTION'
  | 'CHAT_REPLY'
  | 'AGENT_STEP'
  | 'SPEECH_TRANSCRIPT'
  | 'ERROR'
  | 'CLEAR_HISTORY_ACK';
```

ACK payload：

```ts
export interface LlmProviderSelectionPayload {
  event_id: string;
  reply_to_event_id: string;
  effective_provider_selection: LlmProviderSelection;
  timestamp: string;
}
```

`CAPABILITY` 不在同一连接内重复发送。选择变更通过 `LLM_PROVIDER_SELECTION` ACK 更新前端 store；下次重连时，新的 `CAPABILITY` 反映最新选择。

---

## 7. Frontend Design

### 7.1 Store Shape

`useAiCenterStore` 增加：

```ts
providerCapabilities: LlmProviderCapability[];
providerSelection: LlmProviderSelection | null;
providerSelectionPending: boolean;
setProviderSelection: (selection: Partial<LlmProviderSelection>) => void;
```

收到 `CAPABILITY` 后初始化 `providerCapabilities` 与 `providerSelection`。收到 `LLM_PROVIDER_SELECTION` 后更新有效选择并清除 pending 状态。

### 7.2 Settings Panel

AI 中心设置面板新增两个 selector：

| Selector | 写入字段 | 说明 |
|---|---|---|
| Chat provider | `chat_provider` | 普通 chat、agent chat、advisory agent 暂时共用 |
| Risk explanation provider | `explanation_provider` | 后续系统生成 `EXPLANATION` 时使用 |

可选项来自 `providerCapabilities`：

- `available = false` 的 provider 禁用，并显示 `disabled_reason`
- 不支持目标 task 的 provider 不显示或禁用
- `degraded_tasks` 包含目标 task 时，展示“降级支持”提示
- `provider_selection_mutable` 本步骤由后端固定返回 `true`；前端可以保留 `false` 时禁用 selector 的防御性分支，但本步骤不设计触发该分支的运行配置

切换交互：

1. 用户选择新 provider
2. 前端立即进入 pending 状态，但不改变 effective label
3. WebSocket 发送 `SET_LLM_PROVIDER_SELECTION`
4. 收到 `LLM_PROVIDER_SELECTION` 后更新 effective label
5. 收到 `ERROR` 后恢复原选择并显示错误

### 7.3 Request Path Behavior

`CHAT` 请求不需要携带 provider 字段。后端按当前 selection store 路由。这样可以避免每条消息都带 provider override，并使语音、agent 和普通 chat 共用同一 selection。

Risk explanation 是系统驱动 SSE 事件，前端 selection 影响后续生成，不会重算已经发布的 explanation。已存在的 explanation 卡片继续展示其原始 `provider` 字段。

---

## 8. File And Module Impact

### 8.1 Backend Modified Files

| 文件 | 改动 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java` | 增加 `explanationProvider` / `chatProvider` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmConfigurationValidator.java` | 校验任务级 provider 配置与 API key |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/config/GeminiConfig.java` | 按 Gemini API key 条件注册 provider bean |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/config/ZhipuConfig.java` | 按 Zhipu API key 条件注册 provider bean |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentLoopResult.java` | `Completed` 增加实际 provider 字段 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmExplanationService.java` | 移除具体 provider qualifier，按 `EXPLANATION` 任务选 client |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java` | 普通 chat 按 `CHAT` 任务选 client |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentLoopOrchestrator.java` | 按 `AGENT` 任务选 client，返回实际 provider |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/advisory/AdvisoryService.java` | provider 字段使用 orchestrator 实际 provider |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatMessageType.java` | 新增 `SET_LLM_PROVIDER_SELECTION` / `LLM_PROVIDER_SELECTION` 类型 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatCapabilityPayload.java` | 增加 provider capability、effective selection、`disabled_reasons` 和 `provider_selection_mutable` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java` | `CAPABILITY` 增加 provider 能力；处理 selection 设置消息 |
| `backend/map-service/src/main/resources/application.properties` | 更新阶段一硬编码注释与默认任务级路由字段 |

### 8.2 Backend New Files

| 文件 | 用途 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmProvider.java` | provider id 枚举 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmTaskType.java` | `EXPLANATION` / `CHAT` / `AGENT` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClientRegistry.java` | provider -> client 映射与能力声明 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmProviderCapability.java` | 后端 capability DTO |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmProviderSelectionStore.java` | 运行时 provider selection |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/SetLlmProviderSelectionPayload.java` | WebSocket 上行设置 payload |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/LlmProviderSelectionPayload.java` | WebSocket 下行 ACK payload |

### 8.3 Frontend Modified Files

| 文件 | 改动 |
|---|---|
| `frontend/src/types/schema.d.ts` | 增加 provider capability、selection uplink / downlink 类型 |
| `frontend/src/services/chatWsService.ts` | 发送 `SET_LLM_PROVIDER_SELECTION`，消费 `LLM_PROVIDER_SELECTION` |
| `frontend/src/store/useAiCenterStore.ts` | 保存 provider capabilities、selection 和 pending 状态 |
| `frontend/src/components/Dashboard/RiskExplanationPanel.tsx` | 挂载 AI 中心设置面板；若实施时组件继续膨胀，应只负责组合子组件 |
| `frontend/src/components/Dashboard/AiCenterSettingsPanel.tsx` | 可选新增，承载 provider selectors 与后续 AI 设置项 |
| `frontend/src/types/aiCenter.ts` | 如设置面板拆分类型，则增加 provider selection view model |

### 8.4 Documentation Files

| 文件 | 改动 |
|---|---|
| `docs/EVENT_SCHEMA.md` | 扩展 `CAPABILITY`，新增 provider selection 消息 |
| `docs/v1.0/agent/AGENT_LOOP_PLAN.md` | Appendix A 记录 Step 4C 插入和 API 方案取舍 |
| `docs/TODO.md` | 移除已挂入 Step 4C 的“任务级模型路由与前端模型选择” backlog |

---

## 9. Implementation Order

### Phase 1：后端 registry 与配置

- 新增 `LlmProvider` / `LlmTaskType`
- `LlmProperties` 增加任务级 provider 字段
- 新增 `LlmProviderSelectionStore`
- 新增 `LlmClientRegistry`
- 调整 `GeminiConfig` / `ZhipuConfig` 为可选 provider bean 注册
- 更新配置校验

### Phase 2：服务路由替换

- `LlmExplanationService` 改为 registry 选择
- `LlmChatService` 改为 registry 选择
- `AgentLoopResult.Completed` 增加实际 provider 字段
- `AgentLoopOrchestrator` 改为任务级 provider 选择，并在 completed 结果中返回实际 provider
- `AdvisoryService` 使用实际 provider 填充 payload
- 移除业务服务中的具体 provider `@Qualifier`

### Phase 3：协议扩展

- 扩展 `ChatCapabilityPayload`
- 新增 `SET_LLM_PROVIDER_SELECTION` 上行
- 新增 `LLM_PROVIDER_SELECTION` 下行 ACK
- 更新 `EVENT_SCHEMA.md` 与 `schema.d.ts`

### Phase 4：前端设置 UI

- `useAiCenterStore` 保存 provider capabilities 与 selection
- `chatWsService` 实现 provider selection request / ack
- AI 中心设置面板增加两个 selector
- 错误时恢复原选择

### Phase 5：测试与收口

- 后端 registry、validator、service routing 测试
- WebSocket capability 与 selection 测试
- 前端 store、service 和 selector 测试
- 从 `docs/TODO.md` 移除本步骤接管事项

---

## 10. Test Plan

### 10.1 Backend Unit Tests

- 默认配置下 `EXPLANATION -> zhipu`
- 默认配置下 `CHAT -> gemini`
- 默认配置下 `AGENT -> gemini`
- 配置 `llm.explanation-provider=gemini` 后 explanation 使用 Gemini client
- 配置 `llm.chat-provider=zhipu` 后 chat 使用 Zhipu client
- provider 不存在时配置校验失败
- 当前选择 provider 缺少 API key 时配置校验失败
- 用户显式选择不可用 provider 时返回 `INVALID_CHAT_REQUEST`
- `CAPABILITY` 包含 `llm_providers` 与 `effective_provider_selection`
- `CAPABILITY` 包含后端补齐的 `disabled_reasons`
- `provider_selection_mutable` 在本步骤固定为 `true`
- `SET_LLM_PROVIDER_SELECTION` 成功后更新 selection store 并发送 ACK
- `SET_LLM_PROVIDER_SELECTION` 部分字段更新时，未提供字段保持不变
- `AdvisoryService` 发布 payload 使用 orchestrator 返回的实际 provider

### 10.2 Frontend Unit Tests

- 收到 `CAPABILITY` 后初始化 provider capability list
- Chat selector 只显示支持 `chat` 的可用 provider
- Risk explanation selector 只显示支持 `explanation` 的可用 provider
- `degraded_tasks` 对应选项展示降级提示
- selector pending 时不提前改变 effective label
- 收到 `LLM_PROVIDER_SELECTION` 后更新 effective selection
- 收到 `ERROR` 后恢复原 selection
- `provider_selection_mutable` 为 `true` 时两个 selector 按 provider capability 正常启用
- 重连后新的 `CAPABILITY` 覆盖旧 provider capability state

### 10.3 Manual Validation

1. 后端以默认配置启动，WebSocket 连接后 `CAPABILITY` 显示 `explanation_provider = zhipu`、`chat_provider = gemini`
2. AI 中心设置面板显示 Gemini / Zhipu 的可用性和任务支持
3. 将 chat provider 切换为 Zhipu 后发送普通 chat，`CHAT_REPLY.provider` 为 `zhipu`
4. 将 chat provider 切回 Gemini 后发送 Agent 请求，工具路径仍可运行，`CHAT_REPLY.provider` 为 `gemini`
5. 将 risk explanation provider 切换为 Gemini 后触发新的 risk explanation，`EXPLANATION.provider` 为 `gemini`
6. 已存在 explanation 卡片仍显示其原始 provider，不被重写
7. 模拟不可用 provider，selector 禁用；强行发送设置消息时后端返回 `INVALID_CHAT_REQUEST`

---

## 11. Constraints And Risks

- **不要复制 capability 真值**：provider 可用性合并到 `CAPABILITY`，避免 REST API 与 WebSocket 握手各维护一套初始化语义
- **用户显式选择不能静默 fallback**：显式选择失败必须返回错误，否则 UI 展示与实际 provider 会不一致
- **risk explanation 仍是全局 SSE 语义**：当前没有 per-user SSE，所以 explanation provider selection 是单操作员运行时选择，不是多用户偏好
- **advisory provider 暂不独立**：advisory agent 继承 `chatProvider`，避免 Step 4C 过早扩展到第三个 selector
- **provider 切换不自动清空会话历史**：切换 provider 后，`ConversationMemory` 中已有历史继续保留。跨 provider 混合历史的输出质量不作为本步骤保证；前端应在切换成功后提示用户必要时执行 `CLEAR_HISTORY`
- **quota status 不做实时探活**：本步骤可以返回 `UNKNOWN`；不要在连接建立时阻塞等待 provider 网络健康检查
- **配置文件不能被前端修改**：WebSocket selection 只更新运行时状态，不写本地 properties，也不伪造 API key

---

## 12. Deviations

本步骤是对 `AGENT_LOOP_PLAN.md` 的插入步骤，不来自原 §4 阶段拆分正文。对应偏离已记录在 `AGENT_LOOP_PLAN.md` Appendix A.7。

版本收敛时，`AGENT_LOOP_PLAN.md` 正文应把 Step 4C 纳入 Step 4A / Step 4B 与 Step 5 之间，并说明 provider 能力声明通过 WebSocket `CAPABILITY` 扩展完成，而不是新增 `GET /api/llm/providers` 主路径。
