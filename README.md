# Fayda-ID-Check

National ID verification mobile app with AI-powered liveness detection, face
matching, and secure biometric authentication for government use.

It is built so a photo of a photo cannot pass, and biometric data never leaves
the device unencrypted.

The written case study is `index.html` at the repository root; the interface every
component must honour is `docs/CONTRACT.md`. This README covers running the code.

## Components

| Path | What it is | Runs on |
| --- | --- | --- |
| `mobile/` | Flutter client: guided capture, on-device quality gate, payload encryption | Flutter 3.29+ |
| `backend/verification-service/` | Spring Boot: identity, attempt state machine, decision composite, audit | JDK 21+, Maven |
| `backend/inference-service/` | FastAPI: liveness + face-embedding inference, stateless | Python 3.12 (TF) / 3.14 (stub) |
| `backend/verification-service/src/main/resources/db/migration/` | MS SQL baseline schema + seeded threshold profile | SQL Server 2019+ |
| `docs/CONTRACT.md` | The interface every component must honour | — |

The hard boundary: **Spring Boot owns identity and audit, TensorFlow owns inference
and nothing else.** The inference service opens no database connection and sees no
person-identifying field, so its logs cannot be joined back to a subject.

## Run it

### Full stack (needs Docker)

```bash
cp .env.example .env      # fill in the three secrets; see comments in the file
docker compose up --build
```

- verification API: `http://localhost:8080/api/v1`
- inference API: `http://localhost:8000`
- MS SQL: `localhost:11433` (`sa`)

Flyway applies `backend/verification-service/src/main/resources/db/migration`
on first boot and seeds the threshold profile.
The `db` container exits immediately if `MSSQL_SA_PASSWORD` fails SQL Server's
complexity rules — that is the usual cause of a hung `docker compose up`.

### Without Docker

Both services are testable with no database and no GPU:

```bash
cd backend/inference-service && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
INFERENCE_BACKEND=stub .venv/Scripts/uvicorn app.main:app --port 8000

cd ../verification-service && mvn spring-boot:run   # H2 profile, no MS SQL needed
cd ../../mobile && flutter pub get && flutter test
```

`INFERENCE_BACKEND=stub` selects a deterministic numpy heuristic instead of
TensorFlow so the service runs on a Python that has no TF wheels. **It is a
development stand-in and makes no accuracy claim** — real scores require the
trained models below.

## Tests

```bash
cd backend/verification-service && mvn test
cd backend/inference-service   && python -m pytest -q
cd mobile                      && flutter analyze && flutter test
```

Three of these tests enforce invariants rather than behaviour, and they are the
reason the privacy claims in the case study are checkable:

- the retention test walks every JPA entity and fails if any `byte[]`/Blob column exists;
- the statelessness test monkeypatches file writes and DB clients, then runs a full `/verify`;
- the envelope tests reject a tampered tag and a `sentAtUtc` outside the 120 s replay window.

## Models

`backend/inference-service/training/` holds the two scripts the model path needs:
an evaluator for thresholds and an exporter for the SavedModel bundles.

```bash
python training/evaluate.py --kind liveness --input attacks.csv   # FAR/FRR per threshold + AUC
python training/evaluate.py --self-test                            # checks the maths, needs only numpy
python training/export.py --out-dir models                         # SavedModels + pinned manifest
```

`evaluate.py` runs on any interpreter with numpy, and `--self-test` is part of that
file's own checks. `export.py` needs TensorFlow, so it runs on the Python 3.12 image
built from `requirements-tf.txt`, not on the stub path.

There is deliberately no training script. The repository has no labelled face corpus
and no presentation-attack corpus, so `export.py` writes untrained architectures and
stamps `"trained": false` in each manifest: the bundles prove the serving path
(signatures, shapes, warm-up, versioning) without pretending to prove accuracy.

Thresholds are chosen from that evaluation: target false-accept rate first, then
minimise false rejects inside it, because a false accept is the expensive error.
`thresholdVersion` is stamped on every decision so a future re-tuning never
silently rewrites what history means.

## Status

Working code with tests, built as a portfolio system. Not a deployed service:
the models are untrained, there is no real Fayda document corpus behind the
evaluation, and no presentation-attack test suite has been run against hardware.
Treat the scores as plumbing that is correct, not as accuracy that is proven.

Run and passing on this machine:

- `verification-service`: 63 Maven tests, including the attempt-flow integration
  test that drives `POST /attempts` through capture, verify and retry;
- `inference-service`: 149 pytest tests (stub backend, contract, decision rules);
- `mobile`: `flutter analyze` reports no issues and 21 tests pass — envelope bytes,
  the controller's key/`keyId`/frame-count invariants, and the consent gate;
- `training/evaluate.py`: `--self-test` for both metric kinds, plus CSV input.

Not run here, so not claimed:

- `training/export.py` — TensorFlow has no wheel for this interpreter, so the file is
  byte-compiled only; the SavedModel export has to be executed in the 3.12 image;
- the containers in `docker-compose.yml` (the local Docker daemon is off). The Maven tests run
  against H2 in `MSSQLServer` compatibility mode with Hibernate's `create-drop` schema, and
  `spring.flyway.enabled` defaults to `false` — so `V1__baseline.sql` has not been applied by
  anything here. It needs a real MS SQL instance: `FLYWAY_ENABLED=true` with `DB_URL` pointing at
  one, on the `sqlserver` profile;
- the camera paths on real hardware: `camera_adapter.dart` and the two capture screens
  have never rendered on a device, so the framing gate's behaviour under live preview is
  reasoned about in code and tests, not observed.
