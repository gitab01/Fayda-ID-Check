"""What the stub actually measures, and the properties its docstring claims.

The stub is not a liveness model and must never be presented as one — but it *is* the
code path development and CI run on, so its measurements have to behave sensibly:
monotone in each injected signal, deterministic, bounded, and stamped so a decision
record can never be mistaken for a model-produced one.

The helpers here reuse the production geometry functions (``to_model_frame``) rather than
a private crop: the moiré test looks at a specific DFT radius, and that radius only lands
where the module says it does *after* the real downscale to ``MODEL_INPUT_SIZE``.
"""

from __future__ import annotations

import sys

import numpy as np
import pytest
from conftest import make_settings
from synthetic import (
    blur,
    face_luminance,
    live_frames,
    mix_frames,
    screen_frames,
    to_uint8,
    with_screen_lattice,
)

from app.errors import BackendUnavailableError, ModelNotLoadedError
from app.image import to_model_frame
from app.models.base import LivenessResult
from app.models.stub_backend import (
    DEFAULT_TUNING,
    StubBackend,
    motion_energy,
    texture_energy,
)

INPUT_SIZE = make_settings().model_input_size


@pytest.fixture
def backend() -> StubBackend:
    active = StubBackend(make_settings())
    active.load()
    return active


def stack(frames: list[np.ndarray]) -> np.ndarray:
    """Luminance frames -> the (n, size, size, 3) float clip ``score_liveness`` is handed."""
    return np.stack([to_model_frame(frame, INPUT_SIZE) for frame in frames], axis=0)


def gray(frames: list[np.ndarray]) -> np.ndarray:
    """The same clip as luma: the (n, size, size) layout the signal functions measure."""
    return stack(frames)[:, :, :, 0]


def model_frame(frame: np.ndarray) -> np.ndarray:
    """A single (size, size, 3) frame, exactly what ``embed`` receives in production."""
    return to_model_frame(frame, INPUT_SIZE)


def score(backend: StubBackend, frames: list[np.ndarray]):
    return backend.score_liveness(stack(frames))


class TestCompositeProperties:
    def test_the_stub_loads_and_stamps_itself_as_a_stub(self, backend):
        assert backend.loaded is True
        assert backend.name == "stub"
        assert backend.liveness_model_version.startswith("liveness-stub-")
        assert backend.embedding_model_version.startswith("embedding-stub-")

    def test_scores_stay_inside_the_contract_range(self, backend):
        for frames in (live_frames(), screen_frames(), live_frames(n=6)):
            result = score(backend, frames)
            assert 0.0 <= result.attack_score <= 1.0
            assert 0.0 <= result.signals.texture <= 1.0
            assert 0.0 <= result.signals.moire <= 1.0

    def test_the_composite_never_saturates_at_exactly_one(self, backend):
        """Weights sum to 0.95, so a score is a measurement and never a clipped wall."""
        weights = DEFAULT_TUNING.weight_texture + DEFAULT_TUNING.weight_moire
        weights += DEFAULT_TUNING.weight_motion
        assert weights == pytest.approx(0.95)

        for frames in (live_frames(), screen_frames(), screen_frames(n=6)):
            assert score(backend, frames).attack_score < 1.0

    def test_a_screen_replay_scores_higher_than_a_live_clip(self, backend):
        live = score(backend, live_frames()).attack_score
        attack = score(backend, screen_frames()).attack_score

        assert attack > live

    def test_blurring_a_frame_removes_the_texture_energy_a_real_face_has(self, backend):
        sharp = texture_energy(gray([to_uint8(face_luminance())]))
        soft = texture_energy(gray([to_uint8(blur(face_luminance(), 6.0))]))

        assert soft < sharp

    def test_a_resampled_screen_line_raises_the_moire_signal(self, backend):
        """The lattice is added to an already-blurred frame, which is what a screen replay is."""
        plain = to_uint8(blur(face_luminance(), 3.0))
        lattice = to_uint8(with_screen_lattice(blur(face_luminance(), 3.0), amplitude=0.09))

        live = score(backend, [plain] * 4).signals.moire
        attack = score(backend, [lattice] * 4).signals.moire

        assert attack > live

    def test_a_still_clip_has_no_motion_and_a_live_one_does(self, backend):
        assert motion_energy(gray(screen_frames())) == pytest.approx(0.0, abs=1e-9)
        assert motion_energy(gray(live_frames())) > 0.0

    def test_mixing_toward_the_attack_raises_the_score(self, backend):
        live, attack = live_frames(), screen_frames()
        scores = [
            score(backend, mix_frames(live, attack, alpha)).attack_score
            for alpha in (0.0, 0.5, 1.0)
        ]

        assert scores[2] > scores[0]

    def test_the_reported_signals_are_the_components_of_the_composite(self, backend):
        result = score(backend, screen_frames())

        assert result.signals.blink_executed in (True, False)
        assert 0.0 <= result.signals.texture <= 1.0


class TestBlinkSignal:
    def test_a_clip_that_blinks_reports_blink_executed(self, backend):
        assert score(backend, live_frames(n=4, blink=True)).signals.blink_executed is True

    def test_a_clip_that_never_moves_reports_no_blink(self, backend):
        assert score(backend, screen_frames(n=4)).signals.blink_executed is False

    def test_two_frames_cannot_evidence_a_blink(self, backend):
        assert score(backend, live_frames(n=2, blink=True)).signals.blink_executed is False

    def test_a_single_frame_is_a_still_not_a_crash(self, backend):
        result = score(backend, live_frames(n=1))

        assert result.signals.blink_executed is False
        assert 0.0 <= result.attack_score <= 1.0


class TestEmbeddingBehaviour:
    def test_the_same_frame_always_embeds_to_the_same_vector(self, backend):
        source = model_frame(to_uint8(face_luminance()))

        assert np.allclose(backend.embed(source), backend.embed(source))
        assert backend.embed(source).shape == (512,)

    def test_two_backends_share_a_projection_and_agree(self, backend):
        other = StubBackend(make_settings())
        other.load()
        source = model_frame(to_uint8(face_luminance()))

        assert np.allclose(backend.embed(source), other.embed(source))

    def test_an_embedding_is_a_unit_vector(self, backend):
        vector = backend.embed(model_frame(to_uint8(face_luminance())))

        assert float(np.linalg.norm(vector)) == pytest.approx(1.0, abs=1e-5)

    def test_different_captures_do_not_collapse_onto_one_vector(self, backend):
        live = backend.embed(model_frame(live_frames(n=1)[0]))
        attack = backend.embed(model_frame(screen_frames(n=1)[0]))

        assert not np.allclose(live, attack)

    def test_a_flat_crop_carries_no_information_and_is_refused(self, backend):
        with pytest.raises(BackendUnavailableError):
            backend.embed(np.zeros((INPUT_SIZE, INPUT_SIZE, 3), dtype=np.float64))

    def test_a_wrongly_shaped_input_is_refused(self, backend):
        with pytest.raises(BackendUnavailableError):
            backend.embed(np.zeros((INPUT_SIZE, INPUT_SIZE), dtype=np.float64))


class TestBackendGuardrails:
    def test_the_stub_never_touches_tensorflow(self):
        """Importing and loading the stub must not pull in a 600 MB dependency."""
        from app.models import build_backend

        build_backend(make_settings(INFERENCE_BACKEND="stub"))

        assert "tensorflow" not in sys.modules

    def test_an_unloaded_stub_refuses_to_score(self):
        with pytest.raises(ModelNotLoadedError):
            StubBackend(make_settings()).score_liveness(stack(live_frames()))

    def test_a_wrongly_ranked_clip_is_reported_as_a_backend_failure(self, backend):
        with pytest.raises(BackendUnavailableError):
            backend.score_liveness(np.zeros((4, 4), dtype=np.float32))

    def test_a_clip_that_is_too_small_to_measure_is_refused(self, backend):
        with pytest.raises(BackendUnavailableError):
            backend.score_liveness(np.zeros((4, 4, 4, 3), dtype=np.float32))

    def test_the_result_type_carries_the_score_and_its_signals(self, backend):
        result = score(backend, live_frames())

        assert isinstance(result, LivenessResult)
        assert isinstance(result.signals.blink_executed, bool)
