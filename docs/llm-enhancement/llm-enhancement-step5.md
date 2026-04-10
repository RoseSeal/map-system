# Step 5 执行方案：依赖方向收口与风险解释增强

## Task Summary

Step 1–4 完成了 LLM 客户端抽象、prompt 外部化、风险上下文注入、多轮对话管理。当前 LLM 模块仍存在三类遗留问题：

1. **反向依赖**：`LlmTriggerService` 直接持有 `RiskStreamPublisher`（transport 层），LLM 模块对 transport 层形成反向依赖。
2. **协议 DTO 泄漏**：`LlmChatService`、`VoiceChatService`、`ChatPayloadValidator` 直接消费 `dto.websocket.ChatRequestPayload` / `SpeechRequestPayload` / `ChatErrorCode`，LLM 层与 WebSocket 协议模型耦合。
3. **风险解释质量**：system prompt 过于简短，用户消息缺少 `currentDistanceNm` 和相对方位角（字段未注入或不存在），输出缺乏专业航海术语和结构化约束。

Step 5 解决上述三类问题。实质是一次跨 `service.llm`、`transport`、`pipeline`、DTO 层的依赖收口重构，加上 explanation 链路的 prompt 增强。完成后 `service.llm` 的依赖方向收敛为：**仅依赖 `llm.*`（内部 DTO/客户端）、`domain.*` 和 `config.properties`，不依赖 `dto.websocket`、`engine.risk`、`transport`**。

> **关于 step 4 遗留**：`LlmChatService` 输入从协议 DTO 收口为内部类型，在 step 4 规划中已提及但未实施。该工作并入 step 5 而非追补 step 4.1，因为 step 5 的主线之一即接口规范化，合并处理比单独追补更自然。

---

## Scope / Non-goals

### In scope

| # | 工作项 | 动机 |
|---|--------|------|
| 1 | `LlmTriggerService` 去除 `RiskStreamPublisher` 依赖，改为回调 | 消除 LLM → transport 反向依赖 |
| 2 | 引入 `LlmChatRequest` / `LlmVoiceRequest` 内部请求类型 | 将协议 DTO 挡在 transport 层 |
| 3 | 引入 `LlmErrorCode`（仅含 LLM 运行时错误） | LLM 层不再导入 `dto.websocket.ChatErrorCode` |
| 4 | 风险等级字符串抽取为 `domain.RiskLevel` 共享枚举 | 消除 LLM → `engine.risk` 依赖，同时避免常量复制 |
| 5 | 协议校验逻辑上移至 transport 层 | `ChatPayloadValidator` 不再导入协议 DTO |
| 6 | 增强 `system-risk-explanation.txt` | 提升风险解释专业性与可控性 |
| 7 | `LlmExplanationService` 用户消息补入 `currentDistanceNm` | 利用已有字段，提供更完整态势 |
| 8 | 新增相对方位角计算，注入 explanation 用户消息 | 为方位描述提供确定性输入，避免 LLM 从经纬度推断 |

### Non-goals

- **不做包迁移**：不将 `service.llm` 迁移到 `LLM_ENHANCEMENT_PLAN.md` 目标结构中的 `llm/service`、`llm/context`、`llm/memory`。Step 5 只解决依赖方向问题，不做物理包重组。包迁移作为独立结构性变更，在 step 5 完成后再评估是否需要。
- 不拆分独立服务或引入 RPC。
- 不新增协议字段或改变前端行为。
- 不调整 chat 链路的 system prompt（chat prompt 已足够，独立迭代）。
- 不引入结构化 JSON 输出或 function calling。
- 不改变 `LlmExplanation` 的对外数据结构。

---

## Design Decisions

### D1：`LlmTriggerService` 回调化

**现状**：`LlmTriggerService` 直接持有 `RiskStreamPublisher`，在 `triggerExplanationsIfNeeded()` 内部调用 `riskStreamPublisher.publishExplanation()` 和 `riskStreamPublisher.publishError()`。

**方案**：方法签名增加两个回调参数：

```java
void triggerExplanationsIfNeeded(
    LlmRiskContext context,
    Consumer<LlmExplanation> onExplanation,
    BiConsumer<LlmRiskTargetContext, LlmExplanationService.LlmExplanationError> onError
)
```

`LlmTriggerService` 将 `onExplanation` 和 `onError` 透传给 `LlmExplanationService.generateTargetExplanationsAsync()`。由 `ShipDispatcher` 在调用时注入具体的发布逻辑，包括 `LlmErrorCode` → `ChatErrorCode` 的映射：

```java
// ShipDispatcher.publishRiskSnapshot()
llmTriggerService.triggerExplanationsIfNeeded(
    snapshot.llmContext(),
    explanation -> riskStreamPublisher.publishExplanation(explanation, riskObjectId),
    (target, error) -> {
        ChatErrorCode code = error.errorCode() == LlmErrorCode.LLM_TIMEOUT
            ? ChatErrorCode.LLM_TIMEOUT
            : ChatErrorCode.LLM_REQUEST_FAILED;
        riskStreamPublisher.publishError(code, error.errorMessage(),
            target == null ? null : target.getTargetId());
    }
);
```

保留 `LLM_TIMEOUT` 与普通失败的区分，与当前 `LlmExplanationService` 行为一致。

**不采用的方案**：引入接口（如 `ExplanationPublisher`）替代回调。当前调用方仅 `ShipDispatcher` 一处，接口层只增加文件数量，无实际抽象收益。

### D2：内部请求类型

**现状**：`LlmChatService.handleChat()` 直接接收 `ChatRequestPayload`（`dto.websocket` 包），`VoiceChatService.handleVoice()` 直接接收 `SpeechRequestPayload`。

**方案**：
- 定义 `LlmChatRequest` record（字段：`conversationId`、`eventId`、`content`、`selectedTargetIds`），置于 `service.llm`。
- 定义 `LlmVoiceRequest` record（字段：`conversationId`、`eventId`、`audioData`、`audioFormat`、`mode`、`selectedTargetIds`），置于 `service.llm`。其中 `mode` 类型为 `LlmVoiceMode` 枚举（`DIRECT` / `PREVIEW`），定义于 `service.llm`。
- `ChatWebSocketHandler` 在协议校验通过后构造内部请求类型，再调用 LLM 服务。

**`LlmVoiceMode` 命名**：沿用 `DIRECT` / `PREVIEW`，与协议层 `SpeechMode` 值语义一致，只做类型隔离，不做语义改名。

**结果**：`service.llm` 包的所有公开方法签名不再出现 `dto.websocket` 中的类型。

### D3：`LlmErrorCode` — 仅限 LLM 运行时错误

**现状**：`LlmChatService`、`LlmExplanationService`、`LlmTriggerService`、`ChatPayloadValidator`、`ValidationResult` 均导入 `dto.websocket.ChatErrorCode`。

**分析**：`ChatErrorCode` 中的错误码分两类：

| 类别 | 错误码 | 责任归属 |
|------|--------|----------|
| 协议/校验 | `INVALID_CHAT_REQUEST`、`INVALID_SPEECH_REQUEST`、`INVALID_AUDIO_FORMAT`、`AUDIO_TOO_LARGE` | transport 层，请求到达 LLM 服务前已拦截 |
| LLM 运行时 | `LLM_TIMEOUT`、`LLM_REQUEST_FAILED`、`LLM_DISABLED`、`CONVERSATION_BUSY`、`TRANSCRIPTION_FAILED`、`TRANSCRIPTION_TIMEOUT` | LLM 服务层，处理过程中产生 |

**方案**：`LlmErrorCode` 仅包含 LLM 运行时错误：

```java
public enum LlmErrorCode {
    LLM_TIMEOUT,
    LLM_FAILED,
    LLM_DISABLED,
    CONVERSATION_BUSY,
    TRANSCRIPTION_FAILED,
    TRANSCRIPTION_TIMEOUT
}
```

分层处理：
- **transport 层**：`ChatWebSocketHandler` 解析并校验协议 payload（`conversationId` 非空、`content` 非空、base64 格式、音频大小等）。校验失败时直接用 `ChatErrorCode` 下行，不经过 LLM 服务。
- **LLM 服务层**：错误回调使用 `LlmErrorCode`。`ChatWebSocketHandler` 在错误回调中将 `LlmErrorCode` 映射为 `ChatErrorCode`。

映射关系：

| LlmErrorCode | ChatErrorCode |
|---|---|
| `LLM_TIMEOUT` | `LLM_TIMEOUT` |
| `LLM_FAILED` | `LLM_REQUEST_FAILED` |
| `LLM_DISABLED` | `LLM_DISABLED` |
| `CONVERSATION_BUSY` | `CONVERSATION_BUSY` |
| `TRANSCRIPTION_FAILED` | `TRANSCRIPTION_FAILED` |
| `TRANSCRIPTION_TIMEOUT` | `TRANSCRIPTION_TIMEOUT` |

### D4：`domain.RiskLevel` 共享枚举 — 替代常量复制

**现状**：`RiskContextFormatter` 和 `LlmTriggerService` 导入 `engine.risk.RiskConstants` 获取 `SAFE`、`CAUTION`、`WARNING`、`ALARM` 字面量。`LlmExplanationService` 导入 `EXPLANATION_SOURCE_LLM`。

**分析**：风险等级字符串（`SAFE` / `CAUTION` / `WARNING` / `ALARM`）是跨层共享的领域词汇，被 `engine.risk`、`service.llm`、`assembler` 三处使用。它们不属于任何单一模块的内部实现。

**方案**：在 `domain` 包中新增 `RiskLevel` 枚举：

```java
package com.whut.map.map_service.domain;

public enum RiskLevel {
    SAFE, CAUTION, WARNING, ALARM;
}
```

#### 枚举边界：内部类型用枚举，序列化边界保持 String

`riskLevel` 在系统中流经多个节点。Step 5 仅在 LLM 层内部收口为枚举，不扩散到序列化链路：

| 类型 | `riskLevel` 类型 | 理由 |
|------|-----------------|------|
| `TargetRiskAssessment` | `String`（保持） | engine 内部类型，step 5 不改动 engine 层 |
| `LlmRiskTargetContext` | **`RiskLevel`（改为枚举）** | LLM 层内部类型，排序/过滤受益于枚举 |
| `LlmExplanation` | `String`（保持） | 输出到 `SseEventFactory` → `ExplanationPayload` → SSE JSON，改枚举会触发序列化链路变更 |
| `ExplanationPayload` | `String`（保持） | 协议 DTO，直接序列化为 JSON |
| `TargetAssembler` 输出 | `String`（保持） | 协议 DTO 组装，step 5 不涉及 |

**转换点**（两处）：

1. `String → RiskLevel`：`LlmRiskContextAssembler.buildTargetContexts()` 中，将 `TargetRiskAssessment.getRiskLevel()`（String）转为 `RiskLevel.valueOf()`。null 或无法识别的值映射为 `null`。
2. `RiskLevel → String`：`LlmExplanationService` 构建 `LlmExplanation` 时，`target.getRiskLevel().name()` 写入 `riskLevel` 字段。

**`EXPLANATION_SOURCE_LLM` 处理**：该常量语义为"解释来源标签"，不属于风险等级概念。移至 `LlmExplanation` 类内作为 `public static final String SOURCE_LLM = "llm"`。`RiskConstants` 中的 `EXPLANATION_SOURCE_RULE` / `EXPLANATION_SOURCE_FALLBACK` 等同类常量保留原位（仅被 engine 层使用）。

**`RiskConstants` 处理**：风险等级相关常量（`SAFE` / `CAUTION` / `WARNING` / `ALARM`）标记为 `@Deprecated`，引导后续使用方迁移到 `RiskLevel` 枚举。Step 5 不强制迁移 engine 层内部的调用点（`RiskAssessmentEngine`、`TargetAssembler`、`RiskVisualizationAssembler`），避免扩散范围。

### D5：验证逻辑归属调整

**现状**：`ChatPayloadValidator` 位于 `service.llm.validation`，直接校验 `ChatRequestPayload` / `SpeechRequestPayload`。

**方案**：
- **文本请求校验**（`conversationId`、`eventId`、`content` 非空）：上移至 `ChatWebSocketHandler`，在构造 `LlmChatRequest` 前完成。校验逻辑为三个 `StringUtils.hasText()` 调用，不需要独立类。校验失败直接用 `ChatErrorCode.INVALID_CHAT_REQUEST` 返回。
- **语音请求校验**：分两层。协议字段校验（`conversationId`、`eventId`、`audioFormat`、`mode` 非空）上移至 handler。音频内容校验（按 `Base64.Decoder` 语义校验 base64，并检查解码后大小）保留在 `service.llm.validation`，由 `AudioValidator.validateAudio(String audioData, String audioFormat)` 执行；`ChatWebSocketHandler` 根据校验结果直接映射为 `ChatErrorCode.INVALID_AUDIO_FORMAT` / `AUDIO_TOO_LARGE`，校验通过后再构造 `LlmVoiceRequest`。

### D6：相对方位角计算

**现状**：`LlmExplanationService` 的用户消息只有经纬度、航速、航向。没有"相对方位/舷角"这种确定性字段。如果 system prompt 要求输出方位描述（如"右舷前方"），LLM 需从经纬度和航向推断，跨 provider 结果不稳定。

**方案**：在数据源头计算确定性方位值，作为输入字段注入 prompt，LLM 只需引用、无需推算。

1. **`GeoUtils` 新增 `trueBearing(lat1, lon1, lat2, lon2)`**：计算从点 1 到点 2 的真方位角（0°–360°），使用等距投影（与现有 `toXY` 方法一致）。

2. **`LlmRiskTargetContext` 新增 `relativeBearingDeg`**（`Double`，nullable）：目标船相对于本船船首的方位角。0° = 正前方，90° = 右舷正横，180° = 正后方，270° = 左舷正横。

3. **`LlmRiskContextAssembler` 计算相对方位**：
   ```
   trueBearing = GeoUtils.trueBearing(ownLat, ownLon, targetLat, targetLon)
   referenceHeading = ownShip.heading != null ? ownShip.heading : ownShip.cog
   relativeBearing = (trueBearing - referenceHeading + 360) % 360
   ```

   其中 `heading` 为优先基准，符合 relative bearing 相对于船首向的定义；仅当 AIS 未提供可用 `heading` 时，回退到 `cog` 作为近似值。

4. **方位扇区标签**：在 `LlmExplanationService` 或公共工具中提供 `bearingSectorLabel(double relativeBearingDeg) → String` 方法，将角度转为中文扇区标签，供 prompt 注入使用：

   | 相对方位角范围 | 标签 |
   |--------------|------|
   | 337.5°–22.5° | 正前方 |
   | 22.5°–67.5° | 右舷前方 |
   | 67.5°–112.5° | 右舷正横 |
   | 112.5°–157.5° | 右舷后方 |
   | 157.5°–202.5° | 正后方 |
   | 202.5°–247.5° | 左舷后方 |
   | 247.5°–292.5° | 左舷正横 |
   | 292.5°–337.5° | 左舷前方 |

5. **`LlmExplanationService.buildMessages()` 注入**：用户消息中增加"相对方位: 右舷前方 (045°)"，由 Java 计算完成，LLM 直接引用。本船方向展示与 `relativeBearingDeg` 使用同一参考基准：优先输出 `heading`，缺失时输出 `cog` 并标注为近似。

**chat 链路**：`RiskContextFormatter` 的摘要/详情格式暂不注入方位信息。chat 场景下目标船数量多，方位角对摘要信息密度的提升有限。如需后续可独立补充。

### D7：风险解释 prompt 增强

**增强方向**：

1. **system prompt 结构化**：将角色定义、输出要求、术语约束分段编写，替代当前两行文本。
2. **补入 `currentDistanceNm` 和相对方位**：在用户消息中增加"现距"和"相对方位"字段。
3. **chat 与 explanation 职责区分**：explanation prompt 明确约束"仅描述风险现状，不回答追问"，chat prompt 明确"可引用上下文回答开放问题"。

**不做的事**：
- 不要求 JSON 结构化输出（当前前端按文本渲染，无解析需求）。
- 不增加建议具体操纵动作（需 COLREG 规则库支撑，属 P3 范围）。

---

## Step-by-step Implementation Plan

### Phase A：基础设施 — 新增共享类型与内部类型（无行为变更）

| 步骤 | 描述 |
|------|------|
| A1 | 新增 `domain.RiskLevel` 枚举（`SAFE`、`CAUTION`、`WARNING`、`ALARM`） |
| A2 | 新增 `LlmErrorCode` 枚举（6 个运行时错误码） |
| A3 | 新增 `LlmChatRequest` record |
| A4 | 新增 `LlmVoiceRequest` record + `LlmVoiceMode` 枚举（`DIRECT` / `PREVIEW`） |
| A5 | `GeoUtils` 新增 `trueBearing(lat1, lon1, lat2, lon2)` 方法 |

此阶段所有新增类型均为独立文件或纯新增方法，不修改任何现有逻辑，不影响运行时行为。

### Phase B：`RiskLevel` 枚举替换与方位角字段

| 步骤 | 描述 |
|------|------|
| B1 | `LlmRiskTargetContext`：`riskLevel` 字段类型 `String` → `RiskLevel`；新增 `relativeBearingDeg`（`Double`）字段。 |
| B2 | `LlmRiskContextAssembler`：(a) 构建 target context 时，将 `TargetRiskAssessment.getRiskLevel()`（String）通过安全解析转为 `RiskLevel` 枚举，null/无法识别值映射为 null；(b) 计算 `relativeBearingDeg` 并填入。 |
| B3 | `RiskContextFormatter`：`isVisibleTarget()` 和 `riskLevelOrder()` 改为比较 `RiskLevel` 枚举值（可用 ordinal 或 switch），删除 `RiskConstants` import。 |
| B4 | `LlmTriggerService`：`isExplainableTarget()` 中 `RiskConstants.SAFE` 改为 `RiskLevel.SAFE`，删除 `RiskConstants` import。 |
| B5 | `LlmExplanation`：新增 `public static final String SOURCE_LLM = "llm"`。`riskLevel` 字段保持 `String` 类型（序列化边界）。 |
| B6 | `LlmExplanationService`：`EXPLANATION_SOURCE_LLM` → `LlmExplanation.SOURCE_LLM`；构建 `LlmExplanation` 时 `target.getRiskLevel().name()` 转为字符串。删除 `RiskConstants` import。 |
| B7 | `RiskConstants`：`SAFE` / `CAUTION` / `WARNING` / `ALARM` 标记 `@Deprecated`，不删除，不强制迁移 engine 层调用点。 |

Phase B 完成后，`service.llm` 包不再存在 `engine.risk.*` 的 import。

### Phase C：LLM 服务层切换内部请求类型与错误码

| 步骤 | 描述 |
|------|------|
| C1 | `LlmChatService.handleChat()` 入参从 `ChatRequestPayload` 改为 `LlmChatRequest`；错误回调从 `BiConsumer<ChatErrorCode, String>` 改为 `BiConsumer<LlmErrorCode, String>`。内部逻辑不变，仅字段访问方式变更（`request.getContent()` → `request.content()`）。删除 `ChatPayloadValidator` 依赖（文本校验已上移）。 |
| C2 | `VoiceChatService.handleVoice()` 入参从 `SpeechRequestPayload` 改为 `LlmVoiceRequest`；错误回调同步切换。`buildTextRequestFromTranscript()` 改为构造 `LlmChatRequest`。`isPreviewMode()` 判断 `LlmVoiceMode.PREVIEW`。 |
| C3 | `ChatPayloadValidator` 重构为 `AudioValidator`：删除 `validateTextRequest()` / `validateSpeechRequest()`；新增 `validateAudio(String audioData, String audioFormat)` —— 仅负责 base64 合法性和大小校验。返回 `AudioValidator.Result`（含规范化后的 base64、内部 `AudioValidationCode` 和错误消息），由 `ChatWebSocketHandler` 映射为协议错误码。 |
| C4 | 删除 `ValidationResult.java`。音频校验结果由 `AudioValidator.Result` 内联承载，不再单独保留通用验证结果类型。 |
| C5 | `LlmExplanationService`：`LlmExplanationError.errorCode` 类型改为 `LlmErrorCode`；删除 `ChatErrorCode` import。 |
| C6 | `LlmTriggerService`：(a) 删除 `RiskStreamPublisher` 字段；(b) `triggerExplanationsIfNeeded()` 增加回调参数 `Consumer<LlmExplanation> onExplanation`、`BiConsumer<LlmRiskTargetContext, LlmExplanationService.LlmExplanationError> onError`；(c) 内部将回调透传给 `LlmExplanationService.generateTargetExplanationsAsync()`；(d) 删除 `RiskStreamPublisher` / `ChatErrorCode` import；(e) 删除不再需要的 `riskObjectId` 形参。 |

Phase C 完成后，`service.llm` 包不再存在 `dto.websocket.*` 或 `transport.*` 的 import。

### Phase D：Transport 层适配

| 步骤 | 描述 |
|------|------|
| D1 | `ChatWebSocketHandler.handleChat()`：解析 `ChatRequestPayload` 后，内联执行字段校验（`conversationId`、`eventId`、`content` 非空），校验失败直接 `sendError(ChatErrorCode.INVALID_CHAT_REQUEST, ...)`。校验通过后构造 `LlmChatRequest`，调用 `llmChatService.handleChat()`。错误回调中新增 `mapToProtocolErrorCode(LlmErrorCode)` 映射。 |
| D2 | `ChatWebSocketHandler.handleSpeech()`：解析 `SpeechRequestPayload` 后，先做协议字段校验，再调用 `AudioValidator.validateAudio()` 做音频内容校验；全部通过后构造 `LlmVoiceRequest`，调用 `voiceChatService.handleVoice()`。 |
| D3 | `ChatWebSocketHandler` 新增私有方法 `mapToProtocolErrorCode(LlmErrorCode) → ChatErrorCode`，覆盖所有 6 个 `LlmErrorCode` 值。 |
| D4 | `ShipDispatcher.publishRiskSnapshot()`：调用 `llmTriggerService.triggerExplanationsIfNeeded()` 时传入回调 lambda。`riskObjectId` 由 `ShipDispatcher` 在 lambda 闭包中捕获，`LlmTriggerService` 不再接收该参数。`onExplanation` 转发至 `riskStreamPublisher.publishExplanation()`。`onError` 从 `LlmExplanationError` 中提取 `LlmErrorCode`，映射为 `ChatErrorCode`（`LLM_TIMEOUT` → `ChatErrorCode.LLM_TIMEOUT`，其余 → `ChatErrorCode.LLM_REQUEST_FAILED`），转发至 `riskStreamPublisher.publishError()`。 |

### Phase E：风险解释 prompt 增强

| 步骤 | 描述 |
|------|------|
| E1 | 重写 `resources/prompts/system-risk-explanation.txt`，见下文。 |
| E2 | `LlmExplanationService.buildMessages()`：用户消息中补入 `currentDistanceNm`（"现距: X.XX 海里"）和相对方位（"相对方位: 右舷前方 (045°)"），放置在"风险等级"行之后、"DCPA"行之前。新增 `bearingSectorLabel()` 私有方法（或放入公共工具类）。 |

### Phase F：清理与验证

| 步骤 | 描述 |
|------|------|
| F1 | 删除 `service.llm.validation.ChatPayloadValidator.java` 和 `ValidationResult.java`（已由 `AudioValidator` 及其内联结果类型替代）。 |
| F2 | 确认 `service.llm` 包内无残余 `dto.websocket.*`、`engine.risk.*`、`transport.*` import。验证命令：`grep -rn "import com.whut.map.map_service.dto.websocket\|import com.whut.map.map_service.engine.risk\|import com.whut.map.map_service.transport" backend/.../service/llm/` |
| F3 | 更新既有单元测试，适配新类型签名。新增 `GeoUtils.trueBearing()` 和 `bearingSectorLabel()` 的单元测试。 |

---

## File-by-file Change List

### 新增文件

| 文件 | 包 | 说明 |
|------|-----|------|
| `RiskLevel.java` | `domain` | 枚举：`SAFE`、`CAUTION`、`WARNING`、`ALARM` |
| `LlmErrorCode.java` | `service.llm` | 枚举：6 个运行时错误码 |
| `LlmChatRequest.java` | `service.llm` | record：`conversationId`、`eventId`、`content`、`selectedTargetIds` |
| `LlmVoiceRequest.java` | `service.llm` | record：`conversationId`、`eventId`、`audioData`、`audioFormat`、`mode`(LlmVoiceMode)、`selectedTargetIds` |
| `LlmVoiceMode.java` | `service.llm` | 枚举：`DIRECT`、`PREVIEW` |
| `AudioValidator.java` | `service.llm.validation` | 替代 `ChatPayloadValidator`，仅校验音频内容（base64 / 大小），内含 `Result` / `AudioValidationCode` |

### 修改文件

| 文件 | 变更要点 |
|------|----------|
| `GeoUtils.java` | 新增 `trueBearing(lat1, lon1, lat2, lon2)` 方法 |
| `LlmRiskTargetContext.java` | `riskLevel` 字段类型 `String` → `RiskLevel`；新增 `relativeBearingDeg`（`Double`） |
| `LlmExplanation.java` | 新增 `public static final String SOURCE_LLM = "llm"`；`riskLevel` 保持 `String` |
| `LlmRiskContextAssembler.java` | 风险等级 String → `RiskLevel` 转换；计算 `relativeBearingDeg` |
| `RiskConstants.java` | 风险等级常量标记 `@Deprecated` |
| `LlmChatService.java` | `handleChat()` 入参 → `LlmChatRequest`；错误回调 → `LlmErrorCode`；删除 `ChatPayloadValidator` 依赖和协议 DTO import |
| `VoiceChatService.java` | `handleVoice()` 入参 → `LlmVoiceRequest`；`buildTextRequestFromTranscript()` 构造 `LlmChatRequest`；`isPreviewMode()` 判断 `LlmVoiceMode.PREVIEW`；删除协议 DTO import |
| `LlmExplanationService.java` | `EXPLANATION_SOURCE_LLM` → `LlmExplanation.SOURCE_LLM`；`LlmExplanationError.errorCode` → `LlmErrorCode`；`buildMessages()` 补入 `currentDistanceNm` 和相对方位；新增 `bearingSectorLabel()` 方法；删除 `RiskConstants` / `ChatErrorCode` import |
| `LlmTriggerService.java` | 删除 `RiskStreamPublisher` 字段；`triggerExplanationsIfNeeded()` 增加回调参数（透传给 `LlmExplanationService`）并删除 `riskObjectId` 形参；`isExplainableTarget()` → `RiskLevel.SAFE`；删除 `RiskStreamPublisher` / `RiskConstants` / `ChatErrorCode` import |
| `RiskContextFormatter.java` | 所有 `RiskConstants.*` → `RiskLevel.*`；`riskLevelOrder()` 改为基于枚举；删除 `RiskConstants` import |
| `ChatWebSocketHandler.java` | `handleChat()`：内联文本校验 → 构造 `LlmChatRequest` → 调用 service。`handleSpeech()`：内联协议字段校验 → `AudioValidator.validateAudio()` → 构造 `LlmVoiceRequest` → 调用 service。新增 `mapToProtocolErrorCode(LlmErrorCode)` 私有方法。 |
| `ShipDispatcher.java` | `publishRiskSnapshot()` 中 `triggerExplanationsIfNeeded()` 调用增加回调 lambda，含 `LlmErrorCode` → `ChatErrorCode` 映射 |
| `system-risk-explanation.txt` | 重写，见下文 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `ChatPayloadValidator.java` | 职责拆分：文本校验内联至 handler，音频校验迁移至 `AudioValidator` |
| `ValidationResult.java` | 音频校验结果改为由 `AudioValidator.Result` 内联承载 |

### 测试文件同步修改

| 文件 | 变更要点 |
|------|----------|
| `LlmChatServiceTest.java` | 构造 `LlmChatRequest` 替代 `ChatRequestPayload`；错误断言改用 `LlmErrorCode` |
| `VoiceChatServiceTest.java` | 构造 `LlmVoiceRequest` 替代 `SpeechRequestPayload`；错误断言改用 `LlmErrorCode` |
| `LlmExplanationServiceTest.java` | 错误码断言改用 `LlmErrorCode`；验证 `currentDistanceNm` 和相对方位出现在 prompt 中 |
| `RiskContextFormatterTest.java` | `RiskConstants.*` → `RiskLevel.*` |
| `ConversationMemoryTest.java` | 无变更 |
| 新增 `GeoUtilsBearingTest.java` | `trueBearing()` 正确性测试（已知坐标对） |

---

## 风险解释 prompt 重写

### `system-risk-explanation.txt`（新内容）

```
你是一名航行安全分析系统的风险描述模块。

职责：根据提供的本船与目标船态势数据，输出简洁、专业的碰撞风险描述。

输出要求：
- 使用中文，1–2 句话
- 首句概述风险等级和来源方位（使用提供的"相对方位"字段，如"右舷前方有一目标船正在接近"）
- 第二句引用关键指标（现距、DCPA、TCPA）说明紧迫程度
- 当 TCPA ≤ 0 或目标船远离时，说明风险正在解除
- 不输出具体操纵建议（如"建议右转"），仅输出监控建议或关注方向
- 不回答任何追问，不进行对话

术语约定：
- DCPA：最近会遇距离
- TCPA：到达最近会遇点的剩余时间
- 现距：当前实际距离
- 风险等级从高到低：ALARM > WARNING > CAUTION > SAFE
```

### `LlmExplanationService.buildMessages()` 用户消息变更

在"风险等级"行之后、"DCPA"行之前，新增两行：

```diff
 风险等级: %s
+现距: %s 海里
+相对方位: %s (%s°)
 DCPA: %.2f 海里，TCPA: %.0f 秒
```

- `currentDistanceNm`：`Double`，可能为 null，null 时输出"未知"。
- 相对方位：由 `bearingSectorLabel()` 计算扇区标签（如"右舷前方"），括号内为数值角度。`relativeBearingDeg` 为 null 时整行输出"相对方位: 未知"。
- 本船方向：显示为"船首向"，优先使用 `heading`；若 `heading` 缺失，则回退显示 `cog`，并标注为 `COG` 近似值。

---

## Invariants / Rules

1. **依赖方向**：`service.llm` 仅向内依赖 `llm.*`（client、dto、prompt）、`domain.*` 和 `config.properties`。不得出现 `dto.websocket.*`、`engine.risk.*`、`transport.*` 的 import。验证命令见 Phase F。

2. **`RiskLevel` 单一来源**：LLM 内部类型与 step 5 新增/修改的判断逻辑统一使用 `domain.RiskLevel` 枚举，不得在任何包中新增同语义的字符串常量。`RiskConstants` 中的风险等级常量标记为 `@Deprecated`。

3. **枚举边界**：`RiskLevel` 枚举仅用于内部类型（`LlmRiskTargetContext`、`RiskContextFormatter`、`LlmTriggerService`）。面向序列化的类型（`LlmExplanation`、`ExplanationPayload`、`TargetAssembler` 输出）保持 `String`。转换点仅两处：`LlmRiskContextAssembler`（String → 枚举）和 `LlmExplanationService`（枚举 → String）。

4. **错误码分层**：协议校验错误（字段缺失、格式非法）由 transport 层直接使用 `ChatErrorCode` 处理，不进入 LLM 服务层。LLM 运行时错误使用 `LlmErrorCode`，由 transport 层在错误回调中映射为 `ChatErrorCode`。

5. **错误码不丢失**：`LlmTriggerService` 的 `onError` 回调携带 `LlmExplanationError`（含 `LlmErrorCode`），`ShipDispatcher` 在回调中负责映射为 `ChatErrorCode`，保留 `LLM_TIMEOUT` 与普通失败的区分。

6. **回调非空**：`LlmTriggerService.triggerExplanationsIfNeeded()` 的回调参数不可为 null。调用方（`ShipDispatcher`）负责传入有效回调。

7. **请求类型不可变**：`LlmChatRequest`、`LlmVoiceRequest` 为 record（不可变），构造后不可修改。

8. **Voice mode 语义一致**：`LlmVoiceMode.DIRECT` / `PREVIEW` 与协议层 `SpeechMode.DIRECT` / `PREVIEW` 值语义一一对应，不引入新术语。

9. **错误码映射完备**：`ChatWebSocketHandler.mapToProtocolErrorCode()` 必须覆盖所有 `LlmErrorCode` 枚举值。新增 `LlmErrorCode` 时必须同步更新映射。

10. **方位角确定性**：相对方位和扇区标签由 Java 代码计算，作为确定性输入注入 prompt。system prompt 不要求 LLM 自行从经纬度推算方位。

11. **风险解释与聊天职责分离**：`system-risk-explanation.txt` 明确禁止对话行为；`system-chat.txt` 允许引用上下文回答开放问题。两条链路的 system prompt 不得合并。

12. **协议无变更**：Step 5 不修改 WebSocket / SSE 协议字段、消息类型或版本号。前端无需任何改动。

13. **不做包迁移**：Step 5 不改变任何 Java 类的包路径。物理包重组为独立评估项。
