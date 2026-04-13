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

## 9. Frontend Integration Guidance

### 9.1 Risk Score Rendering Continuity

`risk_score` is a continuous display signal, but backend scoring may still contain intentional rule boundaries. A representative case is the CPA crossing point: once a target transitions from approaching to diverging, backend `tcpaScore` may drop sharply by design.

This discontinuity should be handled at the frontend rendering layer rather than forcing backend risk semantics to become animation-oriented.

Frontend responsibilities:
- Treat backend `risk_score` as the authoritative instantaneous value.
- Apply short-window visual smoothing when rendering charts, cards, or list ordering indicators derived from `risk_score`.
- Prefer interpolation / easing for displayed values, not mutation of the underlying store payload.
- Keep alarm logic, threshold logic, and protocol state based on raw backend values, not smoothed values.

Implementation guidance:
- Suitable targets include progress bars, chip intensity, marker glow, panel transitions, and secondary ranking visuals.
- Suitable techniques include lerp, spring easing, or fixed-duration tweening over a short interval.
- Do not delay or soften discrete backend fields such as `risk_level`, `ozt_sector.is_active`, or warning trigger conditions.

Constraint:
- Smoothing is a presentation concern only. It must not change business semantics, alert timing, or operator-visible categorical state.

### 9.2 Stepwise Backend Field Adoption

Step 2 and Step 3 may extend the frontend schema ahead of stable UI usage, for example `targets[*].predicted_trajectory` and `targets[*].risk_assessment.encounter_type`.

Frontend responsibilities:
- Accept new optional protocol fields in TypeScript schema and store ingestion as soon as the backend begins emitting them.
- Avoid deep UI coupling to fields whose semantics are still expected to evolve in later engine steps.
- Prefer thin compatibility work first: schema updates, safe parsing, optional-field guards, and internal render hooks.
- Defer production-facing visualization and interaction logic for evolving Step 2/3 engine outputs until Step 4 risk semantics are stable.

Implementation guidance:
- It is acceptable to add debug-only panels or non-blocking inspection views for intermediate fields.
- Map layers, target cards, ranking logic, and operator-facing summaries should not depend on intermediate fields unless the backend contract for those fields is already stable.
- Cleanup-path omissions, temporary fallback values, and staging-only asymmetries are expected during engine enhancement steps and should not force premature frontend coupling.

Constraint:
- Frontend compatibility must not be mistaken for frontend commitment. Accepting a field in the contract layer does not require immediate visual consumption.

## 10. Local Frontend Development

```bash
cd frontend
npm install
npm run dev
```

Backend must be running at `http://localhost:8080` for SSE and WebSocket connections to succeed.
Whisper service runs at `http://localhost:8081` (Docker, orchestrated by backend — not called directly by frontend).

## 11. Acceptance Checklist

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
