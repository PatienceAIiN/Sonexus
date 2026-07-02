# SoNex — room-aware adaptive volume

Volume that listens to the room: when someone talks, media ducks on the phone,
the paired Android TV, Bluetooth, and Cast; when it's quiet, it restores. Loud
non-speech noise raises volume instead. On-device ML, per-home OTA models,
consent-first server, dev portal, and EN/HI voice control.

Built per `BUILD_SONEX.md`, all 7 phases. See `docs/architecture.md`.

| Dir | What | Build / test |
|---|---|---|
| `/android` | `:core` (pure logic) + `:app-mobile` + `:app-tv` | `./gradle assembleDebug testDebugUnitTest` (needs JDK 17 + Android SDK 34) |
| `/server` | FastAPI + Postgres + Redis + training + OTA | `docker compose up` · `pytest` (28 tests) |
| `/portal` | React + Vite + TS dev portal | `npm run dev` · `npm test` (11 tests) |
| `/models` | `download_models.sh` (Silero VAD, YAMNet; `--voice` for Vosk EN/HI) + manifest | no weights committed |
| `/docs` | architecture, API contract, privacy draft, Play Store notes | — |

## Quick start
1. `models/download_models.sh` — fetch detection models (optional; app falls
   back to the energy heuristic without them).
2. Build both APKs in `/android`; install SoNex TV on the television, SoNex on
   the phone; pair with the 4-digit code (same Wi-Fi).
3. Optional cloud: `cd server && docker compose up`, then set the server URL in
   the app's Settings and `npm run dev` in `/portal`.

## Privacy
All audio is processed on-device by default. Every data-sharing purpose is a
separate consent toggle, OFF by default; the app is fully functional with all
of them off. See `docs/privacy-policy-draft.md` (pending legal review).
