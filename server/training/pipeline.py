"""Per-home training pipeline.

Takes a home's labelled events + clips (falls back to fixture data when the home
has too few labelled samples), tunes thresholds from user override events, and
trains a small logistic-regression classifier over VAD/YAMNet-style features.

Artifact export: ONNX via skl2onnx when available, otherwise a JSON artifact
(weights + bias + feature names). Either way a new `models` row is created with
the artifact's sha256; the previously active per-home model is kept with status
"rollback" so OTA clients can revert.
"""
import hashlib
import json
import logging

import numpy as np
from sklearn.linear_model import LogisticRegression
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models import Clip, Device, Event, Home, Label, Metric, Model
from .features import FEATURE_NAMES, POSITIVE_LABELS, features_for_clip, fixture_dataset

log = logging.getLogger("sonex.training")

MIN_REAL_SAMPLES = 10
SENSITIVITY_STEP = 0.02  # per override event
MARGIN_STEP_DB = 0.25


def tune_thresholds(base: dict, override_events: list[Event]) -> dict:
    """User overrides shift thresholds:

    - user BOOST override  => engine was not sensitive enough => lower `sensitivity`
      (detect speech more eagerly) and widen `boost_margin_db`.
    - user RESTORE/DUCK/MUTE override => engine boosted when it shouldn't =>
      raise `sensitivity` and narrow `boost_margin_db`.
    """
    sensitivity = float(base.get("sensitivity", 0.5))
    margin = float(base.get("boost_margin_db", 8.0))
    for e in override_events:
        if e.action == "BOOST":
            sensitivity -= SENSITIVITY_STEP
            margin += MARGIN_STEP_DB
        elif e.action in ("RESTORE", "DUCK", "MUTE"):
            sensitivity += SENSITIVITY_STEP
            margin -= MARGIN_STEP_DB
    return {
        "sensitivity": round(float(np.clip(sensitivity, 0.05, 0.95)), 4),
        "boost_margin_db": round(float(np.clip(margin, 2.0, 20.0)), 2),
    }


def export_artifact(clf: LogisticRegression) -> tuple[bytes, str]:
    """Returns (artifact_bytes, filename). ONNX when skl2onnx works, else JSON."""
    try:
        from skl2onnx import to_onnx

        onx = to_onnx(clf, np.zeros((1, len(FEATURE_NAMES)), dtype=np.float32))
        return onx.SerializeToString(), "home_classifier.onnx"
    except Exception as exc:  # pragma: no cover - depends on installed wheels
        log.warning("ONNX export unavailable (%s); exporting JSON artifact", exc)
        artifact = {
            "type": "logistic_regression",
            "feature_names": FEATURE_NAMES,
            "weights": clf.coef_.tolist(),
            "bias": clf.intercept_.tolist(),
            "classes": [int(c) for c in clf.classes_],
        }
        return json.dumps(artifact, indent=2).encode(), "home_classifier.json"


async def _load_dataset(db: AsyncSession, home_id: int) -> tuple[np.ndarray, np.ndarray]:
    clips = (await db.execute(select(Clip).where(Clip.home_id == home_id))).scalars().all()
    xs, ys = [], []
    for clip in clips:
        label_row = (
            await db.execute(
                select(Label).where(Label.clip_id == clip.id).order_by(Label.id.desc())
            )
        ).scalars().first()
        label = label_row.label if label_row and label_row.correct else clip.label
        if label is None:
            continue
        xs.append(features_for_clip(clip))
        ys.append(1 if label in POSITIVE_LABELS else 0)
    if len(xs) < MIN_REAL_SAMPLES or len(set(ys)) < 2:
        log.info("home=%s has %d labelled samples; padding with fixture data", home_id, len(xs))
        fx, fy = fixture_dataset(home_id)
        xs.extend(fx.tolist())
        ys.extend(fy.tolist())
    return np.array(xs, dtype=np.float32), np.array(ys)


async def run_training(db: AsyncSession, storage, home_id: int) -> Model:
    home = await db.get(Home, home_id)
    if home is None:
        raise ValueError(f"unknown home {home_id}")

    X, y = await _load_dataset(db, home_id)
    clf = LogisticRegression(max_iter=1000)
    clf.fit(X, y)
    accuracy = float(clf.score(X, y))

    overrides = (
        await db.execute(
            select(Event)
            .join(Device, Device.id == Event.device_id)
            .where(Event.type == "OVERRIDE", Event.source == "user", Device.home_id == home_id)
        )
    ).scalars().all()
    thresholds = tune_thresholds(home.thresholds or {}, overrides)

    data, filename = export_artifact(clf)
    sha256 = hashlib.sha256(data).hexdigest()

    prev = (
        await db.execute(
            select(Model)
            .where(Model.kind == "home", Model.home_id == home_id, Model.status == "active")
            .order_by(Model.id.desc())
        )
    ).scalars().first()
    version = _bump_version(prev.version if prev else None)

    key = f"models/home/{home_id}/{version}/{filename}"
    backend = storage.put(key, data)

    model = Model(
        home_id=home_id, kind="home", file=filename, version=version, sha256=sha256,
        min_app_version=settings.min_app_version, status="active", storage_key=key, backend=backend,
    )
    if prev is not None:
        prev.status = "rollback"  # kept for OTA rollback
    home.thresholds = thresholds
    db.add(model)
    db.add(Metric(home_id=home_id, name="train_accuracy", value=accuracy))
    await db.commit()
    log.info("trained home=%s version=%s sha256=%s acc=%.3f thresholds=%s",
             home_id, version, sha256[:12], accuracy, thresholds)
    return model


def _bump_version(prev: str | None) -> str:
    if prev is None:
        return "1.0.1"
    parts = prev.split(".")
    parts[-1] = str(int(parts[-1]) + 1)
    return ".".join(parts)
