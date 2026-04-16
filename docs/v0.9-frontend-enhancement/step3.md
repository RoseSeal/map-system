# Step 3：展示增强

> 文档状态：active
> 最后更新：2026-04-16
> 对齐文档：[`FRONTEND_ENHANCEMENT_PALN.md`](./FRONTEND_ENHANCEMENT_PALN.md)

## 1. 摘要

Step 3 消费 Engine 增强（v0.7/v0.8）已稳定产出的后端字段，将其映射到可见的 operator-facing 展示信号，并完善连接状态的可见性。

本步骤包含四个独立子项：

- **3A**：在目标卡片与解释卡片渲染 `encounter_type` 标签
- **3B**：以左侧色带宽度反映同级别内 `risk_score` 视觉强度
- **3C**：以透明度降低标识低 `risk_confidence` 目标
- **3D**：将单一连接状态拆分为 risk SSE / chat WS 双连接独立展示，并支持"重连中"中间态

四个子项无先后依赖，可独立交付。
注意，视觉相关改动需要考虑系统浅色、深色两种模式。

## 2. 当前状态与约束

结合现有实现，Step 3 需要了解以下代码现状：

**字段可用性**：

- `RiskAssessment` 中 `encounter_type?: EncounterType`、`risk_score?: number`、`risk_confidence?: number` 均已在 `schema.d.ts` 中定义为可选字段，后端已稳定输出。
- `TargetsPanel.tsx` 已消费 `risk_score` 用于同级别内排序（`(b.risk_score ?? 0) - (a.risk_score ?? 0)`），但不作视觉信号展示。
- `encounter_type` 与 `risk_confidence` 在前端完全未消费。

**连接状态现状**：

- `riskSseService` 的 `ConnectionStatusCallback` 签名为 `(connected: boolean, error?: string | null)`，仅区分"在线"与"离线"，不区分"重连中"。
- 当浏览器 `EventSource` 的 `onerror` 触发时，`readyState` 为 `CONNECTING`（浏览器将自动重连），此时当前实现仍以 `cb(false, ...)` 通知 store，导致 `isConnected` 变为 `false`，但实际连接尚未关闭。
- `chatWsService` 内部已有完整的 `ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting'` 状态机，并暴露 `getState()`，但没有推送回调——store 侧无法订阅状态变化。
- `useRiskStore.isConnected` 为布尔值；`useAiCenterStore` 不持有任何连接状态。
- `StatusPanel` 展示单一在线/离线信号，对应 `useRiskStore.isConnected`；chat WS 的连接状态对用户完全不可见。

**约束**：

- `risk_level` 的色彩映射、阈值定义不得变更——新增字段仅作辅助展示信号，不干扰既有风险等级语义。
- 前端不对 `risk_score` 进行时间维度平滑；如遇跳变，如实反映（后端评分已有平滑语义）。
- `risk_confidence` 降低透明度不影响卡片的可点击性与交互功能。

## 3. 范围与非目标

### 本步骤完成

- `encounter_type` 标签：在 `TargetsPanel` 目标卡片与 `RiskExplanationPanel` 解释卡片中渲染
- `risk_score` 视觉强度：在 `TargetsPanel` 目标卡片左色带中体现
- `risk_confidence` 低置信度淡化：目标卡片整体透明度降级
- risk SSE / chat WS 双连接状态独立展示：`StatusPanel` 分别渲染两条连接的三态指示器

### 本步骤不做

- 不展示 `risk_score` 数值文本
- 不修改排序逻辑（`risk_score` 同级别内排序已由现有实现保留）
- 不修改 `risk_level` 阈值或色彩语义
- 不在 `RiskExplanationPanel` 解释卡片中渲染 `risk_score` 或 `risk_confidence`
- 不为 risk SSE 的重连引入退避计时器或手动重连入口（浏览器自动重连，前端只做状态映射）
- 不新增独立的连接状态 store（在现有 `useRiskStore` 和 `useAiCenterStore` 中各自扩展字段）

## 4. 设计决策

### 4.1 encounter_type 仅在非 UNDEFINED 时渲染

`encounter_type` 为可选字段，后端在无法判断对遇类型时可能输出 `UNDEFINED` 或不携带该字段。前端采用统一过滤规则：`encounter_type` 存在且值不为 `UNDEFINED` 时才渲染标签；其余情况不占用布局空间。

文案：`HEAD_ON` → "对遇"；`OVERTAKING` → "追越"；`CROSSING` → "交叉"。

样式：与当前 `risk_level` badge 风格一致（小型圆角标签）。颜色中性，不绑定风险等级色，以免与告警色混淆。

### 4.2 risk_score 用左色带宽度表达，不展示数值

目标卡片已有固定 `borderLeftWidth: '3px'` 的色带。Step 3 将其改为基于 `risk_score` 动态计算：

- `risk_score >= 0.7`：左色带 `6px`
- `0.4 <= risk_score < 0.7`：左色带 `4px`
- `risk_score < 0.4`：左色带 `3px`（与当前默认值一致，视觉无变化）
- `risk_score` 缺失：保留原始 `3px`

色带颜色仍由 `getRiskColor(risk_level)` 决定，宽度只作同级别内强度信号，不跨等级对比。

### 4.3 risk_confidence 用透明度降级而非隐藏

低置信度目标仍保持在列表中且保持完整交互能力，仅视觉上弱化：

- `risk_confidence < 0.3`：`opacity: 0.45`
- `0.3 <= risk_confidence < 0.5`：`opacity: 0.6`
- `risk_confidence >= 0.5` 或字段缺失：正常渲染（不设置 opacity）

约束参照 `frontend-design.md §9.4`：不以此抑制、延迟或替代 `risk_level` 信号；不将其视为系统级可信度（那是 `governance.trust_factor` 的职责）。

### 4.4 双连接状态：服务层推送三态，不新增 store

Step 3 引入的展示模型需要区分三态：`connected | reconnecting | disconnected`。

`chatWsService` 已有完整状态机，只缺推送回调；`riskSseService` 的 `onerror` 需要区分"浏览器自动重连"与"连接已关闭"两种情形，可通过 `EventSource.readyState` 区分。

选择在服务层完成状态收集，通过订阅回调模式推送到 store，与现有 `onConnectionStatusChange` 模式一致，改动最小。

**risk SSE 三态映射规则**：

| 事件 | EventSource.readyState | 映射状态 |
| --- | --- | --- |
| `onopen` | `OPEN` (1) | `connected` |
| `onerror` | `CONNECTING` (0) | `reconnecting` |
| `onerror` | `CLOSED` (2) | `disconnected` |
| `disconnect()` 主动调用 | — | `disconnected` |

**chat WS 三态映射**：直接复用 `chatWsService` 内部的 `ConnectionState`，在展示层将 `connecting` 归并为 `reconnecting`（对 operator 而言两者语义相同：连接尚未建立）。

**为何不新增 `useConnectionStore`**：当前版本只需在 `StatusPanel` 展示，`useRiskStore.riskConnectionState` 和 `useAiCenterStore.chatConnectionState` 分别独立持有即可。引入独立连接聚合 store 是过度设计；如果 agent loop 未来需要全局连接状态门控，届时再重构。

## 5. 实施方案

### 5.1 子项 3A：encounter_type 标签

**涉及文件**：`TargetsPanel.tsx`、`RiskExplanationPanel.tsx`、（可选）`utils/riskDisplay.ts`

`TargetsPanel` 目标卡片第一行（ID 与 risk_level badge 行）增加 encounter_type 标签，位于 ID 与 risk_level badge 之间：

```tsx
{target.risk_assessment.encounter_type && target.risk_assessment.encounter_type !== 'UNDEFINED' && (
  <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
    {translateEncounterType(target.risk_assessment.encounter_type)}
  </span>
)}
```

新增辅助函数：

```ts
function translateEncounterType(type: string): string {
  switch (type) {
    case 'HEAD_ON': return '对遇';
    case 'OVERTAKING': return '追越';
    case 'CROSSING': return '交叉';
    default: return type;
  }
}
```

`RiskExplanationPanel` 解释卡片头部（展示 target ID 与 risk_level 的区域）增加同样的标签，复用相同的 `translateEncounterType` 函数。由于两个组件都需要该函数，应提取到 `utils/riskDisplay.ts`，避免重复定义。

### 5.2 子项 3B：risk_score 色带宽度

**涉及文件**：`TargetsPanel.tsx`、（可选）`utils/riskDisplay.ts`

当前代码：

```tsx
style={{ borderLeftColor: riskHex, borderLeftWidth: '3px' }}
```

改为：

```tsx
style={{ borderLeftColor: riskHex, borderLeftWidth: `${getRiskScoreBorderWidth(target.risk_assessment.risk_score)}px` }}
```

新增辅助函数：

```ts
function getRiskScoreBorderWidth(score: number | undefined): number {
  if (score === undefined) return 3;
  if (score >= 0.7) return 6;
  if (score >= 0.4) return 4;
  return 3;
}
```

不展示 `risk_score` 数值，不改变排序逻辑。

### 5.3 子项 3C：risk_confidence 透明度降级

**涉及文件**：`TargetsPanel.tsx`、（可选）`utils/riskDisplay.ts`

在目标卡片容器的 `style` 中叠加 `opacity`：

```tsx
style={{
  borderLeftColor: riskHex,
  borderLeftWidth: `${getRiskScoreBorderWidth(target.risk_assessment.risk_score)}px`,
  opacity: getRiskConfidenceOpacity(target.risk_assessment.risk_confidence),
}}
```

新增辅助函数：

```ts
function getRiskConfidenceOpacity(confidence: number | undefined): number | undefined {
  if (confidence === undefined || confidence >= 0.5) return undefined;
  if (confidence < 0.3) return 0.45;
  return 0.6;
}
```

`opacity: undefined` 不输出 inline style 属性，保持正常渲染。透明度不影响 `cursor: pointer` 或点击事件。

### 5.4 子项 3D：双连接状态展示

#### 5.4.1 类型定义

新增前端展示用连接状态枚举（新建 `frontend/src/types/connection.ts`，或并入现有类型文件）：

```ts
export type DisplayConnectionState = 'connected' | 'reconnecting' | 'disconnected';
```

不复用 `chatWsService` 内部的 `ConnectionState`（其中含 `connecting`）；展示层将 `connecting` 与 `reconnecting` 统一呈现为"重连中"。

#### 5.4.2 riskSseService 改动

`ConnectionStatusCallback` 签名从布尔值扩展为三态：

```ts
// 修改前
type ConnectionStatusCallback = (connected: boolean, error?: string | null) => void;

// 修改后
type ConnectionStatusCallback = (state: DisplayConnectionState, error?: string | null) => void;
```

`onerror` 处理逻辑改为检查 `readyState`：

```ts
eventSource.onerror = () => {
  const connState: DisplayConnectionState = eventSource.readyState === EventSource.CLOSED
    ? 'disconnected'
    : 'reconnecting';
  const error = connState === 'disconnected' ? '风险态势连接中断' : null;
  this.connectionStatusCallbacks.forEach((cb) => cb(connState, error));
};
```

`onopen` 更新为：

```ts
eventSource.onopen = () => {
  this.connectionStatusCallbacks.forEach((cb) => cb('connected', null));
};
```

`disconnect()` 主动断开时：

```ts
this.connectionStatusCallbacks.forEach((cb) => cb('disconnected', null));
```

#### 5.4.3 chatWsService 改动

新增连接状态推送回调：

```ts
private connectionStateCallbacks = new Set<(state: DisplayConnectionState) => void>();

onConnectionStateChange(cb: (state: DisplayConnectionState) => void): () => void {
  this.connectionStateCallbacks.add(cb);
  return () => this.connectionStateCallbacks.delete(cb);
}

private notifyConnectionState(): void {
  const display: DisplayConnectionState =
    this.state === 'connected' ? 'connected'
    : this.state === 'disconnected' ? 'disconnected'
    : 'reconnecting'; // covers 'connecting' and 'reconnecting'
  this.connectionStateCallbacks.forEach((cb) => cb(display));
}
```

在内部状态切换的每个位置，在状态赋值后调用 `this.notifyConnectionState()`，共五处：

| 位置 | 状态变更 | 映射展示态 |
| --- | --- | --- |
| `connect()` 中 `this.state = ...` 赋值后 | `'connecting'` 或 `'reconnecting'` | `reconnecting` |
| `socket.onopen` 中 `this.state = 'connected'` 后 | `'connected'` | `connected` |
| `socket.onclose` 的手动断开分支 `this.state = 'disconnected'` 后 | `'disconnected'` | `disconnected` |
| `scheduleReconnect()` 中 `this.state = 'reconnecting'` 后 | `'reconnecting'` | `reconnecting` |
| `disconnect()` 中 `this.state = 'disconnected'` 后 | `'disconnected'` | `disconnected` |

`connect()` 必须列入是因为首次建连时 `this.state` 会被设为 `'connecting'`；若此处不通知，`StatusPanel` 在挂载后到 `onopen` 触发前，"AI 助手"指示器始终停留在初始的 `disconnected`（"离线"红点），与文档目标不符。展示层将 `connecting` 与 `reconnecting` 统一映射为"重连中"，因此两者的 operator-facing 展示一致。

#### 5.4.4 useRiskStore 改动

将 `isConnected: boolean` 替换为 `riskConnectionState: DisplayConnectionState`，更新对应 action 与 selector：

```ts
// initialState 中
riskConnectionState: 'disconnected' as DisplayConnectionState,
// connectionError 保留，用于错误文案展示

// action（替换 setConnectionStatus）
setRiskConnectionState: (state: DisplayConnectionState, error: string | null = null) => {
  set({
    riskConnectionState: state,
    connectionError: state === 'connected' ? null : error,
  });
},
```

`setRiskUpdate` 中原有 `isConnected: true` 直接赋值，改为 `riskConnectionState: 'connected'`。

更新 selector：

```ts
export const selectRiskConnectionState = (state: RiskState) => state.riskConnectionState;
```

同步更新 `useRiskStore` 初始化订阅中的回调调用：

```ts
riskSseService.onConnectionStatusChange((state, error) => {
  useRiskStore.getState().setRiskConnectionState(state, error);
});
```

#### 5.4.5 useAiCenterStore 改动

新增 `chatConnectionState: DisplayConnectionState` 字段，并在模块初始化时订阅 `chatWsService.onConnectionStateChange`：

```ts
// initialState 中
chatConnectionState: 'disconnected' as DisplayConnectionState,

// action
setChatConnectionState: (state: DisplayConnectionState) => {
  set({ chatConnectionState: state });
},
```

初始化订阅（与现有回调订阅并列）：

```ts
chatWsService.onConnectionStateChange((state) => {
  useAiCenterStore.getState().setChatConnectionState(state);
});
```

新增 selector：

```ts
export const selectChatConnectionState = (state: AiCenterState) => state.chatConnectionState;
```

#### 5.4.6 StatusPanel 改动

替换现有单一连接指示器，分别渲染"实时消息"（risk SSE）和"AI 助手"（chat WS）两行。在 `StatusPanel.tsx` 内定义本地辅助组件：

```tsx
function ConnectionIndicator({ label, state }: { label: string; state: DisplayConnectionState }) {
  const dotColor = state === 'connected' ? '#4ade80' : state === 'reconnecting' ? '#facc15' : '#ef4444';
  return (
    <div className="flex items-center gap-1.5">
      <span
        className={`w-1.5 h-1.5 rounded-full ${state === 'reconnecting' ? 'animate-spin' : 'animate-pulse'}`}
        style={{ backgroundColor: dotColor }}
      />
      <span className="text-[10px] uppercase font-mono text-slate-500 dark:text-slate-400">{label}</span>
    </div>
  );
}
```

状态来源：

```tsx
const riskConnectionState = useRiskStore(selectRiskConnectionState);
const chatConnectionState = useAiCenterStore(selectChatConnectionState);
```

### 5.5 辅助函数组织

`translateEncounterType`、`getRiskScoreBorderWidth`、`getRiskConfidenceOpacity` 均属于"风险字段展示转换"类别。由于 `translateEncounterType` 需要在 `TargetsPanel` 与 `RiskExplanationPanel` 中共用，建议统一提取到 `frontend/src/utils/riskDisplay.ts`（新建）。`getRiskScoreBorderWidth` 和 `getRiskConfidenceOpacity` 仅在 `TargetsPanel` 中使用，可就近放置于该文件末尾，无需强制提取。

## 6. 影响文件

| 文件 | 改动类型 |
| --- | --- |
| `frontend/src/types/connection.ts` | 新增（`DisplayConnectionState` 类型） |
| `frontend/src/utils/riskDisplay.ts` | 新增（`translateEncounterType` 及可选的其他辅助函数） |
| `frontend/src/services/riskSseService.ts` | 修改（连接回调三态扩展） |
| `frontend/src/services/chatWsService.ts` | 修改（新增 `onConnectionStateChange` 推送回调） |
| `frontend/src/store/useRiskStore.ts` | 修改（`isConnected` → `riskConnectionState`，更新 selector 与 action） |
| `frontend/src/store/useAiCenterStore.ts` | 修改（新增 `chatConnectionState` 字段与初始化订阅） |
| `frontend/src/store/index.ts` | 修改（删除 `selectIsConnected` 导出，新增 `selectRiskConnectionState`；新增 `selectChatConnectionState`） |
| `frontend/src/components/Dashboard/TargetsPanel.tsx` | 修改（3A + 3B + 3C） |
| `frontend/src/components/Dashboard/RiskExplanationPanel.tsx` | 修改（3A） |
| `frontend/src/components/Dashboard/StatusPanel.tsx` | 修改（3D） |

**测试文件影响**：

- `useRiskStore.test.ts`：`isConnected` 相关断言更新为 `riskConnectionState`
- `riskSseService.test.ts`：`onerror` 与 `disconnect` 回调断言更新为三态
- `chatWsService.test.ts`：新增 `onConnectionStateChange` 回调触发的测试用例
- `useAiCenterStore.test.ts`：新增 `chatConnectionState` 订阅联动的测试用例
- `StatusPanel.test.tsx`：更新或补充双连接状态的最小渲染测试

## 7. 验证标准

Step 3 完成后，应满足以下条件：

1. `TargetsPanel` 目标卡片中，`encounter_type` 为 `HEAD_ON` / `OVERTAKING` / `CROSSING` 时显示对应中文标签；`UNDEFINED` 或字段缺失时不渲染标签
2. `RiskExplanationPanel` 解释卡片中，同一目标的 `encounter_type` 标签与 `TargetsPanel` 文案一致
3. 同一 `risk_level` 下，`risk_score >= 0.7` 的目标卡片左色带明显宽于 `risk_score < 0.4` 的目标；`risk_score` 缺失时退化为默认 `3px`
4. `risk_confidence < 0.3` 的目标卡片明显淡化；`0.3 <= risk_confidence < 0.5` 的卡片中度淡化；淡化后卡片仍可正常点击
5. `StatusPanel` 展示两条独立的连接状态行（"实时消息"与"AI 助手"）
6. risk SSE `onerror` 触发时（浏览器处于自动重连阶段，`readyState === CONNECTING`），"实时消息"状态显示为"重连中"，而非"离线"
7. chat WS `scheduleReconnect` 期间，"AI 助手"状态显示为"重连中"
8. 连接恢复时两条指示器均回到"在线"态
9. `npm test` 全通过，无因本步骤改动而引入的新失败

## 8. 风险与实施注意事项

- **`isConnected` 引用清查**：`useRiskStore.isConnected` 被 `StatusPanel`、`setRiskUpdate` 内部直接赋值、测试文件多处消费。重命名前需全局搜索确认无遗漏，否则 TypeScript 编译会报错，但运行时仍有隐患（如测试中 mock 签名不一致）。
- **riskSseService `onerror` 中 `readyState` 访问**：`this.eventSource` 在 `onerror` 触发时仍不为 `null`（浏览器自动重连不会销毁 `EventSource` 对象），可安全访问。使用 `EventSource.CLOSED`（值为 `2`）常量而非魔法数字。
- **chatWsService `notifyConnectionState()` 调用位置**：需要覆盖四处状态切换点（`onopen` → `connected`；手动 `disconnect()` → `disconnected`；`scheduleReconnect()` → `reconnecting`；`onclose` 的手动断开分支 → `disconnected`）。遗漏任何一处将导致 UI 状态落后于实际连接状态。
- **riskSseService 回调签名是破坏性变更**：`ConnectionStatusCallback` 从 `(boolean, ...)` 改为 `(DisplayConnectionState, ...)` 后，Step 1 中 `riskSseService.test.ts` 的相关断言必须同步更新，否则测试将失败。建议在同一 PR 内完成服务层与测试文件的改动。
- **useAiCenterStore 初始状态与实际状态同步**：若 `App.tsx` 在 store 初始化后立即触发 WS 连接，`connecting` → `connected` 的状态通知会正常推送。测试环境下不调用 `connect()` 时，`chatConnectionState` 保持 `disconnected`，这是预期行为。
