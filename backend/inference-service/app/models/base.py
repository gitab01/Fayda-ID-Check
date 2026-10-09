"""The seam between the HTTP layer and whatever does the actual inference.

``InferenceBackend`` is deliberately tiny: two capabilities (score a liveness clip,
embed a face), a readiness flag and two version strings. The HTTP layer knows nothing
about TensorFlow, numpy layouts beyond a documented array shape, or heuristics.

Adding a backend (aONNX export, a vendor SDK) means one new module and one line in
``app.models.build_backend`` — no route changes.
"""

from __future__ import annotations

import abc
from dataclasses import dataclass, field
from typing import ClassVar

import numpy as np

from ..errors import BackendUnavailableError, ModelNotLoadedError

#: The contract's embedding width (CONTRACT.md §2: "512 floats, L2-normalised").
EMBEDDING_DIM = 512


@dataclass(frozen=True)
class LivenessSignals:
    """Component signals reported next to the aggregate attack score."""

    texture: float
    moire: float
    blink_executed: bool


@dataclass(frozen=True)
class LivenessResult:
    attack_score: float
    signals: LivenessSignals = field(default_factory=lambda: LivenessSignals(0.0, 0.0, False))


class InferenceBackend(abc.ABC):
    """Abstract inference backend.

    Implementations must be stateless with respect to requests: the same input array
    must produce the same output, and handling a call must not retain image bytes.
    """

    name: ClassVar[str] = "abstract"

    # -- lifecycle ----------------------------------------------------------

    @abc.abstractmethod
    def load(self) -> None:
        """Make the models resident. Must raise ``BackendConfigurationError`` or
        ``BackendUnavailableError`` on failure — never leave ``loaded`` False silently."""

    @property
    @abc.abstractmethod
    def loaded(self) -> bool:
        """True only when every model needed to score a request is resident."""

    # -- versions -----------------------------------------------------------

    @property
    @abc.abstractmethod
    def liveness_model_version(self) -> str:
        """Stamp that lands in ``decision_records.liveness_model_ver``."""

    @property
    @abc.abstractmethod
    def embedding_model_version(self) -> str:
        """Stamp that lands in ``decision_records.embedding_model_ver``."""

    # -- capabilities -------------------------------------------------------

    @abc.abstractmethod
    def score_liveness(self, sequence: np.ndarray) -> LivenessResult:
        """Continuous attack score in ``[0, 1]`` for a ``(n, size, size, 3)`` clip.

        Higher means "more likely an attack" (CONTRACT.md Conventions).
        """

    @abc.abstractmethod
    def embed(self, face: np.ndarray) -> np.ndarray:
        """L2-normalised ``embedding_dim`` float32 embedding for a ``(size, size, 3)`` face."""

    # -- shared guards (concrete) ------------------------------------------

    def ensure_ready(self) -> None:
        """Refuse to score when nothing is loaded.

        This is the single choke point that guarantees "an unloaded model never yields
        default scores": every scoring path calls it before touching a backend.
        """
        if not self.loaded:
            raise ModelNotLoadedError(
                f"{self.__class__.__name__} has no model loaded; refusing to score",
                detail={"backend": self.name},
            )

    @staticmethod
    def clamp_attack_score(value: float) -> float:
        """Coerce a model head into the contract's ``[0, 1]`` range.

        A non-finite value is treated as a model failure rather than a boundary:
        returning 0.0 for ``nan`` would be exactly the default score the contract
        forbids.
        """
        array = np.asarray(value, dtype=np.float64)
        if array.size != 1:
            raise BackendUnavailableError("model returned a non-scalar attack score")
        scalar = float(array.reshape(()))
        if not np.isfinite(scalar):
            raise BackendUnavailableError("model returned a non-finite attack score")
        return float(min(1.0, max(0.0, scalar)))

    @staticmethod
    def normalize_embedding(vector: np.ndarray, expected_dim: int = EMBEDDING_DIM) -> np.ndarray:
        """Return an L2-normalised float32 vector of exactly ``expected_dim`` dims."""
        flat = np.asarray(vector, dtype=np.float64).reshape(-1)
        if flat.size != expected_dim:
            raise BackendUnavailableError(
                f"embedding has {flat.size} dims, expected {expected_dim}"
            )
        if not np.all(np.isfinite(flat)):
            raise BackendUnavailableError("embedding contains non-finite values")
        norm = float(np.linalg.norm(flat))
        if norm <= 1e-12:
            raise BackendUnavailableError("embedding has zero norm; refusing to emit a vector")
        return (flat / norm).astype(np.float32)

    # -- introspection ------------------------------------------------------

    def describe(self) -> dict[str, object]:
        return {
            "backend": self.name,
            "loaded": self.loaded,
            "livenessModelVersion": self.liveness_model_version,
            "embeddingModelVersion": self.embedding_model_version,
        }

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return (
            f"<{self.__class__.__name__} name={self.name!r} loaded={self.loaded} "
            f"liveness={self.liveness_model_version!r} embedding={self.embedding_model_version!r}>"
        )


def similarity(embedding_a: np.ndarray, embedding_b: np.ndarray) -> float:
    """Cosine similarity of two embeddings, mapped onto the contract's ``[0, 1]``.

    ``decision_records.match_score`` is documented as "cosine similarity, -1..1
    normalised to 0..1", so the mapping happens here, once, and the Spring service never
    re-derives it. The mapping is monotone in the cosine, so any threshold the composite
    applies translates directly.
    """
    a = np.asarray(embedding_a, dtype=np.float64).reshape(-1)
    b = np.asarray(embedding_b, dtype=np.float64).reshape(-1)
    if a.shape != b.shape:
        raise BackendUnavailableError("cannot compare embeddings of different widths")
    norm_a, norm_b = float(np.linalg.norm(a)), float(np.linalg.norm(b))
    if norm_a <= 1e-12 or norm_b <= 1e-12:
        raise BackendUnavailableError("cannot compare a zero-norm embedding")
    cosine = float(np.dot(a, b) / (norm_a * norm_b))
    return float(min(1.0, max(0.0, (cosine + 1.0) / 2.0)))
