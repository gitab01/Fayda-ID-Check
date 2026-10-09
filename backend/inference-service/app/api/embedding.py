"""``POST /v1/embedding`` — 512-d L2-normalised face embedding."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from ..config import Settings
from ..models.base import InferenceBackend
from ..pipeline import embed_face, get_backend, get_settings_dep
from ..schemas import EmbeddingRequest, EmbeddingResponse

router = APIRouter(tags=["inference"])


@router.post("/embedding", response_model=EmbeddingResponse)
def embedding(
    payload: EmbeddingRequest,
    request: Request,
    settings: Settings = Depends(get_settings_dep),
    backend: InferenceBackend = Depends(get_backend),
) -> EmbeddingResponse:
    vector = embed_face(
        settings, backend, image=payload.image, face_box=payload.face_box
    )
    return EmbeddingResponse(
        request_id=payload.request_id,
        # Plain Python floats: numpy scalars are not JSON serialisable by contract.
        embedding=[float(value) for value in vector],
        model_version=backend.embedding_model_version,
    )
