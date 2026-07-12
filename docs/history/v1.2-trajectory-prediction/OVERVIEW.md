# v1.2-trajectory-prediction — 学习型目标船航迹预测版本总览

> name: map-system v1.2-trajectory-prediction
> description: 把风险引擎的目标船航迹预测从 CV（恒速直线）外推升级为在真实内河 AIS 上训练的学习型序列模型（GRU seq2seq），以降低内河弯道场景下 CPA/TCPA 风险预警的漏报。分两轨：离线验证轨（已完成，见 step-1）与实时系统集成轨（已完成，见 step-2）。
> last_updated: 2026-07-12
> status: completed

---

## Derives From

- [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §八 Roadmap P3「目标船航迹预测：CV 恒速模型 + 历史轨迹存储」（已 `[x]` 交付）与「多因子风险评估增强：整合域侵入检测、预测 CPA、会遇类型修正」——本版本升级其中的预测环节。
- [`../TODO.md`](../TODO.md) §2「CT (Constant Turn) 航迹预测模型」——该 backlog 项预期"在历史轨迹数据充足后，升级当前的 CV 模型……以提高弯道航迹预测精度"。本版本是该项的实施承接（以学习型模型实现，覆盖并超出 CT 的意图）；overview 落地后该条按 TODO §0 移出规则从清单移除。
- [`../ADR_AND_REVIEW_FINDINGS.md`](../ADR_AND_REVIEW_FINDINGS.md)：ADR-013（v1.1 以「独立 port + HTTP sidecar + 顶层服务目录 + feature flag」接入外部 Python 能力）——本版本的集成轨（step-2）复用同一范式接入学习型预测器；新增 ADR-014 记录本版本的预测器升级取舍。

---

## Positioning

map-system 的风险引擎当前用 `CvPredictionEngine`（恒速直线外推）生成目标船预测轨迹，交由 `PredictedCpaTcpaCalculator` 计算预测 CPA/TCPA 并参与多因子风险评分。CV 外推的结构性局限在**内河弯道**：航道约束与操纵行为使真实航迹偏离直线，CV 预测轨迹随之偏离，进而使预测 DCPA/TCPA 偏差、风险等级判定偏差。

本版本要回答并落地的一句话问题：**用真实内河 AIS 训练的学习型预测器替换 CV，能否可度量地降低弯道场景下的风险预警偏差（尤其漏报），并以工程可维护的方式接入现有实时链路。**

该问题的前半段（能否改善）已在离线轨（step-1）用真实数据验证——结论为正，且改善随预测时域增大、在弯道桶最显著（见下「离线验证结论」）。后半段（如何接入）已在集成轨（step-2）完成实现、运行时验收与 review；Open Questions 已于 2026-07-07 在 step-2 设计中拍板为具体实施决策。

定位：贡献落点是"预测升级带来的下游风险决策改善"，不是裸预测精度；预测器是可替换部件，不改写 CPA/TCPA 算法与基础评分。启用 learned 路径时，预测 CPA/TCPA 可按既有阈值**单向提升**目标风险等级（step-2 运行时验收发现的 integration-gap 修正：预测 CPA 原本只调节连续 riskScore、不影响离散告警等级，learned 纠正的漏报无法在 SSE 兑现）；CV 与 flag-off 路径语义不变。

---

## 离线验证结论（step-1 已完成，作为集成轨的依据）

数据：NBSDC「水上交通安全风险协同分析和管控系统船舶 AIS 数据集」武汉长江段（2022-05，约 11.36 天，1.37e7 行 / 6232 MMSI），清洗切段后得 1.8e6 航行点 / 12265 段 / 4382 船，弯道点占比 10.07%，固定 train/val/test 段级切分。数据来源、课题号与共享口径详见 step-1。

- **预测精度**（测试集 168554 窗口，预测未来第 1/2/3 个真实观测点 ≈ +2.5/+5/+7.5 min）：FDE 由 CV 313.8 m 降至 GRU 224.9 m（−28.3%）；改善随时域递增（10.1% / 20.4% / 28.3%）。CV 基线取末 3 点最小二乘速度，与几何核心 `cv_predict` 同口径，避免弱基线夸大增益。
- **下游风险**（105095 个真实会遇，真危险率 31.3%，CV 与学习型走同一几何评估、特征对齐校验 max_abs_diff≈4.8e-7）：真危险中的漏报率由 CV 12.2% 降至 GRU 8.6%；**弯道桶** 16.5%→11.4%，直线桶 9.5%→6.9%；风险等级匹配率 86.0%→86.9%。
- **诚实权衡**：漏报下降的同时，真安全中的误报率由 2.8% 微升至 3.2%——即预测更"敢报警"，牺牲少量误报换取漏报显著下降；在碰撞风险场景（漏报代价远高于误报）方向可接受。

完整指标见 [`../../trajectory-service/results/metrics/`](../../trajectory-service/results/metrics/)，图见 [`../../trajectory-service/results/figures/`](../../trajectory-service/results/figures/)。

---

## Version-Level Definition of Done

### 离线轨（must，已完成）

1. 真实内河 AIS 数据闸门（probe）通过，产出数据契约与 go/no-go。
2. 可复现清洗 + 切分，固定 train/val/test 轨迹集，判据成文。
3. 从轨迹挖出 pairwise 会遇集，定义真值 DCPA/TCPA 与风险标签（含负样本）。
4. CV 基线 + 闭式 CPA/TCPA + ADE/FDE + 下游误报/漏报刻画。
5. 至少一个学习型模型（GRU seq2seq）跑出测试集 ADE/FDE 与下游风险对比，含直线/弯道分桶。

### 集成轨（must，已完成）

6. 抽出 `TrajectoryPredictionPort`（或等价抽象），使 `CvPredictionEngine` 与学习型预测 adapter 并存、受独立 feature flag 控制；禁用时链路与现状完全一致（回归安全）。
7. 学习型预测经 ONNX + onnxruntime/FastAPI Python 推理 sidecar（对标 v1.1 graphrag / whisper sidecar）以 HTTP 批量端点提供服务，Java 侧 adapter 只搬运原始点窗口并映射为 `CvPredictionResult` 等价的预测轨迹点序列，喂给既有 `PredictedCpaTcpaCalculator`，主链路拓扑与协议不变。
8. 特征构造在 sidecar 侧复用离线训练同代码路径，端到端 parity 用例通过；HTTP 契约以 [`trajectory-prediction-http-contract.md`](trajectory-prediction-http-contract.md) 成文并覆盖原始点窗口请求、骨架预测点响应、错误码与 healthcheck。
9. 在离线保留的真实会遇子集上，端到端复现"学习型预测降低弯道漏报"的方向性结论作为集成验收信号（非分数门槛）。

---

## Non-Goals

### 本版本不做（deferred，后续版本可做）

- 把真实内河 AIS 铺到 live 2.5D 海图 / 替换现有仿真数据源。
- 从 AIS 反推航道网络 / 交通流（chart-free 结构学习）；航道约束建模（S-57/S-101）见 TODO。
- 异常行为检测（偏航 / 违规停泊 / 逆行）。
- 上 Transformer / social-pooling 等重模型；本版本以 GRU seq2seq 为落地基线。
- 预测器在线增量学习 / 自动再训练管线。

### 本版本不做（never，跨版本边界）

- 改写 CPA/TCPA 与风险评分主链路：预测器是可替换部件，只换预测环节，不动 `CpaTcpaEngine` / `PredictedCpaTcpaCalculator` 语义与多因子评分。（closure 注：learned 路径的"预测等级单向提升"是在既有阈值与评分之上的门控增强，不改写算法本身，见 Positioning。）
- 合并 / 重写第二套 COLREGS 或碰撞评估引擎。

---

## Step Breakdown

- **step-1 离线管线与验证**（status: completed）：真实内河 AIS → 清洗切段 → 会遇挖掘 → CV 基线 vs GRU → ADE/FDE 分桶 + 下游 CPA/TCPA 误报/漏报。代码与产物落在顶层 [`../../trajectory-service/`](../../trajectory-service/)。详见 [`step-1-offline-pipeline.md`](step-1-offline-pipeline.md)。
- **step-2 实时系统集成**（status: completed）：`TrajectoryPredictionPort` 抽象 + ONNX/FastAPI 学习型预测 sidecar + Java 原始窗口 adapter + feature flag，接入现有预测 CPA/TCPA 链路；真实弯道会遇 MQTT 回放已复现 learned 路径降低漏报的方向性结论。详见 [`step-2-integration.md`](step-2-integration.md)。

---

## Sequencing Constraints

- step-1 离线轨已收口，其保留的会遇子集与训练权重是 step-2 集成验收（DoD #9）的输入。
- step-2 内部串行依赖：port 抽象 → sidecar 推理契约（[`trajectory-prediction-http-contract.md`](trajectory-prediction-http-contract.md)）→ Java adapter → 接入 `PredictedCpaTcpaCalculator` → 集成验收；feature flag 从第一步就位以保证禁用回退。

---

## Step-2 决策（2026-07-07）

- **模型导出与推理形态**：GRU 导出 ONNX，serving 使用 onnxruntime + FastAPI，代码落在 `trajectory-service/server/` 并接入 `compose.yaml`。
- **实时特征来源**：Java adapter 维护独立降采样历史窗口并只向 sidecar 搬运原始点；特征构造在 sidecar 侧复用离线同代码路径（实施后统一抽至 `inland_traj/features.py::build_features`，训练与 serving 共用），`ShipTrajectoryStore` 保持不动。
- **延迟预算**：采用批量端点、结果有效期缓存与 250ms 初始 HTTP 超时；输入窗口未变化时不重复请求。
- **降级策略**：单目标窗口不足 / 超时 / 异常时回 CV，连续 5 次 sidecar 失败后熔断 30s；内部观测 `predictionSource=GRU|CV`，不改 SSE 协议。
- **输出 horizon**：flag-on 时保留 GRU 原生 offset（约 +153/306/459s）并扩展预测 horizon，flag-off 时现有 120s horizon 与行为不变。

---

## Active Deviations

> 追加式附录：实施期发现更优方案或与正文不一致，在此追加，不回写正文；版本翻 completed 时统一并合。

（已回收：两条 deviation——`build_features` 特征构造抽取、learned 预测等级单向提升——均已于 2026-07-12 closure 时按各自 canonical resolution 并合进本文 Positioning / Non-Goals / Step-2 决策正文，并同步 `ARCHITECTURE.md` 与 ADR-015。原始记录见 git 历史。）
