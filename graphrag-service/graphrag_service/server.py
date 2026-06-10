from __future__ import annotations

import json
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .config import load_config, require_api_key
from .retriever import RetrieveError, RetrieveRequest, retrieve


class RetrieveBody(BaseModel):
    query: str | None = None
    situation: dict[str, Any] | None = None
    mode: str | None = None
    top_k: int | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    require_api_key(load_config())
    yield


app = FastAPI(title="map-system GraphRAG service", lifespan=lifespan)


@app.exception_handler(RetrieveError)
async def retrieve_error_handler(_, exc: RetrieveError) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"error": exc.error})


@app.get("/health")
async def health() -> JSONResponse:
    config = load_config()
    manifest_path = config.working_dir / "manifest.json"
    if not manifest_path.exists():
        return JSONResponse(status_code=503, content={"status": "index_missing"})
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    return JSONResponse(
        status_code=200,
        content={
            "status": "ok",
            "cases_indexed": manifest.get("cases_indexed", 0),
            "built_at": manifest.get("built_at"),
            "corpus_hash": manifest.get("corpus_hash"),
        },
    )


@app.post("/retrieve")
async def retrieve_endpoint(body: RetrieveBody) -> dict[str, Any]:
    req = RetrieveRequest(
        query=body.query,
        situation=body.situation,
        mode=body.mode,
        top_k=body.top_k,
    )
    return await retrieve(req)
