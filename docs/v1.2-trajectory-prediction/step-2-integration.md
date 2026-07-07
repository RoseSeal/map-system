# step-2 — 实时系统集成

> 版本：v1.2-trajectory-prediction
> 上游：[`OVERVIEW.md`](OVERVIEW.md)、[`step-1-offline-pipeline.md`](step-1-offline-pipeline.md)
> 下游：代码（risk engine 预测层 + Python 推理 sidecar）
> status: pending review
> last_updated: 2026-07-07

---

## 一、目标

把 step-1 离线验证的学习型预测器接入 map-system 实时风险链路，替换 / 并联现有 `CvPredictionEngine`，使弯道场景的预测 CPA/TCPA 与风险预警受益于学习型预测；接入方式须工程可维护、可开关、可降级，且默认禁用时链路与现状完全一致。

> 本 step 已完成首轮实现，待 review。Open Questions 已于 2026-07-07 拍板为 §七 决策；实施结果与验证状态见附录。

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
