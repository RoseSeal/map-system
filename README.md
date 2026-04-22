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

- Docs index: [docs/README.md](./docs/README.md)
- Current architecture truth: [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)
- Current protocol truth: [docs/EVENT_SCHEMA.md](./docs/EVENT_SCHEMA.md)
- Frontend architecture and interaction model: [docs/frontend-design.md](./docs/frontend-design.md)
- Current project status: [PROJECT_STATUS.md](./PROJECT_STATUS.md)
- Current system overview: [CURRENT_SYSTEM_OVERVIEW.md](./CURRENT_SYSTEM_OVERVIEW.md)

## Tech Stack

- Backend: Java, Spring Boot, MQTT
- Streaming: SSE, WebSocket
- Frontend: Vite, TypeScript, MapLibre, Deck.gl, Tailwind
- LLM / ASR: Gemini, 智谱, `whisper.cpp`
- Data: PostgreSQL, PostGIS
