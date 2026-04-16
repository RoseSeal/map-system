# Frontend 增强实现规划 v0.9

> 最后更新：2026-04-15
> 后续演进方向（agent loop、Generative UI、编辑任意历史消息等）已提取至 [`docs/TODO.md`](../TODO.md)。

---

## 零、当前状态

| 能力 | 现状 |
| --- | --- |
| 测试覆盖 | 零：`frontend/src/` 下无任何测试文件，`package.json` 中无测试框架依赖 |
| 前端架构文档 | 浅：`frontend-design.md` 覆盖协议契约与渲染规则，缺少组件职责、状态流转、交互状态机文档 |
| `risk_score` 展示 | 未消费：仅用于同级别目标排序，不作为任何视觉信号 |
| `encounter_type` 展示 | 未消费：schema 已接收字段，TargetsPanel 和 RiskExplanationPanel 均未渲染 |
| `risk_confidence` 展示 | 未消费：schema 已接收字段，无任何前端消费 |
| 连接状态展示 | 粗糙：实时消息连接（risk SSE）与 AI 助手连接（chat WS）未分开展示，且重连过程对用户不可见 |
| 取消录音 | 缺失：`recording` 状态下无显式取消入口；误触或说错后只能继续录制或停止发送 |
| 已发送语音请求取消 | 缺失：音频一旦进入 `transcribing`，当前无取消能力；本版本不实现 |
| 编辑最后一条用户消息 | 缺失：用户发送文本后无法直接在原消息处修改并重发 |
| 解释上下文注入 | 缺失：`selectTarget` 仅注入风险数据，不含解释文本 |
| 真正取消 LLM 回复 | 缺失：已占位（disabled 按钮），需要协议扩展，本版本不实现 |
| 罗盘组件 | 存在 bug（风险位置与罗盘显示不一致），遮挡 RiskExplanationPanel 视线，与海图底图功能重复 |
| 主题/语音控件 | 耦合在 StatusPanel 中，连接离线时不可用（主题切换与连接状态无关） |

**总体判断**：

1. 零测试是结构性风险。任何协议扩展或新事件类型引入后，均只能靠手工验证。
2. Engine 增强完成后，`encounter_type`、`risk_score`（多因子加权）、`risk_confidence` 等字段已稳定输出，前端尚未消费，是近期展示提升的低成本高回报项。
3. Chat 交互流存在多处 UX 缺口（取消录音、编辑最后一条用户消息），优先修补交互完备性中改动量小的条目。
4. 前端组件职责边界不统一（StatusPanel 承担过多无关职责、罗盘组件存在 bug 且功能冗余），需要在增强前先做清理。
5. 前端架构文档不足将在 agent loop 阶段暴露：agent loop 引入新事件类型时，若缺少对现有组件职责和状态流转的记录，协议设计缺乏前端视角约束。

---

## 一、版本目标

**主要目标**：为后续 agent loop 做前端侧准备（测试保障 + 接口清洁 + 文档记录）。

**次要目标**：服务于近期展示，补足已有后端字段的前端消费空白，改善交互完备性。

**明确不做**：

- 真正取消 LLM 回复（需协议扩展 + 后端中断机制，不适合本版本）
- 已发送语音请求取消 / 真正取消后端转录（需 `CANCEL` 协议、任务注册表与中断语义，不适合本版本）
- 编辑任意历史消息（依赖消息 ID 体系 + 精确历史截断，保留为第二阶段预留）
- Generative UI / 前端动画指令消费（依赖 agent loop 的结构化 advisory 输出，无数据源）
- 抽屉伸缩泛化（仅 RiskExplanationPanel 保留抽屉交互；StatusPanel / TargetsPanel 为持续展示型 HUD，收起后失去持续态势感知，不适合抽屉模式）

---

## 二、实现步骤总览

```
Step 1  测试框架与基线                          ← 引入 vitest + testing-library，覆盖 store/services 核心路径
Step 2  前端架构文档 + 组件清理                  ← 组件职责、状态流转、交互状态机；移除罗盘；解耦主题/语音
Step 3  展示增强                                ← encounter_type + risk_score + risk_confidence + 双连接状态
Step 4  交互增强                                ← 取消录音 + 编辑最后一条用户消息 + 解释上下文注入
```

### 依赖关系

```
Step 1（测试基线）──→ 所有后续步骤的回归保障
Step 2（文档 + 清理）──→ 指导 Step 3/4 的实现范围与边界
Step 3（展示增强）  ← 无步骤间强依赖，各子项可独立交付
Step 4（交互增强）  ← 无步骤间强依赖，各子项可独立交付；4B 涉及后端
```

| 步骤 | 预期改动量 | 前置依赖 |
| --- | --- | --- |
| Step 1 | 中（框架安装 + 配置 + 首批测试） | 无 |
| Step 2 | 小-中（文档 + 组件清理重构） | 无，但宜在 Step 3/4 前完成 |
| Step 3 | 小-中（展示增强，各子项独立） | 无 |
| Step 4 | 中（交互增强；4B 涉及后端协议扩展 + ConversationMemory 修改） | 无 |

---

## Step 1：测试框架与基线

### 目标

建立前端自动化测试最小闭环，覆盖 `store/`、`services/`、核心交互流，并建立至少一条 dashboard 冒烟用例。

### 背景

当前 `package.json` 无任何测试依赖，`frontend/src/` 无任何测试文件。零测试状态下，store 逻辑、协议解析等核心模块的正确性全依赖手工验证。在 agent loop 阶段引入新协议事件前，需要先为现有逻辑建立回归保障基线。

### 技术选型

- **测试运行器**：vitest（与 Vite 生态原生集成，无需额外 transform 配置）
- **组件测试**：@testing-library/react + @testing-library/jest-dom
- **DOM 环境**：jsdom（vitest 内置支持，配置 `environment: 'jsdom'`）
- **Mock**：vitest 内置 `vi.mock` / `vi.fn`

### 做什么

1. **安装依赖**

   ```
   vitest
   @testing-library/react
   @testing-library/jest-dom
   @testing-library/user-event
   jsdom
   ```

2. **配置 vitest**

   在 `vite.config.ts` 中添加 `test` 字段，或新建 `vitest.config.ts`：

   ```ts
   test: {
     environment: 'jsdom',
     setupFiles: ['./src/test/setup.ts'],
     globals: true,
   }
   ```

3. **添加 `npm test` 脚本**

4. **覆盖范围（优先级排序）**

   | 模块 | 测试内容 |
   | --- | --- |
   | `useRiskStore` | `setRiskUpdate`（含 selectedTargetIds 清理）、`upsertExplanation`、`selectTarget` / `deselectTarget` |
   | `useAiCenterStore` | `sendTextMessage`、`appendChatReply`（正常 + 重复事件）、`appendChatError`、voiceCapture 状态机转换 |
   | `riskSseService` | SSE 事件分发（RISK_UPDATE / EXPLANATION / ERROR 回调触发） |
   | `chatWsService` | 消息发送（send 返回 eventId）、消息接收分发（CHAT_REPLY / SPEECH_TRANSCRIPT / ERROR） |
   | dashboard 冒烟 | SSE 连接建立 → 接收 RISK_UPDATE → TargetsPanel 渲染目标列表 |

5. **Mock 策略**

   - `riskSseService` / `chatWsService`：在 store 测试中用 `vi.mock` 替换，单独测试 service 时 mock `EventSource` / `WebSocket`
   - Deck.gl / MapLibre：在组件测试中 mock（冒烟用例不测地图层）

### 验收

- `npm test` 全通过，无跳过
- 覆盖上表所有模块的核心路径
- 冒烟用例：RiskUpdate 事件到 TargetsPanel 渲染通路可通

---

## Step 2：前端架构文档 + 组件清理

### 目标

补全前端内部架构文档，记录组件职责、状态流转、交互状态机；清理冗余组件和职责耦合。

### 背景

`frontend-design.md` 当前覆盖协议契约、渲染规则、integration guidance，但缺少：

- 各组件的职责边界（`RiskExplanationPanel` 承担过多、`StatusPanel` 混合本船状态与全局设置）
- `voiceCaptureState` 状态机的完整转换图
- Chat 消息生命周期（pending → sent → replied / error）
- `AiCenterChatMessage` 与 `ExplanationPayload` 的区分边界
- agent loop 扩展点标注（哪些接口未来需要扩展）
- risk SSE / chat WS 双连接状态的展示边界

同时存在两个组件层面的问题需要清理：

- `CompassOverlay` 存在 bug、遮挡视线、与海图功能重复
- 主题切换和语音开关耦合在 `StatusPanel` 中，连接离线时不可用，但这两个功能与连接状态无关

### 做什么

#### 2A. 移除罗盘组件

- 删除 `CompassOverlay.tsx`
- 移除 `MapContainer.tsx` 或其他父组件中对 `CompassOverlay` 的引用
- 不需要替代方案：海图底图本身提供方位参考

**理由**：

- 遮挡 RiskExplanationPanel 解释卡片视线
- 与海图底图方向功能重复
- 存在 bug（风险位置与罗盘显示位置不一致），修复投入不值得

#### 2B. 主题/语音控件从 StatusPanel 解耦

- 将主题切换和语音播报开关从 `StatusPanel` 提取为独立的轻量组件（如 `ToolbarOverlay`），放置于地图边缘
- `StatusPanel` 专注于：本船动态（航速/航向/位置）、平台健康状态、置信度、连接状态
- 主题切换和语音开关不受连接状态限制，始终可用

#### 2C. 前端架构文档补全

在 `frontend-design.md` 中新增或更新以下章节：

1. **组件职责地图**

   | 组件 | 职责 | 不属于其职责 |
   | --- | --- | --- |
   | `RiskExplanationPanel` | AI 中心面板容器，整合态势监控与对话区域 | 不做业务逻辑计算 |
   | `TargetsPanel` | 目标列表展示与选择 | 不发起 Chat |
   | `ChatMessageList` | 渲染消息列表，不持有状态 | 不发送消息 |
   | `ChatComposer` | 文本/语音输入区，不持有 voiceCapture 状态（由 useVoiceCapture 提供） | 不操作 store |
   | `StatusPanel` | 本船 HUD：动态数据、健康状态、置信度、连接状态 | 不承载全局设置 |
   | `ToolbarOverlay`（新增） | 系统主题切换、语音播报开关 | 不展示船舶数据 |

2. **voiceCaptureState 状态机**

   ```
   idle ──startRecording──→ recording ──stopRecording──→ [sendSpeechMessage]
            ▲                    │                           │
            │                    └────cancelRecording────→ idle
            │                                                │
            └──────────────────── resetTimer ←──── sent ─────┤
                                                             │
                                        transcribing ←───────┘
                                             │        │
                                             ▼        ▼
                                            sent    error
   ```

   取消路径说明：本版本只支持 `recording` → `idle` 的本地取消录音。取消时直接丢弃本次录音缓存，不发送到后端，不进入 `transcribing`。`transcribing` 阶段不提供取消入口。

3. **Chat 消息生命周期**（`AiCenterChatMessage.status`）

   ```
   (user sends)   → pending
   (error reply)  → error
   (transcript)   → sent（SPEECH preview 完成）
   (chat_reply)   → replied（user message 状态更新）
   ```

4. **agent loop 扩展点标注**

   - `chatWsService.handleMessage`：新增下行事件类型时的扩展点
   - `riskSseService`：新增 SSE 事件类型时的扩展点
   - 连接状态聚合层：统一消费 risk SSE / chat WS 状态并向 HUD 暴露展示模型
   - `AiCenterChatMessage.content`：目前为 string，结构化内容扩展预留点
   - `RiskExplanationPanel` 态势监控区：未来渲染 advisory 结构化卡片的位置

5. **抽屉伸缩策略**

   仅 `RiskExplanationPanel` 保留抽屉交互。`StatusPanel` / `TargetsPanel` / `ToolbarOverlay` 为持续展示型元素，不增加抽屉行为。理由：持续展示型 HUD 收起后失去持续态势感知，交互收益不抵信息损失。

### 验收

- `CompassOverlay.tsx` 已删除，无残留引用
- 主题/语音控件独立为 `ToolbarOverlay`，始终可用，不受连接状态限制
- `frontend-design.md` 包含上述五个章节
- 状态机图与代码实现一致（以代码为准）
- 文档明确区分 risk SSE 与 chat WS 两条连接状态链路

---

## Step 3：展示增强

### 目标

消费 Engine 增强已稳定输出的字段，提升目标卡片信息密度，增强连接状态可见性。

### 背景

Engine 增强（v0.7/v0.8）完成后，以下字段已稳定产出，但前端尚未消费：

- `encounter_type`：对遇/追越/交叉分类，在 `RiskAssessment` 中已定义
- `risk_score`：多因子加权综合评分（0-1），当前仅作排序辅助
- `risk_confidence`：目标评估置信度，`frontend-design.md §9.4` 已定义消费边界
- 连接状态：risk SSE / chat WS 重连中间态对用户不可见，且未分开展示

### 做什么

#### 3A. encounter_type 标签展示

**位置**：`TargetsPanel` 目标卡片 + `RiskExplanationPanel` 解释卡片

**规则**：

- `encounter_type` 存在且非 `UNDEFINED` 时，渲染小型标签
- 标签文案：`HEAD_ON` → "对遇"、`OVERTAKING` → "追越"、`CROSSING` → "交叉"
- 样式：与 `risk_level` badge 风格一致，颜色中性（不绑定风险等级色）

**约束**：不改变 `risk_level` 阈值与告警语义，encounter_type 仅为辅助展示信号。

#### 3B. risk_score 视觉强度

**位置**：`TargetsPanel` 目标卡片左侧色带（当前固定宽度 3px border-left）

**规则**：

- 在同一 `risk_level` 内，`risk_score` 高的目标左色带更宽（3px → 5px）或透明度更高
- 不展示 `risk_score` 数值文本
- `risk_score` 缺失时降级为现有固定样式

**约束**：

- 不改变排序逻辑（已有）
- 不改变 `risk_level` 阈值与告警语义
- 前端渲染时不对 risk_score 做时间维度平滑（后端评分已有平滑语义），如遇跳变属后端语义，前端如实反映

#### 3C. risk_confidence 低置信度淡化

**位置**：`TargetsPanel` 目标卡片

**规则**：

- `risk_confidence` 存在且 `< 0.5` 时，卡片整体降低透明度（`opacity: 0.6`）
- `risk_confidence` 存在且 `< 0.3` 时，进一步降低（`opacity: 0.45`）
- `risk_confidence` 缺失或 `>= 0.5` 时，卡片正常渲染

**约束**（参照 `frontend-design.md §9.4`）：

- 不抑制、延迟或覆盖 `risk_level`（卡片仍在列表中，仅视觉淡化）
- 不将 `risk_confidence` 当作全局系统可信度信号（那是 `governance.trust_factor` 的职责）
- 透明度降低不影响卡片的可点击性和交互功能

#### 3D. 连接状态增强

**位置**：`StatusPanel` 连接状态指示器

**规则**：

- 分开展示两条连接：
  - 实时消息连接：risk SSE
  - AI 助手连接：chat WS
- 每条连接统一使用三态：在线（绿点）/ 重连中（黄点 + spinning）/ 离线（红点）
- risk SSE 进入浏览器自动重连期时展示“重连中”；chat WS 状态为 `reconnecting` 时展示“重连中”

**实现**：

- 为前端定义统一连接状态枚举，如 `connected | reconnecting | disconnected`
- `riskSseService` 现有布尔回调需扩展为状态回调，显式区分浏览器自动重连中的 `reconnecting`
- `chatWsService` 新增 `onConnectionStateChange` 回调，向 store 推送 `connecting / connected / reconnecting / disconnected`
- store 聚合后分别暴露 `riskConnectionState` 与 `chatConnectionState` 给 `StatusPanel`
- `StatusPanel` 不再只展示单一“在线/离线”，而是同时展示“实时消息连接”和“AI 助手连接”

### 验收

- TargetsPanel 卡片正确展示 encounter_type 标签（仅非 UNDEFINED 时）
- risk_score 视觉强度在同级别目标间有可见差异
- risk_confidence < 0.5 时卡片明显淡化
- risk SSE 与 chat WS 的重连中间态在 StatusPanel 分别有视觉反馈
- 用户可同时看到“实时消息连接”和“AI 助手连接”的独立状态

---

## Step 4：交互增强

### 目标

修补现有交互流的完备性缺口，实现"编辑最后一条用户消息并重新发送"能力，并将解释文本纳入 `selectTarget` 的上下文注入范围。

---

### 4A. 取消录音（本地）

**背景**：`recording` 状态下，用户可能误触发录音，或录音过程中说错内容。当前只能继续录制并选择发送模式，无法直接放弃本次录音。

**范围限定**：本项只处理“音频尚未发送到后端”的本地取消录音，不涉及 `transcribing` 阶段，也不涉及后端转录中断。

**实现**：

- 在 `recording` 状态下，录音控制区增加“取消录音”按钮
- 点击取消时：
  1. 调用 `voiceRecorderService.cancelRecording()`
  2. 调用 `resetVoiceCapture()`，状态回退至 `idle`
  3. 丢弃本次录音缓存，不发送任何 WebSocket 请求
- 不创建 `eventId`
- 不写入 `pendingChatEventIds`
- 不影响后续立即重新录音或直接输入文本发送

**约束**：不需要后端协议变更，纯前端本地状态处理。

---

### 4B. 编辑最后一条用户消息

#### 实现范围

**实现**：编辑最后一条 user 消息并重新发送（`edit_last_user_message=true`，作用于最近一组 USER/ASSISTANT 对话）。

**不实现**：

- 编辑任意历史轮次消息
- 保留多个分支版本
- 对被截断历史做摘要压缩或重建

#### 目标行为

- 用户在最后一条 user 消息下点击“编辑”
- 该条 user 消息卡片切换为可编辑输入框，且默认填入原消息内容
- 用户可修改文本，并通过“确认 / 取消”完成或放弃本次编辑
- 确认成功后，后端以编辑后的 user 文本重新生成回答，并原子替换当前会话最后一组旧的 `USER/ASSISTANT`
- 若确认失败，前端保留旧的最后一轮内容，重新打开编辑框并保留用户草稿，同时显示错误提示
- 不保留历史版本树；只允许修改最后一条 user 消息，不引入历史分支 UI

#### 方案

**协议扩展（Chat 上行）**：

在 `CHAT` 上行 payload 中新增可选字段：

```ts
interface ChatRequestPayload {
  conversation_id: string;
  event_id: string;
  content: string;
  selected_target_ids?: string[];
  edit_last_user_message?: boolean;  // 新增：是否编辑后替换最后一轮
}
```

- 普通请求：`edit_last_user_message` 不传或为 `false`，按现有流程追加会话
- 编辑确认请求：`edit_last_user_message: true`，作用于最后一轮

**后端处理规则**：

1. 校验当前会话是否存在最后一组 `USER/ASSISTANT`
2. 读取并暂存最后一轮旧消息，作为回退基线
3. 调用 LLM 基于编辑后的 user 文本生成新的助手回复
4. 新回答成功后，原子替换最后一轮旧消息
5. 新回答失败时，不销毁旧消息，返回失败结果，由前端恢复编辑态并保留用户草稿

**接口扩展性**：

- 第一阶段：`edit_last_user_message=true` 表示"编辑最后一条 user 消息并替换最后一轮"
- 第二阶段预留：后续如需编辑指定历史轮次，引入稳定 `message_id` 并扩展目标消息字段；不建议用单一字段特殊值长期承载全部语义

**前端实现**：

- 在最后一条 `user` 消息底部增加"编辑"按钮
- 点击后：该条消息切换为 textarea，原消息内容自动写入 textarea
- textarea 下方出现"确认"和"取消"按钮
- 确认时发送带 `edit_last_user_message: true` 的 CHAT 请求；取消时退出编辑态，不发请求
- 前端 store 处理：
  - 编辑确认发送后，不立即销毁旧的最后一组 user+assistant，而是等待成功后整体替换
  - 成功回包后，再以新一组 user+assistant 替换旧内容
  - 失败回包后，恢复编辑态并保留用户草稿，展示错误提示

**后端实现**（v0.9 子步骤，不独立拆分）：

- `ChatWebSocketHandler` 解析 `edit_last_user_message` 字段，透传至 `LlmChatService`
- `ConversationMemory` 新增“读取最后一轮 / 成功后替换最后一轮”的能力；不采用“先删后生成”的破坏式路径
- `LlmChatService` 在 `edit_last_user_message=true` 时按“先生成、成功后替换、失败则保留旧轮次”的语义执行
- 如协议层需要更清晰区分普通失败与编辑提交失败，可为前端补充失败原因字段或沿用现有 `ERROR`，由前端根据请求上下文决定是否恢复编辑态

**第二阶段预留**：

- 为消息引入稳定 `messageId`
- 前端点击某条历史 user 消息时传递目标消息 ID
- 后端从该消息开始截断其后整段历史，再基于截断后上下文生成新的后续回答

**复杂度判断**：

- 只编辑最后一条 user 消息：中等复杂度。为避免失败时丢失旧轮次，需引入“成功后替换”的非破坏式语义和前端内联编辑 UI
- 编辑任意历史轮次：中等偏高复杂度，主要成本在消息 ID、协议扩展、精确截断定位

#### 必要性

对当前项目而言，"只编辑最后一条 user 消息"有实现价值：

- 当前场景是实时海事风险解释，用户问题通常是短链路问答
- 高频需求更像是"上一条提问想改一下再发"
- 实时风险上下文会变化，越早的历史轮次越容易与当前态势脱节
- 任意轮次回滚会引入历史时态与当前风险快照不一致的问题

对 agent loop 场景：如果未来演进为长对话、多步骤任务编排，编辑任意历史轮次的价值才会上升；当前阶段，先实现"编辑最后一条 user 消息"，接口预留扩展能力。

---

### 4C. 解释上下文随 selectTarget 自动注入

**背景**：`selected_target_ids` 协议已支持后端在对话中注入选中目标的完整风险数据。但当目标存在 LLM 解释文本时，该解释未被一并注入——用户选中某个目标并提问时，AI 看不到自己之前为该目标生成的解释。

**方案**：不新增 UI 元素或独立的"询问 AI"入口。修改后端注入逻辑：当 `selected_target_ids` 包含某目标，且该目标在最近的 `EXPLANATION` 事件中存在解释文本时，后端将解释文本一并注入到 LLM 上下文中。

**前端变更**：无。已有的 `selectTarget` 交互和 `selected_target_ids` 协议透传保持不变。

**后端变更**：

- 新增 `ExplanationCache`，按 `targetId` 持有每个目标最近一条解释文本及其时间戳，并维护“当前仍被追踪且仍非 SAFE”的目标集合
- 缓存生命周期与前端解释卡片严格对齐：目标消失于追踪列表或当前风险等级降为 SAFE 时驱逐；新解释到达时仅在统一校验通过后 upsert 覆盖
- `LlmRiskEventListener` 在每次风险评估事件中刷新目标追踪/风险状态；新解释到达时统一校验“目标仍被追踪且仍非 SAFE”，满足条件才同时发布 `EXPLANATION` SSE 并调用 `put`
- `RiskContextFormatter` 格式化选中目标时，若缓存命中且目标当前风险等级非 SAFE，则追加解释文本（含生成时间戳供 LLM 感知新鲜度）；该 SAFE 判断保留为格式化层防御性兜底
- 不影响未选中目标的上下文注入行为

**设计优势**：

- 无新 UI 元素，交互心智模型统一：选中目标 = AI 看到该目标的全部信息（含解释）
- 复用现有 `selected_target_ids` 协议，不引入 `selectExplanation` 等新概念
- 将解释缓存与风险快照缓存分离，避免错误假设 `RiskContextHolder` 已持有 explanation 文本

**约束**：解释文本注入仅在 Chat 链路（WebSocket）中生效，不影响 risk SSE 链路。

---

### Step 4 验收

- `recording` 状态下有可点击的“取消录音”按钮，点击后状态回退至 `idle`，且不会发送请求到后端
- 最后一条 user 消息底部有"编辑"按钮，点击后该消息切换为可编辑输入框，默认填入原消息内容
- 点击确认后，以 `edit_last_user_message: true` 发起编辑确认请求；成功后旧的最后一轮被新结果替换；失败后原最后一轮保持不变，编辑框重新打开并保留草稿
- 选中含解释的目标后发起 Chat，AI 回复内容表明其已获取解释上下文

---

## 三、各步骤交付物汇总

| 步骤 | 主要交付物 |
| --- | --- |
| Step 1 | vitest 配置、首批测试文件（store + services + 冒烟） |
| Step 2 | 删除 `CompassOverlay`；新增 `ToolbarOverlay`（主题/语音）；`frontend-design.md` 新增章节（组件职责 + 状态机 + 扩展点 + 抽屉策略） |
| Step 3 | TargetsPanel + RiskExplanationPanel 展示增强、StatusPanel 双连接状态增强 |
| Step 4 | 取消录音（前端本地）、编辑最后一条用户消息（前后端联动，失败恢复编辑态并保留草稿）、解释上下文注入（后端缓存支持） |

---

## 四、延后与不做项

以下内容在讨论中明确不纳入本版本，原因已记录：

| 条目 | 原因 | 去向 |
| --- | --- | --- |
| 真正取消 LLM 回复 | 需要协议新增 `CANCEL` 上行消息 + 后端 LLM HTTP 请求中断机制，工作量和风险超出本版本范围 | `docs/TODO.md §4` |
| 已发送语音请求取消 / 真正取消后端转录 | 需要协议新增 `CANCEL` 上行消息、后端转录任务注册表与迟到结果丢弃规则；当前展示阶段收益不足以覆盖复杂度 | `docs/TODO.md §4-5` |
| 编辑任意历史消息 | 依赖消息 ID 体系 + 精确历史截断，复杂度中等；当前场景需求有限 | Step 4B 第二阶段预留 |
| Generative UI / 动画指令 | 依赖 agent loop 产出的结构化 advisory，当前无数据源 | `docs/TODO.md §4`，待 agent loop 后启动 |
| 带唤醒词语音交互 | P4 级别，不在本版本 | `docs/TODO.md §5` |
| 面板抽屉伸缩泛化 | StatusPanel / TargetsPanel 为持续展示型 HUD，收起后失去态势感知，交互收益不抵信息损失 | 不计划实现 |
