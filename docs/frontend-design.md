# 前端设计参考文档

> 文档状态：current
> 最后更新：2026-04-16
> 基线来源：`docs/ARCHITECTURE.md`、`docs/EVENT_SCHEMA.md`

本文档描述当前前端运行模型、组件职责、协议消费边界与本地联调约束。

## 1. 项目定位

前端是面向海上态势感知与碰撞风险预警的 2.5D 控制台界面，负责：

- 渲染 S-57 海图与船舶态势图层
- 消费风险 SSE 与聊天 WebSocket 双实时链路
- 展示风险解释卡片、聊天消息与语音交互状态
- 承接选中目标上下文、主题切换与语音播报设置

主要前端路径：`frontend/`

## 2. 架构模型

系统遵循“重后端、轻前端”模式：

- 后端负责 AIS 接入、风险计算、LLM 编排、Whisper 转录与实时推送
- 前端负责协议消费、地图渲染、状态展示与交互编排

传输协议：

- 风险流：SSE `/api/v2/risk`，事件类型为 `RISK_UPDATE`、`EXPLANATION`、`ERROR`
- 聊天流：WebSocket `/api/v2/chat`，上行为 `CHAT`、`SPEECH`、`CLEAR_HISTORY`，下行为 `CHAT_REPLY`、`SPEECH_TRANSCRIPT`、`ERROR`、`CLEAR_HISTORY_ACK`

完整字段定义以 `docs/EVENT_SCHEMA.md` 为准。

## 3. 前端技术栈

- React 18
- Vite 5
- TypeScript
- Zustand
- MapLibre GL JS 4+
- Deck.gl 9+
- Tailwind CSS 3+
- Vitest + Testing Library

## 4. 目录结构

```text
frontend/
  src/
    components/
      Dashboard/        ← 左侧 HUD、右侧 AI 工作区、聊天消息与输入组件
      Map/              ← 地图渲染核心
    config/             ← 前端常量与图层配置
    hooks/              ← 语音采集与播报编排
    services/           ← risk SSE、chat WS、TTS、录音、S-57 API
    store/              ← useRiskStore / useAiCenterStore / useThemeStore
    test/               ← 测试初始化与夹具
    types/              ← 协议与本地 UI 类型
    utils/              ← 风险展示与 LLM 事件归一化辅助函数
```

## 5. 组件职责地图

| 组件 | 职责 | 不属于其职责 |
| --- | --- | --- |
| `MapContainer` | 挂载 MapLibre 与 Deck.gl 图层，响应目标选择与地图交互 | 不维护聊天状态，不负责协议解析 |
| `StatusPanel` | 展示本船动力学、平台健康、全局 trust warning、双连接状态 | 不承载主题切换与语音播报设置 |
| `TargetsPanel` | 展示目标列表、风险强度、会遇标签、低置信度提示与目标选择 | 不渲染聊天消息，不维护解释缓存 |
| `RiskExplanationPanel` | 右侧 AI 工作区壳层；整合风险解释卡片区、聊天区、设置折叠区与显隐交互 | 不直接计算业务状态，不直接调用后端 SDK |
| `ChatMessageList` | 渲染消息列表、错误态、重试入口与“编辑最后一条用户消息”内联编辑 UI | 不持有消息源状态，不负责发送协议 |
| `ChatComposer` | 文本输入、语音入口、目标 Chip、录音取消入口与发送状态反馈 | 不直接维护录音实现，不直接操作 WebSocket |

## 6. 状态模型

### 6.1 语音采集状态

`voiceCaptureState` 由 `useAiCenterStore` 持有，流转如下：

```text
idle -> recording -> transcribing -> sent -> idle
                 \-> error -------> idle
recording --cancel--> idle
```

约束：

- `cancel` 仅覆盖本地录音阶段，即 `recording -> idle`
- `transcribing` 表示音频已发送至后端，当前版本不支持取消
- `sent` 表示转录结果已回填或语音请求已成功发出

### 6.2 聊天消息生命周期

文本消息：

```text
user text -> pending -> replied
                    \-> error
```

语音直发：

```text
speech direct -> pending -> sent -> replied
                         \-> error
```

语音预览：

```text
speech preview -> transcribing -> transcript writeback -> input pending user confirmation
```

编辑最后一条文本消息：

```text
latest CHAT turn -> editing -> confirm(edit_last_user_message=true)
                               -> success: replace last USER / ASSISTANT turn
                               -> error: restore editing state and keep draft
```

## 7. 连接管理

前端维护两条独立连接状态，并在 `StatusPanel` 中并排展示：

- `riskConnectionState`：由 `useRiskStore` 持有，对应 risk SSE
- `chatConnectionState`：由 `useAiCenterStore` 持有，对应 chat WebSocket

展示语义统一为三态：

- `connected`
- `reconnecting`
- `disconnected`

说明：

- risk SSE 在浏览器自动重连期间展示为 `reconnecting`
- chat WebSocket 的 `connecting` 与 `reconnecting` 在 UI 上统一收敛为 `reconnecting`

## 8. 数据模型与协议消费

主要渲染输入是 `RISK_UPDATE` 载荷中的 `RiskObject`：

- `own_ship`：位置、动力学、平台健康、预测轨迹、安全域
- `targets[*]`：`risk_level`、`cpa_metrics`、`graphic_cpa_line`、`ozt_sector`、`encounter_type`、`risk_score`、`risk_confidence`、`predicted_trajectory`
- `governance.trust_factor`：本船状态置信度告警来源；缺失时按 `0.0` 展示低可信告警

`EXPLANATION` 事件与 `RISK_UPDATE` 解耦，通过 risk SSE 单独下发并按 `target_id` 写入前端解释缓存。

聊天上行中的 `selected_target_ids` 由前端透传至后端；后端会为匹配目标注入：

- 当前风险详情
- 最近有效的 LLM 解释文本

前提是该目标当前仍被追踪且风险等级非 `SAFE`。

## 9. 当前界面组织与渲染规则

### 9.1 布局骨架

当前主界面分为三块：

- 左侧态势监控信息簇：`StatusPanel` + `TargetsPanel`
- 中央海图视图：`MapContainer`
- 右侧 AI 工作区：`RiskExplanationPanel`

其中右侧工作区为全高侧栏，内部再分为：

- 顶部标题与设置折叠区
- 中部风险解释卡片区
- 底部聊天消息与输入区

### 9.2 风险目标展示规则

`TargetsPanel` 当前已消费以下后端字段：

- `encounter_type`：渲染为标签
- `risk_score`：映射为左侧色带宽度
- `risk_confidence`：映射为卡片透明度；低于阈值时额外显示低置信度提示框

排序规则：

1. 先按 `risk_level`
2. 同级别内再按 `risk_score`

### 9.3 风险解释区规则

- 仅展示 `CAUTION / WARNING / ALARM` 目标的解释卡片
- 点击解释卡片会同步选中目标
- 右侧工作区在 hover、聊天聚焦或语音录制/转录期间保持展开

### 9.4 聊天区规则

- 选中目标会以 Chip 形式显示在 `ChatComposer` 中
- 若目标在后续 `RISK_UPDATE` 中消失，会显示一次性掉线提示并自动移除对应 Chip
- `recording` 状态下，输入区提供“停止并直发”“停止并预览”“取消”三种操作
- 仅最后一条 `request_type === 'CHAT'` 的 user 消息允许进入编辑态
- 预留“处理中”占位，但当前版本不支持真正中断 LLM 回复

### 9.5 状态面板规则

`StatusPanel` 当前只承担运行态展示职责：

- 本船 `SOG / COG / HDG`
- 本船经纬度
- 平台健康状态
- trust warning
- `STREAM` 与 `AI-WS` 双连接指示

## 10. 主题与语音播报

- 主题状态由 `useThemeStore` 在前端本地维护
- 语音播报基于浏览器 `SpeechSynthesis`
- 当前设置入口位于 `RiskExplanationPanel` 标题栏内的折叠设置区
- 设置入口不依赖连接在线状态

## 11. 语音交互

- ASR：由 `MediaRecorder` 采集音频，使用 `SPEECH` WebSocket 消息上传，后端统一编排 `whisper.cpp`
- TTS：由浏览器原生 `SpeechSynthesis` 播放 AI 回复
- 语音模式：
  - `direct`：转录后直接进入 LLM 回复链路
  - `preview`：仅转录并写回输入框，由用户决定是否发送

## 12. 前端消费边界

### 12.1 `risk_score`、`encounter_type` 与 `risk_confidence`

- `risk_score` 是次级视觉强度信号，不改变 `risk_level` 语义
- `encounter_type` 是辅助标签，不独立驱动告警
- `risk_confidence` 仅用于弱化和提示，不得覆盖或抑制 `risk_level`

### 12.2 `selected_target_ids`

- 该字段只是目标选择语义，不额外创建新的前端解释选择协议
- 前端只负责透传目标 ID 列表，不在客户端拼接风险上下文
- 后端注入的解释文本属于聊天上下文，不改变 `EXPLANATION` SSE 事件本身

### 12.3 `edit_last_user_message`

- 当前只支持编辑最后一条文本用户消息
- 不支持编辑任意历史轮次
- 不支持前端本地先删后发；必须等待后端成功后整体替换最后一轮

### 12.4 `prediction_type`

- 前端继续统一渲染轨迹路径
- 不根据 `prediction_type` 做样式分支
- 模型差异通过轨迹点分布自然体现

## 13. 本地开发指南

```bash
cd frontend
npm install
npm run dev
```

联调约束：

- 后端默认运行于 `http://localhost:8080`
- Whisper 服务默认运行于 `http://localhost:8081`

## 14. 当前验收检查项

- `/api/v2/risk` SSE 能建立连接并区分 `connected / reconnecting / disconnected`
- `/api/v2/chat` WebSocket 能建立连接并区分 `connected / reconnecting / disconnected`
- `RISK_UPDATE` 可驱动地图与左侧目标列表同步更新
- `EXPLANATION` 可渲染到右侧风险解释区
- `recording` 状态下存在可用的“取消”入口，且取消后不发送请求
- 最后一条文本用户消息可进入编辑态，并以 `edit_last_user_message=true` 触发重答
- 选中目标后发起聊天，后端可感知选中目标及其最近有效解释上下文
