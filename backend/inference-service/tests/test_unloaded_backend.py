"""The single most important rule: an unloaded model refuses instead of scoring.

``unloaded_client`` is the ``conftest`` seam — the same heuristic code reporting that
nothing is resident. A default score out of this service would silently become a real
decision downstream, so every scoring path must answer 503 and carry no numbers at all.
"""

from __future__ import annotations

import pytest
from conftest import make_settings
from synthetic import live_clip

SCORING_PATHS = ("/v1/liveness", "/v1/embedding", "/v1/verify")


@pytest.fixture
def frames() -> list[str]:
    return live_clip(n=1)


def valid_body_for(path: str, body, frames: list[str]) -> dict:
    """The minimal *valid* body for each scoring endpoint.

    Valid on every axis except the one under test: if the request itself were malformed we
    would be proving a 422, not the 503 refusal.
    """
    if path == "/v1/liveness":
        return body(frames)
    if path == "/v1/embedding":
        return {"requestId": body(frames)["requestId"], "image": frames[0]}
    return body(frames, image=frames[0], referenceImage=frames[0])


class TestScoringRefusal:
    @pytest.mark.parametrize("path", SCORING_PATHS)
    def test_an_unloaded_backend_answers_503(self, unloaded_client, body, frames, path):
        response = unloaded_client.post(path, json=valid_body_for(path, body, frames))

        assert response.status_code == 503
        error = response.json()
        assert error["code"] == "MODEL_NOT_LOADED"
        assert error["retryable"] is True
        assert error["backend"] == "stub-unloaded"
        assert error["requestId"]

    @pytest.mark.parametrize("path", SCORING_PATHS)
    def test_the_refusal_carries_no_scores_at_all(self, unloaded_client, body, frames, path):
        response = unloaded_client.post(path, json=valid_body_for(path, body, frames))

        payload = response.json()
        # Not "attackScore": 0.0, not a neutral 0.5 — nothing a caller could mistake for a
        # measurement.
        for forbidden in ("attackScore", "similarity", "embedding", "signals"):
            assert forbidden not in payload

    @pytest.mark.parametrize("path", SCORING_PATHS)
    def test_the_refusal_is_logged_not_silently_swallowed(
        self, unloaded_client, body, frames, path, caplog
    ):
        with caplog.at_level("ERROR"):
            unloaded_client.post(path, json=valid_body_for(path, body, frames))

        assert any("MODEL_NOT_LOADED" in record.getMessage() for record in caplog.records)


class TestReadinessSurface:
    def test_health_reports_the_process_up_and_the_model_absent(self, unloaded_client):
        response = unloaded_client.get("/health")

        assert response.status_code == 503
        payload = response.json()
        assert payload["status"] == "unavailable"
        assert payload["modelLoaded"] is False
        assert payload["reason"] == "MODEL_NOT_LOADED"
        assert payload["livenessModelVersion"] is None
        assert payload["backend"] == "stub-unloaded"

    def test_ready_is_503_too(self, unloaded_client):
        response = unloaded_client.get("/health/ready")

        assert response.status_code == 503
        assert response.json()["status"] == "not_ready"

    def test_liveness_probe_stays_green_without_a_model(self, unloaded_client):
        """"Alive" must not imply "able to score": a bad model must stop traffic, not loop."""
        response = unloaded_client.get("/health/live")

        assert response.status_code == 200
        assert response.json() == {"status": "alive", "process": "inference-service"}

    def test_the_contract_spelling_of_health_agrees(self, unloaded_client):
        assert unloaded_client.get("/v1/health").status_code == 503
        assert unloaded_client.get("/v1/health/ready").status_code == 503

    def test_skip_model_load_reproduces_the_same_refusal_on_a_real_backend(self, client_factory):
        """The ops escape hatch: start without models and get the 503 behaviour."""
        from app.models import build_backend

        backend = build_backend(make_settings(INFERENCE_SKIP_MODEL_LOAD=True))
        client = client_factory(backend=backend)

        response = client.get("/health")

        assert response.status_code == 503


class TestLoadedCounterpart:
    @pytest.mark.parametrize("path", SCORING_PATHS)
    def test_the_same_requests_succeed_once_a_model_is_resident(
        self, client, body, frames, path
    ):
        response = client.post(path, json=valid_body_for(path, body, frames))

        assert response.status_code == 200, response.json()

    def test_health_is_green_when_loaded(self, client):
        response = client.get("/health")

        assert response.status_code == 200
        payload = response.json()
        assert payload["modelLoaded"] is True
        assert payload["livenessModelVersion"].startswith("liveness-stub-")
        assert payload["embeddingModelVersion"].startswith("embedding-stub-")

    def test_the_backend_is_one_object_for_the_life_of_the_app(self, client, body, frames):
        """No per-request model swap: two calls must hit the same resident instance."""
        first = client.post("/v1/liveness", json=body(frames))
        second = client.post("/v1/liveness", json=body(frames))

        assert first.status_code == second.status_code == 200
        backend = client.app.state.backend
        assert backend.loaded is True
