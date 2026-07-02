# SoNex API contract (v1)

Base path: `/v1`. JSON unless noted. Server: FastAPI (`/server`).

## Auth
- **Portal/dev users:** `POST /v1/auth/signup {email, password}` → `201 {access_token, ...}` (409 duplicate, 422 short password); `POST /v1/auth/login {email, password}` → `{access_token, token_type:"bearer"}` (JWT, HS256, `sub`=user id, `exp`). Send as `Authorization: Bearer <jwt>`.
- **Devices:** per-device API key, header `X-Device-Key: <key>`. Issued by `POST /v1/devices/register {device_name, home_name}` → `{device_id, api_key}`.

## Events (device auth)
`POST /v1/events` — detection events + user overrides. **No raw audio.**
```json
{"events":[{"ts":"2026-07-02T12:00:00Z","type":"STATE_CHANGE|OVERRIDE|CALL",
  "room_state":"QUIET|TALKING|BOOST","action":"DUCK|BOOST|RESTORE|MUTE|PAUSE|RESUME",
  "level":30,"db":-32.5,"source":"engine|user","detail":"..."}]}
```
→ `202 {accepted: n}`.

## Clips (device auth, consent-gated)
`POST /v1/clips` — multipart: `file` (audio), `meta` (JSON string: `{ts, duration_ms, label?, room_state?}`).
Rejected `403` unless the device's home has an active `upload_clips` consent.
→ `201 {clip_id, storage_key, backend:"cloudinary"|"r2"}`.

## Models / OTA (device auth)
- `GET /v1/models/manifest?device=<id>` → per-home manifest, falls back to global:
```json
{"models":{"vad":{"id":1,"file":"silero_vad.onnx","version":"1.0","sha256":"...","min_app_version":1,"url":"/v1/models/1/download"},
           "sound":{...},"home":{...}},
 "thresholds":{"sensitivity":0.5,"boost_margin_db":8.0}}
```
- `GET /v1/models/{id}/download` → model bytes (or 307 to signed URL).

## Privacy (device or user auth)
- `POST /v1/data/delete` → deletes the caller's rows + stored objects → `{deleted: true, ...counts}`.
- `GET /v1/data/export` → JSON dump of everything held for the caller.

## Consents (device auth)
`PUT /v1/consents {purpose:"upload_clips|telemetry|training|wake_word", granted:bool}` → `200`.
All purposes default **absent = denied**.

## Portal endpoints (user JWT)
- `GET /v1/clips?home_id=` — clip metadata list; `GET /v1/clips/{id}/audio` — bytes for waveform.
- `POST /v1/labels {clip_id|event_id, label, correct:bool}` — training review.
- `GET /v1/metrics?home_id=&days=` — accuracy metrics time series.
- `GET /v1/models` / `POST /v1/models/{id}/promote` / `POST /v1/models/{id}/rollback`.
- `GET /v1/devices` — devices, current state (from Redis), last-seen.
- `POST /v1/training/run {home_id}` — enqueue a training job → `{job_id}`.

## Errors
`{"detail": "..."}` with proper status codes: 401 bad/missing auth, 403 consent/permission, 404, 422 validation.
