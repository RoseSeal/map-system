# 前端设计参考文档

> 文档状态：当前 (Current)
> 最后更新：2026-04-15
> 基线来源：`docs/ARCHITECTURE.md`、`docs/EVENT_SCHEMA.md`

本文档描述了 Map System 前端的架构设计、渲染模型及运行指南。

## 1. 项目背景

前端是一个基于 WebGL 的态势感知界面，专为 Map System 海上风险预警平台设计。

核心目标：
- 实时渲染 2.5D 海图（S-57 ENC）。
- 可视化动态船舶风险信息（CPA、OZT、安全域、预测轨迹）。
- 通过聊天面板支持基于 LLM 的风险解释及语音交互。

主要前端路径：`frontend/`

## 2. 架构模型

系统遵循“重后端 + 轻前端”模式：
- 后端 (`map-service`) 负责 MQTT 数据接入、风险计算、LLM 编排及流式数据推送。
- 前端专注于数据渲染与用户交互。

传输协议：
- 风险流：SSE `/api/v2/risk` — `RISK_UPDATE`（风险更新）, `EXPLANATION`（解释）, `ERROR`（错误）
- 聊天流：WebSocket `/api/v2/chat` — `CHAT`, `SPEECH`, `CHAT_REPLY`, `SPEECH_TRANSCRIPT`, `ERROR`

完整协议定义请参阅 `docs/EVENT_SCHEMA.md`。

## 3. 前端技术栈

- React 18（函数式组件 + Hooks）
- Vite 5
- TypeScript（严格类型检查）
- Zustand（状态管理）
- MapLibre GL JS 4+
- Deck.gl 9+（MapboxOverlay 集成）
- Tailwind CSS 3+

## 4. 目录结构

```text
frontend/
  src/
    components/
      Dashboard/        ← 态势 HUD 与 AI 中心组件
      Map/              ← 地图渲染核心
      Overlays/         ← 全局工具与浮层
    config/
      constants.ts      ← 常量配置
    services/
      riskSseService.ts       ← SSE 客户端（风险流）
      chatWsService.ts        ← WebSocket 客户端（聊天流）
    store/
      useRiskStore.ts         ← 态势数据存储
      useAiCenterStore.ts     ← 聊天与语音状态存储
    types/
      schema.d.ts             ← 协议类型定义（以 EVENT_SCHEMA.md 为准）
    utils/
```

## 5. 组件职责地图

| 组件 | 职责 | 不属于其职责 |
| --- | --- | --- |
| `RiskExplanationPanel` | AI 中心容器，整合态势日志区与对话区 | 不进行 Store 业务计算，不聚合地图连接状态 |
| `TargetsPanel` | 风险目标列表、目标选择、触发 AI 中心打开 | 不展示聊天消息，不维护解释缓存 |
| `ChatMessageList` | 渲染消息列表与局部重试入口 | 不持有消息状态，不负责发送逻辑 |
| `ChatComposer` | 文本输入、语音入口、目标 Chip 展示 | 不直接操作服务层，不持有录音实现 |
| `StatusPanel` | 本船 HUD、平台健康、可信度与连接状态展示 | 不承载主题切换与播报设置 |
| `ToolbarOverlay` | 主题切换、语音播报开关 | 不展示船舶数据，不承担态势 HUD 职能 |

## 6. 语音状态机与消息生命周期

### 6.1 语音采集状态 (Voice Capture State)

`voiceCaptureState` 在 `useAiCenterStore` 中维护，流转如下：

```text
idle -> recording -> transcribing -> sent -> idle
                 \-> error -------> idle
recording --cancel--> idle
```

- `cancel`: 仅发生在本地录音阶段（录音时长过短或用户主动取消）。
- `transcribing`: 后端正在通过 Whisper 进行转录，此时不可取消。
- `sent`: 转录结果已回填至输入框或已作为消息发送。

### 6.2 聊天消息生命周期 (Message Lifecycle)

以 `AiCenterChatMessage.status` 为核心状态主线：

```text
user send -> pending -> replied
                  \-> error

speech preview transcript -> sent
assistant reply            -> sent
```

- `pending`: 用户消息已通过 WebSocket 发送，等待后端响应。
- `replied`: 已收到 `CHAT_REPLY`，对话回合完成。
- `error`: 发送失败、连接断开或响应超时。

## 7. 连接管理

前端当前维护两条独立连接：
- **风险 SSE**: 通过 `useRiskStore.isConnected` 反映连接状态。
- **聊天 WS**: 通过 `chatWsService.getState()` 反映连接状态。

**计划项（Step 3）**: 将实现统一的连接展示模型，在 UI 上聚合展示双连接的整体健康度。

## 8. 数据层与契约模型

主要渲染单元：`RiskObject` (`RISK_UPDATE` 载荷)

核心字段说明：
- `risk_object_id`, `timestamp`, `governance.mode`, `governance.trust_factor`
- `own_ship`: `position`（位置）, `dynamics`（动力学数据：`sog`, `cog`, `hdg`, `rot`）, `platform_health`（平台健康）, `future_trajectory`（预测轨迹）, `safety_domain`（安全域）
- `targets[*]`: `risk_level`（风险等级）, `cpa_metrics`（CPA 指标）, `graphic_cpa_line`（CPA 线）, `ozt_sector`（OZT 扇区）, `encounter_type`（会遇类型）, `risk_score`（风险评分）, `risk_confidence`（评估置信度）, `predicted_trajectory`（预测轨迹）
- `environment_context.safety_contour_val`

风险等级定义：`SAFE`（安全）, `CAUTION`（注意）, `WARNING`（警告）, `ALARM`（警报）

平台健康状态：`NORMAL`（正常）, `DEGRADED`（降级）, `NUC`（失控）

解释事件：`EXPLANATION` 载荷通过风险 SSE 通道分发，独立于 `RISK_UPDATE`。渲染为风险解释卡片。

## 9. 渲染规则

地图与叠加层的层级顺序（从底至顶）：
1. MapLibre 基础地图：从 `/api/s57/tiles/{z}/{x}/{y}.pbf` 加载 S-57 ENC 矢量切片。
2. Deck.gl 动态叠加层：渲染船舶模型、CPA 线、OZT 扇区、安全域及轨迹。
3. React UI 叠加层：面板组件（Dashboard Panels）、聊天面板、主题切换控件。

S-57 海图样式指南：
- 陆地 (`LNDARE`): 基础填充，支持可选 3D 挤压。
- 水深区域 (`DEPARE`): 根据水深值进行深浅色分色。
- 受限区域 (`RESARE`): 半透明红色警示填充。
- 辅助海图层: `COALNE`, `DEPCNT`, `SOUNDG`。

行为触发阈值：
- 当风险等级 >= `CAUTION` 时，显示安全域。
- 当目标 `risk_assessment` 中存在 `graphic_cpa_line` 时，显示 CPA 线。
- 当 `ozt_sector.is_active` 为 true 时，显示 OZT 扇区。
- 当 `trust_factor < 0.4` 时，显示低置信度警告。

## 10. 主题支持

前端支持“亮色 (Light)”与“深色 (Dark)”主题切换。主题状态通过 `useThemeStore` 在前端本地管理，不依赖后端。

## 11. 语音交互

- TTS（语音合成）：使用浏览器原生 `SpeechSynthesis` 播放 LLM 回复。
- ASR（语音识别）：由 `MediaRecorder` 采集音频，作为 `SPEECH` WebSocket 消息（Base64 编码，`webm` 格式）发送至后端。后端编排 `whisper.cpp` 进行转录。
- 模式说明：分为 `direct`（转录并触发 LLM 回复）和 `preview`（仅转录，结果回填至输入框）。

## 12. 前端集成指南

### 12.1 风险评分 (risk_score) 渲染连续性

`risk_score` 是后端生成的连续辅助信号，但在当前版本中，前端不将其作为面向操作员的核心指标展示。典型行为如 CPA 交越点：当目标从接近转为远离时，后端 `tcpaScore` 可能会大幅下降。

当前前端策略：
- 仅将 `risk_score` 作为同一 `risk_level` 组内的次级排序键。
- 排序时将缺失的 `risk_score` 视为 `0.0`。
- 不以文本、进度条、颜色强度、发光强度或其他视觉指标展示 `risk_score`。

后续演进方向：
- 在后端评分模型通过真实交通数据验证后，`risk_score` 可能被提升为面向表现层的展示信号，用于视觉强度或动画增强。
- 届时任何前端平滑逻辑必须仅限于表现层，不得修改警报时机、阈值逻辑或分类状态。

约束：
- 在获得明确批准前，`risk_score` 仅限用于内部排序辅助。

### 12.2 后端字段的分步采用

在引擎增强阶段（如 Step 2 和 Step 3），前端可能会提前扩展 Schema（例如 `targets[*].predicted_trajectory` 和 `targets[*].risk_assessment.encounter_type`）。

前端职责：
- 一旦后端开始输出新字段，立即在 TypeScript Schema 及 Store 接入层支持相关可选字段。
- 避免 UI 层深度耦合语义尚在演进中的字段。
- 优先进行轻量化兼容：Schema 更新、安全解析、可选字段防护及内部渲染 Hook 预留。
- 将面向生产的最终可视化及交互逻辑推迟至 Step 4 风险语义稳定后执行。

实施建议：
- 允许增加仅供调试使用的面板或非阻塞的查看视图，用于检查中间字段。
- 地图层、目标卡片、排序逻辑及操作员摘要不应依赖中间字段，除非该字段的后端契约已标记为稳定。

约束：
- 前端兼容不代表前端承诺。在契约层接受字段并不要求立即在 UI 中进行视觉消费。

### 12.3 轨迹渲染样式 — 不区分 prediction_type

`target.predicted_trajectory.points` 可能由 CV（线性）或 CTR（恒定转弯率）模型生成。后端通过 `prediction_type` 告知模型类型，但前端在渲染样式上不得根据该字段进行分支处理。

前端职责：
- 使用统一的样式渲染 `PathLayer`，不区分 `prediction_type` 取值。
- 对所有轨迹点阵列进行一致处理：按顺序连接，应用标准的透明度衰减方案。
- 当后端 CTR 模型激活时，曲线效果应通过点的空间分布自然呈现，无需前端修改渲染器。

约束：
- 不得根据 `prediction_type` 增加样式分支、图标变更或颜色覆盖。否则将导致渲染器与后端算法实现细节耦合，增加后续算法升级时的前端维护成本。

### 12.4 风险评估置信度 (risk_confidence) 消费边界

`risk_confidence` 是后端针对每个目标输出的评估置信度，这不同于顶层的全局系统可信度字段 `governance.trust_factor`。

前端职责：
- 在契约层接受并存储该字段。
- 确保任何基于此字段的消费都是次要且非阻塞的。
- 若在 UI 展示，应倾向于低置信度提示（如微调角标、弱化样式或诊断文本），仅作为辅助参考。

约束：
- 不允许 `risk_confidence` 抑制、延迟或覆盖后端的 `risk_level`。
- 不得将 `risk_confidence` 视为全局系统信任信号（该职能归属于 `governance.trust_factor`）。
- 后端降级语义：当无有效目标评估可用时，`governance.trust_factor` 可能为 `0.0`；前端必须将其解释为“当前缺乏全局可信度基础”，而非隐藏的风险等级覆盖。

## 13. 本地开发指南

```bash
cd frontend
npm install
npm run dev
```

后端必须运行在 `http://localhost:8080` 以确保 SSE 和 WebSocket 连接成功。
Whisper 服务运行在 `http://localhost:8081`（通过 Docker 由后端编排，前端不直接调用）。

## 14. 验收清单

- 前端可正常编译并启动，无编译错误。
- 成功建立至 `/api/v2/risk` 的 SSE 连接，可接收并渲染 `RISK_UPDATE` 事件。
- 成功建立至 `/api/v2/chat` 的 WebSocket 连接，`CHAT` 与 `SPEECH` 消息功能正常。
- 能够从 `/api/s57/tiles/{z}/{x}/{y}.pbf` 成功加载 S-57 切片。
- 陆地、水深及海岸线图层显示正确。
- 动态船舶叠加层在每次 `RISK_UPDATE` 时可准确更新。
- 风险叠加层（安全域、OZT、CPA 线）遵循既定的阈值触发规则。
- `EXPLANATION` 事件可正确渲染为风险解释卡片。
- 亮色/深色主题切换功能正常。
- 浏览器控制台无严重错误日志。
