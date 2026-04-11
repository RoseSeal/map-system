# Architecture Decisions and Review Findings

> 用途：记录当前稳定架构决策，以及围绕这些决策沉淀出的实现级 review finding
> 最后更新：2026-04-11（前端播报 / 录音竞态 review 补充）
> 关系说明：架构概览以 `docs/ARCHITECTURE.md` 为准；协议真值以 `docs/EVENT_SCHEMA.md` 为准；本文档承接关键设计取舍与相关复盘结论

---

## 一、Architecture Decisions (ADR)

### ADR-001

内部统一可信实体使用 `ShipStatus`

Reason:
解耦 AIS / CV / radar 等异构数据源，统一领域层输入模型。

### ADR-002

LLM 能力通过 `LlmClient` 接口抽象

Reason:
隔离具体厂商实现，支持 Gemini / 智谱切换，并降低上层业务对模型供应商的耦合。

### ADR-003

LLM 服务提供商通过配置切换

Reason:
使用 `@ConditionalOnProperty` 控制实现装配，便于环境切换、实验对比与故障降级。

### ADR-004

语音输入链路采用 Backend-orchestrated 方案

Trade-off: 语音输入链路方案选择

#### 可选方案

##### 方案一：Frontend-first

```text
Browser MediaRecorder
  -> whisper-server
  -> 返回转写文本
  -> 前端确认 / 修正
  -> 现有 WebSocket CHAT
  -> map-service 调 LLM
  -> 前端文字显示 + SpeechSynthesis 播报
```

##### 方案二：Backend-orchestrated

```text
Browser MediaRecorder
  -> map-service
  -> whisper-server
  -> map-service 获取 transcript
  -> map-service 调 LLM
  -> map-service 推送给前端
  -> 前端文字显示 + SpeechSynthesis 播报
```

#### 评估维度

##### 1. 延迟

- 两种方案的主耗时都在音频采集、ASR 推理与 LLM 推理，链路多一跳通常不是主要瓶颈。
- 方案二相比方案一，多了一层 `map-service -> whisper-server` 中转，但在本机、局域网或同机容器部署下，这部分通常只是小头。
- 结论：延迟基本一致。方案二不会因为多一层编排而天然显著变慢。

##### 2. 架构清晰度

- 方案一由前端负责更多编排逻辑，包括调用 ASR、处理 transcript，再调用现有 CHAT 链路。
- 方案二由 `map-service` 统一承担 orchestration，ASR、LLM、结果推送均由后端统一编排。
- 对当前系统而言，`map-service` 已经是 risk、LLM、WebSocket 的中枢，语音链路继续收归后端，整体职责边界更一致。
- 结论：方案二更优，更符合现有系统的 backend-centered orchestration 风格。

##### 3. 流式拓展（Streaming ASR）

- 方案一更容易先做前端侧流式试验，前端可以先拿 partial transcript，确认后再发给后端，交互更灵活。
- 方案二也可以支持流式，但复杂度会明显上升，主要增加在：
- speech session 状态管理。
- chunk / frame 协议设计。
- partial / final transcript 语义管理。
- LLM 触发时机控制。
- WebSocket 消息 schema 扩展。
- 这些增加的主要是实现复杂度和状态复杂度，不主要是推理延迟。
- 结论：方案一的流式拓展更轻；方案二并非不能做，但会更复杂。相对当前非流式实现，方案二若扩展到稳定可用的流式版本，整体复杂度可视为约 1.5x 到 2x，复杂度主要集中在协议、状态管理和触发策略，而不是性能本身。

#### 最终选择

选择方案二：Backend-orchestrated。

##### 选择原因

- 与当前系统架构一致，前后端职责更清晰。
- `map-service` 统一编排 ASR + LLM，更利于后续日志、审计、容错与 Provider 替换。
- 当前 v2 协议已将语音输入收敛为独立 `SPEECH` 消息类型，后端统一处理转写与后续问答，比前端自行拼接多段链路更稳定。
- 当前阶段优先目标是先将真实链路干净跑通，而不是优先追求流式体验。

##### 当前判断

- P1 已完成方案二的非流式版本。
- 当前语音输入链路已接入 `whisper.cpp`，并由 `map-service` 统一完成 ASR、LLM 调用与结果推送。
- 若后续真实测试发现转录错误率较高、交互等待感明显，或需要边说边看 partial transcript，再评估是否引入流式 speech session。

#### 模型选择注意事项

##### 1. Whisper 的适用性

- `whisper.cpp` 适合作为当前阶段的本地 ASR backend，便于 Docker 化、本地部署和快速接入。官方 server 示例支持通过 Docker 运行 `whisper-server`，并支持 `--prompt` 初始提示等参数。
- 但 Whisper 本质上是通用型 multilingual ASR，不应直接假设其就是中文航运场景的最终最优解。OpenAI 对 Whisper 的定位也是 general-purpose speech recognition。

##### 2. 航运专业词汇风险

- 航运专业词汇如 `CPA`、`TCPA`、船名、航道名等，Whisper 可能发生转写偏差，例如把缩写识别为普通中文词或其他英文片段。
- 可优先尝试通过 `prompt / initial prompt` 向模型注入领域词表或示例短语，以降低术语误识别风险。`whisper.cpp` server / CLI 均支持 `prompt` 参数。

##### 3. 中文场景必须做 A/B Test

- 项目面向中国内河真实船员，中文口语、口音与行业词都可能显著影响 ASR 表现。
- 因此不能只押注 Whisper 一家，必须与中文系模型进行 A/B test，例如 FunASR / SenseVoice 路线，作为真实效果对照。

##### 4. 优先级判断

- 若真实测试中转录错误率较高，优先应提升模型能力、词表适配与音频质量，而不是第一时间将希望寄托在流式化上。
- 流式主要改善的是交互体验和反馈速度，不天然等于更高识别精度。

### ADR-005

实时事件协议采用 `risk = SSE`、`chat = WebSocket` 的双连接方案

Trade-off: 风险广播与会话交互协议拆分

#### 背景问题

- `risk` 与 `chat` 复用单条 WebSocket 连接时，广播事件与会话消息语义混杂。
- LLM 解释同步阻塞在风险主链路中，会直接放大风险帧推送延迟。
- 协议字段职责不清，前后端类型与实现容易漂移。

#### 可选方案

##### 方案一：继续使用单 WebSocket 承载全部事件

- 优点：连接数少，初始接入成本低。
- 缺点：广播与会话复用同一 transport，协议边界模糊；需要自行处理重连、事件去重与序号语义。

##### 方案二：风险流与聊天流拆分

- `risk` 使用 SSE：`/api/v2/risk`
- `chat` 使用 WebSocket：`/api/v2/chat`

#### 评估维度

##### 1. 连接语义

- `risk` 本质是无会话、单向下行的广播快照与异步解释事件。
- `chat` 本质是带会话语义的双向交互。
- 结论：两类流量的通信模型不同，拆分后职责边界更清晰。

##### 2. 可靠性与前端复杂度

- SSE 原生支持自动重连与 `Last-Event-ID`，适合风险流的最新态同步。
- WebSocket 保留给 `CHAT` / `SPEECH`，避免在广播通道上浪费双向能力。
- 结论：`risk` 使用 SSE 后，前端连接管理与乱序保护成本更低。

##### 3. 主链路延迟

- 风险快照与 LLM 解释拆分后，可形成“快路径同步推送风险、慢路径异步生成解释”的结构。
- 这比在主链路中同步等待 LLM 更符合实时预警系统诉求。
- 结论：拆分连接与拆分事件流是同一方向上的架构收敛。

#### 最终选择

选择方案二：`risk = SSE`，`chat = WebSocket`。

##### 选择原因

- 让广播风险与会话交互各自使用更匹配的 transport。
- 让 `RISK_UPDATE` 与 `EXPLANATION` 成为独立事件，而非将解释内嵌在风险帧中。
- 为协议版本化、事件统一建模与后续 schema 演进提供更稳定的基础。

##### 当前状态

- v2 协议已实施，当前生效文档为 `docs/EVENT_SCHEMA.md`。
- 风险通道使用 SSE，下发 `RISK_UPDATE`、`EXPLANATION` 与 `ERROR`。
- 聊天通道使用 WebSocket，承载 `CHAT`、`SPEECH` 及对应回复事件。

### ADR-006

实时风险帧发布采用“同一服务端发布序列内，先完成 current-state context 切换，再广播 `RISK_UPDATE`”的轻量同步提交模型

Trade-off: 风险帧发布与 `RiskContextHolder` 更新的提交顺序

#### 背景问题

- 风险快照由 MQTT 主链路与 cleanup 刷新链路并发产生。
- chat / explanation 读取的是 latest-only 的 `RiskContextHolder`，其语义是“当前世界状态”，而不是历史版本缓存。
- 若风险帧广播与 `RiskContextHolder` 更新没有统一提交边界，会出现前端与 chat 对“当前态势”看到不同版本的问题。

#### 可选方案

##### 方案一：先广播风险帧，再更新 `RiskContextHolder`

- 优点：风险帧尽快对外发送，发布线程上没有额外同步提交步骤。
- 缺点：会制度化地产生“前端已看到新帧、chat 仍读旧 context”的窗口；该窗口不是概率问题，而是顺序定义带来的必然结果。

##### 方案二：单一事件源，多个 listener 并行消费

```text
RiskFrameEvent published
  ├── Listener A -> broadcast risk frame
  └── Listener B -> update RiskContextHolder
```

- 优点：事实载体只有一份，结构上较整洁。
- 缺点：单一事件源只解决“事实构造一份”，不解决“不同观察者何时看到这份事实”。
- 若 listener 并行或异步消费，则仍会出现可观察顺序漂移；而“最多只差 1 帧”的判断只是乐观假设，不是机制保证。
- 若要让异步 listener 成立，必须额外加入背压、丢弃中间帧、deadline 对齐或显式串行化等机制，复杂度显著上升。

##### 方案三：在同一发布序列内，先同步提交 context，再广播风险帧

```text
build RiskFrame
  -> enter RiskStreamPublisher single-thread sequence
  -> assign snapshotVersion
  -> beforePublish(snapshotVersion)
       -> publish RiskAssessmentCompletedEvent
       -> synchronous LlmRiskEventListener updates RiskContextHolder
  -> broadcast RISK_UPDATE
```

- 优点：latest-only current-state 语义最直接；chat 与风险帧共享同一个服务端提交边界。publisher 线程承担的同步工作上界可预测，不是不确定性风险。
- 缺点：publisher 线程承担一小段同步工作，必须严格控制该链路持续保持轻量、非阻塞。

#### 评估维度

##### 1. current-state 一致性

- 系统目标不是“历史帧问答”，而是“LLM 解释当前态势”。
- 在这一目标下，`RiskContextHolder` 必须始终表示 latest-only 当前态势。
- 结论：不能接受“前端已看到最新帧，但 chat 仍基于上一帧回答”这一类帧级漂移。

##### 2. 复杂度

- 异步 listener / 双消费者模型要想可靠成立，必须额外设计慢消费者处理策略。
- 这些机制相对于一次内存写级别的 context 刷新而言，复杂度明显过高。
- 结论：为极轻量同步操作引入异步消费基础设施，不符合当前问题规模。

##### 3. 实时性

- 风险帧不应因为 LLM explanation 或重逻辑而被显著延后。
- 但在广播前完成一次内存级、可预测的小成本提交，是可接受的。
- 结论：应避免的是“重逻辑阻塞发布线程”，而不是“任何同步提交步骤”。

#### 最终选择

选择方案三：在 `RiskStreamPublisher` 的单线程发布序列内，先完成轻量同步提交，再广播风险帧。

##### 选择原因

- latest-only `RiskContextHolder` 与 `RISK_UPDATE` 需要共享同一个服务端提交点。
- `RiskContextHolder` 更新本身是轻量内存操作，不值得为其单独设计异步消费模型。
- explanation 已经是独立异步旁路；需要保持同步的仅是 current-state commit，而不是整个 LLM 链路。

##### 实现约束

- `beforePublish` 链路只允许：
  - 分配 `snapshotVersion`
  - 发布内部风险完成事件
  - 由同步 `LlmRiskEventListener` 刷新 `RiskContextHolder`
- `beforePublish` 不得执行：
  - LLM 远程调用
  - 阻塞等待 future / timeout
  - 重计算
  - 任何不可预测的长耗时逻辑
- explanation 触发继续作为异步旁路，收敛在 `LlmTriggerService` / `LlmExplanationService` 的 executor 中。

##### 明确否决的方向

- 不采用“先推帧，再更新 context”，因为它会制度化地引入帧级 current-state 漂移。
- 不采用“事件发布后由两个并行 listener 各自消费即可自然对齐”的表述，因为该方案没有提供可观察一致性的上界保证。
- 不为本步引入历史快照缓存、多版本 context holder 或 `snapshotId` 问答协议；若未来需要历史问答，应作为独立 history retrieval 能力设计。

## 二、Review Findings

- `ADR-005 / 连接状态建模`：`ERROR` SSE 事件属于业务错误而非传输断线；实现上由前端 `riskSseService` 与 `useRiskStore` 将 `lastError` 与 `isConnected` 分离，避免一次 LLM 超时导致面板误判离线。
- `ADR-005 / SSE 重连语义`：SSE 真实断线必须由 `EventSource.onopen` / `onerror` 驱动连接状态；实现上将在线状态切换收口到 `riskSseService`，保证断线与恢复均可被感知。
- `ADR-005 / 传输边界收口`：业务 pipeline 不直接依赖 SSE 细节；实现上由 `RiskStreamPublisher` 作为唯一发送入口，替代 `ShipDispatcher` 直接操作 `SseEventFactory` / `SseEmitterRegistry`。
- `ADR-005 / 包结构语义对齐`：协议拆分后，SSE 与 WebSocket 相关实现继续塞在 `websocket` 包内会导致语义失真；实现上将传输层统一更名为 `transport`，并拆分为 `protocol`、`chat`、`risk` 子包，保证包结构与协议职责一致。
- `ADR-005 / 标识语义分离`：SSE 原生 `id:` 必须承载递增 `sequence_id`，而非业务 `event_id`；实现上统一由 `RiskStreamPublisher` 分配 `sequence_id`，`event_id` 仅保留在 payload 中用于去重与追踪。
- `ADR-005 / 异步解释解耦`：风险快照与解释事件拆分后，发送路径不应重复 build / broadcast；实现上将风险与解释事件统一收归 `RiskStreamPublisher`，避免双发与调用方持有传输层依赖。
- `ADR-005 / 协议分层命名`：校验器命名与包位置需要反映真实职责；实现上将 `ChatRequestValidator` 更正为 `ChatPayloadValidator`，并迁移至业务服务相关校验层，避免 transport 与 payload 语义混淆。
- `ADR-004 / 语音交互模式`：`preview` 与 `direct` 属于不同交互语义；实现上前端 `useAiCenterStore` 将 preview 结果仅回填输入框，`direct` 模式则在收到 transcript 后原位更新语音消息，避免聊天历史污染与重复消息。
- `ADR-006 / current-state commit`：实时风险帧与 latest-only `RiskContextHolder` 的一致性必须由同一服务端发布序列保证；实现上保留 `RiskStreamPublisher.beforePublish -> RiskAssessmentCompletedEvent -> LlmRiskEventListener` 的轻量同步提交链，不采用异步 listener 并行消费。
- `ADR-006 / 风险快照一致性`：`RiskContextHolder` 的最新上下文与前端最终收到的 `RISK_UPDATE` 存在乱序风险。根因不是计算错误，而是 `holder update -> SSE async publish` 缺少统一版本语义。处理决策：已在 `llm-enhancement-step-final` 修复，通过事件边界收口时一并补齐了快照版本一致性约束。
- `ADR-004 / 播报节流 key 必须与去重 key 解耦`：去重（"这段文本播过了吗"）与限流（"同一目标距上次播报是否超过间隔"）使用不同粒度的 key，不能合并。`message_id = llm-explanation::{targetId}::{eventId}`，每条 SSE EXPLANATION 的 `eventId` 由后端唯一生成，以此为限流 key 导致 `lastSpokenAt[message_id]` 永远为 0，`WARNING_SPEECH_INTERVAL_MS`（15 s）节流完全失效，每条解释均被立即播报。修复过程经历两次迭代：第一次以裸 `target_id` 为限流 key，节流本身正确，但 `resetConversation()` 仅保留 `llm-explanation::` 前缀的 key，裸 ID 在对话重置后全部丢失，导致重置后同目标可立即重新播报的回归。**最终 Fix**：限流 key 改为 `event.conversation_id`（即 `buildLlmConversationKey(targetId)` = `llm-explanation::{targetId}`），该 key 天然携带正确前缀，跨对话重置保留；`markMessageSpoken` 后额外以 `event.conversation_id` 写入 `lastSpokenAt`，使下一条同目标解释命中限流。三类 key 最终分工：去重用 `message_id`（含 eventId，一次性）；限流用 `conversation_id`（按目标，跨重置保留）；聊天回复去重用 `event_id`（随会话清空，正确）。
- `ADR-004 / 播报跳过路径的终态语义`：事件驱动处理器中，"跳过"不等于"忽略"——跳过路径须明确选择终态：永久丢弃或限流抑制，二者不可混淆。`useAiSpeechBroadcast` 在 TTS 忙时对非 ALARM 解释执行 `return`，不记录任何状态，导致该解释在 TTS 空闲后的下一次无关触发时意外重播（zombie speech）。第一次修复在跳过处调用 `markMessageSpoken(event.message_id, event.text)`，引入新回归：`spokenMessages[message_id]` 被写入，后续去重命中，该警告被永久压制、永不播报。**最终 Fix**：跳过时调用 `markMessageSpoken(event.conversation_id, event.text)`——此调用写 `lastSpokenAt[conversation_id]`（限流 15 s 生效），同时写 `spokenMessages[conversation_id]`（去重只查 `spokenMessages[message_id]`，不命中，无害）；`spokenMessages[message_id]` 不被写入，15 s 后若 TTS 空闲且后端未发新解释，旧解释仍可播出一次，属于受节流约束的有界重播而非 zombie。
- `ADR-004 / MediaRecorder.stop() 同步置态但 onstop 异步触发的竞态`：W3C 规范规定 `stop()` 同步将 `state` 置为 `'inactive'`，但 `onstop` 异步触发。若 `stopRecording()` 后立即调用 `startRecording()`，`state !== 'inactive'` 保护已失效，新 session 得以启动并清空 `this.chunks`；旧 `onstop` 之后触发，读到空 chunks 生成 0 字节 Blob，再调用 `this.cleanup()` 销毁新 session 的 recorder。**Fix**：`VoiceRecorderService` 增加 `private sessionId = 0` 字段；`startRecording()` 首部递增 `this.sessionId`；`stopRecording()` 时捕获 `const capturedChunks = this.chunks` 和 `const sessionAtStop = this.sessionId`，在 `onstop` 内先检查 `this.sessionId !== sessionAtStop`，不匹配则直接返回，不调用 `cleanup()` 也不 resolve；否则以 `capturedChunks` 而非 `this.chunks` 构建 Blob。
- `RiskAssessmentEngine / TCPA 边界与 eps 语义`：`classifyRisk` 对 `tcpaSec` 使用 `> 0` 判断，导致 `TCPA == 0`（船只正处于最近会遇点）时因浮点边界问题从最高风险瞬间误降为 SAFE。朴素修法 `>= 0` 仍不足：`buildTargetAssessment` 原先对负 TCPA 做 `Math.max(..., 0)` 截断，截断后无法区分”恰在 CPA”与”已过 CPA 正在远离”。第一轮 Fix 引入 `TCPA_CPA_EPS_SEC = 1.0`，`classifyRisk` 改为 `tcpaSec >= -TCPA_CPA_EPS_SEC`，但随即暴露两个后续问题：（1）`CpaTcpaEngine` 在相对速度近零（平行航行）时以 `tcpa=0` 作为 sentinel，与”真实 TCPA=0 = 恰在 CPA”无法区分——`>= -eps` 会把平行航行的近距船误判为 ALARM；（2）`approaching` 字段在 `RiskAssessmentEngine` 中用 `>` 而 `classifyRisk` 用 `>=`，导致 `-TCPA_CPA_EPS_SEC` 恰好边界处”风险 ALARM 但 approaching=false”的矛盾；（3）`RiskVisualizationAssembler` / `LlmRiskContextAssembler` 直接读 `CpaTcpaResult.isApproaching()`（`tcpa > 0`），绕过了 eps 后处理，UI 可能出现无 `graphic_cpa_line` 但风险等级 ALARM 的不一致，LLM prompt 可能与风险等级矛盾。**最终 Fix**：`CpaTcpaResult` 增加 `@Builder.Default boolean cpaValid = true`；`CpaTcpaEngine` 在 `vel2 < MIN_RELATIVE_SPEED_MS` 时 early-return 并设 `cpaValid=false`；`RiskAssessmentEngine` 对 `!cpaValid` 提前返回 SAFE；`approaching` 字段边界统一改为 `rawTcpaSec >= -TCPA_CPA_EPS_SEC`（与 `classifyRisk` 一致）；`RiskVisualizationAssembler.buildGraphicCpaLine` 和 `LlmRiskContextAssembler` 均改为读 `TargetRiskAssessment.isApproaching()`，以后处理结果为唯一权威来源，不再依赖原始引擎输出。
- `LLM timeout 取消语义`：`CompletableFuture.orTimeout(...)` 只约束 future 在逻辑层面超时，不等同于底层 HTTP 连接被可靠中止。原始代码中 `LlmChatService` / `VoiceChatService` 未保存 future 引用，超时时无法调用 `cancel(true)`，而 `LlmExplanationService` 已做此处理。初版 review 将此归因为”线程池 starvation”，但 executor 为 virtual-thread-per-task，不存在 platform thread 耗尽风险，结论过度。**Fix**（有限改进）：两处改为先取引用再 `.orTimeout()`，超时时调用 `future.cancel(true)` 发送中断信号，与 `LlmExplanationService` 对齐。根本修复需在 provider/client 层显式配置 connect/read timeout，或改用可中断的 HTTP client 封装；Gemini / Zhipu SDK 当前不暴露该配置，留为后续技术债。
- `AIS 重复报文与 ghost ship`：相同 `msgTime` 导致目标长期存活的问题，更适合归类为来源侧重复投递 / 新鲜度问题，而不是 engine 核心 bug。处理决策：已纳入 `ENGINE_ENHANCEMENT_PLAN.md` Step 5A，不再在此作为独立 bug 追踪。
- `useRiskStore / droppedTargetNotices 替换而非累积`：`setRiskUpdate` 中 `droppedTargetNotices` 以 `state.selectedTargetIds.filter(id => !targetIds.has(id))` 计算当帧新增丢失目标，当结果非空时整体替换旧值。若两个相邻 RISK_UPDATE 帧分别丢失目标 A、B，帧 1 写入 `['A']`，帧 2 计算得 `['B']`（此时 `selectedTargetIds` 已不含 A），整体覆盖，A 的掉线提示在用户看到前即消失。**Fix**：改为 `[...new Set([...state.droppedTargetNotices, ...droppedTargetNotices])]`，累积语义，由 `clearDroppedTargetNotices` 统一清空。
- `RiskExplanationPanel / WebSocket 断连静默吞字`：`handleSendChat` 未检查 `sendTextMessage` 的返回值。WS 断连时 `chatWsService.send()` 返回 null，`sendTextMessage` 返回 false 并提前退出（不清空输入框、不添加气泡）；调用方忽略返回值，用户点击发送后界面无任何变化，无法判断是卡顿还是已发出。**Fix**：检查返回值，返回 false 时向 `ChatComposer` 传入 `sendError` prop，复用现有状态行展示 3 s 短暂提示，与 `voiceError` 模式一致。
