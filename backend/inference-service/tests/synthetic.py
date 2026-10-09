"""Deterministic synthetic capture frames for the liveness ordering tests.

Two families, built to differ in exactly the ways the stub measures:

``live_clip``
    an unblurred face-shaped luminance map with per-frame sensor noise, a few pixels of
    frame-to-frame translation, and a blink on the last frame. High spatial detail, real
    temporal change, no periodic structure.

``screen_clip``
    the same face heavily blurred (texture gone), resampled onto a periodic lattice (a
    moiré line in the DFT), and repeated verbatim (zero temporal change). This is the
    "photo of a screen" presentation attack the composite must score higher.

``mix_clip(alpha)`` blends the two, which is how the continuity/monotonicity tests inject
the attack signal progressively.

Everything is encoded as lossless PNG so JPEG ringing cannot contaminate the high-
frequency statistics these tests assert on.
"""

from __future__ import annotations

import base64
import io

import numpy as np
from PIL import Image, ImageFilter

SOURCE_SIZE = 224
#: Lattice period in source pixels; becomes ~14 px at the 112 px model input, i.e. a
#: spectral bin inside the annulus the moiré test looks at.
SCREEN_PERIOD = 28
SCREEN_AMPLITUDE = 0.09
LIVE_NOISE_SIGMA = 0.06
LIVE_SEED = 11
CLIP_LENGTH = 4


def face_luminance(size: int = SOURCE_SIZE) -> np.ndarray:
    """A smooth head ellipse with two eye blobs and a mouth band, in ``[0, 1]``."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    image = 0.55 + 0.35 * np.exp(
        -(((xx - 0.50 * size) ** 2) / (2 * (0.22 * size) ** 2)
          + ((yy - 0.50 * size) ** 2) / (2 * (0.28 * size) ** 2))
    )
    for centre_x in (0.38, 0.62):
        image -= 0.30 * np.exp(
            -(((xx - centre_x * size) ** 2) / (2 * (0.035 * size) ** 2)
              + ((yy - 0.36 * size) ** 2) / (2 * (0.020 * size) ** 2))
        )
    image -= 0.22 * np.exp(
        -(((xx - 0.50 * size) ** 2) / (2 * (0.10 * size) ** 2)
          + ((yy - 0.68 * size) ** 2) / (2 * (0.020 * size) ** 2))
    )
    return np.clip(image, 0.05, 0.95)


def blink_variant(image: np.ndarray, size: int = SOURCE_SIZE) -> np.ndarray:
    """Lift the eye blobs toward skin tone: the closed-eye frame of a blink."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    out = image.copy()
    for centre_x in (0.38, 0.62):
        out += 0.25 * np.exp(
            -(((xx - centre_x * size) ** 2) / (2 * (0.04 * size) ** 2)
              + ((yy - 0.36 * size) ** 2) / (2 * (0.012 * size) ** 2))
        )
    return np.clip(out, 0.05, 0.95)


def with_screen_lattice(image: np.ndarray, amplitude: float, period: int = SCREEN_PERIOD) -> np.ndarray:
    size = image.shape[0]
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    lattice = np.cos(2 * np.pi * xx / period) + np.cos(2 * np.pi * yy / period)
    return np.clip(image * (1.0 + amplitude * lattice / 2.0), 0.0, 1.0)


def blur(image: np.ndarray, radius: float) -> np.ndarray:
    if radius <= 0:
        return image.copy()
    blurred = Image.fromarray(to_uint8(image)).filter(ImageFilter.GaussianBlur(radius))
    return np.asarray(blurred, dtype=np.float64) / 255.0


def to_uint8(image: np.ndarray) -> np.ndarray:
    return (np.clip(image, 0.0, 1.0) * 255.0).astype(np.uint8)


def live_frames(
    n: int = CLIP_LENGTH,
    *,
    sigma: float = LIVE_NOISE_SIGMA,
    jitter: int = 2,
    blink: bool = True,
    seed: int = LIVE_SEED,
) -> list[np.ndarray]:
    """Frames that behave like a live capture: detail, motion, and a blink."""
    rng = np.random.default_rng(seed)
    base = face_luminance()
    frames: list[np.ndarray] = []
    for index in range(n):
        shifted = np.roll(base, jitter * index, axis=1) if jitter else base
        noise = rng.normal(0.0, sigma, shifted.shape) if sigma else 0.0
        source = blink_variant(shifted) if (blink and index == n - 1) else shifted
        frames.append(to_uint8(source + noise))
    return frames


def screen_frames(
    n: int = CLIP_LENGTH,
    *,
    amplitude: float = SCREEN_AMPLITUDE,
    blur_radius: float = 3.0,
    period: int = SCREEN_PERIOD,
) -> list[np.ndarray]:
    """Frames that behave like a photo of a screen: blurred, periodic, motionless."""
    still = to_uint8(with_screen_lattice(blur(face_luminance(), blur_radius), amplitude, period))
    return [still.copy() for _ in range(n)]


def mix_frames(
    live: list[np.ndarray],
    screen: list[np.ndarray],
    alpha: float,
) -> list[np.ndarray]:
    """Blend a live clip and a screen clip: ``alpha=0`` is live, ``alpha=1`` is the attack."""
    alpha = float(min(1.0, max(0.0, alpha)))
    return [
        to_uint8((1.0 - alpha) * (l.astype(np.float64) / 255.0) + alpha * (s.astype(np.float64) / 255.0))
        for l, s in zip(live, screen)
    ]


def encode_png(frame: np.ndarray) -> str:
    buffer = io.BytesIO()
    Image.fromarray(frame.astype(np.uint8), mode="RGB").save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def encode_clip(frames: list[np.ndarray]) -> list[str]:
    return [encode_png(frame) for frame in frames]


def rgb(frame: np.ndarray) -> np.ndarray:
    """Promote a luminance map to a 3-channel image."""
    return np.stack([frame, frame, frame], axis=2)


def as_rgb(frames: list[np.ndarray]) -> list[np.ndarray]:
    return [rgb(frame) for frame in frames]


def live_clip(n: int = CLIP_LENGTH) -> list[str]:
    return encode_clip(as_rgb(live_frames(n=n)))


def screen_clip(n: int = CLIP_LENGTH) -> list[str]:
    return encode_clip(as_rgb(screen_frames(n=n)))


def mixed_clip(alpha: float, n: int = CLIP_LENGTH) -> list[str]:
    """Live clip blended ``alpha`` of the way toward the screen clip, base64 PNG encoded."""
    return encode_clip(as_rgb(mix_frames(live_frames(n=n), screen_frames(n=n), alpha)))
