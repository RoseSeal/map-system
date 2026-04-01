# 海图系统 (Map-System) — Architecture Overview v0.1

> 用途：项目架构概览 / AI 上下文输入 / 对外介绍
> 最后更新：2026-03-31
> 维护原则：仅记录稳定的架构事实、链路与关键决策

---

## 一、项目一句话

面向船舶态势感知与碰撞风险评估的实时系统，基于多源输入统一建模，包含 2.5D 海图前端、LLM 风险解读与语音交互能力。

## 二、系统定位

- 面向实时船舶态势监控与碰撞风险预警。
- 以多源输入统一汇聚为 `ShipStatus`，形成从消息接入、风险计算、可视化展示到 LLM 解读的完整链路。
- 兼顾演示验证与后续扩展。

## 三、技术栈

| 层 | 技术 |
| --- | --- |
| 消息接入 | MQTT |
| 后端核心 | Spring Boot (`map-service`) |
| 实时推送 | WebSocket |
| 风险计算 | 自研 CPA/TCPA 引擎 |
| LLM 接入 | Gemini / 智谱，使用 `@ConditionalOnProperty` 切换，`CompletableFuture` 超时降级 |
| 前端 | Vite + TypeScript + Tailwind，2.5D 海图，浏览器原生 `SpeechSynthesis` TTS |
| 语音识别 | `whisper.cpp`（ASR，已完成，Backend-orchestrated） |
| 模拟器 | Python，MQTT 推送，haversine 航迹推算，`--speed-scale` 加速 |
| 数据库副服务 | `listener-service`（暂挂起，离线分析用） |

## 四、系统模块地图

### 4.1 `map-service`

#### 接入层（Adapter）

| 模块 | 职责 |
| --- | --- |
| MQTT Listener | 订阅外部输入消息，触发处理链入口 |
| WebSocket Handler | 向前端推送风险消息，并转发用户与 LLM 的聊天消息 |

#### 领域层（Domain）

| 模块 | 职责 |
| --- | --- |
| `ShipStatus` | 内部可信船舶状态类，含 `confidence` 字段，用于解耦 AIS / CV 等数据源 |
| Mapper | 将 AIS / CV / radar 等外部消息转为 `ShipStatus`，承担 normalize 职责 |
| `CpaTcpaEngine` | 计算两船 CPA / TCPA，包含坐标投影修正 |
| `ShipDispatcher` | 按 `ShipRole` 路由到对应引擎，并调用 `RiskObjectAssembler` 聚合结果 |
| `RiskObjectAssembler` | 汇总风险数据，组装待推送与待 LLM 分析的 DTO |
| `LlmClient` | LLM 调用抽象接口 |
| `GeminiLlmClient` | Gemini 实现 |
| `ZhipuLlmClient` | 智谱实现 |
| `LlmRiskContext` | 投喂给 LLM 的风险上下文结构体 |

### 4.2 副服务

| 模块 | 职责 |
| --- | --- |
| `listener-service` | 外部输入消息入库，服务离线分析与大数据处理，当前暂挂起 |

## 五、核心消息链路

### 5.1 链路 A：风险评估主链路

```text
Python Simulator / 外部输入源
  │  MQTT payload
  ▼
MQTT Listener
  │  原始消息
  ▼
Mapper (normalize)
  │  ShipStatus（内部可信格式）
  ▼
ShipDispatcher
  │  按 ShipRole 分发
  ▼
CpaTcpaEngine
  │  CPA / TCPA 计算结果
  ▼
RiskObjectAssembler
  │  风险 DTO（含置信度、危险等级）
  ▼
WebSocket Handler ──→ 2.5D Frontend（全局风险态势面板）
  │
  └──→ LlmClient（异步，带超时降级）
         │  风险解读文本
         ▼
       WebSocket Handler ──→ 2.5D Frontend（LLM 风险播报 + TTS 语音）
```

### 5.2 链路 B：用户与 LLM 对话链路

```text
2.5D Frontend（文本输入 / 语音输入）
  │  WebSocket 消息 / 音频数据
  ▼
WebSocket Handler
  │  透传文本 / 编排语音转写
  ▼
whisper.cpp（语音输入时）
  │  transcript
  ▼
LlmClient（单轮无上下文对话）
  │  LLM 回复
  ▼
WebSocket Handler ──→ 2.5D Frontend（文字显示 + SpeechSynthesis TTS）
```

## 六、能力边界

- 已具备多源消息接入、风险计算、态势推送、LLM 解读、TTS 播报与 ASR 语音输入能力。
- LLM 对话链路当前以单轮问答为主，尚未建立稳定的多轮上下文管理。
- ASR 已通过 `whisper.cpp` 接入，当前采用后端统一编排的非流式方案。
- 动画指令、规则引擎、多智能体 / RAG 仍处于规划阶段。
- `listener-service` 当前不在主运行链路中。

## 七、功能完成状态

### P0

- [x] MQTT 接入与多源消息 normalize
- [x] CPA/TCPA 风险引擎
- [x] WebSocket 风险态势推送
- [x] 单轮 LLM 风险态势解读
- [x] Gemini / 智谱双实现与超时降级
- [x] 移除前端消息气泡功能，消除目标船模型渲染串扰
- [ ] 为后端追踪目标集合增加失联目标超时清理

### P1

- [x] 语音播报 TTS（`SpeechSynthesis`）
- [x] 语音输入 ASR（`whisper.cpp`，非流式，Backend-orchestrated）
- [ ] 增加前端亮色主题，形成 `light / dark` 可切换模式
- [ ] 向 LLM 注入当前风险上下文
- [ ] 将 LLM 解释从风险主流程解绑，形成独立异步流

### P2

- [x] 引入统一 `Event` 抽象（`type / source / payload`）
- [x] 为 WebSocket 消息增加版本号
- [x] 新增 `docs/EVENT_SCHEMA.md` 协议文档
- [ ] 建立多轮上下文管理

### P3

- [ ] JSON 指令驱动动画
- [ ] 显式实现 agent loop
- [ ] 本船安全领域
- [ ] 目标船航迹预测
- [ ] 法律法规 RAG

### P4

- [ ] 带唤醒词的纯语音交互
- [ ] `whisper.cpp` 升级至 `large-v3`

### 副线

- [ ] `listener-service` 离线分析链路恢复与衔接

## 八、近期路线图（Roadmap）

### P0 - 缺陷修复与运行稳定性

- 移除前端消息气泡功能。当前实现存在渲染串扰，新解释出现后会错误影响目标船模型显示；同类提示已可由目标追踪面板承载。
- 为后端追踪目标集合增加超时清理机制。目标船在持续一段时间未收到 `id = xxx` 的新信号后，应自动移出当前追踪集合。

### P1 - 演示前必须完成

- 增加前端亮色主题，与现有深色主题形成 `light / dark` 可切换模式。
- 向 LLM 注入当前风险上下文，使其能够回答“评估当前状况”等基于实时态势的问题。
- 将 LLM 解释从风险主流程中解绑。风险结果优先实时推送，LLM 解释改为独立异步流，降低主链路感知延迟；后续链路将逐步形成 `risk stream` 与 `llm stream` 分离的结构。

### P2 - 架构强化

- 显式引入统一 `Event` 抽象，采用 `type / source / payload` 结构，逐步承载 `risk`、`chat`、`speech` 等消息，并为后续 `exploration / text / speech` 细分事件保留扩展空间。
- 为 WebSocket 消息增加版本号，并新增 `docs/EVENT_SCHEMA.md` 作为专门协议文档。
- 建立多轮上下文管理，形成稳定的对话状态维护能力。

### P3 - 功能扩展

- 引入 JSON 指令驱动动画，用于前端联动与解释增强。
- 显式实现 agent loop，承接后续更复杂的任务编排。
- 增加本船安全领域与目标船航迹预测能力，强化风险评估与 LLM 输出依据。
- 接入法律法规 RAG，提升解释与建议的规则依据。

### P4 - 体验增强

- 实现带唤醒词的纯语音交互，减少对录音按钮点击的依赖。
- 将 `whisper.cpp` 模型升级至 `large-v3`，进一步评估识别准确率提升空间。

## 九、Architecture Decisions (ADR)

### ADR-001

内部统一可信实体使用 `ShipStatus`

Reason:
解耦 AIS / CV / radar 等异构数据源，统一领域层输入模型。

### ADR-002

LLM 能力通过 `LlmClient` 接口抽象

Reason:
隔离具体厂商实现，支持 Gemini / 智谱切换，并降低上层业务对模型供应商的耦合。

### ADR-003

LLM 服务提供商通过配置切换

Reason:
使用 `@ConditionalOnProperty` 控制实现装配，便于环境切换、实验对比与故障降级。

### ADR-004

语音输入链路采用 Backend-orchestrated 方案

Trade-off: 语音输入链路方案选择

#### 可选方案

##### 方案一：Frontend-first

```text
Browser MediaRecorder
  -> whisper-server
  -> 返回转写文本
  -> 前端确认 / 修正
  -> 现有 WebSocket CHAT
  -> map-service 调 LLM
  -> 前端文字显示 + SpeechSynthesis 播报
```

##### 方案二：Backend-orchestrated

```text
Browser MediaRecorder
  -> map-service
  -> whisper-server
  -> map-service 获取 transcript
  -> map-service 调 LLM
  -> map-service 推送给前端
  -> 前端文字显示 + SpeechSynthesis 播报
```

#### 评估维度

##### 1. 延迟

- 两种方案的主耗时都在音频采集、ASR 推理与 LLM 推理，链路多一跳通常不是主要瓶颈。
- 方案二相比方案一，多了一层 `map-service -> whisper-server` 中转，但在本机、局域网或同机容器部署下，这部分通常只是小头。
- 结论：延迟基本一致。方案二不会因为多一层编排而天然显著变慢。

##### 2. 架构清晰度

- 方案一由前端负责更多编排逻辑，包括调用 ASR、处理 transcript，再调用现有 CHAT 链路。
- 方案二由 `map-service` 统一承担 orchestration，ASR、LLM、结果推送均由后端统一编排。
- 对当前系统而言，`map-service` 已经是 risk、LLM、WebSocket 的中枢，语音链路继续收归后端，整体职责边界更一致。
- 结论：方案二更优，更符合现有系统的 backend-centered orchestration 风格。

##### 3. 流式拓展（Streaming ASR）

- 方案一更容易先做前端侧流式试验，前端可以先拿 partial transcript，确认后再发给后端，交互更灵活。
- 方案二也可以支持流式，但复杂度会明显上升，主要增加在：
- speech session 状态管理。
- chunk / frame 协议设计。
- partial / final transcript 语义管理。
- LLM 触发时机控制。
- WebSocket 消息 schema 扩展。
- 这些增加的主要是实现复杂度和状态复杂度，不主要是推理延迟。
- 结论：方案一的流式拓展更轻；方案二并非不能做，但会更复杂。相对当前非流式实现，方案二若扩展到稳定可用的流式版本，整体复杂度可视为约 1.5x 到 2x，复杂度主要集中在协议、状态管理和触发策略，而不是性能本身。

#### 最终选择

选择方案二：Backend-orchestrated。

##### 选择原因

- 与当前系统架构一致，前后端职责更清晰。
- `map-service` 统一编排 ASR + LLM，更利于后续日志、审计、容错与 Provider 替换。
- 已有 CHAT 协议预留了 `input` 字段，且 `InputType` 已包含 `speech`，复用现有协议是自然扩展，而非临时拼接。
- 当前阶段优先目标是先将真实链路干净跑通，而不是优先追求流式体验。

##### 当前判断

- P1 已完成方案二的非流式版本。
- 当前语音输入链路已接入 `whisper.cpp`，并由 `map-service` 统一完成 ASR、LLM 调用与结果推送。
- 若后续真实测试发现转录错误率较高、交互等待感明显，或需要边说边看 partial transcript，再评估是否引入流式 speech session。

#### 模型选择注意事项

##### 1. Whisper 的适用性

- `whisper.cpp` 适合作为当前阶段的本地 ASR backend，便于 Docker 化、本地部署和快速接入。官方 server 示例支持通过 Docker 运行 `whisper-server`，并支持 `--prompt` 初始提示等参数。
- 但 Whisper 本质上是通用型 multilingual ASR，不应直接假设其就是中文航运场景的最终最优解。OpenAI 对 Whisper 的定位也是 general-purpose speech recognition。

##### 2. 航运专业词汇风险

- 航运专业词汇如 `CPA`、`TCPA`、船名、航道名等，Whisper 可能发生转写偏差，例如把缩写识别为普通中文词或其他英文片段。
- 可优先尝试通过 `prompt / initial prompt` 向模型注入领域词表或示例短语，以降低术语误识别风险。`whisper.cpp` server / CLI 均支持 `prompt` 参数。

##### 3. 中文场景必须做 A/B Test

- 项目面向中国内河真实船员，中文口语、口音与行业词都可能显著影响 ASR 表现。
- 因此不能只押注 Whisper 一家，必须与中文系模型进行 A/B test，例如 FunASR / SenseVoice 路线，作为真实效果对照。

##### 4. 优先级判断

- 若真实测试中转录错误率较高，优先应提升模型能力、词表适配与音频质量，而不是第一时间将希望寄托在流式化上。
- 流式主要改善的是交互体验和反馈速度，不天然等于更高识别精度。

## 十、维护要求

- 新增模块或职责变更时，同步更新模块地图。
- 消息链路变更时，同步更新链路图。
- 新增关键设计取舍时，同步补充 ADR。
- 功能阶段变化时，同步更新完成状态与 TODO。