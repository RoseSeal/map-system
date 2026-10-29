# Map System

Real-time maritime situational awareness and collision risk assessment system.

## Overview

Map System ingests vessel data from MQTT, normalizes multi-source inputs into a unified ship model, computes collision risk, and delivers:

- 2.5D map visualization
- SSE risk streaming
- WebSocket chat and voice interaction
- LLM-based risk explanation and proactive advisories (bounded tool-calling agent loop, per-encounter trigger deduplication)
- Optional learned (GRU) trajectory prediction and historical-case GraphRAG sidecars

## Architecture

MQTT -> `map-service` -> SSE (`/api/v2/risk`) + WebSocket (`/api/v2/chat`) -> Frontend

Optional sidecars: `trajectory-service` (GRU trajectory prediction, ONNX) and `graphrag-service` (historical case retrieval).

## Demo Scenarios

- Wuhan Yangtze reach (main demo): `simulator/wuhan_yangtze_ais_simulator.py` — head-on and overtaking encounters over satellite imagery, exercising risk escalation and advisories.
- Jamaica Bay (chart demo): `simulator/jamaica_bay_ais_mqtt_publisher.py` — the area covered by the S-57 chart data (loaded into PostGIS, not shipped in this repository).

## Quick Start

Prerequisites: JDK 21, Maven, Node, Python 3, Docker. Setup and run steps for the Wuhan demo are in [docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md](./docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md) §0, including how to run without an LLM API key (`llm.enabled=false`) and a Maven mirror for slow networks.

## Documentation

Read in this order. Each step narrows scope; do not skip ahead unless you already know the area.

1. [PROJECT_STATUS.md](./PROJECT_STATUS.md) — milestone-level state and pointers to active track plans.
2. [docs/README.md](./docs/README.md) — full document map (truth / decisions / planning / history).
3. [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) — current architectural truth.
4. [docs/EVENT_SCHEMA.md](./docs/EVENT_SCHEMA.md) — current SSE / WebSocket protocol truth.
5. [docs/frontend-design.md](./docs/frontend-design.md) — frontend architecture and interaction model.

Secondary references:

- [CURRENT_SYSTEM_OVERVIEW.md](./CURRENT_SYSTEM_OVERVIEW.md) — task-to-file navigation across modules. Use after the above when locating concrete source files.
- [docs/history/v1.2-trajectory-prediction/README.md](./docs/history/v1.2-trajectory-prediction/README.md) — v1.2 closure summary (latest milestone: learned trajectory prediction).
- [docs/ADR_AND_REVIEW_FINDINGS.md](./docs/ADR_AND_REVIEW_FINDINGS.md) — architecture decisions and review findings.
- [docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md](./docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md) — demo setup and script.

## Tech Stack

- Backend: Java, Spring Boot, MQTT
- Streaming: SSE, WebSocket
- Frontend: Vite, TypeScript, MapLibre, Deck.gl, Tailwind
- LLM / ASR: Zhipu GLM (default), Gemini, `whisper.cpp`
- Sidecars: Python, FastAPI, ONNX Runtime (GRU), LightRAG
- Data: PostgreSQL, PostGIS
