# LLM Roadmap

> 文档状态：active planning
> 最后更新：2026-04-03
> 关系说明：当前实现事实以 `docs/ARCHITECTURE.md` 和 `docs/EVENT_SCHEMA.md` 为准；本文档只描述 LLM 方向的规划、演进与未决问题。

---

## 1. 文档定位

本文档用于承接 LLM 相关的中长期设计，不再让历史版本文档同时承担“当前真值”和“未来规划”两种角色。

本文档分三类内容：

- `Adopted`：已经被当前架构吸收的原则
- `Planned`：明确方向但尚未稳定落地的能力
- `Open Questions`：已经识别但仍待收敛的问题

---

## 2. Adopted

以下设计原则已经被当前架构吸收，应视为现行共识：

- LLM 只承担解释、建议与语义增强，不承担底层 CPA/TCPA 等数值计算
- LLM 不是控制器，也不是最终执行器
- 风险主链路不能被 LLM 阻塞；风险快照优先，解释异步下发
- LLM 调用失败、超时或返回异常时，系统必须降级而不能拖垮主流程
- LLM 供应商通过 `LlmClient` 抽象隔离，当前支持 Gemini / 智谱切换
- 语音输入由后端统一编排，ASR 与 LLM 调用均由 `map-service` 收口

这些原则的当前实现与职责边界见：

- [ARCHITECTURE.md](/home/xin/workspace/map-system/docs/ARCHITECTURE.md)
- [EVENT_SCHEMA.md](/home/xin/workspace/map-system/docs/EVENT_SCHEMA.md)

---

## 3. Current Baseline

当前已经具备的 LLM 相关能力：

- 基于风险快照触发单轮风险解释
- 解释通过 risk SSE 通道中的独立 `EXPLANATION` 事件下发
- chat 通道支持文本问答与语音问答
- 语音输入支持 `direct` / `preview` 两种模式
- LLM 请求具备超时与错误降级能力
- `LlmClient` 接口已升级支持多角色消息序列（`chat(List<ChatMessage>)`），`GeminiLlmClient` / `ZhipuLlmClient` 已完成适配（Step 1 已完成）
- `llm/client/` 与 `llm/dto/` 包已完成收口，`LlmRiskContext` 等 LLM 专用 DTO 已迁入 `llm/dto/`（Step 1 已完成）

当前还没有稳定落地的能力：

- 注入完整当前风险上下文的对话问答
- 稳定多轮上下文管理
- 法规 RAG
- 结构化 advisory 输出
- JSON 指令驱动动画

---

## 4. Planned

### 4.1 P1：上下文增强问答

目标：

- 让 LLM 能回答“评估当前状况”“哪个目标最危险”这类依赖实时态势的问题

方向：

- 将当前风险快照、重点目标、风险等级与关键 CPA/TCPA 指标注入 `LlmRiskContext`
- 保持 chat 会话与 risk 解释事件分离，不把 `EXPLANATION` 重新并入 chat 流

### 4.2 P2：多轮上下文管理

目标：

- 建立稳定的会话状态维护能力

方向：

- 管理 `conversation_id` 级别的上下文窗口
- 控制上下文截断、去噪与过期策略
- 明确什么内容来自 chat、什么内容来自实时风险上下文

### 4.3 P3：结构化 Advisory 输出

目标：

- 从纯文本解释升级到“文本解释 + 结构化建议”

设计约束：

- 结构化 advisory schema 与实时事件协议分离
- advisory schema 不能复用 `EVENT_SCHEMA` 的版本号语义
- LLM 不直接生成可执行坐标路径
- 坐标、航迹、机动路径等几何结果必须由确定性模块生成或校验

建议输出方向：

- 场景语义标签
- 风险等级与置信提示
- 规则引用
- 动作语义建议
- 可视化联动指令

### 4.4 P4：法规与知识增强

目标：

- 为解释和建议提供更强的规则依据与可追溯性

方向：

- 接入法律法规 RAG
- 按风险场景检索规则条款与案例
- 让规则引用与自然语言解释分层输出

### 4.5 P5：Generative UI / 动画联动

目标：

- 让 LLM 输出可被前端消费的展示建议，而不越权到控制执行

设计约束：

- LLM 输出的是展示语义，不是直接执行命令
- 所有动画或高亮建议都必须能被消费层校验
- 多目标场景下需要冲突消解机制，避免同时输出互相打架的视觉建议

---

## 5. Open Questions

### 5.1 结构化 advisory schema 如何分层

待决定问题：

- advisory schema 是只在后端内部使用，还是也要暴露给前端
- advisory schema 是否嵌入 `EXPLANATION`，还是独立成新的事件类型

当前倾向：

- 不污染当前 `EXPLANATION` 最小文本事件
- 若要引入结构化 advisory，优先新增显式字段或新事件类型

### 5.2 多目标场景下的建议聚合

待决定问题：

- 每个 target 单独生成建议，再由后端汇总
- 还是让 LLM 看到全局态势后输出统一 maneuver advisory

当前倾向：

- 起步阶段优先“逐目标评估 + 后端 conflict resolution”

### 5.3 时序稳定性

待决定问题：

- 连续帧下建议是否会频繁抖动
- 什么条件下应复用上一轮结果
- 什么条件下必须强制重算

建议方向：

- 使用阈值触发
- 引入短时间缓存与相似输入复用
- 将解释频率与传感器更新频率解耦

### 5.4 ASR 路线

待决定问题：

- `whisper.cpp` 是否继续作为主 ASR 方案
- 是否需要引入中文模型对照测试
- 是否需要流式 partial transcript

当前倾向：

- 先做中文航运语境下的 A/B test，再决定是否升级模型或切换路线

---

## 6. 不再作为当前真值的旧提法

以下内容来自早期设计，保留历史价值，但不再视为当前事实：

- “LLM v1 已通过 WebSocket 接入现有输出链路”
- “单一 LLM 接口”作为当前实现描述
- 将实时事件协议与 LLM advisory schema 混称为同一套 schema
- 把动画、Generative UI、多智能体写成已确定的近期实现

这些内容若仍需引用，应明确标注为历史方案或候选方向。

---

## 7. 历史文档

历史设计文档已归档到：

- [llm-history/llm-module-v1.md](/home/xin/workspace/map-system/docs/llm-history/llm-module-v1.md)
- [llm-history/llm-module-v2.md](/home/xin/workspace/map-system/docs/llm-history/llm-module-v2.md)

归档文档用于保留原始思路，不再作为当前实现真值。
