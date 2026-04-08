# Step 3.2 执行方案：选中目标定向注入 + 前后端协议扩展

## Context

Step 3.1 已完成后端内部默认摘要注入。Step 3.2 补齐剩余能力：用户选中一或多个目标船后提问（文本/语音），请求携带 `selected_target_ids`，后端注入选中目标的完整数据。同时将前端目标选择从单选升级为多选。

## 协议版本

`selected_target_ids` 为可选字段，向后兼容，仍为 v2。EVENT_SCHEMA.md 变更记录添加一行补充说明。

---

## 一、协议扩展

### 1.1 CHAT payload

**后端** `backend/.../dto/websocket/ChatRequestPayload.java`:
- 新增 `@JsonProperty("selected_target_ids") List<String> selectedTargetIds`

**前端** `frontend/src/types/schema.d.ts`:
- `ChatRequestPayload` 新增 `selected_target_ids?: string[]`

### 1.2 SPEECH payload

**后端** `backend/.../dto/websocket/SpeechRequestPayload.java`:
- 新增 `@JsonProperty("selected_target_ids") List<String> selectedTargetIds`

**前端** `frontend/src/types/schema.d.ts`:
- `SpeechRequestPayload` 新增 `selected_target_ids?: string[]`

### 1.3 EVENT_SCHEMA.md

- 变更记录新增一行（日期 + "CHAT/SPEECH payload 新增可选字段 selected_target_ids"）
- CHAT payload 章节新增字段行：`selected_target_ids | string[] | 可选 | 用户选中的目标船 ID 列表`
- SPEECH payload 章节新增字段行：同上

---

## 二、后端 — 选中目标详情格式化

**文件**: `backend/.../service/llm/RiskContextFormatter.java`

新增方法 `formatSelectedTargets(LlmRiskContext context, List<String> targetIds, Instant updatedAt) → String`

与现有 `formatSummary` 的区别：

| 维度 | formatSummary（默认摘要） | formatSelectedTargets（定向详情） |
|------|--------------------------|----------------------------------|
| 过滤 | 排除 SAFE | 不过滤（用户显式选中即展示） |
| 截断 | Top-N | 不截断 |
| 排序 | 风险等级降序 → 现距升序 | 按 targetIds 输入顺序 |
| 字段 | 风险等级、现距、DCPA、TCPA、是否接近 | **完整数据**：风险等级、现距、DCPA、TCPA、是否接近、经纬度、航速、航向、规则说明 |
| 统计 | 末尾输出总数/展示数/未展示数 | 末尾输出"共选中 N 艘，匹配 M 艘" |
| 无匹配 | 无可展示目标返回 null | 无匹配返回 null |

新增私有方法 `appendTargetDetail(StringBuilder, LlmRiskTargetContext)` 输出完整字段行。格式示例：
```
目标船 413999001: 风险等级 WARNING, 现距 0.80海里, DCPA 0.30海里, TCPA 120秒, 正在接近, 位置: (114.3100, 30.5100), 航速: 8.0节, 航向: 45.0°, 说明: 目标船正从右舷接近
```

复用已有 `appendOwnShip`、`defaultText`、`formatDecimal` 等私有方法。

---

## 三、后端 — LlmChatService 策略分支

**文件**: `backend/.../service/llm/LlmChatService.java`

修改 `buildMessages(ChatRequestPayload request)`:

```
selectedTargetIds = request.getSelectedTargetIds()
context = riskContextHolder.getCurrent()
updatedAt = riskContextHolder.getUpdatedAt()

if selectedTargetIds 非 null 且非空:
    text = formatter.formatSelectedTargets(context, selectedTargetIds, updatedAt)
    if text 为 null → 回退到 formatter.formatSummary(context, updatedAt)
else:
    text = formatter.formatSummary(context, updatedAt)

消息组装逻辑不变（有上下文则拼接，无则仅用户问题）
```

---

## 四、后端 — 语音链路透传

**文件**: `backend/.../service/llm/VoiceChatService.java`

修改 `buildTextRequestFromTranscript(SpeechRequestPayload, String)`:
- 将 `request.getSelectedTargetIds()` 透传到构建的 `ChatRequestPayload`

```java
return ChatRequestPayload.builder()
        .conversationId(request.getConversationId())
        .eventId(request.getEventId())
        .content(content)
        .selectedTargetIds(request.getSelectedTargetIds())  // 新增
        .build();
```

---

## 五、前端 — 目标多选改造

### 5.1 useRiskStore 数据结构

**文件**: `frontend/src/store/useRiskStore.ts`

```
- selectedTargetId: string | null
+ selectedTargetIds: string[]
```

`selectTarget(targetId)` 改为 toggle 语义：
- 已在列表中 → 移除
- 不在列表中 → 追加
- `null` → 清空全部

新增 `deselectTarget(targetId: string)` 方法，供 chip 的 x 按钮单独移除。

选择器变更：
- `selectSelectedTargetId` → 移除
- 新增 `selectSelectedTargetIds: (state) => state.selectedTargetIds`
- `selectSelectedTarget` → `selectSelectedTargets`：返回 `RiskTarget[]`
- `selectSelectedTargetExplanation` → `selectSelectedTargetExplanations`：返回 `Record<string, ExplanationPayload>`（仅包含选中且有解释的项）

### 5.2 store/index.ts 导出更新

同步更新选择器导出名。

### 5.3 TargetsPanel

**文件**: `frontend/src/components/Dashboard/TargetsPanel.tsx`

- `isSelected` 判断从 `selectedTarget?.id === target.id` 改为 `selectedTargetIds.includes(target.id)`
- 点击行为保持 toggle：`selectTarget(target.id)`
- `requestAiCenterOpen` 逻辑：点击选中且有解释时仍触发

### 5.4 MapContainer

**文件**: `frontend/src/components/Map/MapContainer.tsx`

- `selectedTargetId` → `selectedTargetIds`
- 高亮图层从单点 `ScatterplotLayer` 改为多点数据：
  ```
  data: selectedTargets.map(t => ({ position: [t.position.lon, t.position.lat] }))
  ```
- 点击 target icon 仍走 `selectTarget(object.id)` toggle

### 5.5 RiskExplanationPanel

**文件**: `frontend/src/components/Dashboard/RiskExplanationPanel.tsx`

- `selectedTarget` → `selectedTargetIds` 用于判断 `isSelected`
- `isSelected` 从 `selectedTarget?.id === target.id` 改为 `selectedTargetIds.includes(target.id)`
- 其他逻辑不变

### 5.6 CompassOverlay

不使用 `selectedTargetId`，无需修改。

---

## 六、前端 — ChatComposer 选中目标 chip 指示器

**文件**: `frontend/src/components/Dashboard/ChatComposer.tsx`

在 textarea 上方（或 textarea 与按钮区之间）添加 chip 区域：
- 从 `useRiskStore` 读取 `selectedTargetIds` 和 `targets`
- 为每个选中目标渲染一个 chip：显示目标 ID + 风险等级色标 + x 关闭按钮
- 点击 x 调用 `deselectTarget(targetId)` 移除单个
- 无选中目标时不渲染该区域

ChatComposer 接口扩展：
- 新增 props：`selectedTargets: Array<{ id: string; riskLevel: string }>`, `onDeselectTarget: (id: string) => void`
- 由 `RiskExplanationPanel` 传入

---

## 七、前端 — 发送时携带选中目标

### 7.1 sendTextMessage

**文件**: `frontend/src/store/useAiCenterStore.ts`

```typescript
const selectedTargetIds = useRiskStore.getState().selectedTargetIds;
const payload: Omit<ChatRequestPayload, 'event_id'> = {
  conversation_id: conversationId,
  content: text,
  ...(selectedTargetIds.length > 0 && { selected_target_ids: selectedTargetIds }),
};
```

### 7.2 sendSpeechMessage

同理，发送 SPEECH 时读取 `selectedTargetIds` 并附加到 payload。

---

## 涉及文件汇总

| 文件 | 动作 |
|------|------|
| `backend/.../dto/websocket/ChatRequestPayload.java` | 新增字段 |
| `backend/.../dto/websocket/SpeechRequestPayload.java` | 新增字段 |
| `backend/.../service/llm/RiskContextFormatter.java` | 新增 `formatSelectedTargets` + `appendTargetDetail` |
| `backend/.../service/llm/LlmChatService.java` | `buildMessages` 增加策略分支 |
| `backend/.../service/llm/VoiceChatService.java` | `buildTextRequestFromTranscript` 透传 |
| `frontend/src/types/schema.d.ts` | ChatRequestPayload + SpeechRequestPayload 扩展 |
| `frontend/src/store/useRiskStore.ts` | 多选改造 |
| `frontend/src/store/index.ts` | 导出更新 |
| `frontend/src/store/useAiCenterStore.ts` | 发送时附加 selectedTargetIds |
| `frontend/src/components/Dashboard/ChatComposer.tsx` | chip 指示器 |
| `frontend/src/components/Dashboard/RiskExplanationPanel.tsx` | 适配多选 + 传 chip props |
| `frontend/src/components/Dashboard/TargetsPanel.tsx` | 适配多选 |
| `frontend/src/components/Map/MapContainer.tsx` | 多目标高亮 |
| `docs/EVENT_SCHEMA.md` | 协议文档更新 |

---

## 测试计划

### 后端单元测试

**RiskContextFormatterTest**:
- `formatSelectedTargets` 返回匹配目标的完整详情（含位置、航速、航向、规则说明）
- 包含 SAFE 目标（不过滤）
- targetIds 中有不存在 ID 时仅展示匹配项
- 全部不匹配时返回 null
- targetIds 为空/null 时返回 null

**LlmChatServiceTest**:
- 有 selectedTargetIds 且匹配 → 消息含选中目标详情
- 有 selectedTargetIds 但无匹配 → 回退到默认摘要
- 无 selectedTargetIds → 默认摘要行为不变
- 原有校验失败、超时等行为不变

**VoiceChatServiceTest**:
- direct 模式：selectedTargetIds 透传到 LlmChatService
- preview 模式：不调用 LLM（现有行为不变）

### 端到端验证

1. 不选中任何目标，提问"当前有什么风险" → LLM 基于默认摘要回答
2. 选中一艘非 SAFE 目标，提问"它距离多少" → LLM 基于完整数据回答
3. 选中一艘 SAFE 目标 → LLM 仍能回答（不被过滤）
4. 选中多艘目标 → ChatComposer 显示多个 chip，LLM 收到多目标详情
5. 点击 chip x 移除一个目标 → 再发送时不再包含该目标
6. 语音 direct 模式 + 选中目标 → 同样基于选中目标回答
7. 地图上多个选中目标同时显示高亮圈
