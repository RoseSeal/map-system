## Step 2 执行规划：目标船航迹预测（CV 模型）

### Summary

实现恒速（Constant Velocity）航迹预测模型，为所有目标船输出未来 N 分钟内的预测轨迹点序列（`target.predicted_trajectory`），并建立 `ShipTrajectoryStore` 历史存储基础设施。

**管线类型变更**：`ShipDerivedOutputs` / `RiskObjectAssembler` / `TargetAssembler` 之间传递的 cv 预测结果从单个 `CvPredictionResult` 改为 `Map<String, CvPredictionResult>`（key = ship ID）。此 Map 仅流向 Assembler 层；`RiskAssessmentEngine.consume()` 在本步继续接收 `null`，其签名和逻辑均不变，等 Step 4 统一修改。

同步修正两处已有设计问题：① `cvResult` 参数被错误路由到 `OwnShipAssembler`，改为正确地路由到 `TargetAssembler`；② `refreshAfterCleanup()` 路径提取公共 `batchPredict()` 辅助方法，确保 cleanup 触发后的快照与正常触发路径一致，不丢失 `predicted_trajectory`。

`ShipTrajectoryStore` 在本步中作为纯基础设施建立（写入但不读取）；CV 模型仅消费 `ShipStatus` 最新快照做外推，不依赖历史序列——历史序列是 Step 5B 运动学校验的前置条件。

---

### Key Changes

**1. 补全 `CvPredictionResult` 字段**

文件：`engine/trajectoryprediction/CvPredictionResult.java`

```java
@Data @Builder
public class CvPredictionResult {
    private String targetId;
    private List<PredictedPoint> trajectory;  // 时间序列预测点，offsetSeconds 升序
    private Instant predictionTime;           // 预测基准时刻（优先取 msgTime，见 Key Change 5）
    private int horizonSeconds;               // 预测总时域（秒）

    @Data @Builder
    public static class PredictedPoint {
        private double latitude;
        private double longitude;
        private int offsetSeconds;  // 相对于 predictionTime 的时间偏移
    }
}
```

**2. 新增 `TrajectoryPredictionProperties`**

文件：`config/properties/TrajectoryPredictionProperties.java`（与 `ShipDomainProperties` 同包）

```java
@Data
@Component
@ConfigurationProperties(prefix = "engine.trajectory-prediction")
public class TrajectoryPredictionProperties {
    /** 预测时域，单位秒（默认 10 分钟）。 */
    private int horizonSeconds = 600;
    /** 采样步长，单位秒（默认 30 秒，产生 20 个预测点）。 */
    private int stepSeconds = 30;
}
```

在 `application.properties` 追加配置节：

```properties
# Step 2: CV trajectory prediction
engine.trajectory-prediction.horizon-seconds=600
engine.trajectory-prediction.step-seconds=30
```

**3. 新增 `ShipTrajectoryStore`**

文件：`store/ShipTrajectoryStore.java`（与 `ShipStateStore` 同包）

并发模型：`ConcurrentHashMap<String, List<ShipStatus>>`，`compute()` 块内每次写入时创建新 `List`，替换引用——copy-on-write，`getHistory()` 读到的 `List` 引用不再被 `append()` 修改，天然线程安全。`List` 创建后以 `Collections.unmodifiableList()` 包装，防止调用方意外变更。

快照语义：`append()` 在写入前对 `ShipStatus` 做 builder 拷贝，与 `ShipStateStore.snapshotOf()` 保持一致，避免历史列表持有外部可变引用。

```java
@Component
public class ShipTrajectoryStore {
    private static final int MAX_HISTORY = 20;

    private final ConcurrentHashMap<String, List<ShipStatus>> trajectories =
            new ConcurrentHashMap<>();

    /** 追加一条历史快照；超出 MAX_HISTORY 时滑动丢弃最旧一条。 */
    public void append(ShipStatus ship) {
        if (ship == null || ship.getId() == null) {
            return;
        }
        ShipStatus snapshot = snapshotOf(ship);
        trajectories.compute(ship.getId(), (id, existing) -> {
            List<ShipStatus> next = existing == null
                    ? new ArrayList<>()
                    : new ArrayList<>(existing);   // 复制旧列表
            next.add(snapshot);
            if (next.size() > MAX_HISTORY) {
                next.remove(0);
            }
            return Collections.unmodifiableList(next);  // 替换引用
        });
    }

    /**
     * 返回指定船 ID 的历史快照列表（时间升序，最旧在前）。
     * 供 Step 5B 运动学校验使用；Step 2 CV 模型不调用此方法。
     */
    public List<ShipStatus> getHistory(String shipId) {
        List<ShipStatus> list = trajectories.get(shipId);
        return list == null ? Collections.emptyList() : list;
    }

    /** 删除指定船 ID 的历史记录，随 ShipStateStore 过期清理联动调用。 */
    public void remove(String shipId) {
        trajectories.remove(shipId);
    }

    private ShipStatus snapshotOf(ShipStatus ship) {
        return ShipStatus.builder()
                .id(ship.getId())
                .role(ship.getRole())
                .longitude(ship.getLongitude())
                .latitude(ship.getLatitude())
                .sog(ship.getSog())
                .cog(ship.getCog())
                .heading(ship.getHeading())
                .msgTime(ship.getMsgTime())
                .confidence(ship.getConfidence())
                .build();
    }
}
```

`MAX_HISTORY` 内联为私有常量；Step 5B 如需可配置，届时在 `TrajectoryPredictionProperties` 中增加 `maxHistorySize` 字段，本步不预先设计。

**4. `ShipStateStore.triggerCleanupIfNeeded()` 改为返回被删 ID 集合**

文件：`store/ShipStateStore.java`

将 `purgeExpiredShips()` 和 `triggerCleanupIfNeeded()` 的返回类型从 `boolean` 改为 `Set<String>`（被删除的 ship ID 集合，无删除时返回空集）：

```java
Set<String> purgeExpiredShips(OffsetDateTime referenceTime) {
    ...
    // 原 ArrayList<String> removedShipIds 保持不变
    // 末尾改为：
    return Collections.unmodifiableSet(new HashSet<>(removedShipIds));
}

public Set<String> triggerCleanupIfNeeded() {
    ...
    // 原 return purgeExpiredShips(OffsetDateTime.now());
    // 改为：
    Set<String> removed = purgeExpiredShips(OffsetDateTime.now());
    return removed != null ? removed : Collections.emptySet();
}
```

调用方（`ShipDispatcher`）通过返回值同步清理 `ShipTrajectoryStore`，避免历史轨迹内存泄漏。

**5. `GeoUtils` 新增 `displace` 方法**

文件：`util/GeoUtils.java`

新增面向业务语义的位移方法，代替原方案中的通用 `fromXY`（后者语义模糊，将局部近似逆投影包装为通用几何基础能力）：

```java
/**
 * Returns the (latitude, longitude) obtained by displacing the given reference position
 * by the specified east and north offsets, using an equirectangular approximation.
 * Suitable for short-range displacement (river-scale distances, < 50 km).
 *
 * @param refLat      reference latitude (degrees)
 * @param refLon      reference longitude (degrees)
 * @param eastMeters  eastward displacement (meters; positive = east)
 * @param northMeters northward displacement (meters; positive = north)
 * @return double[]{latitude, longitude} of the displaced position
 */
public static double[] displace(double refLat, double refLon, double eastMeters, double northMeters) {
    double dLat = northMeters / 111320.0;
    double dLon = eastMeters / (Math.cos(Math.toRadians(refLat)) * 111320.0);
    return new double[]{refLat + dLat, refLon + dLon};
}
```

与已有 `toXY` / `toVelocity` 使用相同的等距投影系数，精度一致。`refLat` 在整个预测时域内取船舶当前纬度（固定），内河短期预测误差可接受。

**6. 实现 `CvPredictionEngine.predict(ShipStatus)`**

文件：`engine/trajectoryprediction/CvPredictionEngine.java`

- 注入 `TrajectoryPredictionProperties`（构造函数注入，与 `ShipDomainEngine` 同模式）
- `consume(ShipStatus)` 签名保持不变，改为调用 `predict(ShipStatus)`
- `predictionTime` 优先取 `ship.getMsgTime().toInstant()`，缺失时 fallback 到 `Instant.now()`

```java
private static final double AIS_SOG_NOT_AVAILABLE_KN = 102.3;

private CvPredictionResult predict(ShipStatus ship) {
    // predictionTime: msgTime-driven，与系统其他时序语义一致
    Instant predictionTime = (ship.getMsgTime() != null)
            ? ship.getMsgTime().toInstant()
            : Instant.now();

    double sog = ship.getSog();
    // 无效 SOG 返回空轨迹；前端不渲染该字段，不抛异常
    if (Double.isNaN(sog) || sog < 0 || sog >= AIS_SOG_NOT_AVAILABLE_KN) {
        return CvPredictionResult.builder()
                .targetId(ship.getId())
                .trajectory(Collections.emptyList())
                .predictionTime(predictionTime)
                .horizonSeconds(props.getHorizonSeconds())
                .build();
    }

    // sog=0 合法：静止船产生重复当前位置的点序列
    double[] velocity = GeoUtils.toVelocity(sog, ship.getCog()); // [vx=east m/s, vy=north m/s]
    int step = props.getStepSeconds();
    int horizon = props.getHorizonSeconds();

    List<CvPredictionResult.PredictedPoint> points = new ArrayList<>();
    for (int t = step; t <= horizon; t += step) {
        double[] latLon = GeoUtils.displace(
                ship.getLatitude(), ship.getLongitude(),
                velocity[0] * t, velocity[1] * t
        );
        points.add(CvPredictionResult.PredictedPoint.builder()
                .latitude(latLon[0])
                .longitude(latLon[1])
                .offsetSeconds(t)
                .build());
    }

    return CvPredictionResult.builder()
            .targetId(ship.getId())
            .trajectory(points)
            .predictionTime(predictionTime)
            .horizonSeconds(horizon)
            .build();
}

public CvPredictionResult consume(ShipStatus message) {
    log.debug("CV prediction for target MMSI: {}", message.getId());
    return predict(message);
}
```

`AIS_SOG_NOT_AVAILABLE_KN` 与 `ShipDomainEngine` 各自独立定义，Step 6 可统一提取至共享常量类，本步不预先移动。

**7. `ShipDerivedOutputs` 类型变更**

文件：`pipeline/ShipDerivedOutputs.java`

```java
record ShipDerivedOutputs(
        ShipDomainResult shipDomainResult,
        Map<String, CvPredictionResult> cvPredictionResults,   // 从单个改为 Map
        Map<String, CpaTcpaResult> cpaResults
) {}
```

**8. `ShipDispatcher` 联动**

文件：`pipeline/ShipDispatcher.java`

**(8a) 构造函数注入 `ShipTrajectoryStore`：**

```java
private final ShipTrajectoryStore shipTrajectoryStore;
// 追加到构造函数参数列表
```

**(8b) `prepareContext()` 写入历史存储，联动清理 `ShipTrajectoryStore`：**

```java
private ShipDispatchContext prepareContext(ShipStatus message) {
    if (message.getRole() == ShipRole.UNKNOWN) { ... }
    if (!shipStateStore.update(message)) { return null; }

    Set<String> removedIds = shipStateStore.triggerCleanupIfNeeded();   // 改：接收被删 ID
    removedIds.forEach(shipTrajectoryStore::remove);                    // 新增：同步清理历史
    shipTrajectoryStore.append(message);                                // 新增：写入当前快照
    return new ShipDispatchContext(message, shipStateStore.getOwnShip(), shipStateStore.getAll());
}
```

**(8c) 提取 `batchPredict()` 辅助方法：**

`runDerivations()` 和 `refreshAfterCleanup()` 共用同一批量预测逻辑，提取为私有方法：

```java
private Map<String, CvPredictionResult> batchPredict(String ownShipId, Collection<ShipStatus> allShips) {
    Map<String, CvPredictionResult> results = new HashMap<>();
    for (ShipStatus ship : allShips) {
        if (ship == null || ship.getId() == null || ship.getId().equals(ownShipId)) {
            continue;
        }
        results.put(ship.getId(), cvPredictionEngine.consume(ship));
    }
    return results;
}
```

**(8d) `runDerivations()` 使用 `batchPredict()`：**

```java
private ShipDerivedOutputs runDerivations(ShipDispatchContext context) {
    ShipDomainResult shipDomainResult = null;
    if (context.hasOwnShip()) {
        shipDomainResult = shipDomainEngine.consume(context.ownShip());
    }

    String ownShipId = context.hasOwnShip() ? context.ownShip().getId() : null;
    Map<String, CvPredictionResult> cvPredictionResults =
            batchPredict(ownShipId, context.allShips());    // 替换原单船预测逻辑

    Map<String, CpaTcpaResult> cpaResults = cpaTcpaBatchCalculator.calculateAll(
            context.ownShip(), context.allShips());

    return new ShipDerivedOutputs(shipDomainResult, cvPredictionResults, cpaResults);
}
```

**(8e) `buildRiskSnapshot()` 分叉传递：**

```java
// RiskAssessmentEngine 本步保持不变，继续接收 null
RiskAssessmentResult riskResult = riskAssessmentEngine.consume(
        context.ownShip(), context.allShips(), outputs.cpaResults(),
        outputs.shipDomainResult(), null           // cv 结果在 Step 4 前不传入风险引擎
);

// Map 仅流向 Assembler 层
RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
        context.ownShip(), context.allShips(), outputs.cpaResults(),
        riskResult, outputs.shipDomainResult(), outputs.cvPredictionResults()
);
```

**(8f) `refreshAfterCleanup()` 批量预测修正：**

```java
public void refreshAfterCleanup() {
    ShipStatus ownShip = shipStateStore.getOwnShip();
    if (ownShip == null) { return; }

    Collection<ShipStatus> allShips = shipStateStore.getAll().values();
    ShipDomainResult domainResult = shipDomainEngine.consume(ownShip);

    // 与 runDerivations() 保持一致，避免 cleanup 触发后 predicted_trajectory 消失
    Map<String, CvPredictionResult> cvPredictionResults = batchPredict(ownShip.getId(), allShips);

    Map<String, CpaTcpaResult> cpaResults = cpaTcpaBatchCalculator.calculateAll(ownShip, allShips);
    RiskAssessmentResult riskResult = riskAssessmentEngine.consume(
            ownShip, allShips, cpaResults, domainResult, null);
    RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
            ownShip, allShips, cpaResults, riskResult, domainResult, cvPredictionResults);
    ...
}
```

**9. `RiskObjectAssembler` 签名及路由修正**

文件：`pipeline/assembler/RiskObjectAssembler.java`

```java
public RiskObjectDto assembleRiskObject(
        ShipStatus ownShip,
        Collection<ShipStatus> allShips,
        Map<String, CpaTcpaResult> cpaResults,
        RiskAssessmentResult riskResult,
        ShipDomainResult domainResult,
        Map<String, CvPredictionResult> cvResults    // 类型：单个 → Map
) {
    ...
    return RiskObjectDto.builder()
            ...
            .ownShip(ownShipAssembler.assemble(ownShip, domainResult))          // 移除 cvResult
            .targets(targetAssembler.assembleTargets(
                    ownShip, allShips, cpaResults, riskResult, cvResults))      // 新增 cvResults
            ...
            .build();
}
```

**10. `OwnShipAssembler` 移除 `cvResult` 参数**

文件：`pipeline/assembler/riskobject/OwnShipAssembler.java`

```java
// 修改前
public Map<String, Object> assemble(ShipStatus ownShip, ShipDomainResult domainResult, CvPredictionResult cvResult)

// 修改后
public Map<String, Object> assemble(ShipStatus ownShip, ShipDomainResult domainResult)
```

`cvResult` 从未被 `OwnShipAssembler` 使用。`own_ship.future_trajectory` 是本船占位符，与 CV 模型的目标船预测无关，本步保持 `prediction_type: "linear"` 存根不变。

**11. `TargetAssembler` 填充 `predicted_trajectory`**

文件：`pipeline/assembler/riskobject/TargetAssembler.java`

**(11a) `assembleTargets()` 增加 `Map<String, CvPredictionResult>` 参数：**

```java
public List<Map<String, Object>> assembleTargets(
        ShipStatus ownShip,
        Collection<ShipStatus> allShips,
        Map<String, CpaTcpaResult> cpaResults,
        RiskAssessmentResult riskResult,
        Map<String, CvPredictionResult> cvResults    // 新增
) {
    ...
    CvPredictionResult cvResult = (cvResults == null) ? null : cvResults.get(ship.getId());
    targets.add(assembleTarget(ownShip, ship, cpaResult, assessment, cvResult));
    ...
}
```

**(11b) `assembleTarget()` 写入 `predicted_trajectory` 字段：**

```java
public Map<String, Object> assembleTarget(
        ShipStatus ownShip, ShipStatus targetShip,
        CpaTcpaResult cpaResult, TargetRiskAssessment assessment,
        CvPredictionResult cvResult    // 新增
) {
    ...
    // 在 target Map 末尾条件写入；null 或空轨迹时不写入该 key
    if (cvResult != null && cvResult.getTrajectory() != null
            && !cvResult.getTrajectory().isEmpty()) {
        target.put("predicted_trajectory", buildPredictedTrajectory(cvResult));
    }
    return target;
}

private Map<String, Object> buildPredictedTrajectory(CvPredictionResult cvResult) {
    List<Map<String, Object>> points = new ArrayList<>();
    for (CvPredictionResult.PredictedPoint p : cvResult.getTrajectory()) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("lat", p.getLatitude());
        point.put("lon", p.getLongitude());
        point.put("offset_seconds", p.getOffsetSeconds());
        points.add(point);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("prediction_type", "cv");
    result.put("horizon_seconds", cvResult.getHorizonSeconds());
    result.put("points", points);
    return result;
}
```

**12. 协议文档与前端类型同步**

文件：`docs/EVENT_SCHEMA.md`，`frontend/src/types/schema.d.ts`

在 `EVENT_SCHEMA.md` 的 `targets[*]` 结构中追加 `predicted_trajectory` 可选字段定义及示例。

在 `schema.d.ts` 的 `RiskTarget` 接口追加：

```typescript
export interface PredictedPoint {
  lat: number;
  lon: number;
  offset_seconds: number;
}

export interface PredictedTrajectory {
  prediction_type: 'cv';
  horizon_seconds: number;
  points: PredictedPoint[];
}

export interface RiskTarget {
  id: string;
  tracking_status: TrackingStatus;
  position: Position;
  vector: TargetVector;
  risk_assessment: RiskAssessment;
  predicted_trajectory?: PredictedTrajectory;   // 新增，可选
}
```

---

### Constraints

- **`RiskAssessmentEngine` 本步不变**：`buildRiskSnapshot()` 向 `riskAssessmentEngine.consume()` 的第五参数显式传 `null`，其签名、逻辑均不修改。`Map<String, CvPredictionResult>` 仅在 Assembler 链中流转，Step 4 统一改签名。
- **`ShipTrajectoryStore` 仅作基础设施建立**：本步只写不读；`CvPredictionEngine` 不注入 `ShipTrajectoryStore`，Step 5B 才读取历史序列。
- **cleanup 联动在本步完成**：`triggerCleanupIfNeeded()` 改为返回 `Set<String>`，`prepareContext()` 在拿到被删 ID 后同步调用 `shipTrajectoryStore.remove()`，不留内存泄漏到 Step 6。
- **`refreshAfterCleanup()` 与正常路径对称**：两条路径均调用 `batchPredict()`，消除 cleanup 触发后 `predicted_trajectory` 消失的字段抖动。
- **AIS 无效 SOG 返回空轨迹**：`sog < 0 || sog >= 102.3 || NaN` → `trajectory=[]`，Assembler 静默跳过字段写入，不抛异常。`sog=0` 为有效值，产生静止点序列。
- **`predictionTime` 语义**：优先 `ship.getMsgTime().toInstant()`（消息时间驱动），msgTime 为 null 时 fallback 到 `Instant.now()`，保持与系统时序语义一致。
- **`clamp` 不提取**：`CvPredictionEngine` 不使用 clamp（无速度因子计算），不触发提取条件。

---

### Protocol Impact

目标船协议结构 `target` 新增可选字段 `predicted_trajectory`（轨迹有效时才出现）：

```json
{
  "id": "...",
  "tracking_status": "tracking",
  "position": { "lat": 30.12, "lon": 114.56 },
  "vector": { "speed_kn": 8.0, "course_deg": 045.0 },
  "risk_assessment": { ... },
  "predicted_trajectory": {
    "prediction_type": "cv",
    "horizon_seconds": 600,
    "points": [
      { "lat": 30.1213, "lon": 114.5612, "offset_seconds": 30 },
      { "lat": 30.1226, "lon": 114.5624, "offset_seconds": 60 }
    ]
  }
}
```

字段缺失时不影响已有渲染逻辑，前端可选消费。

---

### Test Plan

- **`CvPredictionEngineTest`**（新增）

  | 场景 | 输入 | 期望 |
  |------|------|------|
  | 正北航行 | sog=8.0 kn, cog=0° | 每点经度不变，纬度按等距外推，offsetSeconds 等差 |
  | 正东航行 | sog=8.0 kn, cog=90° | 每点纬度不变，经度增加 |
  | 静止船 | sog=0 | trajectory 非空，所有点 lat/lon 等于当前位置 |
  | AIS sentinel | sog=102.3 | trajectory 为空列表，不抛异常 |
  | 负值 SOG | sog=-1.0 | trajectory 为空列表 |
  | NaN SOG | sog=NaN | trajectory 为空列表 |
  | predictionTime 来源 | msgTime 非 null | predictionTime 等于 msgTime.toInstant() |
  | predictionTime fallback | msgTime=null | predictionTime 接近 Instant.now()（误差 < 1s） |
  | 点数 | horizon=60, step=30 | trajectory.size()==2，offsetSeconds=[30,60] |
  | targetId 传递 | shipId="12345" | result.targetId=="12345" |

- **`GeoUtilsTest` 补充**（在已有测试文件中追加）

  - `displace(lat, lon, 0, 0)` 应返回 `[lat, lon]`
  - `displace(30.0, 114.0, 1000, 0)` 纬度不变，经度增加约 0.0102°
  - 往返验证：`displace(lat, lon, dx, dy)` 与 `toXY` 逆投影结果吻合（误差 < 0.0001°）

- **`ShipTrajectoryStoreTest`**（新增）

  - `append` 后 `getHistory` 返回非空列表，包含写入的 ship ID
  - 超出 MAX_HISTORY（20）条时，最旧一条被丢弃
  - `remove` 后 `getHistory` 返回空列表
  - `getHistory` 返回的 `List` 是不可变的（调用 `add()` 抛 `UnsupportedOperationException`）
  - 快照独立性：写入后修改原 `ShipStatus` 对象，`getHistory` 返回的值不受影响（验证 snapshotOf 拷贝）

- **`TargetAssemblerTest`**（新增或补充）

  - 传入有效 `CvPredictionResult`（2 点）→ 目标 Map 包含 `predicted_trajectory`，`points.size()==2`，`prediction_type=="cv"`
  - 传入 `cvResults=null` → 目标 Map 不含 `predicted_trajectory` 键
  - 传入 `trajectory=[]` 的 `CvPredictionResult` → 目标 Map 不含 `predicted_trajectory` 键

- **`OwnShipAssemblerTest` 回归**

  - 签名变更后编译通过，已有测试继续通过

- **`ShipDispatcherTest` 补充场景**

  - **批量预测路径**：构造 2 艘目标船 + 1 艘本船上下文，验证 `cvPredictionResults` Map 包含 2 个 key，不包含 ownShip ID
  - **历史写入路径**：`dispatch()` 调用后 `shipTrajectoryStore.getHistory(message.getId())` 非空
  - **cleanup 联动**：`triggerCleanupIfNeeded()` 返回非空 ID 集合时，对应 ID 从 `ShipTrajectoryStore` 中移除

- **编译回归**：`./mvnw -q -DskipTests compile` 无错误，`ShipDispatcherTest` / `OwnShipAssemblerTest` 原有测试继续通过。

---

### Assumptions

- `GeoUtils.toVelocity(sog, cog)` 已返回 `[vx=east m/s, vy=north m/s]`，与 `displace(refLat, refLon, vx*t, vy*t)` 参数对应。
- `ShipStatus.getId()` 在 `ShipStateStore` 中 ownShip 的 key 为 `"ownShip"`；`batchPredict()` 按 `ownShip.getId()` 判断跳过本船，与 ownShip 在 map 中的存储 key 无关。
