# v1.1-graphrag Milestone — Closure Summary

> 文档状态：archived（v1.1 closure summary）
> 关闭日期：2026-07-01
> 用途：归档 v1.1-graphrag milestone 的目标、产出与收口结论；指引读者从这里跳转到 current truth。
> 非目标：本文与本目录下任何文件**都不再是 current truth**。当前实现真值以 active docs 为准。

---

## 1. v1.1 目标回顾

v1.1-graphrag 是项目从"法规图谱 + 结构化 advisory evidence"演进到"历史相似案例 GraphRAG 证据分支"的实施 milestone。

里程碑聚焦一条主线与一条验证 track：

| Track | Release impact | 收口形态 |
| --- | --- | --- |
| `step-1` 历史案例语料与 LightRAG sidecar | 阻塞 v1.1 主版本 | 完成 |
| `step-2` `HistoricalCaseQueryPort` 与 Java adapter | 阻塞 v1.1 主版本 | 完成 |
| `step-3` `query_historical_case_graph` 工具与 advisory evidence 注入 | 阻塞 v1.1 主版本 | 完成 |
| `step-4` Graph-RAG 检索效果评测 | 不阻塞 | 完成 |

## 2. 已完成的 step 与主要系统增量

### 2.1 Step 1：历史案例语料与 Graph-RAG 索引服务

- 新增顶层 `graphrag-service/` Python sidecar，提供 `GET /health` 与 `POST /retrieve`。
- 使用 `data/cases/*.md` 合成案例语料离线构建 LightRAG index，index 产物落在 `runtime/index/` 并被 gitignore。
- `compose.yaml` 新增 `graphrag` 服务，服务启动加载既有 index，不在 boot-time 重建索引。
- 存储后端确定为 LightRAG 文件型本地存储（NetworkX + nano-vectordb + 本地 JSON），不复用 `postgis`，不引入 Neo4j。

### 2.2 Step 2：Java 侧 port 与 adapter

- 新增 `HistoricalCaseQueryPort`，与 `GraphQueryPort` 并存。
- 新增 `LightRagCaseGraphAdapter`，通过 HTTP 调用 sidecar `/retrieve` 并映射历史案例 DTO。
- 新增 `llm.case-graph.enabled` 独立 feature flag；默认关闭，启用时加载 port 与后续工具 bean。
- 保持 `GraphQueryPort` / `MemoryGraphAdapter` 的 COLREGS 规则图链路不变。

### 2.3 Step 3：历史案例工具与 advisory evidence 注入

- 新增 `query_historical_case_graph` 工具，并注册进 `AgentToolRegistry`。
- 工具从 `AgentSnapshot` 读取会遇类型、让路角色、能见度、风险等级等态势特征，不读 live state。
- 新增条件化 `system-advisory-casegraph.txt` prompt fragment；工具可用时追加历史案例引用规则，工具禁用时基底 advisory prompt 回退到 v1.0。
- advisory 后置校验要求 `[source: historical_case]` evidence 引用本轮成功 tool result 中返回的 `case_id`；未成功召回或 case_id 不匹配时判 `ADVISORY_SCHEMA_FAILED`。
- `evidence_items` 协议保持 `string[]`，未升级为结构化对象。

### 2.4 Step 4：非门槛评测 track

- 新增 `graphrag-service/eval/`，包含查询集、四组对照 runner、judge、结果 CSV 与图表输出。
- 评测对比 naive embedding、LightRAG local、global、hybrid 四组，指标为 Comprehensiveness / Diversity / Empowerment，并记录 latency、token、`retrieval_source` 等辅助遥测。
- 检索/生成沿用智谱兼容 OpenAI API 配置；judge 走非智谱本机 CLI，默认 Gemini CLI，可切换 `agy` / `codex` / `copilot` / `claude`。
- 评测分数仅作为课程汇报和方法验证材料，不作为 v1.1 产品验收门槛。

### 2.5 协议层产出

v1.1 对 `ADVISORY` payload 结构无字段升级；`evidence_items` 仍为 `string[]`。协议真值已沉淀到 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) §变更记录：

- `ADVISORY.evidence_items` 增补 `[source: historical_case]` 来源约束。
- 历史相似案例事实必须引用 `query_historical_case_graph` 成功 tool result 中返回的 `case_id`。

## 3. 决策沉淀至 ADR

下列 v1.1 相关关键决策已收口至 [`../../ADR_AND_REVIEW_FINDINGS.md`](../../ADR_AND_REVIEW_FINDINGS.md)：

- ADR-007：法规知识增强与历史危险场景检索优先采用 `GraphRAG`，而不是普通 RAG。
- ADR-012：v1.0 阶段 COLREGS Part B 采用进程内规则图，并将外部图数据库与历史案例图推迟到 v1.1+。
- ADR-013：历史案例 GraphRAG 采用独立 port + HTTP sidecar + LightRAG 文件型本地存储，评测作为非门槛验证 track。

ADR 之外的实施细节（step plan 正文、评测脚本说明、外部资料快照）仅作为历史过程文档归档于本目录，不再回写当前真值。

## 4. 这里只是历史，不是当前真值

本目录下任何文件**都不承担 current truth**。如果当前实现与历史 step 描述发生冲突，**以下列 active 文档为准**：

- 架构总览：[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)
- 协议真值：[`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)
- 前端架构：[`../../frontend-design.md`](../../frontend-design.md)
- 决策与复盘：[`../../ADR_AND_REVIEW_FINDINGS.md`](../../ADR_AND_REVIEW_FINDINGS.md)
- 任务到文件导航：[`../../../CURRENT_SYSTEM_OVERVIEW.md`](../../../CURRENT_SYSTEM_OVERVIEW.md)
- 项目状态：[`../../../PROJECT_STATUS.md`](../../../PROJECT_STATUS.md)

历史步骤文档可能引用已重构或重命名的字段、模块路径、评测结果与协议形态；引用前请在当前代码或当前真值文档中复核。

## 5. 目录索引（仅供历史追溯）

- [`OVERVIEW.md`](./OVERVIEW.md)：v1.1-graphrag 版本总览与已并合的收口正文。
- [`step-1.md`](./step-1.md)：历史案例语料、LightRAG index、HTTP sidecar 与存储后端决策。
- [`step-2.md`](./step-2.md)：`HistoricalCaseQueryPort`、LightRAG HTTP adapter 与 feature flag。
- [`step-3.md`](./step-3.md)：`query_historical_case_graph` 工具、prompt fragment 与 historical_case source-grounding。
- [`step-4.md`](./step-4.md)：Graph-RAG 检索效果评测、judge 后端、`retrieval_source` 遥测与结果产物。
- [`external/`](./external/)：v1.1 期间保存的外部法规资料快照。
