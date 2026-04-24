# Agent Step 2：工具注册表与查询工具

> 文档状态：active
> 最后更新：2026-04-23
> 执行状态：completed
> 所属 track：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md)
> 对应总览：`AGENT_LOOP_PLAN.md` §4 Step 2
> 目标：实现无副作用的 agent 工具层，建立统一注册与调度机制，并交付 advisory / chat agent 共用的基础查询工具。

---

## 1. Summary

Step 2 不实现 `AgentLoopOrchestrator`，也不直接改造任何 SSE / WebSocket 协议。其职责是把 Step 0 提供的冻结快照能力变成 LLM 可调用的、稳定的、可测试的工具接口：

1. 建立 `AgentTool` 与 `AgentToolRegistry`
2. 为工具调用提供确定性的参数校验、错误返回和结果序列化
3. 实现四个 Step 2 基线工具：
   - `get_risk_snapshot`
   - `get_top_risk_targets`
   - `get_target_detail`
   - `get_own_ship_state`
4. 保证所有工具只读取 `AgentSnapshot`，或基于 `AgentSnapshot` 做纯计算，不触碰 live store

本步骤完成后，Step 3 的 advisory 编排器只需“把 provider 的 tool call 接到注册表”；Step 5 也可以在同一注册表内追加规则查询与操纵评估工具，而不重做底层分发。

总览中的完成边界在本文件中保持不变：

- 完成 `AgentToolRegistry` 与四个 Step 2 基线查询工具
- 所有工具均只从 `AgentSnapshot` 读取，或基于 `AgentSnapshot` 做无副作用纯计算
- 交付独立单元测试，覆盖工具行为与注册表调度
- `QueryRegulatoryContextTool` 与 `EvaluateManeuverTool` 不属于本步骤完成标准

本步骤依赖 Step 0 的 `AgentSnapshot` 类型和 Step 1 的工具调用合同类型，但不提前实现 Step 3 的 loop 编排或 Step 5 的规则增强。

---

## 2. Current State And Step Delta

当前代码已经具备 Step 2 的前置基础，但工具层仍完全缺失：

- `llm/agent/AgentSnapshot.java`、`AgentSnapshotFactory.java`、两类 deep copier 与 `SceneRiskStateTracker` 已落地，说明“冻结快照再推理”的基础设施已经存在
- `LlmClient` 仍只有 `chat(List<LlmChatMessage>)`；仓库中尚不存在 `ToolDefinition`、`ToolCall`、`ToolResult`、`chatWithTools(...)` 或 `AgentMessage` 等 Step 1 合同类型
- `RiskContextFormatter` 当前只负责生成人类可读文本；其输出不具备稳定 schema，不能直接充当 tool result
- `AgentSnapshot` 当前只冻结 `LlmRiskContext + Map<String, TargetDerivedSnapshot>`；这足以支撑目标详情与场景摘要查询，但 **不直接包含 own-ship safety domain 尺寸**
- `ShipDomainEngine` 已是纯函数式 Spring 组件，输入 `ShipStatus` 即可重算 `ShipDomainResult`；因此 own-ship 工具无需反向读取 live pipeline 状态

因此，Step 2 的真实改动不是“把几个查询函数挂到 orchestrator 上”，而是先补齐一个独立、稳定、可单测的 agent tool 子层。

---

## 3. In Scope

### 3.1 工具基础契约与注册

- 在 `llm/agent/tool/` 下建立 `AgentTool` 接口
- 建立 `AgentToolRegistry`，统一暴露：
  - `getToolDefinitions()`
  - `execute(ToolCall call, AgentSnapshot snapshot)`
- 启动时校验工具名唯一，重复注册直接 fail-fast

### 3.2 Step 2 基线工具

- `GetRiskSnapshotTool`
- `GetTopRiskTargetsTool`
- `GetTargetDetailTool`
- `GetOwnShipStateTool`

### 3.3 工具参数校验与错误返回

- 参数缺失、枚举非法、`target_id` 不存在、`limit` 超范围等场景，统一返回结构化错误 payload
- recoverable 的调用错误不以异常中断 agent loop

### 3.4 单元测试

- 注册表测试
- 各工具独立测试
- 参数校验与错误 payload 测试
- 排序稳定性与快照只读边界测试

---

## 4. Out Of Scope

- deferred：provider function calling 接入与 `chatWithTools(...)`，归属 Step 1
- deferred：`AgentLoopOrchestrator`、tool result 回填消息轮次、最大循环次数治理，归属 Step 3
- deferred：`ADVISORY` 结构化输出、SSE 协议、前端 advisory 消费，归属 Step 3
- deferred：`QueryRegulatoryContextTool` 与 `EvaluateManeuverTool`，归属 Step 5
- not doing：工具直接访问 `RiskContextHolder`、`DerivedTargetStateStore`、`ShipDispatcher` 等 live 数据源
- not doing：为 Step 2 新增“可写工具”或任何副作用型工具
- not doing：前端展示真实 tool trace；该能力仍留在 `docs/TODO.md` 的独立 backlog 中
- not doing：为获取 own-ship safety domain 而扩展 `AgentSnapshot` 结构；本步骤选择工具内确定性重算

Step 2 不需要向 `docs/TODO.md` 新增事项。所有被延后的内容都已经被总览 Step 1 / 3 / 5 或现有 TODO backlog 明确承接。

---

## 5. Key Decisions

### 5.1 工具只读冻结快照；缺失的 own-ship 域尺寸在工具内重算

总览要求 `GetOwnShipStateTool` 返回 safety domain 尺寸，但 Step 0 的 `AgentSnapshot` 未纳入 `ShipDomainResult`。本步骤不回改 Step 0 的快照结构，而是在工具内部：

1. 从 `snapshot.riskContext().ownShip()` 重建临时 `ShipStatus`
2. 调用现有 `ShipDomainEngine.consume(...)`
3. 把结果放入 tool payload

`ShipStatus` 重建字段规则在本步骤固定如下：

- `id / longitude / latitude / sog / cog / heading / confidence`：直接从 `LlmRiskOwnShipContext` 复制
- `role = null`
- `msgTime = null`
- `qualityFlags = Set.of()`

原因：

- `ShipDomainEngine` 当前只依赖 `ShipStatus` 与配置，计算确定、无副作用
- `ShipDomainEngine.calculate()` 当前实际只读取 `sog`，因此缺失的 `role / msgTime / qualityFlags` 不需要额外补造语义
- 不扩大 `AgentSnapshot` 字段面，避免重新定义 Step 0 的冻结边界
- advisory / chat tool 读取到的 safety domain 仍与当前引擎算法保持一致

### 5.2 工具结果采用“结构化 JSON first”，而不是自然语言摘要

Step 2 工具返回的主路径是稳定键值结构，不是 prose 文本。工具 payload 必须满足：

- 顶层统一携带 `status`
- 成功结果携带 `snapshot_version`
- 数值字段使用明确单位后缀（如 `dcpa_nm`、`tcpa_sec`）
- 仅返回快照中可验证的事实，不写“建议”“推断”“应当”类叙述

原因是 Step 3 / Step 5 的 prompt 需要从 tool result 中抽取事实，而不是再解析一层非结构化文本。

### 5.3 Recoverable 错误返回为普通 tool result，不抛给 orchestrator

以下场景都视为 recoverable：

- 未知 `tool_name`
- 参数解析失败
- `target_id` 不存在于快照
- `limit` 超出允许范围

此类错误由注册表或工具本身转为结构化错误 payload，例如：

```json
{
  "status": "ERROR",
  "error_code": "TARGET_NOT_FOUND",
  "message": "Target 413999001 is not present in snapshot_version 12345"
}
```

这样做的目的不是吞掉错误，而是允许 LLM 在同一 loop 中修正调用。真正的程序错误（如 JSON 序列化异常、空依赖）才允许抛出并由 Step 3 记录失败。

### 5.4 工具注册表保持简单 Spring 组装，不引入插件化层

Step 2 只服务仓库内建工具。`AgentToolRegistry` 直接消费 Spring 注入的 `List<AgentTool>`，构建不可变 `Map<String, AgentTool>`。不引入独立 plugin SPI、动态装载或多级目录扫描。

这符合当前仓库的最小化变更原则，也已足够支撑 Step 5 往同一注册表追加工具。

`getToolDefinitions()` 的返回策略在本步骤固定为“构造期排序并缓存”：

- 在 `AgentToolRegistry` 构造函数中完成工具名去重
- 同时生成按 `tool_name` 排序的不可变 `List<ToolDefinition>`
- `getToolDefinitions()` 仅返回该缓存引用，不在每次调用时重排或重建列表

### 5.5 目标排序必须稳定、可复现

所有“按风险排序”的工具必须使用同一比较规则，避免同一快照内返回顺序抖动：

1. `risk_level` 降序（`ALARM > WARNING > CAUTION > SAFE`）
2. `risk_score` 降序（`null` 视为最小）
3. `approaching = true` 优先
4. 在同一 `approaching` bucket 内，`tcpa_sec` 升序；当前 `LlmRiskTargetContext.tcpaSec` 由 `RiskAssessmentEngine` 以 `0.0` 作为 sentinel 写入，因此 `tcpaSec <= 0` 一律视为非排序值并排后
5. `current_distance_nm` 升序
6. `target_id` 字典序

统一排序规则应由注册表共享的比较器或静态工具方法提供，而不是每个工具各自定义。

风险等级数据源也在本步骤固定：`get_risk_snapshot` 与 `get_top_risk_targets` 一律只读取 `snapshot.riskContext().targets` 中的 `LlmRiskTargetContext.riskLevel`，不混用 `snapshot.targetDetails()` 内 `TargetRiskAssessment.riskLevel`，以避免快照不完整时出现双源不一致。

---

## 6. Detailed Design

### 6.1 包结构

```text
backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/
  ToolDefinition.java          // Step 1 依赖
  ToolCall.java                // Step 1 依赖
  ToolResult.java              // Step 1 依赖

backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/
  AgentTool.java
  AgentToolNames.java
  AgentToolRegistry.java

backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/
  GetRiskSnapshotTool.java
  GetTopRiskTargetsTool.java
  GetTargetDetailTool.java
  GetOwnShipStateTool.java
```

Step 2 不新建额外的 `service/`、`manager/` 或多层 DTO 包。四个内建工具直接放在 `builtin/` 下即可。

### 6.2 `AgentTool` 与注册表接口

```java
public interface AgentTool {
    ToolDefinition getDefinition();
    ToolResult execute(ToolCall call, AgentSnapshot snapshot);
}
```

```java
@Component
public class AgentToolRegistry {

    public List<ToolDefinition> getToolDefinitions();

    public ToolResult execute(ToolCall call, AgentSnapshot snapshot);
}
```

注册表行为约束：

- `getToolDefinitions()` 返回按 `tool_name` 排序后的不可变列表，保证 prompt 与测试稳定
- 该列表在构造函数中排序并缓存，后续调用直接返回缓存引用
- `execute(...)` 先按名称查找工具，再委托执行
- 名称不存在时返回 `UNKNOWN_TOOL` 错误 payload
- 工具列表在构造函数中完成去重校验；若同名，Spring 启动失败

### 6.3 参数解析与序列化约束

Step 2 复用仓库现有 Jackson `ObjectMapper`，不自造字符串协议。

- `ToolCall.arguments` 以 JSON 结构承载
- 每个工具在本类内声明自己的 request record，并使用 `ObjectMapper` 解析
- 顶层结果统一序列化为 JSON 对象
- key 使用 snake_case，与现有协议风格保持一致

该步骤不要求 Step 1 的 `ToolResult` 必须持有 `JsonNode` 还是 `String`；只要求它能稳定承载结构化 payload。若 Step 1 最终选择不同 carrier，Step 2 只需要在注册表边界做适配。

### 6.4 `get_risk_snapshot`

**目标**：让模型以一次低成本调用拿到当前场景摘要，并决定下一步要查哪些目标。

**工具名**：`get_risk_snapshot`

**请求参数**：无

**成功返回结构**：

```json
{
  "status": "OK",
  "snapshot_version": 12345,
  "own_ship": {
    "id": "own-ship",
    "longitude": 114.3521,
    "latitude": 30.5372,
    "sog_kn": 10.4,
    "cog_deg": 83.1,
    "heading_deg": 82.0
  },
  "highest_risk_level": "ALARM",
  "target_count": 6,
  "approaching_target_count": 3,
  "risk_level_counts": {
    "SAFE": 2,
    "CAUTION": 1,
    "WARNING": 2,
    "ALARM": 1
  },
  "top_risk_target_ids": ["413999001", "413999002", "413999003"]
}
```

实现要点：

- `top_risk_target_ids` 仅返回排序后的前 3 个 `target_id`
- 该工具不返回完整 target 列表；完整排序明细由 `get_top_risk_targets` 负责
- 所有统计均基于 `snapshot.riskContext().targets`
- `highest_risk_level`、`risk_level_counts`、`top_risk_target_ids` 的风险等级来源一律为 `LlmRiskTargetContext.riskLevel`，不混用 `TargetRiskAssessment`

### 6.5 `get_top_risk_targets`

**目标**：以稳定排序方式返回当前最值得关注的目标列表。

**工具名**：`get_top_risk_targets`

**请求参数**：

```json
{
  "limit": 3,
  "min_level": "WARNING"
}
```

参数规则：

- `limit` 可缺省，默认 `3`
- `limit` 取值范围 `1..10`
- `min_level` 可缺省；若存在，必须是 `SAFE | CAUTION | WARNING | ALARM`

**成功返回结构**：

```json
{
  "status": "OK",
  "snapshot_version": 12345,
  "items": [
    {
      "target_id": "413999001",
      "risk_level": "ALARM",
      "risk_score": 92.4,
      "approaching": true,
      "current_distance_nm": 0.68,
      "dcpa_nm": 0.12,
      "tcpa_sec": 138,
      "encounter_type": "CROSSING"
    }
  ]
}
```

实现要点：

- 数据源只使用 `snapshot.riskContext().targets`
- 过滤后应用 §5.5 的统一排序规则
- `items[].risk_level` 一律取自 `LlmRiskTargetContext.riskLevel`
- `items` 中只保留“下一步决策必需字段”，不提前塞入轨迹点、predicted CPA 等重字段

### 6.6 `get_target_detail`

**目标**：针对单个目标返回完整事实，供 Step 3 advisory 和 Step 4 chat agent 继续推理。

**工具名**：`get_target_detail`

**请求参数**：

```json
{
  "target_id": "413999001"
}
```

**成功返回结构**：

```json
{
  "status": "OK",
  "snapshot_version": 12345,
  "target": {
    "target_id": "413999001",
    "risk_level": "ALARM",
    "risk_score": 92.4,
    "current_distance_nm": 0.68,
    "relative_bearing_deg": 37.0,
    "dcpa_nm": 0.12,
    "tcpa_sec": 138,
    "approaching": true,
    "longitude": 114.3611,
    "latitude": 30.5412,
    "speed_kn": 12.7,
    "course_deg": 215.0,
    "confidence": 0.96,
    "domain_penetration": 0.44,
    "rule_explanation": "...",
    "encounter_type": "CROSSING"
  },
  "derived": {
    "cpa": {
      "cpa_distance_nm": 0.12,
      "tcpa_sec": 138,
      "approaching": true,
      "cpa_valid": true
    },
    "predicted_cpa": {
      "cpa_distance_nm": 0.34,
      "tcpa_sec": 210,
      "approaching": true
    },
    "encounter": {
      "encounter_type": "CROSSING",
      "relative_bearing_deg": 37.0,
      "course_difference_deg": 128.0
    },
    "risk_assessment": {
      "risk_level": "ALARM",
      "risk_score": 92.4,
      "risk_confidence": 0.91,
      "domain_penetration": 0.44,
      "explanation_source": "RULE_ENGINE",
      "explanation_text": "..."
    },
    "prediction": {
      "prediction_time": "2026-04-23T10:20:30Z",
      "horizon_seconds": 300,
      "trajectory": [
        { "offset_seconds": 60, "longitude": 114.36, "latitude": 30.54 }
      ]
    }
  }
}
```

实现要点：

- 先用 `target_id` 在 `snapshot.riskContext().targets` 中查轻量上下文，再从 `snapshot.targetDetails()` 中取派生结果
- 若一侧存在、另一侧缺失，不静默补默认值；缺失字段保持 `null`
- 单位统一为航海推理友好的 `nm / sec / deg / kn`
- `trajectory` 直接复用冻结后的 `CvPredictionResult`，不额外裁剪；Step 2 不新增分页逻辑

### 6.7 `get_own_ship_state`

**目标**：返回本船状态与当前算法下的 safety domain 尺寸。

**工具名**：`get_own_ship_state`

**请求参数**：无

**成功返回结构**：

```json
{
  "status": "OK",
  "snapshot_version": 12345,
  "own_ship": {
    "id": "own-ship",
    "longitude": 114.3521,
    "latitude": 30.5372,
    "sog_kn": 10.4,
    "cog_deg": 83.1,
    "heading_deg": 82.0,
    "confidence": 0.99
  },
  "safety_domain": {
    "shape_type": "ellipse",
    "fore_nm": 0.54,
    "aft_nm": 0.22,
    "port_nm": 0.18,
    "stbd_nm": 0.18
  }
}
```

实现要点：

- 从 `snapshot.riskContext().ownShip()` 重建临时 `ShipStatus`
- 重建时 `role = null`、`msgTime = null`、`qualityFlags = Set.of()`；这些字段当前不被 `ShipDomainEngine` 使用
- 调用 `ShipDomainEngine.consume(...)` 获取 `ShipDomainResult`
- 不读取 `ShipDispatcher.cachedOwnShipDomainResult` 等 live 字段

### 6.8 错误 payload 规范

所有 recoverable 错误统一为：

```json
{
  "status": "ERROR",
  "error_code": "INVALID_ARGUMENT",
  "message": "Field 'limit' must be between 1 and 10"
}
```

建议使用的错误码：

- `UNKNOWN_TOOL`
- `INVALID_ARGUMENT`
- `TARGET_NOT_FOUND`
- `SNAPSHOT_INCOMPLETE`

`SNAPSHOT_INCOMPLETE` 用于 `snapshot.riskContext()`、`ownShip` 或必要派生状态缺失，避免返回伪造数值。

---

## 7. File Impact

### 7.1 新增

| 文件 | 说明 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/AgentTool.java` | 工具接口 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/AgentToolNames.java` | 内建工具名常量 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/AgentToolRegistry.java` | 工具注册与统一调度 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/GetRiskSnapshotTool.java` | 场景摘要工具 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/GetTopRiskTargetsTool.java` | 目标排序工具 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/GetTargetDetailTool.java` | 单目标详情工具 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/GetOwnShipStateTool.java` | 本船状态工具 |
| `backend/map-service/src/test/java/com/whut/map/map_service/llm/agent/tool/AgentToolRegistryTest.java` | 注册表测试 |
| `backend/map-service/src/test/java/com/whut/map/map_service/llm/agent/tool/builtin/GetRiskSnapshotToolTest.java` | 场景摘要测试 |
| `backend/map-service/src/test/java/com/whut/map/map_service/llm/agent/tool/builtin/GetTopRiskTargetsToolTest.java` | 排序与过滤测试 |
| `backend/map-service/src/test/java/com/whut/map/map_service/llm/agent/tool/builtin/GetTargetDetailToolTest.java` | 详情测试 |
| `backend/map-service/src/test/java/com/whut/map/map_service/llm/agent/tool/builtin/GetOwnShipStateToolTest.java` | own-ship 状态测试 |

### 7.2 依赖但不在本步骤修改

| 文件 / 类型 | 依赖方式 |
|---|---|
| `llm/agent/AgentSnapshot.java` | 所有工具的只读输入 |
| `llm/agent/ToolDefinition.java` / `ToolCall.java` / `ToolResult.java` | 来自 Step 1 的调用合同 |
| `risk/engine/safety/ShipDomainEngine.java` | `GetOwnShipStateTool` 内部纯计算复用 |
| `tracking/store/TargetDerivedSnapshot.java` | `GetTargetDetailTool` 的派生信息来源 |

Step 2 本身不需要修改 `RiskContextHolder`、`SceneRiskStateTracker` 或 `LlmTriggerService`。

---

## 8. Implementation Order

### Phase 1：注册表骨架

- 落 `AgentTool`、`AgentToolNames`、`AgentToolRegistry`
- 完成工具名去重与未知工具错误返回
- 固化统一排序比较器与错误码常量

### Phase 2：四个基线工具

- `GetRiskSnapshotTool`
- `GetTopRiskTargetsTool`
- `GetTargetDetailTool`
- `GetOwnShipStateTool`

建议顺序：

1. 先实现只依赖 `LlmRiskContext` 的两个轻量工具
2. 再实现 `GetTargetDetailTool`
3. 最后实现需要 `ShipDomainEngine` 重算的 `GetOwnShipStateTool`

### Phase 3：测试与回归

- 完成每个工具的 happy path / invalid argument / missing data 三类测试
- 验证排序稳定性
- 验证结果单位与字段名
- 验证工具执行不会修改传入的 `AgentSnapshot`

---

## 9. Test Plan

### 9.1 `AgentToolRegistryTest`

- 多个工具注册后，`getToolDefinitions()` 按名称稳定排序
- `getToolDefinitions()` 返回同一缓存引用，不在每次调用时重建列表
- 未知 `tool_name` 返回 `UNKNOWN_TOOL`
- 存在重复工具名时构造失败

### 9.2 `GetRiskSnapshotToolTest`

- 空目标列表时仍返回 `target_count = 0` 与 `highest_risk_level = SAFE`
- 多风险等级混合时，`risk_level_counts` 与 `top_risk_target_ids` 正确

### 9.3 `GetTopRiskTargetsToolTest`

- `min_level` 过滤正确
- `limit` 缺省走默认值
- 同风险等级下按 `risk_score / approaching / tcpa / distance / target_id` 稳定排序
- `tcpaSec <= 0` 的目标在同一 `approaching` bucket 内排在正值 TCPA 之后
- 非法 `limit` 返回 `INVALID_ARGUMENT`

### 9.4 `GetTargetDetailToolTest`

- 同时存在 `riskContext` 与 `targetDetails` 时，返回完整 payload
- `target_id` 不存在时返回 `TARGET_NOT_FOUND`
- 只有一侧数据存在时，对应缺失字段为 `null`，不伪造默认值
- `trajectory` 返回冻结副本内容，不因测试内修改原对象而漂移

### 9.5 `GetOwnShipStateToolTest`

- 从 `LlmRiskOwnShipContext` 可正确重建 `ShipStatus`
- 重建结果中 `role = null`、`msgTime = null`、`qualityFlags = Set.of()`
- 复用 `ShipDomainEngine` 后得到的 `fore_nm / aft_nm / port_nm / stbd_nm` 与当前配置一致
- 测试直接实例化 `ShipDomainEngine(new ShipDomainProperties())`，按需覆盖 `referenceSpeedKn / baseForeNm / baseAftNm / basePortNm / baseStbdNm`，不启动 Spring context
- `ownShip` 缺失时返回 `SNAPSHOT_INCOMPLETE`

### 9.6 只读边界测试

- 对工具返回 payload 做修改，不影响原 `AgentSnapshot`
- 工具执行过程中不访问 live store；测试通过 mock / spy 验证仅使用注入的 `AgentSnapshot` 与 `ShipDomainEngine`

---

## 10. Constraints And Risks

- Step 2 依赖 Step 1 先交付 `ToolDefinition` / `ToolCall` / `ToolResult`；若实现顺序上需要并行推进，应先锁定这三个类型的最小字段集，避免工具层返工
- `GetTargetDetailTool` 的 `trajectory` 可能较长，但在当前 `CvPredictionResult` 规模下仍可接受；若联调发现 provider 对 tool result token 敏感，再由 Step 3 在 prompt / tool 选择层面控制调用频率，而不是在 Step 2 提前分页
- `GetOwnShipStateTool` 的 safety domain 来源于实时算法重算，因此其结果代表“按当前算法从快照本船状态重建的 domain”，而不是 pipeline 某次缓存对象的直接镜像；两者在当前算法下应一致
- Step 2 不承担建议生成职责。若 tool result 中混入 prompt 文案或决策建议，会污染 Step 3 的推理边界，应在实现时明确避免
