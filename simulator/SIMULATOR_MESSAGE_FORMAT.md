# Simulator MQTT 消息格式需求

> 文档状态：current / 已整理
> 最后更新：2026-04-22
> 依据：[`../simulator`](../simulator) 当前脚本实现，以及后端接收 DTO [`MqttAisDto.java`](../backend/map-service/src/main/java/com/whut/map/map_service/source/ais/mqtt/MqttAisDto.java) 与 [`WeatherMqttDto.java`](../backend/map-service/src/main/java/com/whut/map/map_service/source/weather/mqtt/WeatherMqttDto.java)

## 1. 文档定位

本文档整理 `simulator/` 目录下当前已实现的 MQTT 模拟消息格式，用于：

- 统一 simulator 脚本的 payload 结构
- 明确后端当前实际接收的字段命名与类型
- 为新增 simulator 场景提供直接可复用的格式基线

本文档描述的是**当前实现契约**，不是未来方案设计。风险 SSE / WebSocket 对外协议见 [`EVENT_SCHEMA.md`](./EVENT_SCHEMA.md)。

## 2. 脚本与 Topic 对照

| 脚本 | 默认 Topic | 消息类别 | 说明 |
|---|---|---|---|
| [`../simulator/ais_simulator.py`](../simulator/ais_simulator.py) | `usv/AisMessage` | AIS | 武汉演示区域连续航迹 |
| [`../simulator/jamaica_bay_ais_mqtt_publisher.py`](../simulator/jamaica_bay_ais_mqtt_publisher.py) | `usv/AisMessage` | AIS | Jamaica Bay 演示航迹 |
| [`../simulator/step3_frontend_ais_mqtt_publisher.py`](../simulator/step3_frontend_ais_mqtt_publisher.py) | `usv/AisMessage` | AIS | 前端 Step 3 测试场景，含低置信度脉冲与异常航向值 |
| [`../simulator/llm_smoke_test_publisher.py`](../simulator/llm_smoke_test_publisher.py) | `usv/AisMessage` | AIS | LLM smoke test 最小场景 |
| [`../simulator/cpaTcapTest.py`](../simulator/cpaTcapTest.py) | `usv/AisMessage` | AIS | CSV 回放 |
| [`../simulator/weather_mqtt_publisher.py`](../simulator/weather_mqtt_publisher.py) | `usv/Weather` | Weather | 天气快照与区域天气 |
| [`../simulator/mqtt_publisher_base.py`](../simulator/mqtt_publisher_base.py) | 由调用方指定 | 基础设施 | MQTT 连接、循环与发布基类 |

## 3. 通用格式要求

### 3.1 传输层

- 每条消息均通过 MQTT 单条 `publish()` 发送，不额外包裹统一 envelope。
- payload 为 UTF-8 JSON 字符串，对象根节点固定为 JSON object，不使用 array 作为根节点。
- topic 决定 schema；`usv/AisMessage` 与 `usv/Weather` 不共享字段命名规则。
- 默认 broker 参数由脚本 CLI 控制：`--host`、`--port`、`--topic`、`--username`、`--password`。

### 3.2 编码与命名

- AIS 消息字段名固定为全大写：`MSGTIME`、`MMSI`、`LAT`、`LON`、`SOG`、`COG`、`HEADING`。
- Weather 消息字段名固定为小写 snake_case：`weather_code`、`visibility_nm`、`timestamp_utc` 等。
- 新增 simulator 脚本应复用 [`../simulator/mqtt_publisher_base.py`](../simulator/mqtt_publisher_base.py)，避免重复实现 MQTT 连接逻辑。

### 3.3 类型约束

- 新增脚本应优先输出 JSON number，而不是把数值字段编码为 JSON string。
- 现有 [`../simulator/cpaTcapTest.py`](../simulator/cpaTcapTest.py) 为 CSV 兼容路径，会把 CSV 原始值直接写入 JSON；后端当前依赖 Jackson 类型转换进行兼容解析。
- 新增脚本不应在 payload 中增加自定义包裹层，例如 `{ "topic": "...", "data": { ... } }`；当前后端 DTO 不按此结构接收。

## 4. AIS 消息格式

### 4.1 Topic

- Topic：`usv/AisMessage`
- 后端接收 DTO：[`MqttAisDto.java`](../backend/map-service/src/main/java/com/whut/map/map_service/source/ais/mqtt/MqttAisDto.java)

### 4.2 字段定义

| 字段 | 类型 | 必填 | 当前要求 |
|---|---|---|---|
| `MSGTIME` | string | 是 | 时间格式固定为 `yyyy-M-d HH:mm:ss`，例如 `2026-4-22 09:30:15` |
| `MMSI` | string | 是 | 船舶标识，当前脚本均按字符串发送 |
| `LAT` | number | 是 | 纬度，通常保留 6 位小数 |
| `LON` | number | 是 | 经度，通常保留 6 位小数 |
| `SOG` | number | 是 | 对地航速，单位 kn，通常保留 2 位小数 |
| `COG` | number | 是 | 对地航向，单位 deg，通常保留 1 位小数 |
| `HEADING` | number | 是 | 船首向，单位 deg，通常保留 1 位小数 |

### 4.3 语义约束

- `MSGTIME` 当前为**无时区**本地时间字符串；AIS 模拟器脚本使用 `datetime.now()` 生成，后端按 `LocalDateTime` 解析。
- `LAT` 与 `LON` 在当前脚本中均按十进制度表示。
- `COG` 与 `HEADING` 在正常场景下使用 `[0, 360)` 范围内值。
- [`../simulator/step3_frontend_ais_mqtt_publisher.py`](../simulator/step3_frontend_ais_mqtt_publisher.py) 存在两个显式兼容特例：
  - `COG = 360.0`：用于表示无效 course，驱动 encounter type 退化为 `UNDEFINED`
  - `HEADING = 511.0`：用于表示未知 heading，沿用 AIS 协议常见哨兵值

### 4.4 标准示例

```json
{
  "MSGTIME": "2026-4-22 09:30:15",
  "MMSI": "123456789",
  "LAT": 30.578,
  "LON": 114.2,
  "SOG": 12.0,
  "COG": 90.0,
  "HEADING": 90.0
}
```

### 4.5 异常/测试场景示例

```json
{
  "MSGTIME": "2026-4-22 09:30:19",
  "MMSI": "366100104",
  "LAT": 40.621,
  "LON": -73.854,
  "SOG": 7.0,
  "COG": 360.0,
  "HEADING": 511.0
}
```

### 4.6 CSV 回放映射

[`../simulator/cpaTcapTest.py`](../simulator/cpaTcapTest.py) 从 CSV 读取后按如下字段映射输出：

| MQTT 字段 | CSV 列名 |
|---|---|
| `MSGTIME` | `MSGTIME` |
| `MMSI` | `MMSI` |
| `LAT` | `LAT` |
| `LON` | `LON` |
| `SOG` | `SpeedOverGround` |
| `COG` | `CourseOverGround` |
| `HEADING` | `TrueHeading` |

约束：

- CSV 列值必须可被后端解析为 `LocalDateTime` 与数值类型。
- 若新增 CSV 回放源，应优先在读入阶段完成数值清洗，而不是把脏值直接透传到 MQTT。

## 5. Weather 消息格式

### 5.1 Topic

- Topic：`usv/Weather`
- 后端接收 DTO：[`WeatherMqttDto.java`](../backend/map-service/src/main/java/com/whut/map/map_service/source/weather/mqtt/WeatherMqttDto.java)

### 5.2 顶层字段定义

| 字段 | 类型 | 必填 | 当前要求 |
|---|---|---|---|
| `weather_code` | string | 是 | 当前脚本内置场景使用 `CLEAR`、`FOG`、`RAIN`、`STORM` |
| `visibility_nm` | number | 是 | 能见度，单位 nm |
| `precipitation_mm_per_hr` | number | 是 | 降水强度，单位 mm/hr |
| `wind` | object | 是 | 风场对象，见 §5.3 |
| `surface_current` | object | 是 | 表层海流对象，见 §5.4 |
| `sea_state` | integer | 是 | 海况等级；当前脚本 jitter 逻辑把范围限制在 `0..9` |
| `timestamp_utc` | string | 是 | UTC 时间戳，ISO 8601 格式，结尾使用 `Z` |
| `weather_zones` | array | 否 | 区域化天气列表；仅区域场景携带 |

### 5.3 `wind` 对象

```json
{
  "speed_kn": 5.0,
  "direction_from_deg": 210.0
}
```

字段要求：

- `speed_kn`：number，单位 kn
- `direction_from_deg`：number，单位 deg，表示风从哪个方向吹来

### 5.4 `surface_current` 对象

```json
{
  "speed_kn": 0.4,
  "set_deg": 95.0
}
```

字段要求：

- `speed_kn`：number，单位 kn
- `set_deg`：number，单位 deg，表示流向

### 5.5 单快照示例

```json
{
  "weather_code": "FOG",
  "visibility_nm": 0.8,
  "precipitation_mm_per_hr": 0.0,
  "wind": {
    "speed_kn": 3.0,
    "direction_from_deg": 225.0
  },
  "surface_current": {
    "speed_kn": 0.4,
    "set_deg": 90.0
  },
  "sea_state": 2,
  "timestamp_utc": "2026-04-22T09:30:15Z"
}
```

### 5.6 `weather_zones` 扩展格式

当前 [`../simulator/weather_mqtt_publisher.py`](../simulator/weather_mqtt_publisher.py) 在 `zoned_fog` 场景下可附带 `weather_zones`。每个 zone 为独立对象，当前脚本使用如下字段：

| 字段 | 类型 | 必填 | 当前要求 |
|---|---|---|---|
| `zone_id` | string | 是 | zone 唯一标识 |
| `weather_code` | string | 是 | zone 内天气编码 |
| `visibility_nm` | number | 是 | zone 内能见度 |
| `precipitation_mm_per_hr` | number | 是 | zone 内降水 |
| `wind` | object | 是 | 结构同 §5.3 |
| `surface_current` | object | 是 | 结构同 §5.4 |
| `sea_state` | integer | 是 | zone 内海况等级 |
| `geometry` | object | 是 | GeoJSON 几何对象 |

`geometry` 当前要求：

- `type`：当前脚本实际发送 `Polygon`
- 坐标顺序：严格遵循 GeoJSON `[longitude, latitude]`
- 后端 DTO 兼容 `Polygon` 与 `MultiPolygon`

区域天气示例：

```json
{
  "weather_code": "CLEAR",
  "visibility_nm": 10.0,
  "precipitation_mm_per_hr": 0.0,
  "wind": {
    "speed_kn": 5.0,
    "direction_from_deg": 270.0
  },
  "surface_current": {
    "speed_kn": 0.3,
    "set_deg": 90.0
  },
  "sea_state": 2,
  "timestamp_utc": "2026-04-22T09:30:15Z",
  "weather_zones": [
    {
      "zone_id": "fog-bank-east",
      "weather_code": "FOG",
      "visibility_nm": 0.8,
      "precipitation_mm_per_hr": 0.0,
      "wind": {
        "speed_kn": 3.0,
        "direction_from_deg": 225.0
      },
      "surface_current": {
        "speed_kn": 0.4,
        "set_deg": 90.0
      },
      "sea_state": 2,
      "geometry": {
        "type": "Polygon",
        "coordinates": [
          [
            [114.3, 30.52],
            [114.34, 30.52],
            [114.34, 30.56],
            [114.3, 30.56],
            [114.3, 30.52]
          ]
        ]
      }
    }
  ]
}
```

### 5.7 兼容性要求

- 不带 `weather_zones` 的 payload 仍然是有效 weather 消息，语义为单快照全局天气。
- 当前后端 `WeatherMqttDto` 对未知字段启用了忽略策略，因此允许 weather payload 在未来追加非破坏性字段。
- 当前脚本未实现 zone 级时间戳；zone 生效时间默认跟随顶层 `timestamp_utc` 所代表的同一帧快照时间。

## 6. 新增 simulator 脚本时的格式约束

- 若模拟 AIS 消息，应继续复用 `usv/AisMessage` 与 7 个既有字段，不新增并列字段名变体，例如 `msgTime`、`lon`、`headingDeg`。
- 若模拟 weather 消息，应继续复用 `usv/Weather` 与现有 snake_case 字段，不新增二次包裹层。
- 若需要表达异常航迹质量，应优先通过现有字段取值变化完成，而不是新增测试专用字段。
- 若需要表达区域天气，应使用 `weather_zones`，且几何坐标继续遵循 GeoJSON `[longitude, latitude]` 顺序。

## 7. 参考

- [`../simulator/mqtt_publisher_base.py`](../simulator/mqtt_publisher_base.py)
- [`../simulator/ais_simulator.py`](../simulator/ais_simulator.py)
- [`../simulator/jamaica_bay_ais_mqtt_publisher.py`](../simulator/jamaica_bay_ais_mqtt_publisher.py)
- [`../simulator/step3_frontend_ais_mqtt_publisher.py`](../simulator/step3_frontend_ais_mqtt_publisher.py)
- [`../simulator/llm_smoke_test_publisher.py`](../simulator/llm_smoke_test_publisher.py)
- [`../simulator/cpaTcapTest.py`](../simulator/cpaTcapTest.py)
- [`../simulator/weather_mqtt_publisher.py`](../simulator/weather_mqtt_publisher.py)
- [`./EVENT_SCHEMA.md`](./EVENT_SCHEMA.md)
