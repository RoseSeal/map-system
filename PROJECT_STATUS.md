# Project Status

> Last updated: 2026-05-07
> Role: Pointer-style status entry. Detailed step-level progress is owned by each milestone's plan and step documents, not duplicated here.

## 1. Current Baseline

- Latest completed milestone: `v1.0` — closed 2026-05-07.
- Current phase: **post-v1.0 stabilization**. No named milestone is currently active.
- Next milestone planning has not yet been opened. Add the new active pointer here when it starts.

## 2. v1.0 Closure Reference

v1.0 has been archived in full. Read in this order to understand what shipped:

1. [`docs/history/v1.0/README.md`](./docs/history/v1.0/README.md) — v1.0 closure summary (goals, completed tracks, system increments, ADR mapping).
2. [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) and [`docs/EVENT_SCHEMA.md`](./docs/EVENT_SCHEMA.md) — current truth that absorbed v1.0 outputs.
3. [`docs/ADR_AND_REVIEW_FINDINGS.md`](./docs/ADR_AND_REVIEW_FINDINGS.md) — the v1.0-era decisions worth narrating (ADR-007 through ADR-012).

Per-step files under `docs/history/v1.0/{agent,hydrology,weather,visual,bugfix}/` are historical process records and are **not** current truth.

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
5. [`docs/history/v1.0/README.md`](./docs/history/v1.0/README.md) — what shipped in v1.0 (historical closure summary, not current truth).
