# Step Final 执行方案：LLM 物理收口与边界解耦

## Summary

Step 1–5 已完成 LLM 客户端抽象、prompt 外部化、风险上下文注入、多轮对话管理，以及第一轮依赖方向收口。当前后端仍存在两类残留问题：

1. **物理分散**：LLM 相关代码散落在 `llm/*`、`service/llm/*`、`transport/chat/*`、`dto/websocket/*`、`config/llm/*`、`config/properties/*`、`assembler/LlmRiskContextAssembler.java` 等多个根路径下，模块边界不清晰。
2. **边界未切净**：`ShipDispatcher` 仍直接依赖 `LlmRiskContextAssembler`、`LlmTriggerService`、`RiskContextHolder`；`RiskObjectAssembler` / `TargetAssembler` 仍直接认识 `LlmExplanation`；`RiskStreamPublisher` 仍直接消费 LLM DTO 和 chat 错误码。

Step Final 的目标是在**不改变现有外部协议和端点**的前提下，完成一次后端内部的结构性收口：
- LLM 专属类统一收敛到 `llm` 根包下
- 风险计算主干不再直接依赖 LLM 类型或 LLM 服务
- 风险解释继续通过现有 `/api/v2/risk` SSE 的 `EXPLANATION` 事件对外发布
- chat/voice WebSocket 协议与运行时错误边界重新归位，避免全局污染
- 修复风险快照内存状态与 SSE 可见状态之间的竞态，保证 chat 读取的上下文与前端最新风险视图保持同版本一致

本步不拆独立服务，不新增新的远程通道，不修改前端交互行为。

## Key Changes

### 1. 引入风险完成事件，切断 `ShipDispatcher` 对 LLM 的直接依赖

当前 `ShipDispatcher` 在风险计算完成后同时执行三类职责：
- 组装并发布 `RiskObjectDto`
- 组装 LLM 风险上下文
- 触发 LLM 风险解释并回传错误

该职责组合导致 pipeline 主干感知了 LLM 模块的内部实现。

本步引入内部事件 `RiskAssessmentCompletedEvent`，用于在风险计算完成后向 LLM 模块交付快照。事件负载仅包含 LLM 监听器生成上下文所需的事实数据，不包含任何 LLM 类型。建议字段如下：

```java
public record RiskAssessmentCompletedEvent(
        ShipStatus ownShip,
        Collection<ShipStatus> allShips,
        Map<String, CpaTcpaResult> cpaResults,
        RiskAssessmentResult riskResult,
        String riskObjectId,
        boolean triggerExplanations
) {
}
```

处理方式调整为：
- `ShipDispatcher` 仅负责风险计算、风险 DTO 组装、风险 SSE 发布、事件发布
- `ShipDispatcher` 删除对 `LlmRiskContextAssembler`、`LlmTriggerService`、`RiskContextHolder`、`LlmErrorCode`、`ChatErrorCode` 的依赖
- `RiskDispatchSnapshot` 删除 `LlmRiskContext llmContext` 字段，仅保留风险计算结果与风险 DTO，避免 pipeline 内部快照继续携带 LLM 类型
- `refreshAfterCleanup()` 继续发布刷新后的风险快照，同时发布 `RiskAssessmentCompletedEvent`，但 `triggerExplanations=false`，以保持“刷新上下文但不补发 explanation”的现有行为

该变更完成后，pipeline 对 LLM 的感知降为零。

### 2. 在 `llm` 包内新增事件监听器，集中处理上下文缓存与 explanation 触发

新增 `LlmRiskEventListener`，放置在 `llm` 根包内，监听 `RiskAssessmentCompletedEvent`。

监听器职责：
- 调用迁移后的 `LlmRiskContextAssembler` 生成 `LlmRiskContext`
- 更新 `RiskContextHolder`
- 在 `triggerExplanations=true` 时调用 `LlmTriggerService`
- 将 explanation 和错误转换为现有风险 SSE 所需的 payload，并交由 `RiskStreamPublisher` 发送

监听器执行模型要求如下：
- `LlmRiskEventListener` 使用同步 `@EventListener`，不使用 `@Async`
- `RiskContextHolder.update(...)` 必须在风险事件发布的同一调用链内完成，以保持 chat 查询读取到最新风险快照
- LLM explanation 的异步性继续保留在 `LlmTriggerService` / `LlmExplanationService` 内部线程池，而不是放在 Spring 事件分发层

依赖边界要求如下：
- 允许 `LlmRiskEventListener -> RiskStreamPublisher` 这条定向依赖，用于将 LLM explanation 投递到现有风险 SSE 通道
- `LlmRiskEventListener` 直接构造 `ExplanationPayload` 与风险错误码字符串
- 不将 `SseEventFactory` 反向引入 `llm` 包，避免再次形成 LLM 对风险 transport 细节的扩散依赖

为减少 pipeline 端准备 LLM 专属数据的工作，`LlmRiskContextAssembler` 同步迁入 `llm/context`，并吸收当前距离计算逻辑；其输入调整为：

```java
LlmRiskContext assemble(
        ShipStatus ownShip,
        Collection<ShipStatus> allShips,
        Map<String, CpaTcpaResult> cpaResults,
        RiskAssessmentResult riskResult
)
```

`currentDistanceNm`、`relativeBearingDeg` 等 LLM 专属字段均在 assembler 内部完成计算，不再由 pipeline 预处理。

### 3. 完成 `llm` 模块的物理包收口

本步遵循 `LLM_ENHANCEMENT_PLAN.md` 中的 `llm` 根包方向，对现有实现做集中迁移。迁移后的目标结构如下：

```text
llm/
├── client/          # LlmClient、Gemini/Zhipu/Whisper 客户端
├── config/          # LLM/Whisper 配置类、执行器配置、WebSocket 注册
├── context/         # LlmRiskContextAssembler、RiskContextHolder、RiskContextFormatter
├── dto/             # LLM 专属 DTO
├── memory/          # ConversationMemory
├── prompt/          # PromptTemplateService
├── service/         # LlmChatService、VoiceChatService、LlmTriggerService、LlmExplanationService
└── transport/ws/    # ChatWebSocketHandler 及 chat/voice WebSocket 协议 DTO
```

具体迁移原则：
- `service/llm/*` 迁入 `llm/service`、`llm/context`、`llm/memory`
- `transport/chat/ChatWebSocketHandler.java` 及其配套协议 DTO 从全局 `dto.websocket` 迁入 `llm/transport/ws`
- 迁入 `llm` 的是当前 `/api/v2/chat` 这条 chat/voice WebSocket 入口及其专用协议 DTO，不是将 WebSocket 技术栈整体视为 LLM 私有
- 若后续新增非 LLM WebSocket 端点，应按所属业务模块独立落位，例如 `history`、`risk` 或其他领域 transport；不复用 `/api/v2/chat`，也不强制放入 `llm`
- `WebSocketConfig` 采用拆分方案：
  - Servlet 容器级 `ServletServerContainerFactoryBean` 保留在共享 `config/websocket`
  - `/api/v2/chat` 的 handler 注册迁入 `llm/config`，作为 chat 专属路由配置
- 因此，WebSocket 的通用基础设施继续保留在共享 `config` / `transport` 范围；只有 chat 端点注册与 chat 协议模型归属 `llm`
- `config/llm/*` 与 `config/properties/LlmProperties.java`、`WhisperProperties.java` 一并迁入 `llm/config`
- `assembler/LlmRiskContextAssembler.java` 迁入 `llm/context`

兼容性要求：
- 配置前缀保持不变：`llm.*`、`whisper.*`
- WebSocket 路径保持不变：`/api/v2/chat`
- 不修改现有 `PromptTemplateService` 和客户端实现的行为

### 4. 修复风险快照与 SSE 推送流之间的版本竞态

当前存在两个并发入口会生成风险快照：
- MQTT 处理链路：`AisMessageListener -> ShipDispatcher.dispatch()`
- 清理刷新链路：`SseKeepaliveScheduler -> ShipDispatcher.refreshAfterCleanup()`

二者当前都会执行以下顺序：
1. 计算 `RiskObjectDto` 与 `LlmRiskContext`
2. 立即 `riskContextHolder.update(...)`
3. 将 `publishRiskUpdate(...)` 提交给 `RiskStreamPublisher` 的单线程异步队列

由于“更新内存上下文”和“提交 SSE 广播任务”之间没有统一的提交顺序保证，两个线程交错时会出现以下结果：
- `RiskContextHolder` 最终保存的是较新的快照 B
- SSE 队列中最后广播给前端的却可能是较旧的快照 A

这会直接破坏“chat 读取上下文应与前端当前风险视图一致”的约束。

本步修复原则如下：
- 风险快照生成后必须获得单调递增的 `snapshotVersion`
- `RiskObjectDto` 发布与 `RiskContextHolder` 更新必须按同一 `snapshotVersion` 顺序生效
- `RiskContextHolder` 不再在 dispatcher 线程中直接更新，而是与风险帧发布一起进入 `RiskStreamPublisher` 的单线程发布序列
- 由 `RiskStreamPublisher` 在真正广播 `RISK_UPDATE` 前，先提交对应版本的上下文更新回调，再广播该版本的风险帧
- 仅当某个版本已进入发布序列时，chat 才能读到该版本的 `LlmRiskContext`

建议将风险主帧与上下文更新封装为统一快照对象，例如：

```java
public record RiskFrame(
        long snapshotVersion,
        RiskObjectDto riskObject,
        Runnable beforePublish
) {
}
```

`RiskFrame` 放置在共享 DTO 边界，例如 `dto.riskstream` 或等价的共享传输模型包中。
放置原则如下：
- 不放在 `pipeline`，避免 `transport/risk -> pipeline` 反向依赖
- 不放在 `transport/risk`，避免 `pipeline -> transport/risk` 新增耦合
- 由 `pipeline` 构造、`transport/risk` 消费，因此应位于两者都可依赖的共享模型位置

其中 `beforePublish` 的职责仅限：
- 更新 `RiskContextHolder`
- 发布 `RiskAssessmentCompletedEvent`

`RiskStreamPublisher.publishRiskFrame(...)` 的执行顺序固定为：
1. 在 publisher 单线程内执行 `beforePublish`
2. 构造并广播 `RISK_UPDATE`
3. 缓存最新 risk frame，供新 SSE 订阅者回放

该顺序保证：
- 任意时刻，`RiskContextHolder` 中可读到的版本不会领先于尚未对前端可见的风险帧
- 若版本 B 在队列中排在版本 A 之后，则上下文更新和 SSE 广播都严格按 A -> B 顺序生效

实现边界要求：
- 不要求引入数据库事务或全局锁
- 不要求阻塞 MQTT 线程等待 SSE 发送完成
- 仅依赖现有 `RiskStreamPublisher` 的单线程串行语义完成顺序收敛
- `LlmRiskEventListener` 不得在 `beforePublish` 执行链内做任何阻塞调用
- explanation 的异步性必须始终收敛在 `LlmTriggerService` / `LlmExplanationService` 的 executor 内部，不得上浮到 Spring 事件监听层或 publisher 执行链
- 若未来在监听器内加入同步等待 LLM 结果、远程阻塞查询或其他长耗时逻辑，将直接阻塞 `RiskStreamPublisher` 的单线程广播，这属于本步明确禁止的实现方式

`refreshAfterCleanup()` 也必须走同一 `publishRiskFrame(...)` 路径，不能绕过该顺序控制直接更新 `RiskContextHolder`

### 5. 删除风险 DTO 组装层中的遗留 LLM 耦合

当前 `RiskObjectAssembler` / `TargetAssembler` 仍保留 `LlmExplanation` 合并能力，但运行时主链路并未使用该能力；`ShipDispatcher` 两处调用均传入空 map，而 `SseEventFactory` 又会在发布前剥离 `risk_assessment.explanation`。

该状态说明解释合并路径已成为遗留耦合，应直接清理。

清理原则如下：
- `RiskObjectAssembler.assembleRiskObject(...)` 删除 `Map<String, LlmExplanation> llmExplanations` 参数
- `TargetAssembler.assembleTargets(...)` 与 `assembleTarget(...)` 删除 `LlmExplanation` 相关参数
- `TargetAssembler` 不再输出 `risk_assessment.explanation` 节点；rule/fallback explanation 相关私有方法随 explanation 节点一并删除
- `RiskObjectDto.targets` 的输出形态直接与 `RiskUpdatePayload.targets` 对齐，不再先组装 explanation 再在 SSE 层剥离
- `SseEventFactory.removeExplanation(...)` 删除

该调整后，风险更新链路与 explanation 链路的职责边界为：
- `RISK_UPDATE`：纯风险计算结果
- `EXPLANATION`：独立的 LLM 解释事件

本步不引入 `ExplanationProvider` 抽象。原因是现有架构已经采用独立 explanation 事件，继续增加提供者抽象不会带来额外收益。

### 6. 收紧风险发布器与错误码边界

当前 `RiskStreamPublisher` 和 `SseEventFactory` 仍直接导入 `LlmExplanation` 与 `ChatErrorCode`，造成两个问题：
- 风险 transport 直接认识 LLM DTO
- chat WebSocket 错误码污染风险 SSE 错误路径

本步改造为：

1. `RiskStreamPublisher.publishExplanation(...)` 改为接收 `ExplanationPayload`
2. `RiskStreamPublisher.publishError(...)` 改为接收 `String errorCode`
3. `SseEventFactory` 仅负责通用风险 SSE 事件构造，不再导入 `LlmExplanation`
4. `ChatErrorCode` 仅保留为 chat WebSocket 协议错误码，迁入 `llm/transport/ws` 范围
5. 风险 explanation 失败时，由 `LlmRiskEventListener` 直接产出 SSE error payload 所需的错误码字符串，并在监听器内部维护 explanation 专用映射逻辑

错误码处理原则：
- `LLM_TIMEOUT`、`LLM_REQUEST_FAILED` 等 explanation 运行时错误继续保留既有字符串值
- explanation 事件只关心当前链路可能产生的错误：
  - `LlmErrorCode.LLM_TIMEOUT -> "LLM_TIMEOUT"`
  - `LlmErrorCode.LLM_FAILED` 或 `null -> "LLM_REQUEST_FAILED"`
- `SseErrorPayload.error_code` 继续保持字符串，不引入新的全局枚举
- chat 与 risk 两条通道的错误码不再共享同一个后端 enum

### 7. 保持外部协议不变，仅调整内部归属

本步不修改以下外部契约：
- `GET /api/v2/risk` 的 `RISK_UPDATE` / `EXPLANATION` / `ERROR` 事件类型
- `WS /api/v2/chat` 的 `CHAT` / `SPEECH` / `CLEAR_HISTORY` 交互协议
- `frontend/src/types/schema.d.ts` 中既有字段结构
- explanation 继续通过风险 SSE 通道到达前端，不新增独立 LLM SSE

内部可调整但对外保持兼容的内容包括：
- Java 包路径
- Spring Bean 所在包
- 内部事件与监听器
- assembler、publisher、handler 的方法签名

## Test Plan

需要新增或调整以下测试。

### 1. Pipeline 与事件边界

- `ShipDispatcher` 单元测试：
  - 风险更新仍正常发布
  - 风险计算完成后发布 `RiskAssessmentCompletedEvent`
  - 不再直接调用任何 LLM 服务或持有 LLM 类型
  - `buildRiskSnapshot()` / 风险快照构建路径不再生成 `LlmRiskContext`
  - `dispatch()` 与 `refreshAfterCleanup()` 都通过统一的 `publishRiskFrame(...)` 路径提交风险帧
- `refreshAfterCleanup()` 测试：
  - 仍发布风险更新
  - 发布 `triggerExplanations=false` 的事件

### 2. LLM 监听链路

- `LlmRiskEventListener` 单元测试：
  - 能根据事件生成 `LlmRiskContext`
  - 以同步监听方式更新 `RiskContextHolder`
  - 始终更新 `RiskContextHolder`
  - `triggerExplanations=true` 时触发 explanation
  - `triggerExplanations=false` 时不触发 explanation
  - explanation 成功时生成正确的 `ExplanationPayload`
  - explanation 失败时生成正确的 `SseErrorPayload.error_code`

### 3. 风险快照版本一致性

- 并发测试：
  - 模拟 `dispatch()` 与 `refreshAfterCleanup()` 并发生成不同版本的风险帧
  - 验证 `RiskContextHolder` 的最终版本与最后一条对外可见 `RISK_UPDATE` 的版本一致
  - 验证较新的上下文不会在较旧风险帧仍未广播完成前提前对 chat 可见
  - 使用 `CountDownLatch`、`CyclicBarrier` 或等价同步屏障精确构造并发提交，不使用 `Thread.sleep` 依赖时序概率
- 发布顺序测试：
  - 验证 `beforePublish` 总是在对应 `RISK_UPDATE` 广播前执行
  - 验证同一 publisher 队列中 A、B 两个版本严格按提交顺序完成“上下文更新 + 风险帧广播”
  - 通过 mock / spy 记录 publisher 线程内的实际执行顺序断言版本先后，而不是仅检查最终状态

### 4. 风险 DTO 与 SSE 发布

- `RiskObjectAssembler` / `TargetAssembler` 测试：
  - 风险目标输出不再依赖 `LlmExplanation`
  - 目标结构中不再构造独立 explanation 节点
- `SseEventFactory` / `RiskStreamPublisher` 测试：
  - `RISK_UPDATE` payload 与现有协议保持一致
  - `EXPLANATION` 事件仍能按现有字段对外发送
  - 风险 SSE 错误码不再依赖 `ChatErrorCode`

### 5. Chat / Voice 回归

- `ChatWebSocketHandler` 回归测试：
  - `CHAT`、`SPEECH`、`CLEAR_HISTORY` 路由保持不变
  - handler 迁移到 `llm` 包后，WebSocket 注册和消息处理行为保持一致
- `VoiceChatService` / `LlmChatService` / `ConversationMemory` 回归测试：
  - 原有多轮对话、语音转写、超时处理行为继续通过

### 6. 端到端回归

- `/api/v2/risk`：
  - 风险更新继续实时下发
  - explanation 仍经由 `EXPLANATION` 事件下发
  - explanation 失败时前端仍能收到 `ERROR` 事件
- `/api/v2/chat`：
  - 文本问答、语音问答、清空历史行为与当前版本一致

## Assumptions

- 本步仅做后端内部收口与边界解耦，不同步拆分前端类型文件或文档章节结构
- explanation 继续保留在现有 risk SSE 通道，而非新增独立 LLM 通道
- 不引入新的 provider 抽象、领域网关或独立微服务，以避免超过本步必要复杂度
- `LlmProperties`、`WhisperProperties` 即使迁入 `llm/config`，其 Spring 配置前缀和配置项命名均保持不变
- `ChatWebSocketHandler` 作为 LLM transport 入口迁入 `llm` 包后，仍可直接调用 `LlmChatService` 和 `VoiceChatService`；该依赖属于模块内部依赖，不再视为跨模块耦合
