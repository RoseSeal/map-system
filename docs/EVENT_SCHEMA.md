# Event Schema
> schema_version: v2
> 文档状态：current / 已实施
> 生效日期：2026-04-01
> 变更摘要：risk 改为 SSE，chat 保持 WebSocket，解释流并入 SSE 风险通道

## 变更记录
| 版本 | 日期 | 摘要 |
|------|------|------|
| v1 | 2026-03-04 | risk/chat 复用单 WebSocket，speech 作为 chat 子类型 |
| v1 archived | 2026-04-01 | v1 停止生效，归档至 `docs/event-schema-history/SCHEMA_V1.md` |
| v2-draft | 2026-04-01 | 确认 risk/chat 拆连接，定义 SSE + WebSocket 双协议结构 |
| v2 | 2026-04-01 | 协议已落地，文档按当前实现修订 |
| v2 | 2026-04-08 | CHAT/SPEECH payload 新增可选字段 `selected_target_ids`，用于选中目标定向注入 |

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
- 后续若用户选择解释卡片作为补充上下文，再由前端在新的 chat 请求中显式引用

### 3.4 SSE 重连语义

- risk SSE 断线重连后，只补发最新一帧
- 不保证逐条补历史事件
- `Last-Event-ID` 仅用于帮助服务端识别客户端是否断线重连
- 服务端恢复后返回当前最新 `RISK_UPDATE` 或当前最新 `ERROR`

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
| `EXPLANATION` | 风险解释事件 |
| `ERROR` | risk 连接错误事件 |

### 4.3 `RISK_UPDATE` payload

```json
{
  "event_id": "server-event-xxx",
  "risk_object_id": "123456789-2026-04-01T03:21:15Z",
  "timestamp": "2026-04-01T03:21:15Z",
  "governance": { "mode": "adaptive", "trust_factor": 0.99 },
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
      "risk_assessment": {
        "risk_level": "WARNING",
        "cpa_metrics": { "dcpa_nm": 0.42, "tcpa_sec": 180.0 },
        "graphic_cpa_line": { "own_pos": [114.3, 30.5], "target_pos": [114.31, 30.51] },
        "ozt_sector": { "start_angle_deg": 35.0, "end_angle_deg": 55.0, "is_active": true }
      }
    }
  ],
  "environment_context": { "safety_contour_val": 10.0, "active_alerts": [] }
}
```

约束：

- `risk_assessment.explanation` 在 v2 中移除
- `all_targets` 不再出现在 risk payload 中
- `simulation_layer` 不再出现在 risk payload 中

### 4.4 `ERROR` payload（risk）

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

### 4.5 `EXPLANATION` payload（risk）

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

## 5. Chat 连接协议

### 5.1 WebSocket 上行 envelope

```json
{
  "type": "PING | CHAT | SPEECH | CLEAR_HISTORY",
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
  "type": "PONG | CHAT_REPLY | SPEECH_TRANSCRIPT | CLEAR_HISTORY_ACK | ERROR",
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

#### server -> client

| type | payload |
|---|---|
| `PONG` | `null` |
| `CHAT_REPLY` | `ChatReplyPayload` |
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
    "selected_target_ids": ["413999001"]
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversation_id` | `string` | 客户端会话 ID |
| `event_id` | `string` | 当前用户请求事件 ID |
| `content` | `string` | 用户问题文本 |
| `selected_target_ids` | `string[]` | 可选，用户选中的目标船 ID 列表；存在时后端注入选中目标的完整数据 |

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
| `selected_target_ids` | `string[]` | 可选，用户选中的目标船 ID 列表；`direct` 模式下透传至 LLM 链路 |

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

### 6.7 `ERROR`

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

### 6.8 `PING / PONG`

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
| TrackingStatus | `tracking` |
| PredictionType | `linear` |
| SafetyDomainShape | `ellipse` |
| RiskSseEventType | `RISK_UPDATE` / `EXPLANATION` / `ERROR` |
| ChatDownlinkType | `PONG` / `CHAT_REPLY` / `SPEECH_TRANSCRIPT` / `CLEAR_HISTORY_ACK` / `ERROR` |
| ChatUplinkType | `PING` / `CHAT` / `SPEECH` / `CLEAR_HISTORY` |

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
- v1 已归档到 [docs/event-schema-history/SCHEMA_V1.md](/home/xin/workspace/map-system/docs/event-schema-history/SCHEMA_V1.md)
- v2 已切换完成，前后端不得回退或混用 v1/v2 字段名
- 若后续引入“解释卡片作为 chat 补充上下文”，应通过新的显式 payload 字段建模，不应把 `EXPLANATION` 事件本身重新并入 chat 会话流
