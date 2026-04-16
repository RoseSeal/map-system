# Step 1：测试框架与基线

> 文档状态：completed
> 最后更新：2026-04-16
> 对齐文档：[`FRONTEND_ENHANCEMENT_PALN.md`](./FRONTEND_ENHANCEMENT_PALN.md)
> 说明：本文保留 Step 1 的实施前分析、方案与验收记录；当前稳定事实以 `docs/frontend-design.md`、`docs/EVENT_SCHEMA.md`、`docs/ARCHITECTURE.md` 为准。

## 1. 摘要

Step 1 的目标是在 `frontend/` 内建立最小可持续的自动化测试闭环，为 Step 2 至 Step 4 的前端增强提供回归保障。

本步骤不追求完整覆盖率，也不引入浏览器级 E2E。范围限定为：

- 建立 `vitest + jsdom + testing-library` 测试基础设施
- 为 `store/` 与 `services/` 的核心状态流和协议分发建立首批单元测试
- 建立一条以 `TargetsPanel` 为终点的 dashboard 冒烟用例，验证风险事件进入前端后的最短可见路径

## 2. 当前状态与约束

当前前端不存在任何测试依赖，`frontend/package.json` 仅包含 `dev`、`build`、`lint` 脚本，`frontend/src/` 下没有测试文件。

结合现有代码实现，Step 1 需要遵守以下约束：

- `useRiskStore.ts` 与 `useAiCenterStore.ts` 在模块加载时即注册 `riskSseService` / `chatWsService` 订阅，测试不能在真实服务实例已初始化后再补做 mock。
- `App.tsx` 启动时会直接连接 SSE/WS，并挂载 `MapContainer`。如果直接将整页作为第一批冒烟对象，会引入 `MapLibre`、`Deck.gl`、`EventSource`、`WebSocket` 多重依赖，超出本步骤的测试基线目标。
- `TargetsPanel.tsx`、`useRiskStore.ts`、`riskSseService.ts` 已经形成一条清晰的“事件进入 -> store 更新 -> 面板渲染”链路，适合作为首条 UI 冒烟路径。
- `chatWsService.ts` 存在心跳与重连计时器，相关测试需要统一使用 fake timers，避免真实定时器导致用例不稳定。

## 3. 范围与非目标

### 本步骤完成

- 在现有 `frontend/vite.config.ts` 中补充 `test` 配置，不新增独立 `vitest.config.ts`
- 增加 `npm test` 脚本，默认执行一次性测试运行
- 建立统一的测试初始化文件与最小测试数据夹具
- 覆盖 `useRiskStore`、`useAiCenterStore`、`riskSseService`、`chatWsService` 的核心路径
- 增加 `TargetsPanel` 冒烟测试，验证风险目标可从事件流显示到面板

### 本步骤不做

- 不测试 `MapContainer` 的地图渲染、Deck 图层绘制、S-57 图层加载
- 不引入 Playwright、Cypress 等浏览器级 E2E
- 不为视觉样式、动画或截图回归建立基线
- 不覆盖 `voiceRecorderService` 的浏览器录音集成；相关录音交互变更留给 Step 4 一并处理

其中，录音状态机本身仍在 `useAiCenterStore` 范围内验证，但 `MediaRecorder`、麦克风权限和音频编码链路不属于本步骤。

## 4. 设计决策

### 4.1 测试配置并入现有 Vite 配置

`frontend/` 已存在单一的 `vite.config.ts`，其中包含 alias 与 `optimizeDeps`。Step 1 直接在该文件中增加 `test` 段，避免新增并行配置源。

预期配置要点：

- `environment: 'jsdom'`
- `globals: true`
- `setupFiles: ['./src/test/setup.ts']`
- 如后续需要，可在同一处补充 `css: true` 或 `restoreMocks: true`

### 4.2 首批测试文件采用“按模块就近放置”

为了让测试跟随实现演进，首批文件直接放在对应模块旁边：

- `src/store/useRiskStore.test.ts`
- `src/store/useAiCenterStore.test.ts`
- `src/services/riskSseService.test.ts`
- `src/services/chatWsService.test.ts`
- `src/components/Dashboard/TargetsPanel.test.tsx`

公共初始化与夹具放入：

- `src/test/setup.ts`
- `src/test/fixtures/`

仅放置共享测试资源，不新增通用测试抽象层。

### 4.3 Store 测试采用“先 mock 服务，再导入 store”

两个 Zustand store 都在模块末尾执行初始化订阅逻辑。测试若先导入 store，再 mock 服务，将错过订阅替换时机。

Step 1 的默认实现方式应为：

1. `vi.mock('../services/...')`
2. 导入目标 store 模块
3. 在每个用例结束后显式重置 store 状态

这里需要区分两个场景：

- 常规场景：依赖 Vitest 顶层 `vi.mock()` 的 hoist 机制即可，保证 store 模块加载时拿到的是 mock 版本的 `riskSseService` / `chatWsService`。这一场景不要求默认引入 `vi.resetModules()`。
- 特殊场景：只有在同一个测试文件内需要重新评估模块初始化副作用时，才使用 `vi.resetModules()` 或等价模块隔离，例如验证 `hasInitializedRiskStoreSubscriptions`、`hasInitializedAiCenterStoreSubscriptions` 这类 guard flag 的重置行为。

另外，Step 1 实现时需要为 `useAiCenterStore` 新增 `reset(): void`，行为与 `useRiskStore.reset()` 对齐，直接执行 `set(initialState())`。原因是现有 `resetConversation()` 只清理对话消息，不会恢复 `voiceCaptureState`、`speechEnabled`、`speechUnlocked` 等字段，无法满足跨用例隔离要求。

因此，Step 1 的 store 测试清理规则应为：

- `useRiskStore` 用例结束后调用现有 `reset()`
- `useAiCenterStore` 用例结束后调用本步骤新增的 `reset()`

### 4.4 Dashboard 冒烟只验证“风险流到面板”的最短路径

本步骤的 UI 冒烟不以完整 `App` 为目标，而是以 `TargetsPanel` 为目标组件，配合 mock 的风险服务事件触发：

1. 注册 `riskSseService` 订阅
2. 触发 `RISK_UPDATE`
3. 验证 `useRiskStore` 更新
4. 验证 `TargetsPanel` 渲染目标列表与排序

这样可以覆盖前端最核心的一条 operator-facing 链路，同时避免将地图与实时连接行为引入测试基线。

## 5. 实施方案

### 5.1 工具与脚本

在 `frontend/package.json` 中新增以下开发依赖：

- `vitest`
- `jsdom`
- `@testing-library/react`
- `@testing-library/jest-dom`
- `@testing-library/user-event`

脚本调整：

- `npm test` -> `vitest run`

是否补充 `test:watch` 不作为本步骤必需项；若实现时顺手加入，不影响边界。

### 5.2 测试基础设施

`src/test/setup.ts` 负责：

- 引入 `@testing-library/jest-dom`
- 在每个用例后执行 `cleanup`
- 统一清理 fake timers / mock 状态
- 仅在实际缺失时补充浏览器 API polyfill，不预先为所有接口建立宽泛 mock
- 补充一条说明：`constants.ts` 在 jsdom 下可能生成异常的默认 SSE/WS URL，这是已知现象；由于 Step 1 的 service 测试和组件冒烟均使用 mock，不需要为此额外添加全局 URL polyfill

`src/test/fixtures/` 提供最小可复用数据：

- `risk-update` 夹具：至少包含 2 个不同 `risk_level` 的目标，便于验证排序
- `explanation` 夹具：覆盖已存在目标与不存在目标两类情况
- `chat reply / transcript / error` 夹具：覆盖正常回包与错误回包

夹具应贴近 `schema.d.ts` 的真实字段，不引入脱离协议的二次包装。

### 5.3 `useRiskStore` 测试

覆盖重点如下：

- `setRiskUpdate`
  - 写入 `ownShip`、`targets`、`governance`、`environment`
  - 根据 `trust_factor` 计算 `isLowTrust`
  - 当目标消失时清理 `selectedTargetIds`
  - 当目标消失时同步清理 `explanationsByTargetId`
  - 为被移除的已选目标累计 `droppedTargetNotices`
- `upsertExplanation`
  - 已存在目标可写入 explanation
  - 不存在目标时忽略写入
- `selectTarget` / `deselectTarget`
  - 单目标选中
  - 已选目标再次点击触发取消
  - `selectTarget(null)` 清空全部选择
- 订阅联动
  - 模拟 `riskSseService.onRiskUpdate` / `onExplanation` / `onError` / `onConnectionStatusChange`
  - 验证 store 初始化订阅后可响应服务层回调

### 5.4 `useAiCenterStore` 测试

覆盖重点如下：

- `sendTextMessage`
  - 空字符串拒绝发送
  - 已有 pending user message 时拒绝发送
  - 正常发送时调用 `chatWsService.send('CHAT', ...)`
  - 若 risk store 已选中目标，则附带 `selected_target_ids`
- `sendSpeechMessage`
  - 空音频或空格式拒绝发送
  - `preview` 模式不立即追加用户消息
  - 非 `preview` 模式追加占位用户消息
  - 发送成功后进入 `transcribing`
- `appendSpeechTranscript`
  - `preview` 模式将 transcript 写回输入框
  - 非 `preview` 模式更新对应用户消息内容并保持会话一致性
- `appendChatReply`
  - 正常 reply 追加 assistant message
  - 对应 user message 状态更新为 `replied`
  - 重复 reply event 不重复插入 assistant message
  - `conversation_id` 与当前 `state.conversationId` 不一致时忽略该 reply，保持会话隔离
- `appendChatError`
  - 错误回包会将对应 user message 标记为 `error`
  - 无 `reply_to_event_id` 或目标消息不存在时保持边界稳定
- 语音状态机
  - `setVoiceCaptureRecording`
  - `setVoiceCaptureTranscribing`
  - `setVoiceCaptureSent`
  - `setVoiceCaptureSent(eventId)` 在 `eventId` 与 `activeVoiceEventId` 不匹配时保持状态不变
  - `setVoiceCaptureError`
  - `resetVoiceCapture`

另外，`useAiCenterStore.reset()` 作为本步骤新增能力，需要覆盖其“恢复完整初始状态”的行为，而不是以 `resetConversation()` 代替。

`resetConversation`、TTS 播放去重等次要路径不纳入 Step 1 首批覆盖。

### 5.5 `riskSseService` 测试

通过 mock `EventSource` 验证以下行为：

- `connect()` 创建单一连接，重复调用不重复创建
- `onopen` 时派发已连接状态
- `RISK_UPDATE`、`EXPLANATION`、`ERROR` 三类事件可被正确解析并转发
- 非法 JSON 不触发回调
- `disconnect()` 解除事件监听并关闭连接，同时向连接状态订阅者派发 `cb(false, null)`，明确表示主动断开
- `onerror` 路径向连接状态订阅者派发 `cb(false, '风险态势连接中断')`，明确表示异常断连

### 5.6 `chatWsService` 测试

通过 mock `WebSocket` 与 fake timers 验证以下行为：

- `connect()` 建立连接并在 `onopen` 后进入 `connected`
- `send('CHAT' | 'SPEECH' | 'CLEAR_HISTORY')` 会生成 `event_id` 并发送 envelope
- 未连接时 `send` 返回 `null` / `false`
- `handleMessage` 能正确分发 `CHAT_REPLY`、`SPEECH_TRANSCRIPT`、`ERROR`、`CLEAR_HISTORY_ACK`、`PONG`
- 非法 JSON 被忽略
- `disconnect()` 停止心跳、关闭 socket，并阻止自动重连
- 非手动关闭时按退避策略进入 `reconnecting`

### 5.7 Dashboard 冒烟测试

目标组件：`TargetsPanel`

由于 `TargetsPanel.tsx` 直接消费 `useAiCenterStore` 的 `requestAiCenterOpen`，而 `useAiCenterStore` 在模块加载时会初始化 `chatWsService` 订阅，因此该冒烟测试不能只 mock `riskSseService`。实现时需要与 store 测试保持一致，同时对 `chatWsService` 做顶层 mock，避免组件加载阶段触发真实 `WebSocket` 依赖。

验证路径：

1. 渲染 `TargetsPanel`
2. 通过已注册的 `riskSseService` mock 回调推送 `RISK_UPDATE`
3. 断言面板出现
4. 断言目标数量、目标 ID、风险等级文案、排序顺序符合当前实现

本用例不验证：

- 地图存在与否
- 目标点击后的 AI 中心打开动画
- 解释卡片联动

这条用例的职责仅是为 dashboard 建立第一条可持续的 UI 回归链路。

## 6. 影响文件

Step 1 预期触达以下文件或目录：

- `frontend/package.json`
- `frontend/vite.config.ts`
- `frontend/src/test/setup.ts`
- `frontend/src/test/fixtures/*`
- `frontend/src/store/useAiCenterStore.ts`
- `frontend/src/store/useRiskStore.test.ts`
- `frontend/src/store/useAiCenterStore.test.ts`
- `frontend/src/services/riskSseService.test.ts`
- `frontend/src/services/chatWsService.test.ts`
- `frontend/src/components/Dashboard/TargetsPanel.test.tsx`

如测试实现需要极小量辅助导出，应优先选择最小可见性调整，不新增测试专用运行时层。

## 7. 验证标准

Step 1 完成后，应满足以下条件：

- `npm test` 可在 `frontend/` 内独立执行并稳定通过
- `useAiCenterStore` 已新增 `reset()`，并可在测试中恢复完整初始状态
- store 与 service 首批测试覆盖本步骤列出的核心路径
- 至少存在一条 UI 冒烟用例，能验证风险事件到目标面板渲染的通路
- 所有 mock 与 timer 在用例结束后可清理，不存在跨用例污染

## 8. 风险与实施注意事项

- Zustand store 为单例。若不在每个用例后重置状态，测试将互相污染。
- `useAiCenterStore` 在 Step 1 之前缺少完整 `reset()`；若不在实现中补上，该 store 无法可靠恢复到初始状态。
- store 模块的订阅初始化发生在 import 阶段；常规场景依赖顶层 `vi.mock()` hoist 即可，只有在需要重新评估模块初始化副作用时才使用 `vi.resetModules()`。
- `chatWsService` 依赖 `crypto.randomUUID()`。若测试运行环境缺失该接口，只补最小 polyfill，不引入额外 ID 生成抽象。
- Step 1 只建立基线，不以覆盖率数字作为交付目标。覆盖范围以“核心状态流是否被自动化验证”作为验收标准。
