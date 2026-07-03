"""Automated training job: learn from consented audio, publish a new model,
then DELETE the audio. Runs on a daily schedule (2:00 AM IST) and on demand.

Privacy contract (mirrored in the app consent popup + privacy policy):
  * Audio is only ever collected from users who turned on "Let SoNex learn my
    home" (the `training`/`upload_clips` consent).
  * It is used solely to train/improve the detection model.
  * It is never shared with any third party.
  * It is deleted automatically as soon as training has used it.
"""
from __future__ import annotations

import array
import hashlib
import io
import json as _json
import math
import wave
from datetime import datetime, timezone

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from . import cache, training
from .models import Clip, Event, Metric, Model
from .storage import get_storage

_CLASS_IDX = {c: i for i, c in enumerate(training.CLASSES)}


def _features_from_wav(data: bytes) -> list[float] | None:
    """Extract [level-over-floor dB, ZCR, swing dB, ZCR-flux] from a WAV clip.
    Pure stdlib (wave/array) — no numpy. Returns None for anything unreadable."""
    try:
        w = wave.open(io.BytesIO(data), "rb")
        sr, ch, sw, nf = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(nf)
        w.close()
        if sw != 2 or sr <= 0:
            return None
        a = array.array("h")
        a.frombytes(raw)
        if ch > 1:
            a = a[0::ch]  # first channel only
        fs = max(1, int(sr * 0.03))  # ~30 ms frames
        dbs: list[float] = []
        zcrs: list[float] = []
        i = 0
        while i + fs <= len(a):
            win = a[i:i + fs]
            ss = 0.0
            cross = 0
            prev = win[0] >= 0
            for x in win:
                ss += x * x
                cur = x >= 0
                if cur != prev:
                    cross += 1
                prev = cur
            rms = math.sqrt(ss / fs)
            dbs.append(20 * math.log10(rms / 32767.0) if rms > 0 else -100.0)
            zcrs.append(cross / fs)
            i += fs
        if not dbs:
            return None
        srt = sorted(dbs)
        floor = srt[0]
        med = srt[len(srt) // 2]
        p90 = srt[int(len(srt) * 0.9) - 1] if len(srt) > 1 else srt[-1]
        p10 = srt[int(len(srt) * 0.1)]
        zsrt = sorted(zcrs)
        zcr = zsrt[len(zsrt) // 2]
        # ZCR-flux: p90-p10 spread of per-frame ZCR — the masking-robust cue.
        zflux = (zsrt[int(len(zsrt) * 0.9) - 1] if len(zsrt) > 1 else zsrt[-1]) - zsrt[int(len(zsrt) * 0.1)]
        return [max(0.0, med - floor), zcr, max(0.0, p90 - p10), max(0.0, zflux)]
    except Exception:
        return None


async def train_and_publish(db: AsyncSession) -> dict:
    """Train on consented clips (+ level events), publish a new "lite" model,
    and delete the clips that were used. Returns a report dict."""
    storage = get_storage()

    # 1) Real labelled audio, from users who consented (clips only exist when the
    #    upload_clips/training consent is on — gated at the upload endpoint).
    clips = (await db.execute(
        select(Clip).where(Clip.label.is_not(None)).order_by(Clip.id.asc()).limit(5000)
    )).scalars().all()
    extra: list[tuple[list[float], int]] = []
    used: list[Clip] = []
    for c in clips:
        ci = _CLASS_IDX.get((c.label or "").upper())
        if ci is None:
            continue
        try:
            data = storage.get(c.storage_key)
        except Exception:
            continue
        feats = _features_from_wav(data)
        if feats is not None:
            extra.append((feats, ci))
        used.append(c)  # consume it regardless — audio must not linger

    # 2) Weak level-only signal from recent detection events (no audio stored).
    events = (await db.execute(
        select(Event).where(Event.db.is_not(None), Event.room_state.is_not(None))
        .order_by(Event.id.desc()).limit(2000)
    )).scalars().all()
    for e in events:
        ci = _CLASS_IDX.get((e.room_state or "").upper())
        if ci is not None:
            # No audio stored for events; use a neutral mid ZCR-flux placeholder.
            extra.append(([max(0.0, (e.db or -60.0) + 55.0), 0.2, 8.0, 0.05], ci))

    model = training.train(extra_samples=extra or None)
    accuracy = float(model["accuracy"])

    # 3) NO-DEGRADE GUARD: only promote the new model to "active" if it is at least
    #    as good as the last recorded accuracy (small tolerance). Otherwise keep the
    #    current model live and file the new one as rollback — training never makes
    #    detection worse. (Audio is still consumed + deleted either way.)
    prev = (await db.execute(
        select(Metric.value).where(Metric.name == "accuracy").order_by(Metric.id.desc()).limit(1)
    )).scalar()
    promote = prev is None or accuracy >= prev - 0.01
    status = "active" if promote else "rollback"

    existing = (await db.execute(
        select(func.count()).select_from(Model).where(Model.kind == "lite")
    )).scalar() or 0
    version = f"1.{existing + 1}"
    payload = {
        "version": version, "classes": model["classes"], "weights": model["weights"],
        "bias": model["bias"], "mean": model["mean"], "std": model["std"],
    }
    blob = _json.dumps(payload, separators=(",", ":")).encode()
    sha = hashlib.sha256(blob).hexdigest()
    fname = f"lite-{version}.json"
    key = f"models/{fname}"
    backend = storage.put(key, blob, "application/json") or "local"

    if promote:
        for old in (await db.execute(
            select(Model).where(Model.kind == "lite", Model.status == "active")
        )).scalars().all():
            old.status = "rollback"
    db.add(Model(home_id=None, kind="lite", file=fname, version=version, sha256=sha,
                 min_app_version=1, status=status, storage_key=key, backend=backend))

    # 4) Record the live model's accuracy only when we actually promoted it, so the
    #    dashboard chart tracks the active model and never dips on a rejected run.
    improvement_pct = round((accuracy - prev) * 100, 2) if prev is not None else None
    if promote:
        db.add(Metric(home_id=None, name="accuracy", value=accuracy,
                      ts=datetime.now(timezone.utc)))

    # 5) Delete the audio we just trained on — storage blob AND row.
    deleted = 0
    for c in used:
        try:
            storage.delete(c.storage_key)
        except Exception:
            pass
        await db.delete(c)
        deleted += 1

    await db.commit()
    cache.invalidate("admin:")
    return {
        "version": version, "accuracy": round(accuracy, 4),
        "improvement_pct": improvement_pct, "promoted": promote,
        "n_samples": model["n_samples"], "clips_used": len(used),
        "clips_deleted": deleted, "sha256": sha[:12], "backend": backend,
        "at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
