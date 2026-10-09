"""Shared test fixtures.

The seam that matters: ``create_app(settings, backend=...)`` lets a test hand the service
a backend that is *deliberately not loaded*, which is how the "an unloaded model refuses
instead of returning a default score" rule is proven without breaking a real model.
"""

from __future__ import annotations

import pathlib
import sys
import uuid
from collections.abc import Callable, Iterator

import pytest
from fastapi.testclient import TestClient

ROOT = pathlib.Path(__file__).resolve().parents[1]
TESTS = pathlib.Path(__file__).resolve().parent
for path in (str(ROOT), str(TESTS)):
    if path not in sys.path:
        sys.path.insert(0, path)

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.models.stub_backend import StubBackend  # noqa: E402

REQUEST_ID = str(uuid.uuid4())


class UnloadedStubBackend(StubBackend):
    """The same heuristic code, but reporting that nothing is resident.

    ``load()`` is neutered so no path can accidentally make it ready again.
    """

    name = "stub-unloaded"

    def load(self) -> None:  # stays unloaded on purpose
        return None

    @property
    def loaded(self) -> bool:
        return False


def make_settings(**overrides: object) -> Settings:
    """Deterministic settings: never read from the ambient environment or a stray .env."""
    values: dict[str, object] = {
        "INFERENCE_BACKEND": "stub",
        "LOG_LEVEL": "WARNING",
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)  # type: ignore[arg-type]


def new_request_id() -> str:
    return str(uuid.uuid4())


@pytest.fixture
def settings() -> Settings:
    return make_settings()


@pytest.fixture
def loaded_backend(settings: Settings) -> StubBackend:
    backend = StubBackend(settings)
    backend.load()
    return backend


@pytest.fixture
def client_factory() -> Callable[..., TestClient]:
    """Build a TestClient over an app with explicit settings and/or an explicit backend."""

    def factory(
        *, backend: StubBackend | None = None, **setting_overrides: object
    ) -> TestClient:
        app = create_app(make_settings(**setting_overrides), backend=backend)
        return TestClient(app, raise_server_exceptions=True)

    return factory


@pytest.fixture
def client(client_factory: Callable[..., TestClient]) -> Iterator[TestClient]:
    with client_factory() as test_client:
        yield test_client


@pytest.fixture
def unloaded_client(client_factory: Callable[..., TestClient]) -> Iterator[TestClient]:
    with client_factory(backend=UnloadedStubBackend(make_settings())) as test_client:
        yield test_client


@pytest.fixture
def body() -> Callable[..., dict]:
    """Build a contract-shaped request body (camelCase on the wire)."""

    def build(frames: list[str], **overrides: object) -> dict:
        payload: dict[str, object] = {
            "requestId": new_request_id(),
            "frames": list(frames),
            "expectedActions": ["TURN_LEFT", "BLINK", "TILT_RIGHT"],
            "challengeSeed": "b7f3c1",
        }
        payload.update(overrides)
        return payload

    return build
