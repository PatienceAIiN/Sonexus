# BUILD_SONEX.md — Master Build Instructions for Claude Code

You are building **SoNex**, a room-aware adaptive-volume system. Read this whole
file first, then build **phase by phase in the order given**. After each phase:
compile/run it, write its tests, confirm they pass, and only then move on. Do not
scaffold later phases early. Prefer working vertical slices over broad stubs.

If a phase is blocked (e.g. a model file must be downloaded), stop and tell me
exactly what to do, then continue.

---

## 0. Product in one paragraph

Two Android apps + a server + a web portal. The **phone** listens to the room,
detects real human speech (or a phone call) happening over media, and automatically
lowers ("ducks") volume on the phone and a paired **Android TV**, then restores it
when quiet. If loud *non-speech* ambient noise rises, it raises volume instead.
Users pair phone↔TV with a 4-digit code over the same Wi-Fi. A **server (SoNex)**
collects consented data, trains per-home models, and pushes them over-the-air (no
app update). A login-gated **dev portal** visualises audio, training, and accuracy.
Voice control ("SoNex, lower volume") works in English and Hindi, on-device.

---

## Global rules

- **Languages/stack:** Android = Kotlin + Jetpack Compose (Material 3, min SDK 26).
  Server = Python 3.11 + FastAPI. Portal = React + Vite + TypeScript. DB = Postgres.
  Cache/queue = Redis. Storage = Cloudinary primary, Cloudflare R2 fallback.
- **Privacy is a hard constraint, not a feature.** All audio processed on-device by
  default. Nothing leaves the phone without an explicit, revocable, per-purpose
  consent toggle that is OFF by default. App must stay fully functional with all
  sharing off. Persistent notification whenever the mic is live.
- **Models are data, not code.** All ML runs from downloaded model files (`.onnx`/
  `.tflite`) selected at runtime by a version manifest. Retraining must never
  require an app update — only a new model file + manifest entry.
- **Test each phase.** Pure logic (thresholds, protocol, state machine, training
  math) gets real unit tests. Don't test framework glue.
- **Leave clear seams.** Detection has one classification function that models plug
  into. Output targets (phone/TV/BT/Cast) share one command interface.
- **Repo layout:**
  ```
  /android   (Gradle multi-module: :core, :app-mobile, :app-tv)
  /server    (FastAPI app, Alembic migrations, ML training, Docker, render.yaml)
  /portal    (React + Vite + TS)
  /models    (download scripts + version manifest; no weights committed)
  /docs       (architecture notes, privacy policy draft, API contract)
  ```

---

## PHASE 1 — Android core: pair, listen, duck, restore  ✅ build first

**Goal:** On two real devices, phone finds TV via mDNS, pairs with a 4-digit code,
and ducks/restores volume on both when someone talks.

Build:
- `:core` — shared wire protocol (`Command{action, level, reason}`, `PairRequest`,
  `PairResponse`, enums `RoomState{QUIET,TALKING,BOOST}`, `Action{DUCK,MUTE,PAUSE,
  RESUME,BOOST,RESTORE}`) using kotlinx.serialization. Plus framework-free
  `Thresholds` math (see below) so it's unit-testable.
- `:app-mobile`:
  - `DetectionEngine`: 16kHz mono, 30ms frames. Phase-1 detection = RMS energy +
    zero-crossing-rate heuristic (speech ZCR band ~0.05–0.35). One function
    `classifyFrame() -> SPEECH|NOISE|QUIET` — **this is the ML seam**.
  - Smoothed state machine: need ~0.5s sustained speech to enter TALKING; restore
    only after `restoreDelay` seconds of quiet AND no active call. Anti-flicker.
  - `AcousticEchoCanceler` when available (source = phone).
  - `ListeningService` (foreground, `microphone` type, persistent notification):
    on state change, set phone `STREAM_MUSIC` volume and send `Command` to TV.
  - `Calibrator` + `Calibration`: 3-step guided capture (silence floor / media-only
    baseline / media+talk). Save named profiles in prefs.
  - Compose UI, Material 3 + dynamic color: **Login → Pair → Home → Calibrate →
    Settings**. Home shows a live "state orb" that changes colour with room state.
    Pair screen = 4 big digit boxes + numeric field. Settings has duck-level slider
    + Privacy & consent section (all toggles OFF by default).
  - `PairingClient`: NSD/mDNS discovery of `_sonex._tcp`, then a socket handshake
    submitting the code; keeps a command channel open.
- `:app-tv` (Leanback):
  - `TvServer`: generate a 4-digit code, register `_sonex._tcp` on the LAN, accept
    `PAIR` handshake, then apply `DUCK/BOOST/MUTE/RESTORE` via `AudioManager`
    natively; report volume/playback state back.
  - `TvActivity`: big pairing-code screen + connection status.

Threshold math (put in `:core`, unit-test it):
```
trigger      = mediaBaseline + (mediaPlusTalk - mediaBaseline) * (1 - sensitivity)
boostTrigger = mediaBaseline + 8 dB
```
Tests to write: trigger sits between anchors; higher sensitivity lowers trigger;
**corner placement preserves the gap** (offset both anchors by −10dB → same
decision distance); protocol round-trips; 4-digit code is always length 4.

**Definition of done:** both APKs build; on two devices, pairing works and talking
near the phone audibly lowers TV volume, silence restores it; `./gradlew test` green.

---

## PHASE 2 — On-device ML models (Silero VAD + YAMNet), OTA-ready

**Goal:** Replace the heuristic with real models, loaded from files by version.

Build:
- `/models/download_models.sh`: fetch **Silero VAD** (ONNX) and **YAMNet** (TFLite)
  into `/models/artifacts/`. Do NOT commit weights. Write a `manifest.json`:
  `{ "vad": {"file":"silero_vad.onnx","version":"1.0","sha256":"…"},
     "sound": {"file":"yamnet.tflite","version":"1.0","sha256":"…"} }`.
- Android: add ONNX Runtime Mobile + TFLite. In `DetectionEngine.classifyFrame`:
  - **Silero VAD** → speech probability per frame (is someone speaking?).
  - **YAMNet** → top sound classes → decide **speech vs non-speech noise**, which
    picks DUCK (speech) vs BOOST (loud non-speech like appliance/traffic).
  - Keep the energy heuristic as fallback if a model is missing/unverified.
- `ModelStore`: loads model files from app storage, verifies sha256 against the
  manifest, exposes them to the engine; falls back to last-known-good on mismatch.

**Seam contract (keep stable so OTA works):** `classifyFrame(pcm: ShortArray, db:
Double): FrameKind`. Everything above this line is fixed app code; everything below
is swappable model data.

Tests: manifest parse + checksum verify; fallback triggers on bad checksum; VAD
speech-probability threshold maps to the right FrameKind on canned PCM fixtures.

**Done:** detection accuracy visibly better than Phase 1; app still runs if models
absent (heuristic fallback).

---

## PHASE 3 — SoNex server (FastAPI + Postgres + Redis + storage failover)

**Goal:** Ingest consented events/clips, store safely, serve model manifests.

Build `/server`:
- FastAPI app, async, with:
  - `POST /v1/events` — detection events + user overrides (no raw audio).
  - `POST /v1/clips` — consented short audio clip upload (multipart) → storage.
  - `GET  /v1/models/manifest?device=…` — returns current model manifest for OTA.
  - `GET  /v1/models/{id}/download` — signed URL / stream of a model file.
  - Auth: JWT for portal/dev users; per-device API key for clients.
  - `POST /v1/data/delete` and `GET /v1/data/export` — honour privacy rights.
- **Postgres** (SQLAlchemy + Alembic): tables `devices`, `homes`, `sessions`,
  `events`, `clips`(metadata only + storage key), `labels`, `models`(versioned),
  `metrics`, `users`, `consents`.
- **Redis**: real-time device state, rate limiting, and a job queue (ARQ or RQ) for
  training runs and clip post-processing.
- **Storage abstraction** `storage.py` with one interface (`put/get/url/delete`):
  primary **Cloudinary**, automatic fallback to **Cloudflare R2** on failure; log
  which backend served each object.
- Config via env vars; `.env.example`; `Dockerfile`; `render.yaml` for Render
  (web service + Postgres + Redis).

Tests: storage interface with Cloudinary mocked-failing → R2 used; manifest
endpoint returns valid, checksum-bearing JSON; consent gating (clip upload rejected
without a matching consent record); data-delete actually removes rows + objects.

**Done:** `docker compose up` runs API+Postgres+Redis locally; endpoints pass tests;
Android `ModelStore` can fetch a manifest from it.

---

## PHASE 4 — Per-home training + OTA delivery loop

**Goal:** Turn consented data into a per-home model and push it to devices with no
app update.

Build:
- `/server/training/`: pipeline (queued job) that takes a home's labelled events +
  clips and produces **tuned thresholds + a small per-home classifier** (start with
  a lightweight model: logistic-regression / small MLP over VAD+YAMNet features —
  cheap, explainable, on-device-friendly). Export to ONNX/TFLite.
- Versioning: new artifact → new `models` row → new manifest entry with sha256 and
  `min_app_version`. Old model kept as rollback.
- Android OTA client: on launch / daily on Wi-Fi, `ModelStore` checks
  `/v1/models/manifest`, downloads if newer, verifies checksum, hot-swaps into the
  engine, keeps last-known-good on failure. **Never** changes app code paths.
- Learning input: log every user override (manual volume change, remote use) as a
  correction signal feeding the next training run — this is how it learns the
  household's regular behaviour over time.

Tests: training produces a valid artifact + manifest bump from a fixture dataset;
OTA "newer version" path swaps; "bad checksum" path rolls back; override events
change the trained thresholds in the expected direction.

**Done:** retrain on the server → device fetches new model automatically → behaviour
adapts, with zero Play Store update.

---

## PHASE 5 — SoNex dev portal (login-gated)

**Goal:** See and steer the system.

Build `/portal` (React + Vite + TS, matching the app's violet Material feel):
- Auth (JWT against the server).
- **Sound view:** render waveforms/spectrograms of uploaded clips (Web Audio /
  wavesurfer.js). **Training review:** list samples, play, correct/confirm labels.
- **Accuracy dashboard:** metrics over time per home/model version (charts).
- **Model versions:** list, promote, rollback; show manifest + min-app-version.
- **Devices:** connected devices, current state, last-seen.

Tests (component/integration, lightweight): label submit hits the API and updates
UI; version promote/rollback calls the right endpoints; auth guard redirects.

**Done:** log in, see real waveforms + metrics from Phase-3 data, promote a model
that Phase-4 OTA then delivers.

---

## PHASE 6 — Voice control (wake word + EN/Hindi commands), on-device

**Goal:** "SoNex, lower volume / raise volume / stop / play" in English + Hindi.

Build:
- **Wake word "SoNex":** integrate **openWakeWord** (train a custom keyword model;
  document the training-data step — this needs recordings of the word, flag it to
  me). Separate, explicit always-listening consent toggle in Settings.
- **Command recognition:** **Vosk** offline models for English + Hindi. Map intents
  → actions: lower/raise volume, stop/start playback, both applied to phone + TV
  via the existing `Command` interface.
- Voice processing on-device by default; server option behind consent.

Tests: intent parser maps EN + HI phrases to the correct `Action` (unit-test the
mapping table with fixture transcripts); wake-word gate blocks commands until fired.

**Done:** speaking the wake word + a command changes volume/playback in both langs.

---

## PHASE 7 — More outputs: Bluetooth + Cast

**Goal:** Duck any phone-controlled audio, not just the paired TV.

Build:
- Output targets behind one interface (`duck/boost/mute/pause/resume/restore`):
  phone speaker (done), **Bluetooth media** (native volume/media control — echo
  cancellation is clean here since the phone is the source), **Cast** (Cast SDK),
  and the TV companion (done). Per-device rule in Settings: Duck / Mute / Pause /
  Boost. `ListeningService` fans out to all active targets.

Tests: fan-out sends the right command to each registered target; per-device rule
overrides are respected.

**Done:** with a BT speaker playing, talking ducks it and silence restores it,
independently configurable from the TV.

---

## Phone-call layer (fold into Phase 1, extend in 6)

Detect telephony call state → force TALKING → duck all outputs → restore after the
call ends **and** the room is quiet. Auto-decline only as an optional,
permission-gated setting; note in UI it may be unavailable on modern Android.

---

## Deliverables checklist

- [ ] `/android` builds both debug APKs; `./gradlew test` green.
- [ ] `/models` download script + manifest; app runs with and without models.
- [ ] `/server` runs via docker compose; tests green; deploys via `render.yaml`.
- [ ] `/portal` runs via `npm run dev`; talks to the server.
- [ ] Voice control works EN + HI on-device.
- [ ] BT + Cast outputs work alongside TV.
- [ ] `/docs`: architecture diagram, API contract, **privacy policy draft** (flag
      for legal review — India DPDP Act 2023 / GDPR consent + deletion), and Play
      Store data-safety notes.

## Order of attack (do not skip)

1 → 2 → 3 → 4 → 5 → 6 → 7. Get each running end-to-end before the next. The single
biggest risk is trying to build breadth before the Phase-1 detect→duck→restore loop
is solid on real hardware. Build that first, prove it, then expand.

## Things to stop and ask me about (don't guess)

- Cloudinary / R2 / Render credentials and bucket names.
- openWakeWord custom "SoNex" training data (I need to provide/approve recordings).
- Any Play Store data-safety / privacy-policy wording before publishing.
- Whether to auto-decline calls (permission-sensitive) — default OFF.
