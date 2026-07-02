# SoNex server (Phases 3–4)

FastAPI + SQLAlchemy(async) + Alembic + Redis. Implements `docs/api-contract.md`.

## Run locally
```bash
docker compose up          # api + postgres + redis
# or, dev mode:
python -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env       # fill secrets
.venv/bin/uvicorn app.main:app --reload
```

## Tests
```bash
.venv/bin/python -m pytest -q     # 28 tests; SQLite + fakeredis + mock storage
```

## Layout
- `app/` — routers (`auth`, `device_api`, `portal`), models, security (JWT +
  per-device API keys), `storage.py` (Cloudinary → R2 failover, LocalDisk for dev),
  `redisq.py` (device state, rate limit, job queue).
- `training/` — per-home pipeline: tunes thresholds from labelled events +
  override signals, trains a logistic-regression classifier over VAD/YAMNet
  features, exports **ONNX** (skl2onnx; JSON artifact fallback if the import
  fails), registers a new versioned `models` row with sha256 so the OTA
  manifest bumps automatically. Old versions are kept for rollback.
- `alembic/` — schema migrations (Postgres in prod; tests run on SQLite).

## Deploy
`render.yaml` provisions web service + Postgres + Redis on Render. Set
`CLOUDINARY_URL`, `R2_*`, `JWT_SECRET` (32+ bytes) in the dashboard.

## Notes
- Consent gating: clip upload 403s unless the device's home holds an active
  `upload_clips` consent row. All purposes default to denied.
- `POST /v1/data/delete` removes DB rows **and** stored objects.
- Rate limiting is Redis-based per device key.
