"""Lightweight on-server trainer for the frame classifier (speech / noise /
quiet / whisper).

We deliberately train a small multinomial-logistic (softmax) model over three
cheap features the app computes for every frame:

    f0 = level above the room's noise floor (dB)   — loudness
    f1 = zero-crossing rate                         — voiced vs breath vs hum
    f2 = level modulation swing (dB, p90-p10 ~1s)   — people pulse, machines don't

This is intentionally NOT a deep net: it trains in milliseconds in pure Python
(no numpy/torch/GPU), exports as a tiny JSON the phone applies directly, and is
robust to loud steady machinery in a way fixed ZCR thresholds are not — the
model learns that high modulation + voice-band ZCR means speech even when a
cooler dominates the absolute level. As real consented clips accumulate they are
folded in on top of the dataset-derived priors, so it keeps improving.

The output dict matches the Kotlin `LinearVad` data class exactly.
"""
from __future__ import annotations

import math
import random

CLASSES = ["QUIET", "SPEECH", "NOISE", "WHISPER"]
FEATURES = ["rms_over_floor_db", "zcr", "swing_db"]

# Acoustic priors distilled from the open datasets listed in the admin panel
# (Common Voice/LibriSpeech = voiced speech; MUSAN/ESC-50/AudioSet = machines &
# ambient; CHiME/whisper sets = unvoiced whisper). Each entry: feature ranges the
# class typically occupies. Sampled to seed training before real clips exist.
_PRIORS = {
    # quiet room / hiss: barely above floor, any ZCR, no modulation
    "QUIET":   {"rms": (0.0, 6.0),   "zcr": (0.02, 0.55), "swing": (0.0, 3.0)},
    # a person talking: well above floor, voice-band ZCR, strongly modulated
    "SPEECH":  {"rms": (12.0, 42.0), "zcr": (0.05, 0.35), "swing": (5.0, 22.0)},
    # cooler / fan / motor / vehicle: loud but STEADY (low swing), or non-vocal ZCR
    "NOISE":   {"rms": (12.0, 46.0), "zcr": (0.0, 0.6),   "swing": (0.0, 4.0)},
    # whisper: soft, breathy (higher ZCR), gently modulated
    "WHISPER": {"rms": (6.0, 15.0),  "zcr": (0.12, 0.55), "swing": (2.0, 8.0)},
}


def _sample_priors(per_class: int, seed: int = 7) -> list[tuple[list[float], int]]:
    rng = random.Random(seed)
    out: list[tuple[list[float], int]] = []
    for ci, cls in enumerate(CLASSES):
        p = _PRIORS[cls]
        for _ in range(per_class):
            rms = rng.uniform(*p["rms"])
            zcr = rng.uniform(*p["zcr"])
            swing = rng.uniform(*p["swing"])
            # NOISE also covers loud non-vocal sounds that DO modulate (vehicles):
            if cls == "NOISE" and rng.random() < 0.35:
                swing = rng.uniform(4.0, 18.0)
                zcr = rng.uniform(0.0, 0.05) if rng.random() < 0.5 else rng.uniform(0.45, 0.65)
            out.append(([rms, zcr, swing], ci))
    return out


def _standardise(rows: list[list[float]]):
    n = len(rows)
    dim = len(rows[0])
    mean = [sum(r[i] for r in rows) / n for i in range(dim)]
    std = []
    for i in range(dim):
        var = sum((r[i] - mean[i]) ** 2 for r in rows) / n
        std.append(math.sqrt(var) or 1.0)
    z = [[(r[i] - mean[i]) / std[i] for i in range(dim)] for r in rows]
    return z, mean, std


def _softmax(logits: list[float]) -> list[float]:
    m = max(logits)
    exps = [math.exp(v - m) for v in logits]
    s = sum(exps)
    return [e / s for e in exps]


def train(
    extra_samples: list[tuple[list[float], int]] | None = None,
    per_class: int = 240,
    epochs: int = 300,
    lr: float = 0.3,
    seed: int = 7,
) -> dict:
    """Train the softmax model; return a dict ready to serialise + ship.

    extra_samples: real (feature_vector, class_index) rows from consented clips.
    """
    data = _sample_priors(per_class, seed)
    if extra_samples:
        # weight real data ~3x by duplication so it steers without swamping priors
        data += extra_samples * 3

    raw = [f for f, _ in data]
    labels = [y for _, y in data]
    X, mean, std = _standardise(raw)
    nfeat = len(FEATURES)
    ncls = len(CLASSES)

    # weights[c][f], bias[c]
    w = [[0.0] * nfeat for _ in range(ncls)]
    b = [0.0] * ncls
    n = len(X)

    for _ in range(epochs):
        gw = [[0.0] * nfeat for _ in range(ncls)]
        gb = [0.0] * ncls
        for xi, yi in zip(X, labels):
            logits = [b[c] + sum(w[c][f] * xi[f] for f in range(nfeat)) for c in range(ncls)]
            p = _softmax(logits)
            for c in range(ncls):
                err = p[c] - (1.0 if c == yi else 0.0)
                gb[c] += err
                for f in range(nfeat):
                    gw[c][f] += err * xi[f]
        for c in range(ncls):
            b[c] -= lr * gb[c] / n
            for f in range(nfeat):
                w[c][f] -= lr * gw[c][f] / n

    # training accuracy
    correct = 0
    for xi, yi in zip(X, labels):
        logits = [b[c] + sum(w[c][f] * xi[f] for f in range(nfeat)) for c in range(ncls)]
        if max(range(ncls), key=lambda c: logits[c]) == yi:
            correct += 1
    accuracy = correct / n

    return {
        "classes": CLASSES,
        "weights": w,
        "bias": b,
        "mean": mean,
        "std": std,
        "features": FEATURES,
        "accuracy": accuracy,
        "n_samples": n,
        "n_real": len(extra_samples) if extra_samples else 0,
    }
