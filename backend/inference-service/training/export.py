"""Export the two SavedModels that ``INFERENCE_BACKEND=tf`` loads.

The shapes and names here are not decorative: ``app/models/tf_backend.py`` binds the serving input
by name from a hint list (``frames``, ``image``, ...) and picks the output by name too (``attack``
for liveness, ``embedding`` for the face vector), then calls the signature with keyword arguments.
A bundle exported with different names still loads — the backend just falls back to whichever
entry comes first, and a model that silently reads the wrong tensor is the worst kind of bug in a
verification system. So the names are part of the contract, and ``verify()`` reloads the written
bundle the same way the backend does and asserts them before anything is reported as exported.

What gets exported is an **untrained architecture**: this repository has no labelled face corpus
and no presentation-attack corpus, so a metric printed from it would be fabricated. The bundles
are what the service runs once trained weights replace these ones; until then they exist to prove
the serving path (shapes, dtypes, warm-up, manifest versioning) works.

Usage (Python 3.12 with ``requirements-tf.txt``; TensorFlow is imported lazily so this module
still compiles on a box without it)::

    python training/export.py --out-dir models
    python training/export.py --out-dir models --size 112 --sequence-length 12 --embedding-dim 512

Each directory gets a SavedModel plus ``model_manifest.json``, whose ``name`` is exactly the kind
so ``tf_backend._version_for`` uses ``version`` verbatim as the string stamped into
``decision_records``.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from typing import Any

import numpy as np

#: Must match ``tf_backend.MANIFEST_FILE``.
MANIFEST_FILE = "model_manifest.json"

#: Defaults mirror ``app/config.py`` (MODEL_INPUT_SIZE / MODEL_SEQUENCE_LENGTH / EMBEDDING_DIM).
#: Duplicated rather than imported so the training image needs no pydantic-settings; a drift
#: between the two shows up as a warm-up shape error in ``verify``.
DEFAULT_SIZE = 112
DEFAULT_SEQUENCE_LENGTH = 12
DEFAULT_EMBEDDING_DIM = 512


def _tf() -> Any:
    try:
        import tensorflow as tf  # noqa: PLC0415 - lazy for the same reason as tf_backend
    except ImportError as exc:
        raise SystemExit(
            "exporting needs TensorFlow, which this interpreter cannot import. Build the "
            "python:3.12 image from requirements-tf.txt and run it against the repo, e.g.\n"
            "  docker build -t fayda-inference .\n"
            f"  docker run --rm -v {os.getcwd()}:/work -w /work fayda-inference "
            "python training/export.py --out-dir models\n"
            "TensorFlow publishes no wheels for newer interpreters."
        ) from exc
    return tf


def build_liveness(tf: Any, *, size: int, sequence_length: int) -> Any:
    """Clip -> one attack probability.

    A 3D convolution is the whole point: it sees consecutive frames as neighbours, which is the
    difference between a live face and a printed photo held up to the camera. Pooling keeps time at
    full resolution (``MaxPooling3D((1, 2, 2))``) so the temporal kernel never averages away the
    motion it is there to detect.
    """
    layers, Model = tf.keras.layers, tf.keras.Model
    frames = layers.Input(shape=(sequence_length, size, size, 3), name="frames")
    x = layers.Conv3D(16, 3, activation="relu", padding="same")(frames)
    x = layers.MaxPooling3D(pool_size=(1, 2, 2))(x)
    x = layers.Conv3D(32, 3, activation="relu", padding="same")(x)
    x = layers.GlobalMaxPooling3D()(x)
    x = layers.Dense(64, activation="relu")(x)
    # Named so tf_backend's "attack" hint binds this output rather than the first tensor it sees.
    attack = layers.Dense(1, activation="sigmoid", name="attack_probability")(x)
    return Model(inputs=frames, outputs=attack, name="liveness")


def build_embedding(tf: Any, *, size: int, embedding_dim: int) -> Any:
    """Face crop -> an ``embedding_dim`` vector.

    The head is linear: CONTRACT §4 compares cosine similarity and the backend L2-normalises after
    loading regardless, so normalising here as well would only create a second place for the two
    sides to disagree.
    """
    layers, Model = tf.keras.layers, tf.keras.Model
    image = layers.Input(shape=(size, size, 3), name="image")
    x = layers.Conv2D(32, 3, activation="relu", padding="same")(image)
    x = layers.MaxPooling2D()(x)
    x = layers.Conv2D(64, 3, activation="relu", padding="same")(x)
    x = layers.MaxPooling2D()(x)
    x = layers.Conv2D(128, 3, activation="relu", padding="same")(x)
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dense(256, activation="relu")(x)
    vector = layers.Dense(embedding_dim, name="embedding")(x)
    return Model(inputs=image, outputs=vector, name="embedding")


def export(tf: Any, model: Any, directory: str, *, input_name: str, output_name: str,
           spec: list[int]) -> None:
    """Write a bundle whose ``serving_default`` takes one keyword and returns one named tensor."""
    concrete = tf.function(
        lambda batch: {output_name: model(batch, training=False)}
    ).get_concrete_function(
        **{input_name: tf.TensorSpec(shape=spec, dtype=tf.float32, name=input_name)}
    )
    os.makedirs(directory, exist_ok=True)
    tf.saved_model.save(model, directory, signatures={"serving_default": concrete})


def write_manifest(directory: str, *, kind: str, version: str, details: dict[str, Any]) -> str:
    payload = {
        "name": kind,
        "version": version,
        "exported_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        **details,
    }
    path = os.path.join(directory, MANIFEST_FILE)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return path


def verify(directory: str, tf: Any, *, input_name: str, output_name: str,
           batch: dict[str, Any]) -> None:
    """Reload the bundle exactly as ``TfBackend.load`` does, then run one inference through it."""
    model = tf.saved_model.load(directory)
    signature = model.signatures["serving_default"]
    _args, kwargs = signature.structured_input_signature
    inputs = list(kwargs)
    if inputs != [input_name]:
        raise SystemExit(
            f"{directory}: signature takes {inputs}, not {input_name!r}; tf_backend would bind "
            "the wrong tensor."
        )
    outputs = list(signature.structured_outputs)
    if outputs != [output_name]:
        raise SystemExit(
            f"{directory}: signature exposes {outputs}; tf_backend's hint list looks for "
            f"{output_name!r}."
        )
    result = signature(**batch)
    shapes = {name: tuple(value.shape) for name, value in result.items()}
    print(f"{os.path.basename(directory)}: {input_name} -> {shapes}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", required=True, help="where the two model directories go")
    parser.add_argument("--size", type=int, default=DEFAULT_SIZE)
    parser.add_argument("--sequence-length", type=int, default=DEFAULT_SEQUENCE_LENGTH)
    parser.add_argument("--embedding-dim", type=int, default=DEFAULT_EMBEDDING_DIM)
    parser.add_argument("--liveness-version", default="liveness-untrained-0")
    parser.add_argument("--embedding-version", default="embedding-untrained-0")
    args = parser.parse_args(argv)

    if args.size < 8 or args.sequence_length < 3 or args.embedding_dim < 16:
        # Two 3x3 convs each halve nothing (same padding) but the pools halve twice, and the
        # temporal kernel needs three frames; below these the graph cannot be built.
        parser.error("need --size >= 8, --sequence-length >= 3, --embedding-dim >= 16")

    tf = _tf()
    liveness_dir = os.path.join(args.out_dir, "liveness")
    embedding_dir = os.path.join(args.out_dir, "embedding")

    export(
        tf,
        build_liveness(tf, size=args.size, sequence_length=args.sequence_length),
        liveness_dir,
        input_name="frames",
        output_name="attack_probability",
        spec=[None, args.sequence_length, args.size, args.size, 3],
    )
    export(
        tf,
        build_embedding(tf, size=args.size, embedding_dim=args.embedding_dim),
        embedding_dir,
        input_name="image",
        output_name="embedding",
        spec=[None, args.size, args.size, 3],
    )

    print(write_manifest(liveness_dir, kind="liveness", version=args.liveness_version,
                         details={"input_size": args.size,
                                  "sequence_length": args.sequence_length,
                                  "trained": False}))
    print(write_manifest(embedding_dir, kind="embedding", version=args.embedding_version,
                         details={"input_size": args.size,
                                  "embedding_dim": args.embedding_dim,
                                  "trained": False}))

    verify(liveness_dir, tf, input_name="frames", output_name="attack_probability",
           batch={"frames": tf.constant(
               np.zeros((1, args.sequence_length, args.size, args.size, 3), dtype=np.float32))})
    verify(embedding_dir, tf, input_name="image", output_name="embedding",
           batch={"image": tf.constant(np.zeros((1, args.size, args.size, 3), dtype=np.float32))})
    print("bundles reload and warm up the way tf_backend expects; weights are untrained")
    return 0


if __name__ == "__main__":
    sys.exit(main())
