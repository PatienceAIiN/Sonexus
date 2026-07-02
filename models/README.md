# /models — detection model artifacts

No weights are committed. Run `./download_models.sh` to fetch:

- **Silero VAD** (`silero_vad.onnx`) — per-frame speech probability.
- **YAMNet** (`yamnet.tflite`) — sound-class scores; used to split speech vs
  non-speech noise (DUCK vs BOOST).

The script writes `manifest.json` with sha256 checksums. The Android
`ModelStore` only loads files whose checksum matches a manifest entry and falls
back to the last-known-good copy (or the energy heuristic) otherwise. The
server serves the same manifest shape at `/v1/models/manifest` for OTA updates
(see `docs/api-contract.md`).

To sideload onto a device for testing:
```
adb push artifacts/. /sdcard/Android/data/com.sonex.mobile/files/models/
adb push manifest.json /sdcard/Android/data/com.sonex.mobile/files/models/
```
