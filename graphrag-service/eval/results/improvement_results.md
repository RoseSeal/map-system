# GraphRAG 改进验证结果

## 数据核验

- 数据来源：`eval/results/scores.csv`，运行指纹 `935e5f3596d2c44f0918259dee603c318553cc931a8c0a375fed15c520a9c0e6`。
- 查询与语料：queries_hash `3240af23872df3b9b92513e1db63bbd5f8b66269d2e3ba3dd5da22560eab7ad4`；corpus_hash `c4f80ad977fa712c2e61fd58a1758298752784ba0a4840ff2efbc298dcbaf98d`。
- 判分：gemini / gemini-2.5-pro；生成模型 `glm-4-flash`。
- 行数与均值已通过脚本硬检查：64 行，16 条查询 × 4 模式；四模式均值与既有基准一致。

## 任务一：自适应路由后处理

| 策略 | 综合均分 | Δ vs naive | 均延迟(s) | 选择分布 |
|---|---:|---:|---:|---|
| always-naive | 8.3542 | +0.0000 | 25.41 | naive=16, local=0, global=0, hybrid=0 |
| always-local | 5.7917 | -2.5625 | 39.11 | naive=0, local=16, global=0, hybrid=0 |
| always-global | 4.6250 | -3.7292 | 39.89 | naive=0, local=0, global=16, hybrid=0 |
| always-hybrid | 4.9167 | -3.4375 | 43.44 | naive=0, local=0, global=0, hybrid=16 |
| oracle | 8.9792 | +0.6250 | 28.49 | naive=13, local=1, global=1, hybrid=1 |
| oracle-exclude-naive | 6.8750 | -1.4792 | 38.96 | naive=0, local=7, global=4, hybrid=5 |
| category-rule-in-sample | 8.3542 | +0.0000 | 25.41 | naive=16, local=0, global=0, hybrid=0 |
| category-rule-loocv | 8.3542 | +0.0000 | 25.41 | naive=16, local=0, global=0, hybrid=0 |

类别规则为 A->naive、B->naive、C->naive、D->naive，因此类别层规则退化为 always-naive。
逐查询 oracle 均分为 8.9792，较 naive 高 +0.6250，但仅 3/16 条查询由非 naive 模式胜出。
LOOCV 类别规则均分为 8.3542，较 naive +0.0000，说明当前 16 条数据不足以学习出优于 naive 的类别规则。

非 naive oracle 胜出查询：

| query_id | 类别 | 选择模式 | 选择质量 | naive质量 | Δ |
|---|---|---|---:|---:|---:|
| C-02 | C | local | 8.6667 | 6.6667 | +2.0000 |
| D-02 | D | hybrid | 8.0000 | 2.0000 | +6.0000 |
| D-04 | D | global | 8.0000 | 6.0000 | +2.0000 |

## 任务二：相关度门控 / 拒答模拟

当前产物缺少真实逐查询相关度分数；`cases[].relevance` 为系统合成排序分，不能作为阈值优化依据。因此本节采用分支 B：将 D 类视为语料外代理类别，对图检索答案替换为假设拒答分 `a` 做敏感性扫描。

| 假设拒答分 a | D类图模式原均分 | D类代理门控均分 | Δ |
|---:|---:|---:|---:|
| 3 | 4.6111 | 3.0000 | -1.6111 |
| 4 | 4.6111 | 4.0000 | -0.6111 |
| 5 | 4.6111 | 5.0000 | +0.3889 |
| 6 | 4.6111 | 6.0000 | +1.3889 |
| 7 | 4.6111 | 7.0000 | +2.3889 |
| 8 | 4.6111 | 8.0000 | +3.3889 |

在该代理设定下，当拒答答案可获得至少 5 分时，D 类图模式均值高于原图检索 D 类均值。该结论只说明“拒答可能缓解语料外幻觉”，不等价于真实相关度阈值已经被验证。

## 任务三：rerank 消融

| 模式 | 无rerank均分 | 有rerank均分 | Δ | 无rerank延迟(s) | 有rerank延迟(s) |
|---|---:|---:|---:|---:|---:|
| naive | 7.7500 | 7.7500 | +0.0000 | 25.41 | 25.41 |
| local | 6.0625 | 6.0625 | +0.0000 | 39.11 | 8.49 |
| global | 4.3750 | 4.3750 | +0.0000 | 39.89 | 18.79 |
| hybrid | 4.8333 | 4.8333 | +0.0000 | 43.44 | 13.65 |

rerank 消融结果已按 `scores_rerank.csv` 生成；无 rerank 基线使用 `/Users/roseseal/workspace/flagship-projects/map-system/graphrag-service/eval/results/scores_no_rerank_agy.csv`，解读时仍须限定为 16 条小样本描述性对比。
注意：该 rerank 图表使用 `agy / gemini-2.5-pro` 对无 rerank 与有 rerank 两组结果进行同口径重判，与原始 `scores.csv` 的旧 judge 绝对分数不属于同一尺度；因此只应读取组内 Δ，不应与主实验图表的绝对均分直接对读。
runtime 记录显示，rerank 后图检索候选集合或顺序发生变化（local 5/16、global 10/16、hybrid 10/16），但三种图模式共 48 个最终 answer 文本全部未变。结合 LightRAG `openai_complete_if_cache` 与 query cache hit 日志，该 run 只能证明 reranker 接入和候选重排生效，不能验证 rerank 对生成答案质量的影响；不应解释为 rerank 已确认无收益。

## 回填建议

- §6 增补 oracle 上界：逐查询 oracle 为 8.9792，说明理论空间存在但只集中在少数查询。
- §7.1 删除 “C 类路由至 hybrid” 的规则路由表述，改为类别规则与 LOOCV 均退化为 naive。
- §7.2 写入分支 B 代理验证，明确缺少真实相关度阈值数据。
- §6.5/§9.2 写入 rerank 消融结果，并注明实际 reranker 模型、生成缓存阻断与小样本描述性限制。
