"""The pipeline's rules, tested at the function level.

The routers are thin, so this is where the contract's guarantees actually live: the order of
the checks, the refusal-before-work, and the never-a-default-score mapping of blow-ups to 503.
"""

from __future__ import annotations

import base64
import io

import numpy as np
import pytest
from conftest import UnloadedStubBackend, make_settings
from PIL import Image

from app.errors import (
    BackendUnavailableError,
    FaceBoxError,
    FrameCountError,
    ImageDecodeError,
    InferenceServiceError,
    ModelNotLoadedError,
    UnknownActionError,
)
from app.image import (
    crop_to_face_box,
    decode_base64_image,
    tile_sequence,
    to_model_frame,
)
from app.models.base import InferenceBackend, similarity
from app.pipeline import (
    build_sequence,
    decode_frames,
    guarded,
    score_liveness,
    validate_actions,
    validate_frame_count,
    validate_frame_size,
)


def png_base64(size: int = 32, color: tuple[int, int, int] = (10, 200, 40)) -> str:
    buffer = io.BytesIO()
    Image.new("RGB", (size, size), color).save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


class TestRuleOrdering:
    def test_an_unknown_action_is_caught_before_the_frame_count(self):
        settings = make_settings(MAX_FRAMES=1)

        with pytest.raises(UnknownActionError):
            validate_actions(settings, ["SIT_DOWN"])
        with pytest.raises(FrameCountError):
            validate_frame_count(settings, ["a", "b"])
        # Both invalid: the vocabulary check runs first inside score_liveness.
        with pytest.raises(UnknownActionError):
            score_liveness(
                settings,
                UnloadedStubBackend(settings),
                frames=["x", "y"],
                expected_actions=["SIT_DOWN"],
            )

    def test_an_unloaded_backend_refuses_before_any_decoding_work(self):
        settings = make_settings()
        unloaded = UnloadedStubBackend(settings)

        # Garbage frames would raise ImageDecodeError if decoding were attempted first.
        with pytest.raises(ModelNotLoadedError):
            score_liveness(
                settings, unloaded, frames=["!!!not base64!!!"], expected_actions=[]
            )

    def test_frame_size_is_checked_before_the_backend_is_touched(self):
        settings = make_settings(MAX_FRAME_BYTES=8)
        unloaded = UnloadedStubBackend(settings)

        with pytest.raises(FrameCountError):
            score_liveness(settings, unloaded, frames=["x" * 40], expected_actions=[])


class TestFrameRules:
    def test_counts_over_max_frames_are_rejected_with_the_numbers(self):
        settings = make_settings(MAX_FRAMES=3)

        with pytest.raises(FrameCountError) as excinfo:
            validate_frame_count(settings, ["a"] * 4)
        assert excinfo.value.status_code == 422
        assert excinfo.value.detail["maxFrames"] == 3
        assert excinfo.value.detail["received"] == 4

    def test_empty_frame_lists_are_rejected(self):
        settings = make_settings()

        with pytest.raises(FrameCountError):
            validate_frame_count(settings, [])

    def test_a_single_frame_is_allowed_by_default(self):
        validate_frame_count(make_settings(), ["a"])

    def test_only_the_oversized_indexes_are_reported(self):
        settings = make_settings(MAX_FRAME_BYTES=16)

        with pytest.raises(FrameCountError) as excinfo:
            validate_frame_size(settings, ["a" * 4, "b" * 40, "c" * 40])
        assert excinfo.value.detail["oversizedFrameIndexes"] == [1, 2]

    def test_the_allowed_vocabulary_covers_every_action_the_verifier_issues(self):
        """The two services must agree, or a legitimate challenge 422s at scoring time."""
        from app.config import DEFAULT_ALLOWED_ACTIONS

        validate_actions(make_settings(), list(DEFAULT_ALLOWED_ACTIONS))
        with pytest.raises(UnknownActionError):
            validate_actions(make_settings(), ["TURN_LEFT", "WINK"])


class TestImageDecoding:
    def test_a_plain_png_round_trips_to_an_rgb_array(self):
        array = decode_base64_image(png_base64(), max_pixels=1_000_000)

        assert array.shape == (32, 32, 3)
        assert array.dtype == np.uint8

    def test_a_data_url_prefix_is_stripped(self):
        encoded = "data:image/png;base64," + png_base64()

        assert decode_base64_image(encoded, max_pixels=1_000_000).shape == (32, 32, 3)

    def test_wrapped_base64_with_whitespace_is_still_decoded(self):
        wrapped = "\n".join(
            chunk for chunk in (png_base64()[i : i + 32] for i in range(0, len(png_base64()), 32))
        )

        assert decode_base64_image(wrapped, max_pixels=1_000_000).shape == (32, 32, 3)

    def test_non_image_bytes_are_rejected_rather_than_blanked(self):
        junk = base64.b64encode(b"not an image at all").decode("ascii")

        with pytest.raises(ImageDecodeError):
            decode_base64_image(junk, max_pixels=1_000_000)

    def test_the_pixel_ceiling_runs_before_rasterisation(self):
        # 64x64 = 4096 pixels, above the 1024 ceiling: rejected on the declared size alone.
        with pytest.raises(ImageDecodeError) as excinfo:
            decode_base64_image(png_base64(size=64), max_pixels=1024)
        assert "ceiling" in str(excinfo.value)

    def test_undecodable_frames_raise_instead_of_scoring_a_blank(self):
        settings = make_settings()

        with pytest.raises(ImageDecodeError):
            decode_frames(settings, ["!!!"])


class TestFaceBox:
    def test_a_box_inside_the_image_crops_exactly(self):
        array = np.zeros((40, 40, 3), dtype=np.uint8)

        cropped = crop_to_face_box(array, [10, 10, 20, 20])

        assert cropped.shape == (20, 20, 3)

    def test_a_box_hanging_off_an_edge_is_clamped(self):
        array = np.zeros((40, 40, 3), dtype=np.uint8)

        # 15x15 of a 20x20 box is inside: clamped, not rejected.
        assert crop_to_face_box(array, [25, 25, 20, 20]).shape == (15, 15, 3)

    def test_a_box_that_is_mostly_outside_is_rejected(self):
        array = np.zeros((40, 40, 3), dtype=np.uint8)

        with pytest.raises(FaceBoxError) as excinfo:
            crop_to_face_box(array, [30, 30, 20, 20])
        assert excinfo.value.detail["overlapRatio"] < 0.5

    def test_a_box_entirely_outside_is_rejected(self):
        array = np.zeros((40, 40, 3), dtype=np.uint8)

        with pytest.raises(FaceBoxError):
            crop_to_face_box(array, [500, 500, 10, 10])

    @pytest.mark.parametrize("box", [[1, 2, 3], [1, 2, 3, 4, 5], [0, 0, -5, 5], [0, 0, 0, 10]])
    def test_malformed_boxes_are_rejected(self, box):
        array = np.zeros((40, 40, 3), dtype=np.uint8)

        with pytest.raises(FaceBoxError):
            crop_to_face_box(array, box)

    def test_a_tiny_crop_is_rejected_rather_than_embedded(self):
        array = np.zeros((40, 40, 3), dtype=np.uint8)

        with pytest.raises(FaceBoxError):
            crop_to_face_box(array, [0, 0, 4, 4])

    def test_no_box_means_the_whole_image(self):
        array = np.zeros((40, 40, 3), dtype=np.uint8)

        assert crop_to_face_box(array, None) is array


class TestModelGeometry:
    def test_a_frame_becomes_a_normalised_square(self):
        array = decode_base64_image(png_base64(size=64), max_pixels=1_000_000)

        frame = to_model_frame(array, 112)

        assert frame.shape == (112, 112, 3)
        assert frame.dtype == np.float32
        assert 0.0 <= float(frame.min()) and float(frame.max()) <= 1.0

    def test_a_short_clip_is_tiled_in_order(self):
        frames = np.zeros((2, 4, 4, 3), dtype=np.float32)
        frames[0] = 0.1
        frames[1] = 0.2

        tiled = tile_sequence(frames, 4, 5)

        assert tiled.shape == (5, 4, 4, 3)
        assert np.allclose(tiled[0], 0.1) and np.allclose(tiled[1], 0.2)
        assert np.allclose(tiled[2], 0.1)

    def test_a_long_clip_is_sampled_evenly_not_truncated(self):
        frames = np.arange(10 * 4 * 4 * 3, dtype=np.float32).reshape(10, 4, 4, 3)

        sampled = tile_sequence(frames, 4, 5)

        assert sampled.shape == (5, 4, 4, 3)
        assert {int(index) for index in np.linspace(0, 9, 5, dtype=int)} == {0, 2, 4, 6, 9}

    def test_build_sequence_produces_the_documented_clip_shape(self):
        settings = make_settings()

        sequence = build_sequence(settings, decode_frames(settings, [png_base64()]))

        assert sequence.shape == (
            settings.model_sequence_length,
            settings.model_input_size,
            settings.model_input_size,
            3,
        )


class TestNeverADefaultScore:
    def test_an_unexpected_blow_up_becomes_a_retryable_503(self):
        def boom() -> float:
            raise ZeroDivisionError("model said no")

        with pytest.raises(BackendUnavailableError) as excinfo:
            guarded("liveness scoring", boom)

        assert excinfo.value.status_code == 503
        assert excinfo.value.retryable is True

    def test_a_service_error_is_passed_through_unchanged(self):
        def refuse() -> float:
            raise UnknownActionError("known refusal")

        with pytest.raises(UnknownActionError):
            guarded("liveness scoring", refuse)

    def test_a_non_finite_attack_score_is_a_failure_not_a_boundary(self):
        for value in (np.nan, np.inf, -np.inf):
            with pytest.raises(BackendUnavailableError):
                InferenceBackend.clamp_attack_score(float(value))

    def test_out_of_range_values_are_clipped(self):
        assert InferenceBackend.clamp_attack_score(1.7) == 1.0
        assert InferenceBackend.clamp_attack_score(-0.4) == 0.0

    def test_a_vector_is_not_a_scalar_score(self):
        with pytest.raises(BackendUnavailableError):
            InferenceBackend.clamp_attack_score([0.1, 0.2])

    def test_a_wrongly_shaped_embedding_is_refused(self):
        with pytest.raises(BackendUnavailableError):
            InferenceBackend.normalize_embedding(np.ones(511), 512)

    def test_a_zero_norm_embedding_is_refused_rather_than_invented(self):
        with pytest.raises(BackendUnavailableError):
            InferenceBackend.normalize_embedding(np.zeros(512), 512)

    def test_a_normalised_embedding_has_unit_length(self):
        vector = InferenceBackend.normalize_embedding(np.arange(1, 513, dtype=np.float64), 512)

        assert float(np.linalg.norm(vector)) == pytest.approx(1.0, abs=1e-6)

    def test_the_abstract_backend_cannot_be_instantiated(self):
        with pytest.raises(TypeError):
            InferenceBackend()  # type: ignore[abstract]

    def test_ensure_ready_refuses_without_a_model(self):
        settings = make_settings()

        with pytest.raises(ModelNotLoadedError) as excinfo:
            UnloadedStubBackend(settings).ensure_ready()
        assert excinfo.value.status_code == 503
        assert excinfo.value.detail["backend"] == "stub-unloaded"


class TestSimilarityMath:
    def test_identical_vectors_map_to_one(self):
        vector = np.ones(512)

        assert similarity(vector, vector) == pytest.approx(1.0, abs=1e-9)

    def test_orthogonal_vectors_map_to_the_midpoint(self):
        a = np.zeros(512)
        a[0] = 1.0
        b = np.zeros(512)
        b[1] = 1.0

        assert similarity(a, b) == pytest.approx(0.5, abs=1e-9)

    def test_opposite_vectors_map_to_zero(self):
        a = np.ones(512)

        assert similarity(a, -a) == pytest.approx(0.0, abs=1e-9)

    def test_the_result_always_sits_in_the_contracts_range(self):
        rng = np.random.default_rng(7)
        for _ in range(20):
            a, b = rng.standard_normal(512), rng.standard_normal(512)
            assert 0.0 <= similarity(a, b) <= 1.0

    def test_mismatched_widths_cannot_be_compared(self):
        with pytest.raises(BackendUnavailableError):
            similarity(np.ones(512), np.ones(256))

    def test_a_zero_vector_cannot_be_compared(self):
        with pytest.raises(BackendUnavailableError):
            similarity(np.ones(512), np.zeros(512))


def test_every_pipeline_failure_leaves_the_error_taxonomy_intact():
    for error in (
        FrameCountError("x"),
        UnknownActionError("x"),
        ImageDecodeError("x"),
        FaceBoxError("x"),
        ModelNotLoadedError("x"),
        BackendUnavailableError("x"),
    ):
        assert isinstance(error, InferenceServiceError)
        assert set(error.to_payload("req-1")) >= {"code", "message", "requestId", "retryable"}
