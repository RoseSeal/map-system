# GraphRAG Historical Case Service

This sidecar builds and serves a LightRAG index over synthetic maritime cases for map-system v1.1.

## Prerequisites

`GRAPHRAG_LLM_API_KEY` must be set (see `.env.example`). Both the index build and the service fail fast without it; there is no keyless fallback mode.

## Build the Index

From this directory:

```bash
python -m graphrag_service.build_index --cases-dir data/cases --index-dir runtime/index --reset
```

The command writes `runtime/index/catalog.json` and `runtime/index/manifest.json`. The `runtime/` directory is ignored by git and can be rebuilt from `data/cases/*.md`.

## Run the Service

From the repository root:

```bash
docker compose up graphrag
```

The service exposes:

- `GET /health`
- `POST /retrieve`

Example request:

```bash
curl -sS http://localhost:8100/retrieve \
  -H 'Content-Type: application/json' \
  -d '{"situation":{"own_ship_role":"GIVE_WAY","encounter_type":"CROSSING","water_area":"限制水域","visibility":"RESTRICTED_VISIBILITY","risk_level":"WARNING"},"mode":"local","top_k":3}'
```
