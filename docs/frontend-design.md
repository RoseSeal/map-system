# Frontend Design Reference

> 文档状态：current
> 最后更新：2026-04-14
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
- `targets[*]`: `risk_level`, `cpa_metrics`, `graphic_cpa_line`, `ozt_sector`, `encounter_type`, `risk_score`, `risk_confidence`, `predicted_trajectory`
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

`risk_score` is a continuous backend auxiliary signal, but it is not yet treated as an operator-facing display metric in the current frontend. A representative backend behavior is the CPA crossing point: once a target transitions from approaching to diverging, backend `tcpaScore` may drop sharply by design.

Current frontend policy:
- Use `risk_score` only as a secondary sort key within the same `risk_level` group.
- Treat a missing `risk_score` as `0.0` for sort purposes.
- Do not display `risk_score` as text, progress, color intensity, chip strength, glow strength, or any other operator-visible metric.

Deferred direction:
- After the backend scoring model is validated against real traffic data, `risk_score` may be promoted to a presentation-layer display signal for visual intensity or animation strength.
- If that promotion happens later, frontend smoothing should remain presentation-only and must not alter alert timing, threshold logic, or categorical state.

Constraint:
- Until that future promotion is explicitly approved, `risk_score` remains a sorting aid only.

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

### 9.3 Trajectory Rendering Style — No Branch on `prediction_type`

`target.predicted_trajectory.points` may be produced by either the CV (linear) or CTR (constant turn rate) model. The backend communicates which model was used via `prediction_type`, but the frontend must not branch on this field for rendering style.

Frontend responsibilities:
- Render `PathLayer` with a uniform style regardless of `prediction_type` value.
- Treat all trajectory point arrays identically: connect them in sequence, apply the standard fade-opacity scheme.
- Curved appearance emerges naturally from point spatial distribution when the backend CTR model is active; no renderer change is required.

Constraint:
- Do not add style branches, icon changes, or color overrides keyed on `prediction_type`. Doing so would couple the renderer to backend algorithm internals, requiring frontend changes on every future model upgrade.

### 9.4 `risk_confidence` Consumption Boundary

`risk_confidence` is a per-target assessment confidence emitted by the backend. It is not the same field as top-level `governance.trust_factor`.

Frontend responsibilities:
- Accept and store the field in the contract layer.
- Keep any consumption secondary and non-blocking.
- If consumed in UI, prefer low-confidence hinting such as a subtle badge, muted secondary styling, or diagnostic text in an inspection/debug surface.

Constraint:
- Do not let `risk_confidence` suppress, delay, or override backend `risk_level`.
- Do not treat `risk_confidence` as the global system trust signal; that role belongs to `governance.trust_factor`.
- Backend fallback semantics: when no valid target assessments are available, `governance.trust_factor` may be `0.0`; frontend must interpret this as "no current global confidence basis", not as a hidden risk-level override.

### 9.5 Voice Interaction TODO

Pending frontend work:
- Add a cancel-transcription action while voice capture is in `transcribing` state, so an accidental recording can be abandoned from the UI without waiting for Whisper to finish.
- This is a frontend interaction safeguard. It does not currently imply backend cancellation semantics for an already submitted Whisper job.

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
