# CURRENT_SYSTEM_OVERVIEW

> Last updated: 2026-05-07 (post-v1.0 handoff card)
> Purpose: Task-to-file navigation across modules. Use when you already know the area and need to locate concrete source files.
> Position in reading order: **not the first stop**. Read [`README.md`](./README.md) → [`PROJECT_STATUS.md`](./PROJECT_STATUS.md) → [`docs/README.md`](./docs/README.md) → [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) first.

This file is intentionally compact. Module-level rationale and architectural decisions live in [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md), [`docs/EVENT_SCHEMA.md`](./docs/EVENT_SCHEMA.md) and [`docs/ADR_AND_REVIEW_FINDINGS.md`](./docs/ADR_AND_REVIEW_FINDINGS.md). v1.0 milestone history is at [`docs/history/v1.0/README.md`](./docs/history/v1.0/README.md).

## 1. System Snapshot

- Real-time maritime situational awareness and collision-risk assessment.
- Inputs: AIS over MQTT (`usv/AisMessage`), weather over MQTT (`usv/Weather`), S-57 ENC tables in PostGIS.
- Outputs: SSE risk + environment + advisory streams; WebSocket chat with optional agent loop and voice; S-57 vector tile HTTP API.
- Bounded agent loop with `AgentSnapshot` deep-copied at boundary; advisory pipeline emits `ADVISORY` SSE events; chat agent path emits `AGENT_STEP` WebSocket events.

## 2. Top-Level Repository Structure

- [backend](backend) — Spring Boot services
- [frontend](frontend) — React + Vite web client
- [simulator](simulator) — Python AIS / weather publishers
- [docs](docs) — current truth, decisions, history
- [scripts](scripts) — repo-side utilities
- [compose.yaml](compose.yaml) — local services (MQTT, PostGIS, Whisper)

## 3. Backend Services

- Active: [backend/map-service](backend/map-service) — main runtime path.
- Standby: [backend/listener-service](backend/listener-service) — offline ingestion persistence; not on primary runtime path.

## 4. Endpoint Quick Reference

### 4.1 SSE — `/api/v2/risk`

Event types: `RISK_UPDATE`, `ENVIRONMENT_UPDATE`, `EXPLANATION`, `ADVISORY`, `ERROR`.

Source:
- [backend/map-service/.../risk/api/RiskSseController.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java)
- [backend/map-service/.../risk/transport/RiskStreamPublisher.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/RiskStreamPublisher.java)

### 4.2 WebSocket — `/api/v2/chat`

Uplink: `PING`, `CHAT` (with optional `agent_mode` / `selected_target_ids` / `edit_last_user_message`), `SPEECH`, `CLEAR_HISTORY`, `SET_LLM_PROVIDER_SELECTION`.
Downlink: `PONG`, `CAPABILITY` (handshake), `CHAT_REPLY`, `AGENT_STEP`, `SPEECH_TRANSCRIPT`, `LLM_PROVIDER_SELECTION`, `CLEAR_HISTORY_ACK`, `ERROR`.

Source:
- [backend/map-service/.../llm/transport/ws/ChatWebSocketHandler.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java)
- [backend/map-service/.../shared/transport/protocol/ProtocolPaths.java](backend/map-service/src/main/java/com/whut/map/map_service/shared/transport/protocol/ProtocolPaths.java)

### 4.3 Chart HTTP — `/api/s57/*`

`/api/s57/tiles/{z}/{x}/{y}.pbf`, `/api/s57/layers`, `/api/s57/safety-contour` (HTTP command), `/api/s57/style.json`.

Source:
- [backend/map-service/.../chart/api/S57Controller.java](backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java)

Protocol contract: [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md) and [frontend/src/types/schema.d.ts](frontend/src/types/schema.d.ts).

## 5. Configuration

- map-service: [backend/map-service/src/main/resources/application.properties](backend/map-service/src/main/resources/application.properties)
- listener-service: [backend/listener-service/src/main/resources/application.properties](backend/listener-service/src/main/resources/application.properties)
- LLM secret template (gitignored counterpart): [backend/map-service/src/main/resources/application-local.properties.example](backend/map-service/src/main/resources/application-local.properties.example)
- Frontend origins: [frontend/src/config/constants.ts](frontend/src/config/constants.ts)
- Compose: [compose.yaml](compose.yaml) — MQTT, PostGIS, whisper.cpp

LLM key policy: enabled providers fail fast when key absent; do not commit fake defaults.

## 6. Tests

- map-service: [backend/map-service/src/test/java](backend/map-service/src/test/java) — risk engines, pipeline, tracking, SSE transport, LLM services, chat WS, audio validation. Full context-load may fail without local LLM key (intentional fail-fast).
- listener-service: [backend/listener-service/src/test/java](backend/listener-service/src/test/java) — minimal coverage.
- Frontend: stores, services and Dashboard components under [frontend/src](frontend/src) (Vitest + Testing Library).

## 7. Reality Checks (Known Mismatches)

1. [frontend/README.md](frontend/README.md) describes mock-first mode; runtime [frontend/src/App.tsx](frontend/src/App.tsx) connects real SSE/WS on mount. Trust source code.
2. listener-service is standby, not on primary runtime path.
3. LLM config is fail-fast when enabled provider keys are missing.
4. v1.0 plan and step documents have been archived to [docs/history/v1.0/](docs/history/v1.0/); they are no longer current truth.

## 8. Fast Task-To-File Lookup

Risk frame generation:
- [backend/map-service/.../risk/pipeline/ShipDispatcher.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/pipeline/ShipDispatcher.java)
- [backend/map-service/.../risk/engine/risk/RiskAssessmentEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/risk/RiskAssessmentEngine.java)

CPA/TCPA, encounter, safety domain, prediction:
- [backend/map-service/.../risk/engine/collision/CpaTcpaEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/collision/CpaTcpaEngine.java)
- [backend/map-service/.../risk/engine/encounter/EncounterClassifier.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/encounter/EncounterClassifier.java)
- [backend/map-service/.../risk/engine/safety/ShipDomainEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/safety/ShipDomainEngine.java)
- [backend/map-service/.../risk/engine/trajectoryprediction/CvPredictionEngine.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/engine/trajectoryprediction/CvPredictionEngine.java)

Environment context (weather + hydrology + safety contour + active_alerts):
- [backend/map-service/.../risk/environment/EnvironmentContextService.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/environment/EnvironmentContextService.java)

SSE publish path:
- [backend/map-service/.../risk/transport/RiskStreamPublisher.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/transport/RiskStreamPublisher.java)
- [backend/map-service/.../risk/api/RiskSseController.java](backend/map-service/src/main/java/com/whut/map/map_service/risk/api/RiskSseController.java)

Chat / speech / agent_step path:
- [backend/map-service/.../llm/transport/ws/ChatWebSocketHandler.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/transport/ws/ChatWebSocketHandler.java)
- [backend/map-service/.../llm/service/LlmChatService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java)
- [backend/map-service/.../llm/service/VoiceChatService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/service/VoiceChatService.java)

LLM context, memory, prompt:
- [backend/map-service/.../llm/context/RiskContextHolder.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextHolder.java)
- [backend/map-service/.../llm/context/RiskContextFormatter.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/context/RiskContextFormatter.java)
- [backend/map-service/.../llm/context/ExplanationCache.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/context/ExplanationCache.java)
- [backend/map-service/.../llm/context/LlmRiskEventListener.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/context/LlmRiskEventListener.java)
- [backend/map-service/.../llm/memory/ConversationMemory.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/memory/ConversationMemory.java)
- [backend/map-service/.../llm/prompt/PromptTemplateService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/prompt/PromptTemplateService.java)

LLM clients and config:
- [backend/map-service/.../llm/client/LlmClient.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/client/LlmClient.java)
- [backend/map-service/.../llm/client/GeminiLlmClient.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/client/GeminiLlmClient.java)
- [backend/map-service/.../llm/client/ZhipuLlmClient.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/client/ZhipuLlmClient.java)
- [backend/map-service/.../llm/config/LlmProperties.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/config/LlmProperties.java)

Agent loop and tools (v1.0):
- [backend/map-service/.../llm/agent/AgentLoopOrchestrator.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentLoopOrchestrator.java)
- [backend/map-service/.../llm/agent/AgentSnapshotFactory.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/AgentSnapshotFactory.java)
- [backend/map-service/.../llm/agent/tool/AgentToolRegistry.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/tool/AgentToolRegistry.java)
- [backend/map-service/.../llm/agent/advisory/AdvisoryService.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/advisory/AdvisoryService.java)
- [backend/map-service/.../llm/agent/graph/GraphQueryPort.java](backend/map-service/src/main/java/com/whut/map/map_service/llm/agent/graph/GraphQueryPort.java)

S-57 chart serving:
- [backend/map-service/.../chart/api/S57Controller.java](backend/map-service/src/main/java/com/whut/map/map_service/chart/api/S57Controller.java)
- [backend/map-service/.../chart/repository/S57TileRepository.java](backend/map-service/src/main/java/com/whut/map/map_service/chart/repository/S57TileRepository.java)

Tracking stores:
- [backend/map-service/.../tracking/store/ShipStateStore.java](backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/ShipStateStore.java)
- [backend/map-service/.../tracking/store/ShipTrajectoryStore.java](backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/ShipTrajectoryStore.java)
- [backend/map-service/.../tracking/store/DerivedTargetStateStore.java](backend/map-service/src/main/java/com/whut/map/map_service/tracking/store/DerivedTargetStateStore.java)

Frontend protocol consumption:
- [frontend/src/types/schema.d.ts](frontend/src/types/schema.d.ts)
- [frontend/src/services/riskSseService.ts](frontend/src/services/riskSseService.ts)
- [frontend/src/services/chatWsService.ts](frontend/src/services/chatWsService.ts)
- [frontend/src/store/useRiskStore.ts](frontend/src/store/useRiskStore.ts)
- [frontend/src/store/useAiCenterStore.ts](frontend/src/store/useAiCenterStore.ts)

Frontend map rendering:
- [frontend/src/components/Map/MapContainer.tsx](frontend/src/components/Map/MapContainer.tsx)
- [frontend/src/config/layerStyles.ts](frontend/src/config/layerStyles.ts)

Simulator entry points:
- [simulator/jamaica_bay_ais_mqtt_publisher.py](simulator/jamaica_bay_ais_mqtt_publisher.py)
- [simulator/llm_smoke_test_publisher.py](simulator/llm_smoke_test_publisher.py)

Docs consistency tooling:
- [scripts/check_doc_relative_links.py](scripts/check_doc_relative_links.py)
- [scripts/check_line_endings.py](scripts/check_line_endings.py)

## 9. Maintenance Rules For This File

1. Update on changes to: runtime endpoint paths, protocol event types, module ownership / package paths, active milestone handoff.
2. Keep links relative and repository-local.
3. Do not duplicate architectural rationale here; defer to ARCHITECTURE.md and ADR_AND_REVIEW_FINDINGS.md.
4. Do not include hardcoded file counts or per-package file numbers; they go stale on every PR.
