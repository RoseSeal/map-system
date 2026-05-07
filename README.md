# Map System

Real-time maritime situational awareness and collision risk assessment system.

## Overview

Map System ingests vessel data from MQTT, normalizes multi-source inputs into a unified ship model, computes collision risk, and delivers:

- 2.5D map visualization
- SSE risk streaming
- WebSocket chat and voice interaction
- LLM-based risk explanation

## Architecture

MQTT -> `map-service` -> SSE (`/api/v2/risk`) + WebSocket (`/api/v2/chat`) -> Frontend

## Documentation

Read in this order. Each step narrows scope; do not skip ahead unless you already know the area.

1. [PROJECT_STATUS.md](./PROJECT_STATUS.md) — milestone-level state and pointers to active track plans.
2. [docs/README.md](./docs/README.md) — full document map (truth / decisions / planning / history).
3. [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) — current architectural truth.
4. [docs/EVENT_SCHEMA.md](./docs/EVENT_SCHEMA.md) — current SSE / WebSocket protocol truth.
5. [docs/frontend-design.md](./docs/frontend-design.md) — frontend architecture and interaction model.

Secondary references:

- [CURRENT_SYSTEM_OVERVIEW.md](./CURRENT_SYSTEM_OVERVIEW.md) — task-to-file navigation across modules. Use after the above when locating concrete source files.
- [docs/history/v1.0/README.md](./docs/history/v1.0/README.md) — v1.0 closure summary (what the latest released milestone delivered).
- [docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md](./docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md) — current demo script.

## Tech Stack

- Backend: Java, Spring Boot, MQTT
- Streaming: SSE, WebSocket
- Frontend: Vite, TypeScript, MapLibre, Deck.gl, Tailwind
- LLM / ASR: Gemini, 智谱, `whisper.cpp`
- Data: PostgreSQL, PostGIS
