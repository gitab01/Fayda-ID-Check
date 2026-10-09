# Fayda-ID Check — service contract

This file is the source of truth for anything crossing a process boundary.
If code and this document disagree, the code is wrong.

## Actors

| Component | Owns | Never owns |
| --- | --- | --- |
| Flutter app | capture, on-device quality gate, payload encryption | decision thresholds |
| Spring Boot verification service | identity, attempt lifecycle, audit, decision composite | inference |
| FastAPI inference service | liveness + embedding scores | any stored data, any user identity |
| MS SQL | scores, hashes, decision metadata | image bytes |

## Conventions

- All timestamps UTC, ISO-8601 with milliseconds.
- Every request carries `X-Request-Id` (UUID). The inference service logs it and nothing that identifies a person.
- Inference requests carry **no** user id, phone number or national id — ever. Only a request-scoped id.
- Scores are floats in `0..1`. An attack score of `0.9` means "almost certainly an attack".

---

## 1. Verification service — public API (mobile + reviewer clients)

Base: `/api/v1`. Auth: `Authorization: Bearer <JWT>` (HS256, issuer `fayda-id-check`,
`sub` = user id, `scope` = `subject` | `reviewer` | `system`).

### POST `/attempts`
Starts an attempt.

```json
{ "documentType": "NATIONAL_ID", "deviceInfo": "Pixel 6a / Android 14 / v1.2.0" }
```
Response `201`:
```json
{
  "attemptId": 4812,
  "status": "CREATED",
  "challenge": { "seed": "b7f3…", "actions": ["TURN_LEFT", "BLINK", "TILT_RIGHT"] },
  "uploadKey": { "keyId": "9f2c…", "algorithm": "AES-256-GCM", "expiresAtUtc": "…" },
  "stageDeadlineUtc": "…",
  "attemptNo": 3,
  "attemptsRemaining": 2
}
```
The `actions` order is derived server-side from `seed`; the client must follow it and
the server re-derives it on submit. A mismatch is a `CHALLENGE_SEQUENCE_MISMATCH` failure.
`keyId` is a hash of the per-attempt AES key the client generated before this call. The key
itself travels exactly once, over TLS, in the `X-Attempt-Key` request header of
`POST /attempts` (base64 of 32 raw bytes); it is never inside an envelope and never stored.

### POST `/attempts/{id}/document`
Multipart-free: body is the encryption envelope (§3). Decrypted payload:
```json
{
  "image": { "bytes64": "…", "width": 1280, "height": 720, "mimeType": "image/jpeg" },
  "ocr": { "idNumber": "…", "fullName": "…", "issuingRegion": "…", "expiryDate": "2030-04-01" },
  "quality": { "blurScore": 212.5, "glareRatio": 0.01, "cornersFound": true }
}
```
Response `202` `{ "attemptId": 4812, "status": "CREATED", "documentAccepted": true }`.
Images are held in memory only. The service stores `document_metadata` (hashes + facts), never the image.

### POST `/attempts/{id}/selfie`
Envelope-wrapped:
```json
{
  "frames": [ { "bytes64": "…", "action": "TURN_LEFT", "capturedAtMs": 1717000000123 } ],
  "executedActions": ["TURN_LEFT","BLINK","TILT_RIGHT"],
  "quality": { "blurScore": 180.0, "faceBox": [412,180,240,300] }
}
```
Requires ≥ 3 frames. Moves the attempt `CREATED → CAPTURED`.

### POST `/attempts/{id}/verify`
Triggers inference and the decision. `CAPTURED → INFERRING → DECIDED`.
Response `200`:
```json
{
  "attemptId": 4812,
  "decision": "REVIEW",
  "compositeScore": 0.7412,
  "scores": { "liveness": 0.06, "match": 0.81, "document": 1.0 },
  "reasonCode": "MATCH_IN_REVIEW_BAND",
  "guidance": "A reviewer will confirm this verification. No action needed from you.",
  "thresholdVersion": "2026-05-1",
  "decidedAtUtc": "…"
}
```

### GET `/attempts/{id}`
Current status + decision if present. Always writes an `audit_log` `RECORD_READ` row.

### POST `/attempts/{id}/retry`
Only valid when status is `FAILED_RETRYABLE`. Increments `attempt_no`; past
`max_attempts` the next decision is forced to `REVIEW`.

### Errors
`401` unauthenticated · `403` wrong scope · `404` unknown attempt ·
`409` wrong state for this transition · `422` payload failed validation ·
`429` rate limited · `503` inference unavailable (attempt goes to `FAILED_RETRYABLE`).

```json
{ "code": "ATTEMPT_NOT_CAPTURED", "message": "…", "requestId": "…", "retryable": false }
```

---

## 2. Inference service — internal API

Base: `/v1`. No auth token carries identity; network-level restriction only.

### GET `/health`
`200 { "status": "ok", "modelLoaded": true, "livenessModelVersion": "…", "embeddingModelVersion": "…" }`
`503` when the process is up but a model is not loaded. Liveness/readiness must differ:
an unloaded model must stop traffic, not return default scores.

### POST `/liveness`
```json
{ "requestId": "…", "frames": [ "<base64 jpeg>" ], "challengeSeed": "…", "expectedActions": ["TURN_LEFT","BLINK","TILT_RIGHT"] }
```
Response:
```json
{ "requestId": "…", "attackScore": 0.06, "signals": { "texture": 0.11, "moire": 0.03, "blinkExecuted": true },
  "modelVersion": "liveness-cnn-1.3.0" }
```

### POST `/embedding`
`{ "requestId": "…", "image": "<base64 jpeg>", "faceBox": [x,y,w,h] }` →
`{ "requestId": "…", "embedding": [ …512 floats, L2-normalised… ], "modelVersion": "arcface-r18-1.1.0" }`

### POST `/verify`
Both of the above in one round trip. Returns `attackScore`, `similarity`, both model versions.
Never returns image bytes or anything derived that could be reversed into an image.

### Rules
- Stateless. No database connection is opened by this service.
- Frames are decoded in memory and dropped when the response is written. No temp files.
- Max request body 8 MB; max 12 frames; images downscaled to the model input size on the client.
- `500` only for a genuine fault; a model failure returns `503`, never a default score.

---

## 3. Payload envelope (app → verification service)

TLS for transport, plus this envelope for payload-level encryption with the
per-attempt key the client generated before `POST /attempts` and delivered in that call's
`X-Attempt-Key` header (§1). The envelope below never contains the key, only its `keyId`:

```json
{
  "keyId": "9f2c…",
  "iv": "base64 12 bytes",
  "ciphertext": "base64 AES-256-GCM of the JSON body",
  "tag": "base64 16 bytes",
  "sentAtUtc": "…"
}
```
`sentAtUtc` older than 120 s is rejected (replay window). The server keeps the
decrypted body in memory for the duration of the attempt only.

---

## 4. Decision composite

`composite = w_l·(1 − attackScore) + w_m·similarity + w_d·documentScore`

Hard floors are applied **before** the composite:
- `attackScore > min_liveness` → `FAIL` / `PRESENTATION_ATTACK_SUSPECTED`
- `similarity < min_match` → `FAIL` / `FACE_MISMATCH`
- document checksum or MRZ invalid → `FAIL` / `DOCUMENT_INVALID`
- retries exhausted → `REVIEW` / `ATTEMPT_CAP_REACHED`

Then `composite ≥ pass_composite` → `PASS`; `≥ review_composite` → `REVIEW`; else `FAIL`.
Weights and thresholds come from the active `threshold_profiles` row; its `version`
is stamped onto the decision record so history stays interpretable.
