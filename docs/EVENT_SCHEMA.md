# Event Schema
> schema_version: v2
> 文档状态：current / 已实施
> 生效日期：2026-04-01
> 变更摘要：risk 改为 SSE，chat 保持 WebSocket，解释流并入 SSE 风险通道；CHAT 上行支持最后一轮非破坏式重答

## 变更记录
| 版本 | 日期 | 摘要 |
|------|------|------|
| v1 | 2026-03-04 | risk/chat 复用单 WebSocket，speech 作为 chat 子类型 |
| v1 archived | 2026-04-01 | v1 停止生效，归档至 `docs/history/v0.5-mvp/event-schema/SCHEMA_V1.md` |
| v2-draft | 2026-04-01 | 确认 risk/chat 拆连接，定义 SSE + WebSocket 双协议结构 |
| v2 | 2026-04-01 | 协议已落地，文档按当前实现修订 |
| v2 | 2026-04-08 | CHAT/SPEECH payload 新增可选字段 `selected_target_ids`，用于选中目标定向注入 |
| v2 | 2026-04-16 | CHAT payload 新增 `edit_last_user_message`；`selected_target_ids` 语义扩展为可注入最近有效解释文本 |
| v2 | 2026-04-18 | `RISK_UPDATE.environment_context` 新增 `weather` 字段；`active_alerts` 增补天气告警枚举 |
| v2 | 2026-04-24 | risk SSE 新增 `ADVISORY` 事件；新增错误码 `ADVISORY_SCHEMA_FAILED` |
| v2 | 2026-04-25 | CHAT payload 新增 `agent_mode`；WebSocket 连接建立时下发 `CAPABILITY` 握手消息；chat agent path 新增 `AGENT_STEP` 下行事件 |
| v2 | 2026-04-27 | `CAPABILITY` 扩展 LLM provider 能力声明；新增 `SET_LLM_PROVIDER_SELECTION` / `LLM_PROVIDER_SELECTION` 运行时 provider 选择协议 |
| v2 | 2026-04-28 | risk SSE 新增 `ENVIRONMENT_UPDATE`；`RISK_UPDATE` 移除 `environment_context` 并新增 `environment_state_version` |

## 1. 文档定位

本文档定义当前已实施并生效的 v2 协议。

- 当前生效协议即本文档
- 本文档用于前后端实现、联调与后续变更控制
- v2 仅重构实时事件协议
- `/api/s57/*` HTTP 海图接口暂不纳入本次 v2 变更范围

## 2. 总体设计

### 2.1 连接拆分

| 连接 | 路径 | 协议 | 方向 |
|---|---|---|---|
| risk | `/api/v2/risk` | SSE | 单向下行 |
| chat | `/api/v2/chat` | WebSocket | 双向 |

### 2.2 设计目标

- risk 流专注实时态势，并承载异步风险解释事件
- explanation 流与 chat 会话解耦，但归属 risk SSE 通道
- speech 不再复用 `CHAT + input_type`，而是独立 `SPEECH` 消息类型
- risk 使用 SSE 原生 envelope，利用浏览器自动重连能力
- chat 保持 WebSocket，承载用户问答与语音交互

## 3. 关键语义约定

### 3.1 `sequence_id`

#### risk 连接

- 不放在 JSON payload 中
- 由 SSE 原生 `id:` 字段承担
- 语义为服务端生成的递增事件序号
- 浏览器断线重连时通过 `Last-Event-ID` 自动带回

#### chat 连接

- 仅存在于 server -> client 的 WebSocket 顶层 envelope
- 语义为服务端在单条 chat 连接内生成的递增事件序号
- 主要用于 Message Deduplication、Idempotency 与 Distributed Tracing
- 不用于传输层重排序控制；WebSocket 基于 TCP，天然保证有序
- 不承担业务会话标识

### 3.2 `conversation_id`

- 仅存在于 chat 业务 payload
- 由客户端生成
- 用于将用户消息、语音转写、LLM 回复绑定到同一问答会话
- 不用于 `EXPLANATION`

### 3.3 `EXPLANATION`

- `EXPLANATION` 不属于 chat 会话消息
- 它是风险触发后由服务端异步产生的解释事件
- 它通过 risk SSE 通道下发
- 前端收到后应展示为风险卡片/解释卡片
- 当前阶段它不携带 `conversation_id`

### 3.4 SSE 重连语义

- risk SSE 断线重连后，只补发最新环境快照与最新风险快照
- 不保证逐条补历史事件
- `Last-Event-ID` 仅用于帮助服务端识别客户端是否断线重连
- 服务端恢复后按 `ENVIRONMENT_UPDATE`、`RISK_UPDATE` 顺序返回当前最新状态；若某类快照尚不存在，则跳过该类

### 3.5 `event_id`

- 原 `message_id` 在 v2 中统一重命名为 `event_id`
- 所有 server -> client 业务事件都必须有 `event_id`
- client -> server 的请求也保留 `event_id`，用于关联回复

## 4. Risk 连接协议

## 4.1 SSE 封包格式

SSE 原生字段承担 envelope 职责，不再额外包一层统一 JSON：

```text
: keepalive

id: {sequence_id}
event: RISK_UPDATE
data: {"event_id":"server-event-xxx", ...payload}

id: {sequence_id}
event: ENVIRONMENT_UPDATE
data: {"event_id":"server-event-xxx", ...payload}

id: {sequence_id}
event: EXPLANATION
data: {"event_id":"server-event-xxx", ...payload}

id: {sequence_id}
event: ERROR
data: {"event_id":"server-event-xxx", ...payload}
```

字段约定：

| SSE 字段 | 语义 |
|---|---|
| `id` | `sequence_id`，服务端递增事件序号 |
| `event` | 事件类型 |
| `data` | payload JSON |
| `: keepalive` | 心跳注释，无业务语义 |

### 4.2 risk 连接事件类型

| event | 说明 |
|---|---|
| `RISK_UPDATE` | 风险快照事件 |
| `ENVIRONMENT_UPDATE` | 环境状态快照事件 |
| `EXPLANATION` | 风险解释事件 |
| `ADVISORY` | 场景级 AI 航行建议事件 |
| `ERROR` | risk 连接错误事件 |

### 4.3 `RISK_UPDATE` payload

```json
{
  "event_id": "server-event-xxx",
  "risk_object_id": "123456789-2026-04-01T03:21:15Z",
  "timestamp": "2026-04-01T03:21:15Z",
  "environment_state_version": 17,
  "governance": { "mode": "adaptive", "trust_factor": 1.0 },
  "own_ship": {
    "id": "123456789",
    "position": { "lon": 114.3, "lat": 30.5 },
    "dynamics": { "sog": 12.5, "cog": 90.0, "hdg": 90.0, "rot": 0.0 },
    "platform_health": { "status": "NORMAL", "description": "" },
    "future_trajectory": { "prediction_type": "linear" },
    "safety_domain": {
      "shape_type": "ellipse",
      "dimensions": { "fore_nm": 0.5, "aft_nm": 0.1, "port_nm": 0.2, "stbd_nm": 0.2 }
    }
  },
  "targets": [
    {
      "id": "413999001",
      "tracking_status": "tracking",
      "position": { "lon": 114.31, "lat": 30.51 },
      "vector": { "speed_kn": 8.0, "course_deg": 45.0 },
      "predicted_trajectory": {
        "prediction_type": "cv",
        "horizon_seconds": 600,
        "points": [
          { "lat": 30.512, "lon": 114.312, "offset_seconds": 30 },
          { "lat": 30.514, "lon": 114.314, "offset_seconds": 60 }
        ]
      },
      "risk_assessment": {
        "risk_level": "WARNING",
        "cpa_metrics": { "dcpa_nm": 0.42, "tcpa_sec": 180.0 },
        "risk_score": 0.85,
        "risk_confidence": 0.9,
        "graphic_cpa_line": { "own_pos": [114.3, 30.5], "target_pos": [114.31, 30.51] },
        "ozt_sector": { "start_angle_deg": 35.0, "end_angle_deg": 55.0, "is_active": true },
        "encounter_type": "CROSSING"
      }
    }
  ]
}
```

约束：

- `risk_assessment.explanation` 在 v2 中移除
- `all_targets` 不再出现在 risk payload 中
- `simulation_layer` 不再出现在 risk payload 中
- `target.predicted_trajectory` 为可选字段；存在时结构固定为 `{ prediction_type, horizon_seconds, points[] }`
- CTR 升级后 `target.predicted_trajectory.prediction_type` 仍保持 `"cv"`
- `target.risk_assessment.encounter_type` 为可选字段；取值为 `HEAD_ON` / `OVERTAKING` / `CROSSING` / `UNDEFINED`
- `environment_state_version` 指向该风险快照引用的最新环境状态版本；前端不得用该字段重建环境内容
- `governance.trust_factor` 表示本船状态置信度；本船置信度缺失或不可解析时取 `0.0`

### 4.4 `ENVIRONMENT_UPDATE` payload

```json
{
  "event_id": "server-event-xxx",
  "timestamp": "2026-04-28T06:00:00Z",
  "environment_state_version": 17,
  "reason": "WEATHER_UPDATED",
  "changed_fields": ["weather", "weather_zones", "active_alerts"],
  "environment_context": {
    "safety_contour_val": 10.0,
    "active_alerts": ["LOW_VISIBILITY", "SHOAL_PROXIMITY"],
    "weather": {
      "weather_code": "FOG",
      "visibility_nm": 0.8,
      "precipitation_mm_per_hr": 0.0,
      "wind": { "speed_kn": 3.2, "direction_from_deg": 225.0 },
      "surface_current": { "speed_kn": 0.4, "set_deg": 90.0 },
      "sea_state": 2,
      "source_zone_id": "fog-bank-east",
      "updated_at": "2026-04-18T10:22:15Z"
    },
    "weather_zones": null,
    "hydrology": {
      "own_ship_min_depth_m": 8.3,
      "nearest_shoal_nm": 0.0,
      "nearest_obstruction": {
        "category": "WRECK",
        "distance_nm": 0.71,
        "bearing_deg": 37
      }
    }
  }
}
```

约束：

- `reason` 取值为 `WEATHER_UPDATED` / `WEATHER_EXPIRED` / `SAFETY_CONTOUR_UPDATED` / `SAFETY_CONTOUR_RESET` / `OWN_SHIP_ENV_REEVALUATED`
- `changed_fields` 仅用于前端优化和调试提示，不作为语义真值；前端收到事件后整体替换环境状态
- `environment_context.weather` 为可选字段；无实时天气源或超过陈旧阈值时取 `null`
- `environment_context.weather_zones` 与 `weather` 联动；当 `weather == null` 时同步取 `null`
- `environment_context.hydrology` 为可选字段；水文查询完全不可用时取 `null`，子字段未知时取 `null`
- `environment_context.active_alerts` 可包含共享环境告警枚举：`LOW_VISIBILITY` / `HIGH_WIND` / `HEAVY_PRECIPITATION` / `STRONG_CURRENT_SET` / `SHOAL_PROXIMITY` / `OBSTRUCTION_NEARBY` / `DEPTH_DATA_MISSING`

### 4.5 `ERROR` payload（risk）

```json
{
  "event_id": "server-event-xxx",
  "connection": "risk",
  "error_code": "string",
  "error_message": "string",
  "reply_to_event_id": null,
  "timestamp": "2026-04-01T03:21:30Z"
}
```

### 4.6 `EXPLANATION` payload（risk）

说明：

- 用于风险解释卡片
- 与 chat 会话解耦
- 通过 risk SSE 通道下发
- 不携带 `conversation_id`

```json
{
  "event_id": "server-event-xxx",
  "risk_object_id": "123456789-2026-04-01T03:21:15Z",
  "target_id": "413999001",
  "risk_level": "WARNING",
  "provider": "gemini",
  "text": "目标船正接近本船，建议保持关注。",
  "timestamp": "2026-04-01T03:21:18Z"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | `string` | 服务端事件 ID |
| `risk_object_id` | `string` | 对应的风险快照帧 ID |
| `target_id` | `string` | 被解释目标 ID |
| `risk_level` | `string` | 解释对应的风险等级 |
| `provider` | `string` | `gemini` / `zhipu` / `fallback` |
| `text` | `string` | 解释文本 |
| `timestamp` | `string` | 解释生成时间 |

### 4.7 `ADVISORY` payload（risk）

说明：

- 用于场景级 AI 航行建议卡片
- 通过 risk SSE 通道下发，独立于 `EXPLANATION`
- 不进入最新快照 replay 缓存，新连接不补发
- 生命周期：前端收到新 advisory 时将旧 active advisory 标记为 `SUPERSEDED` 并归档

```json
{
  "event_id": "server-event-xxx",
  "advisory_id": "advisory-uuid",
  "risk_object_id": "snapshot-12345",
  "snapshot_version": 12345,
  "scope": "SCENE",
  "status": "ACTIVE",
  "supersedes_id": null,
  "valid_until": "2026-04-24T10:25:00Z",
  "risk_level": "ALARM",
  "provider": "gemini",
  "timestamp": "2026-04-24T10:23:00Z",
  "summary": "目标 413999001 进入紧急接近态势，建议立即采取避让动作。",
  "affected_targets": ["413999001"],
  "recommended_action": {
    "type": "COURSE_CHANGE",
    "description": "建议右转并持续监控 TCPA 变化。",
    "urgency": "IMMEDIATE"
  },
  "evidence_items": [
    "目标 413999001 DCPA 0.12 nm，低于 WARNING 阈值 0.5 nm。",
    "目标 413999001 TCPA 138 s，低于 300 s 紧急阈值。"
  ]
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | `string` | 服务端事件 ID |
| `advisory_id` | `string` | 本条 advisory 的唯一 ID（UUID） |
| `risk_object_id` | `string` | 关联的风险快照帧 ID |
| `snapshot_version` | `number` | 触发此 advisory 的快照版本号 |
| `scope` | `string` | 固定为 `SCENE` |
| `status` | `string` | `ACTIVE` / `SUPERSEDED` |
| `supersedes_id` | `string\|null` | 被本条取代的上一条 advisory ID |
| `valid_until` | `string` | advisory 前端展示有效期（ISO 8601） |
| `risk_level` | `string` | 场景最高风险等级，由后端从快照填充 |
| `provider` | `string` | LLM 提供商标识 |
| `timestamp` | `string` | advisory 生成时间 |
| `summary` | `string` | 场景级综合建议摘要 |
| `affected_targets` | `string[]` | 涉及目标 ID 列表 |
| `recommended_action.type` | `string` | 动作类型枚举 |
| `recommended_action.description` | `string` | 动作描述 |
| `recommended_action.urgency` | `string` | 紧急程度枚举 |
| `evidence_items` | `string[]` | 支撑建议的数值/状态事实列表（来自工具结果） |

新增错误码：

- `ADVISORY_SCHEMA_FAILED`：advisory 输出解析校验失败

## 5. Chat 连接协议

### 5.1 WebSocket 上行 envelope

```json
{
  "type": "PING | CHAT | SPEECH | CLEAR_HISTORY | SET_LLM_PROVIDER_SELECTION",
  "source": "client",
  "payload": {}
}
```

上行约束：

- 顶层无 `sequence_id`
- 会话锚定由 payload 内 `conversation_id` 承担
- `source` 固定为 `client`

### 5.2 WebSocket 下行 envelope

```json
{
  "type": "PONG | CAPABILITY | LLM_PROVIDER_SELECTION | CHAT_REPLY | AGENT_STEP | SPEECH_TRANSCRIPT | CLEAR_HISTORY_ACK | ERROR",
  "source": "server",
  "sequence_id": "string",
  "payload": {}
}
```

下行约束：

- `sequence_id` 为单条 chat 连接内的服务端递增序号
- `source` 固定为 `server`
- 真正业务数据全部在 `payload`

### 5.3 chat 连接消息类型

#### client -> server

| type | payload |
|---|---|
| `PING` | `null` |
| `CHAT` | `ChatPayload` |
| `SPEECH` | `SpeechPayload` |
| `CLEAR_HISTORY` | `ClearHistoryPayload` |
| `SET_LLM_PROVIDER_SELECTION` | `SetLlmProviderSelectionPayload` |

#### server -> client

| type | payload |
|---|---|
| `PONG` | `null` |
| `CAPABILITY` | `ChatCapabilityPayload` |
| `LLM_PROVIDER_SELECTION` | `LlmProviderSelectionPayload` |
| `CHAT_REPLY` | `ChatReplyPayload` |
| `AGENT_STEP` | `AgentStepPayload` |
| `SPEECH_TRANSCRIPT` | `SpeechTranscriptPayload` |
| `CLEAR_HISTORY_ACK` | `ClearHistoryAckPayload` |
| `ERROR` | `ErrorPayload` |

## 6. Payload 定义

### 6.1 `CHAT`

```json
{
  "type": "CHAT",
  "source": "client",
  "payload": {
    "conversation_id": "conversation-xxx",
    "event_id": "client-event-xxx",
    "content": "请评估当前风险",
    "agent_mode": "CHAT",
    "selected_target_ids": ["413999001"],
    "edit_last_user_message": false
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversation_id` | `string` | 客户端会话 ID |
| `event_id` | `string` | 当前用户请求事件 ID |
| `content` | `string` | 用户问题文本 |
| `agent_mode` | `string` | 可选；`CHAT`（默认）走普通 prompt 拼接路径，`AGENT` 走 agent loop；缺省视为 `CHAT` |
| `selected_target_ids` | `string[]` | 可选，用户选中的目标船 ID 列表；存在时后端注入选中目标的风险详情；若存在最近有效解释文本且目标当前仍被追踪且非 SAFE，则一并注入；不控制 agent 路由 |
| `edit_last_user_message` | `boolean` | 可选；`true` 表示本次请求用于编辑并重答当前会话最后一组 `USER / ASSISTANT` 轮次 |

补充语义：

- `edit_last_user_message` 不传或为 `false` 时，按普通追加会话处理。
- `edit_last_user_message=true` 时，服务端先基于编辑后的文本生成新回复；仅在生成成功后替换最后一组 `USER / ASSISTANT`，失败时保留旧轮次不破坏会话历史。

### 6.2 `SPEECH`

```json
{
  "type": "SPEECH",
  "source": "client",
  "payload": {
    "conversation_id": "conversation-xxx",
    "event_id": "client-event-xxx",
    "audio_data": "<base64>",
    "audio_format": "webm",
    "mode": "direct",
    "selected_target_ids": ["413999001"]
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversation_id` | `string` | 客户端会话 ID |
| `event_id` | `string` | 当前语音请求事件 ID |
| `audio_data` | `string` | Base64 音频内容 |
| `audio_format` | `string` | 音频格式 |
| `mode` | `string` | `direct` / `preview` |
| `selected_target_ids` | `string[]` | 可选，用户选中的目标船 ID 列表；`direct` 模式下透传至 LLM 链路，并沿用 CHAT 的选中目标上下文注入语义 |

约束：

- `audio_data` 非空
- `audio_data` 必须是合法 Base64
- 解码后音频大小不得超过 10MB
- 默认转写语言为 `zh`

### 6.3 `CLEAR_HISTORY`

```json
{
  "type": "CLEAR_HISTORY",
  "source": "client",
  "payload": {
    "conversation_id": "conversation-xxx",
    "event_id": "client-event-xxx"
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversation_id` | `string` | 要清理的客户端会话 ID |
| `event_id` | `string` | 当前清理请求事件 ID |

### 6.4 `CHAT_REPLY`

文本问答与 `mode=direct` 语音问答共用同一回复类型：

```json
{
  "event_id": "server-event-xxx",
  "conversation_id": "conversation-xxx",
  "reply_to_event_id": "client-event-xxx",
  "role": "assistant",
  "content": "...",
  "provider": "gemini",
  "timestamp": "2026-04-01T03:21:18Z"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | `string` | 服务端回复事件 ID |
| `conversation_id` | `string` | 会话 ID |
| `reply_to_event_id` | `string` | 关联的客户端请求事件 ID |
| `role` | `string` | 固定为 `assistant` |
| `content` | `string` | LLM 回复文本 |
| `provider` | `string` | `gemini` / `zhipu` |
| `timestamp` | `string` | 回复时间 |

### 6.5 `SPEECH_TRANSCRIPT`

```json
{
  "event_id": "server-event-xxx",
  "conversation_id": "conversation-xxx",
  "reply_to_event_id": "client-event-xxx",
  "transcript": "请评估当前风险",
  "language": "zh",
  "timestamp": "2026-04-01T03:21:15Z"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | `string` | 服务端转写事件 ID |
| `conversation_id` | `string` | 会话 ID |
| `reply_to_event_id` | `string` | 对应语音请求事件 ID |
| `transcript` | `string` | 转写文本 |
| `language` | `string` | 语种，当前默认 `zh` |
| `timestamp` | `string` | 转写完成时间 |

补充：

- `mode=preview` 时，到 `SPEECH_TRANSCRIPT` 为止，不继续触发 LLM

### 6.6 `CLEAR_HISTORY_ACK`

```json
{
  "event_id": "server-event-xxx",
  "conversation_id": "conversation-xxx",
  "reply_to_event_id": "client-event-xxx",
  "timestamp": "2026-04-09T09:20:00Z"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | `string` | 服务端确认事件 ID |
| `conversation_id` | `string` | 已清理的会话 ID |
| `reply_to_event_id` | `string` | 对应的 `CLEAR_HISTORY` 请求事件 ID |
| `timestamp` | `string` | 清理确认时间 |

### 6.7 `CAPABILITY`

WebSocket 连接建立后，后端必须发送一次 `CAPABILITY` 消息，告知前端当前服务的能力可用性。前端在收到该消息前，不得发送依赖能力判断的请求。

```json
{
  "event_id": "server-event-xxx",
  "chat_available": true,
  "agent_available": true,
  "speech_transcription_available": true,
  "disabled_reasons": {
    "agent": null,
    "speech_transcription": null
  },
  "llm_providers": [
    {
      "provider": "gemini",
      "display_name": "Gemini",
      "available": true,
      "supported_tasks": ["explanation", "chat", "agent"],
      "degraded_tasks": [],
      "quota_status": "UNKNOWN"
    },
    {
      "provider": "zhipu",
      "display_name": "Zhipu",
      "available": true,
      "supported_tasks": ["explanation", "chat", "agent"],
      "degraded_tasks": ["agent"],
      "quota_status": "UNKNOWN"
    }
  ],
  "effective_provider_selection": {
    "explanation_provider": "zhipu",
    "chat_provider": "gemini"
  },
  "provider_selection_mutable": true,
  "timestamp": "2026-04-25T10:00:00Z"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | `string` | 服务端事件 ID |
| `chat_available` | `boolean` | 普通 chat 路径是否可用 |
| `agent_available` | `boolean` | agent loop 路径是否可用（`llm.agent-mode-enabled=true` 且相关组件可用） |
| `speech_transcription_available` | `boolean` | 语音转录路径是否可用 |
| `disabled_reasons` | `object\|null` | 各能力不可用时的原因文本；均可用时可为 `null` 或省略 |
| `llm_providers` | `LlmProviderCapability[]` | 后端声明的 provider 可用性与任务支持矩阵 |
| `effective_provider_selection` | `LlmProviderSelection` | 当前实际生效的任务级 provider 选择 |
| `provider_selection_mutable` | `boolean` | 当前部署是否允许前端运行时切换 provider；本阶段固定为 `true` |
| `timestamp` | `string` | 消息发送时间 |

`LlmProviderCapability` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `provider` | `LlmProviderId` | `gemini` 或 `zhipu` |
| `display_name` | `string` | 前端展示名 |
| `available` | `boolean` | provider client 是否已配置并注册 |
| `supported_tasks` | `LlmTaskType[]` | 支持的任务：`explanation` / `chat` / `agent` |
| `degraded_tasks` | `LlmTaskType[]` | 降级支持的任务；如 Zhipu agent path 可返回最终文本但工具能力弱于 Gemini |
| `quota_status` | `LlmQuotaStatus` | 当前为静态占位值 `UNKNOWN` |
| `disabled_reason` | `string\|null` | provider 不可用时的原因 |

`LlmProviderSelection` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `explanation_provider` | `LlmProviderId` | 后续 risk explanation 生成使用的 provider |
| `chat_provider` | `LlmProviderId` | 普通 chat、chat agent、advisory agent 共用的 provider |

前端消费规则：

- 连接建立后（`onopen`）将 capability 状态置为 `pending`，并启动 3000ms 超时计时器
- 收到 `CAPABILITY` 后清除计时器，将状态置为 `ready`，并按字段禁用对应 UI 入口
- 超时仍未收到 `CAPABILITY` 时，将状态置为 `unavailable`，关闭连接触发重连
- 每次重连后必须重复以上流程；旧连接迟到的 `CAPABILITY` 不得覆盖新连接状态
- 同一连接内 provider 选择变更不重复发送 `CAPABILITY`；应通过 `LLM_PROVIDER_SELECTION` ACK 更新前端状态

### 6.8 `SET_LLM_PROVIDER_SELECTION` / `LLM_PROVIDER_SELECTION`

`SET_LLM_PROVIDER_SELECTION` 用于前端提交运行时 provider 选择。字段均可选，但至少必须提供一个；未提供字段保持当前选择不变。

上行：

```json
{
  "event_id": "client-event-xxx",
  "explanation_provider": "gemini",
  "chat_provider": "zhipu"
}
```

下行 ACK：

```json
{
  "event_id": "server-event-xxx",
  "reply_to_event_id": "client-event-xxx",
  "effective_provider_selection": {
    "explanation_provider": "gemini",
    "chat_provider": "zhipu"
  },
  "timestamp": "2026-04-27T10:00:00Z"
}
```

约束：

- provider 不存在、不可用或不支持目标任务时，后端返回 `ERROR`，`error_code = INVALID_CHAT_REQUEST`
- 成功 ACK 后，前端更新本地 effective selection 并清除 pending 状态
- 收到对应 `ERROR` 后，前端恢复提交前的 selection 并展示错误状态
- `CHAT` / `SPEECH` 请求不携带 provider 字段；后端按当前 selection store 路由

### 6.9 `AGENT_STEP`

仅在 `agent_mode = AGENT` 请求处理期间发送，用于实时展示工具调用进度。每完成一个工具调用阶段发送一条。

```json
{
  "event_id": "server-event-xxx",
  "conversation_id": "conversation-xxx",
  "reply_to_event_id": "client-event-xxx",
  "step_id": "step-1",
  "tool_name": "get_risk_snapshot",
  "status": "SUCCEEDED",
  "message": "已读取风险快照",
  "timestamp": "2026-04-25T10:00:01Z"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | `string` | 服务端事件 ID |
| `conversation_id` | `string` | 所属会话 ID |
| `reply_to_event_id` | `string` | 对应的客户端请求事件 ID，用于挂到对应 pending 消息下 |
| `step_id` | `string` | 单次请求内唯一的 step 标识 |
| `tool_name` | `string\|null` | 工具名；`FINALIZING` 等非工具阶段可为 `null` |
| `status` | `string` | `RUNNING` / `SUCCEEDED` / `FAILED` / `FINALIZING` |
| `message` | `string` | 前端展示文案，如"正在读取风险快照"、"工具调用完成" |
| `timestamp` | `string` | 服务端生成时间 |

发送规则：

- 决定调用工具前发送 `RUNNING`；工具返回后发送 `SUCCEEDED`；工具失败发送 `FAILED`
- 所有工具轮次完成并准备最终回复时，发送一次 `FINALIZING`
- 若 provider 首轮直接返回文本且无工具调用，允许只发送 `FINALIZING`
- 失败路径（provider failure、max iterations exceeded）不发送 `FINALIZING`；前端收到 `ERROR` 时应将最后一个 `RUNNING` step 标记为 `FAILED`

### 6.10 `ERROR`

SSE 与 WebSocket chat 下行共用：

```json
{
  "event_id": "server-event-xxx",
  "connection": "chat",
  "error_code": "LLM_TIMEOUT",
  "error_message": "human-readable description",
  "reply_to_event_id": "client-event-xxx",
  "timestamp": "2026-04-01T03:21:30Z"
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | `string` | 服务端错误事件 ID |
| `connection` | `string` | `risk` / `chat` |
| `error_code` | `string` | 错误码 |
| `error_message` | `string` | 可读错误描述 |
| `reply_to_event_id` | `string|null` | 关联请求事件 ID；risk 中为 `null` |
| `timestamp` | `string` | 错误时间 |

当前错误码枚举：

- `INVALID_CHAT_REQUEST`
- `INVALID_SPEECH_REQUEST`
- `TRANSCRIPTION_FAILED`
- `TRANSCRIPTION_TIMEOUT`
- `INVALID_AUDIO_FORMAT`
- `AUDIO_TOO_LARGE`
- `LLM_TIMEOUT`
- `LLM_REQUEST_FAILED`
- `LLM_DISABLED`
- `CONVERSATION_BUSY`

### 6.11 `PING / PONG`

```json
{ "type": "PING", "source": "client", "payload": null }
{ "type": "PONG", "source": "server", "sequence_id": "xxx", "payload": null }
```

## 7. 枚举汇总

| 枚举 | 值 |
|---|---|
| RiskLevel | `SAFE` / `CAUTION` / `WARNING` / `ALARM` |
| SpeechMode | `direct` / `preview` |
| Connection | `risk` / `chat` |
| TrackingStatus | `tracking` / `stale` |
| PredictionType | `linear` / `cv` |
| EncounterType | `HEAD_ON` / `OVERTAKING` / `CROSSING` / `UNDEFINED` |
| SafetyDomainShape | `ellipse` |
| RiskSseEventType | `RISK_UPDATE` / `ENVIRONMENT_UPDATE` / `EXPLANATION` / `ADVISORY` / `ERROR` |
| AdvisoryScope | `SCENE` |
| AdvisoryStatus | `ACTIVE` / `SUPERSEDED` |
| AdvisoryActionType | `COURSE_CHANGE` / `SPEED_CHANGE` / `MAINTAIN_COURSE` / `MONITOR` / `UNKNOWN` |
| AdvisoryUrgency | `LOW` / `MEDIUM` / `HIGH` / `IMMEDIATE` |
| ChatDownlinkType | `PONG` / `CAPABILITY` / `LLM_PROVIDER_SELECTION` / `CHAT_REPLY` / `AGENT_STEP` / `SPEECH_TRANSCRIPT` / `CLEAR_HISTORY_ACK` / `ERROR` |
| ChatUplinkType | `PING` / `CHAT` / `SPEECH` / `CLEAR_HISTORY` / `SET_LLM_PROVIDER_SELECTION` |
| ChatAgentMode | `CHAT` / `AGENT` |
| AgentStepStatus | `RUNNING` / `SUCCEEDED` / `FAILED` / `FINALIZING` |
| CapabilityState | `pending` / `ready` / `unavailable` |
| LlmProviderId | `gemini` / `zhipu` |
| LlmTaskType | `explanation` / `chat` / `agent` |
| LlmQuotaStatus | `UNKNOWN` / `AVAILABLE` / `LIMITED` / `EXHAUSTED` |

## 8. 与 v1 的核心差异

- risk 从 WebSocket 广播改为 SSE 下行
- chat 从混合 risk/chat 单连接改为独立 WebSocket 连接
- explanation 从 `RiskObject.targets[*].risk_assessment.explanation` 拆出为 risk SSE 通道下的独立 `EXPLANATION` 事件
- speech 从 `CHAT + input_type=SPEECH` 改为独立 `SPEECH` 事件
- `message_id` 统一重命名为 `event_id`
- `reply_to_message_id` 统一重命名为 `reply_to_event_id`
- risk 重连策略明确为“只补最新一帧”

## 9. 实施备注

- 当前生效协议即本文档
- v1 已归档到 [docs/history/v0.5-mvp/event-schema/SCHEMA_V1.md](history/v0.5-mvp/event-schema/SCHEMA_V1.md)
- v2 已切换完成，前后端不得回退或混用 v1/v2 字段名
