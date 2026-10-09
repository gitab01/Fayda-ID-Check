"""Backend registry: the one place where ``INFERENCE_BACKEND`` is interpreted.

``build_backend`` returns *only* the requested backend. There is no try-tf-except-stub:
a misconfigured production process must exit, not serve heuristic scores.

Both backend modules are import-safe on Python 3.14 — TensorFlow is imported inside
``TfBackend._tf()``, i.e. at load time, never at module import time. ``tests`` assert
that property directly.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..errors import BackendConfigurationError
from .base import (
    EMBEDDING_DIM,
    InferenceBackend,
    LivenessResult,
    LivenessSignals,
    similarity,
)

if TYPE_CHECKING:  # pragma: no cover - typing only, keeps runtime imports lazy
    from ..config import Settings
    from .stub_backend import StubBackend
    from .tf_backend import TfBackend

__all__ = [
    "EMBEDDING_DIM",
    "InferenceBackend",
    "LivenessResult",
    "LivenessSignals",
    "StubBackend",
    "TfBackend",
    "build_backend",
    "similarity",
]


def build_backend(settings: "Settings", *, load: bool = True) -> InferenceBackend:
    """Instantiate (and by default load) the configured backend.

    The branch-local imports are the whole swappable-backend design: choosing ``stub``
    never evaluates ``import tensorflow``, and choosing ``tf`` never reaches the stub.
    """
    requested = (settings.inference_backend or "").strip().lower()

    if requested == "stub":
        from .stub_backend import StubBackend as backend_class
    elif requested == "tf":
        from .tf_backend import TfBackend as backend_class
    else:
        raise BackendConfigurationError(
            f"unknown INFERENCE_BACKEND={settings.inference_backend!r}; expected 'tf' or "
            "'stub'. Refusing to guess, because the two backends make very different "
            "guarantees.",
            detail={"requested": settings.inference_backend},
        )

    backend: InferenceBackend = backend_class(settings)
    if load and not settings.skip_model_load:
        backend.load()
    return backend


def __getattr__(name: str) -> object:
    """Expose the concrete backends without importing them eagerly."""
    if name == "StubBackend":
        from .stub_backend import StubBackend

        return StubBackend
    if name == "TfBackend":
        from .tf_backend import TfBackend

        return TfBackend
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
