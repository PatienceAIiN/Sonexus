#!/usr/bin/env bash
# Fetch the Phase-2 detection models. Weights are NOT committed — run this.
# Writes artifacts/ + manifest.json with real sha256 checksums.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p artifacts

SILERO_URL="https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx"
# tfhub-lite-models started returning 403 (2026-07); HF mirror first, tfhub as fallback.
YAMNET_URLS=(
  "https://huggingface.co/thelou1s/yamnet/resolve/main/lite-model_yamnet_tflite_1.tflite"
  "https://storage.googleapis.com/tfhub-lite-models/google/lite-model/yamnet/tflite/1.tflite"
)

fetch() { # fetch <dest> <url...> — first URL that works wins
  local dest="$1"; shift
  if [ -s "$dest" ]; then echo "$(basename "$dest") already present, skipping."; return; fi
  for url in "$@"; do
    echo "Downloading $(basename "$dest") from $url…"
    if curl -fL --retry 3 -o "$dest" "$url"; then return; fi
  done
  echo "ERROR: could not fetch $(basename "$dest")" >&2; exit 1
}

fetch artifacts/silero_vad.onnx "$SILERO_URL"
fetch artifacts/yamnet.tflite "${YAMNET_URLS[@]}"

# Phase 6: offline speech models for voice control (optional, ~90MB total).
# Usage: ./download_models.sh --voice
if [ "${1:-}" = "--voice" ]; then
  fetch artifacts/vosk-en.zip "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
  fetch artifacts/vosk-hi.zip "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"
  echo "Sideload: unzip each into /sdcard/Android/data/com.sonex.mobile/files/voice/{en,hi}/"
fi

sha() { sha256sum "$1" | cut -d' ' -f1; }

cat > manifest.json <<EOF
{
  "vad":   { "file": "silero_vad.onnx", "version": "1.0", "sha256": "$(sha artifacts/silero_vad.onnx)" },
  "sound": { "file": "yamnet.tflite",   "version": "1.0", "sha256": "$(sha artifacts/yamnet.tflite)" }
}
EOF
echo "Wrote manifest.json:"
cat manifest.json
