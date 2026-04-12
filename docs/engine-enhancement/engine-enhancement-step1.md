## Step 1 执行规划：本船安全领域

### Summary

实现 `ShipDomainEngine` 的动态椭圆域计算，替代 `OwnShipAssembler` 中四个方向的硬编码尺寸。本步新增 `ShipDomainProperties` 配置类和 `ShipDomainResult` 字段定义，`ShipDomainEngine.calculate()` 实现基于航速的缩放公式并**永不返回 null**（异常 SOG 内部兜底为基准值）；`OwnShipAssembler` 无条件消费 `ShipDomainResult`，不再保有硬编码 fallback。

`ShipDispatcher.runDerivations()` 的触发条件需同步修正：**本船安全领域是当前快照 ownShip 的派生结果，应按 `context.ownShip()` 计算，而非按 `context.message().getRole()` 决定**。不修正则 TARGET_SHIP 触发的正常快照会拿到 null domainResult，导致 `OwnShipAssembler` NPE。`refreshAfterCleanup()` 同理。不涉及 Step 4 的域侵入检测，不修改事件协议结构。

---

### Key Changes

**1. 补全 `ShipDomainResult` 字段**

文件：`engine/safety/ShipDomainResult.java`

```java
@Data @Builder
public class ShipDomainResult {
    public static final String SHAPE_ELLIPSE = "ellipse";

    private double foreNm;
    private double aftNm;
    private double portNm;
    private double stbdNm;
    private String shapeType;
}
```

`SHAPE_ELLIPSE` 常量作为唯一字面量定义点，Assembler 通过该常量引用，消除魔法字符串。

**2. 新增 `ShipDomainProperties`**

文件：`config/properties/ShipDomainProperties.java`（与 `RiskAssessmentProperties` 同包）

```java
@Data
@Component
@ConfigurationProperties(prefix = "engine.ship-domain")
public class ShipDomainProperties {
    // 基准值与历史硬编码严格对齐，确保 Step 1 引入的唯一变量是"动态缩放"
    private double baseForeNm       = 0.5;
    private double baseAftNm        = 0.1;
    private double basePortNm       = 0.2;
    private double baseStbdNm       = 0.2;
    private double referenceSpeedKn = 8.0;
    private double minSpeedFactor   = 0.5;
    private double maxSpeedFactor   = 2.0;
}
```

在 `application.properties` 追加 `engine.ship-domain.*` 一节，写入与默认值一致的配置项，便于运维调优。

**3. 实现 `ShipDomainEngine.calculate(ShipStatus)`**

文件：`engine/safety/ShipDomainEngine.java`

- 将 `ShipDomainProperties` 注入构造函数
- `calculate()` 改为 `private ShipDomainResult calculate(ShipStatus shipStatus)`
- 计算逻辑：

  ```
  // SOG 防御性校验（AIS 102.3 = not available sentinel，负值和 NaN 均视为无效）
  double sog = shipStatus.getSog();
  if (Double.isNaN(sog) || sog < 0 || sog >= 102.3) {
      sog = props.getReferenceSpeedKn();   // speedFactor → 1.0，使用基准值
  }

  speedFactor = clamp(sog / referenceSpeedKn, minSpeedFactor, maxSpeedFactor)
  foreNm  = baseForeNm  × speedFactor
  aftNm   = baseAftNm   × speedFactor
  portNm  = basePortNm  × speedFactor
  stbdNm  = baseStbdNm  × speedFactor
  ```

- `clamp` 用 `Math.min(Math.max(value, min), max)` 内联（当前仅本步一处使用，Step 2 出现第二处时再统一提取）
- `shapeType` 使用 `ShipDomainResult.SHAPE_ELLIPSE`
- `consume(ShipStatus)` 直接返回 `calculate(message)`，**不再返回 null**

**4. `OwnShipAssembler` 无条件消费 `ShipDomainResult`**

文件：`pipeline/assembler/riskobject/OwnShipAssembler.java`

- 移除 null-check 分支及其硬编码默认值，直接读取 domain result 字段：

  ```java
  dimensions.put("fore_nm", domainResult.getForeNm());
  dimensions.put("aft_nm",  domainResult.getAftNm());
  dimensions.put("port_nm", domainResult.getPortNm());
  dimensions.put("stbd_nm", domainResult.getStbdNm());
  safetyDomain.put("shape_type", domainResult.getShapeType());
  ```

**5. `ShipDispatcher.runDerivations()` 触发条件修正**

文件：`pipeline/ShipDispatcher.java`

当前代码只在 `message.role == OWN_SHIP` 时计算 `shipDomainResult`，但 `buildRiskSnapshot()` 无论触发消息是谁都会组装 `own_ship`。移除 null-check 后 TARGET_SHIP 触发路径会 NPE。

修正：ship domain 是 ownShip 当前状态的派生结果，与触发消息无关，改为只要 `context.hasOwnShip()` 就计算：

```java
// 修改前
if (context.message().getRole() == ShipRole.OWN_SHIP) {
    shipDomainResult = shipDomainEngine.consume(context.message());
}

// 修改后
if (context.hasOwnShip()) {
    shipDomainResult = shipDomainEngine.consume(context.ownShip());
}
```

`TARGET_SHIP` 的 `cvPredictionResult` 分支保持不变（按 `message.role` 判断是正确的）。

**`refreshAfterCleanup()` 同步更新**：该路径不经过 `runDerivations()`，需单独调用：

```java
ShipDomainResult domainResult = shipDomainEngine.consume(ownShip);

RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
    ownShip, allShips, cpaResults, riskResult, domainResult, null);
```

---

### Constraints

- **不实现域侵入检测**：`ShipDomainResult` 仅输出四方向半径，`RiskAssessmentEngine` 在本步保持不变。
- **domain 计算与 `message.role` 解耦**：`ShipDomainEngine` 始终消费 `context.ownShip()`，`cvPredictionEngine` 仍按 `message.role == TARGET_SHIP` 触发，两者逻辑独立。
- **不修改事件协议**：`safety_domain.dimensions` 字段已存在于协议中，值由硬编码变为动态，不新增字段。
- **不提取 `clamp` 工具方法（本步）**：当前仅 `ShipDomainEngine` 一处使用，内联即可；Step 2 出现第二处时再统一提取至 `MathUtils`。
- **Step 4 前置约束（前瞻）**：本步采用的四参数非对称椭圆（fore/aft/port/stbd 四个方向半径不等）决定了本船位置并不在几何中心。Step 4 的 `DomainPenetrationCalculator` 实现域侵入检测时，必须先将目标船转换到本船 body frame，然后按象限动态选轴（`dy_body >= 0` 取 foreNm，否则 aftNm；`dx_body >= 0` 取 stbdNm，否则 portNm），不能套用标准中心对称椭圆方程。该逻辑已在 ENGINE_ENHANCEMENT_PLAN Step 4 域侵入检测小节中明确，此处作为前瞻约束记录。
- **SOG 防御校验属于 Engine 层**：`calculate()` 内部的 SOG 异常拦截（NaN / <0 / >=102.3）是 Engine 层的 defensive programming，独立于 Step 5A 协议层 `qualityFlags` 校验，两者职责不同，互不替代。

---

### Test Plan

- **单元测试 `ShipDomainEngineTest`**（新增）

  | 场景 | 输入 sog | 期望 speedFactor | 验证 |
  |------|---------|-----------------|------|
  | 参考速 | 8.0 kn | 1.0 | fore = baseFore × 1.0 |
  | 低速下限 | 0.0 kn | 0.5（clamp 到 min） | fore = baseFore × 0.5，不返回 null |
  | 高速上限 | 20.0 kn | 2.0（clamp 到 max） | fore = baseFore × 2.0 |
  | 中间速度 | 12.0 kn | 1.5 | 验证线性缩放 |
  | AIS sentinel | 102.3 kn | 1.0（fallback to refSpeed） | fore = baseFore × 1.0 |
  | 超出 sentinel | 200.0 kn | 1.0（fallback） | 不得被 clamp 放大 |
  | 负值 SOG | -1.0 kn | 1.0（fallback） | 防御性处理 |
  | NaN SOG | NaN | 1.0（fallback） | 不抛异常 |
  | shapeType | 任意 | — | 结果的 `shapeType` 等于 `ShipDomainResult.SHAPE_ELLIPSE` |

- **`OwnShipAssemblerTest`**（新增或补充）

  - 传入有效 `ShipDomainResult` → 四方向字段来自 result，`shape_type` 等于 `SHAPE_ELLIPSE` 常量

- **`ShipDispatcherTest` 补充场景**（回归关键路径）

  - **TARGET_SHIP 触发路径**：构造 ownShip 已存在、当前消息为 TARGET_SHIP 的场景，验证输出快照中 `own_ship.safety_domain` 来自真实 `ShipDomainResult`（非 null，非硬编码默认值）
  - **`refreshAfterCleanup()` 路径**：触发 cleanup 刷新，验证产出快照中 `own_ship.safety_domain` 同样来自真实 `ShipDomainResult`

- **编译回归**：`./mvnw -q -DskipTests compile` 无错误，`ShipDispatcherTest` 原有测试继续通过。

---

### Assumptions

- `ShipStatus.getSog()` 单位为海里/小时（kn），与 `referenceSpeedKn` 单位一致。
- `ShipDomainEngine` 不做单位转换校验；单位一致性由数据入口（`AisMessageMapper`）保证。
