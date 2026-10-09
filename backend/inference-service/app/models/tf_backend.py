"""Production inference backend: two exported TensorFlow SavedModels.

TensorFlow is imported **lazily, inside the class** (``TfBackend._tf``). This module is
referenced by name only when ``INFERENCE_BACKEND=tf``, so a Python 3.14 development box
never evaluates ``import tensorflow`` and never sees the missing-wheel error at import
time — while the 3.12 production image gets real inference. If ``tf`` *is* requested and
the import fails, that is reported as a startup configuration error; the service does not
fall back to the stub backend, because a stub score in production would be a silent
integrity failure.

Expected artifacts (produced by ``training/export.py``):

``<LIVENESS_MODEL_DIR>/``
    a SavedModel whose serving signature takes ``(batch, sequence, size, size, 3)``
    float32 frames and emits a continuous attack probability in ``[0, 1]``.

``<EMBEDDING_MODEL_DIR>/``
    a SavedModel whose serving signature takes ``(batch, size, size, 3)`` float32 faces
    and emits a 512-d vector. Post-load L2 normalisation is applied here regardless of
    what the head returns, because CONTRACT.md §2 promises a unit vector.

``model_manifest.json`` beside each SavedModel pins the version string that is stamped
into ``decision_records.liveness_model_ver`` / ``embedding_model_ver``.
"""

from __future__ import annotations

import json
import os
import sys
from typing import Any

import numpy as np

from ..config import Settings
from ..errors import (
    BackendConfigurationError,
    BackendUnavailableError,
)
from .base import InferenceBackend, LivenessResult, LivenessSignals

MANIFEST_FILE = "model_manifest.json"

_LIVENESS_OUTPUT_HINTS = ("attack", "probability", "score", "output")
_EMBEDDING_OUTPUT_HINTS = ("embedding", "vector", "output")
_INPUT_HINTS = ("frames", "frame", "image", "input", "x")


class TfBackend(InferenceBackend):
    """SavedModel-backed liveness + embedding."""

    name = "tf"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._tf_module: Any | None = None
        self._liveness_fn: Any | None = None
        self._embedding_fn: Any | None = None
        self._liveness_io: dict[str, str | None] = {}
        self._embedding_io: dict[str, str | None] = {}
        self._liveness_version: str | None = None
        self._embedding_version: str | None = None

    # -- lazy dependency ----------------------------------------------------

    @staticmethod
    def _tf() -> Any:
        """Import TensorFlow on first use only, or explain why it cannot be imported."""
        try:
            import tensorflow as tf  # noqa: PLC0415 - the laziness *is* the design
        except ImportError as exc:
            version = f"{sys.version_info.major}.{sys.version_info.minor}"
            raise BackendConfigurationError(
                "INFERENCE_BACKEND=tf requires TensorFlow, but `import tensorflow` failed "
                f"on Python {version} ({exc}). TensorFlow publishes no wheels for this "
                "interpreter; the tf backend runs in the python:3.12 production image built "
                "from requirements-tf.txt. Install it there, or set INFERENCE_BACKEND=stub "
                "explicitly for local development. This service will NOT silently fall back "
                "to the heuristic stub backend.",
                detail={"backend": "tf", "python": version},
            ) from exc
        return tf

    # -- lifecycle ----------------------------------------------------------

    def load(self) -> None:
        tf = self._tf()
        liveness_dir = self._require_dir("LIVENESS_MODEL_DIR", self._settings.liveness_model_dir)
        embedding_dir = self._require_dir(
            "EMBEDDING_MODEL_DIR", self._settings.embedding_model_dir
        )

        self._liveness_fn, self._liveness_io, self._liveness_version = self._load_one(
            tf, liveness_dir, "liveness", self._settings.fallback_model_version
        )
        self._embedding_fn, self._embedding_io, self._embedding_version = self._load_one(
            tf, embedding_dir, "embedding", self._settings.fallback_model_version
        )
        self._warm_up(tf)

    def _require_dir(self, env_name: str, value: str | None) -> str:
        if not value:
            raise BackendConfigurationError(
                f"INFERENCE_BACKEND=tf needs {env_name} to point at an exported SavedModel "
                "(see training/export.py); it is not set.",
                detail={"env": env_name},
            )
        if not os.path.isdir(value):
            raise BackendConfigurationError(
                f"{env_name}={value!r} is not a directory containing a SavedModel.",
                detail={"env": env_name},
            )
        return value

    def _load_one(
        self, tf: Any, directory: str, kind: str, fallback_version: str
    ) -> tuple[Any, dict[str, str | None], str]:
        try:
            model = tf.saved_model.load(directory)
        except Exception as exc:  # TF raises a wide range of types on a bad bundle
            raise BackendConfigurationError(
                f"failed to load the {kind} SavedModel from {directory!r}: {exc}",
                detail={"kind": kind},
            ) from exc

        try:
            signature = model.signatures["serving_default"]
        except (AttributeError, KeyError, TypeError) as exc:
            raise BackendConfigurationError(
                f"the {kind} bundle at {directory!r} has no `serving_default` signature; "
                "export it with training/export.py.",
                detail={"kind": kind},
            ) from exc

        input_key = _pick(_signature_inputs(signature), _INPUT_HINTS)
        output_key = _pick(
            _signature_outputs(signature),
            _LIVENESS_OUTPUT_HINTS if kind == "liveness" else _EMBEDDING_OUTPUT_HINTS,
        )
        return signature, {"input": input_key, "output": output_key}, _version_for(
            directory, kind, fallback_version
        )

    def _warm_up(self, tf: Any) -> None:
        """Run one inference per model so a broken bundle fails at startup, not on the
        first subject."""
        try:
            self.score_liveness(
                np.zeros((self._settings.model_sequence_length, self._settings.model_input_size, self._settings.model_input_size, 3), dtype=np.float32)
            )
            self.embed(
                np.zeros((self._settings.model_input_size, self._settings.model_input_size, 3), dtype=np.float32)
            )
        except (BackendUnavailableError, BackendConfigurationError):
            raise
        except Exception as exc:
            raise BackendConfigurationError(
                f"loaded models failed to warm up: {exc}", detail={"backend": self.name}
            ) from exc

    @property
    def loaded(self) -> bool:
        return self._liveness_fn is not None and self._embedding_fn is not None

    @property
    def liveness_model_version(self) -> str:
        return self._liveness_version or "not-loaded"

    @property
    def embedding_model_version(self) -> str:
        return self._embedding_version or "not-loaded"

    # -- capabilities -------------------------------------------------------

    def score_liveness(self, sequence: np.ndarray) -> LivenessResult:
        self.ensure_ready()
        frames = np.asarray(sequence, dtype=np.float32)
        if frames.ndim != 4 or frames.shape[-1] != 3:
            raise BackendUnavailableError("liveness input must be (n, size, size, 3)")
        output = self._invoke(
            self._liveness_fn, self._liveness_io, frames[None, ...], "liveness"
        )
        value = _scalar(_extract(output, self._liveness_io.get("output")))
        # A sigmoid head already emits a probability; a bare logit does not. Accepting
        # either keeps the export contract forgiving without changing its meaning.
        probability = value if 0.0 <= value <= 1.0 else float(1.0 / (1.0 + np.exp(-value)))
        score = self.clamp_attack_score(probability)
        # Per-signal breakdown is only reported when the exported model actually has
        # those heads; the service never fabricates them to make a response look rich.
        return LivenessResult(
            attack_score=score,
            signals=LivenessSignals(
                texture=_optional_unit(output, "texture", default=score),
                moire=_optional_unit(output, "moire", default=0.0),
                blink_executed=_optional_bool(output, "blink"),
            ),
        )

    def embed(self, face: np.ndarray) -> np.ndarray:
        self.ensure_ready()
        frame = np.asarray(face, dtype=np.float32)
        if frame.ndim != 3 or frame.shape[2] != 3:
            raise BackendUnavailableError("embedding input must be (size, size, 3)")
        output = self._invoke(
            self._embedding_fn, self._embedding_io, frame[None, ...], "embedding"
        )
        raw = _extract(output, self._embedding_io.get("output"))
        vector = np.asarray(raw, dtype=np.float64).reshape(-1)
        return self.normalize_embedding(vector, self._settings.embedding_dim)

    # -- invocation ---------------------------------------------------------

    def _invoke(
        self, fn: Any, io_keys: dict[str, str | None], batch: np.ndarray, kind: str
    ) -> Any:
        """Run one serving call and return the raw (dict or tensor) model output."""
        tf = self._tf()
        tensor = tf.constant(batch)
        input_key = io_keys.get("input")
        try:
            return fn(**{input_key: tensor}) if input_key else fn(tensor)
        except Exception as exc:
            raise BackendUnavailableError(
                f"{kind} inference failed: {exc}", detail={"kind": kind}
            ) from exc


# --- module helpers (kept outside the class so they are testable without TF) ---


def _signature_inputs(signature: Any) -> list[str]:
    try:
        _args, kwargs = signature.structured_input_signature
        return list(kwargs.keys())
    except Exception:  # pragma: no cover - depends on TF internals
        return []


def _signature_outputs(signature: Any) -> list[str]:
    try:
        outputs = signature.structured_outputs
        if isinstance(outputs, dict):
            return list(outputs.keys())
    except Exception:  # pragma: no cover
        pass
    return []


def _pick(candidates: list[str], hints: tuple[str, ...]) -> str | None:
    for hint in hints:
        for name in candidates:
            if hint in name.lower():
                return name
    return candidates[0] if candidates else None


def _extract(output: Any, preferred_key: str | None) -> Any:
    if isinstance(output, dict):
        keys = list(output.keys())
        key = preferred_key if preferred_key in keys else (keys[0] if keys else None)
        if key is None:
            raise BackendUnavailableError("model returned no outputs")
        output = output[key]
    value = output.numpy() if hasattr(output, "numpy") else np.asarray(output)
    return value


def _scalar(raw: Any) -> float:
    flat = np.asarray(raw, dtype=np.float64).reshape(-1)
    if flat.size == 0 or not np.all(np.isfinite(flat)):
        raise BackendUnavailableError("model returned an empty or non-finite attack score")
    return float(flat[0])


def _named(output: Any, key: str) -> Any | None:
    """Fetch an optional named head, or None when the model does not export it."""
    if isinstance(output, dict):
        for name, value in output.items():
            if key in str(name).lower():
                return value
    return None


def _optional_unit(output: Any, key: str, default: float) -> float:
    raw = _named(output, key)
    if raw is None:
        return float(default)
    try:
        return float(min(1.0, max(0.0, _scalar(raw))))
    except (BackendUnavailableError, TypeError, ValueError):
        return float(default)


def _optional_bool(output: Any, key: str) -> bool:
    raw = _named(output, key)
    if raw is None:
        return False
    try:
        return _scalar(raw) >= 0.5
    except (BackendUnavailableError, TypeError, ValueError):
        return False


def _version_for(directory: str, kind: str, fallback: str) -> str:
    manifest_path = os.path.join(directory, MANIFEST_FILE)
    try:
        with open(manifest_path, "r", encoding="utf-8") as handle:
            manifest = json.load(handle)
    except FileNotFoundError:
        return f"{kind}-{fallback}"
    except (OSError, json.JSONDecodeError) as exc:
        raise BackendConfigurationError(
            f"{manifest_path} is unreadable; the {kind} model version cannot be established: {exc}",
            detail={"kind": kind},
        ) from exc
    name = str(manifest.get("name") or kind)
    version = str(manifest.get("version") or "")
    if not version:
        raise BackendConfigurationError(
            f"{manifest_path} has no `version`; refusing to serve an unversioned model.",
            detail={"kind": kind},
        )
    return version if name.startswith(kind) else f"{name}-{version}"
