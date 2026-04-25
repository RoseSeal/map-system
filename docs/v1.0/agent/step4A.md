# Agent Step 4A：Explicit Agent Mode + Runtime Visibility

> 文档状态：completed
> 最后更新：2026-04-25
> 执行状态：completed
> 所属 track：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md)
> 对应总览：`AGENT_LOOP_PLAN.md` Appendix A.3 / A.4 / A.5
> 目标：在 Step 5 前修正 chat agent 激活语义，补齐演示所需的显式模式切换、agent 工具调用可见性与强约束本船跟随能力。

---

## 1. Summary

Step 4A 是 Step 4 与 Step 5 之间的插入步骤。该步骤不引入 COLREGS GraphRAG，不新增规则查询工具，也不改变 Step 5 的目标；它只处理演示前必须先稳定的交互和协议边界：

1. `CHAT` WebSocket payload 新增 `agent_mode` 字段，由前端显式选择 `CHAT` 或 `AGENT`
2. `selected_target_ids` 与 agent 激活完全解耦，只作为可选上下文，不再决定路由
3. 后端 agent loop 每完成一次工具调用后，通过 WebSocket 推送 `AGENT_STEP`
4. 前端聊天框按 `AGENT_STEP.reply_to_event_id` 展示工具执行状态
5. 地图新增强约束本船跟随模式，同时保留当前 `flyTo` 浅约束跟随能力

Step 4A 完成后，Step 5 可以继续专注于 COLREGS 图谱、`QueryRegulatoryContextTool` 与 `EvaluateManeuverTool`。Step 5 不再承担“让 agent 模式可演示、可观察、可手动切换”的职责。

---

## 2. Current State And Step Delta

### 2.1 Step 4 当前语义

Step 4 已实现 selection-constrained chat agent path：

```java
boolean useAgentPath = llmProperties.isAgentModeEnabled()
        && !request.selectedTargetIds().isEmpty();
```

该设计把 `selected_target_ids` 同时作为上下文提示和 agent 激活条件。此行为与本步骤目标冲突：前端需要独立的 Chat / Agent 模式切换，选中目标不应隐式改变 LLM 路径。

### 2.2 前端当前语义

前端当前会在发送 chat / speech 请求时，把已选目标作为 `selected_target_ids` 透传。该字段现在承担两层含义：

- UI 选择状态：用户希望对某些目标提问
- 后端 agent 激活条件：Step 4 中 `selected_target_ids` 非空会触发 agent path

Step 4A 后，只保留第一层含义。路径选择由 `agent_mode` 单独控制。

### 2.3 工具调用可见性当前缺口

当前 WebSocket 下行类型只有 `CHAT_REPLY`、`SPEECH_TRANSCRIPT`、`ERROR`、`CLEAR_HISTORY_ACK` 等结果类事件。Agent loop 内部工具调用完成前，前端无法知道正在执行哪个工具，也无法展示成功或失败状态。

Step 4A 新增 `AGENT_STEP`，只用于 chat WebSocket 的 agent 请求过程展示。它不是 SSE advisory 流式协议，也不替代 Step 3 的 `ADVISORY` 事件。

### 2.4 地图跟随当前语义

`MapContainer` 当前存在基于 `ownShip` 的 `flyTo` 浅约束跟随：当地图中心与本船位置偏差超过经纬度阈值时，触发一次 `flyTo`。该行为适合“距离过远时回到本船附近”，但不满足演示所需的强约束跟随：

- 本船需要保持在屏幕中心附近的小范围内
- 跟随应随每帧位置变化平滑更新
- 用户应能显式关闭强约束，避免镜头抢占手动操作

Step 4A 保留现有浅约束能力，并新增强约束模式。

---

## 3. In Scope

### 3.1 Backend

- `ChatRequestPayload` 新增 `agent_mode`
- `LlmChatRequest` 新增 `agentMode`
- 新增 agent mode 枚举，建议值为 `CHAT`、`AGENT`
- WebSocket 连接建立后一次性下发运行能力消息，声明 chat、agent、语音转录能力是否可用
- `LlmChatService` 路由改为由 `agent_mode` 决定
- `selected_target_ids` 不再参与 agent 路由判断
- `ChatWebSocketHandler` 支持发送 `AGENT_STEP`
- `AgentLoopOrchestrator` 或其调用链支持在每次工具调用完成后发出 step callback
- `AGENT` 模式下即使 `selected_target_ids` 为空，也进入 agent path
- `CHAT` 模式下即使 `selected_target_ids` 非空，也走 Step 4 之前的普通 chat prompt 拼接路径

### 3.2 Frontend

- 聊天区新增 Chat / Agent 模式切换控件
- 前端消费连接建立时的一次性能力消息，并据此禁用不可用按钮
- `CHAT` 请求携带 `agent_mode`
- 前端 store 记录当前 assistant mode
- `chatWsService` 订阅并分发 `AGENT_STEP`
- 聊天消息列表在对应 pending 用户消息下展示 agent step 状态
- 地图工具区新增本船跟随模式控制
- 新增强约束本船跟随；保留现有浅约束 `flyTo` 逻辑

### 3.3 Documentation And Schema

- 更新 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)：新增 `agent_mode` 与 `AGENT_STEP`
- 更新 [`../../../frontend/src/types/schema.d.ts`](../../../frontend/src/types/schema.d.ts)：同步 TypeScript 协议类型
- 本文作为 Step 4A 的实施边界
- [`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md) Appendix A 记录插入步骤与 Step 4 语义修正

---

## 4. Out Of Scope

- not doing：Step 5 的 COLREGS GraphRAG、`QueryRegulatoryContextTool`、`EvaluateManeuverTool`
- not doing：把 `selected_target_ids` 作为 agent path 激活条件继续保留
- not doing：在 `CHAT_REPLY` 中塞入工具调用轨迹；工具状态必须走独立 `AGENT_STEP`
- not doing：SSE advisory 流式中间步骤；`AGENT_STEP` 仅服务 WebSocket chat agent path
- not doing：让普通 `CHAT` 模式显示伪造工具步骤；只有后端真实 agent loop 执行工具后才显示
- not doing：改变 `ConversationMemory` 写入边界；工具调用中间轮次仍不进入 conversation memory
- deferred：voice 请求是否也支持显式 `agent_mode`。本步骤优先处理 `CHAT` payload；若演示需要 voice agent mode，应在本步骤实施前明确追加，否则保持 voice 继承默认 `CHAT` 语义
- deferred：完整工具参数 / 返回结果详情展示。Step 4A 只展示工具名、运行状态和简短状态文案；参数和 payload 审计视图等有真实需求后再挂入后续 step 或 TODO

---

## 5. Protocol Contract

### 5.1 Uplink `agent_mode`

`CHAT` payload 新增字段：

```ts
export type ChatAgentMode = 'CHAT' | 'AGENT';

export interface ChatRequestPayload {
  conversation_id: string;
  event_id: string;
  content: string;
  agent_mode?: ChatAgentMode;
  selected_target_ids?: string[];
  edit_last_user_message?: boolean;
}
```

兼容规则：

- 缺省 `agent_mode` 视为 `CHAT`
- 非法 `agent_mode` 返回 `ERROR`，`error_code = INVALID_CHAT_REQUEST`
- `agent_mode = CHAT` 时，后端必须走普通 `llmClient.chat(messages)` 路径
- `agent_mode = AGENT` 时，后端必须走 `AgentLoopOrchestrator` 路径
- `selected_target_ids` 只影响 prompt 上下文，不影响路由

### 5.2 Connection Capability Announcement

WebSocket 连接建立后，后端必须主动发送一次能力消息，供前端决定按钮与输入框可用性。该消息只描述当前服务启动状态和配置状态，不代表某一次请求正在运行。

下行类型新增：

```ts
export type ChatDownlinkType =
  | 'PONG'
  | 'CAPABILITY'
  | 'CHAT_REPLY'
  | 'AGENT_STEP'
  | 'SPEECH_TRANSCRIPT'
  | 'ERROR'
  | 'CLEAR_HISTORY_ACK';
```

Payload：

```ts
export interface ChatCapabilityPayload {
  event_id: string;
  chat_available: boolean;
  agent_available: boolean;
  speech_transcription_available: boolean;
  disabled_reasons?: {
    chat?: string;
    agent?: string;
    speech_transcription?: string;
  };
  timestamp: string;
}
```

发送规则：

- `ChatWebSocketHandler.afterConnectionEstablished(...)` 中发送一次 `CAPABILITY`
- `chat_available` 表示普通 chat 路径可用
- `agent_available` 表示 `llm.agent-mode-enabled = true` 且后端具备 agent loop 依赖
- `speech_transcription_available` 表示语音转录依赖可用或配置允许启用语音转录入口
- 若某项不可用，`disabled_reasons` 应给出简短原因，供前端 tooltip 或状态提示使用

前端消费规则：

- `chat_available = false` 时，Chat 模式按钮不可用
- `agent_available = false` 时，Agent 模式按钮不可用
- `speech_transcription_available = false` 时，语音录制 / 转录相关按钮不可用
- 当 `chat_available = false` 且 `agent_available = false` 时，聊天框整体渲染为不可用状态，输入框、发送按钮和模式切换均禁用
- 若 `CAPABILITY` 尚未收到，前端应按未知能力处理：保持连接中状态，不主动发送 chat / agent 请求

该设计替代“用户点击后才通过请求错误得知能力不可用”的交互路径。不可用能力应在连接建立后即反映到 UI，而不是等用户点击后失败。

#### 5.2.1 Capability Receipt Guard

`CAPABILITY` 是 chat UI 的前置握手消息，不是普通状态提示。前端必须显式确保该消息已经收到：

1. WebSocket `onopen` 后，`chatWsService` 将 capability 状态重置为 `pending`
2. 同时启动一次 `CAPABILITY_TIMEOUT_MS` 计时器，建议默认 `3000ms`
3. 收到 `CAPABILITY` 后：
   - 清除计时器
   - 将 capability 状态置为 `ready`
   - 更新 `useAiCenterStore.chatCapability`
   - 允许 UI 按 capability 开启可用入口
4. 在计时器到期前，前端不得发送 `CHAT`、`SPEECH` 或 `CLEAR_HISTORY` 之外依赖能力判断的请求；实际 UI 上发送按钮、模式切换和语音按钮应保持不可用或连接中状态
5. 计时器到期仍未收到 `CAPABILITY` 时：
   - 将 capability 状态置为 `unavailable`
   - 将 chat、agent、speech transcription 均视为不可用
   - 显示“聊天能力初始化失败，请重新连接”一类状态
   - 主动关闭当前 WebSocket，让既有 reconnect 机制重新建立连接并重新等待 `CAPABILITY`

重连时必须重复上述流程。旧连接上的迟到 `CAPABILITY` 不得覆盖新连接状态；`chatWsService` 只接受当前 socket 实例收到的 capability 消息。

后端侧也需要保持该消息的强约束：

- `afterConnectionEstablished(...)` 设置消息大小限制后，应立即发送 `CAPABILITY`
- 如果 capability 构建失败，后端应发送 `ERROR` 后关闭连接，而不是让前端无限等待
- `CAPABILITY` 每个 WebSocket 连接只发送一次；连接级能力变化需要通过重连体现，不在本步骤内设计动态能力更新事件

### 5.3 Backend Routing

Step 4A 后的路由语义：

```java
boolean requestedAgentMode = request.agentMode() == ChatAgentMode.AGENT;
boolean useAgentPath = llmProperties.isAgentModeEnabled() && requestedAgentMode;
```

正常 UI 流程下，前端不会在 `agent_available = false` 时发送 `agent_mode = AGENT` 请求。后端仍需保留防御性校验：若收到不可用模式请求，返回 `ERROR`，`error_code = INVALID_CHAT_REQUEST`，并说明当前能力未启用。该错误只作为协议防御，不作为常规交互路径。

### 5.4 Downlink `AGENT_STEP`

`AGENT_STEP` 是 §5.2 中 `ChatDownlinkType` 的新增取值之一。本节只定义该事件的 payload，避免在文档内重复维护完整下行枚举。

```ts
export type AgentStepStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'FINALIZING';

export interface AgentStepPayload {
  event_id: string;
  conversation_id: string;
  reply_to_event_id: string;
  step_id: string;
  tool_name: string | null;
  status: AgentStepStatus;
  message: string;
  timestamp: string;
}
```

字段语义：

| 字段 | 说明 |
|---|---|
| `event_id` | 本次 step 下行事件 ID |
| `conversation_id` | 所属会话 |
| `reply_to_event_id` | 对应的用户请求 event ID，用于挂到 pending 用户消息下 |
| `step_id` | 单次请求内唯一的 step ID |
| `tool_name` | 工具名；`FINALIZING` 等非工具阶段可为 `null` |
| `status` | 当前阶段状态 |
| `message` | 前端展示文案，例如“正在读取风险快照”“工具调用完成”“正在整理态势” |
| `timestamp` | 服务端生成时间 |

### 5.5 Step Event Emission Rules

后端必须发送真实状态，不生成占位轨迹：

1. agent loop 决定调用工具后，发送 `RUNNING`
2. 工具返回 `ToolResult` 后，发送 `SUCCEEDED`
3. 工具执行抛出非预期异常或 registry 返回失败时，发送 `FAILED`
4. 所有工具轮次结束并准备最终回复时，发送一次 `FINALIZING`
5. 若 provider 首轮直接返回 `FinalText` 且没有工具调用，允许只发送 `FINALIZING`

失败路径不发送 `FINALIZING`。若 provider failure、`MaxIterationsExceeded` 或其他异常导致最终下发 `ERROR`，前端在收到 `ERROR` 时应把该 `reply_to_event_id` 下最后一个仍为 `RUNNING` 的 step 标记为 `FAILED`，并显示错误文案。这样可以避免 UI 停留在“正在调用工具”状态。

`AGENT_STEP` 不改变 `AgentLoopResult` 语义。最终回复仍由 `CHAT_REPLY` 承载，失败仍由 `ERROR` 承载。

---

## 6. Backend Design

### 6.1 Types

建议新增：

```java
public enum ChatAgentMode {
    CHAT,
    AGENT;

    public static ChatAgentMode fromNullable(String value) {
        return value == null || value.isBlank() ? CHAT : ChatAgentMode.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
```

`ChatRequestPayload` 增加：

```java
@JsonProperty("agent_mode")
private String agentMode;
```

`LlmChatRequest` 增加：

```java
ChatAgentMode agentMode
```

构造器中若为空则归一化为 `ChatAgentMode.CHAT`。

### 6.2 `LlmChatService` Routing

`handleChat` 的分支调整为：

```text
if request.agentMode == CHAT:
    run existing buildMessages -> llmClient.chat path

if request.agentMode == AGENT and advertised agent capability is unavailable:
    fail defensively with INVALID_CHAT_REQUEST

if request.agentMode == AGENT and llmProperties.agentModeEnabled == true:
    run agent chat path
```

普通 chat 路径继续使用 Step 4 前已有的 `buildMessages(request)` 拼接方式。若 `selected_target_ids` 非空，仍可按旧规则注入选中目标相关上下文，但这不再触发 agent loop。

Agent 路径中，`ChatAgentPromptBuilder` 可把 `selected_target_ids` 作为可选上下文提示写入 user message；为空时也必须能构造合法 agent 初始消息。system prompt 应引导模型在缺少目标上下文时主动调用工具获取场景态势，例如 `get_risk_snapshot` 或 `get_top_risk_targets`；但 chat agent 不强制每轮必须调用工具，`toolCallCount == 0` 仍按 Step 4 规则允许。

### 6.3 `AgentLoopOrchestrator` Step Callback

`AgentLoopOrchestrator.run(...)` 增加可选 step sink，避免 orchestrator 直接依赖 WebSocket handler：

```java
public AgentLoopResult run(
        AgentSnapshot snapshot,
        List<AgentMessage> initialMessages,
        int maxIterations,
        AgentStepSink stepSink
);
```

`AgentStepSink` 为函数式接口：

```java
@FunctionalInterface
public interface AgentStepSink {
    void accept(AgentStepEvent event);
}
```

普通 advisory path 可传入 no-op sink，避免 Step 4A 意外改变 Step 3 的 SSE advisory 行为。
Step 3 advisory 调用方应显式传入 `AgentStepSink.NOOP` 或等价 lambda。当前主要影响点是 `AdvisoryService` 中对 `AgentLoopOrchestrator.run(...)` 的调用；该路径不得向 WebSocket 发布 `AGENT_STEP`。

### 6.4 Capability Detection

`ChatCapabilityPayload` 的三个能力字段按启动期配置和 bean 可用性判断，不做网络探活：

| 字段 | 判断规则 |
|---|---|
| `chat_available` | `LlmChatService` 与当前 `LlmClient` bean 可用，且普通 chat 路径未被配置禁用 |
| `agent_available` | `llm.agent-mode-enabled = true`，且 `AgentLoopOrchestrator`、`AgentSnapshotFactory`、`AgentToolRegistry`、`ChatAgentPromptBuilder` 可用 |
| `speech_transcription_available` | `VoiceChatService`、`WhisperClient`、`AudioValidator`、`WhisperProperties` 可用，且 `whisper.url` 非空 |

`speech_transcription_available` 不要求在连接建立时调用 whisper 服务做实时健康检查；真正的上游不可达仍按现有 `TRANSCRIPTION_TIMEOUT` / `TRANSCRIPTION_FAILED` 请求级错误处理。若后续增加显式 `whisper.enabled` 或 transcription feature flag，应优先使用该配置决定 capability。

### 6.5 `ChatWebSocketHandler` Step Push

`ChatWebSocketHandler` 在调用 `llmChatService.handleChat(...)` 时，为 chat agent 请求传入 step sender。具体实现可以二选一：

- `LlmChatService.handleChat(...)` 新增一个可选 `Consumer<AgentStepEvent>` 参数
- 或新增 `handleChatWithSteps(...)`，只由 WebSocket chat path 使用

为保持已有调用方稳定，推荐新增重载：

```java
public void handleChat(
        LlmChatRequest request,
        Consumer<ChatReplyResult> onSuccess,
        BiConsumer<LlmErrorCode, String> onError,
        Consumer<AgentStepEvent> onStep
);
```

原三参方法委托到四参方法并传 no-op sink。

---

## 7. Frontend Design

### 7.1 Assistant Mode Store

`useAiCenterStore` 新增：

```ts
type AssistantMode = 'CHAT' | 'AGENT';

assistantMode: AssistantMode;
setAssistantMode: (mode: AssistantMode) => void;
```

默认值为 `CHAT`，保证刷新页面后不会意外进入 agent path。

发送 `CHAT` 请求时始终携带 `agent_mode`：

- `assistantMode = CHAT` → `agent_mode: 'CHAT'`
- `assistantMode = AGENT` → `agent_mode: 'AGENT'`

`selected_target_ids` 继续按当前选择状态发送，但不参与前端模式判断。

### 7.2 Mode Toggle UI

`RiskExplanationPanel` 的“智能决策助理”标题区增加 Chat / Agent 分段控件：

- Chat：普通对话，延迟低，使用现有 prompt 拼接路径
- Agent：工具增强推理，可能更慢，展示实时工具步骤

控件只改变 `assistantMode`，不自动选择或取消目标。

### 7.3 Agent Step State

`useAiCenterStore` 增加按用户请求事件归属的状态：

```ts
agentStepsByReplyToEventId: Record<string, AgentStepPayload[]>;
appendAgentStep: (payload: AgentStepPayload) => void;
clearAgentSteps: (replyToEventId: string) => void;
```

收到 `CHAT_REPLY` 或 `ERROR` 后，不立即删除 agent steps。步骤应保留在对应消息下，直到该消息被清理、重试替换或会话重置。

`edit_last_user_message = true` 的重试 / 编辑提交需要先清理旧步骤：前端在发送新的编辑请求前，应定位将被替换的上一轮 user message event id，并调用 `clearAgentSteps(prevEventId)`。新的请求使用新的 `event_id` 接收 `AGENT_STEP`，避免旧步骤和新步骤同时显示在同一条消息下。

`CAPABILITY_TIMEOUT_MS` 建议定义在 `frontend/src/config/constants.ts` 的 WebSocket / chat 常量区域，默认值为 `3000ms`。`chatWsService.ts` 只消费该常量，不在服务内部硬编码 timeout。

### 7.4 Chat Message Rendering

`ChatMessageList` 在 pending 用户消息或其后续 assistant 回复附近展示 agent step 状态：

- `RUNNING`：灰色或蓝色活动点，文案如“正在读取风险快照”
- `SUCCEEDED`：绿色点，文案如“已完成 get_top_risk_targets”
- `FAILED`：红色点，文案如“工具调用失败”
- `FINALIZING`：中性点，文案如“正在整理态势”

展示工具名时使用后端传入的 `tool_name`，但可通过前端映射表给常见工具提供中文标签：

| tool_name | 展示标签 |
|---|---|
| `get_risk_snapshot` | 读取风险快照 |
| `get_top_risk_targets` | 查询高风险目标 |
| `get_target_detail` | 查询目标详情 |
| `get_own_ship_state` | 读取本船状态 |

未识别工具显示原始工具名。

---

## 8. Strong Own-Ship Follow

### 8.1 Store Contract

`useMapSettingsStore` 新增跟随模式：

```ts
export type OwnShipFollowMode = 'OFF' | 'SOFT' | 'LOCKED';

followMode: OwnShipFollowMode;
setFollowMode: (mode: OwnShipFollowMode) => void;
```

默认值建议为 `SOFT`，以保留当前浅约束 `flyTo` 行为。演示时可切换为 `LOCKED`。

### 8.2 Mode Semantics

| 模式 | 行为 |
|---|---|
| `OFF` | 不自动移动镜头 |
| `SOFT` | 保留当前距离过大时 `flyTo` 回到本船附近的浅约束行为 |
| `LOCKED` | 本船保持在屏幕中心小范围内；每次 own ship 更新后平滑调整地图中心 |

`LOCKED` 使用屏幕像素 deadband，而不是经纬度阈值。建议 deadband 为 12 px。若本船屏幕位置偏离中心超过 deadband，则调用 `map.easeTo({ center, duration })`，duration 建议 300-600ms。

### 8.3 Manual Interaction

用户手动拖拽、缩放或旋转地图时，`LOCKED` 不应无限抢回控制权。建议规则：

- 用户拖拽地图时自动切换为 `SOFT`
- 用户点击 follow control 才重新进入 `LOCKED`
- `SOFT` 模式继续允许距离过远时回到本船附近

该规则既满足演示时的强约束跟随，也避免常规操作被镜头锁定破坏。

---

## 9. File And Module Impact

### 9.1 Backend Modified Files

| 文件 | 改动 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatRequest.java` | 新增 `agentMode` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java` | 路由改为显式 `agent_mode`；新增 step sink 传递 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/VoiceChatService.java` | 如构造 `LlmChatRequest`，需显式传默认 `CHAT` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatRequestPayload.java` | 新增 `agent_mode` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatMessageType.java` | 新增 `CAPABILITY`、`AGENT_STEP` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java` | 连接建立时发送 `CAPABILITY`；校验 `agent_mode`；发送 `AGENT_STEP` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentLoopOrchestrator.java` | 增加 step callback 或 no-op sink |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/advisory/AdvisoryService.java` | 调用 orchestrator 时传入 no-op step sink，保持 advisory path 不产生 WS step |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/chat/ChatAgentPromptBuilder.java` | 支持无 selected targets 的 agent 初始消息 |

### 9.2 Backend New Files

| 文件 | 用途 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/ChatAgentMode.java` | `CHAT` / `AGENT` 模式枚举 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentStepEvent.java` | 内部 agent step 事件 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentStepSink.java` | Orchestrator step callback |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatCapabilityPayload.java` | WebSocket 初始能力下行 payload |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/AgentStepPayload.java` | WebSocket 下行 payload |

### 9.3 Frontend Modified Files

| 文件 | 改动 |
|---|---|
| `frontend/src/types/schema.d.ts` | 新增 `ChatAgentMode`、`ChatCapabilityPayload`、`AgentStepPayload`、`CAPABILITY`、`AGENT_STEP` |
| `frontend/src/services/chatWsService.ts` | 发送 `agent_mode`；订阅 `CAPABILITY` 与 `AGENT_STEP`；实现 capability receipt guard |
| `frontend/src/store/useAiCenterStore.ts` | assistant mode、capability 与 agent step 状态 |
| `frontend/src/components/Dashboard/RiskExplanationPanel.tsx` | 增加 Chat / Agent 模式切换 |
| `frontend/src/components/Dashboard/ChatMessageList.tsx` | 渲染 agent step 状态 |
| `frontend/src/store/useMapSettingsStore.ts` | 增加 `followMode` |
| `frontend/src/components/Map/MapContainer.tsx` | 实现 `OFF` / `SOFT` / `LOCKED` 跟随 |

### 9.4 Documentation Files

| 文件 | 改动 |
|---|---|
| `docs/EVENT_SCHEMA.md` | 新增协议说明 |
| `docs/v1.0/agent/AGENT_LOOP_PLAN.md` | Active Deviations 记录 Step 4A 插入和 Step 4 语义修正 |
| `docs/TODO.md` | 本步骤 review 通过后，应移除被 Step 4A 接管的三项 TODO |

---

## 10. Implementation Order

### Phase 1：协议与后端路由

- 新增 `ChatAgentMode`
- `ChatRequestPayload` / `LlmChatRequest` 增加 `agent_mode`
- 新增 `CAPABILITY` 下行 payload，并在 WebSocket 连接建立时发送一次
- `ChatWebSocketHandler` 校验并透传 `agent_mode`
- `LlmChatService` 路由从 `selected_target_ids` 改为 `agent_mode`
- `AGENT` 请求但 capability 不可用时防御性返回 `INVALID_CHAT_REQUEST`

### Phase 2：Agent step callback

- 新增 `AgentStepEvent` / `AgentStepSink`
- `AgentLoopOrchestrator` 在工具调用前后发出 step event
- `AdvisoryService` 调用 orchestrator 时传入 no-op step sink
- `LlmChatService` 将 step event 转交给 WebSocket 层
- `ChatWebSocketHandler` 下发 `AGENT_STEP`

### Phase 3：前端模式与 step 展示

- `chatWsService` 实现 capability receipt guard：连接建立后等待 `CAPABILITY`，超时则禁用能力并触发重连
- `useAiCenterStore` 增加 `assistantMode`
- `RiskExplanationPanel` 增加模式切换
- `chatWsService` 发送 `agent_mode` 并分发 `AGENT_STEP`
- `useAiCenterStore` 记录 agent steps
- `ChatMessageList` 渲染工具执行状态

### Phase 4：强约束本船跟随

- `useMapSettingsStore` 增加 `followMode`
- `MapContainer` 保留 `SOFT` 的现有 `flyTo` 浅约束
- `LOCKED` 模式实现像素 deadband + `easeTo`
- 增加地图 follow control

### Phase 5：文档与测试收口

- 更新 `EVENT_SCHEMA.md`
- 更新 `schema.d.ts`
- 补齐后端和前端测试
- review 通过后，从 `docs/TODO.md` 移除 Step 4A 已接管事项

---

## 11. Test Plan

### 11.1 Backend Unit Tests

- `agent_mode` 缺省时归一化为 `CHAT`
- 非法 `agent_mode` 返回 `INVALID_CHAT_REQUEST`
- WebSocket 连接建立时发送一次 `CAPABILITY`
- `CAPABILITY` 正确反映 chat、agent、speech transcription 的可用性
- `agent_mode = CHAT` 且 `selected_target_ids` 非空时，仍走普通 chat path
- `agent_mode = AGENT` 且 `selected_target_ids` 为空时，仍走 agent path
- `agent_mode = AGENT` 但 capability 不可用时防御性返回 `INVALID_CHAT_REQUEST`
- agent path 工具调用成功时发送 `RUNNING` → `SUCCEEDED` → `FINALIZING`
- 工具调用失败时发送 `RUNNING` → `FAILED`，最终按现有失败规则返回 `ERROR`
- provider failure / max iterations 等失败路径不发送 `FINALIZING`
- advisory path 调用 orchestrator 时使用 no-op sink，不产生 WebSocket step 事件

### 11.2 Frontend Unit Tests

- assistant mode 默认 `CHAT`
- 未收到 `CAPABILITY` 前，chat / agent / voice 入口保持不可发送状态
- `CAPABILITY_TIMEOUT_MS` 到期仍未收到 capability 时，前端将 chat、agent、speech transcription 均置为不可用，并关闭连接触发重连
- 重连后 capability 状态重新进入 `pending`，旧连接迟到消息不得覆盖新连接状态
- capability 超时后，聊天框整体显示能力初始化失败或重连中状态
- `chat_available = false` 时 Chat 按钮不可用
- `agent_available = false` 时 Agent 按钮不可用
- `speech_transcription_available = false` 时语音按钮不可用
- `chat_available = false` 且 `agent_available = false` 时，聊天框整体不可用
- 切换 `AGENT` 后发送 payload 包含 `agent_mode: 'AGENT'`
- `CHAT` 模式下即使有 selected targets，payload 仍包含 `agent_mode: 'CHAT'`
- `chatWsService` 收到 `AGENT_STEP` 后触发对应 callback
- `useAiCenterStore.appendAgentStep` 按 `reply_to_event_id` 归档
- `ChatMessageList` 对 `RUNNING` / `SUCCEEDED` / `FAILED` / `FINALIZING` 显示对应状态
- 收到 `ERROR` 时，最后一个仍为 `RUNNING` 的 step 被标记为 `FAILED`
- `followMode = LOCKED` 时 `MapContainer` 调用平滑居中逻辑
- 用户拖拽地图后 `followMode` 从 `LOCKED` 降级到 `SOFT`

### 11.3 Manual Demo Validation

1. 后端以 `llm.agent-mode-enabled=true` 启动
2. WebSocket 初次连接后收到一次 `CAPABILITY`，Chat、Agent、语音按钮状态与后端能力一致
3. 未选择任何目标，切换 Agent 模式并发送问题，后端进入 agent path
4. 选择目标后切换 Chat 模式并发送问题，后端仍走普通 chat path
5. Agent 模式请求期间，聊天框实时显示工具调用状态
6. 工具成功显示绿点，工具失败显示红点
7. 最终回复仍以 `CHAT_REPLY` 出现在聊天框
8. 地图 `SOFT` 模式保留原有远距离回中行为
9. 地图 `LOCKED` 模式下，本船保持在屏幕中心附近并平滑移动
10. 手动拖拽地图后，强约束跟随停止抢占镜头

---

## 12. Constraints And Risks

- **协议兼容**：`agent_mode` 必须允许缺省并归一化为 `CHAT`，否则旧前端无法继续发送 chat 请求
- **能力发布必须先于交互**：前端应在收到 `CAPABILITY` 后再允许用户发送 chat / agent 请求，否则按钮可用状态会短暂误导用户
- **显式模式不可静默回退**：`AGENT` 请求在后端 capability 不可用时必须返回防御性错误；静默走普通 chat 会误导演示
- **工具步骤只能来自真实执行**：前端不得基于 pending 状态自行伪造工具名或成功状态
- **回调不能破坏 orchestrator 纯编排边界**：`AgentLoopOrchestrator` 只发出内部 step event，不直接持有 WebSocket session
- **前端状态归属必须按 `reply_to_event_id`**：同一会话中存在重试、编辑和迟到回复时，agent steps 不能挂到错误消息
- **强约束跟随可能影响地图操作**：`LOCKED` 必须有明确关闭和手动拖拽降级规则
- **Step 5 仍可独立演进**：Step 4A 不改变工具注册表的扩展方向，只补齐现有工具调用的可见性

---

## 13. Deviations

本步骤是对 `AGENT_LOOP_PLAN.md` 的插入步骤，不来自原 §4 阶段拆分正文。对应偏离已记录在 `AGENT_LOOP_PLAN.md` Appendix A：

- A.3：在 Step 4 与 Step 5 之间插入 Step 4A
- A.4：将 chat agent 激活条件从 `selected_target_ids` 改为显式 `agent_mode`
- A.5：将 chat agent 工具执行过程通过 WebSocket `AGENT_STEP` 暴露给前端

版本收敛时，`AGENT_LOOP_PLAN.md` 正文应把 Step 4A 纳入阶段拆分，并将 §3.7 的 selection-constrained 语义改写为 explicit user-selected agent mode。
