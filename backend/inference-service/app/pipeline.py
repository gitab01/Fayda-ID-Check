"""The scoring pipeline shared by ``/liveness``, ``/embedding`` and ``/verify``.

Responsibilities, in order:

1. **enforce the rules** — frame count, ``expectedActions`` vocabulary, per-frame size;
2. **decode** base64 to arrays (in memory, never on disk);
3. **resize/crop** to the model input geometry;
4. **refuse** if no model is loaded (before any of the above is even attempted, so an
   unloaded process cannot be probed for a default score);
5. **call the backend** and translate any backend blow-up into 503.

The routers stay thin so the semantics live in one testable place.
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Sequence
from typing import Any, TypeVar

import numpy as np
from fastapi import Request

from .config import Settings
from .errors import (
    BackendUnavailableError,
    FrameCountError,
    ImageDecodeError,
    InferenceServiceError,
    UnknownActionError,
    message_for,
)
from .image import crop_to_face_box, decode_base64_image, prepare_frames, tile_sequence, to_model_frame
from .models.base import InferenceBackend, LivenessResult, similarity

logger = logging.getLogger("app.inference")

T = TypeVar("T")


# --- FastAPI dependencies --------------------------------------------------


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings


def get_backend(request: Request) -> InferenceBackend:
    return request.app.state.backend


# --- rule enforcement ------------------------------------------------------


def validate_frame_count(settings: Settings, frames: Sequence[Any]) -> None:
    count = len(frames)
    if count > settings.max_frames:
        raise FrameCountError(
            f"at most {settings.max_frames} frames are accepted per call, got {count}",
            detail={"maxFrames": settings.max_frames, "received": count},
        )
    if count < settings.min_frames:
        raise FrameCountError(
            f"at least {settings.min_frames} frame(s) are required, got {count}",
            detail={"minFrames": settings.min_frames, "received": count},
        )


def validate_actions(settings: Settings, expected_actions: Sequence[str]) -> None:
    """Reject unknown challenge actions instead of scoring a request we can't interpret."""
    allowed = set(settings.allowed_actions)
    unknown = sorted({action for action in expected_actions if action not in allowed})
    if unknown:
        raise UnknownActionError(
            f"unknown expectedActions: {', '.join(unknown)}",
            detail={
                "unknownActions": unknown,
                "allowedActions": sorted(allowed),
            },
        )


def validate_frame_size(settings: Settings, frames: Sequence[str]) -> None:
    oversized = [index for index, frame in enumerate(frames) if len(frame) > settings.max_frame_bytes]
    if oversized:
        raise FrameCountError(
            f"{len(oversized)} frame(s) exceed the per-frame base64 ceiling "
            f"of {settings.max_frame_bytes} bytes",
            detail={"oversizedFrameIndexes": oversized[:8], "maxFrameBytes": settings.max_frame_bytes},
        )


# --- decoding --------------------------------------------------------------


def decode_frames(settings: Settings, frames: Sequence[str]) -> list[np.ndarray]:
    """Decode every frame to an RGB uint8 array. Raises 422 on any bad payload."""
    return [
        decode_base64_image(frame, max_pixels=settings.max_image_pixels, label=f"frames[{index}]")
        for index, frame in enumerate(frames)
    ]


def decode_image(settings: Settings, image: str) -> np.ndarray:
    if len(image) > settings.max_frame_bytes:
        raise ImageDecodeError(
            f"image exceeds the per-frame base64 ceiling of {settings.max_frame_bytes} bytes",
            detail={"label": "image", "maxFrameBytes": settings.max_frame_bytes},
        )
    return decode_base64_image(image, max_pixels=settings.max_image_pixels, label="image")


def build_sequence(settings: Settings, decoded: Sequence[np.ndarray]) -> np.ndarray:
    """Downscale decoded frames and normalise to the model's fixed clip length."""
    frames = prepare_frames(decoded, settings.model_input_size)
    return tile_sequence(frames, settings.model_input_size, settings.model_sequence_length)


def build_face(settings: Settings, decoded: np.ndarray, face_box: Sequence[int] | None) -> np.ndarray:
    cropped = crop_to_face_box(decoded, face_box)
    return to_model_frame(cropped, settings.model_input_size)


# --- backend calls ---------------------------------------------------------


def guarded(description: str, call: Callable[[], T]) -> T:
    """Run a backend call, mapping any unexpected failure to a retryable 503.

    CONTRACT.md §2: "a model failure returns 503, never a default score". Falling back to
    a neutral number here would be the worst possible outcome, so anything that is not
    already a service error becomes ``INFERENCE_UNAVAILABLE``.
    """

    try:
        return call()
    except InferenceServiceError:
        raise
    except Exception as exc:  # noqa: BLE001 - deliberate wide net: never emit a default
        logger.error("inference failed during %s: %s", description, exc.__class__.__name__)
        raise BackendUnavailableError(
            f"{description} failed: {message_for(exc)}", detail={"cause": exc.__class__.__name__}
        ) from exc


def score_liveness(
    settings: Settings,
    backend: InferenceBackend,
    *,
    frames: Sequence[str],
    expected_actions: Sequence[str],
) -> LivenessResult:
    validate_actions(settings, expected_actions)
    validate_frame_count(settings, frames)
    validate_frame_size(settings, frames)
    backend.ensure_ready()
    sequence = build_sequence(settings, decode_frames(settings, frames))
    result = guarded("liveness scoring", lambda: backend.score_liveness(sequence))
    _log_signal("liveness_scored", attack_score=result.attack_score, frames=len(frames))
    return result


def embed_face(
    settings: Settings,
    backend: InferenceBackend,
    *,
    image: str,
    face_box: Sequence[int] | None,
) -> np.ndarray:
    backend.ensure_ready()
    decoded = decode_image(settings, image)
    embedding = guarded(
        "embedding", lambda: backend.embed(build_face(settings, decoded, face_box))
    )
    _log_signal("embedding_computed", dims=int(embedding.shape[0]))
    return embedding


def embed_reference(
    settings: Settings,
    backend: InferenceBackend,
    *,
    reference_embedding: Sequence[float] | None,
    reference_image: str | None,
) -> np.ndarray:
    """Return a unit-norm reference vector from either accepted input form."""
    if reference_embedding is not None:
        return backend.normalize_embedding(
            np.asarray(reference_embedding, dtype=np.float64), settings.embedding_dim
        )
    return embed_face(settings, backend, image=reference_image or "", face_box=None)


def _log_signal(event: str, **fields: Any) -> None:
    """Structured, identity-free log line.

    Only event names and numbers go here. Frame bytes, base64 payloads, face boxes and
    any person field are never formatted into a log message (asserted by the tests).
    """
    rendered = " ".join(f"{key}={value}" for key, value in fields.items())
    logger.info("%s %s", event, rendered)


def embed_similarity(
    backend: InferenceBackend,
    *,
    embedding: np.ndarray,
    reference: np.ndarray,
) -> float:
    return guarded("similarity", lambda: float(similarity(embedding, reference)))


__all__: list[str] = [
    "build_face",
    "build_sequence",
    "decode_frames",
    "embed_face",
    "embed_reference",
    "embed_similarity",
    "get_backend",
    "get_settings_dep",
    "guarded",
    "score_liveness",
    "validate_actions",
    "validate_frame_count",
    "validate_frame_size",
]
