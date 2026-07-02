# SoNex architecture

```
 PHONE (:app-mobile)                      ANDROID TV (:app-tv)
 ┌──────────────────────────────┐         ┌─────────────────────┐
 │ mic → DetectionEngine        │  LAN    │ TvServer            │
 │   FrameClassifier (ML SEAM)  │ socket  │  mDNS _sonex._tcp   │
 │   ├ MlClassifier             │────────►│  4-digit pairing    │
 │   │  Silero VAD (onnx, OTA)  │         │  VolumePolicy       │
 │   │  YAMNet (tflite, OTA)    │         │  AudioManager       │
 │   └ HeuristicClassifier      │◄────────│  reports TvState    │
 │ RoomStateMachine (:core)     │         └─────────────────────┘
 │ OutputRouter ── RulePolicy   │
 │  ├ PhoneSpeakerTarget        │         SERVER (/server)
 │  ├ BluetoothTarget           │  HTTPS  ┌─────────────────────┐
 │  ├ TvTarget                  │────────►│ FastAPI /v1         │
 │  └ CastTarget                │ consent │  events, clips,     │
 │ CallMonitor → force TALKING  │  gated  │  consents, privacy  │
 │ VoiceController (Vosk EN+HI) │         │ Postgres · Redis    │
 │ ModelStore ◄── OTA manifest ─┼─────────│ training/ → ONNX    │
 └──────────────────────────────┘         │ Cloudinary→R2       │
                                          └────────▲────────────┘
                                                   │ JWT
                                          PORTAL (/portal)
                                          React+Vite+TS: waveforms,
                                          labels, metrics, model
                                          promote/rollback, devices
```

## Key invariants
- **The ML seam** is `FrameClassifier.classify(pcm, n, db) -> FrameKind`.
  App code above the seam never changes; model files below it swap via OTA
  (`ModelStore.sync` → verify sha256 → hot-swap, last-known-good on failure).
- **One command interface** (`Command{action, level, reason}`) for every output
  target and for voice intents. `RulePolicy` translates room state per device.
- **Pure core**: thresholds, state machine, protocol framing, volume policy,
  manifest handling, intent parsing, wake-word gate — all in `:core`, all JVM
  unit-tested. Android classes are thin shells.
- **Privacy**: all audio on-device by default; every server interaction is
  behind an explicit consent (default OFF) or serves the user (OTA manifests).
  Persistent notification whenever the mic is live.

## Detection loop
30ms mono 16kHz frames → classifier → streak-smoothed state machine
(~0.5s speech to duck, `restoreDelay` of quiet to restore, cough-proof) →
`OutputRouter.onState` fans out. An active phone call forces TALKING and
blocks restore until the call ends AND the room is quiet.

## Learning loop (Phase 4)
User overrides (manual volume changes) are logged as correction events →
`POST /v1/events` (telemetry consent) → nightly/queued `training/pipeline.py`
tunes per-home thresholds + logistic-regression classifier → ONNX artifact →
new `models` row + manifest bump → devices pull on next sync. Rollback keeps
the previous version live.
