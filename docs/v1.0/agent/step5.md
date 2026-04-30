# Agent Step 5：GraphRAG Foundation + EvaluateManeuverTool（COLREGS 知识图谱）

> 文档状态：active
> 最后更新：2026-04-29
> 执行状态：pending
> 所属 track：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md)
> 对应总览：`AGENT_LOOP_PLAN.md` §3.8 / §4 Step 5
> 目标：构建 COLREGS Part B 规则知识图谱并暴露为 advisory agent 可调用的 `query_regulatory_context` 工具；同期引入 `evaluate_maneuver` 操纵假设评估工具，使 advisory `evidence_items` 中的规则依据与定量假设均有可审计来源。

---

## 1. Summary

Step 5 在 Step 0–4C 已经稳定的 advisory / chat-agent 基础设施之上，新增两类无副作用工具，并把它们注册到 `AgentToolRegistry`：

1. `query_regulatory_context`：基于内存 COLREGS 图谱，按相遇态势、本船角色和（可选）能见度返回相关规则摘要与推荐操纵动作 ID。
2. `evaluate_maneuver`：基于冻结的 `AgentSnapshot` 中本船状态，假设瞬时执行给定操纵后调用现有 [`CpaTcpaBatchCalculator.calculateOne`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/collision/CpaTcpaBatchCalculator.java) 重新求解 DCPA / TCPA，返回 before / after 对比。

本步骤不改变 Step 3 的 advisory 协议外形（`AdvisoryPayload` 字段、`AdvisoryActionType` 枚举、SSE 事件类型不变），但：

- 解除 Step 3 对 `evidence_items` 内容的 baseline 限制（Step 3 仅允许查询工具产出的数值/状态事实），允许 `evidence_items` 包含来自这两类新工具的规则引用与机动假设事实。
- 上游引擎新增独立的 `EncounterRoleResolver`，把 `ownShipRole` 写入 [`EncounterClassificationResult`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/encounter/EncounterClassificationResult.java)，作为 advisory 工具与 chat agent 工具均可读取的场景事实，避免在 LLM tool 内部反推角色。
- 改写 advisory system prompt，将“禁止规则条款 / 责任判定 / 假设 CPA”这一 Step 3 临时约束替换为“规则、责任、假设 CPA 必须来自这两类新工具”的强约束。Chat agent 路径只做软约束。

GraphRAG 在 v1.0 仅覆盖 COLREGS Part B（Rules 4–19）。历史案例数据、外部图数据库、案例相似度检索均不在本步骤；这些扩展方向通过 `GraphQueryPort` 抽象保留可扩展性，但 v1.0 不引入对应实体或 adapter。

---

## 2. Current State And Step Delta

### 2.1 已就位的前置能力

- 冻结快照：[`AgentSnapshot`](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentSnapshot.java) 已通过 [`TargetStateSnapshotDeepCopier`](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/TargetStateSnapshotDeepCopier.java) 提供 value-level 不可变副本。
- Tool 合同：`AgentTool` / `AgentToolRegistry` / `ToolDefinition` / `ToolCall` / `ToolResult` 已稳定；现有内建工具 `get_risk_snapshot` / `get_top_risk_targets` / `get_target_detail` / `get_own_ship_state` 全部从 snapshot 读取。
- 编排器：[`AgentLoopOrchestrator`](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentLoopOrchestrator.java) 已在 advisory 与 chat agent 路径上稳定运行；`AdvisoryService` / `AdvisoryOutputParser` / `AdvisoryPromptBuilder` 均可独立扩展。
- 任务级 provider 路由：Step 4C 完成后 `AgentLoopOrchestrator` 通过 `LlmTaskType.AGENT` 取得当前 chat / agent provider，本步骤无需重新决策 provider。

### 2.2 当前缺失项

- 上游 pipeline 不存在 `ownShipRole` 概念，[`EncounterClassifier`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/encounter/EncounterClassifier.java) 仅做几何相遇态势分类。
- `EncounterClassificationResult` 与 `LlmRiskTargetContext.encounterType` 都没有“让路 / 直航”维度。
- 仓库内不存在 COLREGS 文本资源、图实体、图 adapter 或法规检索 port。
- `AgentToolNames` 无 `query_regulatory_context` / `evaluate_maneuver` 常量；`AgentToolRegistry` 无对应工具实现。
- `system-advisory.txt` 当前明确禁止 evidence 中出现 COLREGS 规则、责任判定与假设 CPA（Step 3 边界），与新工具语义冲突，必须改写。

### 2.3 Step 5 的最小增量

- 新增 `risk/engine/encounter/EncounterRoleResolver.java` 与 `OwnShipRole` 枚举；为 `EncounterClassificationResult` 新增 `ownShipRole` 字段；同步更新两处 pipeline 调用点与 `TargetStateSnapshotDeepCopier`。
- 新增 `llm/agent/graph/` 包：`GraphQueryPort` / `RegulatoryQuery` / `RegulatoryContext` / `Rule` / `EncounterSituation` / `ManeuverAction` / `MemoryGraphAdapter`，以及加载 `resources/colregs/rules.json` 的 loader。
- 新增 `llm/agent/tool/builtin/QueryRegulatoryContextTool.java`、`EvaluateManeuverTool.java`，并在 `AgentToolNames` 中追加常量。
- 改写 `prompts/system-advisory.txt`；不修改 chat agent 的 `system-agent-chat.txt`（chat 软约束以工具描述传达，不靠 prompt 强制）。
- 配置：`LlmProperties` 新增 `graph` 子项（资源路径、是否启用），不引入新顶层 properties 类。

---

## 3. In Scope

### 3.1 上游引擎

- 新增 `OwnShipRole` 枚举与 `EncounterRoleResolver` 组件，按 §6.1 决策映射 `EncounterType → OwnShipRole`。
- 在 [`ShipDispatcher`](../../../backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java) 与 [`LlmRiskContextAssembler`](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskContextAssembler.java) 两处现有 `EncounterClassifier.classify(...)` 调用之后立即调用 `EncounterRoleResolver.resolve(...)`，把结果写回 `EncounterClassificationResult.ownShipRole`。
- `EncounterClassificationResult` 新增字段；`TargetStateSnapshotDeepCopier.copyEncounterClassification` 同步携带新字段。
- `LlmRiskTargetContext` 不强制下放 `ownShipRole`，仅保证 `TargetDerivedSnapshot.encounterResult().ownShipRole` 可被工具读取。

### 3.2 GraphRAG 内存图

- 实体：`Rule`、`EncounterSituation`、`ManeuverAction`，字段定义见 §5.1。
- 关系：`APPLIES_TO(ruleId → encounterType)`、`APPLIES_TO_ROLE(ruleId → ownShipRole)`、`RECOMMENDS(ruleId → maneuverActionId)`。
- 资源：`backend/map-service/src/main/resources/colregs/rules.json`，覆盖 Rules 4–8、11、13–17、19；Rules 9 / 10 / 12 / 18 仅占位，标记 `not_applicable_without_channel_or_vessel_type_context`，`applicableSituations` 与 `applicableRoles` 留空。
- 加载：Spring 启动时通过 `MemoryGraphLoader` 一次性读入 classpath 资源，构造 `MemoryGraphAdapter` bean；加载失败抛出 fail-fast 异常，避免 advisory 运行时静默退化。
- Port：`GraphQueryPort` 暴露领域级方法 `findRegulatoryContext(RegulatoryQuery)`；不暴露图原语遍历。

### 3.3 AgentSnapshot 扩展（EvaluateManeuverTool 前置条件）

当前 `AgentSnapshot` 仅包含 `LlmRiskContext`（投影 DTO）与 `Map<String, TargetDerivedSnapshot>`，均不持有 `ShipStatus` 原始对象。`EvaluateManeuverTool` 需要调用 `CpaTcpaBatchCalculator.calculateOne(ShipStatus ownShip, ShipStatus targetShip)`，因此必须在 Phase 1 完成以下扩展（不早于 Phase 3 工具实现）：

- `AgentSnapshot` 新增两个字段：`ShipStatus frozenOwnShip`、`Map<String, ShipStatus> frozenTargetShips`。
- `AgentSnapshotFactory.build(...)` 从 pipeline 传入当前 `ownShip` 与 `targetShips` 副本；深拷贝由 `ShipStatus` 内部字段平铺赋值实现（`ShipStatus` 不含引用类型集合，浅拷贝即为深拷贝）。
- `EvaluateManeuverTool` 直接读取 `snapshot.frozenOwnShip()` 和 `snapshot.frozenTargetShips().get(targetId)`，不再依赖 `LlmRiskContext`。
- 此扩展对现有工具透明：`AgentSnapshot` 为 record，新增字段后原有工具仍只读取 `riskContext` / `targetDetails`，无影响。

### 3.4 工具实现

- `QueryRegulatoryContextTool`：从 `AgentSnapshot` 取 `encounterResult`，组装 `RegulatoryQuery`，调用 `GraphQueryPort`，把 `RegulatoryContext` 序列化为 tool result JSON。允许 caller 通过参数覆盖 `encounter_type` / `own_ship_role`，但默认从 snapshot 读取。
- `EvaluateManeuverTool`：参数 `target_id` / `maneuver_type` / `magnitude` / 可选 `apply_seconds`（v1.0 必须为 0 或缺省，否则返回 `INVALID_ARGUMENT`），返回结构化 before / after CPA 对比；依赖 §3.3 的 snapshot 扩展。
- 两工具分别注册到 `AgentToolNames`，由 Spring 自动通过 `List<AgentTool>` 注入到 `AgentToolRegistry`，无需修改注册代码。

### 3.4 Prompt 与 Output Parser

- 改写 `prompts/system-advisory.txt`：保留“只能引用工具事实”的总原则，按 §7.1 添加规则条款 / 责任判定 / 假设 CPA 的强约束。
- `AdvisoryOutputParser` 移除（如有）针对 evidence 内容关键字的拒绝逻辑；保持仅做结构化校验。Step 3 测试中“evidence 含 COLREGS 关键字应被拒绝”的用例需在本步骤删除或反向化。

### 3.5 配置与文档

- `LlmProperties` 新增 `graph` 子结构：`enabled`、`resourcePath`。`enabled=false` 时跳过 `MemoryGraphLoader`，不注册 `QueryRegulatoryContextTool`，避免运行期 LLM 看到工具但调用必然失败。
- `application.properties` 新增 `llm.graph.enabled=true`、`llm.graph.resource-path=classpath:colregs/rules.json`。
- advisory prompt 对规则 / 责任 / 假设 CPA 的强约束是无条件的（见 §8.1），不通过配置项控制；不引入 `requireRulesForCourseChange` / `requireRulesForSpeedChange` 等 flag——若 prompt 约束需调整，直接修改 `system-advisory.txt`。
- 无 SSE 协议改动；无前端改动。
- 同步更新：[`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md) Appendix A 追加 Step 5 deviation 摘要（仅在偏离总览原文时）；[`docs/TODO.md`](../../TODO.md) §3 “GraphRAG 历史案例与外部图存储扩展”一条中，移除已落地的 “COLREGS 基础图谱” 与 “`EvaluateManeuverTool`” 表述，保留该条目中历史案例数据集、外部图数据库接入、版本化引用与统一检索接口等尚未挂入实现链的 backlog。不删除该条目本身。

---

## 4. Out Of Scope

- **deferred**：历史碰撞 / 近失事件案例图谱与相似度检索；触发条件为后续出现具备授权的事件数据集和案例标签体系；继续登记于 `docs/TODO.md` §3 的 GraphRAG 历史扩展项。
- **deferred**：外部图数据库（Neo4j / TigerGraph 等）adapter；触发条件为内存图无法满足查询性能或规则数据量级跃迁；`GraphQueryPort` 已为此预留替换点。
- **deferred**：转向动力学（最大转艏率、加速度上限）下的非瞬时机动评估；触发条件为 demo 阶段反映出瞬时假设导致建议偏离实船能力；登记到 `docs/TODO.md` §2 “安全领域模型增强”同段或新增独立条目。
- **deferred**：`evaluate_maneuver` 的多目标批量评估、组合机动（先减速后右转）评估；触发条件为单步评估无法覆盖典型场景。
- **deferred**：能见度受限（Rule 19）的完整规则推理；v1.0 接口预留 `visibility_condition` 字段，但默认 `OPEN_VISIBILITY`，weather context 集成留待后续。
- **deferred**：`ownShipRole` 在前端 explanation / advisory UI 中的展示；本步骤只到后端 tool 层，前端展示由后续 visual track 决定。
- **not doing**：把 `ManeuverAction.type` 提升为 SSE 协议枚举；`AdvisoryActionType` 保持现状，graph 层枚举仅作为内部域类型。
- **not doing**：在 chat agent path 强制每轮都要调用 `query_regulatory_context`；chat 路径只在工具描述中说明可用性，不在 system prompt 中作硬约束。
- **not doing**：把 `ownShipRole` 计算下沉到 `QueryRegulatoryContextTool` 内部；让路 / 直航是场景事实，必须由 pipeline 写入 snapshot。
- **not doing**：vendoring COLREGS 规则全文；v1.0 仅存 `summary + principle + sourceCitation`，等 SOURCEBOOK §7 来源 / 许可确认后再补 `fullText` 字段。

Step 5 不向 `docs/TODO.md` 新增条目，只对 GraphRAG 主线项做收敛。所有 deferred 项已在 §4 中给出触发条件，无需额外 owner 占位。

---

## 5. Domain Model

### 5.1 实体字段

```java
public record Rule(
        String ruleId,                    // "rule-13", "rule-14", ...
        String ruleNumber,                // "13", "14", "19"
        String title,                     // "Overtaking" / "Head-on situation"
        String part,                      // "B"
        String section,                   // "I" / "II" / "III"
        String summaryEn,                 // 一句话英文摘要（authoritative key）
        String summaryZh,                 // 一句话中文摘要（UI 翻译）
        String principle,                 // 核心原则，例如 "Give-way vessel must take early and substantial action"
        String sourceCitation,            // "COLREGS 1972, Rule 13"
        List<EncounterType> applicableSituations,
        List<OwnShipRole> applicableRoles,
        List<String> recommendedActionIds,
        List<String> limitations,         // e.g. ["not_applicable_without_channel_or_vessel_type_context"]
        String fullText                   // optional, 默认 null，等许可确认后回填
) {}

public record EncounterSituation(
        EncounterType type,               // 直接复用现有枚举
        String descriptionEn,
        String descriptionZh
) {}

public record ManeuverAction(
        String actionId,                  // "alter-course-starboard"
        ManeuverActionType type,
        String descriptionEn,
        String descriptionZh,
        String rationale                  // 引用规则原则的简短说明
) {}

public enum ManeuverActionType {
    ALTER_COURSE_STARBOARD,
    ALTER_COURSE_PORT,
    REDUCE_SPEED,
    REDUCE_TO_MINIMUM_SPEED,
    MAINTAIN_COURSE_AND_SPEED,
    INCREASE_LOOKOUT,
    NO_RECOMMENDATION
}

public enum OwnShipRole {
    GIVE_WAY,
    STAND_ON,
    MUTUAL_ACTION,
    UNKNOWN,
    NOT_APPLICABLE
}
```

字段说明：

- `ruleId` 是图节点稳定主键，使用 `"rule-<number>"` 格式；`ruleNumber` 是给 LLM 与日志看的字符串。
- `summaryEn` 是 authoritative source；`summaryZh` 仅供 UI 与 LLM 中文输出参考，不可作为法规原文。
- `applicableSituations` 与 `applicableRoles` 共同决定该规则是否进入 query 命中集合；二者均为 “或”（任意 situation × 任意 role 命中即视为适用）。空列表表示 `not_applicable_without_channel_or_vessel_type_context`，仅作 placeholder 节点存在。
- `ManeuverAction.type` 与 `AdvisoryActionType`（SSE 协议）的映射关系见 §5.4，不在 graph 层强行对齐。

### 5.2 资源 schema（`resources/colregs/rules.json`）

```jsonc
{
  "schemaVersion": 1,
  "rules": [
    {
      "ruleId": "rule-13",
      "ruleNumber": "13",
      "title": "Overtaking",
      "part": "B",
      "section": "II",
      "summaryEn": "Any vessel overtaking another shall keep out of the way of the vessel being overtaken.",
      "summaryZh": "追越船应避让被追越船。",
      "principle": "The overtaking vessel is give-way; the overtaken vessel is stand-on.",
      "sourceCitation": "COLREGS 1972, Rule 13",
      "applicableSituations": ["OVERTAKING"],
      "applicableRoles": ["GIVE_WAY", "STAND_ON"],
      "recommendedActionIds": [
        "maintain-course-and-speed",
        "increase-lookout"
      ],
      "limitations": []
    }
    // ... rules 4-8, 11, 13-17, 19
    // rule-9 / rule-10 / rule-12 / rule-18 仅作占位：
    //   applicableSituations: [], applicableRoles: [],
    //   limitations: ["not_applicable_without_channel_or_vessel_type_context"]
  ],
  "maneuverActions": [
    {
      "actionId": "alter-course-starboard",
      "type": "ALTER_COURSE_STARBOARD",
      "descriptionEn": "Make a substantial alteration of course to starboard.",
      "descriptionZh": "向右舷做明显且足够的转向。",
      "rationale": "Substantial action keeps the give-way vessel well clear and makes intent visible to the other vessel."
    }
    // ... 其它动作
  ]
}
```

加载器 `MemoryGraphLoader` 校验：

- `ruleId` 与 `actionId` 全局唯一；
- `recommendedActionIds` 引用的 `actionId` 必须存在；
- `applicableSituations` 元素必须可映射到 `EncounterType` 枚举；
- 同一 `ruleNumber` 不重复出现；
- 任一校验失败抛出 `IllegalStateException` 终止启动。

### 5.3 GraphQueryPort 与查询语义

```java
public interface GraphQueryPort {
    RegulatoryContext findRegulatoryContext(RegulatoryQuery query);
}

public record RegulatoryQuery(
        EncounterType encounterType,
        OwnShipRole ownShipRole,
        RiskLevel riskLevel,                  // 用于 future ranking，本步骤暂不参与命中筛选
        VisibilityCondition visibilityCondition  // 默认 OPEN_VISIBILITY；本步骤只作字段透传
) {}

public record RegulatoryContext(
        List<Rule> rules,                     // 命中集合，已排序：先 article 升序，再 principle 长度升序
        List<ManeuverAction> recommendedActions,  // rules 命中集合的 recommendedActionIds 去重展开
        List<String> limitations              // 命中规则中 limitations 的去重并集
) {}

public enum VisibilityCondition {
    OPEN_VISIBILITY,
    RESTRICTED_VISIBILITY,
    UNKNOWN
}
```

命中规则：

- `encounterType == UNDEFINED` 或 `ownShipRole == UNKNOWN`：返回空 `rules` 与空 `recommendedActions`，`limitations` 含字符串 `"insufficient_classification_input"`。
- `encounterType` 命中 `applicableSituations` 且 `ownShipRole` 命中 `applicableRoles` 时，规则进入命中集合。
- `applicableSituations` 为空或 `applicableRoles` 为空的占位节点（如 Rule 9 / 10 / 12 / 18）永远不会命中正常查询。
- v1.0 不依据 `riskLevel` 过滤 / 排序，但保留参数避免后续接口扩张造成 breaking change。

### 5.4 ManeuverAction 与 SSE AdvisoryActionType 的映射

| `ManeuverActionType` | 映射目标 `AdvisoryActionType` |
|---|---|
| `ALTER_COURSE_STARBOARD` / `ALTER_COURSE_PORT` | `COURSE_CHANGE` |
| `REDUCE_SPEED` / `REDUCE_TO_MINIMUM_SPEED` | `SPEED_CHANGE` |
| `MAINTAIN_COURSE_AND_SPEED` | `MAINTAIN_COURSE` |
| `INCREASE_LOOKOUT` | `MONITOR` |
| `NO_RECOMMENDATION` | `UNKNOWN` |

映射不在代码中显式建表：LLM 看到的是工具结果中的 `ManeuverActionType` 字符串与航海语义描述，由 LLM 自行选择 `AdvisoryActionType` 写入 `recommended_action.type`。`AdvisoryOutputParser` 仍按现有协议解析；不引入二次转换层。

---

## 6. Encounter Role Resolver

### 6.1 决策映射

| 现有 `EncounterType` | 现有几何含义 | `OwnShipRole` |
|---|---|---|
| `HEAD_ON` | 两船相向，互在对方船头扇区 | `MUTUAL_ACTION` |
| `OVERTAKING` | 目标在本船船尾扇区 + 同向（即“目标正在追越本船”） | `STAND_ON` |
| `CROSSING`，目标在本船 starboard 半圆（`relativeBearingDeg ∈ (head_on_bow_half_angle, 180)`） | 目标从右舷接近 | `GIVE_WAY` |
| `CROSSING`，目标在本船 port 半圆（`relativeBearingDeg ∈ (180, 360 - head_on_bow_half_angle)`） | 目标从左舷接近 | `STAND_ON` |
| `UNDEFINED` | COG 无效 | `UNKNOWN` |

OVERTAKING 边界澄清：现有 `EncounterClassifier` 只检查”目标在本船船尾扇区且航向差小”，**不验证相对速度 / 距离收敛**（即不检查目标是否真的在追近本船）。几何上满足条件但目标实际速度低于本船时，”追越”判定可能不成立。本步骤不在 resolver 层引入速度收敛检查（属于 EncounterClassifier 演进范围，超出 Step 5 边界）；而是在 `QueryRegulatoryContextTool` 中：当 `encounterType == OVERTAKING` 时，在工具输出的 `assumptions` 字段追加 `”overtaking_role_inferred_from_geometry_only_converging_speed_not_verified”`。LLM 看到此标注后，应在 evidence / summary 中对 Rule 13 责任判定加适当限定语（”基于几何态势判定”），而不是以绝对口吻表述。Speed-convergence 检查作为 `EncounterClassifier` 的后续演进项记录在 `docs/TODO.md` §2。

CROSSING 边界澄清：本系统不模拟船舶责任类型与航道约束，因此 Rule 9 / 10 / 12 / 18 全部不参与命中。CROSSING 的 GIVE_WAY / STAND_ON 仅按相对方位的左 / 右半圆判定，与几何分类一致。`head_on_bow_half_angle` 与 `overtaking_stern_half_angle` 已被 `EncounterClassifier` 用于划分扇区边界，role resolver 不再重复判断。

### 6.2 组件与调用点

```java
@Component
public class EncounterRoleResolver {
    public OwnShipRole resolve(EncounterClassificationResult classification);
}
```

- 输入：已生成的 `EncounterClassificationResult`（包含 `encounterType`、`relativeBearingDeg`）。
- 不读取 ShipStatus 原始字段；所有判定基于 `EncounterClassifier` 已暴露的派生字段，避免与几何分类逻辑重复。
- 调用点 1：`ShipDispatcher.dispatchAll(...)` 与 `dispatchIncrementalForTarget(...)` 内 `EncounterClassifier.classify(...)` 之后。
- 调用点 2：`LlmRiskContextAssembler.assembleTargets(...)` 内 `encounterClassifier.classify(...)` 之后。
- 调用方负责把结果以 `result.setOwnShipRole(role)` 写回 `EncounterClassificationResult`（`@Data` setter 已存在）。

### 6.3 EncounterClassificationResult 字段扩展

```java
@Data
@Builder
public class EncounterClassificationResult {
    private String targetId;
    private EncounterType encounterType;
    private double relativeBearingDeg;
    private double courseDifferenceDeg;
    private OwnShipRole ownShipRole;   // 新增；默认 UNKNOWN，由 resolver 写入
}
```

`TargetStateSnapshotDeepCopier.copyEncounterClassification` 同步拷贝新字段。`@Builder` 默认值通过 `@Builder.Default` 设为 `OwnShipRole.UNKNOWN`，避免现有 builder 调用方因新增必填字段而崩溃。

---

## 7. Tool Contracts

### 7.1 `query_regulatory_context`

工具定义：

| 名称 | `query_regulatory_context` |
|---|---|
| 描述 | "Query COLREGS Part B regulatory context for the current encounter. Returns applicable rules, recommended maneuver actions, and limitations. Read encounter_type / own_ship_role from the snapshot if not provided." |
| 输入 schema | `target_id`（可选）、`encounter_type`（可选 enum）、`own_ship_role`（可选 enum）、`visibility_condition`（可选，默认 `OPEN_VISIBILITY`） |
| 输出 schema | 见下方示例 |

输入解析顺序：

1. 若提供 `target_id`，从 `snapshot.targetDetails().get(targetId).encounterResult()` 读 `encounterType` 与 `ownShipRole`。
2. 若同时提供 `encounter_type` 或 `own_ship_role`，**显式参数覆盖 snapshot 派生值**，并在输出 `assumptions` 中追加一行 `"override:encounter_type"` 或 `"override:own_ship_role"`。
3. 若两者均无，则返回 `INVALID_ARGUMENT`，`message = "either target_id or (encounter_type, own_ship_role) must be provided"`。

输出示例：

```json
{
  "status": "OK",
  "snapshot_version": 12345,
  "encounter_type": "CROSSING",
  "own_ship_role": "GIVE_WAY",
  "visibility_condition": "OPEN_VISIBILITY",
  "rules": [
    {
      "rule_id": "rule-15",
      "rule_number": "15",
      "title": "Crossing situation",
      "summary_en": "...",
      "summary_zh": "...",
      "principle": "Vessel which has the other on her own starboard side shall keep out of the way.",
      "source_citation": "COLREGS 1972, Rule 15",
      "limitations": []
    },
    {
      "rule_id": "rule-16",
      "rule_number": "16",
      "title": "Action by give-way vessel",
      "summary_en": "...",
      "summary_zh": "...",
      "principle": "Take early and substantial action to keep well clear.",
      "source_citation": "COLREGS 1972, Rule 16",
      "limitations": []
    }
  ],
  "recommended_actions": [
    {
      "action_id": "alter-course-starboard",
      "type": "ALTER_COURSE_STARBOARD",
      "description_en": "Make a substantial alteration of course to starboard.",
      "description_zh": "向右舷做明显且足够的转向。",
      "rationale": "..."
    }
  ],
  "limitations": [],
  "assumptions": []
}
```

Assumptions 规则：

- 当有效 `encounter_type == OVERTAKING` 时，工具在输出 `assumptions` 中追加 `"overtaking_role_inferred_from_geometry_only_converging_speed_not_verified"`，用于提示 LLM 对 Rule 13 责任判定使用限定语。

错误路径：

- `TARGET_NOT_FOUND`：`target_id` 在 snapshot 中不存在。
- `INSUFFICIENT_CLASSIFICATION`：派生 / 显式输入 `encounter_type == UNDEFINED` 或 `own_ship_role == UNKNOWN`，工具仍以 `status = OK` 返回，但 `rules` / `recommended_actions` 为空数组，`limitations` 含 `"insufficient_classification_input"`，由 LLM 决定降级或 `NO_RECOMMENDATION`。

### 7.2 `evaluate_maneuver`

工具定义：

| 名称 | `evaluate_maneuver` |
|---|---|
| 描述 | "Evaluate the immediate effect of an own-ship maneuver on a single target. Course change positive degrees mean turn to starboard; speed change in knots, negative means slowdown. Returns before/after CPA values from CpaTcpaBatchCalculator. Assumes instantaneous execution; turning dynamics are not modeled." |
| 输入 schema | `target_id` 必填、`maneuver_type` 必填（`COURSE_CHANGE` / `SPEED_CHANGE`）、`magnitude` 必填（数值）、`apply_seconds` 可选（v1.0 必须为 0 / 缺省） |
| 输出 schema | 见下方示例 |

参数语义：

- `COURSE_CHANGE.magnitude`：单位度，正数 = 右转 / starboard，负数 = 左转 / port，绝对值不得超过 180。
- `SPEED_CHANGE.magnitude`：单位节，负数 = 减速，正数 = 加速；最终 `sog + magnitude` 不得 < 0；若 < 0 视为 `INVALID_ARGUMENT`。
- `apply_seconds`：v1.0 仅接受 0 或缺省；非 0 返回 `INVALID_ARGUMENT`，`message = "apply_seconds is reserved for future use; must be 0 or omitted in v1.0"`，避免无声忽略导致建议偏差。

实现（依赖 §3.3 中 `AgentSnapshot` 的 `frozenOwnShip` / `frozenTargetShips` 扩展）：

1. 读取 `snapshot.frozenOwnShip()`；若为 `null` 返回 `OWN_SHIP_UNAVAILABLE`。
2. 从 `snapshot.frozenTargetShips().get(targetId)` 取目标 `ShipStatus`；不存在则返回 `TARGET_NOT_FOUND`。
3. 浅拷贝本船为 `ShipStatus simulatedOwnShip`，按 `maneuver_type` 修改 `cog` 或 `sog`：
   - `cog_after = (cog_before + magnitude + 360) % 360`
   - `sog_after = sog_before + magnitude`
4. 以目标原始 `ShipStatus`（不修改）和 `simulatedOwnShip` 调用 `CpaTcpaBatchCalculator.calculateOne(...)`；before 结果直接读取 `snapshot.targetDetails().get(targetId).cpaResult()`。
5. 组装输出：

```json
{
  "status": "OK",
  "snapshot_version": 12345,
  "target_id": "413999001",
  "maneuver": {
    "type": "COURSE_CHANGE",
    "magnitude": 20,
    "magnitude_unit": "deg",
    "apply_seconds": 0
  },
  "before": {
    "dcpa_nm": 0.12,
    "tcpa_sec": 138,
    "is_approaching": true,
    "cpa_valid": true
  },
  "after": {
    "dcpa_nm": 0.62,
    "tcpa_sec": 152,
    "is_approaching": true,
    "cpa_valid": true
  },
  "delta_dcpa_nm": 0.50,
  "assumptions": [
    "instantaneous_execution",
    "no_turning_dynamics",
    "target_state_unchanged"
  ]
}
```

错误路径：

- `TARGET_NOT_FOUND`：`target_id` 不在 snapshot 中。
- `INVALID_ARGUMENT`：参数缺失、`maneuver_type` 非法、`magnitude` 超界、`apply_seconds != 0`、计算后 `sog < 0`。
- `OWN_SHIP_UNAVAILABLE`：snapshot 中无法取得本船 `ShipStatus`；返回 `status = ERROR` 而非空对象。
- 当 `CpaTcpaBatchCalculator.calculateOne` 返回 `null`（参数自洽但 ID 自指等）时，工具按 `OWN_SHIP_UNAVAILABLE` 处理。

### 7.3 不引入新工具集合常量类

`AgentToolNames` 直接追加：

```java
public static final String QUERY_REGULATORY_CONTEXT = "query_regulatory_context";
public static final String EVALUATE_MANEUVER = "evaluate_maneuver";
```

两工具均以 `@Component` 注册，`AgentToolRegistry` 通过现有 `List<AgentTool>` 注入自动收录。

---

## 8. Prompt And Tool Strategy

### 8.1 Advisory system prompt 改写

现 [`prompts/system-advisory.txt`](../../../backend/map-service/src/main/resources/prompts/system-advisory.txt) 第 6 行明确禁止 `evidence_items` 包含 COLREGS 规则、责任判定和假设 CPA。Step 5 改写为相反方向的强约束：

- 仍保留“只能引用工具结果中的事实”这一总原则。
- 新增条款：在 `evidence_items` 中输出任何 COLREGS 规则引用、让路 / 直航责任判定、或机动后 CPA 评估前，**必须**先调用 `query_regulatory_context` 或 `evaluate_maneuver`，并且引用其返回字段（`rule_id` / `source_citation` / `delta_dcpa_nm` 等）作为出处。
- 当 `recommended_action.type` 为 `COURSE_CHANGE` 或 `SPEED_CHANGE` 时，**必须**至少调用一次 `evaluate_maneuver` 并将其结果作为 `evidence_items` 的一项。
- 当 `evaluate_maneuver` 显示 `delta_dcpa_nm <= 0` 或 `cpa_valid == false` 时，禁止输出原拟定的 `COURSE_CHANGE` / `SPEED_CHANGE`；必须改为 `MAINTAIN_COURSE` 或 `MONITOR`，并在 `evidence_items` 中说明评估结果不支持原假设。
- 当 `query_regulatory_context.rules` 为空且 `limitations` 含 `"insufficient_classification_input"` 时，禁止杜撰 COLREGS 引用，应输出 `MAINTAIN_COURSE` 或 `MONITOR`，并在 `summary` 中说明态势分类不充分。
- baseline 工具调用顺序仍包含 Step 3 的 `get_risk_snapshot` / `get_top_risk_targets` / `get_target_detail`；Step 5 是在其上叠加规则查询与机动评估，不替代查询工具。

### 8.2 Chat agent prompt 不强制

[`prompts/system-agent-chat.txt`](../../../backend/map-service/src/main/resources/prompts/system-agent-chat.txt) 不增加 Step 5 的强约束。原因：

- chat 路径用户问题多样，强制每轮调用规则查询会抬高延迟与成本。
- 工具描述（`ToolDefinition.description`）已经在 chat agent 可见的工具目录中说明 `query_regulatory_context` 与 `evaluate_maneuver` 的用途与适用边界，由 LLM 自主决定是否使用。
- 若用户提问明确包含规则 / 让路 / 转向假设，LLM 应自然命中工具调用；不需要 prompt 兜底。

### 8.3 Output parser 调整

`AdvisoryOutputParser` 现有实现仅做结构化校验，不针对 evidence 关键字进行内容检查。Step 5 不需要修改 parser 代码本体，但：

- step3.md §10.2 中“evidence 含 COLREGS / maneuver 关键字应失败”的测试用例需在 Step 5 中删除或改写为“evidence 含 COLREGS 引用且 `query_regulatory_context` 已被调用时通过”。
- Step 5 的 advisory 测试增加对 “LLM 缺失工具调用却输出 COLREGS 关键字” 这一行为的拦截：通过 prompt 约束 + LLM 行为测试覆盖，不引入 parser 内容审查。

---

## 9. File And Module Impact

### 9.1 后端新建文件

| 文件 | 用途 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/encounter/OwnShipRole.java` | 让路 / 直航 / 互让 / 未知 / 不适用枚举 |
| `backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/encounter/EncounterRoleResolver.java` | 由 `EncounterClassificationResult` 推导 `OwnShipRole` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/GraphQueryPort.java` | 法规检索 port |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/RegulatoryQuery.java` | 检索输入 record |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/RegulatoryContext.java` | 检索输出 record |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/Rule.java` | 规则节点 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/EncounterSituation.java` | 相遇态势节点 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/ManeuverAction.java` | 机动动作节点 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/ManeuverActionType.java` | 内部枚举 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/VisibilityCondition.java` | 内部枚举 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/MemoryGraphAdapter.java` | port 的内存实现 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/MemoryGraphLoader.java` | classpath JSON 加载 / 校验 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/QueryRegulatoryContextTool.java` | 法规查询工具 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/builtin/EvaluateManeuverTool.java` | 机动假设评估工具 |
| `backend/map-service/src/main/resources/colregs/rules.json` | COLREGS Part B 规则 + 机动动作语料（v1.0 中英摘要） |

### 9.2 后端修改文件

| 文件 | 改动 |
|---|---|
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentSnapshot.java` | 新增 `ShipStatus frozenOwnShip`、`Map<String, ShipStatus> frozenTargetShips` 字段 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentSnapshotFactory.java` | 在 `build(...)` 中接受并浅拷贝 `ownShip` / `targetShips`，写入两个新字段 |
| `backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/encounter/EncounterClassificationResult.java` | 新增 `ownShipRole` 字段，默认 `UNKNOWN` |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/TargetStateSnapshotDeepCopier.java` | `copyEncounterClassification` 同步拷贝新字段 |
| `backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java` | 两处 `encounterClassifier.classify(...)` 调用后追加 role resolver 调用 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskContextAssembler.java` | 同上：在 `encounterClassifier.classify(...)` 之后写入 ownShipRole |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/AgentToolNames.java` | 追加 `QUERY_REGULATORY_CONTEXT`、`EVALUATE_MANEUVER` 常量 |
| `backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java` | 新增 `Graph` inner class，字段 `enabled` / `resourcePath` |
| `backend/map-service/src/main/resources/application.properties` | 追加 `llm.graph.enabled=true`、`llm.graph.resource-path=classpath:colregs/rules.json` |
| `backend/map-service/src/main/resources/prompts/system-advisory.txt` | 改写规则条款，详见 §8.1 |

### 9.3 前端 / 协议 / 文档

- 前端：无改动。`AdvisoryActionType` 枚举不扩展，`AdvisoryPayload` 无字段调整，前端展示沿用 Step 3 卡片。
- SSE 协议：无改动，`docs/EVENT_SCHEMA.md` 无新增条目。
- [`docs/v1.0/SOURCEBOOK.md`](../SOURCEBOOK.md) §7：在 SOURCEBOOK 单独流程中由用户回填来源 / 许可后，再决定是否补 `Rule.fullText`，不在本步骤同步。
- [`AGENT_LOOP_PLAN.md`](./AGENT_LOOP_PLAN.md) Appendix A：仅在 §12 列出的偏离生效时追加；当前规划本步骤不引入对总览的实质性偏离，无需新增 Active Deviation。
- [`docs/TODO.md`](../../TODO.md) §3：完成本步骤后，在”GraphRAG 历史案例与外部图存储扩展”一条内移除已落地的 “COLREGS 基础图谱” 与 “`EvaluateManeuverTool`” 表述；历史案例数据集、外部图数据库、版本化引用等 backlog 保留原条目，不删整条。

---

## 10. Implementation Order

### Phase 1：上游 role resolver 与字段扩展

- 新增 `OwnShipRole`、`EncounterRoleResolver`。
- 为 `EncounterClassificationResult` 添加 `ownShipRole` 字段并设默认值。
- 在 `ShipDispatcher` 与 `LlmRiskContextAssembler` 调用 resolver。
- 更新 `TargetStateSnapshotDeepCopier`。
- 单测：role 映射决策矩阵覆盖；现有 `ShipDispatcherTest` / 相关 risk 引擎测试通过（不应破坏行为）。

### Phase 2：Graph 内核与资源

- 定义 graph 实体 record / 枚举。
- 实现 `MemoryGraphLoader` 与 `MemoryGraphAdapter`，注册为 Spring bean。
- 编写 `resources/colregs/rules.json`，覆盖 Rules 4–8、11、13–17、19，占位 9 / 10 / 12 / 18。
- 单测：loader 校验失败抛错；adapter 命中 / 未命中三种相遇态势 + 让路 / 直航组合。

### Phase 3：Tools

- 实现 `QueryRegulatoryContextTool`，覆盖 snapshot / 显式参数 / `INSUFFICIENT_CLASSIFICATION` 三条路径。
- 实现 `EvaluateManeuverTool`，覆盖 COURSE / SPEED 双类型与错误路径；与 `CpaTcpaBatchCalculator.calculateOne` 对照。
- `AgentToolNames` 追加常量。
- 单测：每工具独立。

### Phase 4：Prompt 与 advisory loop 集成

- 改写 `system-advisory.txt`，按 §8.1 表述。
- `AdvisoryPromptBuilder` 不变（仍走 `PromptScene.ADVISORY`）。
- 集成测试：使用 stub `LlmClient` 与真实 `AgentToolRegistry`，构造 CROSSING / GIVE_WAY 场景，断言 advisory loop 实际调用 `query_regulatory_context` 与 `evaluate_maneuver`，并最终生成包含规则 ID 与 `delta_dcpa_nm` 的 evidence 列表。

### Phase 5：文档与回归

- 更新 `docs/TODO.md`，按 §9.3 收敛 GraphRAG 项。
- 更新本文件 `执行状态` 为 `completed`，并记录任何 implementation 期间出现的 deviation。
- 运行后端测试套件；运行 `risk` 与 `llm` 模块相关测试；不要求前端测试改动。

---

## 11. Test Plan

### 11.1 `EncounterRoleResolverTest`

- HEAD_ON → MUTUAL_ACTION。
- OVERTAKING → STAND_ON（验证“目标在本船船尾、同向”这一几何含义被正确解读）。
- CROSSING + relativeBearing ∈ (head_on_half, 180) → GIVE_WAY。
- CROSSING + relativeBearing ∈ (180, 360 - head_on_half) → STAND_ON。
- UNDEFINED → UNKNOWN。

### 11.2 `MemoryGraphLoaderTest`

- 合法 JSON 成功加载，`ruleId` / `actionId` 唯一性通过。
- `recommendedActionIds` 引用未知 actionId 时启动失败。
- `applicableSituations` 含未知枚举时启动失败。
- 占位规则（Rule 9 等）不进入命中集合，但被加载并可枚举。

### 11.3 `MemoryGraphAdapterTest`

- CROSSING + GIVE_WAY 命中 Rule 15、Rule 16。
- CROSSING + STAND_ON 命中 Rule 15、Rule 17。
- HEAD_ON + MUTUAL_ACTION 命中 Rule 14。
- OVERTAKING + STAND_ON 命中 Rule 13、Rule 17。
- OVERTAKING + GIVE_WAY（理论上由 future role resolver 改造提供）命中 Rule 13、Rule 16；当前 v1.0 不会出现该组合，但 adapter 仍应稳定返回。
- UNDEFINED / UNKNOWN 输入返回空 `rules` + `limitations = ["insufficient_classification_input"]`。

### 11.4 `QueryRegulatoryContextToolTest`

- `target_id` + snapshot 派生路径成功返回。
- 显式 `encounter_type` 覆盖 snapshot 派生值，`assumptions` 包含 `"override:encounter_type"`。
- 既无 `target_id` 也无 `(encounter_type, own_ship_role)` 时返回 `INVALID_ARGUMENT`。
- snapshot 中目标缺失返回 `TARGET_NOT_FOUND`。
- 派生为 UNDEFINED / UNKNOWN 时返回空命中 + `limitations`。

### 11.5 `EvaluateManeuverToolTest`

- COURSE_CHANGE +20° 在典型交叉态势下：`delta_dcpa_nm > 0` 且 `after.dcpa_nm > before.dcpa_nm`，与手工 CPA 计算误差 < 1%。
- COURSE_CHANGE -20°（左转）：dcpa 不一定改善，断言数值与 calculator 一致。
- SPEED_CHANGE -2 节：sog 减少后 CPA 重算正确。
- `apply_seconds != 0` 返回 `INVALID_ARGUMENT`。
- `magnitude` 超界（COURSE 绝对值 > 180、SPEED 计算后 sog < 0）返回 `INVALID_ARGUMENT`。
- snapshot 缺失本船时返回 `OWN_SHIP_UNAVAILABLE`。
- `target_id` 不存在时返回 `TARGET_NOT_FOUND`。

### 11.6 Advisory loop 集成测试

- 使用 fake `LlmClient` 编排：第 1 轮 `get_risk_snapshot`，第 2 轮 `get_top_risk_targets`，第 3 轮 `get_target_detail`，第 4 轮 `query_regulatory_context`，第 5 轮 `evaluate_maneuver`，第 6 轮 `FinalText`。验证：
  - 工具均被实际调用。
  - 生成的 advisory `evidence_items` 包含来自 `query_regulatory_context` 的 `rule_id` 与来自 `evaluate_maneuver` 的 `delta_dcpa_nm`。
  - `recommended_action.type` 为 `COURSE_CHANGE`，`description` 引用 starboard 转向。
- 反例：`evaluate_maneuver` 返回 `delta_dcpa_nm <= 0` 时，最终 advisory 的 `recommended_action.type` 不再是 `COURSE_CHANGE`，而是 `MAINTAIN_COURSE` 或 `MONITOR`，且 evidence 中保留评估结果。

### 11.7 步骤 3 已有测试调整

- step3.md §10.2 中“evidence 含 COLREGS / maneuver 关键字应被拒绝”的用例：在 Step 5 实施时改写为正向用例，或删除（若 parser 本就不做内容审查）。`AdvisoryOutputParser` 单测不应再断言此类拒绝路径。

### 11.8 回归

- `ShipDispatcherTest`、`RiskAssessmentEngineTest`、`LlmRiskContextAssemblerTest`（如有）应在新增 `ownShipRole` 字段后仍通过；构造方法 / builder 调用方按 `@Builder.Default` 处理新字段。
- 手动验证：开启 `llm.advisory.enabled=true` + `llm.graph.enabled=true`，触发一次 advisory，确认 SSE `ADVISORY` payload 中 `evidence_items` 含规则引用与机动假设事实。

---

## 12. Constraints And Risks

- **OVERTAKING 角色仅几何近似**：`EncounterClassifier` 不验证相对速度收敛，OVERTAKING 判定可能在目标未实际追近时触发，导致 Rule 13 责任表述过度确定。`QueryRegulatoryContextTool` 已在 `assumptions` 中追加标注（见 §7.1），但 LLM 是否恰当使用该标注取决于 prompt 约束质量。若 future 改写 `EncounterClassifier` 把”本船追越目标”也归入 `OVERTAKING`，role resolver 将给出错误的 `STAND_ON`；任何 `EncounterClassifier` 行为变更都需要同步修改 resolver 与 §6.1 决策矩阵。
- **Rule 9 / 10 / 12 / 18 占位风险**：占位节点存在于 graph 中但永不命中，确保 LLM 不会“看到节点”而误用。占位规则的 `applicableSituations` / `applicableRoles` 必须为空数组，loader 校验需断言这一点。
- **加载失败 fail-fast**：MemoryGraphLoader 在启动期失败会阻止整个 LLM 模块上线。若 `llm.graph.enabled=false`，应跳过 loader、不注册 `QueryRegulatoryContextTool`，避免运行期 LLM 看到工具但调用必然失败。
- **EvaluateManeuverTool 与现实船舶能力差距**：v1.0 假设瞬时机动，turning dynamics 缺失。LLM 引用其结果作为 evidence 时，prompt 必须保留 `assumptions` 字段（含 `instantaneous_execution`），避免向操作员暗示该操纵立即可达。
- **Prompt 改写与现有 advisory 行为兼容**：Step 3 advisory 已稳定，prompt 改写后必须保持原有 baseline 工具调用顺序（`get_risk_snapshot` / `get_top_risk_targets` / `get_target_detail`）仍被触发，不能被新工具描述吸收注意力导致原 evidence 退化。集成测试需对此兜底。
- **provider schema 与新工具兼容**：Step 1 的 `chatWithTools` 已支持工具目录；Step 4C 的 provider 路由保留 `gemini` / `zhipu` 两条路径，需在两 provider 上各跑一次集成测试，确保新增工具的 JSON schema 不触发 provider 端的 schema 校验失败。

---

## 13. Deviations

本步骤目标与 `AGENT_LOOP_PLAN.md` §3.8 / §4 Step 5 一致，未超出总览定义的范围。当前未引入对总览正文的实质性偏离，因此不向 `AGENT_LOOP_PLAN.md` Appendix A 新增条目。

若实施期间出现以下情况，应在本节追加 deviation 子条目并同步更新总览 Appendix A：

- 决定将 `ManeuverActionType` 提升为 SSE 协议公开枚举（属于协议层偏离，需要同步前端）。
- 决定在 chat agent prompt 也加入强约束（违背 §8.2，属于范围偏离）。
