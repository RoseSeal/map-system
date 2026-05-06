```markdown
# WebSocket CHAT 消息格式约定

本文档定义前端向后端发送 `CHAT` 类 WebSocket 消息时必须遵守的请求格式约定。

## 1. 顶层消息格式

前端发送的 WebSocket 消息必须为 JSON，对话消息统一使用以下结构：

```json
{
  "type": "CHAT",
  "message": {
    ...
  }
}
```

### 约束
- `type` 必填
- `type` 的取值必须为 `CHAT`
- `message` 必须为 JSON 对象

---

## 2. CHAT 公共字段约定

所有 `CHAT` 消息的 `message` 对象都必须包含以下公共字段：

```json
{
  "sequence_id": "string",
  "message_id": "string",
  "role": "user",
  "input_type": "TEXT | SPEECH"
}
```

### 字段说明

| 字段名 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `sequence_id` | string | 否 | 会话或消息序列标识，建议始终传递 |
| `message_id` | string | 是 | 当前消息唯一标识 |
| `role` | string | 是 | 固定为 `user` |
| `input_type` | string | 是 | 输入类型，取值只能为 `TEXT` 或 `SPEECH` |

### 公共校验规则
- `message_id` 不可缺失
- `role` 必须为 `user`
- `input_type` 不可缺失
- `input_type` 只能为 `TEXT` 或 `SPEECH`

---

## 3. 文本消息格式

当 `input_type` 为 `TEXT` 时，消息格式如下：

```json
{
  "type": "CHAT",
  "message": {
    "sequence_id": "uuid",
    "message_id": "uuid",
    "role": "user",
    "input_type": "TEXT",
    "content": "你好"
  }
}
```

### 额外约束
- `content` 必填
- `content` 不可为空字符串或仅包含空白字符

### 字段说明

| 字段名 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `content` | string | 是 | 用户输入的文本内容 |

---

## 4. 语音消息格式

当 `input_type` 为 `SPEECH` 时，消息格式如下：

```json
{
  "type": "CHAT",
  "message": {
    "sequence_id": "uuid",
    "message_id": "uuid",
    "role": "user",
    "input_type": "SPEECH",
    "audio_data": "base64...",
    "audio_format": "webm",
    "mode": "direct"
  }
}
```

### 额外约束
- `audio_data` 必填
- `audio_format` 必填
- `mode` 必填
- `audio_data` 必须为合法 Base64 字符串
- `audio_data` 允许两种形式：
  - 纯 Base64 内容
  - Data URL 形式，例如：`data:audio/webm;base64,xxxx`
- 后端按解码后的音频二进制大小进行限制，当前上限为 `10 MiB`
- `mode` 取值定义为 `direct` 或 `preview`
- 当前前端发送值为 `direct`

### 字段说明

| 字段名 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `audio_data` | string | 是 | 音频的 Base64 编码内容 |
| `audio_format` | string | 是 | 音频格式标识，例如 `webm` |
| `mode` | string | 是 | 语音发送模式，取值为 `direct` 或 `preview` |

---

## 5. 示例

### 文本消息示例

```json
{
  "type": "CHAT",
  "message": {
    "sequence_id": "9b7c7ec6-9e91-4d66-96c4-bc6632d10b93",
    "message_id": "92295d93-21f4-4d83-9544-146f8d0e7b6c",
    "role": "user",
    "input_type": "TEXT",
    "content": "请描述前方海域风险"
  }
}
```

### 语音消息示例

```json
{
  "type": "CHAT",
  "message": {
    "sequence_id": "9b7c7ec6-9e91-4d66-96c4-bc6632d10b93",
    "message_id": "92295d93-21f4-4d83-9544-146f8d0e7b6c",
    "role": "user",
    "input_type": "SPEECH",
    "audio_data": "data:audio/webm;base64,GkXfowEAAAA...",
    "audio_format": "webm",
    "mode": "direct"
  }
}
```

---

## 6. 非法请求示例

以下情况均不符合约定：
- `type` 缺失
- `type` 不为 `CHAT`
- `message` 不是对象
- `message_id` 缺失
- `role` 不为 `user`
- `input_type` 缺失
- 文本消息缺失 `content`
- 语音消息缺失 `audio_data`
- 语音消息缺失 `audio_format`
- 语音消息缺失 `mode`
- 语音消息的 `audio_data` 不是合法 Base64
- 语音解码后大小超过 `10 MiB`
```
