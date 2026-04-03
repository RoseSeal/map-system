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

- Architecture: [docs/ARCHITECTURE.md](/home/xin/workspace/map-system/docs/ARCHITECTURE.md)
- System Design: [docs/design.md](/home/xin/workspace/map-system/docs/design.md)
- Event Schema: [docs/EVENT_SCHEMA.md](/home/xin/workspace/map-system/docs/EVENT_SCHEMA.md)
- LLM Roadmap: [docs/LLM_ROADMAP.md](/home/xin/workspace/map-system/docs/LLM_ROADMAP.md)
- Frontend Design: [docs/frontend-design.md](/home/xin/workspace/map-system/docs/frontend-design.md)

## Tech Stack

- Backend: Java, Spring Boot, MQTT
- Streaming: SSE, WebSocket
- Frontend: Vite, TypeScript, MapLibre, Deck.gl, Tailwind
- LLM / ASR: Gemini, 智谱, `whisper.cpp`
- Data: PostgreSQL, PostGIS
