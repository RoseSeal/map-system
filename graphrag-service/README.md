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

## Run the Evaluation

The evaluation compares a naive vector RAG baseline with LightRAG `local`, `global`, and `hybrid` modes. Retrieval and answer generation continue to use the configured Zhipu API. The judge uses a local CLI subscription and defaults to Gemini CLI.

Prerequisites:

1. Build the index and start the sidecar.
2. Set `GRAPHRAG_LLM_API_KEY`.
3. Authenticate at least one supported judge CLI: `gemini`, `codex`, `copilot`, or `claude`.
4. Set `EVAL_JUDGE_MODEL` to the exact CLI model identifier used for the run.

Run the four-query pilot before the full evaluation:

```bash
EVAL_JUDGE_MODEL=<model-id> python -m eval.run_eval --pilot
EVAL_JUDGE_MODEL=<model-id> python -m eval.run_eval
python -m eval.report
```

Select another judge backend or pin its model when needed:

```bash
EVAL_JUDGE_BACKEND=claude EVAL_JUDGE_MODEL=sonnet python -m eval.run_eval --pilot
```

`eval/runtime/` contains ignored retrieval caches, judge outputs, and resumable progress. A complete full run atomically publishes `eval/results/scores.csv` and `eval/results/run_manifest.json`; `python -m eval.report` then writes the committed plots and `eval/results/case_study.md`. Use `--force-retrieval` or `--force-judge` to invalidate one cache layer explicitly.
