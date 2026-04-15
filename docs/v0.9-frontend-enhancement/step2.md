# Step 2：前端架构文档与组件清理

> 文档状态：active
> 最后更新：2026-04-15
> 对齐文档：[`FRONTEND_ENHANCEMENT_PALN.md`](./FRONTEND_ENHANCEMENT_PALN.md)

## 1. 摘要

Step 2 的目标是在进入展示增强与交互增强前，先完成一轮低风险的前端边界清理：补全文档、收敛组件职责、移除已确认无保留价值的叠加层，并把与连接状态无关的全局控制从 `StatusPanel` 中拆出。

本步骤不引入新的风险语义展示，也不扩展聊天协议。重点是让 Step 3 与 Step 4 建立在更稳定的组件边界和更明确的状态流转说明之上。

## 2. 当前状态与问题定位

结合当前实现，Step 2 需要处理以下具体问题：

- `docs/frontend-design.md` 仍以协议契约与渲染规则为主，缺少组件职责图、聊天消息生命周期、语音状态机、后续 agent loop 扩展点说明。
- [`frontend/src/components/Dashboard/StatusPanel.tsx`](/home/xin/workspace/map-system/frontend/src/components/Dashboard/StatusPanel.tsx) 同时承担本船 HUD、连接状态、主题切换、语音播报开关四类职责，已经越过“状态展示组件”的边界。
- [`frontend/src/components/Overlays/CompassOverlay.tsx`](/home/xin/workspace/map-system/frontend/src/components/Overlays/CompassOverlay.tsx) 叠加 OZT 扇区与航向罗盘，但其信息与海图方位参考重复，且已知存在显示不一致问题。
- [`frontend/src/App.tsx`](/home/xin/workspace/map-system/frontend/src/App.tsx) 当前直接组合 `StatusPanel`、`CompassOverlay`、`RiskExplanationPanel`、`TargetsPanel`，缺少对“持续 HUD”和“全局设置控件”的区分。
- [`frontend/src/components/Dashboard/RiskExplanationPanel.tsx`](/home/xin/workspace/map-system/frontend/src/components/Dashboard/RiskExplanationPanel.tsx)、[`frontend/src/components/Dashboard/ChatComposer.tsx`](/home/xin/workspace/map-system/frontend/src/components/Dashboard/ChatComposer.tsx)、[`frontend/src/hooks/useVoiceCapture.ts`](/home/xin/workspace/map-system/frontend/src/hooks/useVoiceCapture.ts) 已形成较清晰的语音状态链路，但该链路尚未在文档中正式记录。
- 风险 SSE 的连接状态当前只落在 `useRiskStore.isConnected`；聊天 WebSocket 连接状态则只由 `chatWsService` 内部维护，尚未形成统一展示模型。该问题在 Step 2 只做文档标注与边界预留，实际展示增强留到 Step 3。

## 3. 范围与非目标

### 本步骤完成

- 更新 `docs/frontend-design.md`，补齐当前前端内部架构说明
- 从运行时布局中移除 `CompassOverlay`
- 将主题切换与语音播报开关从 `StatusPanel` 中拆出为独立轻量组件
- 调整 `App.tsx` 的 overlay 组合方式，使 HUD 与工具控件职责分离
- 明确记录后续 Step 3 / Step 4 需要承接的扩展点

### 本步骤不做

- 不实现 `encounter_type`、`risk_score`、`risk_confidence` 的 operator-facing 展示；这些内容延后到 Step 3
- 不实现 risk SSE / chat WS 双连接状态的最终聚合展示；该展示模型延后到 Step 3
- 不实现取消录音、最后一轮重答、解释上下文注入；这些交互增强延后到 Step 4
- 不修复或替代罗盘组件；本步骤直接删除，而不是继续投入修复
- 不调整 `useAiCenterStore`、`useRiskStore` 的业务语义，仅在文档中定义其职责边界

## 4. 设计决策

### 4.1 罗盘组件直接删除，不保留兼容层

Step 2 直接删除 `CompassOverlay` 及其在 `App.tsx`、`components/index.ts`、`components/Overlays/index.ts` 中的引用，不增加 feature flag，也不提供临时替代组件。

原因如下：

- 其视觉位置与右侧 `RiskExplanationPanel` 存在竞争关系，遮挡高频关注区域
- OZT 扇区与底图中的空间风险信息重复，不再构成独立的 operator-facing 入口
- 现有实现按航向旋转 cardinal directions 与 OZT arc，已知与真实空间方位存在偏差风险
- 继续修复该组件只会扩大 Step 2 范围，不符合“清理优先于增强”的目标

该删除属于“本版本不再做”，不是 deferred 条目，不进入后续步骤。

### 4.2 `StatusPanel` 只保留本船 HUD 与平台状态

`StatusPanel` 在 Step 2 后仅负责展示：

- 本船动态：`sog`、`cog`、`hdg`
- 本船位置：经纬度
- 平台健康状态：`platform_health.status`
- 全局可信度提示：`governance.trust_factor`
- 连接状态展示占位：当前先保留现有 risk SSE 在线/离线语义，后续 Step 3 再扩展为双连接模型

以下内容从 `StatusPanel` 移出：

- 系统主题切换
- 语音播报开关

这样可以把 `StatusPanel` 收敛回“持续态势 HUD”，避免连接离线时误伤与连接无关的全局设置能力。

### 4.3 新增独立轻量工具组件承载全局设置

Step 2 新增一个轻量 overlay 组件，推荐命名为 `ToolbarOverlay`，位置放在地图边缘的独立区域，由 `App.tsx` 直接挂载。

该组件只负责：

- 主题切换
- 语音播报开关

该组件不负责：

- 展示任何船舶数据
- 聚合聊天状态
- 承担连接可视化主入口

状态来源保持现有实现，直接复用：

- `useThemeStore`
- `useAiCenterStore`
- `speechService`

Step 2 不新增新的 store 或中间状态层。

### 4.4 文档按“当前实现 + 已确定扩展点”重写局部章节

`docs/frontend-design.md` 需要在保留现有协议与渲染规则章节的前提下，补写以下结构：

1. 组件职责地图
2. AI Center 内部组成与边界
3. `voiceCaptureState` 状态机
4. `AiCenterChatMessage.status` 消息生命周期
5. risk SSE / chat WS 连接状态的当前来源与未来聚合边界
6. agent loop 扩展点
7. overlay 布局策略与抽屉策略

文档内容需要区分三类信息：

- 当前已实现
- Step 2 后的目标边界
- Deferred 到 Step 3 / Step 4 的能力

不得把未实现能力写成已完成状态。

## 5. 实施方案

### 5.1 组件与文件改动范围

Step 2 预期涉及以下文件：

- `docs/frontend-design.md`
- `frontend/src/App.tsx`
- `frontend/src/components/Dashboard/StatusPanel.tsx`
- `frontend/src/components/Dashboard/StatusPanel.test.tsx`（如当前不存在则新增最小渲染测试）
- `frontend/src/components/Overlays/CompassOverlay.tsx`
- `frontend/src/components/Overlays/index.ts`
- `frontend/src/components/index.ts`

按实现需要，允许新增：

- `frontend/src/components/Overlays/ToolbarOverlay.tsx`
- `frontend/src/components/Overlays/ToolbarOverlay.test.tsx`

如果团队现有目录习惯更适合放入 `Dashboard/`，也可以落在 `Dashboard/`，但职责上必须仍然是“工具控件 overlay”，不能重新并入 `StatusPanel`。

测试要求保持与 Step 1 一致：本步骤新增或重构的 UI 组件至少补一条最小挂载/渲染冒烟测试，避免测试基线只覆盖 store 与 service，而组件清理继续完全回退到手工验证。

### 5.2 布局重组

`App.tsx` 在 Step 2 后的 overlay 分层应调整为：

- 左上：`StatusPanel`
- 右侧：`RiskExplanationPanel`
- 左下：`TargetsPanel`
- 地图边缘独立区域：`ToolbarOverlay`
- 右下：`Legend`

`CompassOverlay` 删除后，不保留占位容器，避免空布局痕迹。

如果删除罗盘后暴露出遗留的导出、布局常量、定位 class 或无效引用，应在同一步中清理；但不预设存在独立样式文件，也不把本步骤扩展为一次全局样式整理。

### 5.3 组件职责地图写入文档

`docs/frontend-design.md` 中至少要明确以下边界：

| 组件 | 职责 | 不属于其职责 |
| --- | --- | --- |
| `RiskExplanationPanel` | AI 中心容器，整合态势日志区与对话区 | 不做 store 业务计算，不聚合地图连接状态 |
| `TargetsPanel` | 风险目标列表、目标选择、触发 AI 中心打开 | 不展示聊天消息，不维护解释缓存 |
| `ChatMessageList` | 渲染消息列表与局部重试入口 | 不持有消息状态，不负责发送 |
| `ChatComposer` | 文本输入、语音入口、目标 chip 展示 | 不直接操作服务层，不持有录音实现 |
| `StatusPanel` | 本船 HUD、平台健康、可信度与连接状态展示 | 不承载主题与播报设置 |
| `ToolbarOverlay` | 主题切换、语音播报开关 | 不展示船舶数据，不承担态势 HUD |

### 5.4 语音状态机与消息生命周期写入文档

`voiceCaptureState` 在 Step 2 文档中按当前实现描述为：

```text
idle -> recording -> transcribing -> sent -> idle
                 \-> error -------> idle
recording --cancel--> idle
```

需要明确：

- `cancel` 只发生在本地录音阶段
- `transcribing` 阶段没有取消能力
- `preview` 与 `direct` 的差异体现在 `sendSpeechMessage` 和 transcript 回写路径，而不是状态枚举本身

聊天消息生命周期需要以 `AiCenterChatMessage.status` 为主线描述：

```text
user send -> pending -> replied
                  \-> error

speech preview transcript -> sent
assistant reply            -> sent
```

同时注明：

- `pending` 用户消息会显示“处理中”占位，但主动中断仍是协议预留项
- “重答最后一轮”不属于 Step 2，实现留到 Step 4

### 5.5 连接状态边界只做预留，不提前实现聚合层

Step 2 文档需要显式记录：

- 当前 risk 连接状态来源：`useRiskStore.isConnected`
- 当前 chat 连接状态来源：`chatWsService.getState()`
- Step 3 需要补一个前端展示模型，把两条连接统一映射为 operator-facing 状态文案

Step 2 不新增 `useConnectionStore` 之类的新层；该抽象在当前版本属于过度设计。

## 6. 约束与不变量

- 不改变 risk SSE 与 chat WS 协议契约
- 不改变 `useVoiceCapture`、`useAiCenterStore`、`useRiskStore` 的核心状态机语义
- 不把清理任务扩大成布局风格重做
- 不借 Step 2 顺手引入新的全局状态层
- 拆分 `ToolbarOverlay` 时直接复用现有 `useThemeStore`、`useAiCenterStore` 与 `speechService`，不修改 `speechService` 的 TTS 逻辑或其他 service 内部实现
- 删除 `CompassOverlay` 时不得连带修改地图渲染算法或 OZT 数据消费逻辑

## 7. 验证方案

Step 2 完成后至少验证以下内容：

1. 前端可正常构建，`App.tsx` 不再引用 `CompassOverlay`
2. `StatusPanel` 仍能展示本船状态、健康状态、低置信度提示与连接状态
3. 主题切换与语音播报开关在连接离线时依然可操作
4. 删除 `CompassOverlay` 后无残留导出、无效引用或多余布局占位；若布局边缘留白或遮挡关系变化，应在 `App.tsx` 一并修正
5. `RiskExplanationPanel`、`TargetsPanel`、`Legend` 的现有交互不受布局调整影响
6. `docs/frontend-design.md` 已新增组件职责、语音状态机、消息生命周期与扩展点说明，且 Deferred 项明确标注到 Step 3 / Step 4
7. `StatusPanel` 与 `ToolbarOverlay` 的最小组件测试可通过；如果 Step 2 调整了导出路径，相关测试导入也同步更新

如果 Step 1 已落地测试基线，则 Step 2 应补跑现有前端测试，确认组件清理未破坏 store 与 service 的既有行为。

## 8. Deferred 与后续承接

- Deferred 到 Step 3：`encounter_type`、`risk_score`、`risk_confidence` 的前端展示；risk SSE / chat WS 双连接状态的 operator-facing 聚合展示
- Deferred 到 Step 4：取消录音、最后一轮重答、解释卡片上下文注入
- Not doing：`CompassOverlay` 的修复、替代版本或兼容保留

连接离线或 WebSocket 处于 `reconnecting` 时，Step 2 不新增额外语音交互状态分支，继续沿用现有服务层发送阻断语义；更清晰的连接态 UI 提示与交互约束由 Step 3 统一设计。

Step 2 不新增 `docs/TODO.md` 条目，因为上述 deferred 内容已经由当前步骤链明确承接，不属于链外遗留事项。
