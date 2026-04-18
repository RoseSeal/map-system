# Docs Index

> 文档状态：current
> 最后更新：2026-04-17
> 用途：为 `docs/` 提供入口索引，并明确“当前真值 / 规划 / 历史归档”的边界。

## 1. 当前真值

- [`ARCHITECTURE.md`](./ARCHITECTURE.md)：系统架构总览、稳定能力边界、模块职责与主链路。
- [`EVENT_SCHEMA.md`](./EVENT_SCHEMA.md)：实时协议真值源；`risk` SSE 与 `chat` WebSocket 的字段、事件与语义约束以本文档为准。
- [`frontend-design.md`](./frontend-design.md)：前端架构、渲染约束、协议消费边界与本地联调说明。

## 2. 决策与复盘

- [`ADR_AND_REVIEW_FINDINGS.md`](./ADR_AND_REVIEW_FINDINGS.md)：稳定架构决策、关键 trade-off 与实现级复盘结论。

## 3. 规划与待办

- [`TODO.md`](./TODO.md)：跨模块待办、延后事项与工程债。
- [`v1.0/README.md`](./v1.0/README.md)：`v1.0` active milestone 总览；定义 `agent` / `hydrology` / `weather` 三条并行 track 与阻塞关系。
- [`v1.0/SOURCEBOOK.md`](./v1.0/SOURCEBOOK.md)：`v1.0` 共用原始材料索引稿；汇总 agent / hydrology / weather 三条 track 的现有文档入口、代码入口与待补资料。

说明：

- `v1.0/` 是当前 active milestone 规划目录；其中 `agent` 为主线，`hydrology` / `weather` 为并行增强 track。
- 其他尚未冻结的规划项仍优先收口到 `TODO.md`。

## 4. 历史归档

- [`history/`](./history/)：阶段性实现计划、步骤拆解、旧版本协议与历史设计草案。

约束：

- `history/` 只保留历史过程，不再承担当前实现真值。
- 规划文档只记录未落地或未收敛能力，不重复描述已实现事实。
- 若当前事实与历史步骤冲突，以“当前真值”文档为准。

## 5. 维护规则

- 新增稳定能力时，优先更新当前真值文档，再决定是否补 ADR 或 TODO。
- 新增规划项时，优先写入 `TODO.md`，不将其混入 `ARCHITECTURE.md` 的稳定事实段落。
- 完成一个阶段性计划后，将步骤文档归入 `history/`，并在当前真值文档中只保留结果，不保留实施过程。
