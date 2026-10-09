"""The contract's three scoring endpoints, on the wire (CONTRACT.md §2).

These run end-to-end through ``TestClient`` on purpose: the point is that the *response
shape* a caller sees is camelCase, bounded, echoed and version-stamped — which no unit test
of a backend can prove.
"""

from __future__ import annotations

import uuid

import pytest

from synthetic import live_clip, screen_clip

EMBEDDING_DIM = 512


def new_id() -> str:
    return str(uuid.uuid4())


def unit_norm(vector: list[float]) -> float:
    total = sum(value * value for value in vector)
    return total**0.5


class TestLivenessEndpoint:
    def test_returns_the_contract_shape(self, client, body):
        request_id = new_id()
        response = client.post("/v1/liveness", json=body(live_clip(), requestId=request_id))

        assert response.status_code == 200
        payload = response.json()
        assert set(payload) == {"requestId", "attackScore", "signals", "modelVersion"}
        assert payload["requestId"] == request_id
        assert set(payload["signals"]) == {"texture", "moire", "blinkExecuted"}
        assert 0.0 <= payload["attackScore"] <= 1.0
        assert payload["modelVersion"].startswith("liveness-stub-")

    def test_a_screen_replay_scores_higher_than_a_live_clip(self, client, body):
        live = client.post("/v1/liveness", json=body(live_clip())).json()["attackScore"]
        attack = client.post("/v1/liveness", json=body(screen_clip())).json()["attackScore"]

        assert attack > live

    def test_an_unparsable_frame_is_refused_not_blanked(self, client, body):
        response = client.post("/v1/liveness", json=body(["not-base64-at-all"]))

        assert response.status_code == 422
        assert response.json()["code"] == "IMAGE_DECODE_FAILED"


class TestEmbeddingEndpoint:
    def test_returns_512_unit_norm_floats(self, client):
        frame = live_clip(n=1)[0]
        response = client.post("/v1/embedding", json={"requestId": new_id(), "image": frame})

        assert response.status_code == 200
        payload = response.json()
        assert payload["modelVersion"].startswith("embedding-stub-")
        assert len(payload["embedding"]) == EMBEDDING_DIM
        assert unit_norm(payload["embedding"]) == pytest.approx(1.0, abs=1e-4)

    def test_a_face_box_crops_before_embedding(self, client):
        frame = live_clip(n=1)[0]
        whole = client.post(
            "/v1/embedding", json={"requestId": new_id(), "image": frame}
        ).json()["embedding"]
        boxed = client.post(
            "/v1/embedding",
            json={"requestId": new_id(), "image": frame, "faceBox": [40, 40, 120, 120]},
        ).json()["embedding"]

        assert whole != boxed
        assert unit_norm(boxed) == pytest.approx(1.0, abs=1e-4)

    def test_a_face_box_mostly_outside_the_image_is_rejected(self, client):
        frame = live_clip(n=1)[0]
        response = client.post(
            "/v1/embedding",
            json={"requestId": new_id(), "image": frame, "faceBox": [900, 900, 50, 50]},
        )

        assert response.status_code == 422
        assert response.json()["code"] == "FACE_BOX_INVALID"


class TestVerifyEndpoint:
    def test_returns_both_signals_in_one_round_trip(self, client, body):
        frames = live_clip()
        response = client.post(
            "/v1/verify", json=body(frames, image=frames[0], referenceImage=frames[0])
        )

        assert response.status_code == 200
        payload = response.json()
        assert set(payload) == {
            "requestId",
            "attackScore",
            "similarity",
            "signals",
            "livenessModelVersion",
            "embeddingModelVersion",
        }
        assert 0.0 <= payload["similarity"] <= 1.0

    def test_the_same_face_matches_itself(self, client, body):
        frames = live_clip()
        payload = client.post(
            "/v1/verify", json=body(frames, image=frames[0], referenceImage=frames[0])
        ).json()

        assert payload["similarity"] == pytest.approx(1.0, abs=1e-6)

    def test_a_different_face_matches_less(self, client, body):
        frames = live_clip()
        payload = client.post(
            "/v1/verify",
            json=body(frames, image=frames[0], referenceImage=screen_clip(n=1)[0]),
        ).json()

        assert payload["similarity"] < 1.0 - 1e-9

    def test_a_stored_template_is_accepted_instead_of_a_reference_image(self, client, body):
        frames = live_clip()
        template = [1.0] * EMBEDDING_DIM
        response = client.post(
            "/v1/verify",
            json=body(frames, image=frames[0], referenceEmbedding=template),
        )

        assert response.status_code == 200
        assert 0.0 <= response.json()["similarity"] <= 1.0

    def test_exactly_one_reference_form_is_required(self, client, body):
        frames = live_clip()
        both = client.post(
            "/v1/verify",
            json=body(
                frames,
                image=frames[0],
                referenceImage=frames[1],
                referenceEmbedding=[0.0] * EMBEDDING_DIM,
            ),
        )
        neither = client.post("/v1/verify", json=body(frames, image=frames[0]))

        assert both.status_code == 422
        assert neither.status_code == 422

    def test_a_template_of_the_wrong_width_is_refused(self, client, body):
        frames = live_clip()
        response = client.post(
            "/v1/verify",
            json=body(frames, image=frames[0], referenceEmbedding=[0.1] * 128),
        )

        assert response.status_code == 422
