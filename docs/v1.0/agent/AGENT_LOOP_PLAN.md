# v1.0 Agent Loop Foundation — 总览规划

> 文档状态：active
> 最后更新：2026-04-27
> 用途：v1.0 阶段方向判断、范围收敛与 step 拆分的中间层规划文档。
> 非目标：不是 step-plan 实施细则；不替代 `EVENT_SCHEMA.md`、`ARCHITECTURE.md` 等当前真值文档。

---

## 1. 当前系统现状（工程视角）

### 1.1 LLM 模块现状

`LlmClient` 接口当前只有一条调用路径：

```java
// LlmClient.java
String chat(List<LlmChatMessage> messages);
```

消息类型（`ChatRole`）仅有 `SYSTEM / USER / ASSISTANT`，不支持 tool call / tool result 轮次。两个 provider 实现（`GeminiLlmClient`、`ZhipuLlmClient`）在 SDK 层面均支持 function calling，但该能力目前未被接口暴露。

`LlmChatService` 当前工作：拼装 system prompt + 风险上下文 + 历史轮次 + 用户问题 → 单次调用 `llmClient.chat(messages)` → 写入 `ConversationMemory` → 通知 WebSocket handler 下发 `CHAT_REPLY`。全流程为单次文本生成，LLM 没有主动查询数据的能力。

### 1.2 解释触发机制现状

`LlmTriggerService` 已有以下门控：`triggerExplanations` 标志位 + 非 `SAFE` 过滤 + 时间窗口 cooldown（`cooldownSeconds` 可配，per-target 独立计时）+ `maxTargetsPerCall` 并发上限 + stale target 清理。`LlmRiskEventListener` 在每次 `RiskAssessmentCompletedEvent` 后先检查 `triggerExplanations` 标志，再调用 `LlmTriggerService.triggerExplanationsIfNeeded`。

当前机制对轻量单次 explanation 是够用的。对 agent loop 不够用：agent 每次循环开销是当前的 2–5 倍，而时间窗口 cooldown 仍按固定时间间隔触发，而不是语义变化触发。这意味着 LLM 可能对同一风险状态反复生成 advisory，也可能在关键场景变化时因 cooldown 未到期而不触发。

### 1.3 快照数据源分析

当前有两个 agent 可能读取的数据源，快照契约不同：

**`RiskContextHolder`**：持有 `volatile Snapshot(version, LlmRiskContext context, updatedAt)`。`Snapshot` 一旦构造不再被整体修改（只会被整体替换）。**但 `LlmRiskContext` 及其嵌套对象（`LlmRiskOwnShipContext`、`LlmRiskTargetContext`）当前仍是 Lombok `@Data` 可变 DTO**，意味着单纯"捕获 `Snapshot` 引用"无法在语义上保证内容冻结——若有其他代码路径持有同一引用并修改字段（实际当前代码未这么做，但契约不保证），agent loop 中的读取仍会看到变动。真正的快照冻结需要在 `AgentSnapshot` 构建时同步做深拷贝，或者把这些 DTO 改造为 Java record / 不可变结构。

**`DerivedTargetStateStore`**：内部是 `ConcurrentHashMap<String, TargetDerivedSnapshot>`。`getAll()` 返回的是 `unmodifiableMap`，是 live map 的视图，不是不可变副本。更重要的是，`TargetDerivedSnapshot` 虽然本身是 record，但其内部持有的 `CpaTcpaResult`、`EncounterClassificationResult`、`TargetRiskAssessment`、`CvPredictionResult` 当前仍是 Lombok `@Data` 可变对象。因此，`new HashMap<>(targetStateStore.getAll())` 只切断了 map 结构共享，**没有切断 value 对象共享**。要实现严格的快照冻结，必须在 agent loop 启动时同步做 targetDetails 的值层深拷贝，或投影为新的不可变冻结结构。

这两点共同决定了需要一个显式的 `AgentSnapshot` 设计，并在构建时承担深拷贝或改造 DTO 的工程代价（见第 3.2 节）。

### 1.4 协议与传输

双通道协议 v2 已稳定。SSE 通道事件类型：`RISK_UPDATE`、`EXPLANATION`、`ERROR`。`EXPLANATION` payload 含 `target_id`、`risk_level`、`text`、`provider`、`timestamp`、`risk_object_id`。当前无 advisory 专用字段，`text` 为纯字符串。

### 1.5 前端现状

v0.9 完成后已具备 AI 工作区壳层、解释卡片、目标选择注入链路（`selected_target_ids`）、对话消息列表与语音入口。`useAiCenterStore` 和 `useRiskStore` 构成前端消费状态层。尚无结构化 advisory 消费契约，也没有 agent loop 状态指示能力。

2026-04-18 的前端视觉升级补丁曾提议在 AI 面板中增加“Agent 工具调用”区域，但由于当前协议和 store 中不存在真实工具调用轨迹，该区域未落地，仅保留为实现参考。参考稿见 [`../VISUAL_UPGRADE_REFERENCE.md`](../VISUAL_UPGRADE_REFERENCE.md)。

---

## 2. Agent Loop 实现方向

### 2.1 核心问题

当前 LLM 是"被动单次生成"：用户问一次，LLM 基于预拼装上下文回答一次。

Agent loop 的目标：**LLM 在生成最终输出前，可以主动决定需要哪些信息，通过工具查询获取，然后推理**。对本系统，这意味着：

- advisory 生成时，LLM 先查询当前 ALARM 目标列表，再查询最高风险目标的完整 CPA 参数和相遇类型，必要时查询相关 COLREGS 规则，最终生成有规则依据的结构化建议
- 用户提问复杂场景时，LLM 可以按需查询数据，而不是依赖静态的上下文注入

### 2.2 两种工作模式

v1.0 同时支持两种模式，系统驱动的 advisory 为主线，用户驱动的 chat agent 为次线：

**Advisory Agent（系统驱动，主线）**：由场景风险状态变化触发，无需用户发起。LLM 通过 agent loop 查询当前场景数据，生成场景级结构化 advisory，通过 SSE 新增的 `ADVISORY` 事件类型下发至前端（与现有 `EXPLANATION` 事件解耦）。这是本阶段最高工程价值的改变。

**Chat Agent（用户驱动，次线）**：仅在用户已选中目标（`selected_target_ids` 非空）时启用 agent loop。未选中目标的普通对话维持现有单次生成路径，不引入 agent loop。

### 2.3 有界工具增强推理循环

两种模式共用同一个 `AgentLoopOrchestrator` 实现：

```
触发事件（场景变化 / 用户选中目标后提问）
     ↓
AgentLoopOrchestrator
     ├─ 捕获 AgentSnapshot（本循环全程读此快照，不读 live 数据）
     ├─ 组装初始消息 + 工具目录（来自 AgentToolRegistry）
     │
     ├─ [Loop, max N 次]
     │       LlmClient.chatWithTools(messages, tools)
     │              ↓ ToolCallRequest
     │       AgentToolRegistry.execute(toolName, args, snapshot)
     │              ↓ ToolResult
     │       将 ToolCallAgentMessage + ToolResultAgentMessage 追加到内部消息列表
     │              ↓ FinalText
     │       退出循环
     │
     ├─ 解析结构化输出（provider schema 约束为主路径；text parse 为降级路径）
     ├─ 发布时检查快照时效性（过期则静默丢弃，不下发）
     └─ 返回 AgentLoopResult(finalText, advisoryBlock?, snapshotVersion)
```

关键约束：

- **最大工具调用轮次**：每次 agent loop 不超过 5 轮（可配置），防止无限循环
- **无副作用工具**：工具只能读取 AgentSnapshot，或基于 AgentSnapshot 做纯函数式确定性计算；不读取 live 数据、不修改任何状态、不触发持久副作用
- **快照推理**：循环开始时冻结快照，全程不读 live 数据（见第 3.2 节）
- **单通道 advisory**：同一时刻只能有一个 advisory agent loop 在运行；运行期间触及的新触发一律**跳过**（非排队），避免队列堆积造成迟到建议

### 2.4 ADVISORY 作为新增 SSE 事件类型

经协议影响面评估，v1.0 将 advisory 定义为独立的 `ADVISORY` SSE 事件类型，而不是复用 `EXPLANATION` 的扩展字段。理由如下：

- `EXPLANATION` 当前 `target_id` 为必填字符串（`schema.d.ts` 中 `target_id: string`），前端 `useRiskStore.upsertExplanation` 直接按 `target_id` 建索引。若复用同一事件承载场景级（无单一目标）advisory，必须把 `target_id` 改为可空，并在前端 SSE 消费侧先分流再索引，事实上已经是协议级不兼容变动
- 独立事件类型让 per-target explanation 与 scene-level advisory 在 schema、触发、生命周期、前端状态管理上彻底解耦，避免语义混用
- `EVENT_SCHEMA.md` 的扩展成本相同，但独立事件的前端消费路径更清晰

SSE 通道事件类型在 v1.0 变为：`RISK_UPDATE`、`EXPLANATION`、`ADVISORY`（新增）、`ERROR`。

---

## 3. 关键设计决策

### 3.1 EXPLANATION 与 ADVISORY：语义分层

两者为独立 SSE 事件类型，职责不重叠：

| | `EXPLANATION`（现有） | `ADVISORY`（v1.0 新增） |
|---|---|---|
| SSE 事件类型 | `EXPLANATION` | `ADVISORY`（新增） |
| 触发方式 | 目标风险等级升级时触发 | 场景风险状态升级时触发 |
| 粒度 | per-target | 场景级（scene-level） |
| 内容 | 文本解释（说明发生了什么） | 结构化操纵建议（说明应该做什么） |
| 关键标识 | `target_id`（必填，索引键） | `advisory_id`（必填，生命周期键） |
| LLM 路径 | 单次生成 | agent loop |
| 生命周期字段 | 无 | `advisory_id`、`snapshot_version`、`status`、`supersedes_id`、`valid_until` |
| 前端状态 | `explanationsByTargetId` map | 独立的 active advisory slice |

两者在协议上完全解耦，前端在 SSE 消费侧按事件类型分流：`EXPLANATION` 进入现有 `upsertExplanation` 路径；`ADVISORY` 进入新增的 advisory 状态分支。

### 3.2 AgentSnapshot：快照推理的基础设施

在 `AgentLoopOrchestrator` 启动时构建一次，全循环只读该快照：

```java
record AgentSnapshot(
    long snapshotVersion,
    LlmRiskContext riskContext,                       // 深拷贝后的冻结上下文
    Map<String, TargetDerivedSnapshot> targetDetails  // 深拷贝后的冻结派生状态
) {}
```

构建方式（包含 DTO 不可变化处理）：

```java
// 捕获 RiskContextHolder 当前快照（持有引用，但内部 DTO 当前可变）
RiskContextHolder.Snapshot ctxSnapshot = riskContextHolder.getSnapshot();
// 必须对 LlmRiskContext 做深拷贝（或在 Step 0 将其改造为不可变 record），
// 否则"冻结"只在 Snapshot 整体替换层面成立，对字段级改动不成立
LlmRiskContext frozenContext = riskContextDeepCopier.copy(ctxSnapshot.context());
// 必须对 targetDetails 做 value 层深拷贝；new HashMap(...) 只复制 map 结构，不复制其中的可变结果对象
Map<String, TargetDerivedSnapshot> frozenTargets =
    targetStateSnapshotDeepCopier.copyAll(derivedTargetStateStore.getAll());
AgentSnapshot agentSnapshot = new AgentSnapshot(
    ctxSnapshot.version(), frozenContext, frozenTargets
);
```

Step 0 对 DTO 不可变化有两条可选路径：

- **路径 A（推荐长期）**：把 `LlmRiskContext` / `LlmRiskOwnShipContext` / `LlmRiskTargetContext` 从 Lombok `@Data` 改造为 Java record。调用方更新较多，但一次性解决问题
- **路径 B（短期折中）**：保留现有 DTO / 引擎结果对象，引入 `LlmRiskContextDeepCopier` 与 `TargetStateSnapshotDeepCopier` 组件，`AgentSnapshot` 构建时深拷贝。改动面小，但每次 agent loop 启动额外开销

v1.0 采用**路径 B**，在 step-plan 阶段实施时若发现深拷贝成本显著，再评估转入路径 A。

所有工具实现（Step 2）从 `AgentSnapshot` 读取，不持有任何 live 数据源引用。

发布前时效性检查：agent loop 结束时，比较 `agentSnapshot.snapshotVersion()` 与 `riskContextHolder.getVersion()`。若版本差超过可配置阈值（如 5 帧），静默丢弃 advisory，不下发。

### 3.3 Agent 消息模型扩展

`LlmChatMessage` 仅支持 `SYSTEM / USER / ASSISTANT`，不适用于 agent loop 的工具调用轮次。定义独立的内部消息类型，不污染 `ConversationMemory`：

```java
sealed interface AgentMessage permits
    TextAgentMessage,       // 对应 SYSTEM / USER / ASSISTANT 角色
    ToolCallAgentMessage,   // LLM 请求调用工具（callId, toolName, args）
    ToolResultAgentMessage  // 工具执行结果回填（callId, toolName, result text）
```

`AgentLoopOrchestrator` 维护 `List<AgentMessage>` 作为内部消息历史。循环结束后，只将最终的一对 `(用户原始问题, LLM 最终回答)` 写入 `ConversationMemory`——工具调用中间轮次不进入 `ConversationMemory`，不污染用户可见的对话历史。

`LlmClient.chat(List<LlmChatMessage>)` 的现有接口不变，通过内部适配器将 `TextAgentMessage` 列表转换为 `LlmChatMessage` 列表调用。

### 3.4 ADVISORY Schema（独立事件 + 生命周期）

`ADVISORY` 作为独立 SSE 事件类型，payload 完整样例：

```json
{
  "event_id": "server-event-xxx",
  "advisory_id": "advisory-uuid",
  "risk_object_id": "...",
  "snapshot_version": 12345,
  "scope": "SCENE",
  "status": "ACTIVE",
  "supersedes_id": null,
  "valid_until": "2026-04-17T10:25:00Z",
  "risk_level": "ALARM",
  "provider": "gemini",
  "timestamp": "...",
  "summary": "目标 413999001 进入紧急交叉相遇态势，本船为让路船，建议立即右转 15–20 度",
  "affected_targets": ["413999001"],
  "recommended_action": {
    "type": "COURSE_CHANGE",
    "description": "建议右转约 15–20 度",
    "urgency": "IMMEDIATE"
  },
  "evidence_items": [
    "目标 413999001 DCPA 0.12 nm，低于安全阈值 0.5 nm",
    "TCPA 2.3 分钟，进入紧急阈值（< 5 分钟）",
    "相遇态势：交叉相遇（CROSSING），本船依据 COLREGS Rule 15 为让路船",
    "假设本船立即右转 20 度，预估 CPA 扩大至约 0.6 nm（由 EvaluateManeuverTool 计算）"
  ]
}
```

字段语义：

| 字段 | 说明 |
|---|---|
| `event_id` | 服务端事件 ID，沿用现有 SSE envelope 约定 |
| `advisory_id` | 本次 advisory 的唯一标识，用于生命周期追踪 |
| `risk_object_id` | 对应的风险快照帧 ID（与 `EXPLANATION` 同义） |
| `snapshot_version` | 快照版本号，供审计与追溯 |
| `scope` | 固定为 `SCENE`（场景级别） |
| `status` | `ACTIVE` / `SUPERSEDED` |
| `supersedes_id` | 替代的上一条 `advisory_id`，若为首条则 `null` |
| `valid_until` | advisory 建议的有效时间上限，到期后前端停止展示 |
| `risk_level` | 场景最高风险等级 |
| `summary` | 一句话摘要，作为前端卡片主标题 |
| `affected_targets` | 受影响目标列表 |
| `recommended_action` | 操纵建议结构（`type`、`description`、`urgency`） |
| `evidence_items` | 来自工具查询结果的可验证事实项 |

**`evidence_items` 的生成方式**：由 agent 工具查询结果提供事实，LLM 负责归纳表达。每个 evidence_item 必须是可验证的事实：

- 来自查询类工具（`GetTargetDetailTool` 等）的数值读数
- 来自规则查询工具（`QueryRegulatoryContextTool`）的规则条款引用
- 来自确定性计算工具（`EvaluateManeuverTool`，见第 3.8 节）的假设评估输出

prompt 约束禁止 LLM 自由编造数值推断。这使 advisory 可审计、可追溯，适合航海安全场景。

Step 3 的基线完成标准不依赖后两类增强工具：在 Step 5 尚未引入前，`evidence_items` 只要求包含来自 Step 2 查询工具的数值与状态事实。规则条款引用和操纵假设评估项属于 Step 5 的增强内容，不阻塞 Step 3 主线完成。

生命周期规则：
- 每次新 advisory 发布时，`supersedes_id` 填入上一次 advisory 的 `advisory_id`，前端收到后将旧 advisory 的 `status` 置为 `SUPERSEDED`
- 前端在 `valid_until` 时间到期后停止展示该 advisory

### 3.5 触发策略：语义变化驱动

**EXPLANATION 触发改造**（替换现有时间窗口 cooldown）：

引入 `Map<String, RiskLevel> lastExplainedLevelMap` 作为语义去重 key。仅当目标当前风险等级高于上次生成解释时的等级时才触发（level upgrade）。等级下降后再升级，视为新触发。时间窗口 cooldown 作为同等级重复生成的兜底保护保留，但阈值大幅调高（不再是主要门控）。

**ADVISORY 触发（新增）**：

引入 `SceneRiskState`：追踪当前场景中所有目标的最高风险等级（`highestRiskLevel`）和当前是否有 advisory 正在生成（`generatingFlag`）。

触发条件（满足任一）：
- 场景最高风险等级升级（如 `WARNING → ALARM`）
- 最高风险目标的 TCPA 穿越可配置阈值（如 < 5 分钟、< 3 分钟）

并发控制：`generatingFlag` 为 `true` 时，任何新触发均被**跳过**（非排队），与第 2.3 节循环约束保持一致。Advisory 生成结束（成功或失败）后清除标志。跳过的触发若仍然满足条件，会在下一次风险帧中自然重新触发，不依赖队列恢复。

### 3.6 结构化输出：provider schema 约束为主路径

`ADVISORY` 的结构化输出不以"LLM 返回 JSON 字符串 → 后端 parse"作为主路径，而以 provider 原生 schema 约束为主路径：

- **Gemini**：在生成配置中设置 `response_schema`，SDK 层面约束输出格式，返回结构化对象
- **Zhipu**：使用 JSON mode（`response_format: {type: "json_object"}`），降低输出格式不稳定风险
- **降级路径**：若 provider 原生 schema 返回失败或输出无法映射为合法 `AdvisoryPayload`，尝试从响应文本中 JSON parse；仍失败时 **不发布 `ADVISORY` 事件**，记录告警日志，可选发布 `ERROR` 事件（`error_code=ADVISORY_SCHEMA_FAILED`）供前端感知失败。不向前端发送语义上模糊的残缺 advisory

### 3.7 Chat Agent：selection-constrained

Chat agent 模式仅在用户已选中目标（`selected_target_ids` 非空）时激活 agent loop。逻辑：

- 无 `selected_target_ids`：走现有单次生成路径，不引入 agent loop，保持现有延迟特性
- 有 `selected_target_ids` + agent mode 开关开启：以选中目标为切入点启动 agent loop，LLM 可进一步查询选中目标的详情和相关场景数据

此设计直接复用现有 UI 的目标选择链路，降低 token 和延迟成本，同时将 agent loop 限定在用户有明确意图的场景中。

Agent mode 开关在 `LlmProperties` 中配置，默认关闭，允许独立灰度。

### 3.8 GraphRAG：COLREGS 知识图谱

v1.0 纳入 COLREGS 规则图谱的基础落地，历史危险案例数据延后。

**实体与关系设计**：

- 实体：`Rule(id, article, summary, principle)`、`EncounterSituation(type, description)`、`ManeuverAction(type, description, rationale)`
- 关系：`APPLIES_TO(rule → situation)`、`RECOMMENDS(situation → action)`

**存储方案**：v1.0 采用内存图（startup 从资源文件加载），不引入外部图数据库。COLREGS 72 条全规则数 量有限（72 条主规则），内存图足够。图数据库扩展留待 v1.1 有历史案例数据后引入。

**规则查询工具**：`QueryRegulatoryContextTool(encounterType, riskLevel)` → 通过图遍历返回与当前相遇态势相关的规则摘要和推荐操纵动作。

**操纵假设评估工具（确定性计算，非图谱）**：`EvaluateManeuverTool(maneuverType, magnitude, targetId)` → 复用现有 `CpaTcpaBatchCalculator`，基于 AgentSnapshot 中本船状态，假设本船执行给定操纵（如"右转 20 度"）后，重新计算该假设下与目标的 DCPA/TCPA 预估值。这属于**无副作用的纯计算工具**，不读取 live 数据、不写回任何状态。这是 `evidence_items` 中出现"假设右转 X 度后 CPA 扩大至 Y nm"这类评估型事实的唯一合法来源——LLM 只能从工具结果引用，不能自行编造。

此工具不属于 GraphRAG，但与 GraphRAG 在 Step 5 同期引入，使 advisory 的 `recommended_action.description` 背后有可验证的定量依据。工具实现复杂度中等：本质是对现有 CPA 批量计算器的参数化封装，不引入新的物理模型。

Advisory `evidence_items` 中可以包含 COLREGS 规则引用（如 "COLREGS Rule 15：交叉相遇局面，让路船应采取避让行动"），使建议具备规则依据。

---

## 4. 阶段拆分

### Step 依赖关系图

```
Step 0（触发策略 + AgentSnapshot 类型定义）
     ↓ AgentSnapshot 类型
Step 1（LlmClient 扩展 + Agent 消息模型）
     ↓ ToolDefinition 类型 + chatWithTools 接口
Step 2（工具注册表 + 查询工具）
     ↓ AgentToolRegistry + tools
Step 3（Advisory path：编排器 + schema + 协议 + 前端）  ← 主线
     ↓
Step 4（Chat Agent path：selection-constrained）         ← 次线
Step 5（GraphRAG：COLREGS 图谱 + 工具注册）            ← 独立，注册进 Step 2 成果
```

Step 0 和 Step 1 可并行启动（Step 1 不依赖 Step 0 的实现，仅依赖 `AgentSnapshot` 类型定义，可先定义类型）。Step 5 依赖 Step 2 的 `AgentToolRegistry` 注册接口，与 Step 3 并行可行。

---

### Step 0：触发策略收敛 + AgentSnapshot 类型定义

**目标**：为 advisory 触发建立语义变化驱动机制；定义 `AgentSnapshot` 不可变快照类型。

**主要工作**：

后端：
- 定义 `AgentSnapshot` record（`snapshotVersion`、`riskContext`、`frozenTargetDetails`）
- 引入 `LlmRiskContextDeepCopier`（第 3.2 节路径 B）：负责 `LlmRiskContext` 及嵌套 `LlmRiskOwnShipContext` / `LlmRiskTargetContext` 的深拷贝，使 `AgentSnapshot` 在 DTO 仍为 Lombok `@Data` 的前提下实现真正的字段级冻结
- 引入 `TargetStateSnapshotDeepCopier`：负责 `TargetDerivedSnapshot` 及其嵌套 `CpaTcpaResult` / `EncounterClassificationResult` / `TargetRiskAssessment` / `CvPredictionResult` 的深拷贝，避免 map 副本仍共享可变 value 对象
- `RiskContextHolder` 暴露返回完整 `Snapshot` 的方法（`getSnapshot()`），允许 agent 在循环启动时捕获引用
- 改造 `LlmTriggerService` explanation 触发逻辑：引入 `lastExplainedLevelMap`，按 `targetId → RiskLevel` 语义去重，替换纯时间窗口 cooldown 为主门控
- 新增 `SceneRiskStateTracker`：管理 `highestRiskLevel`、`generatingFlag`（run-to-run 跳过语义）、TCPA 阈值穿越检测
- 新增 advisory 触发入口（暂不实现 advisory 生成，只定义触发回调接口）

**依赖**：无。可立即启动。

---

### Step 1：Provider-neutral Agent Contract

**目标**：扩展 `LlmClient` 接口支持工具调用；定义 agent 内部消息模型。

**主要工作**：

- 定义数据类型：`ToolDefinition(name, description, parametersJsonSchema)`、`AgentStepResult`（密封类型，两个子类：`ToolCallRequest(callId, toolName, args)` 和 `FinalText(text)`）
- 定义 `AgentMessage` sealed interface（见第 3.3 节）
- `LlmClient` 新增方法：
  ```java
  AgentStepResult chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools);
  ```
- `GeminiLlmClient`：将 function declaration 注册和 function response 填充映射到 `AgentStepResult`；原生 schema 约束通过生成配置注入
- `ZhipuLlmClient`：映射 SDK function calling 到 `AgentStepResult`；若当前模型不支持，fallback 到 JSON mode + `FinalText`，并记录日志；具体支持程度在 step-plan 实施时依 SDK 版本确认
- 现有 `chat(List<LlmChatMessage>)` 通过内部适配器调用 `chatWithTools`（空工具目录），不破坏现有路径
- 单元测试：mock 两种 provider 响应，覆盖 `ToolCallRequest` 和 `FinalText` 两条分支

**依赖**：`AgentSnapshot` 类型定义（来自 Step 0）。

---

### Step 2：Agent 工具注册表与查询工具

**目标**：实现所有无副作用工具，建立工具注册与调度机制。

**主要工作**：

- 定义 `AgentTool` 接口：`ToolDefinition getDefinition()` + `ToolResult execute(ToolCall call, AgentSnapshot snapshot)`
- 实现工具（全部从 `AgentSnapshot` 读取；允许无副作用的纯计算工具，但不持有 live 数据源引用、不写回任何状态）：
  - `GetRiskSnapshotTool`：返回当前场景摘要（本船状态 + 所有目标风险等级汇总 + 快照版本）
  - `GetTargetDetailTool`：返回指定 targetId 的完整数据（CPA metrics、相遇类型、轨迹预测、置信度）
  - `GetTopRiskTargetsTool`：按风险分数返回前 N 个目标，可按 minLevel 过滤
  - `GetOwnShipStateTool`：本船位置、动力学参数、安全域尺寸
  - （`EvaluateManeuverTool` 和 `QueryRegulatoryContextTool` 在 Step 5 中引入并注册至此注册表）
- `AgentToolRegistry`：工具注册、`getToolDefinitions()` 列表获取、`execute(callId, toolName, args, snapshot)` 统一调度
- 单元测试：每个工具独立测试，使用预构建 `AgentSnapshot`

**依赖**：Step 0（`AgentSnapshot` 类型），Step 1（`ToolDefinition`、`ToolCall`、`ToolResult` 类型）。

---

### Step 3：Advisory Path（主线）

**目标**：实现完整的 advisory 生成链路，新增独立 `ADVISORY` SSE 事件类型，前端消费 advisory。

**主要工作**：

后端：
- 实现 `AgentLoopOrchestrator`：接收初始消息、工具注册表、AgentSnapshot、最大迭代数；运行有界循环；结束后调用结构化解析（provider schema 主路径 + text parse 降级；两次失败不下发 advisory，发布 `ADVISORY_SCHEMA_FAILED` 错误事件）；发布前时效性检查（版本差超阈值则丢弃）
- 定义独立的 `AdvisoryPayload` Java record（字段见第 3.4 节样例：`event_id`、`advisory_id`、`risk_object_id`、`snapshot_version`、`scope`、`status`、`supersedes_id`、`valid_until`、`risk_level`、`provider`、`timestamp`、`summary`、`affected_targets`、`recommended_action`、`evidence_items`）
- `RiskSseEventType` 枚举新增 `ADVISORY`；`SseEventFactory` 支持构建 `ADVISORY` 事件
- 新增 `AdvisoryService`：管理 advisory 生成全生命周期（接收场景触发 → 构建 AgentSnapshot → 调用 AgentLoopOrchestrator → 填写 advisory_id / supersedes_id / valid_until → 调用 `RiskStreamPublisher.publishAdvisory`）
- `RiskStreamPublisher` 新增 `publishAdvisory(AdvisoryPayload)` 方法
- 更新 `EVENT_SCHEMA.md`：新增 `ADVISORY` 事件类型的完整 payload 说明；新增 `ADVISORY_SCHEMA_FAILED` 错误码

前端：
- `schema.d.ts`：新增 `AdvisoryPayload` 类型；`RiskSseEventType` 枚举新增 `ADVISORY` 取值
- `riskSseService.ts`：新增 `ADVISORY` 事件分支，解析为 `AdvisoryPayload` 后推送到 store
- `useRiskStore.ts`：新增 `activeAdvisory` 状态字段和相关 selector；收到新 advisory 时将旧 advisory 状态置为 `SUPERSEDED`
- 新增 `AdvisoryCard` 组件（或在 `RiskExplanationPanel` 内独立一块区域）：展示 `summary` 作为主标题、`recommended_action` urgency badge、`evidence_items` 列表、生命周期状态标记
- Advisory 生命周期管理：`valid_until` 到期时停止展示；SUPERSEDED advisory 自动归档
- 运行态契约：chat 路径 pending 状态由前端 `pendingMessageId` 机制覆盖（已有），advisory 路径无用户等待问题（系统异步推送）

Step 3 完成边界：
- `ADVISORY` 事件可独立下发并被前端消费
- `evidence_items` 至少支持来自 Step 2 查询工具的数值与状态事实
- 不要求已集成 `QueryRegulatoryContextTool` 和 `EvaluateManeuverTool`
- Step 5 的规则依据与操纵假设评估属于 Step 3 之上的增强层，而不是 Step 3 的阻塞依赖

**依赖**：Step 0 + Step 1 + Step 2。

---

### Step 4：Chat Agent Path（次线，feature-flagged）

**目标**：在 `selected_target_ids` 非空时启用 selection-constrained chat agent。

**主要工作**：

- `LlmProperties` 新增 `agentModeEnabled` 开关（默认 `false`）
- `LlmChatService`：当 `agentModeEnabled = true` 且请求携带 `selected_target_ids` 时，委托 `AgentLoopOrchestrator` 处理；否则维持现有单次生成路径
- Chat agent 结果以 `CHAT_REPLY` 的普通 `content` 字段下发，不带 advisory block
- Agent 工具调用中间轮次不写入 `ConversationMemory`；仅最终 `USER / ASSISTANT` 轮次写入
- Zhipu 在此路径上与 advisory 路径共享 fallback 机制

**依赖**：Step 3（`AgentLoopOrchestrator`、`AgentSnapshot` 构建路径）。

---

### Step 5：GraphRAG Foundation + 操纵假设评估工具（COLREGS 知识图谱）

**目标**：构建 COLREGS 规则知识图谱，为 advisory 提供规则依据；同期引入操纵假设评估工具，使 `evidence_items` 中的定量建议有确定性来源。

**主要工作**：

- 确定 COLREGS 72 规则的语料格式（已有公开文本，需整理成可加载结构）
- 定义图实体：`Rule(id, article, summary, applicableSituations[])`、`EncounterSituation(type, description)`、`ManeuverAction(type, description)`
- 定义关系：`APPLIES_TO(ruleId → situationType)`、`RECOMMENDS(situationType → actionType)`
- 图实现：内存 adjacency map，从 classpath JSON 资源文件加载，spring 启动时初始化；抽象 `GraphQueryPort` 接口
- 实现 `QueryRegulatoryContextTool`：接受 `encounterType`（`HEAD_ON / CROSSING / OVERTAKING`）和 `riskLevel` 参数，遍历图，返回相关规则摘要和推荐动作的文本 tool result
- 实现 `EvaluateManeuverTool`：参数化封装 `CpaTcpaBatchCalculator`，输入 `maneuverType`（`COURSE_CHANGE / SPEED_CHANGE`）、`magnitude`、`targetId`，基于 `AgentSnapshot` 中本船状态重新计算假设操纵后的 DCPA/TCPA，返回结构化评估结果。此工具不属于 GraphRAG，但解决 advisory `evidence_items` 中定量评估类事实的来源合法性问题
- 两工具均注册至 `AgentToolRegistry`（Step 2 成果）
- 单元测试：图加载、`QueryRegulatoryContextTool` 覆盖三种相遇态势；`EvaluateManeuverTool` 覆盖典型右转 / 减速场景，与手工 CPA 计算对照

**关于向 GraphRAG 扩展**：v1.0 内存图只做 COLREGS 规则。历史案例引入后，实体类型（`HistoricalIncident`、`CaseEvidence`）和关系（`SIMILAR_TO`、`RESULTED_FROM`）的加入，以及外部图数据库的接入，是独立演进步骤。内存图的接口设计须支持这一扩展方向（定义 `GraphQueryPort` 接口，内存图实现只是其中一个 adapter）。

**依赖**：Step 2（`AgentToolRegistry`），可与 Step 3 / Step 4 并行。

---

## 5. 风险与 Trade-off

### 5.1 主要风险

**LLM 输出结构稳定性**：即使使用 provider 原生 schema 约束，不同 provider 在 schema 约束的实现质量上存在差异（Gemini schema 支持较完整，Zhipu 的 JSON mode 约束弱于 response schema）。降级路径（text parse）必须在 Step 3 实施时同步验证，而不是事后补充。

**Advisory agent loop 延迟**：5 轮工具调用叠加 provider 网络延迟，总延迟可能达 15–45 秒。Advisory 是异步推送，用户不等待，这一延迟对 advisory path 影响有限。Chat agent path（Step 4）延迟更明显，这是 selection-constrained 设计和 feature flag 的重要原因之一。

**`ConversationMemory` 与 chat agent 的兼容性**：`AgentLoopOrchestrator` 维护独立的内部 `List<AgentMessage>`，只将最终 USER/ASSISTANT 对写入 `ConversationMemory`。这个边界在 Step 4 实施时必须严格保持，任何工具调用中间步骤进入 `ConversationMemory` 都会破坏多轮对话的语义一致性。

**snapshot 过期阈值选取**：版本差阈值过小会导致 advisory 频繁被丢弃（特别是在 LLM 延迟较高时）；过大会让迟到建议误下发。这个参数需要结合测量的实际生成延迟和风险帧频率来确定，step-plan 阶段应先定义测量方式。

### 5.2 关键 Trade-off

**ADVISORY 作为新事件类型 vs 扩展 EXPLANATION（已决议：新事件类型）**

复用 `EXPLANATION` 的代价是：`EXPLANATION.target_id` 当前为必填字符串，前端 `useRiskStore.upsertExplanation` 直接按 `target_id` 建索引；若承载 scene-level advisory，`target_id` 必须改为可空并在 SSE 消费侧先分流，这已经是协议级不兼容变动。在这种情况下，新事件类型反而是更小的改动面，并且在 schema、触发、生命周期、前端状态管理上彻底解耦。代价是 `EVENT_SCHEMA.md` 需新增一个事件类型文档条目，前端 SSE 服务需新增一个事件分支。

**chat agent 全量 vs selection-constrained**

全量 chat agent（任何对话都走 agent loop）覆盖面更广，但引入不可预期的延迟和 token 消耗，且对不需要工具的简单问题是纯开销。Selection-constrained 设计保证了现有对话体验不降级，同时在用户有明确意图（已选中目标）时提供增强能力。这个决策可以随使用数据的积累再调整。

**GraphRAG 内存图 vs 外部图数据库**

COLREGS 72 条主规则总量有限（全文约 3 万词），内存图在 v1.0 完全可行。外部图数据库（Neo4j 等）在数据量、关系复杂度和实时查询性能上有优势，但引入独立依赖会增加部署复杂度。`GraphQueryPort` 接口设计确保在需要时可以替换实现，而不改动 tool 层。

### 5.3 已排除的路径

**先普通 RAG 再升级 GraphRAG**：SOURCEBOOK §8 已明确排除此路径。v1.0 直接以图结构组织 COLREGS 知识，不经过 chunk-based RAG 过渡。

**流式 advisory 中间步骤**：将 agent 每一步工具调用的结果实时推送给前端（如"正在查询目标 413999001 的 CPA 数据…"）。此特性有 UX 价值，但需要新的 SSE 序列协议设计，与 v1.0 主线复杂度不匹配。留作 v1.1 增强方向。

**多目标 advisory 聚合**：对多个 ALARM 目标分别生成 advisory 再聚合冲突消解。场景价值高但实现复杂（需多次 agent loop + 聚合 prompt）。v1.0 的 advisory 以最高风险目标为主锚点，`affected_targets` 列出所有受影响目标，全局冲突消解留待后续。

### 5.4 需要同步更新的真值文档

本规划引入的范围决策与现有真值文档存在不一致，需要在 step-plan 开始前或同步期间更新：

- [docs/TODO.md](../../TODO.md) 当前仅保留未挂入实施链的 agent backlog，因此不应再重复登记 Step 0–5 主线事项；保留在 TODO 的只应是流式 advisory、历史案例 GraphRAG、外部图数据库接入等外扩项
- [docs/EVENT_SCHEMA.md](../../EVENT_SCHEMA.md) 需要在 Step 3 实施时新增 `ADVISORY` 事件类型的完整定义，并将 `RiskSseEventType` 枚举加入 `ADVISORY` 取值
- [docs/TODO.md](../../TODO.md) 中仍保留的 agent 项只应是当前未挂入 Step 0–5 的剩余 backlog；若后续把某个外扩项正式挂入新的 milestone / step 链，应同步从 TODO 移除

本次文档同步后，`docs/TODO.md` 仅保留未实现且未挂到当前 step / milestone 链的 agent backlog；Step 0–5 主线事项不再重复登记于 TODO。后续仅在以下场景回收至 TODO：

- 某项被明确移出 Step 0–5 且暂未挂入新的 milestone / step 链
- `v1.0` 外扩方向（如流式 advisory 中间步骤、历史案例 GraphRAG、外部图数据库接入）仍无明确 owner

`EVENT_SCHEMA.md` 等真值文档仍按各 step 实施时同步更新。

---

## Appendix A. Active Deviations

### A.1 Step 3 快照构建位置前移到触发点

- affected body item：§4 Step 3 中 `AdvisoryService` “接收场景触发 → 构建 AgentSnapshot”
- triggered by：Step 3
- date：2026-04-24
- reason：better-approach
- temporary handling：`docs/v1.0/agent/step3.md` 记录 `SceneRiskStateTracker` 在触发瞬间通过 `AgentSnapshotFactory.build()` 构建冻结快照，并通过 `AdvisoryTriggerPort.onAdvisoryTrigger(AgentSnapshot snapshot, Runnable onComplete)` 传入 `AdvisoryService`
- canonical resolution：Step 3 正文在版本收敛时应改为“接收已冻结的 AgentSnapshot → 调用 AgentLoopOrchestrator → 填写 advisory 生命周期字段 → 发布”

### A.2 Step 3 结构化解析与时效性检查移出 orchestrator

- affected body item：§4 Step 3 中 `AgentLoopOrchestrator` “结束后调用结构化解析…发布前时效性检查”
- triggered by：Step 3
- date：2026-04-24
- reason：better-approach
- temporary handling：`docs/v1.0/agent/step3.md` 记录 `AgentLoopOrchestrator` 只返回 `AgentLoopResult`；`AdvisoryService` 负责 `AdvisoryOutputParser` 调用、schema failure 处理、snapshot version lag 检查和 SSE 发布
- canonical resolution：Step 3 正文在版本收敛时应改为“`AgentLoopOrchestrator` 只编排有界 tool loop；`AdvisoryService` 在 loop 完成后执行结构化解析和发布前时效性检查”

### A.3 Step 4 与 Step 5 之间插入 Step 4A

- affected body item：§4 Step 依赖关系图与 Step 5 前置顺序
- triggered by：Step 4A
- date：2026-04-25
- reason：better-approach
- temporary handling：[`step4A.md`](./step4A.md) 作为 Step 4 与 Step 5 之间的额外实施步骤，接管显式 Chat / Agent 模式切换、chat agent 工具步骤可见性和演示所需本船强约束跟随能力；Step 5 仍保持 COLREGS GraphRAG 与操纵假设评估工具范围不变
- canonical resolution：§4 阶段拆分应在 Step 4 后增加“Step 4A：Explicit Agent Mode + Runtime Visibility”，依赖 Step 4，完成后再进入 Step 5；Step 5 目标和依赖仍为 GraphRAG Foundation + 操纵假设评估工具

### A.4 Chat agent 激活条件从 selected targets 改为显式 agent_mode

- affected body item：§3.7 Chat Agent：selection-constrained、§4 Step 4 目标与主要工作、§5.2 “chat agent 全量 vs selection-constrained”
- triggered by：Step 4A
- date：2026-04-25
- reason：better-approach
- temporary handling：[`step4A.md`](./step4A.md) 定义 `CHAT` WebSocket payload 新增 `agent_mode = CHAT | AGENT`；`selected_target_ids` 不再参与 agent path 激活，只作为普通 chat 或 agent prompt 的可选上下文
- canonical resolution：§3.7 应改写为“Chat Agent 由用户显式选择的 `agent_mode` 激活；`CHAT` 模式始终走普通 prompt 拼接路径，`AGENT` 模式始终走 agent loop；`selected_target_ids` 只提供可选目标上下文，不控制路由”

### A.5 Chat agent 工具执行过程通过 WebSocket AGENT_STEP 可见

- affected body item：§5.3 “流式 advisory 中间步骤”排除项、§4 Step 4 “CHAT_REPLY 的普通 content 字段下发”边界
- triggered by：Step 4A
- date：2026-04-25
- reason：better-approach
- temporary handling：[`step4A.md`](./step4A.md) 将 chat agent 工具调用过程定义为 WebSocket `AGENT_STEP` 下行事件；该事件按 `reply_to_event_id` 归属于用户请求，展示工具名、执行状态和简短过程文案，但不改变最终 `CHAT_REPLY` / `ERROR` 结果语义，也不实现 SSE advisory 流式协议
- canonical resolution：§5.3 应区分“chat WebSocket agent step visibility”和“advisory SSE streaming”。前者纳入 Step 4A，后者仍作为后续独立协议能力处理

### A.6 Step 4A 与 Step 5 之间插入 Step 4B

- affected body item：§4 Step 依赖关系图与 Step 5 前置顺序、§5.4 需要同步更新的真值文档
- triggered by：Step 4B
- date：2026-04-27
- reason：better-approach
- temporary handling：[`step4B.md`](./step4B.md) 作为 Step 4A 与 Step 5 之间的额外实施步骤，接管“已解除风险解释生命周期”能力：后端和前端均把已解除风险 explanation 从直接删除改为 `RESOLVED` 状态限时限量保留，按最多 20 条、最长 30 分钟的统一规则驱逐；前端不新增独立“已解除”区域，只在同一 explanation 列表内将 resolved 卡片沉底、透明化并标记 `已解除`；前端“清理过期风险解释”按钮必须以后端回执的 `event_id` 删除列表为准，以保持前后端缓存一致；基于 resolved 卡片追问时，LLM 上下文注入“该风险已解除”标签和当前状态
- canonical resolution：§4 阶段拆分应在 Step 4A 后增加“Step 4B：Resolved Risk Explanation Lifecycle”，依赖 Step 4A，完成后可继续进入模型路由或 Step 5；`docs/TODO.md` 不再重复登记“已解除风险解释生命周期”

### A.7 Step 4B 与 Step 5 之间插入 Step 4C

- affected body item：§4 Step 依赖关系图与 Step 5 前置顺序、§5.4 需要同步更新的真值文档
- triggered by：Step 4C
- date：2026-04-27
- reason：better-approach
- temporary handling：[`step4C.md`](./step4C.md) 作为 Step 4B 与 Step 5 之间的额外实施步骤，接管任务级模型路由与前端 provider 选择；后端通过 `LlmClientRegistry` 替换 `@Qualifier("zhipu"|"gemini")` 硬编码路由，前端 provider 可用性并入现有 WebSocket `CAPABILITY` 消息，不新增 `GET /api/llm/providers` 主路径
- canonical resolution：§4 阶段拆分应在 Step 4B 后增加“Step 4C：Task-Level Model Routing And Frontend Provider Selection”，依赖 Step 4A 的 `CAPABILITY` 握手；`docs/TODO.md` 不再重复登记“任务级模型路由与前端模型选择（第二阶段）”
