# CURRENT_SYSTEM_OVERVIEW

> Last updated: 2026-04-17
> Purpose: A practical, implementation-first guide for quickly understanding and locating the current system.
> Scope: Only current effective code and docs, with explicit separation from archived planning history.

## 1. How To Use This File

1. Use this file as the first stop when joining the project or debugging cross-module behavior.
2. Use links in each section to jump directly to source code, protocol specs, and operational docs.
3. Treat this file as an index and orientation guide, not a replacement for module-level design docs.

## 2. System Snapshot (Current Reality)

### 2.1 Product Goal

Real-time maritime situational awareness and collision-risk assessment with:
- AIS tracking
- CPA/TCPA and enhanced risk scoring
- SSE risk streaming
- WebSocket chat and voice interaction
- LLM-based risk explanation

Primary architecture and boundary truth:
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md)

### 2.2 Current Milestone Status

Project status tracker:
- [PROJECT_STATUS.md](PROJECT_STATUS.md)

At last update in status file:
- Latest completed milestone: v0.9 frontend enhancement
- Active milestone: v1.0 implementation milestone (`agent` mainline, `hydrology` / `weather` parallel tracks)

### 2.3 Effective Artifact Counts (Code + Docs)

Measured from the current workspace snapshot, excluding dependency directories such as `frontend/node_modules` and local agent-skill folders:
- `map-service` main Java files: 131
- `map-service` test Java files: 39
- `listener-service` main Java files: 8
- `listener-service` test Java files: 1
- Frontend source files (`ts`, `tsx`, `css`): 52
- Frontend test files: 8
- Project Markdown docs total: 55
- Current root docs under `docs/` (non-history): 6
- History docs: 31
- Simulator top-level scripts: 7
- Repo utility scripts: 2

### 2.4 Top-Level Repository Structure

- [backend](backend)
- [frontend](frontend)
- [simulator](simulator)
- [docs](docs)
- [runtime](runtime)
- [scripts](scripts)
- [compose.yaml](compose.yaml)

## 3. Runtime Architecture (As Implemented)

### 3.1 Main Runtime Data Flows

Risk flow:
1. MQTT input arrives
2. AIS payload mapped into internal `ShipStatus`
3. Dispatcher runs tracking + engines + assembly
4. Risk frame published to SSE `/api/v2/risk`
5. Explanation generated asynchronously and published as SSE `EXPLANATION`

Chat and voice flow:
1. Frontend sends `CHAT` or `SPEECH` over WebSocket `/api/v2/chat`
2. For speech, backend calls Whisper service for transcript
3. Backend builds context + conversation memory + LLM request; when `selected_target_ids` are present it injects matched target detail and latest valid explanation text
4. When `edit_last_user_message=true`, backend replaces the last completed `USER / ASSISTANT` turn only after new reply generation succeeds
5. Backend returns `CHAT_REPLY` and optional `SPEECH_TRANSCRIPT`

### 3.2 Protocol Split (v2)

Defined and implemented in:
- [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md)

Connections:
- Risk: SSE [backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java)
- Chat: WebSocket [backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java)

Protocol path constants:
- [backend/map-service/src/main/java/com/whut/map/map_service/shared/transport/protocol/ProtocolPaths.java](backend/map-service/src/main/java/com/whut/map/map_service/shared/transport/protocol/ProtocolPaths.java)

## 4. Module Map By Top-Level System

### 4.1 Backend Services

- Core service (active): [backend/map-service](backend/map-service)
- Ingestion persistence service (standby): [backend/listener-service](backend/listener-service)

### 4.2 Frontend App

- Main web client: [frontend](frontend)

### 4.3 Simulation and Tooling

- AIS and scenario publishers: [simulator](simulator)
- Repo checks: [scripts](scripts)

### 4.4 Docs Layers

- Current truth docs: [docs](docs)
- Historical process docs: [docs/history](docs/history)

## 5. Deep Breakdown: map-service (Core Backend)

Service root:
- [backend/map-service](backend/map-service)

Main entry:
- [backend/map-service/src/main/java/com/whut/map/map_service/MapServiceApplication.java](backend/map-service/src/main/java/com/whut/map/map_service/MapServiceApplication.java)

Build and runtime stack:
- Java 21
- Spring Boot 3.5.11
- Maven wrapper
- Paho MQTT, Spring WebSocket, SSE, JPA/PostgreSQL/PostGIS, Gemini SDK, Zhipu SDK
- Dependency file: [backend/map-service/pom.xml](backend/map-service/pom.xml)

### 5.1 Package Distribution (Main Java)

By top-level package under `com.whut.map.map_service`:
- `llm`: 52 files
- `risk`: 42 files
- `shared`: 15 files
- `source`: 7 files
- `tracking`: 4 files
- `chart`: 2 files

### 5.2 Source Ingest Module (`source`)

Role: ingest MQTT AIS messages and normalize into internal model.

Key areas:
- MQTT bootstrap and subscriber setup:
  - [backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config/MqttConfig.java](backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config/MqttConfig.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config/MqttProperties.java](backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config/MqttProperties.java)
- AIS behavior config:
  - [backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config/AisProperties.java](backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config/AisProperties.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config/AisQualityProperties.java](backend/map-service/src/main/java/com/whut/map/map_service/source/ais/config/AisQualityProperties.java)
- Message listener and DTO mapping:
  - [backend/map-service/src/main/java/com/whut/map/map_service/source/ais/mqtt/AisMessageListener.java](backend/map-service/src/main/java/com/whut/map/map_service/source/ais/mqtt/AisMessageListener.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/source/ais/mqtt/AisMessageMapper.java](backend/map-service/src/main/java/com/whut/map/map_service/source/ais/mqtt/AisMessageMapper.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/source/ais/mqtt/MqttAisDto.java](backend/map-service/src/main/java/com/whut/map/map_service/source/ais/mqtt/MqttAisDto.java)

Behavior notes:
- Mapper computes confidence deductions for missing heading, invalid speed/position, and missing timestamp.
- Own ship MMSI is normalized to internal id `ownShip`.

### 5.3 Tracking Module (`tracking`)

Role: maintain in-memory vessel state and trajectory history.

Key files:
- State store and cleanup:
  - [backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/ShipStateStore.java](backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/ShipStateStore.java)
- Trajectory history:
  - [backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/ShipTrajectoryStore.java](backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/ShipTrajectoryStore.java)
- Derived cache:
  - [backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/DerivedTargetStateStore.java](backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/DerivedTargetStateStore.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/TargetDerivedSnapshot.java](backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/TargetDerivedSnapshot.java)

Behavior notes:
- `ShipStateStore` keeps latest state per vessel id.
- Cleanup removes stale non-ownship targets and triggers snapshot refresh.

### 5.4 Risk Module (`risk`)

Role: execute risk pipeline, run engines, build outgoing risk payloads, and publish SSE events.

#### 5.4.1 Pipeline and Coordination

- Main dispatcher:
  - [backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java)
- Context and snapshot records:
  - [backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatchContext.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatchContext.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/RiskDispatchSnapshot.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/RiskDispatchSnapshot.java)

Behavior notes:
- Dispatcher supports full and incremental target derivation paths.
- After risk frame build, it publishes and emits `RiskAssessmentCompletedEvent` for LLM context refresh.

#### 5.4.2 Risk Engines

Collision CPA/TCPA:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/collision/CpaTcpaEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/collision/CpaTcpaEngine.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/collision/CpaTcpaBatchCalculator.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/collision/CpaTcpaBatchCalculator.java)

Encounter classification:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/encounter/EncounterClassifier.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/encounter/EncounterClassifier.java)

Safety domain:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/safety/ShipDomainEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/safety/ShipDomainEngine.java)

Trajectory prediction:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/trajectoryprediction/CvPredictionEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/trajectoryprediction/CvPredictionEngine.java)

Quality check:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/ShipKinematicQualityChecker.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/ShipKinematicQualityChecker.java)

Risk scoring and level:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/DomainPenetrationCalculator.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/DomainPenetrationCalculator.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/PredictedCpaCalculator.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/PredictedCpaCalculator.java)

#### 5.4.3 Assembly and Transport

Risk object assembly:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/RiskObjectAssembler.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/RiskObjectAssembler.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject](backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/assembler/riskobject)

SSE endpoint and publisher:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/RiskStreamPublisher.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/RiskStreamPublisher.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/SseEmitterRegistry.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/SseEmitterRegistry.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/SseEventFactory.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/SseEventFactory.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/scheduling/SseKeepaliveScheduler.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/scheduling/SseKeepaliveScheduler.java)

Config classes:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/config](backend/map-service/src/main/java/com/whut/map/map_service/risk/config)

### 5.5 LLM Module (`llm`)

Role: chat, speech transcript orchestration, risk explanation generation, memory, and context formatting.

#### 5.5.1 LLM Configuration and Provider Wiring

- Properties and validation:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmConfigurationValidator.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmConfigurationValidator.java)
- Executor:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmExecutorConfig.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmExecutorConfig.java)
- Provider-specific beans:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/config/GeminiConfig.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/config/GeminiConfig.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/config/ZhipuConfig.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/config/ZhipuConfig.java)
- Whisper:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/config/WhisperProperties.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/config/WhisperProperties.java)

#### 5.5.2 LLM Clients

- Abstraction interface:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClient.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClient.java)
- Gemini client:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/client/GeminiLlmClient.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/client/GeminiLlmClient.java)
- Zhipu client:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/client/ZhipuLlmClient.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/client/ZhipuLlmClient.java)
- Whisper client:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/client/WhisperClientImpl.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/client/WhisperClientImpl.java)

#### 5.5.3 Prompt and Context

- Prompt loading:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/prompt/PromptTemplateService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/prompt/PromptTemplateService.java)
- Prompt files:
  - [backend/map-service/src/main/resources/prompts/system-chat.txt](backend/map-service/src/main/resources/prompts/system-chat.txt)
  - [backend/map-service/src/main/resources/prompts/system-risk-explanation.txt](backend/map-service/src/main/resources/prompts/system-risk-explanation.txt)
- Context holder and formatter:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextHolder.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextHolder.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextFormatter.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextFormatter.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/context/ExplanationCache.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/context/ExplanationCache.java)

#### 5.5.4 Conversation Memory

- Multi-turn memory and conversation permit locking:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/memory/ConversationMemory.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/memory/ConversationMemory.java)

Current semantics:
- Stores paired `USER / ASSISTANT` history with TTL eviction and max-turn trimming
- Supports non-destructive replacement of the last completed turn for `edit_last_user_message=true`

#### 5.5.5 Services

- Chat service:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java)
- Voice service:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/service/VoiceChatService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/service/VoiceChatService.java)
- Explanation service:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmExplanationService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmExplanationService.java)
- Trigger policy:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmTriggerService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmTriggerService.java)

#### 5.5.6 Transport and Event Listener

- Chat WebSocket protocol handling:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java)
- WS configuration:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/config/ChatWebSocketConfig.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/config/ChatWebSocketConfig.java)
- Risk completion listener for context update + async explanation fan-out:
  - [backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java)

Current listener behavior:
- Refreshes risk context snapshot after each risk assessment completion
- Gates late explanations through `ExplanationCache` so only currently tracked non-`SAFE` targets continue to publish and remain injectable into chat context

### 5.6 Chart Module (`chart`)

Role: S-57 map tile and metadata endpoints for frontend map rendering.

- API controller:
  - [backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java](backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java)
- Repository:
  - [backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java](backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java)

Exposed endpoint family:
- `/api/s57/tiles/{z}/{x}/{y}.pbf`
- `/api/s57/layers`
- `/api/s57/safety-contour`
- `/api/s57/style.json`

### 5.7 Shared Module (`shared`)

Role: domain primitives, DTOs, transport constants, and utility math/geo helpers.

Key files:
- Domain model:
  - [backend/map-service/src/main/java/com/whut/map/map_service/shared/domain/ShipStatus.java](backend/map-service/src/main/java/com/whut/map/map_service/shared/domain/ShipStatus.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/shared/domain/RiskLevel.java](backend/map-service/src/main/java/com/whut/map/map_service/shared/domain/RiskLevel.java)
- DTOs:
  - [backend/map-service/src/main/java/com/whut/map/map_service/shared/dto/RiskObjectDto.java](backend/map-service/src/main/java/com/whut/map/map_service/shared/dto/RiskObjectDto.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/shared/dto/sse](backend/map-service/src/main/java/com/whut/map/map_service/shared/dto/sse)
- Protocol constants:
  - [backend/map-service/src/main/java/com/whut/map/map_service/shared/transport/protocol](backend/map-service/src/main/java/com/whut/map/map_service/shared/transport/protocol)
- Utility:
  - [backend/map-service/src/main/java/com/whut/map/map_service/shared/util/GeoUtils.java](backend/map-service/src/main/java/com/whut/map/map_service/shared/util/GeoUtils.java)
  - [backend/map-service/src/main/java/com/whut/map/map_service/shared/util/MathUtils.java](backend/map-service/src/main/java/com/whut/map/map_service/shared/util/MathUtils.java)

## 6. Deep Breakdown: listener-service (Standby Persistence Service)

Service root:
- [backend/listener-service](backend/listener-service)

Main entry:
- [backend/listener-service/src/main/java/com/whut/map/listener/ListenerApplication.java](backend/listener-service/src/main/java/com/whut/map/listener/ListenerApplication.java)

Build stack:
- Java 21
- Spring Boot 3.5.6
- Spring Integration MQTT + JPA/PostgreSQL/PostGIS
- Dependency file: [backend/listener-service/pom.xml](backend/listener-service/pom.xml)

Module split:
- MQTT configuration:
  - [backend/listener-service/src/main/java/com/whut/map/listener/config/MqttConfig.java](backend/listener-service/src/main/java/com/whut/map/listener/config/MqttConfig.java)
  - [backend/listener-service/src/main/java/com/whut/map/listener/config/MqttProperties.java](backend/listener-service/src/main/java/com/whut/map/listener/config/MqttProperties.java)
- MQTT message handling:
  - [backend/listener-service/src/main/java/com/whut/map/listener/service/mqtt/MqttMessageHandler.java](backend/listener-service/src/main/java/com/whut/map/listener/service/mqtt/MqttMessageHandler.java)
- Queue and batch write:
  - [backend/listener-service/src/main/java/com/whut/map/listener/service/queue/MessageQueue.java](backend/listener-service/src/main/java/com/whut/map/listener/service/queue/MessageQueue.java)
  - [backend/listener-service/src/main/java/com/whut/map/listener/service/queue/BatchDatabaseWriter.java](backend/listener-service/src/main/java/com/whut/map/listener/service/queue/BatchDatabaseWriter.java)
- Entity/repository:
  - [backend/listener-service/src/main/java/com/whut/map/listener/entity/AisMessage.java](backend/listener-service/src/main/java/com/whut/map/listener/entity/AisMessage.java)
  - [backend/listener-service/src/main/java/com/whut/map/listener/repository/AisMessageRepository.java](backend/listener-service/src/main/java/com/whut/map/listener/repository/AisMessageRepository.java)

Operational note:
- Architecture docs currently describe this service as standby and not on the primary runtime path.

## 7. Deep Breakdown: frontend (Web Client)

Frontend root:
- [frontend](frontend)

Main app files:
- [frontend/src/main.tsx](frontend/src/main.tsx)
- [frontend/src/App.tsx](frontend/src/App.tsx)

Tech stack summary (from current package config):
- React 18
- Vite 5
- TypeScript
- Zustand
- MapLibre GL
- Deck.gl
- Tailwind CSS
- Vitest + Testing Library

Dependency and scripts:
- [frontend/package.json](frontend/package.json)

### 7.1 Frontend Module Distribution

Top-level source groups under `frontend/src`:
- `components` (13 files)
- `services` (9 files)
- `store` (6 files)
- `test` (4 files)
- `utils` (4 files)
- `hooks` (3 files)
- `config` (3 files)
- `types` (3 files)

### 7.2 Components

Map rendering:
- [frontend/src/components/Map/MapContainer.tsx](frontend/src/components/Map/MapContainer.tsx)

Dashboard and AI center:
- [frontend/src/components/Dashboard/StatusPanel.tsx](frontend/src/components/Dashboard/StatusPanel.tsx)
- [frontend/src/components/Dashboard/TargetsPanel.tsx](frontend/src/components/Dashboard/TargetsPanel.tsx)
- [frontend/src/components/Dashboard/RiskExplanationPanel.tsx](frontend/src/components/Dashboard/RiskExplanationPanel.tsx)
- [frontend/src/components/Dashboard/ChatMessageList.tsx](frontend/src/components/Dashboard/ChatMessageList.tsx)
- [frontend/src/components/Dashboard/ChatComposer.tsx](frontend/src/components/Dashboard/ChatComposer.tsx)

Current workspace shell:
- Left-side monitoring cluster is composed in [frontend/src/App.tsx](frontend/src/App.tsx) from `StatusPanel` + `TargetsPanel`
- Right-side AI workspace shell, current settings foldout, and hover/focus visibility logic live in [frontend/src/components/Dashboard/RiskExplanationPanel.tsx](frontend/src/components/Dashboard/RiskExplanationPanel.tsx)

### 7.3 Services

Risk SSE transport:
- [frontend/src/services/riskSseService.ts](frontend/src/services/riskSseService.ts)

Chat WS transport:
- [frontend/src/services/chatWsService.ts](frontend/src/services/chatWsService.ts)

Speech services:
- Browser TTS service: [frontend/src/services/speechService.ts](frontend/src/services/speechService.ts)
- Recorder service: [frontend/src/services/voiceRecorderService.ts](frontend/src/services/voiceRecorderService.ts)

S-57 API client:
- [frontend/src/services/s57Service.ts](frontend/src/services/s57Service.ts)

### 7.4 Store Layer

Risk state store:
- [frontend/src/store/useRiskStore.ts](frontend/src/store/useRiskStore.ts)

AI center and chat state:
- [frontend/src/store/useAiCenterStore.ts](frontend/src/store/useAiCenterStore.ts)

Theme state:
- [frontend/src/store/useThemeStore.ts](frontend/src/store/useThemeStore.ts)

### 7.5 Hooks and Utils

Voice capture orchestration:
- [frontend/src/hooks/useVoiceCapture.ts](frontend/src/hooks/useVoiceCapture.ts)

Auto speech broadcast:
- [frontend/src/hooks/useAiSpeechBroadcast.ts](frontend/src/hooks/useAiSpeechBroadcast.ts)

LLM event normalization:
- [frontend/src/utils/llmEventNormalizer.ts](frontend/src/utils/llmEventNormalizer.ts)

Risk display helpers:
- [frontend/src/utils/riskDisplay.ts](frontend/src/utils/riskDisplay.ts)

### 7.6 Types and Protocol Contracts

Main protocol contract types:
- [frontend/src/types/schema.d.ts](frontend/src/types/schema.d.ts)

AI center message model:
- [frontend/src/types/aiCenter.ts](frontend/src/types/aiCenter.ts)

Configuration constants:
- [frontend/src/config/constants.ts](frontend/src/config/constants.ts)
- [frontend/src/config/layerStyles.ts](frontend/src/config/layerStyles.ts)

## 8. Protocol and Endpoint Quick Reference

### 8.1 Backend Endpoints

Risk stream (SSE):
- `/api/v2/risk`

Chat socket (WebSocket):
- `/api/v2/chat`

Chart APIs:
- `/api/s57/tiles/{z}/{x}/{y}.pbf`
- `/api/s57/layers`
- `/api/s57/safety-contour`
- `/api/s57/style.json`

Endpoint sources:
- [backend/map-service/src/main/java/com/whut/map/map_service/shared/transport/protocol/ProtocolPaths.java](backend/map-service/src/main/java/com/whut/map/map_service/shared/transport/protocol/ProtocolPaths.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java](backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java)

### 8.2 Event Types

SSE event types:
- `RISK_UPDATE`
- `EXPLANATION`
- `ERROR`

WebSocket uplink types:
- `PING`
- `CHAT`
- `SPEECH`
- `CLEAR_HISTORY`

WebSocket downlink types:
- `PONG`
- `CHAT_REPLY`
- `SPEECH_TRANSCRIPT`
- `CLEAR_HISTORY_ACK`
- `ERROR`

Contract source:
- [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md)
- [frontend/src/types/schema.d.ts](frontend/src/types/schema.d.ts)

## 9. Configuration and Environment Notes

### 9.1 map-service Main Runtime Config

Primary config file:
- [backend/map-service/src/main/resources/application.properties](backend/map-service/src/main/resources/application.properties)

Contains:
- MQTT broker/topic
- AIS settings
- Risk thresholds
- Engine parameters
- LLM provider and timeout settings
- Whisper URL and limits
- Database connection for chart data

Local secret template:
- [backend/map-service/src/main/resources/application-local.properties.example](backend/map-service/src/main/resources/application-local.properties.example)

### 9.2 Secret Handling Constraint

Project rule from architecture memory:
- Local API keys are expected in ignored local config (`application-local.properties`) or environment variables.
- Do not replace real key config semantics with fake defaults in committed properties.

### 9.3 listener-service Config

Primary config:
- [backend/listener-service/src/main/resources/application.properties](backend/listener-service/src/main/resources/application.properties)

### 9.4 Frontend Runtime Origins

Frontend computes backend origins from browser host, with optional env overrides:
- [frontend/src/config/constants.ts](frontend/src/config/constants.ts)

### 9.5 Compose and Runtime Services

Compose stack:
- [compose.yaml](compose.yaml)

Defined services:
- MQTT broker (`eclipse-mosquitto`)
- PostGIS database (`postgis/postgis`)
- Whisper server (`ghcr.io/ggml-org/whisper.cpp`)

Optional environment:
- [.env.example](.env.example)

## 10. Documentation Layers and Truth Policy

Main docs index:
- [docs/README.md](docs/README.md)

Current truth docs (authoritative for current system):
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md)
- [docs/frontend-design.md](docs/frontend-design.md)
- [docs/ADR_AND_REVIEW_FINDINGS.md](docs/ADR_AND_REVIEW_FINDINGS.md)
- [docs/TODO.md](docs/TODO.md)

Active planning docs:
- [docs/v1.0/README.md](docs/v1.0/README.md)
- [docs/v1.0/SOURCEBOOK.md](docs/v1.0/SOURCEBOOK.md)

Planning note:
- `v1.0/` is the active milestone planning directory. `agent` is the primary release track; `hydrology` and `weather` are parallel non-blocking tracks.

Historical docs (not current truth source):
- [docs/history](docs/history)
- [docs/history/v0.9-frontend-enhancement](docs/history/v0.9-frontend-enhancement)

## 11. Tests and Quality Coverage

### 11.1 map-service Tests

Test root:
- [backend/map-service/src/test/java](backend/map-service/src/test/java)

Coverage includes:
- Risk engines and pipeline
- Tracking stores
- SSE transport
- LLM services, context, memory, clients
- Chat WS handler and audio validation

Known caveat:
- Full `map-service` test run can fail at context load when local LLM API key is unavailable due intentional fail-fast validation.

### 11.2 listener-service Tests

Test root:
- [backend/listener-service/src/test/java](backend/listener-service/src/test/java)

Current coverage is minimal.

### 11.3 Frontend Tests

Test files include:
- [frontend/src/store/useRiskStore.test.ts](frontend/src/store/useRiskStore.test.ts)
- [frontend/src/store/useAiCenterStore.test.ts](frontend/src/store/useAiCenterStore.test.ts)
- [frontend/src/services/riskSseService.test.ts](frontend/src/services/riskSseService.test.ts)
- [frontend/src/services/chatWsService.test.ts](frontend/src/services/chatWsService.test.ts)
- [frontend/src/components/Dashboard/ChatComposer.test.tsx](frontend/src/components/Dashboard/ChatComposer.test.tsx)
- [frontend/src/components/Dashboard/ChatMessageList.test.tsx](frontend/src/components/Dashboard/ChatMessageList.test.tsx)
- [frontend/src/components/Dashboard/TargetsPanel.test.tsx](frontend/src/components/Dashboard/TargetsPanel.test.tsx)
- [frontend/src/components/Dashboard/StatusPanel.test.tsx](frontend/src/components/Dashboard/StatusPanel.test.tsx)

Testing setup and fixtures:
- [frontend/src/test/setup.ts](frontend/src/test/setup.ts)
- [frontend/src/test/fixtures](frontend/src/test/fixtures)

## 12. Simulator and Utility Scripts

Simulator root:
- [simulator](simulator)

Main scripts:
- MQTT publisher base: [simulator/mqtt_publisher_base.py](simulator/mqtt_publisher_base.py)
- AIS route simulator: [simulator/ais_simulator.py](simulator/ais_simulator.py)
- Jamaica Bay route publisher: [simulator/jamaica_bay_ais_mqtt_publisher.py](simulator/jamaica_bay_ais_mqtt_publisher.py)
- LLM smoke test publisher: [simulator/llm_smoke_test_publisher.py](simulator/llm_smoke_test_publisher.py)
- CSV replay script: [simulator/cpaTcapTest.py](simulator/cpaTcapTest.py)

Repo utility scripts:
- Relative link checker: [scripts/check_doc_relative_links.py](scripts/check_doc_relative_links.py)
- LF ending checker/fixer: [scripts/check_line_endings.py](scripts/check_line_endings.py)

## 13. Known Reality Checks and Mismatches

1. Frontend runtime behavior vs frontend README wording:
- Current app code in [frontend/src/App.tsx](frontend/src/App.tsx) connects real SSE/WS on mount.
- [frontend/README.md](frontend/README.md) currently describes mock-first mode as active.
- Treat source code behavior as runtime truth.

2. listener-service status:
- Current architecture docs position this service as standby, not in primary runtime path.

3. Config and key policy:
- LLM config is intentionally fail-fast when enabled provider keys are missing.

## 14. Fast Task-To-File Lookup

If you need to...

Understand risk frame generation:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java)

Trace SSE publish path:
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/RiskStreamPublisher.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/RiskStreamPublisher.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java)

Trace chat and speech path:
- [backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/llm/service/VoiceChatService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/service/VoiceChatService.java)
- [backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java)

Adjust frontend protocol consumption:
- [frontend/src/types/schema.d.ts](frontend/src/types/schema.d.ts)
- [frontend/src/services/riskSseService.ts](frontend/src/services/riskSseService.ts)
- [frontend/src/services/chatWsService.ts](frontend/src/services/chatWsService.ts)
- [frontend/src/store/useRiskStore.ts](frontend/src/store/useRiskStore.ts)
- [frontend/src/store/useAiCenterStore.ts](frontend/src/store/useAiCenterStore.ts)

Adjust map rendering:
- [frontend/src/components/Map/MapContainer.tsx](frontend/src/components/Map/MapContainer.tsx)
- [frontend/src/config/layerStyles.ts](frontend/src/config/layerStyles.ts)

Simulate data for local integration:
- [simulator/jamaica_bay_ais_mqtt_publisher.py](simulator/jamaica_bay_ais_mqtt_publisher.py)
- [simulator/llm_smoke_test_publisher.py](simulator/llm_smoke_test_publisher.py)

Check docs consistency and formatting:
- [scripts/check_doc_relative_links.py](scripts/check_doc_relative_links.py)
- [scripts/check_line_endings.py](scripts/check_line_endings.py)

## 15. Suggested Maintenance Rules For This File

1. Update this file whenever one of the following changes occurs:
- runtime endpoint path changes
- protocol event changes
- module ownership or path changes
- active milestone handoff

2. Keep this file implementation-first:
- prefer source links and current docs over planning prose
- separate current truth from history

3. Keep links relative and repository-local.
