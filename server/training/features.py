"""Feature extraction / fixture data for per-home training.

Features are VAD/YAMNet-style vectors: [vad_prob, yamnet_speech, yamnet_tv, rms_db].
On-device these come from the Silero VAD + YAMNet heads; server-side we derive them
from clip/event metadata (or fixtures for tests) — raw audio is never required.
"""
import hashlib

import numpy as np

FEATURE_NAMES = ["vad_prob", "yamnet_speech", "yamnet_tv", "rms_db"]
POSITIVE_LABELS = {"speech", "talking", "TALKING"}


def _seeded(*parts) -> np.random.Generator:
    digest = hashlib.sha256("|".join(str(p) for p in parts).encode()).digest()
    return np.random.default_rng(int.from_bytes(digest[:8], "big"))


def features_for_clip(clip) -> list[float]:
    """Deterministic pseudo-features from clip metadata (no raw audio on the server)."""
    rng = _seeded("clip", clip.id, clip.storage_key)
    speechy = 1.0 if (clip.label in POSITIVE_LABELS or clip.room_state == "TALKING") else 0.0
    return [
        float(np.clip(0.15 + 0.7 * speechy + rng.normal(0, 0.08), 0, 1)),
        float(np.clip(0.1 + 0.75 * speechy + rng.normal(0, 0.08), 0, 1)),
        float(np.clip(0.7 - 0.5 * speechy + rng.normal(0, 0.08), 0, 1)),
        float(-45 + 20 * speechy + rng.normal(0, 2)),
    ]


def fixture_dataset(home_id: int, n: int = 60) -> tuple[np.ndarray, np.ndarray]:
    """Balanced labelled fixture dataset (speech vs background) for a home."""
    rng = _seeded("fixture", home_id)
    xs, ys = [], []
    for i in range(n):
        y = i % 2
        xs.append([
            float(np.clip(0.15 + 0.7 * y + rng.normal(0, 0.08), 0, 1)),
            float(np.clip(0.1 + 0.75 * y + rng.normal(0, 0.08), 0, 1)),
            float(np.clip(0.7 - 0.5 * y + rng.normal(0, 0.08), 0, 1)),
            float(-45 + 20 * y + rng.normal(0, 2)),
        ])
        ys.append(y)
    return np.array(xs), np.array(ys)
