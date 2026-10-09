"""Fayda-ID Check inference service.

FastAPI process that turns biometric image frames into two floats:
an ``attackScore`` (liveness) and a ``similarity`` (face match).

Hard invariants, all of them mirrored by tests:

* **stateless** — no database client, no disk writes, no cross-request state.
* **no identity** — requests and logs carry a request-scoped id and nothing else.
* **never a default score** — if a model is not loaded the service answers 503.
"""

__all__ = ["__version__"]

__version__ = "1.0.0"
