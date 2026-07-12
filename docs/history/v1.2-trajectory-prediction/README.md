# v1.2-trajectory-prediction Milestone — Closure Summary

> 文档状态：archived（v1.2 closure summary）
> 关闭日期：2026-07-12
> 用途：归档 v1.2-trajectory-prediction milestone 的目标、产出与收口结论；指引读者从这里跳转到 current truth。
> 非目标：本文与本目录下任何文件**都不再是 current truth**。当前实现真值以 active docs 为准。

---

## 1. v1.2 目标回顾

v1.2-trajectory-prediction 把风险引擎的目标船航迹预测从 CV（恒速直线）外推升级为在真实内河 AIS 上训练的学习型序列模型（GRU seq2seq），以降低内河弯道场景下 CPA/TCPA 风险预警的漏报。验收口径是**下游风险决策改善**（尤其真危险漏报率），不是裸预测精度。

里程碑分两轨：

| Track | Release impact | 收口形态 |
| --- | --- | --- |
| `step-1` 离线管线与验证（数据闸门 → 清洗切段 → 窗口化 → GRU 训练 → 会遇挖掘与下游风险评测） | 集成轨的 go/no-go 依据 | 完成 |
| `step-2` 实时系统集成（port 抽象 + ONNX sidecar + Java adapter + feature flag + 回放验收） | 阻塞 v1.2 主版本 | 完成 |

## 2. 已完成的 step 与主要系统增量

### 2.1 Step 1：离线管线与验证（offline-first）

- 顶层 `trajectory-service/`：NBSDC 武汉长江段真实 AIS（2022-05，1.37e7 行）→ 清洗切段（1.8e6 航行点 / 12265 段）→ K=10 窗口化（~153s 节奏）→ GRU seq2seq（hidden 128 × 2 层，6 维运动学特征）训练与评测。
- **预测精度**：测试集 168,554 窗口，FDE 由 CV 313.8 m 降至 GRU 224.9 m（−28.3%），改善随时域递增（10.1% / 20.4% / 28.3%）。CV 基线取末 3 点最小二乘速度（诚实基线）。
- **下游风险**：105,095 个真实会遇上，真危险漏报率 12.2% → 8.6%，弯道桶 16.5% → 11.4%；代价是误报 2.8% → 3.2%（诚实权衡，碰撞场景漏报代价远高于误报，方向可接受）。
- 证据产物 tracked 于 `trajectory-service/results/metrics|figures/`；原始数据与训练产物 gitignored、可由管线复现。

### 2.2 Step 2：实时系统集成

- **port 抽象**：新增 `TrajectoryPredictionPort`；`CvPredictionEngine` 归位为默认与降级实现，`LearningTrajectoryPredictionAdapter` 为学习型实现；受 `engine.trajectory-prediction.learned-enabled` 独立 flag 控制，禁用时链路与现状逐字节一致（回归测试为证）。
- **推理 sidecar**：GRU 导出 ONNX（torch-vs-onnxruntime 数值校验 max_abs_diff≈1.8e-7），`trajectory-service/server/` onnxruntime + FastAPI 提供 `/health` 与 `/predict` 批量端点；serving 依赖与训练拆分（`requirements-server.txt`，镜像不带 torch）；`compose.yaml` 增 `trajectory-predictor` 服务。
- **Java adapter**：维护挂 flag 的降采样原始点窗口 buffer（~150s 节奏、K=10、基于 `msgTime`，`ShipTrajectoryStore` 不动）；特征构造在 sidecar 侧复用离线同代码路径（`inland_traj/features.py::build_features`）；骨架预测点（~+153/306/459s）以 t=0 当前位置为起点分段线性密化到 10s 粒度后喂既有 `PredictedCpaTcpaCalculator`；单目标失败回退 CV + 连续 5 次失败熔断 30s。
- **等级单向提升（integration-gap 修正）**：运行时验收发现预测 CPA 原本只调节连续 riskScore、不影响离散告警等级；修正为仅 `predictionSource=GRU` 时以既有阈值分类预测 CPA/TCPA 并与当前等级取 max。
- **回放验收（DoD 4）**：真实弯道会遇经 MQTT 按原始 `msgTime` 回放，learned 末帧告警由 `SAFE`（CV 漏报）提升为与离线真值一致的 `WARNING`，复现"降低弯道漏报"方向性结论。
- **在线/离线口径一致性**：投影原点差异（离线固定原点 vs 在线窗口末点原点）经 500 真实窗口实测，端到端预测点偏差 p50 0.7 m / max 15.3 m，比效应量低 1–2 个数量级；固化为 skipif parity 测试（`tests/test_feature_parity.py`）。

### 2.3 协议层产出

- `predicted_trajectory.prediction_type` 增补枚举值 `"gru"`（learned 生成的轨迹帧标识；默认/禁用/降级仍为 `"cv"`），`horizon_seconds` 在 GRU 帧扩展至约 460s；字段结构不变。协议真值见 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) 变更记录 2026-07-11 条目。

## 3. 对应 ADR

- **ADR-014**：offline-first 验证 + 独立预测 port + 推理 sidecar 集成、下游主链路不变、以下游风险改善为验收口径。
- **ADR-015**：三项实施决策——特征构造归属 sidecar（同代码路径消灭对齐风险）、原生长 horizon 骨架密化、GRU 预测等级单向提升（integration-gap 修正）。

## 4. 收口时冻结的遗留项

- adapter 结果缓存 / raw buffer 对已移除目标无淘汰 → `docs/TODO.md` §2（demo 规模无影响）。
- 轨迹预测特征引入地图/水文先验（航道几何、水位流速）→ `docs/TODO.md` §2（竞赛答辩洞察，2026-07）。

## 5. 过程记录索引（非 current truth）

- [`OVERVIEW.md`](./OVERVIEW.md)：版本目标、离线验证结论、DoD 与决策记录。
- [`step-1-offline-pipeline.md`](./step-1-offline-pipeline.md)：离线管线与验证全记录。
- [`step-2-integration.md`](./step-2-integration.md)：集成设计共识、§七 Open Questions 决策、多轮实施/验收/修复附录（含 DoD 4 回放证据与两次 integration-gap 处置）。
- [`trajectory-prediction-http-contract.md`](./trajectory-prediction-http-contract.md)：sidecar HTTP 推理契约。
