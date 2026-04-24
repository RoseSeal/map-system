# Agent Step 3：Advisory Path（主线）

> 文档状态：active
> 最后更新：2026-04-24
> 执行状态：completed
> 所属 track：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md)
> 对应总览：`AGENT_LOOP_PLAN.md` §4 Step 3
> 目标：实现完整的 advisory 生成链路，新增独立 `ADVISORY` SSE 事件类型，前端消费 advisory。

---

## 1. Summary

Step 3 将 Step 0 的冻结快照、Step 1 的 provider tool-calling 合同、Step 2 的只读工具注册表串成系统驱动的 advisory 主链路。该步骤的产物不是替换现有 `EXPLANATION`，而是新增独立的场景级 `ADVISORY` 事件：

1. `SceneRiskStateTracker` 触发 advisory 后，通过 `AdvisoryTriggerPort` 进入真实生成服务
2. `AdvisoryService` 持有 advisory 生命周期，构建任务输入并调用 `AgentLoopOrchestrator`
3. `AgentLoopOrchestrator` 在最大轮次内执行 LLM tool call、工具注册表分发和 tool result 回填
4. 最终输出映射为 `AdvisoryPayload`，通过版本时效性检查后经 `RiskStreamPublisher.publishAdvisory(...)` 下发
5. 前端通过 `ADVISORY` SSE 分支写入 `useRiskStore.activeAdvisory`，并在 AI 工作区展示独立 advisory 卡片

总览中的完成边界在本文件中保持不变：

- `ADVISORY` 事件可独立下发并被前端消费
- `evidence_items` 至少支持来自 Step 2 查询工具的数值与状态事实
- 不要求已集成 `QueryRegulatoryContextTool` 和 `EvaluateManeuverTool`
- Step 5 的规则依据与操纵假设评估属于 Step 3 之上的增强层，而不是 Step 3 的阻塞依赖

本步骤依赖 Step 0 + Step 1 + Step 2，不重新定义快照冻结、provider tool-calling 基础合同或内建工具 payload。

---

## 2. Current State And Step Delta

当前代码已经具备 Step 3 的前置骨架：

- `AgentSnapshot`、`AgentSnapshotFactory` 与 deep copier 已提供冻结读取边界
- `LlmClient.chatWithTools(List<AgentMessage>, List<ToolDefinition>)`、`AgentStepResult`、`ToolCallRequest`、`FinalText` 已提供 provider 单步调用合同
- `AgentToolRegistry.execute(ToolCall, AgentSnapshot)` 已提供统一工具分发
- `SceneRiskStateTracker` 已按 `llmProperties.advisory.enabled`、场景风险升级和 TCPA 阈值触发 `AdvisoryTriggerPort`
- 当前 `AdvisoryTriggerPort` 只有 `NoOpAdvisoryTriggerPort`，触发后立即释放 `generatingFlag`
- `RiskStreamPublisher`、`SseEventFactory`、`SseEventType` 仅支持 `RISK_UPDATE`、`EXPLANATION`、`ERROR`
- 前端 `riskSseService` 与 `useRiskStore` 仅消费 `RISK_UPDATE`、`EXPLANATION`、`ERROR`

因此，Step 3 的核心增量是把已有触发点接到真实 asynchronous advisory pipeline，并补齐后端 payload、SSE 协议、前端 store 与展示。

---

## 3. In Scope

### 3.1 后端 Advisory 生成链路

- 新增 `AgentLoopOrchestrator`，负责有界 tool loop，不直接发布 SSE
- 新增 `AdvisoryService`，实现 `AdvisoryTriggerPort`，负责异步生成、生命周期字段补齐、发布前时效性检查和失败上报
- 新增 advisory prompt 构造逻辑，约束 LLM 必须使用 Step 2 查询工具支撑 `evidence_items`
- 新增结构化 advisory 输出映射与校验，主路径使用 provider schema / JSON mode 能力，降级路径解析 final text 中的 JSON

### 3.2 后端 SSE 协议

- 新增 `AdvisoryPayload` 及其嵌套 record
- `SseEventType` 新增 `ADVISORY`
- `SseEventFactory` 支持构建 `ADVISORY` payload 的 event metadata
- `RiskStreamPublisher` 新增 `publishAdvisory(AdvisoryPayload)`
- `docs/EVENT_SCHEMA.md` 新增 `ADVISORY` 事件与 `ADVISORY_SCHEMA_FAILED` 错误码

### 3.3 前端消费与展示

- `schema.d.ts` 新增 `AdvisoryPayload`、嵌套类型和 `RiskSseEventType = 'ADVISORY'`
- `riskSseService.ts` 新增 `ADVISORY` listener 与 callback 注册
- `useRiskStore.ts` 新增 `activeAdvisory`、`archivedAdvisories`、`upsertAdvisory(...)`、`expireActiveAdvisory(...)` 与 selector
- 新增 `AdvisoryCard`，或在 `RiskExplanationPanel` 内创建独立 advisory 区块
- `valid_until` 到期后停止展示 active advisory；新 advisory 到达时将旧 advisory 标记为 `SUPERSEDED` 并归档

### 3.4 测试与文档

- 后端单元测试覆盖 loop 轮次、工具调用、schema 失败、版本过期和发布行为
- 前端单元测试覆盖 SSE 分发、store 生命周期和 advisory 卡片基础渲染
- `docs/EVENT_SCHEMA.md` 与 `frontend/src/types/schema.d.ts` 保持字段命名一致

---

## 4. Out Of Scope

- deferred：`QueryRegulatoryContextTool`、`EvaluateManeuverTool`、COLREGS evidence 与操纵假设 evidence，归属 Step 5
- deferred：Chat agent path、`selected_target_ids` 驱动的 agent loop、chat feature flag，归属 Step 4
- deferred：真实 tool execution trace / streaming advisory 过程展示，保留在 `docs/TODO.md` 的 agent backlog 中，触发条件是后续新增独立 `tool_calls` / `evidence_trace` 生命周期协议
- deferred：任务级模型路由与前端模型选择，保留在 `docs/TODO.md`，触发条件是后端 provider 能力声明与任务级路由设计完成
- not doing：替换或删除现有 `EXPLANATION` 事件
- not doing：把 tool call 中间消息写入 `ConversationMemory`
- not doing：为过期 advisory 建立持久化历史表；Step 3 仅维护前端运行态 active / archived 展示状态
- not doing：provider 不支持结构化输出时发布半结构化 advisory；两次解析失败时只发布 `ERROR` 或记录日志

Step 3 不需要向 `docs/TODO.md` 新增事项。所有延后项已由 Step 4、Step 5 或现有 TODO backlog 承接。

---

## 5. Key Decisions

### 5.1 `AdvisoryService` 实现 `AdvisoryTriggerPort`

`SceneRiskStateTracker` 已经通过 `AdvisoryTriggerPort` 隔离触发方和生成方。本步骤不修改该触发边界，而是新增真实实现替换 `NoOpAdvisoryTriggerPort`：

```java
@Component
class AdvisoryService implements AdvisoryTriggerPort {
    @Override
    public void onAdvisoryTrigger(AgentSnapshot snapshot, Runnable onComplete);
}
```

行为规则：

- 方法入口只接收已经冻结的 `AgentSnapshot`，不重新读取 live store
- 生成任务提交到 LLM executor 或专用 advisory executor，不能阻塞 Spring event listener 线程
- `onComplete.run()` 必须在成功、失败、丢弃、executor 拒绝等所有路径 exactly once 调用
- schema 失败发布 `ADVISORY_SCHEMA_FAILED` 错误；普通 provider 失败沿用 `LLM_REQUEST_FAILED` / `LLM_TIMEOUT` 映射

### 5.2 `AgentLoopOrchestrator` 只编排 loop，不拥有 advisory 生命周期

`AgentLoopOrchestrator` 的职责限定为“给定消息、工具、快照，返回最终文本或失败原因”。它不生成 `advisory_id`，不设置 `valid_until`，不判断 supersession，也不发布 SSE。

建议签名：

```java
public AgentLoopResult run(
        AgentSnapshot snapshot,
        List<AgentMessage> initialMessages,
        int maxIterations
);
```

`AgentLoopResult` 至少区分：

- `completed(finalText, iterations, toolCallCount)`
- `maxIterationsExceeded(iterations, toolCallCount)`
- `providerFailed(errorCode, message, cause)`
- `toolFailed(callId, toolName, message, cause)`

recoverable 工具错误由 Step 2 工具以 `ToolResult.payload` 返回，不视为 `toolFailed`。只有注册表执行发生非预期异常、provider 调用异常或循环合同破坏时，才终止 loop。

### 5.3 Loop 消息追加顺序固定

每一轮调用 `llmClient.chatWithTools(messages, registry.getToolDefinitions())` 后按结果处理。`maxIterations` 守卫位于每轮 provider 调用之前；当已完成的 provider 调用轮数达到上限时，直接返回 `maxIterationsExceeded(...)`：

```text
toolDefs = registry.getToolDefinitions()
iteration = 0
toolCallCount = 0

while iteration < maxIterations:
  result = llmClient.chatWithTools(messages, toolDefs)
  iteration++

  if result is ToolCallRequest:
    append ToolCallAgentMessage(callId, toolName, arguments)
    execute registry with ToolCall(callId, toolName, arguments)
    append ToolResultAgentMessage(callId, toolName, payload)
    toolCallCount++
    continue

  if result is FinalText:
    return completed(finalText, iteration, toolCallCount)

return maxIterationsExceeded(iteration, toolCallCount)
```

约束：

- `maxIterations` 默认使用 advisory 配置，建议初始值为 `5`
- 每轮最多处理一个 `ToolCallRequest`，与当前 Step 1 合同保持一致
- `ToolDefinition` 列表在单次 loop 开始时读取一次，保证同一 loop 内工具集合稳定
- tool result 保持结构化 JSON，不转换成人类摘要后再回填

### 5.4 Advisory 输出先校验再补齐生命周期字段

LLM 只负责生成语义字段，不允许生成服务端生命周期字段。后端负责补齐：

- `event_id`
- `advisory_id`
- `risk_object_id`
- `snapshot_version`
- `scope`
- `status`
- `supersedes_id`
- `valid_until`
- `provider`
- `timestamp`

LLM 可生成并必须通过校验的字段：

- `summary`
- `affected_targets`
- `recommended_action`
- `evidence_items`

`AdvisoryOutputParser` 读取 LLM 输出的语义字段时，忽略 `risk_level` 字段（若存在），不视为校验失败；最终 `risk_level` 始终由后端从 `AgentSnapshot` 的场景最高风险等级填充。

这种划分避免 provider 伪造生命周期信息，也让同一个 parser 可复用在 Gemini response schema、Zhipu JSON mode 和 text JSON fallback 上。

### 5.5 发布前版本时效性检查在 `AdvisoryService` 执行

`AgentSnapshot.snapshotVersion()` 与 `RiskContextHolder.getVersion()` 的差值超过配置阈值时，advisory 静默丢弃，不发布 `ADVISORY`。该路径仍调用 `onComplete.run()`。

配置建议放入现有 `LlmProperties.Advisory`：

```java
private int maxSnapshotVersionLag = 5;
private int maxIterations = 5;
private int validSeconds = 120;
```

`maxSnapshotVersionLag` 的度量为风险帧版本差，不是 wall-clock 秒数。若后续风险帧频率变化，该值需要结合实际延迟指标调整。

### 5.6 Supersession 由后端声明，前端执行

`AdvisoryService` 在内存中维护最后一次发布成功的 `advisory_id`。新 advisory 发布时：

- `supersedes_id` 填入上一条 active advisory 的 ID
- `status` 固定为 `ACTIVE`
- 前端收到后把本地旧 active advisory 标记为 `SUPERSEDED` 并移动到归档列表

后端不需要补发旧 advisory 的 `SUPERSEDED` 事件。该决策符合总览“每次新 advisory 发布时通过 `supersedes_id` 替代上一条”的生命周期规则，并避免新增双事件事务语义。

---

## 6. Payload Contract

### 6.1 Java record

建议在 `shared/dto/sse/` 下新增：

```java
public record AdvisoryPayload(
        String eventId,
        String advisoryId,
        String riskObjectId,
        long snapshotVersion,
        AdvisoryScope scope,
        AdvisoryStatus status,
        String supersedesId,
        String validUntil,
        RiskLevel riskLevel,
        String provider,
        String timestamp,
        String summary,
        List<String> affectedTargets,
        RecommendedAction recommendedAction,
        List<String> evidenceItems
) {}

public record RecommendedAction(
        AdvisoryActionType type,
        String description,
        AdvisoryUrgency urgency
) {}
```

Enums：

- `AdvisoryScope`：`SCENE`
- `AdvisoryStatus`：`ACTIVE`、`SUPERSEDED`
- `AdvisoryActionType`：`COURSE_CHANGE`、`SPEED_CHANGE`、`MAINTAIN_COURSE`、`MONITOR`、`UNKNOWN`
- `AdvisoryUrgency`：`LOW`、`MEDIUM`、`HIGH`、`IMMEDIATE`

### 6.2 JSON field names

SSE JSON 使用现有 snake_case 风格：

```json
{
  "event_id": "server-event-xxx",
  "advisory_id": "advisory-uuid",
  "risk_object_id": "risk-frame-id",
  "snapshot_version": 12345,
  "scope": "SCENE",
  "status": "ACTIVE",
  "supersedes_id": null,
  "valid_until": "2026-04-24T10:25:00Z",
  "risk_level": "ALARM",
  "provider": "gemini",
  "timestamp": "2026-04-24T10:23:00Z",
  "summary": "目标 413999001 进入紧急接近态势，建议立即采取避让动作。",
  "affected_targets": ["413999001"],
  "recommended_action": {
    "type": "COURSE_CHANGE",
    "description": "建议右转并持续监控 TCPA 变化。",
    "urgency": "IMMEDIATE"
  },
  "evidence_items": [
    "目标 413999001 DCPA 0.12 nm，低于 WARNING 阈值 0.5 nm。",
    "目标 413999001 TCPA 138 s，低于 300 s 紧急阈值。"
  ]
}
```

### 6.3 Validation rules

- `summary` 非空，建议前端显示时截断，后端不做过度长度裁剪
- `affected_targets` 非空，且每个 ID 必须存在于 `AgentSnapshot.riskContext().targets()`
- `recommended_action.description` 非空
- `evidence_items` 非空；Step 3 至少包含来自 Step 2 工具结果的数值或状态事实
- `risk_level` 由后端从 snapshot 当前最高风险等级填充，不接受 LLM 输出覆盖
- 任一必填字段不合法时，视为 schema failure，不发布 advisory

---

## 7. Prompt And Tool Strategy

### 7.1 Initial messages

初始消息由 `AdvisoryPromptBuilder` 构造，包含：

- system message：声明只允许基于 tool result 生成事实，禁止编造 CPA/TCPA、目标 ID、风险等级和规则条款
- user message：要求生成场景级 advisory，并说明可用工具名称
- snapshot hints：只包含 `snapshot_version`、场景最高风险等级、非 SAFE 目标数量，不注入完整自然语言风险上下文

不复用 `RiskContextFormatter` 的完整文本输出。原因是 Step 3 的 evidence 必须可追溯到 Step 2 tool result，而不是来自不可结构化审计的 prompt 文本。

### 7.2 Required baseline tool usage

Step 3 advisory loop 应至少促使模型调用：

1. `get_risk_snapshot`
2. `get_top_risk_targets`
3. 对最高风险目标调用 `get_target_detail`

若 provider 直接返回 final text 且 `AgentLoopResult.completed(...).toolCallCount() == 0`，`AdvisoryService` 应按 schema failure 处理，不发布 advisory。该规则保证 Step 3 的 `evidence_items` 不退化为纯 LLM 猜测，也避免在 `AdvisoryService` 中另行维护工具调用状态。

### 7.3 Evidence constraints

`evidence_items` 在 Step 3 中只能表达以下事实来源：

- 场景最高风险等级、目标数量、approaching 数量
- 目标 `dcpa_nm`、`tcpa_sec`、距离、相遇类型、风险分数、approaching 状态
- 本船位置、航速、航向和 safety domain 尺寸

COLREGS 规则依据、让路 / 直航责任判定、假设操纵后 CPA 变化均不在 Step 3 生成。若 LLM 输出这些内容，parser 应拒绝或清理后再校验；推荐实现为拒绝并发布 `ADVISORY_SCHEMA_FAILED`，避免静默改变安全语义。

---

## 8. File And Module Impact

### 8.1 Backend new files

| 文件 | 用途 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentLoopOrchestrator.java` | 有界 tool loop 编排 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentLoopResult.java` | loop 结果 sealed contract |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/advisory/AdvisoryService.java` | `AdvisoryTriggerPort` 真实实现与生命周期管理 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/advisory/AdvisoryPromptBuilder.java` | advisory 初始消息构造 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/advisory/AdvisoryOutputParser.java` | structured output / fallback JSON 映射与校验 |
| `backend/map-service/src/main/java/com/whut/map/map_service/shared/dto/sse/AdvisoryPayload.java` | SSE payload contract |

若 enums 与 nested records 过多，可拆分到同一 `shared/dto/sse/` 包内；保持 DTO 层无 service 依赖。

### 8.2 Backend modified files

| 文件 | 改动 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java` | advisory loop 配置：`maxIterations`、`maxSnapshotVersionLag`、`validSeconds` |
| `backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/SseEventType.java` | 新增 `ADVISORY` |
| `backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/SseEventFactory.java` | 仅负责生成 `event_id`；`AdvisoryService` 构建完整 `AdvisoryPayload` 后传入 publisher |
| `backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/RiskStreamPublisher.java` | 新增 `publishAdvisory(AdvisoryPayload)` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmErrorCode.java` | 不新增枚举；`AdvisoryService` 直接以常量 `ADVISORY_SCHEMA_FAILED` 调用 `RiskStreamPublisher.publishError(...)` |

### 8.3 Frontend new files

| 文件 | 用途 |
|---|---|
| `frontend/src/components/Dashboard/AdvisoryCard.tsx` | advisory 卡片展示 |
| `frontend/src/components/Dashboard/AdvisoryCard.test.tsx` | 卡片渲染测试 |

若现有测试布局不适合组件测试，可先覆盖 store 和 service，组件测试随当前前端测试约定最小化补充。

### 8.4 Frontend modified files

| 文件 | 改动 |
|---|---|
| `frontend/src/types/schema.d.ts` | 新增 advisory 类型与 `ADVISORY` event type |
| `frontend/src/services/riskSseService.ts` | 新增 `ADVISORY` listener、callback set、订阅 API |
| `frontend/src/services/riskSseService.test.ts` | 覆盖 advisory parse and dispatch |
| `frontend/src/store/useRiskStore.ts` | 新增 advisory state、actions、selectors 和 subscription |
| `frontend/src/store/useRiskStore.test.ts` | 覆盖 active / superseded / expired 生命周期 |
| `frontend/src/components/Dashboard/RiskExplanationPanel.tsx` | 插入独立 advisory 区块 |

### 8.5 Documentation

| 文件 | 改动 |
|---|---|
| `docs/EVENT_SCHEMA.md` | 新增 `ADVISORY` payload、生命周期规则、`ADVISORY_SCHEMA_FAILED` 错误码 |

---

## 9. Implementation Order

### Phase 1：后端 payload 与 SSE 外壳

- 新增 `AdvisoryPayload` record 与 enums
- `SseEventType` 加入 `ADVISORY`
- `RiskStreamPublisher.publishAdvisory(...)` 走现有单线程 publisher
- 为 `SseEventFactory` 或 `AdvisoryService` 固定 `event_id` 生成边界

### Phase 2：Agent loop 编排器

- 实现 `AgentLoopResult`
- 实现 `AgentLoopOrchestrator.run(...)`
- 覆盖 final text、tool call、recoverable tool error、provider failure、max iterations

### Phase 3：Advisory service 与 parser

- 实现 `AdvisoryPromptBuilder`
- 实现 `AdvisoryOutputParser`，先完成 text JSON fallback，再接 provider schema / JSON mode 主路径适配
- 实现 `AdvisoryService` 的 async trigger、schema failure、snapshot staleness、supersession 和 publish

### Phase 4：协议文档与前端类型

- 更新 `docs/EVENT_SCHEMA.md`
- 更新 `frontend/src/types/schema.d.ts`
- 更新 `riskSseService.ts` advisory listener

### Phase 5：前端 store 与展示

- `useRiskStore` 增加 advisory lifecycle
- `RiskExplanationPanel` 增加 advisory 区块
- 增加到期隐藏逻辑：组件在 `activeAdvisory` 到达或变化时按 `valid_until - now` 注册一次 `setTimeout` 调用 `expireActiveAdvisory(...)`，避免全局 interval 和 polling

### Phase 6：回归验证

- 运行后端相关单元测试
- 运行前端 service / store / component 相关测试
- 手动或测试夹具验证 `ADVISORY` SSE payload 可被前端消费

---

## 10. Test Plan

### 10.1 `AgentLoopOrchestratorTest`

- provider 首轮返回 `FinalText` 时，loop 返回 completed 且不调用工具
- provider 返回 `ToolCallRequest` 后，注册表收到同 callId / toolName / arguments，并将 `ToolResultAgentMessage` 追加到下一轮消息
- 工具返回 recoverable error payload 时，loop 继续进入下一轮
- provider 连续请求工具超过 `maxIterations` 时返回 `maxIterationsExceeded`
- registry 抛出非预期异常时返回 `toolFailed`

### 10.2 `AdvisoryOutputParserTest`

- 合法 JSON 映射为语义字段对象
- 缺少 `summary`、空 `affected_targets`、空 `evidence_items`、非法 enum 时失败
- `affected_targets` 不在 snapshot 中时失败
- 输出包含 Step 5 才允许的 COLREGS / maneuver evidence 时失败或被明确拒绝

### 10.3 `AdvisoryServiceTest`

- 成功生成时发布一次 `ADVISORY`，并调用 `onComplete` exactly once
- schema 失败时不发布 `ADVISORY`，发布 `ADVISORY_SCHEMA_FAILED` 错误或记录可断言告警路径，并调用 `onComplete`
- snapshot version lag 超阈值时静默丢弃，不发布 `ADVISORY`，并调用 `onComplete`
- 新 advisory 填入上一条 advisory 的 `supersedes_id`
- executor 拒绝或 provider failure 时释放 trigger flag

### 10.4 `RiskStreamPublisher` / SSE tests

- `publishAdvisory(...)` 使用 `SseEventType.ADVISORY`
- 序列化后的 JSON 字段为 snake_case
- `ADVISORY` 不覆盖 `latestRiskFrame` replay 缓存；新连接仍只 replay 最新 `RISK_UPDATE`

### 10.5 Frontend tests

- `riskSseService` 收到 `ADVISORY` event 后调用 advisory callbacks
- `useRiskStore.upsertAdvisory` 设置 `activeAdvisory`
- 第二条 advisory 到达时，旧 advisory 本地状态变为 `SUPERSEDED` 并进入 `archivedAdvisories`
- `expireActiveAdvisory` 在 `valid_until` 到期后清空 active advisory
- `AdvisoryCard` 展示 summary、urgency、recommended action 和 evidence list

---

## 11. Constraints And Risks

- Advisory 生成是异步系统任务，必须保证所有失败路径释放 `SceneRiskStateTracker` 的 `generatingFlag`
- Provider schema / JSON mode 能力差异可能导致 Gemini 与 Zhipu 行为不同；parser 必须统一最终校验，不信任 provider 已约束输出
- Step 3 evidence 只能来自 Step 2 查询工具；若 prompt 允许模型引用规则条款或假设操纵效果，会提前越过 Step 5 边界
- `ADVISORY` 不应进入 `latestRiskFrame` replay 缓存，否则新 SSE 连接可能误把过期 advisory 当作当前态势
- 前端到期隐藏是展示语义，不代表后端风险已经解除；卡片文案需避免将 advisory 到期描述成风险解除
- 如果 `maxSnapshotVersionLag` 过小，LLM 延迟高时 advisory 会被频繁丢弃；实施时应记录 lag 与生成耗时日志，便于后续调参
- `validSeconds = 120` 是从 advisory 发布时刻计算的有效期；若生成耗时为 15–45 秒，前端收到时的实际可用窗口通常约为 75–105 秒，后续调参应同时观察生成耗时和前端剩余展示时间

---

## 12. Deviations

本步骤保持 `AGENT_LOOP_PLAN.md` §4 Step 3 的目标、完成边界和依赖关系不变，但记录以下实现级偏离。对应父规划记录见 `AGENT_LOOP_PLAN.md` 附录 `Active Deviations`。

### 12.1 快照构建位置前移到触发点

- affected body item：`AGENT_LOOP_PLAN.md` §4 Step 3 中 `AdvisoryService` “接收场景触发 → 构建 AgentSnapshot”
- triggered by：Step 3
- date：2026-04-24
- reason：better-approach
- temporary handling：`SceneRiskStateTracker` 继续在触发瞬间通过 `AgentSnapshotFactory.build()` 构建冻结快照，并通过 `AdvisoryTriggerPort.onAdvisoryTrigger(AgentSnapshot snapshot, Runnable onComplete)` 传入 `AdvisoryService`
- canonical resolution：Step 3 应表述为“接收已冻结的 AgentSnapshot → 调用 AgentLoopOrchestrator → 填写 advisory 生命周期字段 → 发布”

### 12.2 结构化解析与时效性检查移出 orchestrator

- affected body item：`AGENT_LOOP_PLAN.md` §4 Step 3 中 `AgentLoopOrchestrator` “结束后调用结构化解析…发布前时效性检查”
- triggered by：Step 3
- date：2026-04-24
- reason：better-approach
- temporary handling：`AgentLoopOrchestrator` 只返回 `AgentLoopResult`；`AdvisoryService` 负责 `AdvisoryOutputParser` 调用、schema failure 处理、snapshot version lag 检查和 SSE 发布
- canonical resolution：Step 3 应表述为“`AgentLoopOrchestrator` 只编排有界 tool loop；`AdvisoryService` 在 loop 完成后执行结构化解析和发布前时效性检查”
