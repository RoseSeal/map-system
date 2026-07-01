# step-4 — Graph-RAG 检索效果评测（非门槛验证 track）

> 版本：[v1.1-graphrag](./OVERVIEW.md) · status: active
> 本步 status：pending review
> 最后更新：2026-06-10

---

## 1. 来源与边界（锚定 overview，不重定义）

引自 [OVERVIEW.md](./OVERVIEW.md) step-4 条目，本步不得越界：

- **objective**：用固定查询集 + LLM-as-judge 对四组对照做检索质量与代价对比，产出课程汇报 §8 数据。
- **input dependencies**：[step-1](./step-1.md)（sidecar 与 index，已完成）；端到端 advisory 前后对比截图额外依赖 [step-3](./step-3.md)（已完成，不再阻塞）。
- **deliverables**：`graphrag-service/eval/`（`queries.json`、`run_eval`、`judge`、`results/scores.csv`、`plots/`）；四组 × 三主指标柱状图、延迟-质量散点图、case study 对比表。
- **DoD**：评测脚本可重复运行，产出四组 × 三主指标（Comprehensiveness / Diversity / Empowerment）评分及延迟 / token 辅助指标；查询集覆盖 A–D 四类且至少含 1 条边界场景。
- **scope ceiling**：纯 LLM-as-judge，无人工标注；不与官方 GraphRAG 做大规模 benchmark；**评分不作为 v1.1 产品验收门槛**。

**定位**（overview Sequencing）：非门槛验证 track，进度不阻塞 v1.1 release；评测主体纯 Python 直连 sidecar HTTP 接口，不触 Java 侧。

---

## 2. 当前状态与可复用资产

- **step-1 已交付（代码在库）**：`graphrag-service/` 含 12 条合成案例（`data/cases/H-01..H-12.md`）、`/retrieve`（接收 `situation` + `query` + `mode` + `top_k`，返回 `cases[]` + `answer` + `metrics.{latency_ms,tokens}`）——契约设计时已为本步预留 `mode` / `answer` / `metrics`（step-1 §6.2）。当前 `metrics.tokens` 固定为 0，不能作为实际 usage 使用；本步按 §3.8 生成统一估算指标。
- **naive baseline 的现成构件**：`retriever._embedding_candidates`（catalog full_text embedding 余弦相似度候选集，即 step-1 §3.5 兜底路径）、`retriever._rerank` 与 `embeddings.embed_texts`（智谱 embedding-3）可被 eval 复用为"普通向量 RAG"对照组的召回层。`_embedding_candidates` 当前至少返回 10 条候选，eval 必须在 rerank 后显式截断至与 sidecar 相同的 `top_k`，不得把全部候选送入生成上下文。
- **检索/生成链路配置**：sidecar 默认智谱 `glm-4-flash` + `embedding-3`（env 可配，step-1 §3.4）；`manifest.json` 含 `corpus_hash`，可作为评测结果与语料版本的绑定键。
- **判分模型的额度约束（本轮新增前提）**：智谱/Gemini API 免费额度低，不足以承担 judge 调用量。本机已有 `gemini`、`codex`、`copilot`、`claude` CLI 订阅，可非交互调用——judge 走 CLI（§3.3），检索/生成链路**不变**，仍走智谱 API。
- **测试基建**：`graphrag-service/tests/` 已有 pytest 基建（mock LightRAG 层），eval 的可 CI 部分沿用。
- **`.gitignore` 的 `runtime/` 规则匹配任意层级**（step-1 §4 已确认），`eval/runtime/`（缓存、原始输出）自动不入库；`results/` 与 `plots/` 不在该规则内，正常入库。

**实施状态（2026-06-10）**：评测代码、固定查询集、judge rubric、缓存/续跑、报告生成与 mock 测试已实现。当前本机进程未设置 `GRAPHRAG_LLM_API_KEY`，因此真实 pilot、全量评分、图表与 case study 仍属于 §9 人工验收，不在本次无凭证验证中伪造生成。

---

## 3. 设计决策与被否方案

### 3.1 四组对照：naive 向量 RAG + LightRAG local / global / hybrid（已与用户确认）

| 组 | 召回 | 答案生成 | 实现 |
|---|---|---|---|
| `naive` | catalog embedding 候选 → 复用 `_rerank` → 显式截断相同 `top_k` | 智谱 LLM + 标准 RAG prompt（截断后的 top-K 案例全文拼接为上下文） | eval 侧 `baselines.py`，不经 sidecar |
| `local` / `global` / `hybrid` | LightRAG 对应 mode；仅 `retrieval_source=lightrag_context` 计为图召回 | sidecar `/retrieve` 返回的 `answer` | 直连 `POST /retrieve` |

对照意义：`naive` vs 三个 LightRAG mode 直接验证 ADR-007「GraphRAG 而非普通 RAG」的路线判断；三个 mode 间对比回收 step-1 §3.3 暴露 `mode` 的初衷。

当前 `/retrieve` 在 LightRAG context 无法解析出 case_id 时会静默退化为 catalog embedding 候选。若不暴露该事实，case study 会把向量兜底结果误记为图召回。因此本步对 sidecar 做一个向后兼容的遥测扩展：在 `metrics` 增加 `retrieval_source`，取值为 `lightrag_context`（从 LightRAG context 解析出案例）、`embedding_fallback`（context 无案例后退到 catalog embedding）或 `lightrag_unavailable`（运行时不可用）。eval 为 naive 组写入同名值 `naive_embedding`。原有字段与 HTTP 契约不删除、不改义。

聚合规则：质量分仍按四组答案统计；召回 case_id 与 case study 只把 `lightrag_context` 解释为 GraphRAG 召回。报告同时列出各 mode 的 fallback 次数；若任一 mode 在 16 条查询中全部 fallback，则该 mode 的 GraphRAG 召回对照无效，不能据此支持 ADR-007，只能报告生成答案结果和该限制。

**被否**：给 sidecar `/retrieve` 加 `mode=naive`——改动 step-1 已交付的产品契约，把纯评测用途泄进产品接口；naive 组在 eval 侧自建（召回构件复用、生成 prompt 自带）。

### 3.2 A–D 查询分类与查询集（已与用户确认）

| 类 | 定义 | 期望行为 |
|---|---|---|
| A | 典型会遇几何直击（语料有直接对应案例） | 召回直接命中，答案引用对应案例 |
| B | 特殊条件（狭水道 / 受限能见度 / 锚泊碍航物） | 召回叠加条件案例，区分于同几何的开阔水域案例 |
| C | 复合多约束态势（需跨案例综合，无单一完美命中） | 综合多案例给出处置参考 |
| D | 边界场景（语料外态势 / 缺少直接对应案例） | 在仍会返回 top-K 的前提下，明确相关性有限、避免把弱相关案例编造成直接依据——DoD「至少 1 条边界场景」由本类承载 |

`queries.json` 共 **16 条**（每类 4 条），每条含：`query_id`、`category`（A–D）、`situation`（step-1 §6.2 结构化态势特征）、`query`（自然语言）、`pilot`（bool，§3.6）、`notes`（设计意图，供汇报 case study 选材）。查询集撰写时与 12 条语料的覆盖面对表（每条 A/B 类查询注明预期命中的 case_id，供 case study 用；不作为自动断言——相关性判定归 judge）。

D 类不把"空召回"设为预期或断言：当前 catalog embedding 无最低相关度阈值，会始终返回 top-K。该类评测的是域外/弱匹配条件下的相关性表达与幻觉控制；本步不新增拒答阈值，不改变产品检索语义。

### 3.3 judge 经 CLI 非交互调用：默认 `gemini`，备用 `codex` / `copilot` / `claude`（已与用户确认）

judge 不走 API（免费额度不足），改为本机 CLI 订阅的非交互调用。`judge.py` 抽象 backend：每个 backend = 一条非交互命令模板（prompt 经 stdin 或参数传入，stdout 取回），默认 `gemini`，经 `--judge-backend` / env 切换为 `codex` / `copilot` / `claude`。运行前 preflight（探活调用）失败即 fail fast 并提示切换 backend。结果元数据（`scores.csv` 与 manifest）记录实际 backend，不同 backend 的评分**不混入同一组结果对比**。

实现要求通过 `EVAL_JUDGE_MODEL` 或 `--judge-model` 显式指定 CLI model identifier；不接受不可审计的 CLI 默认模型。该值进入 retrieval-independent run fingerprint、judge cache fingerprint 与 `scores.csv`，避免 CLI 默认模型静默变化后复用旧评分。

模型分工就此确定（消解 overview Open Questions #2）：语料生成已在 step-1 完成、检索/生成用智谱、judge 默认 Gemini（CLI）——判分模型与被评链路（智谱）不同源，降低同源偏差；备用 CLI 同样满足"非智谱"约束。已记入 overview `Active Deviations`（见 §13）。

**边界（用户显式约束，列入不变式）**：CLI 仅用于 judge 环节；LightRAG 的 embedding、索引与主检索链路继续走智谱 API，**不得**用 CLI 替代——embedding 空间一致性（step-1 §3.4 fail-fast 的同一理由）与检索延迟指标的真实性都依赖这一点。

**被否**：judge 走 Gemini API——免费额度不足以稳定承担 16 次全量调用及失败重试。
**被否**：多 CLI ensemble 投票——备用是故障切换不是集成；ensemble 让"评分来自谁"不可解释，汇报口径变糊。

**已知代价（接受）**：CLI 无 temperature/seed 控制，判分非确定；以 §3.5 缓存固化首次判分结果 + 留存原始输出对冲（重跑命中缓存即完全可复现）。

### 3.4 单查询合并判分：四组匿名答案一次 judge 调用（已与用户确认）

每条查询发起**一次** judge 调用：prompt 含查询与态势 + 四组答案（匿名为 `Answer-1..4`，顺序按 `query_id` 做种子的确定性 shuffle，抵消位置偏差且可复现），要求 judge 输出严格 JSON——对每个匿名答案分别给出三主指标的**绝对评分（1–10）**与一句 rationale。`judge.py` 负责 JSON 抽取（容忍 markdown 代码栅栏）、匿名标签→组名回映射；解析失败重试一次后落盘原始输出并标记该查询 `judge_failed`（断点续跑可补，§3.5）。

调用量：16 查询 × 1 次 = 16 次 judge 调用（pilot 4 次为其子集），相较逐组判分的 64 次显著降低。

rubric 要点（`eval/judge_prompt.txt`，定稿于 pilot 后）：三主指标沿用 LightRAG 论文定义并给 1–10 锚点描述；**对 D 类查询显式规定**——答案即使收到 top-K 案例，也应说明匹配局限，避免把弱相关案例表述为直接对应；能恰当限定结论的答案在 Empowerment 上得高分，编造直接案例对应关系得低分（否则 judge 天然偏好"内容多"的编造答案）。

**被否**：逐组单独判分（4 次/查询）——调用量 ×4，且各组分数失去同一上下文锚定，跨组可比性更差。
**被否**：pairwise win-rate（LightRAG 论文式）——六对组合 × 16 查询的调用量与聚合复杂度对课程汇报过重；柱状图交付形态天然对应绝对分。

### 3.5 缓存与断点续跑（已与用户确认）

- **统一评测指纹**：运行前先构造不含 run id 的 `run_config`，再生成 `evaluation_run_id = sha256(canonical_json(run_config))`；`run_manifest = {evaluation_run_id, run_config}`。`run_config` 至少含 `queries_hash`、`corpus_hash`、index `built_at`、LightRAG 包版本、`top_k`、sidecar URL、LLM/embedding model 与 base URL（不含 key）、naive generation prompt hash、rubric hash、judge backend、judge 实际 model、CLI 名称与版本、token 估算器版本。manifest 写入 `eval/results/run_manifest.json` 并随结果入库。
- **检索缓存**：`eval/runtime/cache/retrieval/<retrieval_fingerprint>/`，其中 fingerprint 覆盖 query 内容、group、corpus/index、LightRAG 版本、`top_k`、LLM/embedding 配置和 naive prompt；值为完整 `/retrieve` 响应（或 naive 组的召回+生成结果）。命中即跳过 HTTP/LLM 调用。
- **judge 缓存**：`eval/runtime/cache/judge/<judge_fingerprint>/`，其中 fingerprint 覆盖 query、四组答案内容、rubric、judge backend、实际 model 与 CLI 版本；值为 judge 原始输出 + 解析后评分。答案、rubric 或 judge 实现变化自动失效。
- **断点续跑**：`run_eval.py` 按 `evaluation_run_id` 写入 `eval/runtime/runs/<evaluation_run_id>/progress.jsonl`；仅同一 run id 的完成项可跳过，只补缺失/失败项。检索 HTTP 失败记录为 `retrieval_failed`，judge 失败记录为 `judge_failed`，二者重跑时均不会视为完成。配置或 backend 变化产生新 run id，不读取旧 progress。`--force-retrieval` / `--force-judge` 分别强制失效对应缓存。
- **结果隔离**：`scores.csv`、`run_manifest.json`、图表与 case study 每次只从一个完整 run id 重建并原子替换，不向已有 CSV 追加其他 run 的行；历史 run 的可恢复状态只留在 `eval/runtime/runs/`。因此切换 backend 后不会在同一入库结果中混合两套评分。
- **可重复性语义**：DoD 的"可重复运行"由此达成——缓存全命中时输出完全确定；缓存失效重判时差异可经留存的原始输出审计。

### 3.6 pilot：4 条查询先行（已与用户确认）

`queries.json` 中每类标 1 条 `pilot: true`（共 4 条，A–D 各一）。`run_eval.py --pilot` 只跑这 4 条，用于在烧全量额度前验证：sidecar 连通、四组管线、judge CLI 调用与 JSON 解析、rubric 是否区分度过低（四组分数挤在一起）。pilot 结果进同一缓存，全量运行直接复用；**rubric 若在 pilot 后修订，`rubric_hash` 变化自动重判 pilot 4 条**，不会混入旧 rubric 的分数。

### 3.7 产物与入库策略

- **入库**：`eval/queries.json`、`eval/judge_prompt.txt`、脚本、`eval/results/scores.csv`（评分 + 延迟/token 估算辅助指标 + 召回来源元数据）、`eval/results/run_manifest.json`（完整评测指纹）、`eval/plots/*.png`、case study 对比表（`eval/results/case_study.md`）。评测需凭证与本机 CLI、无法 CI 重生成，结果即汇报证据，必须入库。
- **不入库**：`eval/runtime/`（缓存、原始 judge 输出、progress）——被既有 `runtime/` gitignore 规则覆盖。

`scores.csv` 行 schema：`evaluation_run_id, query_id, category, group, comprehensiveness, diversity, empowerment, latency_ms, estimated_input_tokens, estimated_output_tokens, token_estimator, retrieval_source, judge_backend, judge_model, corpus_hash, judged_at`。

### 3.8 token 辅助指标：统一估算，不冒充 provider usage

当前 sidecar `metrics.tokens` 固定为 0，LightRAG 封装也不返回可稳定采集的 provider usage；只给 naive 组记录真实 usage 会造成四组口径不一致。本步不为评测侵入 LightRAG 内部调用链，而在 eval 对四组统一估算：

- `estimated_input_tokens`：对评测可观察的输入文本计数。naive 为 query + top-K 完整上下文 + generation prompt；三个 LightRAG mode 为 `query_effective`，不宣称覆盖 LightRAG 内部扩展 prompt/context。
- `estimated_output_tokens`：对最终 `answer` 计数。
- `token_estimator`：固定记录估算器名称与版本；优先使用当前 sidecar 已固定的 Unicode 字符 tokenizer 口径，确保本仓库内确定性。字段名必须保留 `estimated_`，报告中标注其为相对文本规模代理，不得写成 API token usage 或成本。

因此 token 只作辅助描述，不用于四组成本的严格结论；延迟仍使用端到端实测值。

---

## 4. 目录与文件结构

```
graphrag-service/eval/
  queries.json            # 16 条固定查询（A–D × 4，每类 1 条 pilot）
  judge_prompt.txt        # rubric + 输出 JSON 契约（pilot 后定稿）
  run_eval.py             # 主入口：检索四组 → judge → 落盘（--pilot / --force-* / --judge-backend）
  baselines.py            # naive 组：catalog embedding 召回 + 智谱标准 RAG 生成
  judge.py                # CLI backend 抽象、合并判分 prompt 组装、JSON 解析、缓存
  report.py               # scores.csv → 柱状图 / 散点图 / case_study.md
  results/                # scores.csv、run_manifest.json、case_study.md（入库）
  plots/                  # 柱状图、散点图 PNG（入库）
  runtime/                # 缓存、progress、原始输出（不入库）
```

运行方式：在 `graphrag-service/` 下 `python -m eval.run_eval [--pilot]`（eval 作为包内可导入目录，复用 `graphrag_service.*` 模块）；前置条件与 step-1 验收序列一致（index 已 bootstrap、sidecar 已起或本地直跑）。

执行流（每条查询）：
1. 四组检索（缓存优先）并发执行：三个 mode 调 `/retrieve`，naive 组走 `baselines.py`；各组使用独立指纹缓存，某一组失败时其余成功缓存仍可供重跑复用。记录各组 `latency_ms`、统一 token 估算与 `retrieval_source`。naive 组经过 `_rerank` 后截断至相同 `top_k`。
2. 组装匿名合并判分 prompt → judge CLI（缓存优先）→ 解析回映射。
3. 增量写 progress 与结果。

全量完成后 `python -m eval.report` 生成三类图表产物。

---

## 5. 图表与 case study 产出（汇报 §8 对接）

- **柱状图**：四组 × 三主指标平均分（16 查询均值，按 category 分面或合并各一张）。
- **延迟-质量散点图**：x = 组均延迟（检索+生成），y = 三指标均分，四点标注组名——展示 GraphRAG 质量收益的延迟代价。
- **case study 对比表**（`case_study.md`）：选 2 条查询（建议 1 条 B 类——图检索区分度最直观；1 条 D 类——弱匹配下的限定表达对比），逐组列出召回 case_id、`retrieval_source`、答案要点、judge rationale；embedding fallback 不得标注为 GraphRAG 召回。
- 端到端 advisory 前后对比截图：人工操作（flag 关/开各触发一次 advisory，截 `evidence_items` 区域），属本步人工验收清单，不写脚本（§9）。

---

## 6. 测试计划

| 层级 | 测试 | 手段 | 门控 |
|---|---|---|---|
| 单元 | `test_eval_queries.py`：`queries.json` 16 条解析通过、A–D 各 4 条、每类恰 1 条 pilot、situation 字段落在 step-1 §5 受控集合 | 纯解析 | 是 |
| 单元 | `test_eval_baseline.py`：`_embedding_candidates` 返回超过 top-k 时，naive 组仍在 `_rerank` 后只向生成 prompt 传相同 top-k | mock embedding/LLM | 是 |
| 单元 | `test_eval_judge.py`：合并判分 prompt 组装（匿名顺序确定性）、judge 输出 JSON 解析（含 markdown 栅栏、缺字段、非 JSON 三类 fixture）、标签回映射、缓存键稳定性（rubric/答案/backend/model/CLI 版本变化 → 键变化） | mock CLI（fixture 输出） | 是 |
| 单元 | `test_eval_resume.py`：按 `evaluation_run_id` 隔离 progress——同指纹已完成项跳过，backend/rubric/model 变化不复用旧 progress，失败项重做，`--force-judge` 失效缓存 | mock 检索与 judge | 是 |
| 单元 | `test_eval_runner.py`：四组检索并发执行；sidecar HTTP 失败写入 `retrieval_failed` 且续跑时重试 | mock HTTP / naive baseline | 是 |
| 契约 | `test_retrieve_contract.py`：三条路径分别返回 `metrics.retrieval_source=lightrag_context/embedding_fallback/lightrag_unavailable`，既有字段保持兼容 | mock LightRAG/embedding | 是 |
| 集成 | pilot 实跑：`--pilot` 4 条全链路（真实 sidecar + 智谱 + gemini CLI）产出 4×4 组评分 | 真实凭证与 CLI | 手动 |
| 全量 | 16 条全量 + `report` 产出 scores.csv / 三类图表；中断后重跑验证续跑 | 同上 | 手动（即 DoD 验收） |

可 CI 部分（五项 eval 单元测试 + 一项 sidecar 契约测试）全部 mock CLI、HTTP 与模型调用，无凭证可跑；集成/全量为手动验收。

---

## 7. 配置改动

- 无 Java 侧、`compose.yaml` 改动；sidecar 仅对 `retriever.py` 做向后兼容的 `metrics.retrieval_source` 遥测扩展，不增加 eval 专用 mode 或改变召回行为。
- `eval` 新增 env：`EVAL_JUDGE_BACKEND`（默认 `gemini`）、`EVAL_JUDGE_MODEL`（必填，亦可由 `--judge-model` 提供）、`EVAL_SIDECAR_URL`（默认 `http://127.0.0.1:8100`）。检索/生成复用既有 `GRAPHRAG_*` env。
- `graphrag-service/README.md` 新增 eval 章节（运行序列、backend 切换、入库产物说明）。

---

## 8. 文件影响清单

新增：
- `graphrag-service/eval/`：`queries.json`、`judge_prompt.txt`、`run_eval.py`、`baselines.py`、`judge.py`、`report.py`
- `graphrag-service/eval/results/`（`scores.csv`、`run_manifest.json`、`case_study.md`）与 `eval/plots/`（运行后入库）
- `graphrag-service/tests/`：`test_eval_queries.py`、`test_eval_baseline.py`、`test_eval_judge.py`、`test_eval_resume.py`、`test_eval_runner.py`

修改：
- `graphrag-service/README.md`（+eval 章节）
- `graphrag-service/requirements.txt`（+`matplotlib`；若引入开发依赖分层则放 dev 段，实施时按现状定）
- `graphrag-service/graphrag_service/retriever.py`（`metrics.retrieval_source` 向后兼容遥测字段）
- `graphrag-service/tests/test_retrieve_contract.py`（三种召回来源契约覆盖）

不修改：
- `graphrag_service/` 其余包（`embeddings.py` / `lightrag_client.py` 等）
- `data/cases/*.md`（语料冻结于评测期间；如评测暴露语料缺陷，修订归 step-1 范畴回补并重建 index + 失效缓存）
- Java 侧、`compose.yaml`、`docs/EVENT_SCHEMA.md`

---

## 9. 人工验收清单（DoD 核验）

1. index bootstrap → sidecar 起 → `--pilot` 4 条通过（judge JSON 全部可解析）。
2. pilot 检查三个 mode 的 `retrieval_source`；若某 mode 全部 fallback，先将该限制写入结果，不把其 case_id 归因为 GraphRAG 召回。
3. rubric 定稿 → 全量 16 条跑完 → `scores.csv` 含 16×4 组完整评分、延迟、统一 token 估算与召回来源，`run_manifest.json` 可重算出相同 `evaluation_run_id`。
4. 中断一次全量运行再重跑，验证同 run id 只补缺失项；切换 rubric/backend 后验证不会复用旧 progress。
5. `report` 产出柱状图、散点图、case_study.md，并报告各 mode fallback 次数。
6. advisory 前后对比截图（flag 关/开），归档到课程汇报素材目录。

---

## 10. 不变式

- **CLI 只用于 judge**：LightRAG embedding、索引、主检索链路与 naive 组的 embedding/生成全部走智谱 API，不得以 CLI 替代（用户显式约束；embedding 空间一致性与延迟指标真实性所系）。
- 评测通过 sidecar HTTP 接口与 catalog 产物运行；sidecar 只允许新增通用 `metrics.retrieval_source` 遥测，不改请求参数、原有响应字段或召回行为（`/retrieve` 无 eval 专用参数）。
- 评分不进任何产品验收判定；`scores.csv` 与图表只供汇报 §8。
- 不同 judge backend/model/CLI 版本的评分不混入同一组对比结果；完整 `evaluation_run_id` 与 run manifest 随结果落盘。
- 语料、index、模型、prompt、rubric、backend 或 CLI 版本任何变化都产生对应新指纹，不复用不兼容的 progress/cache，不产生新旧混算。

---

## 11. 本步不做（显式分类）

| 项 | 分类 | 何时/何处 |
|---|---|---|
| 人工标注 / 与官方 GraphRAG 大规模 benchmark / 评分纳入验收 | not doing | overview scope ceiling 与 non-goals 原文 |
| 多 judge ensemble 投票、judge 间一致性分析 | not doing | §3.3 被否；备用 CLI 仅故障切换 |
| 统计显著性检验 | not doing | 16 查询 × 单次判分的样本量不支撑；汇报以描述性对比呈现，文字注明样本规模局限 |
| 评测结果反哺语料修订与 index 调优 | deferred | 触发条件：评测暴露 A/B 类查询系统性脱靶（预期命中 case 未进 top-K）；修订归 step-1 语料范畴回补，重建 index 并失效缓存后重评 |
| judge API 化（脱离本机 CLI 依赖） | deferred | 触发条件：API 额度约束解除或评测需在他机复现；`judge.py` 的 backend 抽象已为此留位，届时新增 API backend 即可 |

无新增 `docs/TODO.md` 条目：deferred 两项触发条件具体且实现已留接口/归属既有 step 范畴，不需要独立 owner 挂载。

---

## 12. 偏离记录

- **消解 overview Open Questions #2（模型分工）**：检索/生成沿用智谱（step-1 既定默认）；judge 默认 Gemini **CLI** 非交互调用，`codex` / `copilot` / `claude` CLI 备用（API 免费额度不足，经用户确认）。判分与被评链路不同源，符合 OQ #2 降低同源偏差的本意。与 step-1 消解 OQ #1 同样处理：以 overview `Active Deviations` 条目记录，待 consolidation 移出 Open Questions。
- **sidecar 只读边界的必要细化**：overview 要求评测主体纯 Python 直连 sidecar，但未禁止通用遥测补齐。当前实现会静默 embedding fallback，若不暴露来源则无法解释 GraphRAG 对照；本步允许 `metrics.retrieval_source` 的向后兼容扩展，不改变产品行为。该细化记入 overview `Active Deviations`。
- 其余无偏离：四组对照、A–D 分类、合并判分、缓存/续跑/pilot 均为 overview 委派本步的实现层设计（objective / deliverables / DoD / scope ceiling 原义不变）。

---

## 13. 风险与假设

- **假设**：本机 `gemini` CLI 支持非交互单次调用且输出可捕获；`codex` / `copilot` / `claude` 同理。各 CLI 的确切非交互参数（如 `-p` / `exec`）在实现时逐一验证，backend 模板按实测修正——不影响本文档其余设计。
- **风险（已接受）**：CLI judge 无 temperature/seed 控制，判分非确定 → 缓存固化首判 + 原始输出留存（§3.3/§3.5）；汇报注明该局限。
- **风险**：judge 输出不守 JSON 契约 → 解析容错 + 单次重试 + `judge_failed` 标记续跑可补（§3.4）；pilot 先行暴露该问题（§3.6）。
- **风险**：合并判分 prompt 过长（4 组答案 + rubric）超出 CLI 单次输入舒适区 → 答案在检索侧截断至合理长度（`top_k=5` 默认下 sidecar `answer` 本身有限）；pilot 验证。
- **风险**：rubric 区分度不足（四组分数趋同）→ pilot 后修订 rubric（锚点描述更具体、要求引用案例事实），`rubric_hash` 失效机制保证不混算。
- **风险**：D 类查询仍会因无最低阈值而返回 top-K，judge 可能偏好把弱匹配包装成直接对应的长答案 → rubric 显式奖励限定表达并惩罚伪造直接关联（§3.4）；case study 选 1 条 D 类呈现该现象。
- **风险**：三个 LightRAG mode 的 context 都无法解析 case_id，导致 `cases[]` 全部走 embedding fallback → 通过 `retrieval_source` 与 fallback 计数显式披露；发生时不得把 case_id 结果用于支持 GraphRAG 召回优越性。
- **风险**：16 查询样本小，结论波动 → 定位是课程汇报的方法论演示而非学术结论（scope ceiling 原义），汇报文字注明。
