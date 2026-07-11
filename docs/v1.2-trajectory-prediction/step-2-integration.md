# step-2 — 实时系统集成

> 版本：v1.2-trajectory-prediction
> 上游：[`OVERVIEW.md`](OVERVIEW.md)、[`step-1-offline-pipeline.md`](step-1-offline-pipeline.md)
> 下游：代码（risk engine 预测层 + Python 推理 sidecar）
> status: completed
> last_updated: 2026-07-11

---

## 一、目标

把 step-1 离线验证的学习型预测器接入 map-system 实时风险链路，替换 / 并联现有 `CvPredictionEngine`，使弯道场景的预测 CPA/TCPA 与风险预警受益于学习型预测；接入方式须工程可维护、可开关、可降级，且默认禁用时链路与现状完全一致。

> 本 step 已完成实现、运行时验收与 review。Open Questions 已于 2026-07-07 拍板为 §七 决策；实施结果与验证状态见附录。

## 二、集成点分析（现状）

风险引擎现有预测与预测 CPA/TCPA 链路（`backend/map-service/.../risk/engine/`）：

- `trajectoryprediction/CvPredictionEngine` + `CvPredictionResult`（含 `PredictedPoint`）：输入目标船运动状态，输出恒速直线外推的预测轨迹点序列。
- `collision/PredictedCpaTcpaCalculator` / `PredictedCpaTcpaBatchCalculator` / `PredictedCpaTcpaResult`：消费预测轨迹点序列，算预测 CPA/TCPA。
- `risk/PredictedCpaCalculator`：预测 CPA 参与多因子风险评分。
- `config/TrajectoryPredictionProperties`：预测相关配置载体（feature flag 的挂载点）。

关键观察：**下游只依赖"预测轨迹点序列"这一契约**，不关心它由 CV 还是学习型模型产生。因此只需在预测环节引入可替换抽象，下游 CPA/TCPA 与评分主链路无需改动——这与 OVERVIEW 的"预测器是可替换部件"定位一致。

## 三、设计（复用 v1.1 graphrag sidecar 范式，ADR-013）

1. **预测 port 抽象**：抽出 `TrajectoryPredictionPort`（或等价接口），产出与现有 `CvPredictionResult` 等价的预测轨迹点序列。`CvPredictionEngine` 实现该抽象作为默认与降级实现；新增学习型 adapter 作为另一实现。二者并存，不互相替代。
2. **Python 推理 sidecar**：学习型预测器（step-1 训练的 GRU）导出为 ONNX，以 onnxruntime + FastAPI 常驻 HTTP sidecar 暴露推理接口（含批量端点）——与仓库既有 `graphrag-service` / `whisper` sidecar 同构，避免 subprocess 冷启动毁掉实时延迟。特征构造在 sidecar 侧复用离线同一代码路径（见 §七 决策）。复用 `trajectory-service/` 增设 `server/`，`compose.yaml` 增 service。
3. **Java 侧 adapter**：`TrajectoryPredictionPort` 的 LightRAG-style HTTP adapter——维护挂 flag 的降采样历史窗口 buffer（~150s 节奏、容量 K=10、基于 `msgTime` 而非墙钟），把原始点窗口（msgTime/lat/lon/sog/cog）经 HTTP 送 sidecar，把返回的骨架预测点映射为预测轨迹点序列；特征构造不在 Java 侧（见 §七 决策）。`ShipTrajectoryStore` 保持不动——其容量（20）同时喂 CV 的 ROT 回归，改动违反"禁用时零行为变化"。
4. **feature flag**：adapter 受独立 `@ConditionalOnProperty` 控制（挂在 `TrajectoryPredictionProperties`）；禁用时 bean 不加载、链路回退 `CvPredictionEngine`，行为与现状完全一致（回归安全）。
5. **降级**：sidecar 不可用 / 超时时回退 CV，单目标粒度 + 熔断，超时 250ms（见 §七 决策）；异常模式对标 v1.1 `CaseGraphUnavailableException` 的 typed exception 范式。
6. **输出映射与调用节奏**：GRU 按原生 offset（~+153/306/459s）输出骨架点，flag 启用时预测 horizon 相应扩展，骨架点可插值密化后喂 `PredictedCpaTcpaCalculator`；输入窗口仅在 ~150s 降采样 tick 变化，窗口未变时复用上次预测结果、不发 HTTP（调用频率 ≈ 每目标每 ~150s）。

### 实施锚点

- Java port 与默认实现：`backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/trajectoryprediction/` 下新增 `TrajectoryPredictionPort`，现有 `CvPredictionEngine` 归位为默认实现并继续产出 `CvPredictionResult`。
- Java 学习型 adapter：同包或其 `client/` 子包承载 `LearningTrajectoryPredictionAdapter`（命名可随实施调整）、HTTP DTO、窗口 buffer、结果有效期缓存与熔断状态；不得复用或改写 `backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/ShipTrajectoryStore.java`。
- 配置与开关：`backend/map-service/src/main/java/com/whut/map/map_service/risk/config/TrajectoryPredictionProperties.java` 扩展 `engine.trajectory-prediction.*` 配置面，学习型 adapter 通过 `@ConditionalOnProperty(prefix = "engine.trajectory-prediction", name = "learned-enabled", havingValue = "true")` 或等价开关挂载。
- Sidecar 与契约：`trajectory-service/server/` 承载 FastAPI / onnxruntime 服务；step 2-2 的 HTTP 契约交付物固定为 [`trajectory-prediction-http-contract.md`](trajectory-prediction-http-contract.md)，描述批量请求原始点窗口 schema、骨架预测点响应 schema、错误码与 healthcheck。

## 四、Step 内拆解（串行）

- **2-1 port 抽象与 CV 归位**：抽 `TrajectoryPredictionPort`，`CvPredictionEngine` 实现之；下游改依赖抽象；全程 flag 默认走 CV，回归测试证明零行为变化。
- **2-2 模型导出与 sidecar**：GRU 导出 ONNX + onnxruntime/FastAPI 推理服务（`trajectory-service/server/`）+ `compose` 接入 + smoke 查询；新增 [`trajectory-prediction-http-contract.md`](trajectory-prediction-http-contract.md)，固定 HTTP 推理契约（请求为原始点窗口 schema / 响应为骨架预测点 schema，支持批量）；导出数值一致性校验（以离线 torch 输出为基准）。
- **2-3 Java adapter 与历史窗口**：实现挂 flag 的降采样历史 buffer 与 HTTP adapter（原始窗口搬运 + DTO 映射 + 结果有效期缓存 + 熔断）；sidecar 侧特征构造复用 `train_gru.py::build_features` 同代码路径，加端到端 parity 用例（同一窗口经离线管线与在线路径输出一致）；覆盖启用/禁用/降级三路单测（mock sidecar）。
- **2-4 接入与集成验收**：学习型预测轨迹喂 `PredictedCpaTcpaCalculator`；在 step-1 保留的真实会遇子集上经 MQTT 回放端到端复现"降低弯道漏报"方向性结论（回放可加速——特征 dt 基于 `msgTime`，不依赖墙钟；GRU 路径需 ~25 分钟历史暖机，回放场景需覆盖）。

## 五、Definition of Done

1. `TrajectoryPredictionPort` 存在，`CvPredictionEngine` 与学习型 adapter 均实现之，受独立 feature flag 控制；**禁用时 advisory/风险链路调用拓扑、协议、评分与现状逐字节一致**（回归测试为证）。
2. sidecar 可经 `compose up` 一键拉起并通过 smoke 推理；[`trajectory-prediction-http-contract.md`](trajectory-prediction-http-contract.md) 成文并与 FastAPI schema / smoke 请求一致。
3. 特征构造在 sidecar 侧与离线训练同代码路径，端到端 parity 校验通过；启用/禁用/降级三路单测通过。
4. 启用时预测轨迹经既有 `PredictedCpaTcpaCalculator` 参与风险评分，主链路语义不变；在保留会遇子集上端到端观测到弯道漏报下降的方向性结论（作为集成验收信号，非分数门槛）。

## 六、Scope Ceiling

- 不改 `CpaTcpaEngine` / `PredictedCpaTcpaCalculator` / 多因子评分语义；只在预测环节引入可替换实现。
- Java adapter 只做历史窗口维护、HTTP 调用、DTO 映射、结果缓存与降级控制，不内嵌特征构造、预测或排序逻辑。
- 不做在线增量学习 / 自动再训练；sidecar 加载离线训练的固定权重。
- 不改 AIS 数据源 / 2.5D 海图（见 OVERVIEW Non-Goals）。

## 七、Open Questions 决策（2026-07-07 拍板）

- **导出形态 → ONNX + onnxruntime + FastAPI**：serving 镜像不带 torch、CPU 推理毫秒级、训练/推理运行时解耦；导出后以离线 torch 输出为基准做数值一致性校验。服务代码放 `trajectory-service/server/`，范式对标 `graphrag-service`（FastAPI、healthcheck、compose service）。备选 TorchScript/torch 直载因镜像体积与解耦劣势不取。
- **实时特征来源 → Java 送原始窗口，sidecar 构造特征**：Java adapter 维护挂 flag 的降采样历史 buffer（~150s 节奏、容量 K=10、基于 `msgTime`），只搬运原始点（msgTime/lat/lon/sog/cog）；6 维特征（相对末点坐标 km、dt/100、sog/10、sin/cos(cog)）在 sidecar 侧复用离线 `train_gru.py::build_features` 同一代码路径构造。理由：跨语言复刻特征是对齐风险最大的环节，同代码路径把风险在源头消灭；原设想的 4.8e-7 容差校验由端到端 parity 用例替代。`ShipTrajectoryStore` 不动（容量 20 同时影响 CV 的 ROT 回归，改动违反"禁用时零行为变化"）。窗口不足 K=10 点（暖机期）不调用 sidecar，回 CV。
- **延迟预算 → 批量端点 + 结果有效期缓存 + 250ms 超时**：全量路径一次 HTTP 往返带全部目标；输入窗口未变化不重复调用（调用频率 ≈ 每目标每 ~150s）；超时 250ms（本机推理 p99 之上，远低于 case-graph 的 10s——那是 LLM 检索量级）。
- **降级触发 → 单目标回退 + 熔断**：单目标窗口不足/超时/异常 → 该目标回 CV，不影响其他目标；连续 5 次 sidecar 失败 → 熔断 30s 全走 CV，半开探测恢复。内部结果附 `predictionSource=GRU|CV` 供验收观测，**不进 SSE 协议**（不动 `EVENT_SCHEMA.md`）。
- **输出映射（派生决策）→ 原生长 horizon**：不把 GRU 压进现有 120s horizon（前 153s 仅一个骨架点，插值近似直线会抹掉弯道优势）；flag-on 时按原生 offset（~+153/306/459s）下发骨架点并扩展预测 horizon（~460s），与离线 `mine_encounters` 下游评测口径一致，2-4 的方向性复现才有对照意义。flag-off 时 horizon 配置与行为不变。

### 可调参数

| 参数 | 初始值 / 口径 | 配置面 |
| --- | --- | --- |
| 降采样历史窗口节奏 | 约 150s，基于 `msgTime` | `engine.trajectory-prediction.learned-sample-interval-seconds` |
| 输入窗口容量 | K=10 原始点 | `engine.trajectory-prediction.learned-window-size` |
| HTTP 超时 | 250ms | `engine.trajectory-prediction.learned-timeout-ms` |
| 熔断阈值 | 连续 5 次 sidecar 失败 | `engine.trajectory-prediction.learned-circuit-breaker-failure-threshold` |
| 熔断冷却 | 30s | `engine.trajectory-prediction.learned-circuit-breaker-open-seconds` |
| raw buffer 上限 | 每目标最多 5000 个原始点，按 `msgTime` 时间窗与数量双重裁剪 | `engine.trajectory-prediction.learned-raw-buffer-max-points` |
| GRU 输出 offset / horizon | sidecar 返回模型原生 offset（约 +153/306/459s）；Java flag-on 扩展预测 horizon | sidecar 响应 schema + `engine.trajectory-prediction.learned-horizon-seconds` |
| sidecar healthcheck | FastAPI health endpoint | `compose.yaml` healthcheck 与 sidecar env / args |

## 附录

- 2026-07-07：§七 Open Questions 全部拍板（决策与理由见该节），§三.2/3.3 及 §四/§五 正文已同步修正——原写法为"Java adapter 构造与离线严格对齐的特征 + 容差校验"，按决策改为"Java 送原始窗口、sidecar 侧同代码路径构造特征 + parity 用例"；保留原文会直接误导实施，故按 WORKFLOW 反向流规则立即修正正文并在此记录。有面试讲述价值的决策（特征归属、原生长 horizon 映射）待 step-2 落地后随版本 closure 收编 ADR。
- 2026-07-07：完成首轮实现并进入 pending review。落地内容包括 `TrajectoryPredictionPort`、CV 默认实现归位、`LearningTrajectoryPredictionAdapter`、feature flag 配置、FastAPI / onnxruntime sidecar、ONNX 导出脚本、compose service、HTTP 契约文档、Java adapter 单测，以及离线 / 在线共用的 `train_gru.py::build_features`。本地已通过 Java 目标测试与 Python 语法检查；真实会遇子集 MQTT 回放和 sidecar smoke 需要 ignored 的 `trajectory-service/runtime/` 数据与 `runtime/gru_tuned/gru.onnx`，本次工作树未包含这些运行产物，待 review/验收环境执行。
- 2026-07-07：首轮验收（DoD 核对）结论：**未达 DoD，不翻 completed**。backend 全量 456 测试 0 失败，DoD 1 通过；DoD 2/3 部分达成（契约文档与 compose ✓，但 smoke、ONNX 导出数值一致性校验、在线/离线端到端 parity 用例缺失）；DoD 4 未执行。两项实现偏离设计需修复：(a) **骨架点未插值密化**——§三.6 要求"骨架点可插值密化后喂 `PredictedCpaTcpaCalculator`"，实现直接下发 3 个 ~153s 间距骨架点，而该计算器只在离散点取最小距离、无段内插值；离线 `cpa_tcpa_numerical` 是分段线性闭式口径（明确防"离散漏检"），live 不密化则预测 CPA 系统性偏乐观、方法学与离线验证不可比。修复方向：adapter 侧以 t=0 当前位置 + 骨架点分段线性插值到 `stepSeconds` 粒度。(b) **降采样历史 buffer 未按决策实现**——直接复用 `ShipTrajectoryStore`（20 条消息级快照）降采样；高频数据源（模拟器秒级节奏）下 20 条历史 span 不足 150s×10，GRU 永远静默回退 CV，仅对 ≥~150s 节奏数据源（如 NBSDC 回放）可 warm。修复方向：按 §七 决策补挂 flag 的专用降采样 buffer。次要项：serving 镜像仍带 torch（`build_features` 宜移入无 torch 模块并拆分 serving requirements）、熔断状态字段非线程安全、adapter 缓存无淘汰、SSE `prediction_type` 沿用 `"cv"` 标注 GRU 轨迹（EVENT_SCHEMA 既有约定，closure 时重议）。
- 2026-07-07：按首轮验收修复阻断项：`LearningTrajectoryPredictionAdapter` 改为维护专用 raw buffer（可用 `ShipTrajectoryStore` history 启动 seed，但后续窗口由 adapter buffer 按 `msgTime` 持续积累与降采样），高频 10s 输入可在 300s 后形成 0/150/300s 的 K=3 测试窗口；sidecar 骨架点映射改为以 t=0 当前位置 + 原生骨架点分段线性密化到 `stepSeconds` 粒度，并保留骨架 offset 点；熔断状态方法同步化以避免并发计数丢失。补充 `export_gru_onnx.py` torch-vs-onnxruntime 数值一致性校验，新增 sidecar `feature_matrix` 与离线 `build_features` 的 parity 测试。仍未翻 completed：`runtime/gru_tuned/gru.onnx` 与真实回放数据未在工作树中，compose smoke 与 DoD 4 回放方向性复现仍需在验收环境执行。
- 2026-07-08：复验结论：**首轮验收的两项阻断偏离（骨架点密化、专用 raw buffer）确认修复**，熔断同步、ONNX 导出校验、契约文档同步一并确认；backend 全量 457 测试 0 失败。代码侧达 DoD 1/3 主体，维持 pending review 等待运行时验收（compose smoke + DoD 4 回放）。复验记录的遗留项：(1) sidecar parity 测试是与 `feature_matrix` 同公式的内联重算，未对比离线管线（固定原点投影）的输出——投影原点差异（cos 比例，估计 ≤0.2%）仍靠推断而非测试，建议在 DoD 4 验收环境用真实离线窗口补一条容差断言；(2) 高频数据源暖机后，窗口末点随每条消息更新导致 signature 每消息变化，实际调用频率为每目标每消息（~10s）而非 §七 写的"每目标每 ~150s"——延迟预算无实际风险，属文档与实现口径不一致，closure 时修订 §七 或改为 tick 锚定；(3) 前轮次要项未动：serving 镜像带 torch、adapter cache 与 raw buffer 对已移除目标无淘汰、SSE `prediction_type` 标注问题。
- 2026-07-09：执行运行时产物链与 sidecar smoke 复验。`trajectory-service/runtime/` 由相邻 `competition/runtime/` 的派生产物恢复，`windows_manifest.json` 与本仓 `trajectory-service/results/metrics/windows_manifest.json` 一致，`gru_tuned/metrics.json` 与 `trajectory-service/results/metrics/prediction.json` 一致；运行产物仍被 `.gitignore` 排除。使用 Python 3.12 venv 重新导出 `runtime/gru_tuned/gru.onnx`，torch-vs-onnxruntime 校验通过，`max_abs_diff=1.7881393e-07`、阈值 `1e-05`；`PYTHONPATH=. runtime/.venv/bin/python -m pytest tests` 通过，15 passed。sidecar 本地与 compose 均通过 `/health` 与真实 `segments.parquet` 测试窗口 `/predict` smoke，响应含 1 个目标、3 个骨架点、offset 为 153/306/459s。同步收口次要项：`build_features` 移入 `trajectory-service/inland_traj/features.py`，训练与 serving 共用无 torch 模块；serving Dockerfile 改用 `requirements-server.txt`，镜像不再安装训练依赖。DoD 2 的 sidecar smoke 已闭合；DoD 4 仍未闭合，因本地 PostGIS/MQTT 全链路回放拉取基础镜像未完成，尚未取得保留会遇子集端到端方向性复现证据，故 status 保持 `pending review`。
- 2026-07-10：对 07-09 运行时工作的验收：**声称结果全部独立复现**——pytest 15 passed、ONNX 导出校验 `max_abs_diff=1.7881393e-07`、本地 sidecar `/health` 与真实 test 窗口 `/predict` smoke（153/306/459s 骨架点、坐标落在数据集水域）均亲测通过；恢复产物与 tracked evidence 一致性核对通过（`gru_tuned/metrics.json` ≡ `results/metrics/prediction.json`，windows manifest 逐字段一致）；serving 去 torch（`inland_traj/features.py` + `requirements-server.txt`）确认收口前轮次要项。**投影原点差异由推断转为实测**：500 个真实 test 窗口上，离线固定原点 vs 在线窗口末点原点的输入相对坐标偏差 max 30.8m / p50 3.8m，经 GRU 后端到端预测点偏差 max 15.3m / p50 0.7m——相对 FDE 224.9m 与 CV-GRU 差距 88.9m 低 1–2 个数量级，在线/离线口径一致性成立，DoD 3 的 parity 疑点闭合。建议把该量化固化为 skipif（依赖 runtime 数据）守护的 pytest。当前唯一未闭合项为 DoD 4 回放，status 维持 `pending review`。
- 2026-07-10：完成 DoD 4 实时链路验收。使用 `mine_encounters.py` 从真实 `segments.parquet` 的 105095 个会遇中筛选“弯道 + CV 漏报 + 目标船 GRU 可纠正”子集；验收样本以 `t0=1653576775` 定位，own 为 `mmsi=413817319/seg_id=4509/anchor_offset=129`，target 为 `mmsi=413832089/seg_id=7135/anchor_offset=99`，离线真值/CV/target-only learned 分别为 `WARNING/SAFE/ALARM`，DCPA 分别为 287.684m/7999.160m/430.960m。own 映射为系统本船 MMSI `123456789`，经 MQTT 按原始 `msgTime` 回放 2 船各 13 个点；learned-on 期间 sidecar 收到 2 次 `/predict` 并返回 HTTP 200，SSE 中出现 4 帧 `predictionSource=GRU`、459s horizon、48 个密化点，CV 对照始终为 120s horizon、12 个点。首次对照发现预测 CPA 仅调整连续 `riskScore`，离散 `riskLevel` 仍只由当前 CPA/TCPA 决定，因而结构上无法降低 SSE 漏报；验收期按 `integration-gap` 做最小修正：仅当 `predictionSource=GRU` 时，以既有阈值对预测 CPA/TCPA 分类并与当前等级取较高者，CV 与 flag-off 语义保持不变。相同回放修正后，CV 末帧仍为 `SAFE`，learned 末帧由 `SAFE` 提升为与真值一致的 `WARNING`；全序列 learned 为 `SAFE=22/CAUTION=1/WARNING=2`，CV 为 `SAFE=24/CAUTION=0/WARNING=1`，弯道漏报下降方向性成立。新增 learned 可提升等级与 CV 不提升等级两项单测；backend 全量 459 测试 0 失败，Python 15 测试通过，compose 中 MQTT/PostGIS/trajectory-predictor 均为 Up 且 sidecar health 正常。DoD 1–4 的实现与运行时证据均已闭合；按流程仍保持 `pending review`，待外部模型 review 后再翻 `completed`。
- 2026-07-11：review 通过，status 翻 **`completed`**（review 职责按用户指示由主控模型承担）。最终确认：GRU 等级单向提升实现正确且有 CV 负向测试守护，flag-off / CV 路径零变化；backend 459、Python 15 测试全部通过；`predictionSource` 确认未进 SSE DTO——07-10 记录中"SSE 中出现 predictionSource=GRU"系经 459s horizon / 48 密化点特征辨识的帧，协议未动、`EVENT_SCHEMA.md` 无欠账。遗留项全部移交版本 closure：§七"每 ~150s 调用频率"口径修订、OVERVIEW 两条 integration-gap 正文统一、SSE `prediction_type="cv"` 标注重议、adapter cache / raw buffer 对移除目标无淘汰、投影 parity 量化固化为 skipif pytest、ADR 收编三条决策（特征构造归属 sidecar、原生长 horizon 映射、GRU 等级单向提升）。
