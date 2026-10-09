"""The caps and the refusal paths: size, frame count, action vocabulary, extra fields.

Two rules from CONTRACT.md §2 dominate here:

* an oversized request is answered *before* the JSON parser sees it, and
* a request this service cannot interpret is a 422, never a defaulted score.

Settings overrides in this module use the **environment name spellings**
(``MAX_FRAMES``, not ``max_frames``) because ``Settings`` declares those as validation
aliases: passing the Python field name would be silently ignored by ``extra="ignore"``.
"""

from __future__ import annotations

import asyncio
import json
import uuid
from typing import Any

import pytest
from synthetic import live_clip

FRAME = "aGVsbG8td29ybGQ="  # "hello-world", a valid but non-image base64 blob


def new_id() -> str:
    return str(uuid.uuid4())


class TestRequestBodyCap:
    def test_an_oversized_declared_body_is_rejected_before_parsing(self, client_factory, body):
        client = client_factory(MAX_REQUEST_BYTES=2048)
        frames = [FRAME * 400]  # ~6 KB, well past the 2 KB cap

        response = client.post("/v1/liveness", json=body(frames))

        assert response.status_code == 413
        payload = response.json()
        assert payload["code"] == "REQUEST_TOO_LARGE"
        assert payload["retryable"] is False
        assert payload["maxRequestBytes"] == 2048
        assert payload["bodyBytes"] > 2048
        # The frame bytes must not come back, and the parse must never have happened.
        assert FRAME not in json.dumps(payload)

    def test_the_cap_leaves_a_normal_request_alone(self, client_factory, body):
        client = client_factory(MAX_REQUEST_BYTES=2048)

        response = client.post("/v1/liveness", json=body([FRAME]))

        assert response.status_code == 422
        assert response.json()["code"] != "REQUEST_TOO_LARGE"

    def test_a_body_that_only_exceeds_the_cap_while_streaming_is_rejected(self):
        from app.middleware import RequestContextMiddleware

        sent: list[dict[str, Any]] = []

        async def dummy_app(scope, receive, send):  # pragma: no cover - must not run
        # A request that trips the cap must never reach the application.
            raise AssertionError("oversized streamed body reached the app")

        async def receive_chunk(chunks: list[bytes]):
            for index, chunk in enumerate(chunks):
                yield {
                    "type": "http.request",
                    "body": chunk,
                    "more_body": index < len(chunks) - 1,
                }

        async def drive():
            middleware = RequestContextMiddleware(dummy_app, max_request_bytes=64)
            scope = {
                "type": "http",
                "asgi": {"version": "3.0"},
                "http_version": "1.1",
                "method": "POST",
                "path": "/v1/liveness",
                "raw_path": b"/v1/liveness",
                "query_string": b"",
                "headers": [],
                "client": ("127.0.0.1", 1),
                "server": ("test", 80),
                "scheme": "http",
            }

            async def send(message):
                sent.append(message)

            chunks = [b"x" * 40, b"y" * 40]
            await middleware(scope, receive_chunk(chunks).__anext__, send)

        asyncio.run(drive())

        start = next(message for message in sent if message["type"] == "http.response.start")
        assert start["status"] == 413
        payload = json.loads(next(
            message["body"] for message in sent if message["type"] == "http.response.body"
        ))
        assert payload["code"] == "REQUEST_TOO_LARGE"


class TestFrameRules:
    def test_more_frames_than_max_frames_is_a_422(self, client_factory, body):
        client = client_factory(MAX_FRAMES=2)
        frames = live_clip(n=3)

        response = client.post("/v1/liveness", json=body(frames))

        assert response.status_code == 422
        payload = response.json()
        assert payload["code"] == "FRAME_COUNT_INVALID"
        assert payload["maxFrames"] == 2
        assert payload["received"] == 3

    def test_a_frame_over_the_per_frame_ceiling_is_rejected(self, client_factory, body):
        client = client_factory(MAX_FRAME_BYTES=64)

        response = client.post("/v1/liveness", json=body([FRAME, FRAME * 20]))

        assert response.status_code == 422
        payload = response.json()
        assert payload["code"] == "FRAME_COUNT_INVALID"
        assert payload["oversizedFrameIndexes"] == [1]

    def test_a_blank_frame_is_rejected_by_validation(self, client, body):
        response = client.post("/v1/liveness", json=body(["   "]))

        assert response.status_code == 422
        assert response.json()["code"] == "PAYLOAD_INVALID"


class TestChallengeVocabulary:
    def test_an_unknown_expected_action_is_refused(self, client, body):
        response = client.post(
            "/v1/liveness", json=body(live_clip(n=1), expectedActions=["SIT_DOWN"])
        )

        assert response.status_code == 422
        payload = response.json()
        assert payload["code"] == "EXPECTED_ACTION_UNKNOWN"
        assert payload["unknownActions"] == ["SIT_DOWN"]
        assert "BLINK" in payload["allowedActions"]

    def test_every_action_the_verifier_can_issue_is_accepted(self, client, body):
        """The scorer must accept the issuer's whole pool or real attempts die here."""
        from app.config import DEFAULT_ALLOWED_ACTIONS

        response = client.post(
            "/v1/liveness", json=body(live_clip(n=1), expectedActions=list(DEFAULT_ALLOWED_ACTIONS))
        )

        assert response.status_code == 200


class TestRequestShape:
    def test_an_identity_field_cannot_be_smuggled_into_a_scoring_call(self, client, body):
        frames = live_clip(n=1)
        response = client.post(
            "/v1/liveness", json=body(frames, userId="900719925474", nationalId="1234567890126")
        )

        assert response.status_code == 422
        payload = response.json()
        assert payload["code"] == "PAYLOAD_INVALID"
        # Closed schemas plus sanitised details: neither the smuggled value nor the frame
        # may come back.
        assert "900719925474" not in json.dumps(payload)
        assert frames[0][:24] not in json.dumps(payload)

    @pytest.mark.parametrize(
        "request_id",
        ["short", "has spaces inside it", "'; DROP TABLE attempts;--", "x" * 200],
    )
    def test_a_request_id_outside_the_safe_shape_is_rejected(self, client, body, request_id):
        response = client.post("/v1/liveness", json=body(live_clip(n=1), requestId=request_id))

        assert response.status_code == 422
        assert response.json()["code"] == "PAYLOAD_INVALID"


class TestErrorAndHeaderShapes:
    def test_every_response_echoes_a_request_id(self, client, body):
        sent = "trace-me-4812"

        response = client.post(
            "/v1/liveness", json=body(live_clip(n=1)), headers={"X-Request-Id": sent}
        )

        assert response.headers["X-Request-Id"] == sent

    def test_a_caller_supplied_id_is_sanitised_before_it_is_loggable(self, client, body):
        response = client.post(
            "/v1/liveness",
            json=body(live_clip(n=1)),
            headers={"X-Request-Id": 'at"tempt\\1'},
        )

        assert response.headers["X-Request-Id"] == "attempt1"

    def test_a_missing_id_is_generated_not_left_blank(self, client, body):
        response = client.post("/v1/liveness", json=body(live_clip(n=1)))

        assert response.headers["X-Request-Id"]

    def test_an_error_body_always_has_the_four_contract_fields(self, client):
        response = client.post("/v1/liveness", json={"nope": True})

        payload = response.json()
        assert {"code", "message", "requestId", "retryable"} <= set(payload)

    def test_an_unknown_route_gets_the_contract_error_shape(self, client):
        response = client.get("/v1/not-a-real-endpoint")

        assert response.status_code == 404
        payload = response.json()
        assert payload["code"] == "NOT_FOUND"
        assert payload["retryable"] is False
        assert payload["requestId"]

    def test_a_get_on_a_post_only_endpoint_is_not_a_500(self, client):
        response = client.get("/v1/liveness")

        assert response.status_code == 405
        assert "code" in response.json()
