# v1.0 Frontend Visual Upgrade Reference

> 文档状态：reference
> 最后更新：2026-04-18
> 用途：记录 2026-04-18 提供的前端视觉升级补丁在仓库中的采纳范围、裁剪项与后续实现所需信息。
> 非目标：本文不是当前运行真值；不替代 [`../frontend-design.md`](../frontend-design.md)、[`./weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md)、[`./agent/AGENT_LOOP_PLAN.md`](./agent/AGENT_LOOP_PLAN.md)。

## 1. 来源与落地边界

本参考稿对应工作区根目录收到的 `map-system.zip` 视觉升级补丁。该补丁原始建议替换：

- `frontend/src/index.css`
- `frontend/tailwind.config.js`
- `frontend/src/App.tsx`
- `frontend/src/components/Dashboard/CpaArc.tsx`
- `frontend/src/components/Dashboard/StatusPanel.tsx`
- `frontend/src/components/Dashboard/TargetsPanel.tsx`
- `frontend/src/components/Dashboard/RiskExplanationPanel.tsx`

本次实际落地遵循“视觉升级优先，行为契约不静默漂移”的原则：

- 采纳 Vision Pro 风格玻璃面板、色彩 token、字体、弧形 `CpaArc`、左侧固定高度布局、右侧点击展开 AI 面板。
- 保留现有 `risk` SSE、`chat` WebSocket、语音、目标选择、解释卡片、聊天编辑、会话重置等既有行为链路。
- 去除补丁中会误导用户的纯占位内容，尤其是不带真实能力来源的 agent 工具调用区与硬编码模型表述。

## 2. 本次采纳项

### 2.1 视觉 token 与布局

- `index.css` 引入统一 `oklch` 风险色、`ink` 中性色阶、`glass-vision` / `glass-vision-dark` 玻璃样式、滚动条与基础动画。
- `tailwind.config.js` 补充字体族、风险色 fallback、圆角和动画 token。
- `App.tsx` 改为左侧固定高度信息栈 + 右侧 AI 工作区抽屉，不再单独渲染右下角图例。
- `frontend/index.html` 增加 Google Fonts 引用；字体不可用时仍按系统字体回退。

### 2.2 组件级采纳

- `StatusPanel`：采纳大字号 SOG 展示、紧凑双列、风险图例条、天气条占位。
- `TargetsPanel`：采纳顶部风险分布条、卡片式目标列表、`CpaArc` 弧形指标、柔和选中态。
- `RiskExplanationPanel`：采纳点击展开的侧边柄、玻璃容器、解释卡片新样式、拖动分隔条。

## 3. 本次明确裁剪项

### 3.1 Agent 工具调用区未落地

补丁中 `RiskExplanationPanel` 底部原包含：

- `Agent 工具调用`
- `EvaluateManeuverTool`
- `GetWeatherContextTool`
- `QueryColregsGraphTool`

这些内容在当前前端与协议中均无真实事件源，不存在“最近一次工具调用结果”或“正在执行中的工具轨迹”状态。若保留该区块，会把纯视觉占位伪装成已接线能力，因此本次移除。

后续若要重新引入，至少需要以下真实输入契约之一：

- advisory 或 chat 通道显式下发 `tool_calls` / `evidence_trace`
- AI 工作区 store 持有工具调用的生命周期状态
- 前端能区分“模型内部推理”与“系统已执行的外部工具”

具体实现边界见 [`./agent/AGENT_LOOP_PLAN.md`](./agent/AGENT_LOOP_PLAN.md) 第 2、3 节。

### 3.2 硬编码 provider / model 文案未落地

补丁标题栏中原出现固定 provider 文案。当前系统可在 `gemini` 与 `zhipu` 间切换，且前端头部没有稳定的当前模型真值来源，因此本次只展示通用 `LLM` 标识，不宣称具体模型。

## 4. 天气条占位的后续接线信息（历史参考）

### 4.1 当前保留方式

`StatusPanel` 底部天气条在视觉补丁初次落地时曾保留为纯展示占位，用于预留 `v1.0 weather` 视觉入口。该段描述的是当时的占位状态，而不是当前运行真值。

### 4.2 为什么暂不接真实数据

在该参考稿对应的时间点，前端类型真值 [`../../frontend/src/types/schema.d.ts`](../../frontend/src/types/schema.d.ts) 中，`EnvironmentContext` 仅定义：

- `safety_contour_val`
- `active_alerts`

当时仓库尚不存在稳定的 `environment_context.weather` 子结构。因此，在 weather track 真正扩展协议前，天气条只能作为占位，不应伪装为实时天气。

weather Step 1 完成后，该占位信息已被真实接线替代；当前天气展示真值以 [`./weather/step1.md`](./weather/step1.md) 与 [`./weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md) 为准。

### 4.3 后续实现最低所需字段

天气条要从占位升级为真实消费，最低需要后端稳定下发以下结构：

```json
{
  "environment_context": {
    "weather": {
      "weather_code": "CLEAR|FOG|RAIN|SNOW|STORM",
      "visibility_nm": 0.8,
      "wind": {
        "speed_kn": 12.0,
        "direction_from_deg": 225
      },
      "surface_current": {
        "speed_kn": 1.4,
        "set_deg": 90
      },
      "sea_state": 4,
      "updated_at": "2026-04-17T10:22:15Z"
    }
  }
}
```

字段来源与语义约束以 [`./weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md) §3.2、§3.3 为准。

### 4.4 前端接线建议

后续实现时，建议按以下顺序推进：

1. 扩展 [`../../frontend/src/types/schema.d.ts`](../../frontend/src/types/schema.d.ts) 的 `EnvironmentContext`。
2. 让 [`../../frontend/src/store/useRiskStore.ts`](../../frontend/src/store/useRiskStore.ts) 保持原样透传 `environment_context.weather`，不要在 store 层做展示语义映射。
3. 在 [`../../frontend/src/components/Dashboard/StatusPanel.tsx`](../../frontend/src/components/Dashboard/StatusPanel.tsx) 内新增纯展示映射函数，把 `weather_code` 转成图标、标签和附加说明。
4. 若天气修正进入风险引擎，应另外给 `StatusPanel` 增加“气象修正已启用”标记，不与天气条本体混用。

## 5. 后续如需恢复工具轨迹区，前端需要的最小数据契约

若 agent loop 进入前端展示范围，建议不要直接复活原补丁中的静态列表，而是要求后端提供真实结构，至少包括：

- `call_id`：一次工具调用的唯一标识
- `tool_name`：工具名
- `status`：`RUNNING | SUCCEEDED | FAILED`
- `started_at` / `finished_at`
- `args_summary`：可展示的精简参数摘要
- `result_summary`：可展示的精简返回摘要
- `source_message_event_id` 或 `advisory_id`：归属哪条聊天消息或 advisory

如果缺少这些字段，前端最多只能展示 advisory 的 `evidence_items`，不应伪造“工具执行轨迹”。

当前该事项未挂入 `v1.0` 现有 step 链，已回收到 [`../TODO.md`](../TODO.md)；本节只保留后续接线所需的最小契约，不再把工具轨迹区视为 `v1.0` 目录内的隐含待办。

## 6. 与现有 v1.0 规划文档的关系

- 天气条在本参考稿对应阶段只是 weather track 的视觉占位；该状态已随着 weather Step 1 完成而失效。当前 weather 真值见 [`./weather/step1.md`](./weather/step1.md) 与 [`./weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md)。
- 被移除的 agent 工具区只是一种视觉提案，不代表 agent loop、tool registry、`ADVISORY` 事件或证据链已经进入前端稳定消费，见 [`./agent/AGENT_LOOP_PLAN.md`](./agent/AGENT_LOOP_PLAN.md)。
- 若后续希望恢复工具轨迹区，应先在 [`../TODO.md`](../TODO.md) 或新的 milestone / step 文档中挂入 owner，再开始实现。
- 本文只保存“当时补丁想表达什么，以及后续真实实现至少需要什么”，防止该信息随着 UI 落地裁剪而丢失。
