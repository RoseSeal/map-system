# Weather Step 1：MQTT topic + 后端承载 + 前端视觉基础

> 文档状态：active
> 最后更新：2026-04-17
> 所属 track：[`WEATHER_PLAN.md`](./WEATHER_PLAN.md)
> 目标：建立从 simulator 到前端的端到端气象信号链路，交付首个可演示场景（低能见度 + 雾 overlay），但暂不改动风险引擎与 LLM。

---

## 1. Summary

Step 1 是"点亮信号"，不是"业务消费"。重点是把 `usv/Weather` 这条新 topic 从 simulator 贯通到前端视觉层，让后续 Step 2（引擎消费）与 Step 3（LLM 消费）都有可用的数据源；本步骤不改 `RiskAssessmentEngine`、不改 `LlmRiskContext`、不注册 agent tool。

验收可以通过 `--scene fog` 模拟器命令直接得到"雾化海图 + `StatusPanel` 显示 'FOG, 能见度 0.8 nm' + SSE 带 `LOW_VISIBILITY` alert"三件事。

---

## 2. In Scope

### 2.1 Simulator 端

- 新建 `simulator/weather_mqtt_publisher.py`（独立入口，不与 AIS publisher 合并）
- 复用 [`simulator/mqtt_publisher_base.py`](../../../simulator/mqtt_publisher_base.py) 的 `MqttConfig` 与连接/发布基础设施
- 支持 `--scene` CLI 参数，内置四套预设场景：
  - `clear`：`weather_code=CLEAR, visibility_nm=10, wind 5kn, sea_state 2`
  - `fog`：`weather_code=FOG, visibility_nm=0.8, wind 3kn, sea_state 2`
  - `rain`：`weather_code=RAIN, visibility_nm=3.0, precipitation 8mm/hr, wind 15kn, sea_state 4`
  - `storm`：`weather_code=STORM, visibility_nm=1.5, wind 35kn, sea_state 7, surface_current 2.8kn`
- 发布周期：每 10 秒 1 次（配置可调），固定 payload schema（见 §4.1）
- Topic：`usv/Weather`
- 支持 `--jitter` 在预设值上叠加小幅随机扰动，便于演示动态

### 2.2 后端

- 新建包 `com.whut.map.map_service.source.weather`，子包 `mqtt` / `dto` / `config`
- `WeatherMqttConfig`：订阅 `usv/Weather`，参考现有 `source/ais/config` 的 pattern
- `WeatherMessageHandler`：接收 MQTT message → 反序列化 → 写入 `WeatherContextHolder`
- `WeatherContextHolder`（包 `shared/context`）：持有 `volatile Snapshot(WeatherContext context, Instant updatedAt)`；该对象同时服务 `source/weather`、`risk`、SSE 与 LLM，不归属 `llm/context`
- `WeatherContext` DTO（Java record，从一开始就用 record，不走 Lombok `@Data` 的老路——`AgentSnapshot` 冻结契约的成本教训见 [`../agent/AGENT_LOOP_PLAN.md`](../agent/AGENT_LOOP_PLAN.md) §3.2）
- `RiskObjectMetaAssembler.buildEnvironmentContext` 扩展：若 `WeatherContextHolder` 有 fresh snapshot（`now - updatedAt < 60s`），注入 `environment_context.weather` 与 `active_alerts`

### 2.3 前端

- `schema.d.ts` 扩展 `EnvironmentContext`：
  ```ts
  interface EnvironmentContext {
    safety_contour_val: number;
    active_alerts: string[];
    weather?: WeatherContext | null;  // 新增，null 表示 stale 或未配置
  }
  interface WeatherContext {
    weather_code: 'CLEAR'|'FOG'|'RAIN'|'SNOW'|'STORM';
    visibility_nm: number | null;
    precipitation_mm_per_hr: number | null;
    wind: { speed_kn: number|null; direction_from_deg: number|null };
    surface_current: { speed_kn: number|null; set_deg: number|null };
    sea_state: number | null;
    updated_at: string;
  }
  ```
- `useRiskStore` 的 `environment` 字段已原封接入，无需改消费代码
- `MapContainer` 新增 fog overlay 层：`<div>` 绝对定位铺满地图容器，`background: rgba(210, 215, 220, X)`，`X` 根据 `visibility_nm` 线性映射 `[0.0 → 0.65, 10.0 → 0.0]`
- `StatusPanel` 新增右下角气象摘要标签：`"FOG · vis 0.8 nm · wind SW 12kn"`
- fog overlay 必须在地图图层之上、但在 HUD / 风险符号层之下（zIndex 控制）

---

## 3. Out of Scope

- 风险引擎修正：Step 2
- LLM `LlmRiskContext` 扩展：Step 3
- agent tool 注册：Step 3
- 降雨粒子层、风场箭头、水流矢量：Step 3（与 advisory 消费一起做，避免两次前端迭代）
- 真实气象 API 接入
- 气象时序保留 / 回放

---

## 4. 关键实现细节

### 4.1 MQTT payload 契约

Simulator 发布的 JSON payload 固定 schema（字段与 §3.2 一致）：

```json
{
  "weather_code": "FOG",
  "visibility_nm": 0.8,
  "precipitation_mm_per_hr": 0.0,
  "wind": { "speed_kn": 3.2, "direction_from_deg": 225 },
  "surface_current": { "speed_kn": 0.4, "set_deg": 090 },
  "sea_state": 2,
  "timestamp_utc": "2026-04-17T10:22:15Z"
}
```

后端 `WeatherMessageHandler` 把 `timestamp_utc` 或到达时间作为 `updated_at` 源（两者取后者更稳定，避免 simulator 时钟偏差）。

### 4.2 `WeatherContextHolder` 并发语义

`WeatherContextHolder` 定义在 `com.whut.map.map_service.shared.context`。其职责是维护跨模块共享的环境快照，而不是维护 LLM 专属上下文；因此不放入 `llm/context`。

完全仿照 [`RiskContextHolder.java`](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextHolder.java) 的 `volatile Snapshot` 模式：

```java
@Component
public class WeatherContextHolder {
    private volatile Snapshot snapshot;
    public void update(WeatherContext ctx) { this.snapshot = new Snapshot(ctx, Instant.now()); }
    public Optional<WeatherContext> getFreshContext(Duration staleThreshold) { ... }
    record Snapshot(WeatherContext context, Instant updatedAt) {}
}
```

`getFreshContext` 在 `now - updatedAt > staleThreshold` 时返回 `Optional.empty()`。`RiskObjectMetaAssembler` 调用 `getFreshContext(Duration.ofSeconds(60))` 决定是否注入。

### 4.3 `active_alerts` 生成

在 `RiskObjectMetaAssembler.buildEnvironmentContext` 内按 weather 字段评估：

```java
List<String> alerts = new ArrayList<>();
if (weather.visibilityNm() != null && weather.visibilityNm() < 2.0) alerts.add("LOW_VISIBILITY");
if (weather.wind().speedKn() != null && weather.wind().speedKn() > 25) alerts.add("HIGH_WIND");
if (weather.precipitationMmPerHr() != null && weather.precipitationMmPerHr() > 10) alerts.add("HEAVY_PRECIPITATION");
if (weather.surfaceCurrent().speedKn() != null && weather.surfaceCurrent().speedKn() > 2.5) alerts.add("STRONG_CURRENT_SET");
```

阈值走 `@ConfigurationProperties`，默认值如上。注意不覆盖已有 alerts（未来水文 track 会追加 `SHOAL_PROXIMITY` 等到同一数组）。

### 4.4 fog overlay 的视觉边界

- opacity 线性映射，但 cap 在 0.65，避免彻底遮蔽
- 雾色 `#d2d7dc`（带轻微蓝灰），深色主题下观感更贴近真实雾
- 在目标船 / 轨迹 / 告警符号层之下，通过 MapLibre 的 `beforeId` 参数或独立 React 层叠加 + pointerEvents none 实现
- 验收必须点击测试：fog 生效时，`TargetsPanel` 目标点选不受影响

---

## 5. Validation

### 5.1 端到端

- `python simulator/weather_mqtt_publisher.py --scene fog` 启动后 15 秒内，前端 `StatusPanel` 显示 `FOG · vis 0.8 nm`
- 浏览器 devtools Network 抓包 SSE `RISK_UPDATE`，`environment_context.weather.weather_code == "FOG"`，`active_alerts` 含 `LOW_VISIBILITY`
- `--scene clear` 切换后 70 秒内（一次 MQTT 间隔 + stale 阈值宽裕），前端 fog overlay 消失

### 5.2 Stale 行为

- 停止 simulator 后 90 秒，前端 `environment.weather` 应变为 `null`，fog overlay 消失，`StatusPanel` 摘要退回默认"气象未接入"
- 重启 simulator 后 15 秒内恢复

### 5.3 性能

- fog overlay 启用后 FPS 变化 < 2%
- MQTT 订阅不引起后端主 risk pipeline 的任何回归（现有 RiskAssessment 单测全部保持绿色）

### 5.4 回归

- `useRiskStore.test.ts` 与现有 backend 测试全部通过
- 未配置 MQTT 或 topic 无消息时，系统降级为 `environment.weather == null`，不抛异常不打 ERROR 日志

---

## 6. Deferred

- 降雨 / 风场 / 水流粒子层：Step 3
- 引擎消费：Step 2
- LLM 消费与 advisory：Step 3
- 气象历史回放、导出、多源融合：后续 milestone

---

## 7. 需同步修改的文档

- [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)：Step 1 发版时扩展 `environment_context.weather` 字段与 `active_alerts` 枚举
- [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)：新增 MQTT `usv/Weather` 订阅链路在"数据来源"段补条目
- [`../../TODO.md`](../../TODO.md) 第 2 节"环境语义模型"：追加 Step 1 进度注记（不移除）
- [`WEATHER_PLAN.md`](./WEATHER_PLAN.md) §4 Step 1 状态：更新为"已完成"
- [`../hydrology/HYDROLOGY_PLAN.md`](../hydrology/HYDROLOGY_PLAN.md) §3.3：`EnvAlertCode` 枚举点首次由天气 track 填充，需在枚举定义类里注明 hydrology 后续将追加
