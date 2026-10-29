# Map-System 待办与延后事项清单 (TODO)

> 文档状态：active（roadmap 性质）
> 最后更新：2026-10-26
> 定位声明：项目自 2026-07-12（v1.2 收口）起进入 showcase 态，无 active milestone；本清单整体转为 **roadmap**——记录已识别的升级方向与工程债，**无排期、无 owner、无交付承诺**。重启活跃开发时按 §0 规则把条目挂入新 milestone。2026-10-26 起 v1.3-route-review 进入规划（见 [`v1.3-route-review/OVERVIEW.md`](./v1.3-route-review/OVERVIEW.md)）。
> 摘要：仅记录“未实现且未挂到当前有效实施链”的事项；凡已进入 `docs/v*` 总 plan、step 文档或已命名 milestone 的工作，均不得在此重复登记。
> 移出记录：原 §2「CT (Constant Turn) 航迹预测模型」已于 2026-07-01 被 v1.2-trajectory-prediction 承接（以学习型模型实现并于 2026-07-12 收口，见 `docs/history/v1.2-trajectory-prediction/`），按 §0 移出规则移除。原 §3「`evidence_items` 从 string[] 升级为结构化对象」与 §4「chat agent 工具调用参数与返回结果审计视图」已于 2026-10-26 被 v1.3-route-review 承接（分别见其 step-3、step-2，[`v1.3-route-review/OVERVIEW.md`](./v1.3-route-review/OVERVIEW.md)），按 §0 移出规则移除。

## 0. 使用规则

- **保留标准**：事项必须同时满足“尚未实现”与“当前没有明确 owner / step / milestone 挂载”两个条件，才保留在本清单。
- **移出标准**：一旦事项进入 `docs/v*` 总 plan、step 文档或其它有效实施链，应从本清单移除，并以对应计划文档为准。
- **回收标准**：若 `v1.0` 参考文档或 step 文档中出现“post-v1.0 / v1.1 / 后续可做”但未给出明确后续 owner 的事项，应回收到本清单，而不是继续滞留在 `v1.0` 目录内。

## 1. 架构与工程基础设施

- **配置化治理**：提取并统一配置协议层扣减分值（`DEDUCTION_*`）、阈值等硬编码逻辑。
- **离线分析链路**：恢复并衔接 `listener-service` 的离线分析链路。
- **引擎管线上下文聚合**：将零散的 Engine 输入（Map 参数）重构为统一类型化的上下文对象（如 `OwnShipContext`、`TargetShipContext`），支持未来舰队等扩展场景。
- **SSE 增量协议与版本校准机制**：评估将 SSE 从全量快照改为 delta 协议，并加入定时全量校准或版本校验机制。
- **会话隔离与鉴权**：在多客户端或多用户场景下，补充 WebSocket 会话归属校验与访问控制。

## 2. 后端与引擎 (Risk Engine)

- **安全领域模型增强**：将当前仅按航速缩放的 `ShipDomainEngine` 升级为多因子安全领域模型，补充转向/ROT、会遇类型、环境因素（如能见度/风流）以及左右半弦差异化修正，避免本船安全域长期退化为仅随 SOG 线性缩放的固定前长后短轮廓。
- **D-S 证据理论风险融合**：在累积足够数据后，将目前的规则化加权评分风险评估模型升级为 D-S 证据理论（Dempster-Shafer）模型，更科学地处理多因子冲突。
- **航道约束建模**：引入电子航道图（S-57/S-101）数据进行航道约束验证，剔除上岸的预测轨迹，并引入特殊水域风险修正。v1.3 step-2 计划由历史 AIS 构建武汉段航道网格（`waterway-pipeline/`，演示网格位于 `map-service` 资源目录），可作为本项在武汉段的替代数据基础（[`v1.3-route-review/step-2.md`](./v1.3-route-review/step-2.md) §8）。
- **多源数据融合 (Fusion 层)**：为未来接入雷达或计算机视觉等第二输入源做准备，实现多源目标关联与可信度融合 (qualityScore & fusionConfidence)。
- **基于安全领域的避碰责任判定**：在 owship-centric 域检测基础上，引入目标船尺寸/船型信息，支持基于避碰规则（COLREGS）的让路船/直航船责任判定。
- **相遇场景计算逻辑优化**：在后端增加局面判定过滤逻辑。不应始终全量调用相遇场景（交叉、对遇、追越）计算，而应仅在目标存在潜在风险（如进入警戒圈或 CPA 触发阈值）时才计算并下发场景标签；后端完成后，需同步移除前端 `TargetsPanel` 中的临时过滤逻辑。
- **AIS 重复报文处理策略优化**：细化针对同时间戳重复报文的过滤或字段修正规则，防止 Ghost Ship 长期滞留。
- **learned 预测 adapter 的 per-target 状态淘汰**：`LearningTrajectoryPredictionAdapter` 的结果缓存与 raw buffer 按目标 ID 累积，目标从 `ShipStateStore` 清理移除后不随之淘汰；demo 规模无实际影响，多目标长时间运行会缓慢泄漏。升级方向：订阅目标清理事件（对齐 `ShipTrajectoryStore.remove` 的调用点）或加 TTL 淘汰。触发条件：长跑部署或目标数量大的场景。（来源：v1.2 step-2 验收遗留，2026-07-12 closure 冻结）
- **轨迹预测特征引入地图/水文先验**：当前 GRU 预测器仅使用运动学特征（相对末点坐标、dt、SOG、COG），系出于泛化性与数据可得性考虑（见 `docs/history/v1.2-trajectory-prediction/`）。但内河航道形态高度固定，引入航道几何先验（如 S-57 航道中心线构造"沿航道弧长 + 横向偏移"坐标系，或航道栅格 embedding）与水文特征（水位、流速），预计对弯道预测精度与下游漏报有明显提升。与本节"航道约束建模"互补：本项作用于**模型输入特征**，后者作用于**预测输出的合法性过滤**。触发条件：需要进一步压降弯道 FDE/漏报，或 S-57 数据接入落地后。（来源：2026-07 竞赛答辩洞察）
- **失常航标提醒**：v1.3 step-1 的航标状态推演会把漂失、熄灭等失常航标标记为 `MALFUNCTION`，但它们不参与航线命中，也不单独提醒。原因：原文写明“恢复后不再另行通告”，失常状态何时结束无法确定，“注意识别”类提醒的语义与失效条件需要单独设计。触发条件：通告覆盖区间内出现与计划航线相关、且能匹配到坐标的失常航标。（来源：[`v1.3-route-review/step-1.md`](./v1.3-route-review/step-1.md) §7）
- **OVERTAKING 追越场景相对速度收敛检查**：`EncounterClassifier` 当前仅依据几何关系（目标在本船船尾扇区且航向差小）判定追越，不验证目标是否真的在相对收近；目标实际速度低于本船时"追越"判定可能不成立，导致 Rule 13 责任表述过度确定。升级方向为在分类器层引入相对速度收敛条件校验，并同步更新 `QueryRegulatoryContextTool` 的 resolver 决策矩阵。触发条件：演示阶段或规则推理质量评审发现误判。（来源：agent/step5.md §6）

## 3. LLM 与 AI 智能体 (Agent)

- **上下文质量优化**：继续优化 chat 对当前风险上下文的注入质量、选中目标定向补充和消费边界治理，避免 chat 会话与 risk explanation 语义混杂。
- **对话记忆策略治理**：在现有 `ConversationMemory` 基础上继续收敛并发语义、历史压缩策略与上下文来源可解释性，而不是再次重做基础 memory 能力。
- **多目标场景建议聚合**：在生成操纵建议时，从”逐一评估目标”升级为”全局视角决策与冲突消解”。
- **时序稳定性优化**：触发层的重复生成已由 ADR-016 会遇级去重与失败退避解决；剩余方向为建议内容层面的稳定性——相邻 advisory 对同一会遇给出的动作方向或幅度不应无因翻转，可引入意图复用或“无实质变化不替换”判定。
- **Agent advisory 流式过程展示**：chat agent 工具步骤实时可见性已在 Step 4A 通过 `AGENT_STEP` WebSocket 事件实现。Advisory SSE 流式中间步骤（agent loop 执行期间向前端推送每轮工具状态）仍未实现；需独立协议设计，延后到后续 step 或 v1.1。
- **`evaluate_maneuver` 转向动力学（非瞬时机动评估）**：当前工具假设机动瞬时完成，不建模转向惯性与加速度上限；触发条件为演示阶段反映瞬时假设导致建议偏离实船能力；升级方向为引入最大转艏率与加速度约束，将操纵结果建模为时间函数而非瞬态跳变。
- **`evaluate_maneuver` 多目标批量与组合机动评估**：当前工具每次仅针对单目标评估单一机动动作，不支持对多个目标同时评估同一机动的综合安全效果，也不支持先减速后转向等组合机动场景；触发条件为单步评估无法覆盖典型复杂态势。
- **`query_regulatory_context` 能见度受限（Rule 19）完整推理**：当前接口已预留 `visibility_condition` 字段，`MemoryGraphAdapter` 在开阔能见度查询中会过滤掉 Rule 19；待天气模块完整集成后，weather context 应向工具调用层透传当前能见度状态，使 Rule 19 在受限能见度条件下正确进入命中集合。
- **`EvaluateManeuverWithWeatherTool.lookahead_min` 预测时段语义实现**：当前工具仅接受 `lookahead_min = 0` 或缺省，非零值返回 `INVALID_ARGUMENT`；预测时段语义（评估拟议机动在未来 N 分钟内的气象可行性）已预留参数但未实现。升级方向为结合轨迹预测能力实现有限时域内的气象可行性评估。触发条件：天气 track 后续 milestone 或轨迹预测精度达到评估基线。（来源：weather/step4.md §10 Deferred）

## 4. 前端协同与展示 (Frontend)

- **已发送语音请求取消 / 后端转录中断**：在音频已发送且进入 `transcribing` 后支持主动取消；需协议新增 `CANCEL`、后端维护可中断任务注册表，并定义迟到 transcript/reply 的丢弃规则。
- **真正取消 LLM 回复**：在消息已发送且 LLM 尚未返回时支持主动取消；前端需提供取消入口与状态回退，协议与前后端需联动支持真正中断而非仅本地忽略结果。
- **编辑任意历史消息**：在当前“仅支持编辑最后一条 user 消息”的基础上，支持指定历史轮次重答，并明确后续消息的保留、截断或重建规则。
- **海图主渲染链路收敛到 composite tile**：当前水文 Step 1 明确保留单图层 vector source 方案；若后续需要统一主渲染链路，应单独规划并完成前后端收敛。
- **海图专题补全（`FAIRWY` / `RESARE` / `DEPCNT` 深值标签）**：当前水文 track 仅完成 `OBSTRN` 与 `DEPARE` 视觉主线，这些附加专题仍未挂入现有 Step 1–3。
- **`ownShipRole` 在前端 advisory / explanation UI 展示**：`QueryRegulatoryContextTool` 已在后端 tool 层输出 `ownShipRole`（`GIVE_WAY` / `STAND_ON` / `BOTH`），但前端 advisory card 与 explanation 卡片尚未消费该字段；展示方式（徽章、说明文字或图标）由后续 visual track 决定，当前无明确 step owner。（来源：agent/step5.md §4 Out of Scope）

## 5. 天气与环境专题

- **真实气象源接入**：在 simulator `usv/Weather` 之外，引入可替换的 live weather ingest adapter，并明确授权、更新频率、容错与 schema 兼容边界。
- **气象历史回放 / 导出 / 多源融合**：为 weather 能力补充历史保留、回放、导出与多源合并能力；目前仅停留在 post-`v1.0` 设想，尚无明确 step owner。

## 6. 语音交互体验 (Voice Interaction)

- **带唤醒词的纯语音交互 (P4)**：实现免点击录音按钮的全过程唤醒及交互。
- **ASR 模型评测与升级 (P4)**：使用中文航运场景数据集针对现有 `whisper.cpp` 与其他中文系模型（如 FunASR / SenseVoice）进行 A/B 测试；评估是否升级至 `large-v3`；按需评估流式 (Streaming) 语音转写。
- **底层 Timeout 治理**：明确为 LLM SDK 层面配置 connect/read timeout 控制（如 Gemini/Zhipu HTTP Client 可中断超时支持），增强可靠性。
