"""ASGI middleware: request-id plumbing and the 8 MB body cap.

Written as a plain ASGI middleware (not ``BaseHTTPMiddleware``) for two reasons:

1. the body cap has to be enforced *while the request streams in*, before FastAPI has
   buffered or parsed anything — an oversized body must never reach the JSON parser;
2. the ``X-Request-Id`` echo has to land on *every* response, including the 413 this
   middleware short-circuits and the 503 an exception handler produces.

Nothing here logs, stores or returns a person-identifying field: only the request id,
the route, the status and score-shaped numbers.
"""

from __future__ import annotations

import contextvars
import json
import logging
import time
import uuid
from typing import Any, Awaitable, Callable

from .errors import PayloadTooLargeError

logger = logging.getLogger("app.inference")

Scope = dict[str, Any]
Receive = Callable[[], Awaitable[dict[str, Any]]]
Send = Callable[[dict[str, Any]], Awaitable[None]]

REQUEST_ID_STATE_KEY = "request_id"
_UNSAFE_METHODS_WITH_BODY = frozenset({"POST", "PUT", "PATCH", "DELETE"})

#: Log format for the whole process. `request_id` comes from RequestIdFilter below.
ACCESS_LOG_FORMAT = (
    "%(asctime)s.%(msecs)03dZ %(levelname)s %(name)s [%(request_id)s] %(message)s"
)
ACCESS_LOG_DATE_FORMAT = "%Y-%m-%dT%H:%M:%S"

#: Set for the duration of a request; read by the logging filter and by handlers.
current_request_id: contextvars.ContextVar[str] = contextvars.ContextVar(
    "current_request_id", default="-"
)


class RequestIdFilter(logging.Filter):
    """Attach the in-flight request id to every record.

    A record with no request context gets ``-`` rather than being dropped: losing a log
    line is worse for an audit than a line without an id.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = current_request_id.get()
        return True


def new_request_id() -> str:
    return str(uuid.uuid4())


def request_id_of(scope: Scope) -> str:
    """Resolve the id once per request: caller's ``X-Request-Id`` or a fresh UUID."""
    state = scope.get("state")
    if isinstance(state, dict) and state.get(REQUEST_ID_STATE_KEY):
        return str(state[REQUEST_ID_STATE_KEY])
    return new_request_id()


def _header(scope: Scope, name: bytes) -> bytes | None:
    for key, value in scope.get("headers", []) or []:
        if key.lower() == name:
            return value
    return None


def _set_header(headers: list[tuple[bytes, bytes]], name: bytes, value: bytes) -> list:
    replaced = [(key, val) for key, val in headers if key.lower() != name]
    replaced.append((name, value))
    return replaced


class RequestContextMiddleware:
    """Echo ``X-Request-Id`` and enforce ``MAX_REQUEST_BYTES``.

    ``max_request_bytes`` is a hard ceiling: the declared ``Content-Length`` is checked
    first (cheap rejection), then the streamed body is counted and the request is
    answered 413 the moment it crosses the line.
    """

    def __init__(
        self,
        app: Callable[[Scope, Receive, Send], Awaitable[None]],
        *,
        max_request_bytes: int,
        request_id_header: str = "X-Request-Id",
    ) -> None:
        self.app = app
        self.max_request_bytes = int(max_request_bytes)
        self.request_id_header = request_id_header.lower().encode("ascii")

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        scope.setdefault("state", {})
        raw_id = _header(scope, self.request_id_header)
        candidate = (raw_id or b"").decode("latin-1").strip() or new_request_id()
        # A header is caller-controlled; keep the loggable/echoable id to a safe shape.
        request_id = _sanitise(candidate)
        scope["state"][REQUEST_ID_STATE_KEY] = request_id

        token = current_request_id.set(request_id)
        status_holder = {"status": 0}
        started = time.perf_counter()

        async def send_with_id(message: Scope) -> None:
            if message["type"] == "http.response.start":
                status_holder["status"] = int(message.get("status", 0))
                message = {
                    **message,
                    "headers": _set_header(
                        list(message.get("headers", []) or []),
                        self.request_id_header,
                        request_id.encode("latin-1"),
                    ),
                }
            await send(message)

        try:
            method = str(scope.get("method", "GET")).upper()
            if method not in _UNSAFE_METHODS_WITH_BODY:
                await self.app(scope, receive, send_with_id)
                return

            declared = _header(scope, b"content-length")
            if declared is not None:
                try:
                    if int(declared) > self.max_request_bytes:
                        await self._reject(
                            scope,
                            send_with_id,
                            request_id,
                            reason="declared",
                            declared=int(declared),
                        )
                        return
                except (TypeError, ValueError):
                    pass  # malformed Content-Length: fall through to counting the real body

            body = bytearray()
            exceeded = False
            disconnected = False
            while True:
                message = await receive()
                if message["type"] == "http.disconnect":
                    disconnected = True
                    break
                body += message.get("body", b"") or b""
                if len(body) > self.max_request_bytes:
                    exceeded = True
                    break
                if not message.get("more_body", False):
                    break

            if exceeded:
                await self._reject(
                    scope,
                    send_with_id,
                    request_id,
                    reason="streamed",
                    declared=len(body),
                )
                return
            if disconnected:  # pragma: no cover - client went away mid-upload
                return

            delivered = False

            async def replay() -> Scope:
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {"type": "http.request", "body": bytes(body), "more_body": False}
                # Starlette may poll receive again after the body; say "nothing more".
                return {"type": "http.disconnect"}

            await self.app(scope, replay, send_with_id)
        finally:
            current_request_id.reset(token)
            self._log_access(scope, status_holder["status"], started, request_id)

    def _log_access(self, scope: Scope, status: int, started: float, request_id: str) -> None:
        """One line per request: route, status, duration. Never a payload, never a person."""
        duration_ms = round((time.perf_counter() - started) * 1000.0, 2)
        level = logging.WARNING if status >= 500 else logging.INFO
        logger.log(
            level,
            "request request_id=%s method=%s path=%s status=%s durationMs=%s",
            request_id,
            scope.get("method", ""),
            scope.get("path", ""),
            status or 0,
            duration_ms,
        )

    async def _reject(
        self,
        scope: Scope,
        send: Send,
        request_id: str,
        *,
        reason: str,
        declared: int,
    ) -> None:
        error = PayloadTooLargeError(
            f"request body exceeds the {self.max_request_bytes} byte ceiling",
            detail={"maxRequestBytes": self.max_request_bytes, "bodyBytes": declared},
        )
        payload = json.dumps(error.to_payload(request_id)).encode("utf-8")
        logger.warning(
            "request_id=%s rejected path=%s code=%s reason=%s limit=%d",
            request_id,
            scope.get("path", ""),
            error.code,
            reason,
            self.max_request_bytes,
        )
        await send(
            {
                "type": "http.response.start",
                "status": error.status_code,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(payload)).encode("ascii")),
                ],
            }
        )
        await send({"type": "http.response.body", "body": payload})


def _sanitise(value: str) -> str:
    """Keep a request id loggable: printable, bounded, no CR/LF (log injection)."""
    cleaned = "".join(char for char in value if 32 <= ord(char) < 127 and char not in '"\\')[:64]
    return cleaned or new_request_id()
