"""``POST /v1/liveness`` — attack score for a captured clip."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from ..config import Settings
from ..models.base import InferenceBackend
from ..pipeline import get_backend, get_settings_dep, score_liveness
from ..schemas import LivenessRequest, LivenessResponse, LivenessSignalsModel

router = APIRouter(tags=["inference"])


@router.post("/liveness", response_model=LivenessResponse)
def liveness(
    payload: LivenessRequest,
    request: Request,
    settings: Settings = Depends(get_settings_dep),
    backend: InferenceBackend = Depends(get_backend),
) -> LivenessResponse:
    result = score_liveness(
        settings,
        backend,
        frames=payload.frames,
        expected_actions=payload.expected_actions,
    )
    return LivenessResponse(
        request_id=payload.request_id,
        attack_score=result.attack_score,
        signals=LivenessSignalsModel(
            texture=result.signals.texture,
            moire=result.signals.moire,
            blink_executed=result.signals.blink_executed,
        ),
        model_version=backend.liveness_model_version,
    )
