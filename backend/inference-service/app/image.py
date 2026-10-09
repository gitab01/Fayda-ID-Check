"""Byte-safe image handling: base64 -> ndarray -> model input.

Everything here is pure and in-memory. No temp files, no cache on disk (CONTRACT.md §2:
"Frames are decoded in memory and dropped when the response is written").

Conventions:
* a *decoded image* is ``uint8`` ``(height, width, 3)`` in RGB;
* a *model frame* is ``float32`` ``(size, size, 3)`` in ``[0, 1]``;
* a *liveness sequence* is ``float32`` ``(n_frames, size, size, 3)`` in ``[0, 1]``;
* ``faceBox`` is ``[x, y, w, h]`` in source-image pixels, as sent by the app.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import io
import re
from collections.abc import Sequence

import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

from .errors import FaceBoxError, ImageDecodeError

_DATA_URL_RE = re.compile(r"^data:[^,]*;base64,", re.IGNORECASE)
_WHITESPACE_RE = re.compile(r"\s+")

#: Luma weights used to build the grayscale view that the liveness statistics run on.
_LUMA = np.array([0.299, 0.587, 0.114], dtype=np.float64)


def decode_base64_image(
    value: str | bytes,
    *,
    max_pixels: int,
    label: str = "image",
) -> np.ndarray:
    """Decode a base64 JPEG/PNG into an RGB uint8 array.

    Rejects (``ImageDecodeError`` -> 422) rather than substituting a blank frame: a
    silently blank frame would become a *score* downstream.
    """
    if isinstance(value, bytes):
        try:
            text = value.decode("ascii")
        except UnicodeDecodeError as exc:
            raise ImageDecodeError(f"{label} is not valid ascii base64", detail={"label": label}) from exc
    elif isinstance(value, str):
        text = value
    else:
        raise ImageDecodeError(f"{label} must be a base64 string", detail={"label": label})

    text = _DATA_URL_RE.sub("", text.strip(), count=1)
    # base64 payloads are routinely wrapped; strip whitespace but keep padding intact.
    text = _WHITESPACE_RE.sub("", text)
    if not text:
        raise ImageDecodeError(f"{label} is empty", detail={"label": label})

    try:
        raw = base64.b64decode(text, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ImageDecodeError(
            f"{label} is not valid base64",
            detail={"label": label, "reason": "base64"},
        ) from exc
    if not raw:
        raise ImageDecodeError(f"{label} decoded to zero bytes", detail={"label": label})

    return decode_bytes_image(raw, max_pixels=max_pixels, label=label)


def decode_bytes_image(raw: bytes, *, max_pixels: int, label: str = "image") -> np.ndarray:
    """Rasterise encoded image bytes into an RGB uint8 array, with a pixel ceiling."""
    try:
        with Image.open(io.BytesIO(raw)) as handle:
            # `size` is available before the pixel data is decompressed, so the
            # decompression-bomb guard runs before the expensive part.
            width, height = handle.size
            if width <= 0 or height <= 0:
                raise ImageDecodeError(
                    f"{label} has zero dimensions", detail={"label": label}
                )
            if width * height > max_pixels:
                raise ImageDecodeError(
                    f"{label} exceeds the {max_pixels} pixel ceiling",
                    detail={"label": label, "pixels": width * height},
                )
            handle = ImageOps.exif_transpose(handle)
            rgb = handle.convert("RGB")
            array = np.asarray(rgb, dtype=np.uint8)
    except ImageDecodeError:
        raise
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise ImageDecodeError(
            f"{label} is not a decodable image",
            detail={"label": label, "reason": exc.__class__.__name__},
        ) from exc

    if array.ndim != 3 or array.shape[2] != 3 or array.size == 0:
        raise ImageDecodeError(f"{label} decoded to an unusable shape", detail={"label": label})
    return array


def crop_to_face_box(
    image: np.ndarray,
    face_box: Sequence[int] | None,
    *,
    min_overlap_ratio: float = 0.5,
    min_side: int = 8,
) -> np.ndarray:
    """Crop to ``[x, y, w, h]`` with bounds checks.

    A box that hangs off an edge is clamped (a phone can report a face touching the
    frame). A box that is *mostly* outside, has a non-positive side, or leaves less
    than ``min_side`` usable pixels is rejected with ``FaceBoxError`` — an
    out-of-bounds box means the caller's detector and our image disagree, and
    embedding a background patch would be worse than failing.
    """
    height, width = image.shape[0], image.shape[1]
    if face_box is None:
        return image

    values = list(face_box)
    if len(values) != 4:
        raise FaceBoxError(
            "faceBox must have exactly 4 values [x, y, w, h]", detail={"got": len(values)}
        )
    cleaned: list[int] = []
    for value in values:
        if isinstance(value, bool) or not isinstance(value, (int, np.integer)):
            # Reject floats masquerading as ints so the geometry is unambiguous.
            if isinstance(value, float) and float(value).is_integer():
                cleaned.append(int(value))
                continue
            raise FaceBoxError("faceBox values must be integers", detail={"value": value})
        cleaned.append(int(value))

    x, y, w, h = cleaned
    if w <= 0 or h <= 0:
        raise FaceBoxError(
            "faceBox width and height must be positive",
            detail={"w": w, "h": h},
        )

    left, top = max(x, 0), max(y, 0)
    right, bottom = min(x + w, width), min(y + h, height)
    crop_w, crop_h = right - left, bottom - top
    if crop_w <= 0 or crop_h <= 0:
        raise FaceBoxError(
            "faceBox lies entirely outside the image",
            detail={"imageSize": [width, height], "faceBox": cleaned},
        )
    requested_area = w * h
    overlap_ratio = (crop_w * crop_h) / requested_area
    if overlap_ratio < min_overlap_ratio:
        raise FaceBoxError(
            "faceBox is mostly outside the image",
            detail={
                "imageSize": [width, height],
                "faceBox": cleaned,
                "overlapRatio": round(overlap_ratio, 4),
            },
        )
    if crop_w < min_side or crop_h < min_side:
        raise FaceBoxError(
            "faceBox leaves too few pixels to embed",
            detail={"cropSize": [crop_w, crop_h], "minSide": min_side},
        )
    return image[top:bottom, left:right]


def to_model_frame(image: np.ndarray, size: int) -> np.ndarray:
    """Downscale an RGB uint8 image to a ``(size, size, 3)`` float32 frame in [0, 1]."""
    if image.ndim == 2:
        image = np.repeat(image[:, :, None], 3, axis=2)
    if image.dtype != np.uint8:
        image = np.clip(image, 0, 255).astype(np.uint8)
    resized = Image.fromarray(image).resize((size, size), resample=Image.BILINEAR)
    return (np.asarray(resized, dtype=np.float32)) / 255.0


def prepare_frames(images: Sequence[np.ndarray], size: int) -> np.ndarray:
    """Downscale a list of decoded images to ``(len(images), size, size, 3)``."""
    if not len(images):  # pragma: no cover - guarded by request validation
        raise ImageDecodeError("no frames to prepare")
    return np.stack([to_model_frame(image, size) for image in images], axis=0)


def tile_sequence(frames: np.ndarray, size: int, n_frames: int) -> np.ndarray:
    """Return exactly ``n_frames`` frames, tiling cyclically when shorter.

    The model input is a fixed-length clip. Tiling in order (``f0,f1,f2,f0,...``) keeps
    consecutive-frame motion intact and only adds one wrap-around transition, unlike
    repeating each frame in place which would understate motion.
    """
    if frames.ndim != 4 or frames.shape[1] != size:
        raise ImageDecodeError("frames must be a (n, size, size, 3) array")
    count = frames.shape[0]
    if count == n_frames:
        return frames
    if count > n_frames:
        # Keep an even sample of the clip rather than only its first moments.
        idx = np.linspace(0, count - 1, n_frames, dtype=int)
        return frames[idx]
    reps = -(-n_frames // count)  # ceil
    return np.tile(frames, (reps, 1, 1, 1))[:n_frames]


def frames_to_grayscale(frames: np.ndarray) -> np.ndarray:
    """``(n, h, w, 3)`` -> ``(n, h, w)`` float32 luma in [0, 1]."""
    return np.asarray(frames @ _LUMA, dtype=np.float32)


def frame_to_grayscale(frame: np.ndarray) -> np.ndarray:
    """``(h, w, 3)`` -> ``(h, w)`` float32 luma in [0, 1]."""
    if frame.ndim == 2:
        return frame.astype(np.float32)
    return np.asarray(frame @ _LUMA, dtype=np.float32)


def digest(array: np.ndarray) -> str:
    """SHA-256 of an array's quantised bytes.

    Used only as a debug identity of a frame (never logged by the request logger and
    never returned to a caller): a digest of a downscaled frame is not reversible, but
    there is no reason to move it across the wire.
    """
    quantised = np.ascontiguousarray(np.clip(array * 255.0, 0, 255).astype(np.uint8))
    return hashlib.sha256(quantised.tobytes()).hexdigest()
