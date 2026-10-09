"""``POST /v1/verify`` — liveness + embedding in one round trip.

Returns floats and version stamps only. No image bytes and nothing reversible into an
image ever leaves this process (CONTRACT.md §2).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from ..config import Settings
from ..models.base import InferenceBackend
from ..pipeline import (
    embed_face,
    embed_reference,
    embed_similarity,
    get_backend,
    get_settings_dep,
    score_liveness,
)
from ..schemas import LivenessSignalsModel, VerifyRequest, VerifyResponse

router = APIRouter(tags=["inference"])


@router.post("/verify", response_model=VerifyResponse)
def verify(
    payload: VerifyRequest,
    request: Request,
    settings: Settings = Depends(get_settings_dep),
    backend: InferenceBackend = Depends(get_backend),
) -> VerifyResponse:
    # Challenge validation first: a request we cannot interpret must fail before we spend
    # a decode + two inference passes on it.
    result = score_liveness(
        settings,
        backend,
        frames=payload.frames,
        expected_actions=payload.expected_actions,
    )
    probe = embed_face(settings, backend, image=payload.image, face_box=payload.face_box)
    reference = embed_reference(
        settings,
        backend,
        reference_embedding=payload.reference_embedding,
        reference_image=payload.reference_image,
    )
    score = embed_similarity(backend, embedding=probe, reference=reference)

    return VerifyResponse(
        request_id=payload.request_id,
        attack_score=result.attack_score,
        similarity=score,
        signals=LivenessSignalsModel(
            texture=result.signals.texture,
            moire=result.signals.moire,
            blink_executed=result.signals.blink_executed,
        ),
        liveness_model_version=backend.liveness_model_version,
        embedding_model_version=backend.embedding_model_version,
    )
