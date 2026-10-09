"""The privacy invariant the README claims: this process never writes an image anywhere.

Three independent checks, because a single one is easy to route around:

1. **nothing lands on disk** — the service tree is byte-for-byte the same file listing
   before and after a burst of real scoring calls;
2. **nothing is logged** — no frame's base64 (or any long run of its characters) appears in
   a log record;
3. **nothing leaves in a response** — every response is floats, booleans, ids and versions.
"""

from __future__ import annotations

import pathlib
import re
from typing import Any

import pytest
from synthetic import live_clip, screen_clip

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP_DIR = ROOT / "app"

#: Tooling artefacts that are not this service writing data: bytecode caches and virtualenvs
#: are created by the interpreter running the tests, not by a request.
_IGNORED_PARTS = {".venv", "__pycache__", ".git", ".pytest_cache", ".mypy_cache", "node_modules"}


def tree_listing() -> set[str]:
    entries: set[str] = set()
    for path in ROOT.rglob("*"):
        if any(part in _IGNORED_PARTS for part in path.relative_to(ROOT).parts):
            continue
        entries.add(f"{path.relative_to(ROOT)}|{path.stat().st_size}")
    return entries


def source_text() -> str:
    return "\n".join(
        path.read_text(encoding="utf-8") for path in sorted(APP_DIR.rglob("*.py"))
    )


class TestNothingIsWritten:
    def test_a_burst_of_scoring_calls_creates_no_files(self, client, body):
        before = tree_listing()
        for frames in (live_clip(), screen_clip()):
            assert client.post("/v1/liveness", json=body(frames)).status_code == 200
            assert client.post(
                "/v1/verify", json=body(frames, image=frames[0], referenceImage=frames[1])
            ).status_code == 200
            assert client.post(
                "/v1/embedding", json={"requestId": body(frames)["requestId"], "image": frames[0]}
            ).status_code == 200

        assert tree_listing() == before

    def test_the_source_never_opens_a_file_for_writing(self):
        text = source_text()

        # Image.open() is fine (it reads bytes already in memory); nothing may *create* a file.
        for forbidden in (
            "tempfile",
            "NamedTemporaryFile",
            "shutil",
            ".save(",
            "write_text",
            "write_bytes",
            "mkdir",
            "os.fdopen",
            '"wb"',
            "'wb'",
            '"w+b"',
            "'w+b'",
            '"ab"',
        ):
            assert forbidden not in text, f"app/ must not use {forbidden!r}"

    def test_the_only_log_sink_is_stderr(self):
        """configure_logging installs a StreamHandler and nothing else."""
        text = source_text()

        assert "FileHandler" not in text
        assert "logging.handlers" not in text


class TestNothingIsLogged:
    def test_frame_bytes_never_appear_in_a_log_line(self, client, body, caplog):
        frames = live_clip()
        marker = frames[0][8:40]

        with caplog.at_level("INFO"):
            client.post("/v1/liveness", json=body(frames))

        rendered = "\n".join(record.getMessage() for record in caplog.records)
        assert marker not in rendered
        assert frames[0] not in rendered

    def test_a_rejected_payload_does_not_echo_its_own_bytes(self, client, body):
        frames = live_clip(n=1)
        marker = frames[0][8:40]

        response = client.post("/v1/liveness", json=body(frames, expectedActions=["WAVE"]))

        assert response.status_code == 422
        assert marker not in response.text

    def test_no_person_field_is_ever_named_in_a_log_line(self, client, body, caplog):
        with caplog.at_level("INFO"):
            client.post("/v1/liveness", json=body(live_clip(n=1)))

        rendered = "\n".join(record.getMessage() for record in caplog.records).lower()
        for forbidden in ("nationalid", "phone", "facebox", "image", "frame0", "bytes64"):
            assert forbidden not in rendered


class TestNothingLeavesTheProcess:
    @pytest.mark.parametrize("path", ["/v1/liveness", "/v1/verify"])
    def test_responses_carry_only_numbers_ids_and_versions(
        self, client, body, path
    ):
        frames = live_clip()
        payload: dict[str, Any] = (
            body(frames)
            if path == "/v1/liveness"
            else body(frames, image=frames[0], referenceImage=frames[1])
        )

        response = client.post(path, json=payload)

        assert response.status_code == 200
        body_json = response.json()
        assert set(body_json) <= {
            "requestId",
            "attackScore",
            "similarity",
            "signals",
            "modelVersion",
            "livenessModelVersion",
            "embeddingModelVersion",
        }
        # An embedding response legitimately returns a float vector; nothing else may look
        # like a base64 blob.
        for key, value in body_json.items():
            if key == "embedding":
                continue
            assert not isinstance(value, str) or len(value) < 128, key

    def test_a_response_is_never_a_reversible_image_blob(self, client):
        frame = live_clip(n=1)[0]

        embedding = client.post(
            "/v1/embedding", json={"requestId": "aaaaaaaaaaaa1111", "image": frame}
        ).json()["embedding"]

        assert all(isinstance(value, float) for value in embedding)
        assert len(embedding) == 512


class TestNoPerRequestState:
    def test_the_same_frames_score_the_same_way_twice(self, client, body):
        frames = live_clip()
        first = client.post("/v1/liveness", json=body(frames)).json()
        second = client.post("/v1/liveness", json=body(frames)).json()

        assert first["attackScore"] == second["attackScore"]
        assert first["signals"] == second["signals"]

    def test_a_previous_request_cannot_bleed_into_the_next(self, client, body):
        """Alternate an attack clip and a live clip: the live score must not drift."""
        attack = client.post("/v1/liveness", json=body(screen_clip())).json()["attackScore"]
        live = client.post("/v1/liveness", json=body(live_clip())).json()["attackScore"]
        attack_again = client.post("/v1/liveness", json=body(screen_clip())).json()["attackScore"]
        live_again = client.post("/v1/liveness", json=body(live_clip())).json()["attackScore"]

        assert (attack, live) == (attack_again, live_again)
        assert attack > live

    def test_a_rejected_request_leaves_no_trace_the_next_request_can_read(
        self, client, body
    ):
        client.post("/v1/liveness", json=body(live_clip(), expectedActions=["WAVE"]))

        response = client.post("/v1/liveness", json=body(live_clip()))

        assert response.status_code == 200


def test_the_service_has_no_configuration_that_writes_anywhere():
    """No settings knob may point at a filesystem location that stores captured traffic."""
    from app.config import Settings

    fields = set(Settings.model_fields)
    allowed_path_fields = {"liveness_model_dir", "embedding_model_dir"}
    path_like = {
        name
        for name in fields
        if re.search(r"(dir|path|file|output|cache)$", name)
    }

    assert path_like <= allowed_path_fields, (
        f"new writable location in settings: {sorted(path_like - allowed_path_fields)}"
    )
