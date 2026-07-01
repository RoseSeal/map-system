# v1.1-graphrag — 历史海事案例图检索（Graph-RAG）版本总览

> name: map-system v1.1-graphrag
> description: 在 advisory `evidence_items` 链路上新增"历史相似海事案例图检索"分支——以 Graph-RAG（LightRAG sidecar）实现，包装为独立 port 与 agent 工具，向 advisory 注入带 `[source: historical_case]` 来源标记的案例证据。
> last_updated: 2026-07-01
> status: completed

---

## Derives From

- [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §三 技术栈（COLREGS Part B 进程内规则图与历史案例 GraphRAG 分支并存）与 §八 Roadmap P3（v1.1 已交付历史相似案例证据分支，生产级外部图数据库继续不在当前主线内）。
- [`../../ADR_AND_REVIEW_FINDINGS.md`](../../ADR_AND_REVIEW_FINDINGS.md)：ADR-007（法规与历史危险场景检索走 GraphRAG，不走普通 RAG）、ADR-012（外部图数据库与历史案例图推迟到 v1.1+）。
- v1.1 启动时承接的 GraphRAG 历史案例 backlog 已随本版本落地，当前 `TODO.md` 不再保留该已排期条目。

---

## Positioning

v1.1 在 v1.0 已收口的 agent loop + 结构化 advisory 基础上，**新增一条历史案例检索分支**，不改写 advisory 主链路。

v1.0 已交付：`AgentToolRegistry` + 十个查询/评估工具（含 `query_regulatory_context` / `evaluate_maneuver` / `query_bathymetry` / `get_weather_context` / `evaluate_maneuver_hydrology` 等，完整清单见 `AgentToolNames`）、COLREGS Part B 进程内规则图（`GraphQueryPort` + `MemoryGraphAdapter`）、advisory pipeline 与 `ADVISORY` SSE、`AgentSnapshot` 边界深拷贝快照。advisory `evidence_items` 已能承载法规、机动评估、气象、水文等工具事实，其中水文事实以 `[source: hydrology]` 标记并受后置 source 校验约束。

v1.1 要解决的一句话问题：**advisory 的 `evidence_items` 虽已覆盖法规、机动、气象、水文等工具事实，但缺少"历史上类似态势如何处置"的经验性证据**。本版本用 Graph-RAG 从合成历史案例库召回相似案例，作为新的一类 evidence 注入 advisory，由 advisory 主线与 chat agent 次线共同消费。

落地形态遵循六项已确认的设计决策：
1. **独立 port**：新增 `HistoricalCaseQueryPort`，与 `GraphQueryPort` 并存而非合并（规则图确定性/进程内，案例图随机性/外部检索，失败模式与开关粒度不同）。
2. **HTTP sidecar 桥接**：LightRAG 以常驻 Python sidecar 服务暴露 HTTP 检索接口，Java 侧 adapter 走 HTTP 调用——与仓库既有 `whisper` sidecar 同构，避免 subprocess 冷启动毁掉延迟指标。
3. **顶层服务目录**：Python 侧落在仓库顶层 `graphrag-service/`，与既有 Python `simulator/` 平级，自包含 venv/语料/索引/评测。
4. **scope 分层**：案例语料 + 索引 + sidecar + port + 工具 + evidence 注入属产品 scope；LLM-as-judge 评测属验证研究 track，其评分不作为产品验收门槛。
5. **本地文件型存储**：本版本采用 LightRAG 默认文件型本地存储（NetworkX + nano-vectordb + 本地 JSON），index 产物落在 `graphrag-service/runtime/index/`；复用 `postgis` 因缺少 pgvector + AGE 不作为低成本路径，Neo4j 作为生产化方向继续 deferred。
6. **生成/评测模型分离**：检索与答案生成沿用 GraphRAG sidecar 的智谱兼容 OpenAI API 配置；评测 judge 走非智谱本机 CLI，默认 Gemini CLI，并允许 `agy` / `codex` / `copilot` / `claude` 作为备用后端。

---

## Version-Level Definition of Done

以下为可观测、可验证的版本完成标准（不含评测分数）：

1. **检索服务可用**：`graphrag-service/` sidecar 采用离线 index bootstrap + 常驻服务加载模式；完成 `python -m graphrag_service.build_index ...` 后，`compose.yaml` 可拉起 `graphrag` 服务，由 10–20 条合成案例构建的 index 对外提供 HTTP 检索；给定态势特征（船型 / 遭遇类型 / 海域 / 风险等级）的查询返回 top-K 相似案例（JSON）并通过一次 smoke 查询。
2. **port 独立且可开关**：`HistoricalCaseQueryPort` 抽象与其 LightRAG adapter 存在，受独立 feature flag（`@ConditionalOnProperty`）控制；禁用时该 bean 不加载，`GraphQueryPort` / `MemoryGraphAdapter` 不受影响。
3. **工具已注册**：`query_historical_case_graph` 注册进 `AgentToolRegistry`，从 `AgentSnapshot` 深拷贝快照读取态势特征发起检索，可被 advisory 主线与 chat agent 路径调用。
4. **evidence 注入与来源校验**：工具启用且态势相关时，advisory `evidence_items` 出现带具体案例字段的 `[source: historical_case] ...` 字符串条目，并通过后置 source-grounding 校验——若该来源标记出现但本轮没有 `query_historical_case_graph` 的成功 tool result（`status=OK` 且 `cases` 非空），或 evidence 未引用该结果中的 `case_id`，则判 `ADVISORY_SCHEMA_FAILED`；`evidence_items` 协议仍为 `string[]`，未升级为结构化对象。
5. **行为保持（回归安全）**：工具禁用时，advisory 的输出与 v1.0 完全一致——主链路调用拓扑、`ADVISORY` 事件 schema、prompt 强制调用序列、既有 evidence 内容与既有 source 校验均不变。

> 评测 track（step-4）为**非门槛验证**：其四组对照 × 三主指标的产出供课程汇报 §8 使用，评分不计入上述发布标准；检索/生成与 judge 使用不同模型入口以降低同源偏差。

---

## Non-Goals

### 本版本不做（deferred，未来版本可做）

- **`evidence_items` 结构化升级**：保持 `string[]` + `[source: historical_case]` 字符串标记；含 `text` / `source_tool` / `source_type` 的结构化对象升级继续 deferred，需由未来版本另行规划。
- **真实历史案例数据采集**：本版本用合成语料；真实碰撞/近失事件数据接入延后。
- **生产级图数据库部署**：demo 阶段已采用 LightRAG 文件型本地存储；生产级 Neo4j 集群、鉴权、HA 或 Postgres 图扩展改造不在范围内。

### 本版本不做（never，跨版本边界）

- **重写 advisory 主链路**：只新增分支，保持现有调用拓扑与 `ADVISORY` SSE 协议。
- **替换 COLREGS 规则图**：规则图（`GraphQueryPort`）与案例图（`HistoricalCaseQueryPort`）并存，互不替代。
- **把 LLM-as-judge 评分纳入产品验收**：评分是汇报验证方法论，不是 release gate；不引入人工标注，不与官方 GraphRAG 做大规模 benchmark。

---

## Step Breakdown

### step-1 — 历史案例语料与 Graph-RAG 索引服务（sidecar）

- **objective**：建立合成案例语料库，跑通 LightRAG 索引，以常驻 HTTP sidecar 形式提供相似案例检索。
- **input dependencies**：无运行期依赖（v1.0 已收口）；仅借助 COLREGS 场景知识生成案例语料。
- **deliverables**：仓库顶层 `graphrag-service/`（`data/cases/*.md`、`build_index` 索引脚本、`server`（FastAPI）检索服务、`requirements.txt`、`README`）；`compose.yaml` 新增 `graphrag` service；离线 bootstrap 后可查询的 LightRAG 文件型本地 index 产物（gitignore）。
- **DoD**：index 由 10–20 条合成案例构建；验收序列为 `build_index` bootstrap → `compose up graphrag` → `/health` 就绪 → 一次 `POST /retrieve` smoke 查询返回 top-K 相似案例（JSON）。
- **scope ceiling**：仅合成案例；本版本固定采用 LightRAG 文件型本地存储，不复用 `postgis`，不引入 Neo4j；不做生产级鉴权与高可用；不接真实案例数据。

### step-2 — `HistoricalCaseQueryPort` 抽象与 LightRAG 适配器（Java 侧）

- **objective**：在 map-service 内新增独立的历史案例检索 port 与对接 sidecar 的 adapter，受独立 feature flag 控制。
- **input dependencies**：step-1（sidecar 的 HTTP 检索契约）。
- **deliverables**：`HistoricalCaseQueryPort` 接口 + 其 LightRAG HTTP adapter 实现；态势特征 → 案例结果的查询/返回 DTO；独立 `@ConditionalOnProperty` 开关；覆盖启用/禁用两路的单测（以 mock sidecar 驱动）。
- **DoD**：启用 flag 时 adapter 调用 sidecar 返回结构化案例；禁用时 bean 不加载且既有 `GraphQueryPort` 链路无回归；单测通过。
- **scope ceiling**：不改动 `GraphQueryPort` / `MemoryGraphAdapter`；adapter 只做 HTTP 调用与 DTO 映射，不内嵌检索/排序逻辑；不引入结构化 evidence 协议。

### step-3 — `query_historical_case_graph` 工具与 advisory evidence 注入

- **objective**：将案例检索包装为 agent 工具注册进 `AgentToolRegistry`，更新 advisory prompt 契约与后置校验，使召回案例以**有据可校验**的 `[source: historical_case]` 字符串注入 advisory `evidence_items`。
- **input dependencies**：step-2（port 与 adapter）。
- **deliverables**：
  - `query_historical_case_graph` 工具（读 `AgentSnapshot` 态势特征），注册进 `AgentToolRegistry`，随 feature flag 联动启停；
  - 条件化组装的 `system-advisory-casegraph.txt` prompt fragment，仅当工具目录包含 `query_historical_case_graph` 时追加 `[source: historical_case]` 引用规则，保证工具禁用时基底 `system-advisory.txt` 行为回退到 v1.0；
  - advisory 后置 source-grounding 校验：evidence 出现 `[source: historical_case]` 时，必须能从本轮 `query_historical_case_graph` 成功 tool result 中溯源到被引用的 `case_id`，否则判 `ADVISORY_SCHEMA_FAILED`；
  - `EVENT_SCHEMA.md` §`evidence_items` 增补 historical_case 来源约束（与既有 hydrology 约束并列）；
  - 覆盖"工具启用→证据有据通过校验""伪造来源标记→校验失败""工具禁用→输出与 v1.0 一致"三路的测试。
- **DoD**：工具启用且态势相关时，`evidence_items` 出现带具体案例字段和返回 `case_id` 的 `[source: historical_case] ...` 条目并通过 source-grounding 校验，且工具可在 chat agent 路径调用；伪造来源标记、引用未返回的 case_id、或在失败/空召回后编造案例时 advisory 判 schema 失败；工具禁用时 advisory 输出与 v1.0 完全一致；`evidence_items` 仍为 `string[]`。
- **scope ceiling**：不升级 evidence 协议为结构化对象；不重写 advisory 主链路；允许为 source-grounding 在 `AgentLoopResult.Completed` 中增量保留 `toolResults`，但不改变既有工具强制调用序列；工具仅读 snapshot 深拷贝，不读 live 数据。

### step-4 — Graph-RAG 检索效果评测（非门槛验证 track）

- **objective**：用固定查询集 + LLM-as-judge 对四组对照做检索质量与代价对比，产出课程汇报 §8 数据。
- **input dependencies**：step-1（sidecar 与 index）；端到端 advisory 前后对比截图额外依赖 step-3。
- **deliverables**：`graphrag-service/eval/`（`queries.json`、`run_eval`、`judge`、`results/scores.csv`、`plots/`）；四组 × 三主指标柱状图、延迟-质量散点图、case study 对比表；为可解释评测补充向后兼容的 `retrieval_source` 遥测字段，用于区分 naive embedding、LightRAG context 与 fallback 来源。
- **DoD**：评测脚本可重复运行，产出四组 × 三主指标（Comprehensiveness / Diversity / Empowerment）评分及延迟 / token 辅助指标；查询集覆盖 A–D 四类且至少含 1 条边界场景。
- **scope ceiling**：纯 LLM-as-judge，无人工标注；不与官方 GraphRAG 做大规模 benchmark；**评分不作为 v1.1 产品验收门槛**；检索/生成沿用智谱配置，judge 默认 Gemini CLI 并可切换至其他本机 CLI。

---

## Sequencing Constraints

- step-4 的评测主体走纯 Python 直连 sidecar，**仅依赖 step-1**，可与 step-2 / step-3 并行；其中"端到端 advisory 前后对比截图"一项需待 step-3 完成。为解释评测结果，step-4 可读取 sidecar 返回的向后兼容 `metrics.retrieval_source` 遥测字段。
- step-4 是非门槛验证 track（类比 v1.0 的 hydrology / weather 并行 track）：其进度不阻塞 v1.1 release，可在 step-1 后任意时点推进或回补。
- step-2 与 step-3 之间无并行空间（工具实现强依赖 port），保持串行。

---

## Open Questions

截至 2026-07-01 收口，无未决问题。存储后端已定为 LightRAG 文件型本地存储；生成/检索使用智谱兼容 OpenAI API 配置，评测 judge 使用非智谱本机 CLI（默认 Gemini CLI）。

---

## Active Deviations

> 追加式附录：实施期间下游 step / 代码若发现更优方案或与本 overview 正文不一致，在此追加条目，不回写正文；版本翻 `completed` 时统一并合。

| 受影响正文条目 | 触发 step | 日期 | 原因 | 临时处理 | 建议的最终并合 |
|---|---|---|---|---|---|
| 版本收口时已并合，无残留 | - | 2026-07-01 | - | - | - |
