"""Error taxonomy -> HTTP.

Every failure leaves this module as a JSON body shaped like CONTRACT.md §1:
``{ "code", "message", "requestId", "retryable" }``.

Two rules drive the split:
* a *model* problem is ``503`` and retryable — never a default score;
* a *caller* problem is ``413``/``422`` and not retryable.
"""

from __future__ import annotations

from typing import Any

DEFAULT_CODE = "INFERENCE_ERROR"


class InferenceServiceError(Exception):
    """Base class for anything this service reports to a caller."""

    code: str = DEFAULT_CODE
    status_code: int = 500
    retryable: bool = False
    #: Whether the error is a genuine fault (logged at ERROR) or expected traffic
    #: (logged at WARNING). Purely a logging concern, never part of the body.
    is_fault: bool = True

    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        status_code: int | None = None,
        retryable: bool | None = None,
        detail: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code
        if status_code is not None:
            self.status_code = status_code
        if retryable is not None:
            self.retryable = retryable
        self.detail = detail or {}

    def to_payload(self, request_id: str | None) -> dict[str, Any]:
        body: dict[str, Any] = {
            "code": self.code,
            "message": self.message,
            "requestId": request_id,
            "retryable": self.retryable,
        }
        body.update(self.detail)
        return body


# --- request validation (caller error, 422/413) ----------------------------


class PayloadTooLargeError(InferenceServiceError):
    code = "REQUEST_TOO_LARGE"
    status_code = 413
    retryable = False
    is_fault = False


class FrameCountError(InferenceServiceError):
    code = "FRAME_COUNT_INVALID"
    status_code = 422
    retryable = False
    is_fault = False


class UnknownActionError(InferenceServiceError):
    code = "EXPECTED_ACTION_UNKNOWN"
    status_code = 422
    retryable = False
    is_fault = False


class ImageDecodeError(InferenceServiceError):
    code = "IMAGE_DECODE_FAILED"
    status_code = 422
    retryable = False
    is_fault = False


class FaceBoxError(InferenceServiceError):
    code = "FACE_BOX_INVALID"
    status_code = 422
    retryable = False
    is_fault = False


class ReferenceInputError(InferenceServiceError):
    code = "REFERENCE_INPUT_INVALID"
    status_code = 422
    retryable = False
    is_fault = False


class InvalidRequestError(InferenceServiceError):
    code = "REQUEST_INVALID"
    status_code = 422
    retryable = False
    is_fault = False


# --- model / backend state (service error, 503) ----------------------------


class ModelNotLoadedError(InferenceServiceError):
    """Raised when scoring is requested while no model is resident.

    This is the refusal that stands in for a default score.
    """

    code = "MODEL_NOT_LOADED"
    status_code = 503
    retryable = True


class BackendUnavailableError(InferenceServiceError):
    """A loaded model failed to produce a usable output."""

    code = "INFERENCE_UNAVAILABLE"
    status_code = 503
    retryable = True


class InternalInferenceError(InferenceServiceError):
    code = "INTERNAL_ERROR"
    status_code = 500
    retryable = False


class BackendConfigurationError(InferenceServiceError):
    """Startup-time misconfiguration.

    Raised while building the backend, i.e. before the process serves traffic, so the
    container exits with a readable message instead of quietly degrading to another
    backend.
    """

    code = "BACKEND_CONFIGURATION_INVALID"
    status_code = 503
    retryable = False


def message_for(exc: Exception) -> str:
    text = str(exc).strip()
    return text or exc.__class__.__name__
