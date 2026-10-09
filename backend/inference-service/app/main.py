"""Application factory and process wiring.

Run it with ``uvicorn app.main:app``. ``create_app()`` is public so tests (and a future
second instance behind a different model version) can build an app with explicit settings
and an explicit backend instead of monkeypatching globals.

What lives here:

* backend selection via :func:`app.models.build_backend` — one object on ``app.state``,
  chosen once at startup, never swapped per request;
* the request middleware (``X-Request-Id`` echo + 8 MB cap);
* exception handlers that give every failure the contract's
  ``{code, message, requestId, retryable}`` shape, with pydantic errors sanitised so an
  oversized base64 frame can never be echoed back into a response or a log line.
"""

from __future__ import annotations

import logging
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from .api import health_router, inference_router
from .config import Settings, get_settings
from .errors import InferenceServiceError, PayloadTooLargeError
from .middleware import (
    ACCESS_LOG_DATE_FORMAT,
    ACCESS_LOG_FORMAT,
    RequestContextMiddleware,
    RequestIdFilter,
    request_id_of,
)
from .models import build_backend
from .models.base import InferenceBackend

logger = logging.getLogger("app.inference")


#: The one handler this module owns; tracked so a second create_app() never clears
#: handlers it did not install (pytest's caplog handler included).
_logging_handler: logging.Handler | None = None


def configure_logging(level: str = "INFO") -> None:
    """stderr-only JSON-free single-line logs, every record tagged with the request id.

    No file handler, by design: writing logs to a container filesystem is fine, but a
    log file is still a filesystem write and this service's statelessness guarantee is
    easier to hold when the only sink is stderr.
    """
    global _logging_handler

    root = logging.getLogger()
    resolved_level = getattr(logging, level.upper(), logging.INFO)

    if _logging_handler is not None and _logging_handler in root.handlers:
        root.setLevel(resolved_level)
        return

    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(logging.Formatter(ACCESS_LOG_FORMAT, datefmt=ACCESS_LOG_DATE_FORMAT))
    handler.addFilter(RequestIdFilter())
    root.addHandler(handler)
    root.setLevel(resolved_level)
    _logging_handler = handler

    # uvicorn's access log would duplicate ours; let its records propagate here.
    uvicorn_access = logging.getLogger("uvicorn.access")
    uvicorn_access.handlers = [h for h in uvicorn_access.handlers if h is not handler]
    uvicorn_access.propagate = True


def _validation_detail(exc: RequestValidationError) -> list[dict[str, str]]:
    """Sanitised view of a validation error: location + reason, never the offending value.

    The default pydantic payload would echo the whole base64 frame back to the caller and
    into the log — a request body of image bytes must not become a response body.
    """
    safe: list[dict[str, str]] = []
    for error in exc.errors():
        location = ".".join(str(part) for part in error.get("loc", ()))
        safe.append({"location": location, "reason": str(error.get("type", "invalid"))})
    return safe


def create_app(
    settings: Settings | None = None,
    *,
    backend: InferenceBackend | None = None,
) -> FastAPI:
    """Build the service.

    ``backend`` is the test seam: pass an unloaded backend to prove the 503 refusal path
    without having to break a real model.
    """
    resolved = settings or get_settings()
    configure_logging(resolved.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        active: InferenceBackend = app.state.backend
        logger.info("startup %s", active.describe())
        logger.info("config %s", resolved.describe())
        if not active.loaded:
            logger.error(
                "startup backend=%s loaded=false — /health and /health/ready will answer "
                "503 until a model is resident",
                active.name,
            )
        yield
        logger.info("shutdown backend=%s", active.name)

    app = FastAPI(
        title="Fayda-ID Check inference service",
        version=__version__,
        description="Liveness and face-embedding scoring. Stateless, no identity, no storage.",
        lifespan=lifespan,
    )
    app.state.settings = resolved
    # build_backend() raising here is intentional: a misconfigured INFERENCE_BACKEND must
    # stop the process at startup, not degrade into another backend's scores.
    app.state.backend = backend if backend is not None else build_backend(resolved)

    app.add_middleware(
        RequestContextMiddleware,
        max_request_bytes=resolved.max_request_bytes,
        request_id_header=resolved.request_id_header,
    )

    @app.exception_handler(InferenceServiceError)
    async def handle_service_error(request: Request, exc: InferenceServiceError) -> JSONResponse:
        request_id = request_id_of(request.scope)
        log = logger.error if exc.is_fault else logger.warning
        log("failed request_id=%s code=%s status=%s", request_id, exc.code, exc.status_code)
        return JSONResponse(
            status_code=exc.status_code,
            content=exc.to_payload(request_id),
            headers={"X-Request-Id": request_id},
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        request_id = request_id_of(request.scope)
        error = InferenceServiceError(
            "request payload failed validation",
            code="PAYLOAD_INVALID",
            status_code=422,
            retryable=False,
            detail={"errors": _validation_detail(exc)},
        )
        error.is_fault = False
        logger.warning("rejected request_id=%s code=%s", request_id, error.code)
        return JSONResponse(
            status_code=error.status_code,
            content=error.to_payload(request_id),
            headers={"X-Request-Id": request_id},
        )

    @app.exception_handler(PayloadTooLargeError)
    async def handle_too_large(request: Request, exc: PayloadTooLargeError) -> JSONResponse:
        # Defensive: the middleware already answers oversized bodies. Kept so a future
        # proxy that strips Content-Length cannot produce a silent 500.
        return await handle_service_error(request, exc)

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_exception(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        request_id = request_id_of(request.scope)
        error = InferenceServiceError(
            str(exc.detail),
            code="NOT_FOUND" if exc.status_code == 404 else "REQUEST_INVALID",
            status_code=exc.status_code,
            retryable=False,
        )
        error.is_fault = False
        headers = {"X-Request-Id": request_id}
        if exc.headers:
            headers.update(exc.headers)
        return JSONResponse(
            status_code=exc.status_code,
            content=error.to_payload(request_id),
            headers=headers,
        )

    app.include_router(inference_router)
    app.include_router(health_router)
    # CONTRACT.md §2 lists /health under the /v1 base; answer on both spellings so the
    # load balancer (/health/ready) and the contract (/v1/health) both work.
    app.include_router(health_router, prefix="/v1", include_in_schema=True)
    return app


app = create_app()


def main() -> None:  # pragma: no cover - console entry point
    import uvicorn

    settings = get_settings()
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, log_level=settings.log_level.lower())


if __name__ == "__main__":  # pragma: no cover
    main()
