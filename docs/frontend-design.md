# Frontend Design Reference

> 文档状态：current
> 最后更新：2026-04-06
> 基线来源：`docs/ARCHITECTURE.md`、`docs/EVENT_SCHEMA.md`

This document describes the frontend architecture, rendering model, and operational guidance for the Map System.

## 1. Project Background

The frontend is a WebGL-based situational awareness UI for the Map System maritime risk warning platform.

Core goals:
- Render a 2.5D maritime chart (S-57 ENC) in real time.
- Visualize dynamic vessel risk information (CPA, OZT, Safety Domain, predicted trajectory).
- Support LLM-based risk explanation and voice interaction via chat panel.

Primary frontend path: `frontend/`

## 2. Architecture Model

The system follows a Fat Backend + Thin Frontend model:
- Backend (`map-service`) handles MQTT ingestion, risk calculation, LLM orchestration, and streaming.
- Frontend focuses on rendering and interaction.

Transport:
- Risk stream: SSE `/api/v2/risk` — `RISK_UPDATE`, `EXPLANATION`, `ERROR`
- Chat stream: WebSocket `/api/v2/chat` — `CHAT`, `SPEECH`, `CHAT_REPLY`, `SPEECH_TRANSCRIPT`, `ERROR`

See `docs/EVENT_SCHEMA.md` for full protocol definitions.

## 3. Frontend Technology Stack

- React 18 (functional components + hooks)
- Vite 5
- TypeScript (strict typing)
- Zustand (state management)
- MapLibre GL JS 4+
- Deck.gl 9+ (MapboxOverlay integration)
- Tailwind CSS 3+

## 4. Repository Structure

```text
frontend/
  src/
    components/
    config/
      constants.ts
    services/
      riskSseService.ts       ← SSE 客户端（风险流）
      chatWsService.ts        ← WebSocket 客户端（聊天流）
    store/
      useRiskStore.ts
    types/
      schema.d.ts             ← 协议类型定义（以 EVENT_SCHEMA.md 为准）
    utils/
```

## 5. Data Layers and Contract Model

Primary render unit: `RiskObject` (`RISK_UPDATE` payload)

Key fields:
- `risk_object_id`, `timestamp`, `governance.mode`, `governance.trust_factor`
- `own_ship`: `position`, `dynamics` (`sog`, `cog`, `hdg`, `rot`), `platform_health`, `future_trajectory`, `safety_domain`
- `targets[*]`: `risk_level`, `cpa_metrics`, `graphic_cpa_line`, `ozt_sector`
- `environment_context.safety_contour_val`

Risk levels: `SAFE`, `CAUTION`, `WARNING`, `ALARM`

Platform health states: `NORMAL`, `DEGRADED`, `NUC`

Explanation event: `EXPLANATION` payload delivered on the risk SSE channel, separate from `RISK_UPDATE`. Rendered as a risk explanation card.

## 6. Rendering Rules

Map and overlay layering order:
1. MapLibre base map (bottom) — S-57 ENC tiles from `/api/s57/tiles/{z}/{x}/{y}.pbf`
2. Deck.gl dynamic overlays (middle) — vessel models, CPA lines, OZT sectors, safety domains
3. React UI overlays (top) — dashboard panels, chat panel, theme toggle

S-57 map style guidance:
- Land (`LNDARE`): base fill plus optional 3D extrusion
- Depth areas (`DEPARE`): shallow/deep color split by depth value
- Restricted zones (`RESARE`): transparent red warning fill
- Additional chart layers: `COALNE`, `DEPCNT`, `SOUNDG`

Behavioral display thresholds:
- Show safety domain when risk level >= `CAUTION`
- Show CPA line when `graphic_cpa_line` is present in target's `risk_assessment`
- Show OZT sector when `ozt_sector.is_active` is true
- Show low-trust warning when `trust_factor < 0.4`

## 7. Theme Support

Frontend supports `light / dark` switchable themes. Toggle is managed in frontend state; no backend dependency.

## 8. Voice Interaction

- TTS: Browser-native `SpeechSynthesis` for LLM reply playback.
- ASR: Audio captured by `MediaRecorder`, sent as `SPEECH` WebSocket message (Base64, `webm` format) to backend. Backend orchestrates `whisper.cpp` transcription.
- Two modes: `direct` (transcribe + LLM reply) and `preview` (transcribe only, result returned in input field).

## 9. Local Frontend Development

```bash
cd frontend
npm install
npm run dev
```

Backend must be running at `http://localhost:8080` for SSE and WebSocket connections to succeed.
Whisper service runs at `http://localhost:8081` (Docker, orchestrated by backend — not called directly by frontend).

## 10. Acceptance Checklist

- Frontend compiles and launches without errors.
- SSE connection to `/api/v2/risk` established; `RISK_UPDATE` events received and rendered.
- WebSocket connection to `/api/v2/chat` established; `CHAT` and `SPEECH` messages functional.
- S-57 tiles load from `/api/s57/tiles/{z}/{x}/{y}.pbf`.
- Land/depth/coastline layers render correctly.
- Dynamic vessel overlays render and update on each `RISK_UPDATE`.
- Risk overlays (safety domain, OZT, CPA line) follow threshold rules.
- `EXPLANATION` events render as risk explanation cards.
- Light/dark theme toggle functions correctly.
- No critical console errors in browser.
