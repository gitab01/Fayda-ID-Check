# Production image for the inference service.
#
# Python 3.12, not the 3.14 used on a development host: TensorFlow publishes no cp314 wheels,
# which is exactly why INFERENCE_BACKEND=tf is only expected to work here (app/models/tf_backend.py
# turns a failed import into a startup configuration error rather than a silent stub fallback).
FROM python:3.12-slim

WORKDIR /srv/inference

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    INFERENCE_BACKEND=tf \
    LIVENESS_MODEL_DIR=/models/liveness \
    EMBEDDING_MODEL_DIR=/models/embedding

# libgomp1 is required at runtime by TensorFlow's CPU kernels and is absent from the slim base.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 inference \
    && useradd --system --uid 10001 --gid inference --home-dir /nonexistent --shell /usr/sbin/nologin inference

# Two-stage install: dependencies first, so editing app code does not re-resolve TensorFlow.
COPY requirements.txt requirements-tf.txt ./
RUN pip install --no-cache-dir -r requirements-tf.txt

COPY app ./app

# Model artifacts are mounted, never baked in: exporting a SavedModel is a training step, and an
# image digest should not change when only the model version does. If the directories are empty at
# startup the service refuses to become ready instead of scoring with a placeholder.
RUN mkdir -p /models/liveness /models/embedding \
    && chown -R inference:inference /models /srv/inference

USER inference
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
