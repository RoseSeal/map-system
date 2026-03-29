# 海图系统 (Map-System) — Architecture Overview v0.1

> 用途：项目架构概览 / AI 上下文输入 / 对外介绍
> 最后更新：2026-03-28
> 维护原则：仅记录稳定的架构事实、链路与关键决策

---

## 一、项目一句话

基于 AIS 数据的船舶碰撞风险实时评估系统，包含 2.5D 海图前端、LLM 风险解读与语音播报能力。

## 二、系统定位

- 面向实时船舶态势监控与碰撞风险预警。
- 以 AIS 数据为核心输入，形成从消息接入、风险计算、可视化展示到 LLM 解读的完整链路。
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
| 语音识别 | `whisper.cpp`（ASR，规划中） |
| 模拟器 | Python，MQTT 推送，haversine 航迹推算，`--speed-scale` 加速 |
| 数据库副服务 | `listener-service`（暂挂起，离线分析用） |

## 四、系统模块地图

### 4.1 `map-service`

#### 接入层（Adapter）

| 模块 | 职责 |
| --- | --- |
| MQTT Listener | 订阅 AIS 原始消息，触发处理链入口 |
| WebSocket Handler | 向前端推送风险消息，并转发用户与 LLM 的聊天消息 |

#### 领域层（Domain）

| 模块 | 职责 |
| --- | --- |
| `ShipStatus` | 内部可信船舶状态类，含 `confidence` 字段，用于解耦 AIS / CV 等数据源 |
| Mapper | AIS 原始格式转 `ShipStatus`，承担 normalize 职责 |
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
| `listener-service` | AIS 消息入库，服务离线分析与大数据处理，当前暂挂起 |

## 五、核心消息链路

### 5.1 链路 A：风险评估主链路

```text
Python Simulator
  │  MQTT (AIS JSON payload)
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
2.5D Frontend（用户输入 / whisper.cpp ASR）
  │  WebSocket 消息
  ▼
WebSocket Handler
  │  透传用户文本
  ▼
LlmClient（单轮无上下文对话）
  │  LLM 回复
  ▼
WebSocket Handler ──→ 2.5D Frontend（文字显示 + SpeechSynthesis TTS）
```

## 六、能力边界

- 已具备 AIS 接入、风险计算、态势推送、LLM 解读与 TTS 播报能力。
- LLM 对话链路当前以单轮问答为主，尚未建立稳定的多轮上下文管理。
- ASR、动画指令、规则引擎、多智能体 / RAG 仍处于规划阶段。
- `listener-service` 当前不在主运行链路中。

## 七、功能完成状态

| 阶段 | 功能 | 状态 |
| --- | --- | --- |
| P0 | MQTT 接入 + AIS normalize | 已完成 |
| P0 | CPA/TCPA 风险引擎 | 已完成 |
| P0 | WebSocket 推送风险态势 | 已完成 |
| P0 | LLM 风险态势解读（单轮） | 已完成 |
| P0 | Gemini / 智谱双实现 + 超时降级 | 已完成 |
| P1 | 语音播报 TTS（`SpeechSynthesis`） | 已完成 |
| P1 | 语音输入 ASR（`whisper.cpp`） | 方向已确认，未实现 |
| P2 | JSON 动画指令（前端联动） | 规划中 |
| P3 | 规则引擎 | 规划中 |
| P4 | 多智能体 / RAG | 规划中 |
| 副线 | `listener-service` 离线分析 | 暂挂起 |

## 八、待优化项（TODO）

- 建立 LLM 多轮上下文能力，降低单轮问答限制。
- 梳理 `listener-service` 与实时主链路的衔接方式，明确在线与离线分析边界。
- 持续同步模块职责、消息链路与架构决策，避免文档滞后。

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

- P1 优先实现方案二的非流式版本。
- 若后续真实测试发现转录错误率较高、交互等待感明显，或需要边说边看 partial transcript，再评估是否为方案二引入流式 speech session。
- 当前状态：方向已确认，尚未实现。

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
