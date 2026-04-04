# LLM 增强实现规划

> 最后更新：2026-04-04
> 对应 ARCHITECTURE.md 中 P1 ~ P3 的 LLM 相关待办项

---

## 零、当前状态

| 能力 | 现状 |
| --- | --- |
| 风险解释 | 每次触发独立构建 prompt，包含本船 + 单目标船态势，无历史上下文 |
| 聊天问答 | 单轮无状态，prompt 仅包含用户消息本身，无风险态势注入 |
| 语音链路 | ASR transcript 转为 ChatRequestPayload 后走同一聊天链路 |
| Prompt | 风险解释用中文模板；聊天用英文模板，二者独立，无 system prompt 层 |
| 接口抽象 | `LlmClient.generateText(String prompt)` —— 单字符串输入/输出，不支持消息列表或角色区分 |

**核心瓶颈**：`LlmClient` 接口只接受单个 `String prompt`，无法表达 system / user / assistant 多角色消息序列，也无法承载对话历史。后续所有增强都绕不开这一层的升级。

---

## 模块化策略：边增强边收口

### 当前问题

当前 LLM 代码与 map-service 存在双向耦合：

- `ShipDispatcher` → 直接调用 `LlmTriggerService`
- `LlmTriggerService` → 直接持有 `RiskStreamPublisher` 引用
- `LlmExplanationService` → 依赖 `RiskConstants` 的风险等级枚举
- 各 Service → 散落引用 `dto/websocket/*` 中的协议 DTO

这意味着 LLM 的任何改动都可能影响风险管线，反之亦然。即使暂不拆分为独立服务，这种耦合也会持续增加日常开发与维护成本。

### 收敛策略

**不单独设置额外的“重构阶段”，而是在 Step 1-5 的实施过程中，逐步将 LLM 模块的依赖收敛到接口与回调。**

具体措施如下，并在各 Step 中同步完成：

| Step | 顺手做的收口动作 |
| --- | --- |
| Step 1 | `LlmClient` 升级时，LLM 专用 DTO（`ChatMessage` 等）统一放入 `llm/` 包，不混在 `dto/websocket/` 里 |
| Step 2 | `PromptTemplateService` 归入 `llm/` 包，prompt 模板资源独立于业务配置 |
| Step 3 | `RiskContextHolder` 作为 LLM 层获取态势的唯一入口，替代直接依赖 pipeline 内部类型 |
| Step 4 | `ConversationMemory` 归入 `llm/` 包；`LlmChatService` 的输入从 `ChatRequestPayload`（协议 DTO）收口为自定义的内部参数类型 |
| Step 5 | `LlmExplanationService` 的结果发布改为回调（`Consumer<LlmExplanation>`），不再直接持有 `RiskStreamPublisher` |

### 目标包结构

```
map-service/
├── llm/                          ← LLM 能力收口于此
│   ├── client/                   ← LlmClient 接口及 Gemini/Zhipu 实现
│   ├── dto/                      ← ChatMessage, LlmRiskContext 等 LLM 专用模型
│   ├── prompt/                   ← PromptTemplateService + 模板资源引用
│   ├── memory/                   ← ConversationMemory
│   ├── context/                  ← RiskContextHolder, RiskContextFormatter
│   └── service/                  ← LlmChatService, LlmExplanationService, VoiceChatService, LlmTriggerService
│
├── pipeline/
│   └── ShipDispatcher            ← 调用 llm 层时走接口，不依赖实现细节
└── transport/
    └── risk/RiskStreamPublisher  ← llm 层通过回调交付结果，不反向依赖 transport
```

**依赖方向**：`pipeline → llm（接口）`，`transport → llm（接口）`；`llm` 层不反向依赖 `pipeline` 或 `transport`。

### 独立服务拆分时机

建议在 Step 4 完成后、开始实现 agent loop（P3）时再评估独立服务拆分。届时 LLM 模块的职责边界将更稳定，agent 也天然需要独立的状态管理与调用编排，可作为较自然的拆分切入点。拆分时主要涉及将本地回调替换为远程调用，并将本地接口替换为远程客户端。

---

## 一、实现步骤总览

```
Step 1  升级 LlmClient 接口，支持多消息输入          ← 地基
Step 2  引入 System Prompt 管理                      ← 角色与行为约束
Step 3  向聊天链路注入实时风险上下文（P1）             ← 让 LLM 能回答态势问题
Step 4  建立多轮上下文管理（P2）                      ← 让对话有记忆
Step 5  风险解释 prompt 增强                         ← 提升解释质量
```

> Step 3 是 P1 的交付物，Step 4 是 P2 的交付物。Step 1-2 是二者的前置依赖。Step 5 是对风险解释链路的独立优化，可与 Step 4 并行。

---

## Step 1：升级 LlmClient 接口

### 目标

让 LLM 调用层能表达 `system / user / assistant` 消息序列，为上下文注入与多轮对话提供基础。

### 做什么

1. **新增 `ChatMessage` 模型**

   ```java
   public record ChatMessage(Role role, String content) {
       public enum Role { SYSTEM, USER, ASSISTANT }
   }
   ```

2. **扩展 `LlmClient` 接口**

   ```java
   public interface LlmClient {
       // 保留原有方法，作为简易入口（内部转为单条 USER 消息调用）
       String generateText(String prompt);

       // 新增：接受消息列表
       String chat(List<ChatMessage> messages);
   }
   ```

3. **适配 Gemini / Zhipu 实现**
   - `GeminiLlmClient`：Gemini SDK 的 `generateContent` 原生支持 `Content` 列表，将 `ChatMessage` 映射为 SDK 的 `Content` 对象，`SYSTEM` 角色映射为 `systemInstruction`。
   - `ZhipuLlmClient`：智谱 SDK 支持 `messages` 数组（OpenAI 兼容格式），直接映射 `role` + `content`。

4. **原有 `generateText` 保持向后兼容**，内部委托给 `chat(List.of(new ChatMessage(USER, prompt)))`。

### 影响范围

- `LlmClient` 接口
- `GeminiLlmClient`、`ZhipuLlmClient`
- 现有调用方（`LlmExplanationService`、`LlmChatService`）无需立即改动

---

## Step 2：引入 System Prompt 管理

### 目标

将 LLM 的角色定义、行为约束、输出格式要求从硬编码的 prompt 拼接中抽离，集中管理。

### 做什么

1. **定义 system prompt 资源文件**

   在 `src/main/resources/prompts/` 下新建：
   - `system-risk-explanation.txt` — 风险解释场景的 system prompt
   - `system-chat.txt` — 聊天问答场景的 system prompt

   示例（`system-chat.txt`）：
   ```
   你是一名航行安全助手，部署在船舶态势感知系统中。
   你的职责是根据当前实时态势数据，回答船员关于风险、航行建议、目标船信息等问题。
   回答使用简洁中文，1-3 句话。
   如果提供了当前风险上下文，优先基于上下文作答。
   如果问题超出当前态势范围，诚实告知。
   ```

2. **新增 `PromptTemplateService`**

   - 启动时加载 prompt 模板文件
   - 提供 `getSystemPrompt(scene)` 方法
   - 后续可扩展为支持变量替换（如注入船舶 ID、时间等）

3. **重构 `LlmExplanationService.buildPrompt()` 和 `LlmChatService.buildPrompt()`**
   - 将当前硬编码的 prompt 模板迁移到资源文件或集中模板构建入口
   - Step 2 完成后，不再保留散落在各 Service 中的硬编码提示词
   - 调用时组装为 `[SYSTEM, USER]` 消息列表，通过 `LlmClient.chat()` 发送

### 影响范围

- 新增 `PromptTemplateService`
- 新增 `resources/prompts/` 目录及模板文件
- 重构 `LlmExplanationService`、`LlmChatService` 的 prompt 构建逻辑

---

## Step 3：向聊天链路注入实时风险上下文（P1）

### 目标

使用户在聊天中能够提问"当前有什么风险"、"目标船 413999001 距离多少"等基于实时态势的问题，LLM 可以基于真实数据作答。

### 做什么

1. **新增 `RiskContextHolder`（运行时风险快照缓存）**

   - 持有最近一次 `LlmRiskContext` 的引用（线程安全）
   - 由 `ShipDispatcher` 在每次风险计算完成后更新
   - 提供 `getCurrent(): LlmRiskContext` 方法

   ```java
   @Component
   public class RiskContextHolder {
       private volatile LlmRiskContext current;

       public void update(LlmRiskContext ctx) { this.current = ctx; }
       public LlmRiskContext getCurrent() { return current; }
   }
   ```

2. **`LlmChatService` 注入风险上下文**

   - 调用 `RiskContextHolder.getCurrent()` 获取最新态势
   - 将态势格式化为文本片段，作为 `USER` 消息的前缀或单独一条 `USER` 消息注入
   - 为控制上下文规模，默认仅注入摘要信息与 Top-N 高风险目标，避免在 P1 阶段引入过长上下文
   - 消息序列变为：

     ```
     SYSTEM:    system-chat.txt 的内容
     USER:      [当前风险态势摘要]（由 RiskContextHolder 提供）
     USER:      用户实际提问
     ```

3. **态势摘要格式化**

   新增 `RiskContextFormatter`，将 `LlmRiskContext` 转为 LLM 可读的文本摘要：

   ```
   【当前态势】更新时间: 2026-04-04T10:30:00Z
   本船 ID: OWN-001, 位置: (114.35, 30.58), 航速: 8.2节, 航向: 045°
   ─────
   目标船 413999001: 风险等级 WARNING, DCPA 0.3海里, TCPA 120秒, 正在接近
   目标船 413888002: 风险等级 SAFE, DCPA 2.1海里, TCPA 600秒
   ```

   补充约束：
   - 初期优先保留高风险目标、关键数值与总体统计，不要求逐条展开全部目标船
   - 具体截断规则与摘要上限在本 Step 即明确，避免将 token 控制完全后置到 Step 4

4. **`ShipDispatcher` 联动更新**

   在 `publishRiskSnapshot()` 中增加一行：
   ```java
   riskContextHolder.update(snapshot.llmContext());
   ```

### 影响范围

- 新增 `RiskContextHolder`、`RiskContextFormatter`
- 修改 `ShipDispatcher`（新增一行 update 调用）
- 修改 `LlmChatService.buildPrompt()` → 组装带态势的消息列表

### 验证方式

- 启动模拟器，等待风险数据产生
- 在聊天中提问"当前有哪些目标船？风险等级如何？"
- LLM 应能基于真实态势数据回答，而非泛泛而谈

---

## Step 4：建立多轮上下文管理（P2）

### 目标

让用户与 LLM 的对话具有连续性，后一轮问答能引用前一轮的内容。

### 做什么

1. **新增 `ConversationMemory`**

   - 按 `conversationId` 维护消息历史
   - 每条记录为 `ChatMessage`（USER / ASSISTANT）
   - 设定上限（如最近 10 轮 = 20 条消息），超出后滑动窗口截断

   ```java
   @Component
   public class ConversationMemory {
       private final Map<String, Deque<ChatMessage>> store = new ConcurrentHashMap<>();

       public void append(String conversationId, ChatMessage message) { ... }
       public List<ChatMessage> getHistory(String conversationId) { ... }
       public void clear(String conversationId) { ... }
   }
   ```

2. **`LlmChatService` 接入对话历史**

   消息序列变为：

   ```
   SYSTEM:      system prompt
   USER:        [当前风险态势摘要]         ← Step 3
   USER:        历史用户消息 1             ← Step 4
   ASSISTANT:   历史助手回复 1             ← Step 4
   USER:        历史用户消息 2
   ASSISTANT:   历史助手回复 2
   ...
   USER:        当前用户提问
   ```

   - 调用前从 `ConversationMemory` 取出历史
   - 调用后将 user 消息和 assistant 回复写回 `ConversationMemory`
   - 同一 `conversationId` 下的请求按顺序处理，不支持用户在上一轮回复返回前连续发送多条消息，以避免历史写入顺序错乱

3. **Token 预算控制**

   - system prompt + 风险上下文 + 历史 + 当前问题，总 token 数可能超出模型限制
   - 实现简单策略：优先保留 system prompt 和风险上下文，历史从旧到新截断
   - 初期使用字符数估算（中文约 1.5 字/token），不引入 tokenizer 依赖

4. **会话生命周期管理**

   - 不将 `conversationId` 与 WebSocket 连接生命周期直接绑定
   - WebSocket 断开时不自动清理对话历史，以支持重连后继续同一业务会话
   - 增加 TTL 过期机制（如 30 分钟无活动自动清理），防止内存泄漏
   - 同时提供用户主动清理入口，用于显式结束当前会话并释放对应历史

5. **协议适配**

   - 前端已有 `conversationId` 字段（见 EVENT_SCHEMA.md），无需协议变更
   - 前端需确保同一轮对话使用同一 `conversationId`

### 影响范围

- 新增 `ConversationMemory`
- 修改 `LlmChatService`（组装历史消息 + 写回）
- 修改聊天链路调用约束与会话清理逻辑

### 验证方式

- 连续提问："当前最危险的目标是哪艘？" → "它的航向是多少？" → "如果它不改变航向会怎样？"
- 第二、三轮回答应能正确引用前文上下文，而非要求用户重复信息

---

## Step 5：风险解释 prompt 增强

### 目标

提升风险解释链路的输出质量，使解释更具专业性和可操作性。

### 做什么

1. **迁移到 system + user 消息结构**

   - 将当前 `LlmExplanationService.buildPrompt()` 中的角色定义部分抽离为 system prompt
   - 态势数据仍作为 user 消息传入

2. **丰富 system prompt 内容**

   `system-risk-explanation.txt`：
   ```
   你是一名航行安全助手，专注于碰撞风险评估。
   根据提供的本船与目标船态势数据，给出简洁的风险描述与风险提示。
   要求：
   - 使用中文，1-2 句话
   - 明确指出风险来源（方位、距离、接近趋势）
   - 当前阶段不输出具体操纵动作建议，仅输出风险提示、关注方向或监控建议
   - 参考 DCPA/TCPA 数值判断紧迫程度
   ```

3. **可选：结构化输出**

   如果后续前端需要解析解释中的结构化信息（如建议动作类型），可在 prompt 中要求 JSON 输出。当前阶段暂不需要，仅作预留方向。

### 影响范围

- 修改 `LlmExplanationService`（使用 `LlmClient.chat()` + system prompt）
- 新增/修改 prompt 模板文件

---

## 实施顺序与依赖关系

```
Step 1 ──→ Step 2 ──→ Step 3 (P1) ──→ Step 4 (P2)
                  └──→ Step 5（可与 Step 3/4 并行）
```

| 步骤 | 预期改动量 | 前置依赖 |
| --- | --- | --- |
| Step 1 | 小（接口 + 两个实现类） | 无 |
| Step 2 | 小（新增服务 + 资源文件） | Step 1 |
| Step 3 | 中（新增 2 个类 + 修改 2 个类） | Step 2 |
| Step 4 | 中（新增 1 个类 + 修改 2 个类） | Step 3 |
| Step 5 | 小（修改 prompt + 调用方式） | Step 2 |

---

## 后续方向（P3，本轮不实施）

以下能力依赖 Step 1-4 完成后再评估：

- **Agent Loop**：在 `LlmChatService` 层引入工具调用能力（function calling），让 LLM 能主动查询态势、触发计算。需要 `LlmClient` 进一步支持 tool_use 协议。
- **法律法规 RAG**：在 prompt 注入前，根据当前态势从规则库中检索相关法规条文，拼入上下文。需要向量数据库或关键词检索组件。
- **航迹预测输入**：将目标船预测轨迹作为额外上下文注入，增强 LLM 的前瞻性判断能力。
