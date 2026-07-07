# 学习型航迹预测 HTTP 契约

> 版本：v1.2-trajectory-prediction
> 上游：[`step-2-integration.md`](step-2-integration.md)
> 下游：`trajectory-service/server/`、`LearningTrajectoryPredictionAdapter`
> status: implemented
> last_updated: 2026-07-07

---

## 一、范围

本文定义 Java 风险引擎与 Python 学习型航迹预测 sidecar 之间的 HTTP 契约。该契约只服务实时预测集成，不改变 SSE 风险对象协议，也不改变 `PredictedCpaTcpaCalculator` 的输入语义。

## 二、服务端点

### `GET /health`

用于 compose healthcheck 与 Java 启用前人工确认。

成功响应：

```json
{
  "status": "ok",
  "model_path": "/app/model/gru.onnx",
  "offsets_seconds": [153, 306, 459]
}
```

失败响应：

- HTTP `503`
- `detail.error = "model_missing"`：ONNX 模型文件不存在。
- `detail.error = "onnxruntime_missing"`：运行环境缺少 onnxruntime。

### `POST /predict`

批量预测端点。Java adapter 可一次提交多个目标船窗口；单目标增量路径使用同一端点提交一个目标。

请求：

```json
{
  "targets": [
    {
      "target_id": "413000001",
      "points": [
        {
          "msg_time": "2026-04-12T09:00:00+08:00",
          "latitude": 30.0000,
          "longitude": 114.0000,
          "sog": 7.5,
          "cog": 90.0
        }
      ]
    }
  ]
}
```

字段约定：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `targets[].target_id` | string | 目标船 ID / MMSI。 |
| `targets[].points` | array | Java adapter 从专用 raw buffer 中按 `msgTime` 降采样得到的原始历史点窗口。当前配置下窗口容量为 K=10。 |
| `msg_time` | ISO-8601 datetime | AIS 消息时间；特征 dt 基于该字段，不使用墙钟。 |
| `latitude` / `longitude` | number | WGS84 经纬度。 |
| `sog` | number | 节。 |
| `cog` | number | 罗经角，度。 |

响应：

```json
{
  "predictions": [
    {
      "target_id": "413000001",
      "points": [
        {
          "latitude": 30.0020,
          "longitude": 114.0030,
          "offset_seconds": 153
        },
        {
          "latitude": 30.0040,
          "longitude": 114.0060,
          "offset_seconds": 306
        },
        {
          "latitude": 30.0060,
          "longitude": 114.0090,
          "offset_seconds": 459
        }
      ]
    }
  ]
}
```

字段约定：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `predictions[].target_id` | string | 与请求目标对应。 |
| `points[].latitude` / `points[].longitude` | number | 预测骨架点 WGS84 经纬度。 |
| `points[].offset_seconds` | integer | 相对窗口末点 `msg_time` 的预测 offset；默认约为 153 / 306 / 459 秒。 |

Java adapter 接收响应后不会直接把 3 个骨架点喂给 `PredictedCpaTcpaCalculator`。它会以 t=0 的目标船当前位置为首样本，把 sidecar 返回的骨架点按分段线性插值密化到 `engine.trajectory-prediction.step-seconds` 粒度，并保留原始骨架 offset 点。这样 live CPA/TCPA 的采样粒度不会从 CV 默认 10s 粗化到约 153s。

## 三、错误与降级

- sidecar 返回 4xx / 5xx、响应为空、JSON 解析失败、缺少目标响应或目标响应为空时，Java adapter 对对应目标回退 `CvPredictionEngine`。
- 连续 5 次 sidecar 请求失败后，Java adapter 熔断 30 秒；熔断期间所有目标直接走 CV。
- 专用 raw buffer 暖机不足 K=10、点位字段无效、SOG/COG 为 AIS not-available 哨兵值时，Java adapter 不请求 sidecar，直接回 CV。
- 内部 `predictionSource=GRU|CV` 只用于验收观测，不进入 SSE 风险对象协议。

## 四、特征构造

Java adapter 只传原始点窗口。sidecar 负责构造与离线训练一致的 6 维特征：

1. 相对窗口末点的局部平面坐标 `(x, y)`，单位 km。
2. 相邻点时间差 `dt / 100`。
3. `sog / 10`。
4. `sin(cog)` 与 `cos(cog)`。

该构造复用 `trajectory-service/scripts/train_gru.py::build_features`；离线 `load_split` 与在线 sidecar 均调用该函数。
