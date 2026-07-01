# step-2 — 实时系统集成

> 版本：v1.2-trajectory-prediction
> 上游：[`OVERVIEW.md`](OVERVIEW.md)、[`step-1-offline-pipeline.md`](step-1-offline-pipeline.md)
> 下游：代码（risk engine 预测层 + Python 推理 sidecar）
> status: planning
> last_updated: 2026-07-01

---

## 一、目标

把 step-1 离线验证的学习型预测器接入 map-system 实时风险链路，替换 / 并联现有 `CvPredictionEngine`，使弯道场景的预测 CPA/TCPA 与风险预警受益于学习型预测；接入方式须工程可维护、可开关、可降级，且默认禁用时链路与现状完全一致。

> 本 step 尚未实现，本文为实施前的设计共识。落地细节以实施期 step 附录与代码为准。

## 二、集成点分析（现状）

风险引擎现有预测与预测 CPA/TCPA 链路（`backend/map-service/.../risk/engine/`）：

- `trajectoryprediction/CvPredictionEngine` + `CvPredictionResult`（含 `PredictedPoint`）：输入目标船运动状态，输出恒速直线外推的预测轨迹点序列。
- `collision/PredictedCpaTcpaCalculator` / `PredictedCpaTcpaBatchCalculator` / `PredictedCpaTcpaResult`：消费预测轨迹点序列，算预测 CPA/TCPA。
- `risk/PredictedCpaCalculator`：预测 CPA 参与多因子风险评分。
- `config/TrajectoryPredictionProperties`：预测相关配置载体（feature flag 的挂载点）。

关键观察：**下游只依赖"预测轨迹点序列"这一契约**，不关心它由 CV 还是学习型模型产生。因此只需在预测环节引入可替换抽象，下游 CPA/TCPA 与评分主链路无需改动——这与 OVERVIEW 的"预测器是可替换部件"定位一致。

## 三、设计（复用 v1.1 graphrag sidecar 范式，ADR-013）

1. **预测 port 抽象**：抽出 `TrajectoryPredictionPort`（或等价接口），产出与现有 `CvPredictionResult` 等价的预测轨迹点序列。`CvPredictionEngine` 实现该抽象作为默认与降级实现；新增学习型 adapter 作为另一实现。二者并存，不互相替代。
2. **Python 推理 sidecar**：学习型预测器（step-1 训练的 GRU）导出为可服务形态（TorchScript / ONNX，见 Open Questions），以常驻 Python HTTP sidecar 暴露推理接口——与仓库既有 `graphrag-service` / `whisper` sidecar 同构，避免 subprocess 冷启动毁掉实时延迟。顶层新增服务目录（或复用 `trajectory-service/` 增设 `server`），`compose.yaml` 增 service。
3. **Java 侧 adapter**：`TrajectoryPredictionPort` 的 LightRAG-style HTTP adapter——把目标船历史轨迹窗口构造成与离线**严格对齐**的特征（输入构造、单位、坐标参考一致），HTTP 调 sidecar，把返回映射为预测轨迹点序列。
4. **feature flag**：adapter 受独立 `@ConditionalOnProperty` 控制（挂在 `TrajectoryPredictionProperties`）；禁用时 bean 不加载、链路回退 `CvPredictionEngine`，行为与现状完全一致（回归安全）。
5. **降级**：sidecar 不可用 / 超时时回退 CV，触发粒度与超时预算见 Open Questions；对标现有 LLM 链路 `CompletableFuture` 超时降级。

## 四、Step 内拆解（串行）

- **2-1 port 抽象与 CV 归位**：抽 `TrajectoryPredictionPort`，`CvPredictionEngine` 实现之；下游改依赖抽象；全程 flag 默认走 CV，回归测试证明零行为变化。
- **2-2 模型导出与 sidecar**：GRU 导出 + Python 推理服务 + `compose` 接入 + smoke 查询；定 HTTP 推理契约（请求特征 schema / 响应轨迹点 schema）。
- **2-3 Java adapter 与特征对齐**：实现 HTTP adapter + 特征构造，加特征对齐校验（与离线 `train_gru.py::load_split` 口径比对，容差参照离线 4.8e-7 量级）；覆盖启用/禁用/降级三路单测（mock sidecar）。
- **2-4 接入与集成验收**：学习型预测轨迹喂 `PredictedCpaTcpaCalculator`；在 step-1 保留的真实会遇子集上端到端复现"降低弯道漏报"方向性结论。

## 五、Definition of Done

1. `TrajectoryPredictionPort` 存在，`CvPredictionEngine` 与学习型 adapter 均实现之，受独立 feature flag 控制；**禁用时 advisory/风险链路调用拓扑、协议、评分与现状逐字节一致**（回归测试为证）。
2. sidecar 可经 `compose up` 一键拉起并通过 smoke 推理；HTTP 契约成文。
3. adapter 特征构造与离线训练对齐并有校验通过；启用/禁用/降级三路单测通过。
4. 启用时预测轨迹经既有 `PredictedCpaTcpaCalculator` 参与风险评分，主链路语义不变；在保留会遇子集上端到端观测到弯道漏报下降的方向性结论（作为集成验收信号，非分数门槛）。

## 六、Scope Ceiling

- 不改 `CpaTcpaEngine` / `PredictedCpaTcpaCalculator` / 多因子评分语义；只在预测环节引入可替换实现。
- adapter 只做特征构造 + HTTP 调用 + DTO 映射，不内嵌预测/排序逻辑。
- 不做在线增量学习 / 自动再训练；sidecar 加载离线训练的固定权重。
- 不改 AIS 数据源 / 2.5D 海图（见 OVERVIEW Non-Goals）。

## 七、Open Questions

- **导出形态**：TorchScript vs ONNX；sidecar 用 FastAPI（对标 graphrag-service）还是复用既有推理容器。
- **实时特征来源**：历史轨迹窗口从 `ShipStatus` 历史缓存还是预测引擎自带的历史轨迹存储取；窗口长度与采样节奏如何与离线 ~153s 对齐（实时 AIS 节奏可能不同，需重采样/插值策略且不得把弯道拉直）。
- **延迟预算**：sidecar HTTP 往返对风险帧率影响；批量预测（`PredictedCpaTcpaBatchCalculator` 对应多目标）与缓存策略。
- **降级触发**：超时阈值、连续失败熔断、回退 CV 的粒度（单目标 vs 整帧）。
