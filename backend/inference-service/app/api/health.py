"""Health, readiness and liveness probes (CONTRACT.md §2 "GET /health").

Three distinct answers on purpose:

``/health``
    the contract's endpoint: 200 with ``modelLoaded`` + both version stamps, **503 when
    the process is up but a model is not loaded**.
``/health/ready``
    the same readiness question in load-balancer shape (used by the container healthcheck
    in docker-compose.yml).
``/health/live``
    pure process liveness: if FastAPI can answer, the process is alive. It must *not*
    depend on model state, otherwise a bad model would cause a restart loop instead of a
    clean 503 that stops traffic.
"""

from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from ..models.base import InferenceBackend
from ..pipeline import get_backend
from ..schemas import HealthResponse, LivenessProbeResponse, ReadinessResponse

router = APIRouter(tags=["health"])


def _health(backend: InferenceBackend) -> HealthResponse:
    loaded = bool(backend.loaded)
    return HealthResponse(
        status="ok" if loaded else "unavailable",
        model_loaded=loaded,
        liveness_model_version=backend.liveness_model_version if loaded else None,
        embedding_model_version=backend.embedding_model_version if loaded else None,
        backend=backend.name,
        reason=None if loaded else "MODEL_NOT_LOADED",
    )


def _respond(model: HealthResponse | ReadinessResponse | LivenessProbeResponse, status: int) -> JSONResponse:
    return JSONResponse(status_code=status, content=model.model_dump(by_alias=True))


@router.get("/health", response_model=HealthResponse, responses={503: {"description": "model not loaded"}})
def health(request: Request) -> JSONResponse:
    backend: InferenceBackend = get_backend(request)
    model = _health(backend)
    return _respond(model, 200 if model.model_loaded else 503)


@router.get("/health/ready", response_model=ReadinessResponse, responses={503: {"description": "not ready"}})
def ready(request: Request) -> JSONResponse:
    backend: InferenceBackend = get_backend(request)
    loaded = bool(backend.loaded)
    model = ReadinessResponse(
        status="ready" if loaded else "not_ready",
        model_loaded=loaded,
        backend=backend.name,
        reason=None if loaded else "MODEL_NOT_LOADED",
    )
    return _respond(model, 200 if loaded else 503)


@router.get("/health/live", response_model=LivenessProbeResponse)
def live(request: Request) -> JSONResponse:
    # Deliberately independent of model state: "alive" must not imply "able to score".
    _ = request
    model = LivenessProbeResponse(status="alive", process="inference-service")
    return _respond(model, 200)
