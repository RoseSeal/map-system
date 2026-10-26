# Docs Index

> 文档状态：current
> 最后更新：2026-10-26
> 用途：为 `docs/` 提供入口索引，并明确"当前真值 / 规划 / 历史归档"的边界。

## 0. 阅读顺序

文档体系存在多个入口；建议按以下顺序使用，避免在重叠入口间反复跳转：

1. [`../README.md`](../README.md)：项目一句话与技术栈速览。
2. [`../PROJECT_STATUS.md`](../PROJECT_STATUS.md)：milestone 级状态与 active track 指针。
3. **本文件**：documentation map，定位"该看哪份真值/规划/归档"。
4. [`ARCHITECTURE.md`](./ARCHITECTURE.md) 与 [`EVENT_SCHEMA.md`](./EVENT_SCHEMA.md)：当前真值。
5. [`history/v1.2-trajectory-prediction/README.md`](./history/v1.2-trajectory-prediction/README.md)：v1.2 已完成并归档，此 closure summary 是了解学习型轨迹预测增量与对应 ADR 的入口（**不是 current truth**）。
6. [`history/v1.1-graphrag/README.md`](./history/v1.1-graphrag/README.md)：v1.1-graphrag 已完成并归档，此 closure summary 是了解历史案例 GraphRAG 增量与对应 ADR 的入口（**不是 current truth**）。
7. [`history/v1.0/README.md`](./history/v1.0/README.md)：v1.0 已完成并归档，此 closure summary 是了解 v1.0 增量与对应 ADR 的入口（**不是 current truth**）。

[`../CURRENT_SYSTEM_OVERVIEW.md`](../CURRENT_SYSTEM_OVERVIEW.md) 是"任务 → 文件"导航工具，**不是 first stop**，已定位到模块后再用。

## 1. 当前真值

- [`ARCHITECTURE.md`](./ARCHITECTURE.md)：系统架构总览、稳定能力边界、模块职责与主链路。
- [`EVENT_SCHEMA.md`](./EVENT_SCHEMA.md)：实时协议真值源；`risk` SSE 与 `chat` WebSocket 的字段、事件与语义约束以本文档为准。
- [`frontend-design.md`](./frontend-design.md)：前端架构、渲染约束、协议消费边界与本地联调说明。

## 2. 决策与复盘

- [`ADR_AND_REVIEW_FINDINGS.md`](./ADR_AND_REVIEW_FINDINGS.md)：稳定架构决策、关键 trade-off 与实现级复盘结论。

## 3. 规划与待办

- [`v1.3-route-review/OVERVIEW.md`](./v1.3-route-review/OVERVIEW.md)：当前活跃版本（draft）的定位、DoD、step 拆解与非目标；本版本 step 文档落在同目录。
- [`TODO.md`](./TODO.md)：跨模块待办、延后事项与工程债。

说明：

- 当前活跃版本为 v1.3-route-review（2026-10-26 立项，draft）。此前 2026-10 的 milestone 外增量（advisory 会遇级去重 ADR-016、智谱默认路由、武汉演示场景）直接收口到当前真值文档。v1.2-trajectory-prediction 已于 2026-07-12 收口，归档与 closure summary 见 [`history/v1.2-trajectory-prediction/README.md`](./history/v1.2-trajectory-prediction/README.md)。
- v1.1-graphrag milestone 已于 2026-07-01 收口；归档与 closure summary 见 [`history/v1.1-graphrag/README.md`](./history/v1.1-graphrag/README.md)。
- v1.0 milestone 已于 2026-05-07 收口；归档与 closure summary 见 [`history/v1.0/README.md`](./history/v1.0/README.md)。
- 未冻结的规划项继续收口到 `TODO.md`。

## 4. 历史归档

- [`history/`](./history/)：阶段性实现计划、步骤拆解、旧版本协议与历史设计草案。详见 [`history/README.md`](./history/README.md)。
- [`history/v1.2-trajectory-prediction/README.md`](./history/v1.2-trajectory-prediction/README.md)：v1.2 closure summary，归档学习型轨迹预测的离线管线、GRU/ONNX 推理 sidecar、`TrajectoryPredictionPort` 集成与回放验收。
- [`history/v1.1-graphrag/README.md`](./history/v1.1-graphrag/README.md)：v1.1-graphrag closure summary，归档历史案例 GraphRAG sidecar、Java adapter、advisory evidence 注入与非门槛评测 track。
- [`history/v1.0/README.md`](./history/v1.0/README.md)：v1.0 closure summary，归档 agent / hydrology / weather / visual / bugfix track。

约束：

- `history/` 只保留历史过程，不再承担当前实现真值。
- 规划文档只记录未落地或未收敛能力，不重复描述已实现事实。
- 若当前事实与历史步骤冲突，以"当前真值"文档为准。

## 4.5 演示材料

- [`demo/MAP_SYSTEM_DEMO_SCRIPT.md`](./demo/MAP_SYSTEM_DEMO_SCRIPT.md)：演示脚本。§0 为当前主演示（武汉长江段，仓库内模拟器）；§1 起为 v1.0 视频脚本（外部 AIS 模拟源，海图能力以 Jamaica Bay 为准）。

## 5. 维护规则

- 新增稳定能力时，优先更新当前真值文档，再决定是否补 ADR 或 TODO。
- 新增规划项时，优先写入 `TODO.md`，不将其混入 `ARCHITECTURE.md` 的稳定事实段落。
- 完成一个阶段性计划后，将步骤文档归入 `history/`，并在当前真值文档中只保留结果，不保留实施过程。
