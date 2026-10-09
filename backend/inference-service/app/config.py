"""Environment-driven configuration.

Every knob below is read from the environment (see ``.env`` prefix-less names) and
matches the variable names already used by ``docker-compose.yml``:
``INFERENCE_BACKEND``, ``LIVENESS_MODEL_DIR``, ``EMBEDDING_MODEL_DIR``,
``MAX_REQUEST_BYTES``, ``MAX_FRAMES``.

The backend switch is the core design decision of this service: TensorFlow has no
cp314 wheels, so development on Python 3.14 runs ``stub`` while the production image
(``python:3.12-slim``) runs ``tf``. Nothing else in the code path differs.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BackendName = Literal["tf", "stub"]

#: Actions the challenge generator can ask for. This must stay in step with the
#: verification service's ``ChallengeAction`` pool: the scorer has to accept every action the
#: issuer can issue, otherwise a legitimate attempt dies with EXPECTED_ACTION_UNKNOWN.
#: Anything outside this set is rejected — the service must not silently score a request whose
#: challenge it cannot interpret. Overridable with ALLOWED_ACTIONS (comma separated) so a new
#: action catalog is a config change, not a code change.
DEFAULT_ALLOWED_ACTIONS: tuple[str, ...] = (
    "TURN_LEFT",
    "TURN_RIGHT",
    "BLINK",
    "NOD",
    "SMILE",
    "TILT_LEFT",
    "TILT_RIGHT",
)

#: 8 MB, the request cap mandated by CONTRACT.md §2.
DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # --- backend selection -------------------------------------------------
    inference_backend: BackendName = Field(
        default="stub",
        validation_alias="INFERENCE_BACKEND",
        description="`tf` for the real SavedModel backend, `stub` for the local heuristic.",
    )
    #: Ops/tests escape hatch: start the process without loading models so the 503
    #: readiness path can be exercised on a real deployment.
    skip_model_load: bool = Field(default=False, validation_alias="INFERENCE_SKIP_MODEL_LOAD")

    # --- model locations / versions (tf backend) ---------------------------
    liveness_model_dir: str | None = Field(default=None, validation_alias="LIVENESS_MODEL_DIR")
    embedding_model_dir: str | None = Field(default=None, validation_alias="EMBEDDING_MODEL_DIR")
    #: Used when a SavedModel ships no model_manifest.json.
    fallback_model_version: str = Field(
        default="unversioned", validation_alias="FALLBACK_MODEL_VERSION"
    )

    # --- request limits ----------------------------------------------------
    max_request_bytes: int = Field(
        default=DEFAULT_MAX_REQUEST_BYTES, validation_alias="MAX_REQUEST_BYTES", gt=0
    )
    max_frames: int = Field(default=12, validation_alias="MAX_FRAMES", ge=1)
    min_frames: int = Field(default=1, validation_alias="MIN_FRAMES", ge=1)
    #: Decompression-bomb guard: refuse to rasterise anything above this pixel count.
    max_image_pixels: int = Field(
        default=40_000_000, validation_alias="MAX_IMAGE_PIXELS", ge=1024
    )
    #: Per-frame base64 payload ceiling (belt and braces next to the body cap).
    max_frame_bytes: int = Field(default=6 * 1024 * 1024, validation_alias="MAX_FRAME_BYTES", gt=0)

    # --- model input geometry ----------------------------------------------
    #: Frames are downscaled to this square before inference (CONTRACT.md §2).
    model_input_size: int = Field(default=112, validation_alias="MODEL_INPUT_SIZE", ge=16)
    #: Fixed sequence length the liveness model expects; short calls are tiled.
    model_sequence_length: int = Field(default=12, validation_alias="MODEL_SEQUENCE_LENGTH", ge=1)
    #: The embedding is a 512-d unit vector (CONTRACT.md §2).
    embedding_dim: int = Field(default=512, validation_alias="EMBEDDING_DIM", ge=16)

    # --- behaviour ---------------------------------------------------------
    allowed_actions: tuple[str, ...] = DEFAULT_ALLOWED_ACTIONS
    request_id_header: str = Field(default="X-Request-Id", validation_alias="REQUEST_ID_HEADER")
    log_level: str = Field(default="INFO", validation_alias="LOG_LEVEL")

    @field_validator("inference_backend", mode="before")
    @classmethod
    def _normalise_backend_name(cls, value: object) -> object:
        """``" TF "`` is the same switch as ``tf``.

        Without this the Literal rejects it before anything can normalise it, so a trailing
        space in a compose file would be a startup failure rather than a config choice. An
        unknown name still fails: only case and padding are forgiven.
        """
        if isinstance(value, str):
            return value.strip().lower()
        return value

    @field_validator("allowed_actions", mode="before")
    @classmethod
    def _split_actions(cls, value: object) -> object:
        if isinstance(value, str):
            items = tuple(part.strip() for part in value.split(",") if part.strip())
            return items or DEFAULT_ALLOWED_ACTIONS
        return value

    @field_validator("request_id_header")
    @classmethod
    def _normalise_header_name(cls, value: str) -> str:
        return value.strip() or "X-Request-Id"

    def describe(self) -> dict[str, object]:
        """Config summary for the startup log — no secrets, no paths that leak infra."""
        return {
            "backend": self.inference_backend,
            "max_request_bytes": self.max_request_bytes,
            "max_frames": self.max_frames,
            "model_input_size": self.model_input_size,
            "sequence_length": self.model_sequence_length,
            "embedding_dim": self.embedding_dim,
            "allowed_actions": list(self.allowed_actions),
        }


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Cached settings for the process-wide ``app.main:app`` instance."""
    return Settings()
