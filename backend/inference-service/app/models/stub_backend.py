"""Non-production inference backend: honest, deterministic signal statistics.

**What this is.** A heuristic stand-in so the service is fully runnable and testable on
a machine that cannot install TensorFlow (CPython 3.14 has no cp314 TensorFlow wheels).
It computes *real* measurements from the decoded frames and composes them into the
contract's ``attackScore``:

* ``texture`` — spatial high-frequency energy (a Laplacian-style high-pass, mean square)
  as a **texture proxy**. A genuine face carries skin/high-frequency detail; a photo of
  a screen or a printed spoof loses it.
* ``motion`` — per-pixel variance across the frame sequence as a **motion/blink proxy**.
  A live subject moves and blinks; a replayed still or a printed photo does not.
* ``moire`` — a crude DFT peak test on the mean frame as a **moiré proxy**: the strongest
  mid/high spectral line, weighted by how much of that band's energy it carries, against the
  level of the other lines at the same radius. Resampling a display's pixel lattice produces
  a narrowband *and* dominant line; diffuse photography produces neither.

``attack_score = w_texture * texture_attack + w_moire * moire_attack + w_motion * motion_attack``
with weights summing to 0.95, so the composite never saturates at exactly 1.0 and stays
continuous and monotone in each injected signal.

**What this is not.** It is *not* a face-liveness model. It makes no accuracy claim, has
no training set, no held-out evaluation, no FAR/FRR, and its thresholds are not
calibrated against presentation attacks. ``evaluate.py`` in ``training/`` is what
produces real numbers for the ``tf`` backend. Scores are stamped
``liveness-stub-<version>`` / ``embedding-stub-<version>`` so a decision record can
never be mistaken for a model-produced one, and ``INFERENCE_BACKEND=tf`` is mandatory in
the production image.

**Embedding.** The 512-d vector is a locality-sensitive *feature hash*: a three-part
block descriptor of the downscaled frame (16x16 grid of per-block RGB means, per-block
luma spread and per-block high-frequency energy = 1280 dims, standardised), projected
through a fixed-seed signed random matrix and L2-normalised. Deterministic, cheap, and it
gives the property the composite needs — an identical image self-matches at 1.0, a clearly
different image sits measurably lower — while carrying no trained identity information.
(A pure SHA-256 "hash" would instead give similarity ~0 for *every* pair of distinct
images, which would make the match signal useless; the random projection is what makes it
a hash *with* similarity structure.) It is not reversible into an image, and must never
be enrolled as a real template.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from ..config import Settings
from ..errors import BackendUnavailableError
from ..image import frame_to_grayscale
from .base import (  # `similarity` is re-exported: it is contract math, shared by all backends
    EMBEDDING_DIM,
    InferenceBackend,
    LivenessResult,
    LivenessSignals,
    similarity,
)

LIVENESS_VERSION = "liveness-stub-1.0.0"
EMBEDDING_VERSION = "embedding-stub-1.0.0"

#: Number of coarse spatial blocks per axis in the embedding descriptor.
BLOCK_GRID = 16
#: Descriptor width: RGB block means + normalised luma block spread + normalised
#: high-frequency block energy (3 + 1 + 1 channels per block).
FEATURE_DIM = BLOCK_GRID * BLOCK_GRID * 5
#: Fixed seed so the projection matrix is identical in every process (a stub template
#: must not depend on machine state).
PROJECTION_SEED = 2026_05_01


@dataclass(frozen=True)
class StubTuning:
    """Named constants for the heuristic, kept in one place instead of magic numbers.

    The energies are expressed in squared normalised-intensity units measured on frames
    *after* downscaling to ``MODEL_INPUT_SIZE``, which is what makes them small:
    bilinear downscaling averages away most of the sensor noise. They are illustrative
    defaults for a non-production heuristic, overridable per instance.
    """

    #: Half-saturation energies.
    texture_half: float = 2.5e-5
    texture_sharpness: float = 1.5
    motion_half: float = 2.0e-5
    motion_sharpness: float = 1.5
    #: Contrast ramp for the moiré proxy. The measure is dominated-line-salience * band-energy
    #: share, so it is O(1), not O(100): on this suite's synthetic fixtures at model input size
    #: 112 an unlatticed face measures 1.0-1.4, a screen lattice at amplitude 0.09 measures 1.89
    #: and at 0.15 measures 3.44. Recalibrate against those, not against intuition.
    moire_low: float = 1.5
    moire_high: float = 5.0
    #: Blink detection.
    blink_min_energy: float = 2.0e-4
    blink_min_frames: int = 3
    #: Composite weights; sum to 0.95 so the score is continuous and never clipped.
    weight_texture: float = 0.40
    weight_moire: float = 0.25
    weight_motion: float = 0.30


DEFAULT_TUNING = StubTuning()


def _saturating_inverse(energy: float, half: float, sharpness: float) -> float:
    """Attack contribution from an energy that *should* be present.

    Returns ~0 when ``energy`` is well above ``half`` and approaches 1 as the energy
    vanishes. Monotone decreasing and smooth, so a small change in the frame produces a
    small change in the score.
    """
    if energy < 0.0 or not np.isfinite(energy):
        raise BackendUnavailableError("non-finite signal energy in stub backend")
    ratio = (energy + 1e-12) / half
    return float(1.0 / (1.0 + ratio**sharpness))


def _ramp(value: float, low: float, high: float) -> float:
    """Clipped linear ramp, monotone increasing."""
    if high <= low:
        raise BackendUnavailableError("invalid stub ramp bounds")
    if not np.isfinite(value):
        raise BackendUnavailableError("non-finite signal value in stub backend")
    return float(min(1.0, max(0.0, (value - low) / (high - low))))


def texture_energy(gray_stack: np.ndarray) -> float:
    """Mean square of a 4-neighbour high-pass over every frame. ``(n, h, w)`` input."""
    centre = gray_stack[:, 1:-1, 1:-1]
    neighbours = (
        gray_stack[:, :-2, 1:-1]
        + gray_stack[:, 2:, 1:-1]
        + gray_stack[:, 1:-1, :-2]
        + gray_stack[:, 1:-1, 2:]
    )
    high_pass = centre - neighbours / 4.0
    return float(np.mean(high_pass.astype(np.float64) ** 2))


def motion_energy(gray_stack: np.ndarray) -> float:
    """Mean per-pixel variance across the clip: how much the scene actually moved."""
    if gray_stack.shape[0] < 2:
        return 0.0
    return float(np.mean(np.var(gray_stack.astype(np.float64), axis=0)))


def blink_energy(gray_stack: np.ndarray) -> float:
    """Per-pixel variance restricted to the eye band of the frame.

    The band is the horizontally centred strip around 30-45 % of frame height — crude,
    but a closed-eye frame changes it far more than it changes the rest of the image.
    """
    height, width = gray_stack.shape[1], gray_stack.shape[2]
    top = int(0.30 * height)
    bottom = max(top + 1, int(0.45 * height))
    left = int(0.25 * width)
    right = max(left + 1, int(0.75 * width))
    band = gray_stack[:, top:bottom, left:right]
    if band.shape[0] < 2:
        return 0.0
    return float(np.mean(np.var(band.astype(np.float64), axis=0)))


def moire_peak_contrast(gray_frame: np.ndarray, top_peaks: int = 1, ring: int = 2) -> float:
    """Crude DFT peak test: strongest spectral line vs the other lines at the same radius.

    The spectrum of any face-like image is a bright blob near DC that decays smoothly with
    radius, so the strongest bin in a mid-frequency annulus is simply the bin nearest the
    blob — comparing it with the median of the *whole* annulus returns a huge ratio for a
    perfectly smooth photo. A moiré beat pattern is different in kind: it is narrowband in
    *angle* as well as radius, so one bin at its radius towers over the rest of that radius
    while the blob leaves every bin at its radius near the same level.

    Returns the mean over the ``top_peaks`` strongest bins of ``peak / median(same-radius
    bins) * share`` inside the considered annulus, where ``share`` is that line's fraction of
    the annulus energy, Hann-windowed and mean-removed so the DC pedestal and frame edges
    cannot masquerade as periodicity.
    """
    height, width = gray_frame.shape
    window = np.outer(np.hanning(height), np.hanning(width))
    frame = gray_frame.astype(np.float64)
    spectrum = np.abs(np.fft.rfft2((frame - frame.mean()) * window))
    rows = np.arange(spectrum.shape[0], dtype=np.float64)[:, None]
    cols = np.arange(spectrum.shape[1], dtype=np.float64)[None, :]
    radius = np.sqrt(rows**2 + cols**2)
    inner = min_peak_radius(height)
    outer = max_peak_radius(height)
    annulus = (radius >= inner) & (radius <= outer)
    if not np.any(annulus):
        return 0.0

    masked = np.where(annulus, spectrum, -1.0)
    order = np.argsort(masked.ravel())[::-1]
    rows_n, cols_n = spectrum.shape
    band_energy = float(np.sum(spectrum[annulus] ** 2))
    if band_energy <= 1e-24:
        return 0.0
    # Bins already claimed by a stronger peak must not dilute a later peak's reference.
    claimed = np.zeros((rows_n, cols_n), dtype=bool)
    total = 0.0
    counted = 0
    for flat_index in order:
        if counted >= max(1, top_peaks):
            break
        row, col = divmod(int(flat_index), cols_n)
        if masked[row, col] < 0.0:
            break
        # The peak's own neighbourhood, in both directions: excluded from its reference.
        neighbourhood = np.zeros((rows_n, cols_n), dtype=bool)
        neighbourhood[max(0, row - ring) : row + ring + 1, max(0, col - ring) : col + ring + 1] = True
        same_radius = annulus & (np.abs(radius - radius[row, col]) <= 1.0) & ~neighbourhood & ~claimed
        if int(same_radius.sum()) < 4:
            continue
        pedestal = float(np.median(spectrum[same_radius]))
        if pedestal <= 1e-12:
            continue
        line = float(spectrum[row, col])
        total += (line / pedestal) * (line**2 / band_energy)
        claimed |= neighbourhood
        counted += 1

    if counted == 0:
        return 0.0
    return float(total / counted)


def min_peak_radius(size: int) -> float:
    """Lowest DFT radius considered periodic (above the face's own smooth blob)."""
    return max(5.0, 0.06 * size)


def max_peak_radius(size: int) -> float:
    """Highest DFT radius considered (below the frame's own edge artefacts)."""
    return 0.45 * size


class StubBackend(InferenceBackend):
    """Heuristic backend used on Python 3.14 and in the test suite. See module docstring."""

    name = "stub"

    def __init__(self, settings: Settings, tuning: StubTuning | None = None) -> None:
        self._settings = settings
        self._tuning = tuning or DEFAULT_TUNING
        self._loaded = False
        self._projection: np.ndarray | None = None

    # -- lifecycle ----------------------------------------------------------

    def load(self) -> None:
        """No artifacts exist to load; flip readiness and pre-build the projection."""
        self._projection_matrix()
        self._loaded = True

    @property
    def loaded(self) -> bool:
        return self._loaded

    @property
    def liveness_model_version(self) -> str:
        return LIVENESS_VERSION

    @property
    def embedding_model_version(self) -> str:
        return EMBEDDING_VERSION

    # -- capabilities -------------------------------------------------------

    def score_liveness(self, sequence: np.ndarray) -> LivenessResult:
        self.ensure_ready()
        gray = self._as_gray_stack(sequence)
        tuning = self._tuning

        texture = texture_energy(gray)
        motion = motion_energy(gray)
        peak_contrast = moire_peak_contrast(gray.mean(axis=0))
        blink = blink_energy(gray)

        texture_attack = _saturating_inverse(texture, tuning.texture_half, tuning.texture_sharpness)
        motion_attack = _saturating_inverse(motion, tuning.motion_half, tuning.motion_sharpness)
        moire_attack = _ramp(peak_contrast, tuning.moire_low, tuning.moire_high)

        composite = (
            tuning.weight_texture * texture_attack
            + tuning.weight_moire * moire_attack
            + tuning.weight_motion * motion_attack
        )
        blink_executed = (
            gray.shape[0] >= tuning.blink_min_frames and blink >= tuning.blink_min_energy
        )

        return LivenessResult(
            attack_score=self.clamp_attack_score(composite),
            signals=LivenessSignals(
                texture=round(self.clamp_attack_score(texture_attack), 5),
                moire=round(self.clamp_attack_score(moire_attack), 5),
                blink_executed=bool(blink_executed),
            ),
        )

    def embed(self, face: np.ndarray) -> np.ndarray:
        self.ensure_ready()
        frame = np.asarray(face, dtype=np.float64)
        if frame.ndim != 3 or frame.shape[2] != 3:
            raise BackendUnavailableError("stub embed expects a (size, size, 3) face array")
        features = _block_features(frame, BLOCK_GRID)
        projection = self._projection_matrix()
        return self.normalize_embedding(features @ projection, self._settings.embedding_dim)

    # -- internals ----------------------------------------------------------

    def _as_gray_stack(self, sequence: np.ndarray) -> np.ndarray:
        gray = np.asarray(sequence, dtype=np.float32)
        if gray.ndim != 4 or gray.shape[3] != 3:
            raise BackendUnavailableError("stub liveness expects a (n, size, size, 3) clip")
        height, width = gray.shape[1], gray.shape[2]
        if height < 8 or width < 8:
            raise BackendUnavailableError("stub liveness clip is too small to measure")
        weights = np.array([0.299, 0.587, 0.114], dtype=np.float32)
        return gray @ weights

    def _projection_matrix(self) -> np.ndarray:
        if self._projection is None:
            generator = np.random.default_rng(PROJECTION_SEED)
            self._projection = (
                generator.standard_normal((FEATURE_DIM, EMBEDDING_DIM)) / np.sqrt(FEATURE_DIM)
            )
        return self._projection


def _block_features(frame: np.ndarray, grid: int) -> np.ndarray:
    """Coarse locality-sensitive descriptor of a face frame: ``5 * grid * grid`` floats.

    Three views of the same 16x16 partition:

    * per-block RGB means — the low-frequency shape of the face;
    * per-block luma spread — how much local detail a block holds;
    * per-block mean absolute high-frequency residual — where the detail is.

    Block means alone would be dominated by the smooth head gradient, which makes
    unrelated faces look near-identical; the two texture parts are what pull different
    captures apart. Each part is scaled by its own mean and the concatenation is then
    standardised, so overall brightness and contrast drop out and an identical frame
    always lands on the same unit vector.
    """
    channels = frame.shape[2]
    height, width = frame.shape[0], frame.shape[1]
    edges_y = np.linspace(0, height, grid + 1).astype(int)
    edges_x = np.linspace(0, width, grid + 1).astype(int)

    gray = frame_to_grayscale(frame)
    interior = gray[1:-1, 1:-1] - (
        gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:]
    ) / 4.0
    high_pass = np.pad(np.abs(interior), 1)

    means = np.empty((grid, grid, channels), dtype=np.float64)
    spreads = np.empty((grid, grid), dtype=np.float64)
    details = np.empty((grid, grid), dtype=np.float64)
    for i in range(grid):
        start_y = edges_y[i]
        stop_y = max(edges_y[i + 1], start_y + 1)
        for j in range(grid):
            start_x = edges_x[j]
            stop_x = max(edges_x[j + 1], start_x + 1)
            means[i, j] = frame[start_y:stop_y, start_x:stop_x].reshape(-1, channels).mean(axis=0)
            spreads[i, j] = float(gray[start_y:stop_y, start_x:stop_x].std())
            details[i, j] = float(high_pass[start_y:stop_y, start_x:stop_x].mean())

    flat = np.concatenate(
        [
            means.reshape(-1),
            spreads.reshape(-1) / (float(spreads.mean()) + 1e-9),
            details.reshape(-1) / (float(details.mean()) + 1e-9),
        ]
    )
    spread = float(flat.std())
    if spread <= 1e-9:
        # A perfectly flat crop carries no information; keep it flat (zero) so the
        # normaliser refuses to emit a vector instead of inventing one.
        spread = 1.0
    return (flat - float(flat.mean())) / spread
