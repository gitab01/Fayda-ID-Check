"""HTTP surface of the inference service.

Four modules, four concerns: ``health`` (readiness), ``liveness`` (attack score),
``embedding`` (face vector), ``verify`` (both in one round trip). Each one is thin: it
validates the DTO, delegates to :mod:`app.pipeline`, and formats the response.
"""

from __future__ import annotations

from fastapi import APIRouter

from . import embedding as embedding_api
from . import health as health_api
from . import liveness as liveness_api
from . import verify as verify_api
from ..models.base import InferenceBackend  # noqa: F401 - re-exported for typing convenience

inference_router = APIRouter(prefix="/v1")
inference_router.include_router(liveness_api.router)
inference_router.include_router(embedding_api.router)
inference_router.include_router(verify_api.router)

#: Health lives at the root (that is what docker-compose's healthcheck probes) and is
#: also mounted under /v1, because CONTRACT.md §2 groups it with the /v1 API.
health_router = health_api.router

__all__ = ["health_router", "inference_router"]
