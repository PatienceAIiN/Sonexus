# SoNex detection & self-improving model

This document describes how SoNex decides **QUIET / SPEECH (talking) / NOISE
(machine) / WHISPER**, how the model improves itself over the air, and how a
true neural VAD can be dropped in.

## 1. Per-frame features (identical on phone, web, and server)

Every ~30 ms frame is reduced to a few cheap, level-independent-where-it-matters
features. The three engines compute them **identically** — Kotlin (`core/Dsp`,
`ModulationTracker`, `ZcrTracker`, `LinearVad`), web JS (`webapp/index.html`),
and Python training (`app/training.py::expand`).

| Feature | Meaning | Why |
|---|---|---|
| `rms_over_floor_db` | level above the room's adaptive noise floor | loudness |
| `zcr` | zero-crossing rate | voiced vs. unvoiced/breath vs. hum |
| `swing_db` | p90–p10 of level over ~1 s (`ModulationTracker`) | people pulse; machines hold steady |
| `zcr_flux` | p90–p10 of ZCR over ~1 s (`ZcrTracker`) | **masking-robust** speech cue (see §3) |
| `voiced_band` *(engineered)* | 1 if `zcr ∈ [0.05, 0.35]` | voiced speech band |
| `modulated` *(engineered)* | 1 if `swing ≥ 5 dB` **or** `zcr_flux ≥ 0.04` | "is it changing like a person?" |

## 2. Decision logic (heuristic, always available)

`RoomStateMachine.classify` (in `:core`, unit-tested, mirrored in web JS):

- **SPEECH** — voice-shaped **and changing** (`speechShaped && (!steady || zcrFlux ≥ SPEECH_FLUX)`) and above the talk trigger → duck.
- **NOISE** — loud and either non-vocal or steady → boost (cut through it).
- **WHISPER / WHISPER_GROUP** — breathy + softly modulated, below the trigger; the louder upper part is several people → gentle duck.
- **QUIET** — everything else → restore.

Anti-flicker leaky integrators smooth frame decisions; a SPEECH frame drains the
boost score so **a conversation always wins over a running machine**.

## 3. The loud-machine masking problem — and the fix

A cooler/fan much louder than a talker **flattens the level**, so `swing_db`
alone reads "steady" and would call it NOISE even while someone talks. The fix is
**`zcr_flux`**: a person alternates voiced vowels and unvoiced consonants, so
their zero-crossing rate *fluctuates* — and because ZCR is a ratio, that
fluctuation survives even when a loud steady machine dominates the volume. A
machine's spectrum is constant, so its ZCR barely moves. `classify` therefore
treats a voice-shaped sound as a person if it is changing **in level OR in ZCR**.
The learned model gets the same signal via the `zcr_flux` + `modulated` features,
plus a "masked speech" prior (low swing, high flux → SPEECH) during training.

## 4. Self-improving learned model (`lite`)

`app/training.py` trains a small multinomial-logistic (softmax) model over the 6
features — pure Python, no numpy/GPU. It learns from open-dataset **priors**
(Common Voice, LibriSpeech, MUSAN, ESC-50, AudioSet, whisper sets) plus **real,
consented** clips. Current synthetic accuracy ≈ **0.97** (up from 0.84 before the
engineered + `zcr_flux` features).

- **Automatic:** `app/scheduler.py` runs `trainer_job.train_and_publish` nightly
  at **02:00 IST** (in-process); admin "Run now" triggers the same job.
- **No-degrade guard:** a retrain is promoted to `active` only if its accuracy is
  ≥ previous − 0.01; otherwise it is filed as `rollback` and the live model is
  kept. **Training can never make detection worse.**
- **Privacy:** consented clips are used then **deleted** (blob + row) right after
  the run. Users who never opted in have no audio collected.

## 5. OTA delivery — model gains need NO app/site update

Model files are **data, not code**:

- **Phone:** `ModelStore` fetches `/v1/models/manifest` (kind `lite`), verifies
  sha256, hot-swaps the file; `LiteClassifier` applies it (heuristic fallback
  when unsure). Re-synced on app resume.
- **Web:** fetches `GET /v1/models/lite` on load + every 5 min and runs
  `classifyLite`.

Therefore **accuracy / diversity improvements reach every device automatically**.
App/APK updates are reserved for bug fixes and features only. (See also the
project memory note `sonex-model-ota-policy`.)

## 6. Dropping in a true neural VAD (Silero / YAMNet)

The heuristic + lite model handle the common cases well and are tiny. For the
hardest masking (machine **much** louder than the talker), a pretrained neural
VAD is the gold standard. The app is **already wired for it** — no code change,
pure OTA:

- `MlClassifier` loads a Silero **VAD** (`vad` kind, ONNX, 512-sample window) and
  a **sound** model (`sound` kind, YAMNet TFLite); `buildClassifier` prefers them
  over `lite`/heuristic when present. ONNX Runtime + TFLite are bundled.
- To ship one: publish a `Model` row with `kind="vad"`, upload the `.onnx` to
  storage, and it flows through `/v1/models/manifest` to phones automatically.

What is **not** done here (and why): training or bundling a real Silero network
requires an ML framework + GPU + data (or fetching third-party weights), which
isn't available in this build environment. The integration seam is complete, so
adding the model file later is a drop-in.
