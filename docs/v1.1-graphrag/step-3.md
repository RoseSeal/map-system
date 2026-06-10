# step-3 — `query_historical_case_graph` 工具与 advisory evidence 注入

> 版本：[v1.1-graphrag](./OVERVIEW.md) · status: active
> 本步 status：completed
> 最后更新：2026-06-10

---

## 1. 来源与边界（锚定 overview，不重定义）

引自 [OVERVIEW.md](./OVERVIEW.md) step-3 条目，本步不得越界：

- **objective**：将案例检索包装为 agent 工具注册进 `AgentToolRegistry`，更新 advisory prompt 契约与后置校验，使召回案例以**有据可校验**的 `[source: historical_case]` 字符串注入 advisory `evidence_items`。
- **input dependencies**：[step-2](./step-2.md)（port 与 adapter，代码已落库于 `llm/agent/casegraph/`）。
- **deliverables**：① `query_historical_case_graph` 工具（读 `AgentSnapshot` 态势特征），注册进 `AgentToolRegistry`，随 feature flag 联动启停；② `system-advisory.txt` prompt 契约新增 `[source: historical_case]` 引用规则（条件化触发，工具禁用时 prompt 行为回退到 v1.0）；③ advisory 后置 source-grounding 校验；④ `EVENT_SCHEMA.md` §`evidence_items` 增补 historical_case 来源约束；⑤ 覆盖"启用→有据通过""伪造来源→失败""禁用→与 v1.0 一致"三路的测试。
- **DoD**：工具启用且态势相关时，`evidence_items` 出现带具体案例字段的 `[source: historical_case] ...` 条目并通过 source-grounding 校验，且工具可在 chat agent 路径调用；伪造该来源标记而未调用工具时 advisory 判 `ADVISORY_SCHEMA_FAILED`；工具禁用时 advisory 输出与 v1.0 完全一致；`evidence_items` 仍为 `string[]`。
- **scope ceiling**：不升级 evidence 协议为结构化对象；不重写 advisory 主链路（仅在既有 prompt 契约与后置校验上追加 historical_case 一类，不改既有工具的强制调用序列）；工具仅读 snapshot 深拷贝，不读 live 数据。

> 本步对 overview 有一处 `better-approach` 偏离（外部 review 触发）：source-grounding 校验从「与 hydrology 同构（只查调用名）」强化为「成功调用 + case_id 可溯源」，为此对 `AgentLoopResult.Completed` 做增量扩展；prompt 条件化从「静态措辞」升级为「条件化组装」（"Rule 9 落点编号"的原解读随之作废）。详见 §13 与 overview `Active Deviations` 对应条目。

---

## 2. 当前状态与可复用资产

- **step-2 已交付（代码在库）**：`llm/agent/casegraph/` 下 `HistoricalCaseQueryPort`（`findSimilarCases(HistoricalCaseQuery)`）、领域 record（`HistoricalCaseQuery` / `HistoricalCaseResult` / `HistoricalCase` / `CaseRetrievalMetrics`）、`CaseGraphUnavailableException`、`CaseGraphConfig`（`@ConditionalOnProperty(prefix="llm.case-graph", name="enabled", havingValue="true")`）。`HistoricalCaseQuery` 直接复用 `EncounterType` / `OwnShipRole` / `VisibilityCondition` / `RiskLevel` 枚举，snapshot 派生值可直喂。
- **条件化工具样板**（`QueryRegulatoryContextTool`）：`@Component` + `@ConditionalOnProperty` 门控注册；`target_id` → `snapshot.targetDetails().get(id).encounterResult()` 派生 `encounterType` / `ownShipRole`；显式参数可覆写派生值；错误以 `errorResult(call, errorCode, message)` 形态返回 payload。
- **snapshot 内的气象事实**：`riskContext.weather`（`LlmRiskWeatherContext`）含 `visibilityNm` 与 `activeAlerts`；`LOW_VISIBILITY` 告警由 `EnvironmentContextService` 按 `weatherAlertProperties.getLowVisibilityNm()` 阈值统一判定。能见度特征**可以也应当**从 snapshot 派生（外部 review finding 3），无需 LLM 自报。
- **agent loop 预算事实**：每轮迭代恰好一次工具调用或一次最终输出（`AgentLoopOrchestrator` 主循环）；advisory 预算 `LlmProperties.Advisory#maxIterations` 默认 **5**，`application.properties` 未覆写。prompt 强制序列在最坏情形下需 7 次工具调用 + 1 次最终输出 = 8 轮（见 §3.7），现值必然触发 `MaxIterationsExceeded`（外部 review finding 1）。
- **registry 装配机制**：`AgentToolRegistry` 构造器注入 `List<AgentTool>`——工具 bean 不存在时 registry 自动不含该工具，无需改 registry。advisory 与 chat 共用 `AgentLoopOrchestrator` + 同一 registry，工具注册即两路可用。
- **降级语义的既有事实**（`AgentLoopOrchestrator` L91–106）：工具**抛异常** → `AgentLoopResult.toolFailed` → advisory 整轮终止；工具**返回 ERROR payload** → 作为 tool result 回流给 LLM，loop 继续。同时注意：`calledToolNames` 在 execute 不抛异常时**一律记录**，不区分 payload 成败——这是 finding 2 的根源。
- **source-grounding 校验样板**（`AdvisoryService.hydrologyEvidenceHasToolSource`）：只校验"标记出现 ⇒ 工具名在 `calledToolNames`"。该强度对 historical_case 不够（检索失败/空召回后 LLM 仍可编造并通过），本步按 §3.4 强化；hydrology 既有校验**不动**（scope ceiling）。
- **prompt 装配事实**：`AdvisoryPromptBuilder` 经 `promptTemplateService.getSystemPrompt(PromptScene.ADVISORY)` 加载静态 `system-advisory.txt`。静态文件无论 flag 与否都进 LLM 输入——"禁用时输出与 v1.0 完全一致"要求禁用路径的 prompt **字节一致**（finding 4），故采用条件化组装（§3.5）。

---

## 3. 设计决策与被否方案

### 3.1 工具门控复用 `llm.case-graph.enabled`，不另立 flag

工具类标注 `@Component` + `@ConditionalOnProperty(prefix="llm.case-graph", name="enabled", havingValue="true")`，构造器注入 `HistoricalCaseQueryPort`。与 port bean 同 flag 联动（step-2 §3.6 预留的约定）：禁用时工具 bean 与 port bean 同时不存在，注入关系自洽。

**被否**：为工具单设 flag——产生"工具有、port 无"的非法组合（启动失败），且 overview DoD #2/#3 要求的就是单一开关联动。

### 3.2 降级语义：sidecar 不可用 → ERROR payload，不上抛

工具内捕获 `CaseGraphUnavailableException`，返回 `status: ERROR, error_code: CASE_GRAPH_UNAVAILABLE` 的 `ToolResult`，message 告知 LLM 可在无案例证据下继续。

**理由**：案例证据是增强性证据，不是 advisory 成立的必要条件；异常上抛会把整轮 advisory 杀成 `LLM_REQUEST_FAILED`，让可选 sidecar 的可用性绑架主链路。ERROR 路径下的"事后编造"风险由 §3.4 的强化校验兜住（ERROR 调用不产生可溯源 case_id，编造必被拒）。

**被否**：异常上抛 → `ToolFailed`——sidecar 未起（开发期常态）即全场无 advisory，故障半径远超功能价值。

### 3.3 入参设计：snapshot 派生为主，检索专属特征经参数补充

snapshot 可派生：`encounterType` / `ownShipRole`（`targetDetails[].encounterResult()`）、`riskLevel`（`riskContext.targets[]`）、`targetCount`（`riskContext.targets` 计数）、**`visibilityCondition`**（`riskContext.weather`，见下）。snapshot 没有的检索特征：`water_area`、`query_text`——由 LLM 经参数提供，均可选。

**能见度派生规则**（finding 3 修正，复用 `EnvironmentContextService` 已判定的告警，不在工具内重设阈值）：
- `riskContext.weather == null` 或 `visibilityNm == null` → `UNKNOWN`
- `activeAlerts` 含 `LOW_VISIBILITY` → `RESTRICTED_VISIBILITY`
- 其余 → `OPEN_VISIBILITY`

`visibility_condition` 参数保留为显式覆写入口（chat 路径用户假设性提问场景），缺省走派生。

`mode` / `topK` 不暴露给 LLM：传 `null` 由 adapter 取 `CaseGraphProperties` 默认。`ownShipType` / `targetShipType` 本版本恒传 `null`：`ShipStatus` 无船型字段，无可信来源。

**被否**：`visibility_condition` 缺省 `OPEN_VISIBILITY`（沿用 `QueryRegulatoryContextTool` 的默认）——雾天向 sidecar 传错误特征直接污染 rerank；snapshot 有数据时自报/默认都不可取。
**被否**：暴露 `top_k` / `mode` 给 LLM——增加误用面，demo 无调参价值；运维调参走 properties。
**被否**：让 LLM 自由填 `own_ship_type`——无 snapshot 依据，等于邀请编造，违背 Rule 1。

### 3.4 source-grounding 强化：成功调用 + case_id 可溯源（overview 偏离，better-approach）

overview 原表述为「与 hydrology 校验同构」（只查 `calledToolNames`）。该强度下，工具返回 ERROR 或空 `cases` 时调用名照样在列，LLM 事后编造案例可通过校验，"有据可校验"落空（外部 review finding 2，P1）。本步强化为两级：

1. **成功前提**：evidence 出现 `[source: historical_case]` 标记时，本轮必须存在该工具的**成功**调用（payload `status == "OK"` 且 `cases` 非空）；
2. **case_id 溯源**：每条带标记的 evidence 必须包含成功调用返回集合中的某个完整 `case_id` token（大小写不敏感，ID 两侧不得继续连接 ASCII 字母、数字、下划线或连字符；prompt 规则同步强制 evidence 写明 case_id，见 §6）。

为让 `AdvisoryService` 看得到工具 payload，对 `AgentLoopResult.Completed` 做**增量扩展**：新增 `List<ToolResult> toolResults` 字段（orchestrator 在既有 `calledToolNames.add(...)` 同点位收集）。这超出 scope ceiling「仅在 prompt 与后置校验上追加」的字面边界，已按规程登记 overview `Active Deviations`（见 §13）。`calledToolNames` 语义不变，hydrology 校验零改动。

**被否**：维持 hydrology 同等强度 + 文档声明局限——hydrology 的失败模式罕见（进程内数据），而 sidecar 失败是设计内常态（flag 误开、未 bootstrap），同等强度在本场景等于无校验。
**被否**：只校验"成功调用"不查 case_id——成功召回后仍可整条编造案例内容，溯源粒度不足以支撑 DoD 的"有据可校验"；case_id 子串匹配的误杀风险由 prompt 强制写 case_id 缓解（§14 列为已接受风险）。

### 3.5 prompt 条件化组装：fragment 仅在工具在目录时追加（overview 偏离，better-approach）

静态文件内写"仅当工具目录包含…时生效"的条件化**措辞**，无法满足 DoD #5"禁用时输出与 v1.0 完全一致"——规则文本无论 flag 与否都改变 LLM 输入（外部 review finding 4）。改为**条件化组装**：

- 基底 `system-advisory.txt` **零改动**（禁用路径 prompt 与 v1.0 字节一致）；
- 新增 fragment 文件 `system-advisory-casegraph.txt`（`PromptScene` 新增 `ADVISORY_CASE_RULE` 条目），内容为编号 11 的新规则（基底现有 Rule 1–10 之后顺延）；
- `AdvisoryPromptBuilder` 注入 `AgentToolRegistry`，当 `getToolDefinitions()` 含 `query_historical_case_graph` 时把 fragment 追加到基底之后。以"工具目录包含"为判据（而非直接读 flag），与 overview 的条件化语义逐字对齐，且与 registry 装配状态天然一致。

原 rev.0 的"插入为新 Rule 9、原 9/10 顺延"方案随之作废；overview deliverables 中"Rule 9"编号表述一并进入偏离记录（§13）。

**被否**：静态措辞条件化（rev.0 方案）——LLM 输入仍变，DoD #5 论证不成立。
**被否**：改写 DoD #5 为"调用拓扑与 schema 兼容"——overview DoD 在 active 期间不回写正文；条件化组装能让 DoD 字面成立，没有理由削弱验收。
**被否**：基底文件留 `%s` 占位符 + `formatted()`——禁用时渲染结果与 v1.0 是否字节一致取决于空串与换行的拼接细节，脆弱；追加式组装无此问题。

### 3.6 工具 payload 不透出 `answer` 与 `metrics`

`HistoricalCaseResult.answer`（LightRAG 生成的长文）不进 tool payload：二手综述膨胀 token 且诱导复述而非引用案例字段；step-2 §4.2 已注明其主要供 step-4。`metrics` 仅落日志（debug 级）。

**被否**：透传 `answer` 供 LLM"参考"——与 Rule 1（只基于工具事实）张力大，案例字段已足够支撑 evidence 组装。

### 3.7 advisory 轮次预算：`maxIterations` 5 → 10

最坏路径的强制调用序列：`get_risk_snapshot` + `get_top_risk_targets` + `get_target_detail`（Rule 2，3 轮）+ `query_regulatory_context`（Rule 3）+ `evaluate_maneuver`（Rule 4）+ `evaluate_maneuver_hydrology`（Rule 6）+ `query_historical_case_graph`（新规则）= 7 轮工具 + 1 轮最终 JSON = **8 轮**，现默认 5 必然 `MaxIterationsExceeded`（finding 1，P1；v1.0 的 7 轮最坏路径其实已超预算，本步一并修正）。

改法：`LlmProperties.Advisory#maxIterations` 默认 5 → **10**（8 轮刚性需求 + 2 轮余量，容纳 LLM 自发的 `get_weather_context` 或单次重试），并在 `application.properties` 显式登记 `llm.advisory.max-iterations=10` 作为运维可调入口。预算回归由 orchestrator 层测试覆盖（§9）。

**被否**：只改 properties 不改代码默认——默认值仍是坏值，测试与未带配置的环境照样踩坑。
**被否**：重新设计调用策略（合并工具/放宽强制序列）——改既有工具的强制调用序列违反 scope ceiling。

---

## 4. 工具设计

### 4.1 类与注册

```java
// llm/agent/tool/builtin/QueryHistoricalCaseGraphTool.java
@Component
@ConditionalOnProperty(prefix = "llm.case-graph", name = "enabled", havingValue = "true")
public class QueryHistoricalCaseGraphTool implements AgentTool {
    public QueryHistoricalCaseGraphTool(ObjectMapper mapper, HistoricalCaseQueryPort caseQueryPort) { ... }
}
```

`AgentToolNames` 新增常量：`QUERY_HISTORICAL_CASE_GRAPH = "query_historical_case_graph"`。

### 4.2 输入 schema（全部 optional，`required: []`）

| 参数 | 类型 | 说明 |
|---|---|---|
| `target_id` | string | 给定时从 snapshot 派生 `encounter_type` / `own_ship_role` / `risk_level`；查不到判 `TARGET_NOT_FOUND` |
| `encounter_type` | string | 覆写派生值。Enum: `HEAD_ON` / `OVERTAKING` / `CROSSING` / `UNDEFINED` |
| `own_ship_role` | string | 覆写派生值。Enum: `GIVE_WAY` / `STAND_ON` / `MUTUAL_ACTION` / `UNKNOWN` / `NOT_APPLICABLE` |
| `visibility_condition` | string | 覆写 snapshot 派生值（§3.3 派生规则）。Enum: `OPEN_VISIBILITY` / `RESTRICTED_VISIBILITY` / `UNKNOWN` |
| `water_area` | string | 受控词表（step-1 §5）：`开阔水域` / `限制水域` / `狭水道`；缺省 `null` |
| `query_text` | string | 自然语言检索补充（chat 路径主要入口）；缺省 `null` |

### 4.3 执行行为

1. 解析参数；`target_id` 给定 → 从 `snapshot.targetDetails()` 取 `encounterResult()` 派生 `encounterType` / `ownShipRole`，从 `snapshot.riskContext().getTargets()` 按 `targetId` 匹配取 `riskLevel`；显式参数覆写派生值（枚举解析失败判 `INVALID_ARGUMENT`）。
2. `visibilityCondition` 按 §3.3 规则从 `riskContext.weather` 派生，显式参数覆写。
3. `riskLevel` 无 target 来源时取场景最高（遍历 `riskContext.targets[].riskLevel` 取 max，空则 `null`）；`targetCount` = `riskContext.targets` 计数（空则 0）。
4. 前置校验：`encounterType`、`ownShipRole`、`queryText` 全空 → `INVALID_ARGUMENT`（"either target_id, encounter_type/own_ship_role or query_text must be provided"），**不调 port**（对齐 adapter 的 `IllegalArgumentException` 前置条件，step-2 §4.3）。
5. 构造 `HistoricalCaseQuery`（`ownShipType` / `targetShipType` / `mode` / `topK` 传 `null`），调 `caseQueryPort.findSimilarCases(query)`。
6. `CaseGraphUnavailableException` → `status: ERROR, error_code: CASE_GRAPH_UNAVAILABLE`，message 注明"historical case retrieval unavailable; continue advisory without case evidence"（§3.2）。
7. 成功 → OK payload（§4.4）；`cases` 为空列表照常返回（`status: OK` + 空数组）——注意空召回在 §5 校验中**不**构成可引用前提。

### 4.4 输出 payload（snake_case，对齐既有工具风格）

```
status: "OK"
snapshot_version: <long>
query: { encounter_type, own_ship_role, visibility_condition, risk_level, water_area, query_text }   # 实际生效值回显，null 字段以 null 输出
cases: [ { case_id, title, relevance, water_area, visibility, own_ship_role,
           encounter_type, risk_level, target_summary, colregs_rules[], outcome,
           action_digest, lesson } ]
```

`HistoricalCase` 的描述性字段按 step-2 约定为 `String` 透传，不强转枚举。`answer` / `metrics` 不透出（§3.6）。

---

## 5. AgentLoopResult 扩展与 AdvisoryService 校验

### 5.1 `AgentLoopResult.Completed` 增量扩展

`Completed` record 新增 `List<ToolResult> toolResults` 字段；`AgentLoopOrchestrator` 在 `calledToolNames.add(...)` 同点位收集 `toolResult`，经 `completed(...)` 工厂传入（防御性 `List.copyOf`）。`calledToolNames` 保留且语义不变（hydrology 校验与潜在其他消费方零影响）。record 构造器变更会波及既有构造点（orchestrator 与相关测试），属机械同步。

### 5.2 historical_case grounding 校验

`AdvisoryService` 新增私有方法，在 `hydrologyEvidenceHasToolSource` 检查之后串接（`Completed` 分支）：

```java
private boolean historicalCaseEvidenceGrounded(
        AdvisoryOutputParser.ParsedAdvisory parsed,
        AgentLoopResult.Completed completed
) { ... }
```

行为：

1. 取 `evidenceItems` 中匹配 `\[source:\s*historical_case\]`（大小写不敏感）的条目集合；允许冒号后无空格，集合为空 → 通过（工具禁用场景恒真，零行为差异）。
2. 从 `completed.toolResults()` 过滤 `toolName == QUERY_HISTORICAL_CASE_GRAPH` 且 payload `status == "OK"` 的结果，汇集其 `cases[].case_id`（lowercase）。
3. case_id 集合为空（未调用 / 全 ERROR / 全空召回）→ 返回 false。
4. 否则要求**每条**带标记条目含集合中至少一个完整 case_id token；前后 ASCII 标识符字符边界阻止 `H-0` 对 `H-07` 的前缀误匹配 → 全满足才通过。

返回 false → `publishSchemaFailed()`（复用既有 `ADVISORY_SCHEMA_FAILED` 发布路径）。hydrology 既有校验不动。

---

## 6. prompt 契约改动

**基底 `system-advisory.txt` 零改动**（§3.5）。新增 fragment `prompts/system-advisory-casegraph.txt`，`PromptScene` 新增 `ADVISORY_CASE_RULE("system-advisory-casegraph.txt")`；`AdvisoryPromptBuilder` 在工具目录含 `query_historical_case_graph` 时追加到基底之后。fragment 文本（编号顺延基底 Rule 10）：

> 11. 在确定 recommended_action 前应调用 query_historical_case_graph 检索历史相似案例。若 evidence_items 引用历史案例事实，必须来自该工具的 tool result，且单条 evidence 必须包含 [source: historical_case] 标记、所引用案例的 case_id 与具体字段值，例如"[source: historical_case] 案例 HC-007（狭水道 CROSSING，NEAR_MISS）：让路船延迟转向致 DCPA 0.1 nm，教训为及早大幅右转"。若该工具返回 ERROR 或 cases 为空，严禁编造案例，应在无案例证据下继续生成建议，且不得输出 [source: historical_case] 标记。

要点：强制写 case_id 与 §5.2 的溯源校验闭合；末句封死"检索失败就编造"路径并与校验规则（失败/空召回时出现标记即拒）对齐。不改既有 Rule 2 强制调用序列与其他规则文本（scope ceiling）。`ChatAgentPromptBuilder` 不动——chat 路径靠工具自身 description 即可发现该工具。

---

## 7. `EVENT_SCHEMA.md` 改动

§4 ADVISORY `evidence_items` 字段说明行，在既有 hydrology 约束后并列追加：

> 历史相似案例事实必须包含 `[source: historical_case]` 与所引用案例的 `case_id`，且来源为 `query_historical_case_graph` 的成功 tool result。

不改 `evidence_items` 类型（仍 `string[]`）、不改事件结构与错误码（`ADVISORY_SCHEMA_FAILED` 已存在）。

---

## 8. 配置改动

- `LlmProperties.Advisory#maxIterations` 默认 5 → 10（§3.7）。
- `application.properties` 显式登记 `llm.advisory.max-iterations=10`。
- 其余复用 step-2 的 `llm.case-graph.*`（默认 `enabled=false`，本地经 `application-local.properties` 覆写启用），无新增键。

---

## 9. 测试计划

| 层级 | 测试 | 手段 | 门控 |
|---|---|---|---|
| 工具单元 | `QueryHistoricalCaseGraphToolTest`：① `target_id` 派生路径 → 断言传给 port 的 `HistoricalCaseQuery` 字段与 payload cases 映射；② 显式参数覆写派生值；③ **能见度派生三态**（weather null → `UNKNOWN`；`activeAlerts` 含 `LOW_VISIBILITY` → `RESTRICTED_VISIBILITY`；正常 → `OPEN_VISIBILITY`）；④ port 抛 `CaseGraphUnavailableException` → `status: ERROR, error_code: CASE_GRAPH_UNAVAILABLE`（不上抛）；⑤ 特征与 `query_text` 全空 → `INVALID_ARGUMENT` 且 port 零交互；⑥ `target_id` 查不到 → `TARGET_NOT_FOUND`；⑦ `cases: []` → `OK` + 空数组 | Mockito mock `HistoricalCaseQueryPort` + 手工构造 `AgentSnapshot` | 是 |
| source-grounding | `AdvisoryServiceTest` 新增：① 标记 + 成功调用且 evidence 含返回 case_id → 正常发布；② 标记 + 未调用工具 → `ADVISORY_SCHEMA_FAILED`；③ 标记 + 调用返回 ERROR → 失败；④ 标记 + 调用返回空 `cases` → 失败；⑤ 标记 + 成功调用但 evidence 不含任何返回 case_id → 失败；⑥ 无标记 + 各种调用状态 → 不受影响；⑦ `[source:historical_case]` 无空格变体仍进入校验；⑧ case_id 前缀不得误匹配更长 ID | 既有测试基建（stub orchestrator 结果，`Completed` 填充 `toolResults`） | 是 |
| 轮次预算 | orchestrator 层：stub `LlmClient` 依次发出最坏路径 7 次工具调用 + 1 次最终输出，`maxIterations=10` → 断言 `Completed`（非 `MaxIterationsExceeded`），且 `toolResults` 与 `calledToolNames` 等长对应 | stub `LlmClient` + stub 工具 | 是 |
| prompt 组装 | `AdvisoryPromptBuilder`：工具在 registry 目录 → system 消息为基底 + fragment；不在 → system 消息与基底文件内容**完全一致** | 注入含/不含该工具的 registry | 是 |
| 条件装配 | `enabled=true`（+ mock port bean）→ context 含 `QueryHistoricalCaseGraphTool` bean；`enabled=false` / 缺省 → 不含 | `ApplicationContextRunner` 仅注册工具类与 mock port | 是 |
| 禁用回归 | 既有 `AdvisoryServiceTest` 全量保持绿（无标记 evidence 在新校验下恒通过，断言零改动，仅 `Completed` 构造点机械同步） | 既有套件 | 是 |
| 端到端 | 启用 flag + 起 sidecar（step-1 bootstrap 后），触发 advisory 观察 `evidence_items` 出现带 case_id 的 `[source: historical_case]` 条目并通过校验；chat 路径提问历史相似案例可触发工具调用（`AGENT_STEP` 可见） | 人工验收，不进 CI | 否（DoD 人工核验项） |

"禁用→输出与 v1.0 完全一致"的论证（rev.1 起成立）：(i) 工具 bean 条件装配（条件装配测试）；(ii) **prompt 字节一致**（prompt 组装测试的"不在"分支）；(iii) 新校验在无标记时恒通过（禁用回归行）。三点合计禁用路径的 LLM 输入与后置处理与 v1.0 逐字相同。

---

## 10. 文件影响清单

新增：
- `llm/agent/tool/builtin/QueryHistoricalCaseGraphTool.java`
- `src/main/resources/prompts/system-advisory-casegraph.txt`（fragment）
- 测试：`QueryHistoricalCaseGraphToolTest`（含条件装配用例，或拆分一个装配测试类）

修改：
- `llm/agent/tool/AgentToolNames.java`：+`QUERY_HISTORICAL_CASE_GRAPH` 常量
- `llm/agent/AgentLoopResult.java`：`Completed` +`toolResults` 字段与工厂同步（§5.1）
- `llm/agent/AgentLoopOrchestrator.java`：收集 `toolResults`（与 `calledToolNames` 同点位，约 +3 行）
- `llm/agent/advisory/AdvisoryService.java`：+`historicalCaseEvidenceGrounded` 方法与调用点
- `llm/agent/advisory/AdvisoryPromptBuilder.java`：注入 `AgentToolRegistry`，条件追加 fragment
- `llm/prompt/PromptScene.java`：+`ADVISORY_CASE_RULE` 条目
- `llm/config/LlmProperties.java`：`Advisory#maxIterations` 默认 5 → 10
- `src/main/resources/application.properties`：+`llm.advisory.max-iterations=10`
- `docs/EVENT_SCHEMA.md`：`evidence_items` 字段说明行追加 historical_case 约束
- 测试：`AdvisoryServiceTest`（+5 用例 + `Completed` 构造点同步）、orchestrator 测试（+预算用例 + 构造点同步）、`AdvisoryPromptBuilder` 测试（+2 用例）

不修改：
- `src/main/resources/prompts/system-advisory.txt`（基底零改动，§3.5）
- `AgentToolRegistry` / `AdvisoryOutputParser` / hydrology 既有校验逻辑
- `llm/agent/casegraph/` 全部（step-2 交付物，本步只消费）
- 既有工具类、`ChatAgentPromptBuilder`、chat/websocket 侧

---

## 11. 不变式

- `evidence_items` 协议保持 `string[]`；historical_case 来源约束与 hydrology 约束同级并列，不引入结构化对象。
- 工具仅读 `AgentSnapshot` 深拷贝，不触达 live store / `RiskContextHolder`。
- `llm.case-graph.enabled=false` 时：工具 bean 不存在、registry 工具目录与 v1.0 一致、**system prompt 与 v1.0 字节一致**、新校验恒通过——advisory 的 LLM 输入与输出处理与 v1.0 完全一致。
- sidecar 不可用只降级该工具的单次调用（ERROR payload），不终止 advisory 轮次、不影响其他工具；但失败/空召回调用**不**赋予 evidence 引用资格（§5.2）。
- `calledToolNames` 语义不变；`toolResults` 为纯增量字段，hydrology 校验与既有消费方零改动。
- 既有工具的强制调用序列（Rule 2）与既有规则文本零改动。

---

## 12. 本步不做（显式分类）

| 项 | 分类 | 何时/何处 |
|---|---|---|
| 端到端 advisory 前后对比截图（评测 track 素材） | deferred | step-4（其依赖项，overview Sequencing 已载明） |
| `evidence_items` 结构化对象升级 | not doing | overview non-goal；已由 `docs/TODO.md` §3 既有条目承载 |
| `own_ship_type` / `target_ship_type` 特征接入（`ShipStatus` 无船型字段） | blocked | 触发条件：AIS 静态报文（船型字段）接入 `ShipStatus` 后，在工具内补派生逻辑；属 `docs/TODO.md` §2「多源数据融合」/「基于安全领域的避碰责任判定」所需的同一前置，不单独立项 |
| `QueryRegulatoryContextTool` 的能见度同款派生（其缺省仍为 `OPEN_VISIBILITY`） | not doing（本步） | 即 `docs/TODO.md` §3 既有条目「`query_regulatory_context` 能见度受限（Rule 19）完整推理」，owner 已在 TODO，不因本步重复登记；本步派生逻辑可供其届时复用 |
| hydrology source-grounding 同款强化（成功状态 + 字段溯源） | deferred | 触发条件：hydrology 出现实际失败模式或 v1.2 出现第三类来源标记时，连同 §3.4 被否的"通用映射表校验"一并统一；`toolResults` 基建本步已就位，届时为纯校验层改动 |
| 案例检索结果的前端专项展示（卡片/角标） | not doing | evidence 仍是字符串条目，前端按既有 `evidence_items` 渲染；专项展示属 TODO §4 visual track 范畴 |
| sidecar 真实检索质量验证 | deferred | step-4 评测 track |

无新增 `docs/TODO.md` 条目：deferred 项有具名 step 或具体触发条件且基建已留痕（`toolResults`）；blocked / not-doing 均挂靠 TODO 既有条目或 overview non-goals。

---

## 13. 偏离记录

- **2026-06-10 / `better-approach`（外部 review 触发，P1×2 + P2×1）**：① source-grounding 从 overview DoD #4 的「与 hydrology 校验同构（只查调用名）」强化为「成功调用 + case_id 溯源」，为此 `AgentLoopResult.Completed` 增 `toolResults` 字段——超出 scope ceiling「仅在 prompt 与后置校验上追加」的字面边界；② prompt 条件化从 overview deliverables ② 的「条件化措辞（Rule 9 落点）」改为「条件化组装（fragment 追加，基底零改动）」，否则 DoD #5「禁用时输出完全一致」不可达。已追加 overview `Active Deviations` 对应条目（受影响正文：Version DoD #4 / step-3 deliverables ②③ 与 scope ceiling）。
- `maxIterations` 5 → 10（§3.7）不属 overview 偏离：overview 未规定轮次预算，且 v1.0 最坏路径已超预算，属 `doc-code-inconsistency` 之外的既有缺陷修正，随本步落地。
- §3.2（降级语义）、§3.3（入参与能见度派生）、§3.6（payload 裁剪）为 step-2 显式留给本步的决策或本步自有实现选择，均在 scope ceiling 内。

---

## 14. 风险与假设

- **假设**：step-2 代码（`llm/agent/casegraph/`）通过 review 后契约不变；若 review 调整 port 签名或 DTO 字段，本步 §4 同步跟随（契约真值在 step-2）。
- **风险（已接受）**：LLM 写了真实案例但漏写完整 case_id token 时 advisory 会被判 schema 失败。缓解：fragment 规则强制每条案例 evidence 含 case_id（§6），且示例给出标准格式；该误杀换来的是伪造或 ID 前缀混淆不通过，符合"有据可校验"的优先级。
- **风险**：prompt"应调用"措辞下，LLM 可能在态势不相关时也调用工具，增加每轮一次 sidecar 往返（timeout 10 s 上限）→ 可接受：检索失败/为空均不阻断；若实测调用率过高，调整 fragment 措辞为风险等级条件触发（纯 prompt 文本改动）。
- **风险**：`maxIterations=10` 下最坏路径 LLM 调用次数上升，advisory 延迟与 token 成本增加 → 序列由 prompt 强制项决定而非预算放宽引入（预算只是不再截断）；step-4 评测的延迟/token 辅助指标可量化该影响。
- **风险**：`cases` 全空（语料 10–20 条覆盖有限）导致 demo 场景无案例证据 → step-1 §5 覆盖要求已对齐三类会遇几何 + 特殊条件；demo 场景选择时与语料覆盖对表（step-4 评测亦会暴露盲区）。
