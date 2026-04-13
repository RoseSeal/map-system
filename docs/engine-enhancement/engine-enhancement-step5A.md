# Step 5A: AIS 协议层质量校验执行方案

> 文档状态：就绪
> 对应 ARCHITECTURE.md 与 ENGINE_ENHANCEMENT_PLAN.md 中的 Step 5A

## 1. 目标与范围

本执行方案旨在 `AisMessageMapper` 中引入针对 AIS 协议特征的来源特定校验，产出具体的质量标志（`qualityFlags`）和动态计算的置信度（`confidence`），为后续风险评估提供数据质量依据。

**范围限定**：本方案（Step 5A）仅处理通过单帧报文即可判断的协议层异常，包括字段缺失、取值越界等问题。需要依赖历史状态才能发现的运动学异常（如位置跳变、航速突变）将由后续的 Step 5B 负责处理。

## 2. 详细设计与实现步骤

### 2.1 领域模型扩展

**新增 `QualityFlag` 枚举**

在 `com.whut.map.map_service.domain` 包下新增 `QualityFlag` 枚举类，定义协议层（5A）的质量异常标志。5B 的运动学标志将在 Step 5B 实施时补充至此枚举。

```java
package com.whut.map.map_service.domain;

public enum QualityFlag {
    // 5A：协议层
    MISSING_HEADING,        // heading 缺失（AIS 511）
    SPEED_OUT_OF_RANGE,     // 航速超出合理范围（内河上限 30 kn）
    POSITION_OUT_OF_RANGE,  // 经纬度超出有效范围
    MISSING_TIMESTAMP       // 时间戳缺失或解析失败
}
```

**修改 `ShipStatus` 实体**

在 `ShipStatus` 类中新增 `qualityFlags` 字段，以集合形式存储当前帧附带的质量问题。空集合表示无协议层异常。

```java
private java.util.Set<QualityFlag> qualityFlags;
```

### 2.2 `AisMessageMapper` 校验逻辑增强

重构 `AisMessageMapper.toDomain(MqttAisDto)` 方法，在构建 `ShipStatus` 前完成以下协议层校验与置信度计算：

**置信度计算规则**

以 `1.0` 为基础置信度，按各规则独立扣减后，再与来源配置上限取较小值：

```
finalConfidence = Math.min(
    Math.max(0.0, 1.0 - totalDeduction),
    aisProperties.getConfidence()   // 数据来源的可信度上限，由配置决定
)
```

`aisProperties.getConfidence()` 保留其原语义：作为当前 AIS 数据来源的可信度上限，不同接入源可独立配置。协议层扣减在此上限内进一步细化。

**逐项规则校验**

在 Mapper 中定义以下命名常量：

```java
private static final double DEDUCTION_MISSING_HEADING     = 0.1;
private static final double DEDUCTION_SPEED_OUT_OF_RANGE  = 0.3;
private static final double DEDUCTION_POSITION_OUT_OF_RANGE = 0.5;
private static final double DEDUCTION_MISSING_TIMESTAMP   = 0.2;
```

各规则及对应扣减：

- **Heading 缺失**：`MqttAisDto.heading` 为 511（AIS 协议无效值，已转为 null）时，添加 `MISSING_HEADING`，扣减 `DEDUCTION_MISSING_HEADING`。
- **航速越界**：`sog < 0` 或 `sog > 30`（内河航速上限 30 kn），添加 `SPEED_OUT_OF_RANGE`，扣减 `DEDUCTION_SPEED_OUT_OF_RANGE`。
- **位置越界**：`longitude` 不在 `[-180, 180]` 或 `latitude` 不在 `[-90, 90]`，添加 `POSITION_OUT_OF_RANGE`，扣减 `DEDUCTION_POSITION_OUT_OF_RANGE`。
- **时间戳缺失**：`msgTime` 为 null（DTO 字段缺失或解析失败），添加 `MISSING_TIMESTAMP`，扣减 `DEDUCTION_MISSING_TIMESTAMP`。

> **关于 null 时间戳帧的后续处理**：携带 `MISSING_TIMESTAMP` 标志的帧，若对应船舶已存在于 `ShipStateStore` 中（非首帧），Store 的现有逻辑会拒绝写入（`newTime == null` → 返回 `existing`），该帧不会更新存储状态，标志在内部流转中仅对首帧生效。此为现有 Store 行为，5A 不修改。

### 2.3 `ShipStateStore` 等时间戳拦截修复

**当前问题**：`update()` 方法的条件 `!newTime.isBefore(existingTime)` 在 `newTime == existingTime`（相同时间戳）时为 true，会刷新 `lastSeenAt`，导致重复投递的相同帧延长目标存活时间，干扰 Ghost Ship 清理。

**修复**：在 `update()` 中将等时间戳视为重复报文，不刷新 `lastSeenAt`：

```java
// 修改前：
if (!newTime.isBefore(existingTime)) {
    updated.set(true);
    return new TrackedShip(snapshotOf(ship), now);
}

// 修改后：
if (newTime.isEqual(existingTime)) {
    return existing;  // 重复帧，不刷新 lastSeenAt
}
if (newTime.isAfter(existingTime)) {
    updated.set(true);
    return new TrackedShip(snapshotOf(ship), now);
}
```

**`snapshotOf` 补全**：`ShipStateStore.snapshotOf()` 需补充 `qualityFlags` 字段的拷贝：

```java
.qualityFlags(ship.getQualityFlags() == null ? null : new java.util.HashSet<>(ship.getQualityFlags()))
```

## 3. 影响范围评估

- **新增类**：`QualityFlag`。
- **修改类**：
  - `ShipStatus`：增加 `qualityFlags` 字段。
  - `AisMessageMapper`：实现协议层校验与置信度计算。
  - `ShipStateStore`：修复等时间戳帧的 `lastSeenAt` 刷新问题；修复 `snapshotOf` 未拷贝 `qualityFlags`。
- **前端协议影响**：`qualityFlags` 仅作为内部流转字段，不直接暴露给前端。

## 4. 测试与验证要求

1. **协议层异常验证**：
   - 构造 SOG 为 35 的报文，验证生成对象携带 `SPEED_OUT_OF_RANGE` 且置信度低于基准值。
   - 构造纬度为 95 的报文，验证生成对象携带 `POSITION_OUT_OF_RANGE` 且置信度大幅下降。
   - 构造 `heading == 511` 的报文，验证携带 `MISSING_HEADING` 标志。
   - 构造 `msgTime == null` 的报文，验证携带 `MISSING_TIMESTAMP` 标志。
   - 构造完全正常的基准报文，验证 `qualityFlags` 为空，置信度为 `aisProperties.getConfidence()`。
2. **来源上限约束验证**：
   - 配置 `aisProperties.confidence = 0.7`，构造完全正常报文，验证置信度为 0.7（非 1.0）。
   - 构造 MISSING_HEADING 报文（扣减 0.1），验证最终置信度为 `min(0.9, 0.7) = 0.7`。
3. **等时间戳拦截验证**：
   - 连续两次以相同 `msgTime` 调用 `ShipStateStore.update()`，验证第二次调用返回 false（`updated = false`）且不更新 `lastSeenAt`，从而允许正常的超时清理流程将该目标淘汰。
4. **快照完整性验证**：
   - 确认 `ShipStateStore.snapshotOf()` 能够完整携带原对象的 `qualityFlags`（含多个标志的情况）。
