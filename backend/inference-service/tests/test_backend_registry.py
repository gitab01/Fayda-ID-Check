"""Backend selection: one switch, no fallback, and no eager TensorFlow import.

``INFERENCE_BACKEND`` is the whole swappable-backend design, so the failure modes matter:
a typo must stop the process, ``tf`` must not quietly become ``stub``, and choosing
``stub`` must never evaluate ``import tensorflow``.
"""

from __future__ import annotations

import sys

import pytest
from conftest import make_settings

from app.errors import BackendConfigurationError
from app.models import build_backend
from app.models.stub_backend import StubBackend


class TestBuildBackend:
    def test_stub_is_returned_loaded_and_named(self):
        backend = build_backend(make_settings(INFERENCE_BACKEND="stub"))

        assert isinstance(backend, StubBackend)
        assert backend.name == "stub"
        assert backend.loaded is True

    def test_the_switch_is_case_and_whitespace_tolerant(self):
        assert build_backend(make_settings(INFERENCE_BACKEND=" Stub ")).loaded is True

    def test_load_can_be_deferred(self):
        backend = build_backend(make_settings(INFERENCE_BACKEND="stub"), load=False)

        assert backend.loaded is False

    def test_skip_model_load_is_honoured_even_when_load_is_requested(self):
        backend = build_backend(
            make_settings(INFERENCE_BACKEND="stub", INFERENCE_SKIP_MODEL_LOAD=True)
        )

        assert backend.loaded is False

    @pytest.mark.parametrize("requested", ["torch", "onnx", "tf-lite", ""])
    def test_an_unknown_backend_stops_the_process_instead_of_guessing(self, requested):
        # ``Settings`` types this field as Literal["tf", "stub"], so an unknown name cannot
        # reach it through the constructor; the copy is what the registry itself must reject.
        settings = make_settings().model_copy(update={"inference_backend": requested})

        with pytest.raises(BackendConfigurationError) as excinfo:
            build_backend(settings, load=False)

        error = excinfo.value
        assert error.detail["requested"] == requested
        assert "tf" in str(error) and "stub" in str(error)

    def test_the_refusal_is_not_retryable_because_restarting_would_change_nothing(self):
        settings = make_settings().model_copy(update={"inference_backend": "onnx"})

        with pytest.raises(BackendConfigurationError) as excinfo:
            build_backend(settings, load=False)

        assert excinfo.value.retryable is False


class TestLazyTensorFlow:
    def test_importing_the_registry_does_not_import_tensorflow(self):
        import app.models  # noqa: F401
        import app.models.stub_backend  # noqa: F401

        assert "tensorflow" not in sys.modules

    def test_importing_the_tf_module_itself_stays_import_safe(self):
        """Python 3.14 has no TensorFlow wheels, so this module must import either way."""
        import app.models.tf_backend as tf_module

        assert tf_module.TfBackend is not None
        assert "tensorflow" not in sys.modules

    def test_the_tf_backend_only_reaches_for_tensorflow_when_it_loads(self):
        from app.models.tf_backend import TfBackend

        backend = TfBackend(make_settings(INFERENCE_BACKEND="tf"))

        assert backend.loaded is False
        assert "tensorflow" not in sys.modules

    def test_the_stub_path_never_constructs_the_tf_backend(self):
        backend = build_backend(make_settings(INFERENCE_BACKEND="stub"))

        assert type(backend).__name__ == "StubBackend"


class TestDescribeAndConfig:
    def test_describe_names_the_backend_and_its_versions(self):
        described = build_backend(make_settings(INFERENCE_BACKEND="stub")).describe()

        assert described["backend"] == "stub"
        assert described["loaded"] is True
        assert described["livenessModelVersion"].startswith("liveness-stub-")
        assert described["embeddingModelVersion"].startswith("embedding-stub-")

    def test_the_startup_config_summary_carries_no_secrets_or_paths(self):
        settings = make_settings(
            LIVENESS_MODEL_DIR="/etc/models/secret",
            EMBEDDING_MODEL_DIR="/etc/models/other",
        )

        rendered = repr(settings.describe())

        assert "/etc/models" not in rendered
        assert "secret" not in rendered

    def test_the_default_cap_is_the_contracts_8_mb(self):
        from app.config import DEFAULT_MAX_REQUEST_BYTES

        assert make_settings().max_request_bytes == DEFAULT_MAX_REQUEST_BYTES == 8 * 1024 * 1024

    def test_a_malformed_actions_override_falls_back_instead_of_emptying_the_vocabulary(self):
        settings = make_settings(ALLOWED_ACTIONS=" , ")

        assert settings.allowed_actions
