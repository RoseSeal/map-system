# Project Status

> Last updated: 2026-07-11
> Role: Pointer-style status entry. Detailed step-level progress is owned by each milestone's plan and step documents, not duplicated here.

## 1. Current Baseline

- Latest completed milestone: `v1.2-trajectory-prediction` — closed 2026-07-12. A learned GRU predictor, validated on real inland AIS (FDE −28.3%, true-danger miss rate 12.2%→8.6%), now serves the live risk chain behind a feature flag via an ONNX sidecar; replay on real bend encounters reproduced the miss-rate reduction end to end.
- No active milestone. The project is in showcase/closure mode: remaining work is repository-level closure and interview polish, tracked outside milestone planning.

## 2. Closure References

Recent milestones have been archived in full. Read in this order to understand what shipped:

1. [`docs/history/v1.2-trajectory-prediction/README.md`](./docs/history/v1.2-trajectory-prediction/README.md) — v1.2 closure summary (offline GRU validation, ONNX inference sidecar, `TrajectoryPredictionPort` integration, replay acceptance, ADR mapping).
2. [`docs/history/v1.1-graphrag/README.md`](./docs/history/v1.1-graphrag/README.md) — v1.1-graphrag closure summary (historical case GraphRAG sidecar, Java adapter, advisory evidence, evaluation track, ADR mapping).
3. [`docs/history/v1.0/README.md`](./docs/history/v1.0/README.md) — v1.0 closure summary (goals, completed tracks, system increments, ADR mapping).
4. [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) and [`docs/EVENT_SCHEMA.md`](./docs/EVENT_SCHEMA.md) — current truth that absorbed v1.0–v1.2 outputs.
5. [`docs/ADR_AND_REVIEW_FINDINGS.md`](./docs/ADR_AND_REVIEW_FINDINGS.md) — the stable decisions worth narrating (including ADR-013 for v1.1 GraphRAG and ADR-014/015 for v1.2 learned prediction).

Per-step files under `docs/history/v1.0/{agent,hydrology,weather,visual,bugfix}/`, `docs/history/v1.1-graphrag/` and `docs/history/v1.2-trajectory-prediction/` are historical process records and are **not** current truth.

## 3. Where To Read Active Work

- [`docs/README.md`](./docs/README.md) — full document map.
- [`docs/TODO.md`](./docs/TODO.md) — backlog and engineering debt not bound to any milestone.
- [`CURRENT_SYSTEM_OVERVIEW.md`](./CURRENT_SYSTEM_OVERVIEW.md) — task-to-file navigation across modules.

## 4. Update Rule

- This file changes only when the active milestone or the mainline track changes, or when the headline summary in §1–§2 has clearly drifted.
- Do not maintain a step-by-step status table here.
- Status vocabulary used by step files: `planning`, `active`, `pending review`, `completed`, `archived`.

## 5. First-Stop Reading Order

1. [`README.md`](./README.md) — project one-liner.
2. This file — milestone-level state.
3. [`docs/README.md`](./docs/README.md) — full document map (truth / decisions / planning / history).
4. [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) and [`docs/EVENT_SCHEMA.md`](./docs/EVENT_SCHEMA.md) — current truth on architecture and protocol.
5. [`docs/history/v1.2-trajectory-prediction/README.md`](./docs/history/v1.2-trajectory-prediction/README.md) — what shipped in v1.2 (historical closure summary, not current truth).
