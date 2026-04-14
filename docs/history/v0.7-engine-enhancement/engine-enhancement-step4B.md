# Step 4B 执行计划：预测轨迹增强与前端集成

> 基于 ENGINE_ENHANCEMENT_PLAN.md §Step 4B。
> 前置条件：Step 4A 已完成（`CvPredictionResult`、`ShipTrajectoryStore`、`TargetAssembler`、`risk_score` 协议字段均已就位）。

---

## 一、背景与动机

Step 2 实现的 `CvPredictionEngine` 为纯线性外推（恒速直线），Step 4A 的 `PredictedCpaCalculator` 消费此轨迹计算预测 CPA——但两者都基于相同的线性假设，`predictedCpaNm` 与原始 `dcpaNm` 高度重合，预测修正几乎为零，`PredictedCpaCalculator` 的存在意义被架空。

此外，前端目标船轨迹当前来自 `mockDataGenerator`（`getTargetRemainingWaypoints`），与后端计算完全脱耦。

本步解决两个问题：

1. **后端**：升级 `CvPredictionEngine` 为常速转向（Constant Turn Rate，CTR）模型，利用 `ShipTrajectoryStore` 中已有的历史数据检测转向率（ROT），使预测轨迹能反映船舶实际机动趋势，让 `PredictedCpaCalculator` 的修正信号真正有效。
2. **前端**：将预测轨迹坐标通过协议传到前端，替换 mock；`TargetsPanel` 消费 `risk_score` 做同级别目标二次排序。`risk_score` 进度条展示需求暂缓到后续迭代，本步不实现 UI 展示。

---

## 二、改动清单

### Phase A：后端 — CTR 模型升级

#### A1. `TrajectoryPredictionProperties` 追加转向率阈值

在现有配置类中增加一个字段（前缀不变：`engine.trajectory-prediction`）：

```java
private double rotThresholdDegPerMin = 1.5;
// 低于此转向率视为直航噪声，退化为 CV 模型
```

#### A2. `CvPredictionEngine` 签名与实现重构

**接口变更**：

```java
// 变更前
public CvPredictionResult consume(ShipStatus message)

// 变更后
public CvPredictionResult consume(ShipStatus message, List<ShipStatus> history)
```

`history` 由调用方从 `ShipTrajectoryStore.getHistory(targetId)` 获取，包含当前帧，oldest → newest 排列。

**内部新增方法 `extractRotDegPerSec(List<ShipStatus> history)`**：

1. 从 history 过滤：COG 在 [0, 360)、`msgTime` 非 null 的点，按 `msgTime` 升序。
  - 仓库当前 `ShipStatus` 尚未包含 `qualityFlags` 字段，因此本步实现不做 `COG_JUMP` 过滤。
  - 待 Step 5B 引入 `qualityFlags` 后，再在该过滤条件中补入 `COG_JUMP` 排除逻辑（保持 null-safe）。
2. 若有效点 < 3：返回 `0.0`（退化 CV）
3. 对 COG 序列做角度解缠（unwrap）：相邻帧差超过 ±180° 时累加 ±360° 修正，确保连续转向（如 359° → 1°）被正确表示为 +2° 而非 -358°
4. 对 `(time_sec, cog_unwrapped)` 做最小二乘线性回归，斜率即 ROT（deg/sec）
5. 若 `|ROT| × 60 < rotThresholdDegPerMin`：返回 `0.0`（噪声，退化 CV）

> **设计说明（按仓库现状修订）**：由于当前 `ShipStatus` 尚无 `qualityFlags` 字段，本步无法落地 `COG_JUMP` 过滤钩子；已实现版本仅基于 COG 有效性与时间戳做筛选。该限制不影响 CTR 主流程落地，但在 Step 5B 之前，AIS 跳变帧仍可能混入回归。考虑到需要 ≥3 个有效点且 ROT 需超过阈值，单点跳变触发误判的概率较低，此为已知局限。

**线性回归公式**（无外部依赖，约 10 行）：

```java
double xMean = average(xs), yMean = average(ys);
double numerator = 0, denominator = 0;
for (int i = 0; i < n; i++) {
    numerator   += (xs[i] - xMean) * (ys[i] - yMean);
    denominator += (xs[i] - xMean) * (xs[i] - xMean);
}
return denominator < 1e-9 ? 0.0 : numerator / denominator;  // deg/sec
```

**轨迹生成分支**（在现有 `predict()` 内）：

- ROT == 0.0：保留现有 `GeoUtils.displace(lat, lon, vx*t, vy*t)` 逻辑，无变化
- ROT != 0.0：Euler 积分，步进累积位置

```java
double headingDeg = ship.getCog();
double lat = ship.getLatitude(), lon = ship.getLongitude();
for (int t = step; t <= horizon; t += step) {
    // 先用当前 heading 计算位移，再更新 heading（标准前向 Euler）
    double[] vel = GeoUtils.toVelocity(sog, headingDeg);
    double[] pos = GeoUtils.displace(lat, lon, vel[0] * step, vel[1] * step);
    lat = pos[0]; lon = pos[1];
    headingDeg = ((headingDeg + rotDegPerSec * step) % 360 + 360) % 360;
    points.add(PredictedPoint.builder()
        .latitude(lat).longitude(lon).offsetSeconds(t).build());
}
```

**顺序约束**：displace 必须在 heading 更新之前，否则第一个点将以"转向后的 heading"外推，导致整条曲线领先一步。Euler 积分在 10 min horizon、30 s step 下误差远小于 AIS 位置不确定度，不需要解析解。

#### A3. `ShipDispatcher.batchPredict()` 传入历史记录

```java
// 变更前（第 110 行附近）
results.put(ship.getId(), cvPredictionEngine.consume(ship));

// 变更后
List<ShipStatus> history = shipTrajectoryStore.getHistory(ship.getId());
results.put(ship.getId(), cvPredictionEngine.consume(ship, history));
```

注：`shipTrajectoryStore.append(message)` 在 `prepareContext()` 中已执行，历史记录此时已包含当前帧，无需额外写入。

---

### Phase B：协议与后端传递

#### B1. `EVENT_SCHEMA.md` 新增字段

`target.predicted_trajectory` 已由 `TargetAssembler.buildPredictedTrajectory()` 实现（Step 4A 期间已落地）。本步仅补充文档，说明 `prediction_type` 语义在 CTR 升级后的行为。

当前后端已输出的 envelope 结构（不变）：

```json
"predicted_trajectory": {
  "prediction_type": "cv",
  "horizon_seconds": 600,
  "points": [
    { "lat": 30.1234, "lon": 114.5678, "offset_seconds": 30 },
    { "lat": 30.1250, "lon": 114.5701, "offset_seconds": 60 }
  ]
}
```

> **关于 `prediction_type` 值**：CTR 模型是 CV 的内部实现升级，`prediction_type` 字段保持 `"cv"`，不引入新枚举值。该字段是调试辅助元数据，前端不依赖它做渲染分支决策，无需区分。

轨迹不可用时字段整体缺失（不传 null），现有合约不变。

#### B2. `schema.d.ts` 扩展

后端 envelope 结构需要对应的前端类型定义。新增：

```typescript
export interface PredictedTrajectoryPoint {
  lat: number;
  lon: number;
  offset_seconds: number;
}

export interface PredictedTrajectory {
  prediction_type: string;
  horizon_seconds: number;
  points: PredictedTrajectoryPoint[];
}
```

`RiskTarget` 新增字段：

```typescript
predicted_trajectory?: PredictedTrajectory;
```

`PredictionType` 枚举（现有，用于 `own_ship.future_trajectory`）与本字段无关，不修改。

#### B3. `TargetAssembler` — 无需修改

`buildPredictedTrajectory()` 已在 Step 4A 实施期间完整落地（`prediction_type: "cv"`、`horizon_seconds`、`points[{lat, lon, offset_seconds}]`），本步不改动 Assembler 代码。

**注**：B3 节保留是为了明确说明"此处不需要做什么"，避免实施时误判为遗漏项。

---

### Phase C：前端渲染

#### C1. `MapContainer.tsx` — 替换 mock 轨迹

定位：`buildDeckLayers()` 中 `allTargets.forEach` 循环（约第 207-244 行）。

**变更**：

```typescript
// 变更前：依赖 mockDataGenerator
if (!isTargetInTrackingRange(target.id)) return;
const waypoints = getTargetRemainingWaypoints(target.id);
if (!waypoints || waypoints.length < 2) return;
const fullPath: LonLat[] = [[target.position.lon, target.position.lat], ...waypoints.slice(0, 2)];

// 变更后：使用真实预测轨迹（注意访问 .points 子字段，与 envelope 结构对齐）
const traj = target.predicted_trajectory;
if (!traj?.points || traj.points.length < 1) return;
const fullPath: LonLat[] = [
  [target.position.lon, target.position.lat],
  ...traj.points.map((p): LonLat => [p.lon, p.lat]),
];
```

渐隐 `PathLayer`（`target-trajectories-fade`）的构建逻辑、样式、颜色不变，直接复用。

**同步清理 imports**：移除 `getTargetRemainingWaypoints`、`isTargetInTrackingRange` 的 import（若无其他引用）。

#### C2. `TargetsPanel.tsx` — `risk_score` 二次排序

```typescript
// 变更前
const sortedTargets = [...targets].sort((a, b) => {
  const riskScore = { ALARM: 4, WARNING: 3, CAUTION: 2, SAFE: 1 };
  return (riskScore[b.risk_assessment.risk_level] || 0) - (riskScore[a.risk_assessment.risk_level] || 0);
});

// 变更后
const sortedTargets = [...targets].sort((a, b) => {
  const levelScore = { ALARM: 4, WARNING: 3, CAUTION: 2, SAFE: 1 };
  const levelDiff = (levelScore[b.risk_assessment.risk_level] || 0) -
                    (levelScore[a.risk_assessment.risk_level] || 0);
  if (levelDiff !== 0) return levelDiff;
  return (b.risk_assessment.risk_score ?? 0) - (a.risk_assessment.risk_score ?? 0);
});
```

---

## 三、实现顺序

```
Phase A（后端，无外部依赖）：A1 → A2 → A3
Phase B（协议+后端）：B1（文档）+ B2（类型定义）先行，B3（Assembler）随后
Phase C（前端）：B2 完成后可与 B3 并行推进 C1 + C2
```

推荐单次实现路径（按文件维度）：

1. `TrajectoryPredictionProperties`：追加 `rotThresholdDegPerMin`
2. `CvPredictionEngine`：新增 `extractRotDegPerSec()` + CTR 分支（displace → 更新 heading 顺序），签名增加 `history` 参数
3. `ShipDispatcher.batchPredict()`：传入 history
4. `schema.d.ts`：新增 `PredictedTrajectoryPoint`、`PredictedTrajectory`，`RiskTarget` 追加 `predicted_trajectory?`
5. `EVENT_SCHEMA.md`：补充 `target.predicted_trajectory` 字段说明（`TargetAssembler` 不需改动）
6. `MapContainer.tsx`：替换 mock 轨迹（访问 `.predicted_trajectory.points`），清理 import
7. `TargetsPanel.tsx`：`risk_score` 二次排序

---

## 四、验证清单

### CTR 模型单元测试

| 场景 | 预期 |
| --- | --- |
| 历史有效点 < 3 | 退化 CV，轨迹为直线 |
| \|ROT\| < 1.5°/min | 退化 CV，轨迹为直线 |
| 已知 ROT = 3°/min 的历史序列（10 点，30 s 间隔） | 轨迹各点与手算 CTR Euler 值一致 |
| COG 跨 0°/360° 解缠（359°→361°→363°…） | ROT 为正值，轨迹向右转；不触发反向大幅修正 |
| SOG sentinel（102.3 kn）或 COG 无效 | 返回空 trajectory（现有逻辑覆盖，不回归） |
| history 为 null 或空列表 | 退化 CV，不抛异常 |

### 协议与 Assembler 测试

| 场景 | 预期 |
| --- | --- |
| 正常预测结果（trajectory 非空） | SSE 事件中 `predicted_trajectory` 为对象，含 `prediction_type`、`horizon_seconds`、`points` 三个字段；点数 = horizonSeconds / stepSeconds |
| `CvPredictionResult.trajectory` 为空 | `predicted_trajectory` 字段缺失（不是 null）；现有测试 `assembleTargetWithEmptyTrajectory` 已覆盖 |
| `CvPredictionResult` 为 null | `predicted_trajectory` 字段缺失；现有测试 `assembleTargetWithoutCvPrediction` 已覆盖 |
| 各点字段名 | `offset_seconds`（非 `offset_sec`），与 `TargetAssemblerTest` 第 43 行一致 |

### 前端集成验证

| 场景 | 预期 |
| --- | --- |
| 直航目标（ROT ≈ 0） | 前端轨迹为直线段 |
| 转向中目标（ROT 超阈值） | 前端轨迹为可见弧线 |
| 无预测数据目标 | 不渲染该目标轨迹，无 JS 异常 |
| 同级别多目标 | `TargetsPanel` 按 `risk_score` 降序排列；`risk_score` 缺失时视为 0 |

---

## 五、不做的事

- **不做精确 CTR 解析解**：Euler 积分（步长 30 s，时域 10 min）误差远小于 AIS 位置不确定度
- **不对历史点插值或重采样**：直接使用原始 AIS 上报频率
- **不显示历史尾迹（tail track）**：只传预测轨迹，历史轨迹可视化是独立功能，不在本步范围
- **不修改 `PredictedCpaCalculator`**：上游 CTR 改进后，预测 CPA 信号自动更有效
- **不修改 `TargetAssembler`**：`buildPredictedTrajectory()` 已完整实现，`prediction_type` 保持 `"cv"` 不区分内部算法
- **不在前端区分直线/弧线渲染样式**：`PathLayer` 统一样式，弧线效果来自点的空间分布
- **本步暂不展示 `risk_score` 进度条**：仅用于排序；可视化展示在后续迭代实现

---

## 六、协议影响汇总

`target.predicted_trajectory` 的后端 envelope 结构已在 Step 4A 实施期间落地，本步不新增协议字段。

现有结构（已输出，不变）：

| 字段 | 类型 | 可选 | 说明 |
| --- | --- | --- | --- |
| `predicted_trajectory` | `PredictedTrajectory`（对象） | 是 | 缺失时前端不渲染该目标轨迹 |
| `predicted_trajectory.prediction_type` | `string` | — | `"cv"`（CTR 升级后保持不变） |
| `predicted_trajectory.horizon_seconds` | `number` | — | 预测时域（秒） |
| `predicted_trajectory.points` | `PredictedTrajectoryPoint[]` | — | 预测点序列 |
| `predicted_trajectory.points[].lat` | `number` | — | 纬度 |
| `predicted_trajectory.points[].lon` | `number` | — | 经度 |
| `predicted_trajectory.points[].offset_seconds` | `number` | — | 距当前帧偏移秒数 |

本步需同步更新：

- `frontend/src/types/schema.d.ts`：新增 `PredictedTrajectoryPoint`、`PredictedTrajectory` 类型，`RiskTarget` 追加 `predicted_trajectory?: PredictedTrajectory`
- `docs/EVENT_SCHEMA.md`：补充 `target.predicted_trajectory` 字段说明（若尚未记录）
