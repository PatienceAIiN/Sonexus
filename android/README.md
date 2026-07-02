# SoNex — Room-Aware Adaptive Volume

Two Android apps that work together so your TV **listens to the room**:
when someone nearby starts talking (or a call comes in), the volume ducks
automatically; when it's quiet again, it restores. If non-speech ambient noise
rises, it can *raise* volume instead. Pairing is a 4-digit code on the same Wi-Fi.

This repository is **Phase 1: the complete working core** — login, 4-digit LAN
pairing, calibration, the detect → duck → restore loop, and an expressive
Material 3 UI. The ML training backend, SoNex portal, OTA models, and voice
control are designed-for but intentionally *not* in this phase (see Roadmap).

---

## How it works

```
  PHONE (the brain)                         ANDROID TV (the output)
  ┌───────────────────────┐                 ┌──────────────────────┐
  │ mic → DetectionEngine  │                 │ TvServer (mDNS + LAN) │
  │ VAD + energy + state   │  Command over   │ shows 4-digit code    │
  │ machine (QUIET/TALKING │  LAN socket     │ applies DUCK/RESTORE  │
  │ /BOOST)                │ ───────────────►│ via AudioManager      │
  │ ducks phone volume +   │                 │ reports state back    │
  │ sends command to TV    │                 └──────────────────────┘
  └───────────────────────┘
```

- **Phone** captures 30 ms mic frames, runs an energy + zero-crossing VAD, and a
  smoothed state machine (anti-flicker). On state change it ducks the phone's own
  media volume *and* sends a command to the paired TV.
- **TV** advertises `_sonex._tcp` via mDNS, shows a 4-digit code, accepts the
  phone's handshake, and applies volume changes natively — no per-brand hacks.
- **Calibration** measures the room at *this phone position* (silence / TV-only /
  TV+talk). Because decisions use the *gaps* between anchors, a corner placement
  works as well as a centred one.

## Pairing flow (what the user sees)

1. Open **SoNex TV** on the television → it shows a big 4-digit code.
2. Open **SoNex** on the phone → sign in → it finds the TV on the same Wi-Fi.
3. Type the 4 digits → **Connect**. Done.

## Project layout

```
:core        Shared wire protocol (Command, PairRequest…) + Thresholds math
:app-mobile  Phone app — Compose UI, DetectionEngine, ListeningService, pairing
:app-tv      Android TV app — TvServer + Compose-for-TV pairing screen
```

## Build the APKs

Requires Android Studio (Koala+) or command-line Gradle with JDK 17.

```bash
# Phone APK
./gradlew :app-mobile:assembleDebug
#  → app-mobile/build/outputs/apk/debug/app-mobile-debug.apk

# TV APK
./gradlew :app-tv:assembleDebug
#  → app-tv/build/outputs/apk/debug/app-tv-debug.apk
```

Install the phone APK on your phone and the TV APK on an Android TV (or the TV
emulator). Put both on the same Wi-Fi.

For a signed release APK: Android Studio → **Build → Generate Signed Bundle/APK**,
or configure a keystore and run `./gradlew :app-mobile:assembleRelease`.

## Run the tests

```bash
./gradlew test
```

Covers the two things worth locking down in Phase 1:
- **`ThresholdsTest`** — trigger sits between anchors, sensitivity direction is
  correct, and *corner placement preserves the decision gap* (position-independence).
- **`ProtocolTest`** — the phone↔TV wire format round-trips cleanly.

## Privacy model (built in, not bolted on)

- All audio is processed **on-device**. Nothing leaves the phone in Phase 1.
- A persistent notification shows whenever the mic is active.
- Every data-sharing consent in Settings is **OFF by default** and revocable.
- The app stays fully functional with all sharing disabled.

> The consent UX and defaults are here; the actual Privacy Policy text and Play
> Store data-safety declarations need legal review before shipping (India's DPDP
> Act 2023 / GDPR-style consent + deletion rights).

## Roadmap (Phase 2+)

These are designed-for in the architecture (clear seams left in the code):

1. **Open-source ML models**, dropped in at `DetectionEngine.classifyFrame`:
   Silero VAD (is it speech?) + YAMNet (speech vs. appliance/traffic → duck or
   boost?). Ship as `.tflite` files.
2. **Server (SoNex on Render)** — FastAPI + Postgres + Redis; Cloudinary→R2
   storage failover; per-home training from consented, labelled samples.
3. **OTA model updates** — models are versioned data, not code, so retraining
   never needs a Play Store update. App checks a manifest, verifies checksum,
   hot-swaps, keeps last-known-good.
4. **SoNex dev portal** — login-gated React app to visualise waveforms, review
   training data, track accuracy, manage model versions.
5. **Voice control** — wake word "SoNex" (openWakeWord) + EN/Hindi commands
   (Vosk), on-device.
6. **Bluetooth + Cast outputs** — the ListeningService already fans out; add
   these as extra targets alongside the TV.

## Honest scope note

Phase 1 is a *buildable, testable foundation* — the detect→duck→restore loop and
pairing genuinely work. The full ML/learning system is a multi-month build; each
piece above bolts onto the seams left here. Start by running Phase 1 end-to-end
on two devices before adding the server.
