from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class GraphRagConfig:
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    embed_model: str
    working_dir: Path
    default_top_k: int
    default_mode: str
    embedding_dim: int


def _int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return default
    return int(raw)


def require_api_key(config: GraphRagConfig) -> None:
    if not config.llm_api_key:
        raise RuntimeError(
            "GRAPHRAG_LLM_API_KEY must be set: the service requires a remote "
            "LLM/embedding provider and does not run in a keyless mode"
        )


def load_config() -> GraphRagConfig:
    return GraphRagConfig(
        llm_base_url=os.getenv("GRAPHRAG_LLM_BASE_URL", "https://open.bigmodel.cn/api/paas/v4"),
        llm_api_key=os.getenv("GRAPHRAG_LLM_API_KEY", ""),
        llm_model=os.getenv("GRAPHRAG_LLM_MODEL", "glm-4-flash"),
        embed_model=os.getenv("GRAPHRAG_EMBED_MODEL", "embedding-3"),
        working_dir=Path(os.getenv("GRAPHRAG_WORKING_DIR", "runtime/index")),
        default_top_k=_int_env("GRAPHRAG_DEFAULT_TOP_K", 5),
        default_mode=os.getenv("GRAPHRAG_DEFAULT_MODE", "local"),
        embedding_dim=_int_env("GRAPHRAG_EMBED_DIM", 2048),
    )
