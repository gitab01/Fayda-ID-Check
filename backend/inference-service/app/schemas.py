"""Request/response DTOs for CONTRACT.md §2, plus the shape-level validation.

Everything is ``extra="forbid"``: an inference request must not be able to smuggle a
``userId``, phone number or national id into this process (CONTRACT.md Conventions). The
only identifier allowed is the request-scoped ``requestId``, which is echoed back and
logged.

Field names are snake_case in Python and camelCase on the wire (``alias_generator``),
which is exactly what the contract shows: ``attackScore``, ``expectedActions``,
``faceBox``, ``blinkExecuted``, ``modelVersion``.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

#: Request ids are UUIDs per CONTRACT.md Conventions. The pattern is deliberately a
#: UUID-shaped superset (8-64 chars of hex/dash/alnum) so a caller's opaque id survives
#: while oversized or punctuation-laden values — the usual vector for log injection —
#: do not.
REQUEST_ID_PATTERN = r"^[0-9A-Za-z][0-9A-Za-z_-]{7,63}$"

EMBEDDING_DIMENSION = 512


class ContractModel(BaseModel):
    """Base for every wire model: camelCase, closed, no protected-namespace surprises.

    ``populate_by_name`` is deliberately **not** spelled out here. Pydantic 2.13 ignores a
    subclass that re-declares a key the parent set explicitly: with ``populate_by_name=False``
    written here, ``ResponseModel``'s ``True`` merged into ``model_config`` and still read back
    as ``True``, while validation stayed alias-only — so building any response by field name
    raised. Leaving the key at its default keeps requests strict (the default is ``False``) and
    lets responses opt in for real.
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        extra="forbid",
        protected_namespaces=(),
    )


class ResponseModel(ContractModel):
    """Response half of the contract: still camelCase, but buildable by field name.

    Request models must only accept the wire spelling, so a caller cannot sneak in a
    ``user_id``. Responses are built by this process from Python keywords, so they accept
    either spelling; ``by_alias=True`` at serialisation keeps the camelCase contract.
    """

    model_config = ConfigDict(populate_by_name=True)


class RequestIdMixin(ContractModel):
    request_id: str = Field(
        pattern=REQUEST_ID_PATTERN,
        description="Request-scoped id (UUID per contract). The only identifier accepted.",
    )


# --- liveness --------------------------------------------------------------


class LivenessRequest(RequestIdMixin):
    frames: list[str] = Field(
        min_length=1,
        description="Base64 JPEG/PNG frames, in capture order. Capped by MAX_FRAMES.",
    )
    expected_actions: list[str] = Field(
        default_factory=list,
        description="Challenge actions the caller expects; unknown values are rejected.",
    )
    challenge_seed: str | None = Field(
        default=None,
        max_length=128,
        description=(
            "Opaque server seed, accepted for traceability only. It never affects a score: "
            "the sequence itself is the Spring service's responsibility."
        ),
    )

    @field_validator("frames")
    @classmethod
    def _frames_not_blank(cls, value: list[str]) -> list[str]:
        cleaned = [frame.strip() for frame in value]
        if any(not frame for frame in cleaned):
            raise ValueError("frames must contain non-empty base64 strings")
        return cleaned


class LivenessSignalsModel(ResponseModel):
    texture: float = Field(ge=0.0, le=1.0)
    moire: float = Field(ge=0.0, le=1.0)
    blink_executed: bool


class LivenessResponse(ResponseModel):
    request_id: str
    attack_score: float = Field(ge=0.0, le=1.0)
    signals: LivenessSignalsModel
    model_version: str


# --- embedding -------------------------------------------------------------


class EmbeddingRequest(RequestIdMixin):
    image: str = Field(min_length=1, description="Base64 JPEG/PNG selfie frame.")
    face_box: list[int] | None = Field(
        default=None, description="[x, y, w, h] in source pixels; omitted = whole image."
    )

    @field_validator("image")
    @classmethod
    def _image_not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("image must contain a base64 string")
        return value.strip()


class EmbeddingResponse(ResponseModel):
    request_id: str
    embedding: list[float] = Field(
        min_length=EMBEDDING_DIMENSION,
        max_length=EMBEDDING_DIMENSION,
        description="512 floats, L2-normalised.",
    )
    model_version: str


# --- verify ----------------------------------------------------------------


class VerifyRequest(RequestIdMixin):
    """Liveness inputs plus the face to embed and *one* way to compare it against.

    The reference arrives either as the stored template (``referenceEmbedding`` — what
    the verification service keeps in MS SQL) or as a reference image. Exactly one, never
    both: sending both would make the comparison ambiguous.
    """

    frames: list[str] = Field(min_length=1)
    expected_actions: list[str] = Field(default_factory=list)
    challenge_seed: str | None = Field(default=None, max_length=128)
    image: str = Field(min_length=1, description="Selfie frame to embed.")
    face_box: list[int] | None = None
    reference_embedding: list[float] | None = None
    reference_image: str | None = None

    @field_validator("frames", "image")
    @classmethod
    def _strip_and_check(cls, value: object) -> object:
        if isinstance(value, list):
            cleaned = [str(item).strip() for item in value]
            if any(not item for item in cleaned):
                raise ValueError("frames must contain non-empty base64 strings")
            return cleaned
        stripped = str(value).strip()
        if not stripped:
            raise ValueError("image must contain a base64 string")
        return stripped

    @field_validator("reference_embedding")
    @classmethod
    def _reference_dim(cls, value: list[float] | None) -> list[float] | None:
        if value is None:
            return None
        if len(value) != EMBEDDING_DIMENSION:
            raise ValueError(
                f"referenceEmbedding must have {EMBEDDING_DIMENSION} floats, got {len(value)}"
            )
        return value

    @model_validator(mode="after")
    def _exactly_one_reference(self) -> "VerifyRequest":
        has_embedding = self.reference_embedding is not None
        has_image = bool(self.reference_image and self.reference_image.strip())
        if has_embedding == has_image:
            raise ValueError(
                "provide exactly one of referenceEmbedding or referenceImage"
            )
        return self


class VerifyResponse(ResponseModel):
    request_id: str
    attack_score: float = Field(ge=0.0, le=1.0)
    similarity: float = Field(
        ge=0.0,
        le=1.0,
        description="Cosine similarity mapped from [-1, 1] to [0, 1] like decision_records.match_score.",
    )
    signals: LivenessSignalsModel
    liveness_model_version: str
    embedding_model_version: str


# --- health ----------------------------------------------------------------


class HealthResponse(ResponseModel):
    status: str
    model_loaded: bool
    liveness_model_version: str | None = None
    embedding_model_version: str | None = None
    backend: str
    reason: str | None = None


class ReadinessResponse(ResponseModel):
    status: str
    model_loaded: bool
    backend: str
    reason: str | None = None


class LivenessProbeResponse(ResponseModel):
    status: str
    process: str


__all__ = [
    "ContractModel",
    "EmbeddingRequest",
    "EmbeddingResponse",
    "HealthResponse",
    "LivenessProbeResponse",
    "LivenessRequest",
    "LivenessResponse",
    "LivenessSignalsModel",
    "ReadinessResponse",
    "ResponseModel",
    "VerifyRequest",
    "VerifyResponse",
]
