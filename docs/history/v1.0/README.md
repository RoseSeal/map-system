# v1.0 Milestone — Closure Summary

> 文档状态：archived（v1.0 closure summary）
> 关闭日期：2026-05-07
> 用途：归档 v1.0 milestone 的目标、产出与收口结论；指引读者从这里跳转到 current truth。
> 非目标：本文与本目录下任何文件**都不再是 current truth**。当前实现真值以 active docs 为准。

---

## 1. v1.0 目标回顾

v1.0 是项目从"实时风险态势 + 单次 LLM 解释"演进到"有界 agent loop + 结构化 advisory + 多专题环境上下文"的实施 milestone。

里程碑下并行三条 track 与若干支持子 track：

| Track | Release impact | 收口形态 |
| --- | --- | --- |
| `agent`（主线） | 阻塞 v1.0 主版本 | 完成 |
| `hydrology`（并行） | 不阻塞 | 完成 |
| `weather`（并行） | 不阻塞 | 完成 |
| `visual`（演示视觉收敛） | 不阻塞 | 收敛标准达成 |
| `bugfix` | 不阻塞 | 完成 |

## 2. 已完成的 track 与主要系统增量

### 2.1 Agent Track（Step 0–5）

- 触发策略收敛 + `AgentSnapshot` 边界深拷贝快照模型（Step 0）
- Provider-neutral agent 调用契约，Gemini / 智谱共享 tool-call 抽象（Step 1）
- `AgentToolRegistry` + 第一类查询工具（Step 2）
- Advisory pipeline 与 `ADVISORY` SSE 事件（主线，Step 3）
- Chat agent path：feature flag、显式 `agent_mode` 入口、`AGENT_STEP` 下行事件、解决态生命周期、任务级模型路由与前端 provider 选择（Step 4 / 4A / 4B / 4C）
- COLREGS Part B 进程内规则图谱、`query_regulatory_context` 与 `evaluate_maneuver` 工具（Step 5）

### 2.2 Hydrology Track（Step 1–3）

- 2.5D 海图视觉升级、`OBSTRN` 出图、safety contour 交互（Step 1）
- `HydrologyContextService` 与 `environment_context.hydrology` 注入（Step 2）
- 风险引擎水文惩罚项 + agent `query_bathymetry` 工具（Step 3）

### 2.3 Weather Track（Step 1–4）

- `usv/Weather` MQTT 接入、`WeatherContextHolder` 与前端 fog overlay（Step 1）
- 区域化天气建模 + simulator 驱动 weather zones（Step 2）
- 风险引擎消费天气（能见度、海况、流场）（Step 3）
- LLM static context + agent `get_weather_context` 工具 + advisory 消费（Step 4）

### 2.4 Visual Subtrack

- 2026-04-18 视觉补丁的采纳/裁剪记录与后续接线参考。
- 风险核心图层（CPA/TCPA、本船安全领域、本船与目标船航迹）演示阶段视觉迭代收敛。

### 2.5 Bugfix

- `RISK_UPDATE` / `ENVIRONMENT_UPDATE` 拆分（决策沉淀至 ADR-009）。
- CPA/TCPA 曲线轨迹渲染修复。
- Gemini provider 代理配置修复。

### 2.6 协议层产出

v1.0 期间引入或修改的协议事件均已沉淀到 [`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md) §变更记录：

- `RISK_UPDATE.environment_context.weather` 字段与天气告警枚举（2026-04-18）
- `ADVISORY` 事件 + `ADVISORY_SCHEMA_FAILED` 错误码（2026-04-24）
- `CHAT.agent_mode` + `CAPABILITY` 握手 + `AGENT_STEP` 下行事件（2026-04-25）
- `SET_LLM_PROVIDER_SELECTION` / `LLM_PROVIDER_SELECTION` 运行时 provider 选择（2026-04-27）
- `ENVIRONMENT_UPDATE` 事件拆分 + `RISK_UPDATE.environment_state_version`（2026-04-28）

## 3. 决策沉淀至 ADR

下列 v1.0 期间形成的关键决策已收口至 [`../../ADR_AND_REVIEW_FINDINGS.md`](../../ADR_AND_REVIEW_FINDINGS.md)：

- ADR-007：法规与历史危险场景检索路线（GraphRAG，不走普通 RAG）
- ADR-008：对话重答非破坏式语义
- ADR-009：风险 SSE 与环境状态拆分；safety contour 采用 HTTP command + SSE authoritative state
- ADR-010：AgentSnapshot 在 agent loop 边界以深拷贝构造，不全面改造上游 DTO 为不可变结构
- ADR-011：advisory 走独立 `ADVISORY` SSE 事件，不复用 `EXPLANATION`
- ADR-012：COLREGS Part B 用进程内图，外部图数据库与历史案例图推迟到 v1.1+

ADR 之外的实施细节（Step plan 正文、bugfix 复盘）仅作为历史过程文档归档于本目录，不再回写当前真值。

## 4. 这里只是历史，不是当前真值

本目录下任何文件**都不承担 current truth**。如果当前实现与历史 step 描述发生冲突，**以下列 active 文档为准**：

- 架构总览：[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md)
- 协议真值：[`../../EVENT_SCHEMA.md`](../../EVENT_SCHEMA.md)
- 前端架构：[`../../frontend-design.md`](../../frontend-design.md)
- 决策与复盘：[`../../ADR_AND_REVIEW_FINDINGS.md`](../../ADR_AND_REVIEW_FINDINGS.md)
- 任务到文件导航：[`../../../CURRENT_SYSTEM_OVERVIEW.md`](../../../CURRENT_SYSTEM_OVERVIEW.md)
- 项目状态：[`../../../PROJECT_STATUS.md`](../../../PROJECT_STATUS.md)

历史步骤文档可能引用已重构或重命名的字段、模块路径与协议形态；引用前请在当前代码或当前真值文档中复核。

## 5. 目录索引（仅供历史追溯）

- [`agent/`](./agent/)：agent track 总 plan 与 Step 0–5（含 4A/4B/4C）。
- [`hydrology/`](./hydrology/)：hydrology track 总 plan 与 Step 1–3。
- [`weather/`](./weather/)：weather track 总 plan 与 Step 1–4。
- [`visual/`](./visual/)：演示阶段视觉收敛 plan。
- [`VISUAL_UPGRADE_REFERENCE.md`](./VISUAL_UPGRADE_REFERENCE.md)：2026-04-18 视觉补丁采纳记录。
- [`bugfix/`](./bugfix/)：v1.0 期间的三份 bugfix 实施记录。
- [`SOURCEBOOK.md`](./SOURCEBOOK.md)：v1.0 启动期的研究笔记与外部资料待补清单（仅作思考过程参考）。
