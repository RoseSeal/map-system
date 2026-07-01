# step-1 — 历史案例语料与 Graph-RAG 索引服务（sidecar）

> 版本：[v1.1-graphrag](./OVERVIEW.md) · status: active
> 本步 status：completed
> 最后更新：2026-06-04

---

## 1. 来源与边界（锚定 overview，不重定义）

引自 [OVERVIEW.md](./OVERVIEW.md) step-1 条目，本步不得越界：

- **objective**：建立合成案例语料库，跑通 LightRAG 索引，以常驻 HTTP sidecar 形式提供相似案例检索。
- **input dependencies**：无运行期依赖（v1.0 已收口）；仅借助 COLREGS 场景知识生成案例语料。
- **DoD**：sidecar 启动后，对态势特征查询返回 top-K 相似案例（JSON）；index 由 10–20 条合成案例构建；`compose up` 可一键拉起服务并通过一次 smoke 查询。
- **scope ceiling**：仅合成案例；存储后端在本地内存图 / 复用 `postgis` / Neo4j 三者中择一，不做生产级鉴权与高可用；不接真实案例数据。

本步同时负责定义 **step-2 所依赖的 HTTP 检索契约**（见 §6），该契约是 step-1 的交付物。

---

## 2. 目标态（step-1 完成后仓库新增什么）

- 仓库顶层新增 `graphrag-service/`，与既有 Python `simulator/` 平级，自包含。
- 一个常驻 FastAPI 服务，暴露 `GET /health` 与 `POST /retrieve`。
- 一个离线索引脚本，从 `data/cases/*.md` 构建 LightRAG index，持久化到服务内 `graphrag-service/runtime/index/`。
- `compose.yaml` 新增 `graphrag` service，`compose up` 后 `/health` 就绪、`/retrieve` 可对预建 index 返回结构化案例。
- 仅产品检索路径；**评测脚本（`eval/`）不在本步**，由 step-4 在同目录下补。

---

## 3. 设计决策与被否方案

### 3.1 存储后端：LightRAG 默认文件存储（NetworkX + nano-vectordb）

**决策**：采用 LightRAG 默认的**文件型存储**——图存 NetworkX、向量存 nano-vectordb、KV/doc-status 存本地 JSON，全部落在 `working_dir`（即挂载的 `graphrag-service/runtime/index/`）。

**理由**：
- **零额外基础设施**：无需新增数据库容器，sidecar 自包含。
- **复用 `postgis` 不可行（低成本路径被堵）**：LightRAG 的 PostgreSQL 后端需要 `pgvector` + Apache AGE 扩展，而仓库现用的 `postgis/postgis:17-3.4` 镜像两者皆无，复用反而要改基础镜像，得不偿失。
- **Neo4j 过重**：为课堂 demo 引入一个图数据库服务，与"demo 阶段用本地存储即可"的 non-goal 冲突。

**被否方案**：复用 postgis（扩展依赖、改镜像）、引入 Neo4j（过重）。两者作为生产化方向，归 overview non-goal「生产级图数据库部署（deferred）」，本步不再单列 TODO。

> 本决策**消解 [OVERVIEW.md](./OVERVIEW.md) Open Questions #1**（存储后端选型，overview 已委派 step-1 决定）。非偏离，无需 appendix 条目。

### 3.2 索引时机：离线脚本构建，服务只读

**决策**：索引由 `graphrag_service/build_index.py`（`python -m graphrag_service.build_index`）离线一次性构建，FastAPI 服务启动时只**加载**已存在的 index、不在启动时重建。

**理由**：index 是构建产物而非每次启动重算；服务冷启动快、行为确定；避免容器启动即触发密集 LLM 抽取调用。

**被否**：服务启动时自动索引——启动慢、不确定、每次重启重复消耗 LLM 配额。

### 3.3 查询接口：态势特征 → query 串在 Python 侧组装，对外暴露 LightRAG mode

**决策**：`/retrieve` 接收**结构化态势特征**（可选）与/或自然语言 `query`，由 retriever 在 Python 侧把态势特征拼成检索 query，再以 `QueryParam(mode=...)` 调 LightRAG；`mode` 透传给调用方（`local`/`global`/`hybrid`）。

**理由**：query 构造逻辑靠近检索器（Python），Java 侧 adapter 只传结构化态势，不承担 prompt/query 拼装；同时暴露 mode 让 step-4 直接对比 local/global。

**被否**：只暴露原始文本 query——会把 query 构造推到 Java 侧，跨语言重复且易漂移。

### 3.4 检索 LLM / embedding：env 可配，默认走 OpenAI 兼容端点（智谱）

**决策**：索引与检索所用 LLM、embedding 经环境变量配置（binding + base_url + api_key + model）；默认对接智谱 GLM（OpenAI 兼容）+ 智谱 embedding-3，与 map-system 既有 provider 一致。`api_key` 为必填：服务启动与索引构建在缺失时 fail fast，不提供无 key 的降级运行模式（避免 catalog 与查询使用不同 embedding 空间导致的静默错配）。

**理由**：复用现有智谱凭证，无需新模型下载；config-driven 便于 step-4 切换生成/评测模型分工（overview Open Questions #2，留 step-4）。

**被否**：硬编码单一 provider（不灵活）、强制本地 Ollama（增加模型权重与环境复杂度）。

### 3.5 结构化案例返回：索引期建 case catalog，检索期回映射

**决策**：`build_index.py` 解析 `data/cases/*.md` 时，除送入 LightRAG 外，另写一份轻量 **catalog**（`case_id → {结构化字段, full_text, embedding}`）到 index 目录。`/retrieve` 时：
1. 以 `QueryParam(mode=..., only_need_context=True)` 取 LightRAG 检索上下文，解析其中引用的来源 case，映射回 catalog 得到结构化字段；
2. 另以普通 `QueryParam(mode=...)` 取 LightRAG 合成 `answer`（供 step-4 质量评分）。

**可靠性兜底**：若所装 LightRAG 版本的上下文格式难以稳定解析出来源 case_id，则退化为**对 catalog 内各 case 的 full_text embedding 直接做相似度 top-K**，保证结构化 top-K 始终可返回（DoD 不被 LightRAG 内部格式绑架）。Graph 检索仍是主路径，兜底仅在解析不可用时启用。

**理由**：LightRAG `query` 默认返回合成文本而非结构化记录；catalog 回映射在保留"图检索"价值的同时，稳定产出 step-2/step-3 需要的结构化字段。

> §3.5 的"上下文→case_id 解析"是唯一需在实现时对照所装 LightRAG release 复核的细节；兜底路径已对冲该风险。

---

## 4. 目录与文件结构

```
graphrag-service/
  data/cases/                 # 10–20 条合成案例，每文件一条
    H-01.md … H-NN.md
  graphrag_service/
    __init__.py
    config.py                 # env 驱动配置（LLM/embedding binding、working_dir、default_top_k、default_mode）
    corpus.py                 # 解析 + 校验 case markdown → 结构化记录
    indexer.py                # 从 corpus 构建 LightRAG index + 写 catalog + manifest
    retriever.py              # 态势→query、调 LightRAG、回映射 catalog、rerank、组装响应
    build_index.py            # CLI 入口（python -m graphrag_service.build_index），调 indexer
    server.py                 # FastAPI：/health、/retrieve
  tests/
    test_corpus.py            # 语料字段校验
    test_retrieve_contract.py # /retrieve 响应形状（mock LightRAG 层）
  runtime/index/              # index 产物（挂载入容器；已被 .gitignore 的 runtime/ 规则覆盖）
  requirements.txt
  Dockerfile
  .env.example
  README.md
```

`.gitignore` 的 `runtime/` 规则无前导斜杠，匹配任意层级的 `runtime/` 目录，故 `graphrag-service/runtime/index/` 已被忽略、无需新增规则；`data/cases/*.md` 与源代码正常入库，index 产物可删可重建。

---

## 5. 案例语料格式（`data/cases/*.md`）

每文件一条案例，YAML front-matter 承载结构化字段、正文承载自然语言叙述（供 LightRAG 实体抽取）：

```markdown
---
case_id: H-03
title: 夜间限制水域交叉相遇减速右转避让
synthetic: true                 # 必填且恒为 true——本版本仅合成案例
water_area: 限制水域            # 受控词表：开阔水域 / 限制水域 / 狭水道
visibility: RESTRICTED_VISIBILITY  # 对齐 visibility_condition：OPEN_VISIBILITY / RESTRICTED_VISIBILITY / UNKNOWN
own_ship_role: GIVE_WAY         # 对齐 Java OwnShipRole：GIVE_WAY / STAND_ON / MUTUAL_ACTION / UNKNOWN / NOT_APPLICABLE
encounter_type: CROSSING        # 对齐工具枚举：HEAD_ON / OVERTAKING / CROSSING / UNDEFINED
risk_level: WARNING             # 对齐 Java RiskLevel：SAFE / CAUTION / WARNING / ALARM
own_ship_type: CARGO            # 辅助 rerank 特征
target_ship_type: TANKER        # 辅助 rerank 特征
target_summary: 1 艘货轮，左舷交叉接近
kinematics: CPA 0.3 nm / TCPA 6 min / 相对方位 020°
colregs_rules: [Rule 19, Rule 16]
outcome: SAFE                   # 结果义：SAFE / NEAR_MISS / ACCIDENT
---

## 处置过程
1. 早期识别为受限能见度下的交叉接近……
2. 减速并向右大幅转向，避免向左转向……
3. 待目标通过后复航。

## 经验教训
受限能见度下应及早减速并避免向左转向。
```

**字段校验**：`corpus.py` 校验必填字段（`case_id`、`title`、`synthetic`、`own_ship_role`、`encounter_type`、`risk_level`、`outcome`、至少一条 `colregs_rules`），并校验枚举字段取值落在下述受控集合内，缺失或越界即报错并指明文件与字段。

**枚举对齐（与 v1.0 Java 契约一致，使 step-2 可直接透传 snapshot 派生值、无需翻译层）**：
- `own_ship_role` ← `OwnShipRole`：`GIVE_WAY` / `STAND_ON` / `MUTUAL_ACTION` / `UNKNOWN` / `NOT_APPLICABLE`
- `encounter_type` ← `QueryRegulatoryContextTool` schema：`HEAD_ON` / `OVERTAKING` / `CROSSING` / `UNDEFINED`
- `visibility` ← `visibility_condition`：`OPEN_VISIBILITY` / `RESTRICTED_VISIBILITY` / `UNKNOWN`
- `risk_level` ← `RiskLevel`：`SAFE` / `CAUTION` / `WARNING` / `ALARM`
- `colregs_rules`：`Rule N` 形式，对齐 v1.0 advisory prompt 的条款表述
- `water_area`（受控词表，非 Java 枚举）：`开阔水域` / `限制水域` / `狭水道`；`outcome`（结果义）：`SAFE` / `NEAR_MISS` / `ACCIDENT`

**特征角色**：`encounter_type` / `water_area` / `risk_level` 为**主召回特征**（`risk_level` 既是案例 front-matter 字段、也是查询时由调用方给出的当前态势特征，两侧同用 `RiskLevel` 取值）；`own_ship_type` / `target_ship_type` / `visibility` 为**辅助 rerank/过滤特征**。retriever 据此区分召回与重排（见 §7）。

**语料完整性约束（合成数据纪律）**：
- 每条案例必须显式标记 `synthetic: true`；`corpus.py` 对缺失或非 true 的 `synthetic` 直接报错。
- 案例叙述**只使用合成态势**，不得引用真实事故名称、时间、船名、MMSI 或伤亡结果，避免合成语料被误当真实事故材料。

**覆盖要求**：数量 10–20 条（建议初始 12 条）。会遇几何 `encounter_type` 三类（`HEAD_ON` / `OVERTAKING` / `CROSSING`）各有样本，并叠加特殊条件——狭水道（`water_area=狭水道`）、受限能见度（`visibility=RESTRICTED_VISIBILITY`）、锚泊或碍航物场景，再在风险等级与本/目标船型上保持分布差异（支撑 §13 检索区分度，step-4 评测时复核）。特殊条件经 `water_area` / `visibility` / 正文表达，不塞进 `encounter_type` 枚举。

---

## 6. HTTP 契约（step-2 依赖，本步交付）

### 6.1 `GET /health`

就绪探针，基于 index 目录下的 `manifest.json`（§7 构建产物）判定：manifest 存在则 `200 {"status":"ok","cases_indexed":N,"built_at":"...","corpus_hash":"..."}`；manifest 缺失（未构建索引）则 `503 {"status":"index_missing"}`。供 compose healthcheck 与 smoke 用。

### 6.2 `POST /retrieve`

**请求**：
```json
{
  "query": "夜间限制水域与货轮交叉相遇如何处置",   // 可选；缺省时由 situation 拼装
  "situation": {                                  // 可选结构化态势特征
    "own_ship_role": "GIVE_WAY",
    "encounter_type": "CROSSING",
    "water_area": "限制水域",
    "visibility": "RESTRICTED_VISIBILITY",
    "target_count": 1,
    "risk_level": "WARNING"
  },
  "mode": "local",                                // local | global | hybrid，默认 local
  "top_k": 5                                       // 默认 5，允许 1–10，越界 → 400
}
```
`query` 与 `situation` 至少有一项；两者皆有时 `query` 为主、`situation` 作补充上下文。

**响应 `200`**：
```json
{
  "mode": "local",
  "query_effective": "……实际送入检索的 query 串……",
  "cases": [
    {
      "case_id": "H-03",
      "title": "夜间限制水域交叉相遇减速右转避让",
      "relevance": 0.82,
      "water_area": "限制水域",
      "visibility": "RESTRICTED_VISIBILITY",
      "own_ship_role": "GIVE_WAY",
      "encounter_type": "CROSSING",
      "risk_level": "WARNING",
      "target_summary": "1 艘货轮，左舷交叉接近",
      "colregs_rules": ["Rule 19", "Rule 16"],
      "outcome": "SAFE",
      "action_digest": "减速并向右大幅转向，待目标通过后复航",
      "lesson": "受限能见度下应及早减速并避免向左转向"
    }
  ],
  "answer": "……LightRAG 合成的综合回答，供 step-4 质量评分……",
  "metrics": { "latency_ms": 740, "tokens": { "prompt": 1820, "completion": 240 } }
}
```

- `cases` 为结构化 top-K，是 step-2/step-3 的主消费对象；`action_digest` 由 `处置过程` 压缩为一句。
- `answer` 与 `metrics` 主要服务 step-4 评测（质量评分 + 代价指标），step-2/step-3 可忽略。
- 检索为空时返回 `cases: []` 且 `answer` 说明无相似案例——交由调用方决定是否注入 evidence。

**错误**：`query` 与 `situation` 均缺省 → `400 {"error":"empty_query"}`；`top_k` 越界（<1 或 >10）→ `400 {"error":"top_k_out_of_range"}`；`mode` 不在 `local` / `global` / `hybrid` 内 → `400 {"error":"mode_out_of_range"}`；index 未就绪 → `503 {"error":"index_missing"}`。所有失败均以明确的 4xx/5xx JSON 表达，不返回半结构化文本。

> 契约设计已把 step-4 需要的 `mode`/`answer`/`metrics` 一次性纳入，避免后续重新 contract。

---

## 7. LightRAG 集成点（签名 + 行为，非完整实现）

以下签名描述意图；具体 LightRAG 调用以所装 release（参考 v1.4.x）API 为准。

```python
# indexer.py
def build_index(cases_dir: Path, working_dir: Path, reset: bool) -> CatalogStats:
    """reset=True 时先清空 working_dir 旧索引；
    解析 cases_dir 下全部案例 → LightRAG.ainsert 正文；
    把结构化字段 + full_text + embedding 写入 working_dir/catalog.json；
    写 working_dir/manifest.json（案例数、case_id 列表、built_at、corpus_hash）。
    任一案例缺必填字段则中止并报出文件名与字段名。返回 CatalogStats。"""

# retriever.py
async def retrieve(req: RetrieveRequest) -> RetrieveResponse:
    """1) situation/query → effective query（主召回特征拼入 query，辅助特征留作 rerank）；
       2) LightRAG aquery(mode, only_need_context=True) → 解析来源 case_id → catalog 结构化记录（主路径）；
          解析不可用时退化为 catalog full_text embedding 相似度 top-K（兜底）；
       3) 以辅助特征（own_ship_type/target_ship_type/visibility）对候选做本地 rerank，再截断 top_k；
       4) LightRAG aquery(mode) → answer；统计 latency/token；
       5) 组装 §6.2 响应。"""
```

构建命令入口（在 `graphrag-service/` 下运行，供宿主侧 bootstrap 与 §10 验证）：
```bash
python -m graphrag_service.build_index --cases-dir data/cases --index-dir runtime/index --reset
```

- LightRAG 实例在服务启动时以 `working_dir`（容器内 `/app/index`，本地 `runtime/index`，由 `GRAPHRAG_WORKING_DIR` 配置）构造一次并复用（保持 index/embedding 热加载——对齐 overview 决策 2「避免冷启动毁掉延迟指标」）。
- LightRAG 构造显式传入本地轻量 tokenizer，避免首次启动因 `tiktoken` 远程下载编码文件而依赖额外网络资源；该 tokenizer 仅影响 chunk token 估算，不改变 §6 HTTP 契约。
- `mode` 映射：`local`→`QueryParam(mode="local")`，`global`→`"global"`，`hybrid`→`"hybrid"`。
- `manifest.json` 是 index 就绪与否的单一判据：`/health`、compose healthcheck、smoke 均以其存在性为准。

---

## 8. compose 集成

`compose.yaml` 新增（沿用既有服务块风格 + whisper 的 `runtime/` 挂载约定）：

```yaml
  graphrag:
    build: ./graphrag-service
    container_name: graphrag-service
    ports:
      - "8100:8100"
    environment:
      GRAPHRAG_LLM_BASE_URL: ${GRAPHRAG_LLM_BASE_URL:-https://open.bigmodel.cn/api/paas/v4}
      GRAPHRAG_LLM_API_KEY: ${GRAPHRAG_LLM_API_KEY:-}
      GRAPHRAG_LLM_MODEL: ${GRAPHRAG_LLM_MODEL:-glm-4-flash}
      GRAPHRAG_EMBED_MODEL: ${GRAPHRAG_EMBED_MODEL:-embedding-3}
      GRAPHRAG_EMBED_DIM: ${GRAPHRAG_EMBED_DIM:-2048}
      GRAPHRAG_DEFAULT_TOP_K: ${GRAPHRAG_DEFAULT_TOP_K:-5}
      GRAPHRAG_DEFAULT_MODE: ${GRAPHRAG_DEFAULT_MODE:-local}
      GRAPHRAG_WORKING_DIR: /app/index
    volumes:
      - ./graphrag-service/runtime/index:/app/index
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8100/health"]
      interval: 30s
      timeout: 5s
      retries: 3
    restart: unless-stopped
```

- 不 `depends_on` 任何服务（自包含）。
- index 经 `./graphrag-service/runtime/index` 卷挂载持久化；首次需先在宿主 `graphrag-service/` 下跑 `python -m graphrag_service.build_index ...` 填充。
- compose 修改后须确认既有 `mqtt` / `postgis` / `whisper` 服务定义未被改名、删减或改端口。
- 凭证经 `.env`（compose 变量替换）注入，`.env.example` 同步新增 `GRAPHRAG_*` 占位。

---

## 9. 配置与环境变量

`.env.example` 新增（占位，不含真实密钥）：
```
GRAPHRAG_LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
GRAPHRAG_LLM_API_KEY=
GRAPHRAG_LLM_MODEL=glm-4-flash
GRAPHRAG_EMBED_MODEL=embedding-3
```
`config.py` 读取上述变量 + `GRAPHRAG_WORKING_DIR`、`GRAPHRAG_DEFAULT_TOP_K`（默认 5）、`GRAPHRAG_DEFAULT_MODE`（默认 local）。

---

## 10. 测试计划

| 层级 | 测试 | 依赖 | 门控 |
|---|---|---|---|
| 单元 | `test_corpus.py`：全部 `data/cases/*.md` 解析通过且必填字段齐全；缺字段用例报错指明文件 | 无 | 是 |
| 契约 | `test_retrieve_contract.py`：mock LightRAG 层，`POST /retrieve` 返回 §6.2 形状；`mode` 三值被接受、`top_k` 被遵守、空 query → 400、index 缺失 → 503 | 无（mock）| 是 |
| 冒烟 | **bootstrap index → `compose up` → `GET /health` 200 → `POST /retrieve`（已知态势）返回 ≥1 case** | 真实 LLM 凭证 | 手动（即 DoD 验收）|

单元/契约测试 mock 掉 LightRAG 与 LLM 调用，可在 CI/无凭证环境跑；冒烟测试需凭证，作为 DoD 人工验收。

**验收序列（与离线索引决策 §3.2 一致）**：因服务启动只加载、不重建索引，fresh clone 不能直接 `compose up` 即通过 smoke。验收分两步：① 一次性 bootstrap——在 `graphrag-service/` 下 `python -m graphrag_service.build_index --cases-dir data/cases --index-dir runtime/index --reset` 生成 `manifest.json`；② `compose up` 拉起服务，`/health` 200 后 smoke。此处「一键」指服务启动，索引 bootstrap 是独立的一次性前置。该细化对 overview DoD「`compose up` 一键 smoke」构成 better-approach 偏离，已记入 [OVERVIEW Active Deviations](./OVERVIEW.md#active-deviations)。

---

## 11. 本步不做（显式分类）

| 项 | 分类 | 何时/何处 |
|---|---|---|
| Java `HistoricalCaseQueryPort` 与 adapter | deferred | step-2（消费本步 §6 契约）|
| `query_historical_case_graph` 工具 + advisory evidence 注入 + prompt/校验 | deferred | step-3 |
| 评测脚本 `eval/`（run_eval/judge/plots） | deferred | step-4（同目录下补）|
| 真实历史案例数据接入 | not doing | overview non-goal（本版本仅合成）|
| postgis/Neo4j 后端 | not doing（本版本）| overview non-goal「生产级图数据库部署」；§3.1 已定文件存储 |
| LightRAG 增量更新（insert 新案例后增量重算）| not doing（本步）| LightRAG 原生支持；仅当真实数据接入时才需要，而该项已属 overview deferred non-goal，无需另立 TODO |

无新增 `docs/TODO.md` 条目：上述 deferred 均由 step-2/3/4 明确承接，not-doing 均已被 overview non-goal 覆盖。

---

## 12. 偏离记录

已向 [OVERVIEW Active Deviations](./OVERVIEW.md#active-deviations) 追加两条（均 better-approach，不回写 overview 正文）：

1. **存储后端选型**（Open Questions #1）：§3.1 选定 LightRAG 文件型存储。overview 已将该项委派给 step-1，本应是单纯决策；因 overview 正文仍列其为 open，故以 appendix 记录决策，待 consolidation 移出 Open Questions。
2. **验收序列**（Version DoD #1）：§3.2 离线索引使「`compose up` 一键 smoke」需细化为「bootstrap → compose up → smoke」，见 §10。

其余未偏离：objective、deliverables、scope ceiling 均保持 overview 原义。

---

## 13. 不变式、假设与风险

### 13.1 不变式（实现期必须保持）

- `graphrag-service/data/cases/*.md` 是唯一入库语料源；index 目录可删除并重建，不入库。
- `/retrieve` 绝不返回未入库的 `case_id`；`cases` 长度不超过 `top_k`。
- 所有失败以明确 4xx/5xx JSON 表达，不返回半结构化文本。
- sidecar 不读取 Java 进程内状态；当前态势只能经 HTTP request payload 传入。
- step-1 不新增 Java 代码、不注册 agent tool、不改 advisory prompt、不动 `ADVISORY` SSE schema、不碰 `GraphQueryPort`/`MemoryGraphAdapter`（这些归 step-2/step-3）。
- `similarity_score`/`relevance` 绝对值不作为产品门槛；step-2/step-3 仅依赖结果顺序、case metadata 与字段。

### 13.2 假设

- 智谱 OpenAI 兼容端点可用且凭证可得；embedding-3 维度与 nano-vectordb 兼容（构造时显式传 embedding 维度）。
- `.gitignore` 的 `runtime/` 规则匹配任意层级，已覆盖 `graphrag-service/runtime/index/`；`.env.example` 为白名单、`.env*` 被忽略，新增 `GRAPHRAG_*` 占位安全；`__pycache__/`/`*.pyc`/`venv/` 已忽略。`runtime/index/` 当前不存在，`build_index` 首次运行时创建。

### 13.3 风险

- **已对冲**：LightRAG 检索上下文→来源 case_id 的解析随版本而变 → §3.5 catalog embedding 兜底保证 DoD。
- 合成案例多样性不足导致检索区分度低 → 由 §5 六类场景覆盖 + 风险等级/船型分布差异强制约束，step-4 评测时复核。
- LightRAG Python API 初始化参数随版本变化 → 把 LightRAG 直接调用集中在 `indexer.py` 与 `retriever.py`，不让 API 变化扩散到 §6 HTTP 契约。
