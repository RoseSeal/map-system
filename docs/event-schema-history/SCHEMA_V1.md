# Schema V1
> schema_version: v1
> 生效日期：2026-03-04 至 2026-04-01
> 变更摘要：基于当前源码回填 risk/chat/http 实际协议；统一 Event 抽象尚未落地

## 变更记录
| 版本 | 日期 | 摘要 |
|------|------|------|
| v0 | 2025-xx | 初始消息格式留档 |
| v1 | 2026-03-04 | 从前后端源码提取当前生效协议，补齐 risk/chat/http 三条流 |
| archived | 2026-04-01 | v1 停止生效，归档至 history |

## 1. 文档范围

本文档基于以下源码提取当前实际生效协议，不包含尚未落地的规划接口：

- 前端：`frontend/src/services/socketService.ts`
- 前端：`frontend/src/services/s57Service.ts`
- 前端：`frontend/src/types/schema.d.ts`
- 后端：`backend/map-service/src/main/java/com/whut/map/map_service/dto/websocket/*`
- 后端：`backend/map-service/src/main/java/com/whut/map/map_service/dto/RiskObjectDto.java`
- 后端：`backend/map-service/src/main/java/com/whut/map/map_service/api/S57Controller.java`
- 后端：`backend/map-service/src/main/java/com/whut/map/map_service/websocket/*`
- 后端：`backend/map-service/src/main/java/com/whut/map/map_service/pipeline/ShipDispatcher.java`

当前系统尚未实现统一 `Event(type/source/payload)` 抽象。现网协议仍是：

- `risk` 与 `chat` 复用同一条 WebSocket 连接
- `speech` 不是独立流，而是 `chat` 流中的一种 `input_type`
- 海图数据通过 `/api/s57/*` HTTP GET 接口提供

## 2. 协议总览

| 流 | 传输方式 | 方向 | 入口 | 说明 |
|---|---|---|---|---|
| risk 流 | WebSocket | 后端 -> 前端 | `/api/v1/stream` | 广播实时风险快照，消息类型为 `RISK_UPDATE` |
| chat 流 | WebSocket | 双向 | `/api/v1/stream` | 文本问答、语音转写、LLM 回复、错误回执 |
| http api 流 | HTTP GET | 前端 -> 后端 | `/api/s57/*` | 海图瓦片、图层元数据、样式、健康检查 |

## 3. WebSocket 公共封包

### 3.1 连接入口

- URL：`ws://<host>:<port>/api/v1/stream`
- 当前前端默认：`ws://localhost:8080/api/v1/stream`
- 协议类型：原生 WebSocket，不是 STOMP，不是 SockJS

### 3.2 前端 -> 后端封包

前端发送统一外层结构：

```json
{
  "type": "PING | CHAT",
  "message": {}
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | `string` | 是 | 当前仅支持 `PING`、`CHAT` |
| `message` | `object` | 否 | `type=CHAT` 时为聊天请求体；`PING` 时可省略 |

### 3.3 后端 -> 前端封包

后端返回统一外层结构：

```json
{
  "type": "PONG | RISK_UPDATE | CHAT_TRANSCRIPT | CHAT_REPLY | CHAT_ERROR",
  "sequence_id": "string",
  "payload": {}
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | `string` | 是 | 后端消息类型 |
| `sequence_id` | `string` | 否 | 风险流由后端生成递增字符串；聊天流沿用前端会话 `sequence_id` |
| `payload` | `object|null` | 否 | 业务载荷；`PONG` 当前为 `null` |

### 3.4 当前已实现的 WebSocket 消息类型

| 类型 | 方向 | 说明 |
|---|---|---|
| `PING` | 前端 -> 后端 | 心跳请求 |
| `PONG` | 后端 -> 前端 | 心跳响应 |
| `CHAT` | 前端 -> 后端 | 聊天/语音请求 |
| `CHAT_TRANSCRIPT` | 后端 -> 前端 | 语音转写结果 |
| `CHAT_REPLY` | 后端 -> 前端 | LLM 文本回复 |
| `CHAT_ERROR` | 后端 -> 前端 | 聊天或转写失败 |
| `RISK_UPDATE` | 后端 -> 前端 | 风险快照广播 |

前端代码中还兼容了 `SNAPSHOT`、`ALERT`，但后端当前没有发送这两类消息。

## 4. Risk 流

### 4.1 发送方式

- 入口：`/api/v1/stream`
- 方向：后端广播到全部已连接会话
- 触发：AIS/MQTT 消息进入 `ShipDispatcher` 后组装 `RiskObjectDto` 并广播
- 消息类型：`RISK_UPDATE`

### 4.2 risk 流外层封包

```json
{
  "type": "RISK_UPDATE",
  "sequence_id": "1743500000000",
  "payload": {
    "...": "RiskObject"
  }
}
```

### 4.3 RiskObject 结构

```json
{
  "risk_object_id": "123456789-2026-04-01T03:21:15Z",
  "timestamp": "2026-04-01T03:21:15Z",
  "governance": {
    "mode": "adaptive",
    "trust_factor": 0.99
  },
  "own_ship": {
    "id": "123456789",
    "position": {
      "lon": 114.3,
      "lat": 30.5
    },
    "dynamics": {
      "sog": 12.5,
      "cog": 90.0,
      "hdg": 90.0,
      "rot": 0.0
    },
    "platform_health": {
      "status": "NORMAL",
      "description": ""
    },
    "future_trajectory": {
      "prediction_type": "linear"
    },
    "safety_domain": {
      "shape_type": "ellipse",
      "dimensions": {
        "fore_nm": 0.5,
        "aft_nm": 0.1,
        "port_nm": 0.2,
        "stbd_nm": 0.2
      }
    }
  },
  "targets": [
    {
      "id": "413999001",
      "tracking_status": "tracking",
      "position": {
        "lon": 114.31,
        "lat": 30.51
      },
      "vector": {
        "speed_kn": 8.0,
        "course_deg": 45.0
      },
      "risk_assessment": {
        "risk_level": "WARNING",
        "cpa_metrics": {
          "dcpa_nm": 0.42,
          "tcpa_sec": 180.0
        },
        "graphic_cpa_line": {
          "own_pos": [114.3, 30.5],
          "target_pos": [114.31, 30.51]
        },
        "ozt_sector": {
          "start_angle_deg": 35.0,
          "end_angle_deg": 55.0,
          "is_active": true
        },
        "explanation": {
          "source": "llm",
          "text": "目标船正接近本船，建议保持关注。"
        }
      }
    }
  ],
  "environment_context": {
    "safety_contour_val": 10.0,
    "active_alerts": []
  }
}
```

### 4.4 RiskObject 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `risk_object_id` | `string` | 是 | 由 `ownShip.id + "-" + snapshotTimestamp` 组成 |
| `timestamp` | `string` | 是 | ISO-8601 UTC 时间 |
| `governance` | `object` | 是 | 当前为固定样板值 |
| `own_ship` | `object` | 是 | 本船态势 |
| `targets` | `array` | 是 | 全部被跟踪目标船列表 |
| `environment_context` | `object` | 是 | 环境上下文，当前为固定样板值 |

### 4.5 `governance`

| 字段 | 类型 | 当前值/说明 |
|---|---|---|
| `mode` | `string` | 当前固定为 `adaptive` |
| `trust_factor` | `number` | 当前固定为 `0.99` |

### 4.6 `own_ship`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | `string` | 是 | 本船 ID |
| `position.lon` | `number` | 是 | 经度 |
| `position.lat` | `number` | 是 | 纬度 |
| `dynamics.sog` | `number` | 是 | 航速 |
| `dynamics.cog` | `number` | 是 | 对地航向 |
| `dynamics.hdg` | `number` | 是 | 船首向；无 heading 时回退为 `cog` |
| `dynamics.rot` | `number` | 是 | 当前固定为 `0.0` |
| `platform_health.status` | `string` | 是 | 当前固定为 `NORMAL` |
| `platform_health.description` | `string` | 是 | 当前固定为空串 |
| `future_trajectory.prediction_type` | `string` | 是 | 当前固定为 `linear` |
| `safety_domain.shape_type` | `string` | 是 | 当前固定为 `ellipse` |
| `safety_domain.dimensions` | `object` | 是 | 当前固定椭圆尺寸 |

### 4.7 `targets[*]`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | `string` | 是 | 目标船 ID |
| `tracking_status` | `string` | 是 | 当前固定为 `tracking` |
| `position.lon` | `number` | 是 | 经度 |
| `position.lat` | `number` | 是 | 纬度 |
| `vector.speed_kn` | `number` | 是 | 目标航速 |
| `vector.course_deg` | `number` | 是 | 目标航向 |
| `risk_assessment.risk_level` | `string` | 是 | `SAFE` / `CAUTION` / `WARNING` / `ALARM` |
| `risk_assessment.cpa_metrics.dcpa_nm` | `number` | 是 | DCPA，单位海里 |
| `risk_assessment.cpa_metrics.tcpa_sec` | `number` | 是 | TCPA，单位秒 |
| `risk_assessment.graphic_cpa_line` | `object` | 否 | 仅在 `cpaResult.isApproaching()` 时出现 |
| `risk_assessment.ozt_sector` | `object` | 否 | 仅在 `risk_level` 为 `WARNING` 或 `ALARM` 时出现 |
| `risk_assessment.explanation.source` | `string` | 是 | 当前可能为 `llm` / `rule` / `fallback` |
| `risk_assessment.explanation.text` | `string` | 是 | 当前解释文本；无解释时回退为 `Awaiting CPA/TCPA` |

### 4.8 `environment_context`

| 字段 | 类型 | 当前值/说明 |
|---|---|---|
| `safety_contour_val` | `number` | 当前固定为 `10.0` |
| `active_alerts` | `array` | 当前固定为空数组 |

### 4.9 risk 流当前实现说明

- 后端当前只发送 `targets`，不发送 `all_targets`
- 前端 `schema.d.ts` 中的 `all_targets`、`simulation_layer` 来自前端设计/Mock 数据，不是后端现网输出
- 前端 `socketService` 对风险消息做顺序检查时只在 `sequence_id` 为数字时生效；但后端实际序列化的是字符串

## 5. Chat 流

### 5.1 基本说明

- 入口：`/api/v1/stream`
- 前端请求类型：`CHAT`
- 后端响应类型：`CHAT_TRANSCRIPT`、`CHAT_REPLY`、`CHAT_ERROR`
- 角色约束：前端请求 `role` 必须是 `user`
- `speech` 不是独立通道，而是 `CHAT.message.input_type = SPEECH`

### 5.2 文本聊天请求

前端发送：

```json
{
  "type": "CHAT",
  "message": {
    "sequence_id": "conversation-xxx",
    "message_id": "user-xxx",
    "role": "user",
    "input_type": "TEXT",
    "content": "请评估当前风险"
  }
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sequence_id` | `string` | 是 | 会话 ID，前端用于绑定聊天会话 |
| `message_id` | `string` | 是 | 当前消息 ID |
| `role` | `string` | 是 | 当前必须是 `user` |
| `input_type` | `string` | 是 | 文本模式固定为 `TEXT` |
| `content` | `string` | 是 | 用户输入文本，不能为空 |

### 5.3 语音聊天请求

前端发送：

```json
{
  "type": "CHAT",
  "message": {
    "sequence_id": "conversation-xxx",
    "message_id": "user-voice-xxx",
    "role": "user",
    "input_type": "SPEECH",
    "audio_data": "<base64 or data-url-base64>",
    "audio_format": "webm",
    "mode": "direct"
  }
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sequence_id` | `string` | 是 | 会话 ID |
| `message_id` | `string` | 是 | 当前语音消息 ID |
| `role` | `string` | 是 | 必须是 `user` |
| `input_type` | `string` | 是 | 语音模式固定为 `SPEECH` |
| `audio_data` | `string` | 是 | Base64 音频；允许带 `data:...;base64,` 前缀 |
| `audio_format` | `string` | 是 | 音频格式，如 `webm` |
| `mode` | `string` | 否 | `direct` 或 `preview` |

语音请求约束：

- `audio_data` 不可为空
- `audio_data` 必须是合法 Base64
- 解码后音频大小不得超过 `10 MB`
- 默认转写语言为 `zh`

### 5.4 `CHAT_TRANSCRIPT`

语音请求转写成功后，后端先返回：

```json
{
  "type": "CHAT_TRANSCRIPT",
  "sequence_id": "conversation-xxx",
  "payload": {
    "sequence_id": "conversation-xxx",
    "message_id": "server-msg-xxx",
    "reply_to_message_id": "user-voice-xxx",
    "transcript": "请评估当前风险",
    "language": "zh",
    "timestamp": "2026-04-01T03:21:15Z"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sequence_id` | `string` | 是 | 对应聊天会话 |
| `message_id` | `string` | 是 | 后端生成的新消息 ID |
| `reply_to_message_id` | `string` | 是 | 关联原始语音消息 ID |
| `transcript` | `string` | 是 | 语音转写文本 |
| `language` | `string` | 否 | 当前默认 `zh` |
| `timestamp` | `string` | 否 | ISO-8601 时间 |

`mode=preview` 时，到 `CHAT_TRANSCRIPT` 为止，不再继续触发 LLM 回复。

### 5.5 `CHAT_REPLY`

文本聊天成功，或语音 `mode=direct` 在转写后继续调用 LLM 成功时，后端返回：

```json
{
  "type": "CHAT_REPLY",
  "sequence_id": "conversation-xxx",
  "payload": {
    "sequence_id": "conversation-xxx",
    "message_id": "server-msg-xxx",
    "reply_to_message_id": "user-msg-xxx",
    "role": "assistant",
    "content": "当前存在接近风险，建议保持避碰关注。",
    "source": "zhipu",
    "timestamp": "2026-04-01T03:21:18Z"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sequence_id` | `string` | 是 | 对应聊天会话 |
| `message_id` | `string` | 是 | 后端生成的新消息 ID |
| `reply_to_message_id` | `string` | 是 | 对应用户消息 ID |
| `role` | `string` | 是 | 固定为 `assistant` |
| `content` | `string` | 是 | LLM 文本回复 |
| `source` | `string` | 否 | 当前通常为 `llm.provider`，如 `zhipu` / `gemini` |
| `timestamp` | `string` | 否 | ISO-8601 时间 |

补充说明：

- 后端 DTO 中还声明了 `target_id` 字段，但当前 `ChatMessageFactory` 未赋值，前端也未使用

### 5.6 `CHAT_ERROR`

请求不合法、语音转写失败、音频超限、LLM 超时或 LLM 调用失败时，后端返回：

```json
{
  "type": "CHAT_ERROR",
  "sequence_id": "conversation-xxx",
  "payload": {
    "sequence_id": "conversation-xxx",
    "message_id": "server-msg-xxx",
    "reply_to_message_id": "user-msg-xxx",
    "error_code": "LLM_TIMEOUT",
    "error_message": "LLM request timed out.",
    "timestamp": "2026-04-01T03:21:30Z"
  }
}
```

当前错误码枚举：

- `INVALID_CHAT_REQUEST`
- `TRANSCRIPTION_FAILED`
- `TRANSCRIPTION_TIMEOUT`
- `INVALID_AUDIO_FORMAT`
- `AUDIO_TOO_LARGE`
- `LLM_TIMEOUT`
- `LLM_REQUEST_FAILED`
- `LLM_DISABLED`

### 5.7 `PONG`

前端周期性发送：

```json
{
  "type": "PING"
}
```

后端返回：

```json
{
  "type": "PONG",
  "sequence_id": "1743500000001",
  "payload": null
}
```

## 6. HTTP API 流（海图）

### 6.1 基本说明

- Base Path：`/api/s57`
- 仅提供 HTTP GET
- 后端控制器已开启 `@CrossOrigin(origins = "*")`

### 6.2 `GET /api/s57/tiles/{z}/{x}/{y}.pbf`

说明：

- 返回组合后的 S-57 MVT 瓦片
- 可选查询参数：`safety_contour`

请求示例：

```http
GET /api/s57/tiles/12/3370/1552.pbf
GET /api/s57/tiles/12/3370/1552.pbf?safety_contour=10.0
```

响应：

- `200 OK`
- `Content-Type: application/vnd.mapbox-vector-tile`
- `Cache-Control: public, max-age=3600`
- Body：MVT 二进制

### 6.3 `GET /api/s57/tiles/{z}/{x}/{y}/{layer}.pbf`

说明：

- 返回单图层 MVT 瓦片
- 常用于调试或按图层拉取

支持图层：

- `LNDARE`
- `DEPARE`
- `DEPCNT`
- `COALNE`
- `SOUNDG`

请求示例：

```http
GET /api/s57/tiles/12/3370/1552/DEPARE.pbf
```

响应：

- `200 OK`
- `Content-Type: application/vnd.mapbox-vector-tile`
- Body：MVT 二进制

### 6.4 `GET /api/s57/layers`

返回图层元数据：

```json
{
  "version": "1.0",
  "layers": [
    {
      "id": "LNDARE",
      "type": "fill",
      "minzoom": 0,
      "maxzoom": 22,
      "description": "Land Area",
      "geometryType": "polygon"
    }
  ],
  "crs": "EPSG:3857",
  "bounds": {
    "minLon": -73.8,
    "minLat": 40.575,
    "maxLon": -73.725,
    "maxLat": 40.65
  }
}
```

说明：

- 返回值是对象，不是数组
- `layers[*].attributes` 仅在部分图层存在

### 6.5 `GET /api/s57/safety-contour`

请求示例：

```http
GET /api/s57/safety-contour
GET /api/s57/safety-contour?depth=10.0
```

响应示例：

```json
{
  "safetyContourDepth": 10.0,
  "unit": "meters",
  "description": "Areas shallower than this depth are highlighted as navigation hazards",
  "tileUrl": "/api/s57/tiles/{z}/{x}/{y}.pbf?safety_contour=10.0"
}
```

### 6.6 `GET /api/s57/style.json`

返回 MapLibre 样式 JSON：

```json
{
  "version": 8,
  "name": "S-57 ENC Chart",
  "sources": {
    "s57-source": {
      "type": "vector",
      "tiles": [
        "http://localhost:8081/api/s57/tiles/{z}/{x}/{y}.pbf"
      ],
      "minzoom": 0,
      "maxzoom": 14
    }
  },
  "layers": []
}
```

说明：

- 该接口返回完整 style 对象
- 当前返回中的 `tiles` URL 写死为 `http://localhost:8081/api/s57/tiles/{z}/{x}/{y}.pbf`

### 6.7 `GET /api/s57/health`

响应示例：

```json
{
  "status": "UP",
  "service": "S-57 ENC Chart Service",
  "version": "1.0.0"
}
```

## 7. 当前枚举和值域

### 7.1 WebSocket 类型

- `PING`
- `PONG`
- `CHAT`
- `CHAT_TRANSCRIPT`
- `CHAT_REPLY`
- `CHAT_ERROR`
- `RISK_UPDATE`

### 7.2 RiskLevel

- `SAFE`
- `CAUTION`
- `WARNING`
- `ALARM`

### 7.3 Chat role

- `user`
- `assistant`

### 7.4 Chat input type

- `TEXT`
- `SPEECH`

### 7.5 Speech mode

- `direct`
- `preview`

## 8. 源码差异与兼容说明

### 8.1 前端声明比后端实际更宽

前端 `schema.d.ts` 中存在以下字段/消息类型，但后端当前未发送：

- `RiskObject.all_targets`
- `RiskObject.simulation_layer`
- WebSocket 类型 `SNAPSHOT`
- WebSocket 类型 `ALERT`

### 8.2 后端声明比前端实际更宽

后端 `BackendChatReplyPayload` 中声明了 `target_id`，但当前后端不赋值，前端类型也未接收该字段。

### 8.3 `sequence_id` 类型差异

- 后端 `BackendMessage.sequence_id` 实际序列化为字符串
- 前端 `WebSocketMessage.sequence_id` 声明为 `number | string`
- 前端风险消息顺序检查仅在 `sequence_id` 为数字时启用，因此当前 `RISK_UPDATE` 基本不会触发前端乱序保护

### 8.4 `s57Service.ts` 与后端返回结构不一致

- `fetchLayerMetadata()` 的 TypeScript 返回类型声明为 `LayerMetadata[]`
- 但后端 `/api/s57/layers` 实际返回 `{ version, layers, crs, bounds }`

- `getSafetyContour()` 的 TypeScript 返回类型声明为 `{ depth, unit }`
- 但后端实际返回 `{ safetyContourDepth, unit, description, tileUrl }`

### 8.5 `style.json` 与前端默认后端地址不一致

- 前端常量默认 HTTP 后端是 `http://localhost:8080`
- 但 `/api/s57/style.json` 返回的 `tiles` 地址写死为 `http://localhost:8081`
- 当前前端地图初始化并未直接使用该 `style.json`，而是使用本地 `layerStyles.ts` 中的瓦片源配置
