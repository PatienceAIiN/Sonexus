from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import get_db
from ..models import Clip, Device, Event, Label, Metric, Model, User
from ..redisq import enqueue_job, get_device_state, get_redis
from ..schemas import LabelIn, TrainingRunIn
from ..security import get_current_user
from ..storage import get_storage

router = APIRouter(tags=["portal"], dependencies=[Depends(get_current_user)])


def _row(obj) -> dict:
    return {c.name: getattr(obj, c.name) for c in obj.__table__.columns}


@router.get("/clips")
async def list_clips(home_id: int, db: AsyncSession = Depends(get_db)):
    clips = (
        await db.execute(select(Clip).where(Clip.home_id == home_id).order_by(Clip.ts.desc()))
    ).scalars().all()
    return {"clips": [_row(c) for c in clips]}


@router.get("/clips/{clip_id}/audio")
async def clip_audio(clip_id: int, db: AsyncSession = Depends(get_db)):
    clip = await db.get(Clip, clip_id)
    if clip is None:
        raise HTTPException(status_code=404, detail="Unknown clip")
    data = get_storage().get(clip.storage_key)
    return Response(content=data, media_type="application/octet-stream")


@router.post("/labels", status_code=201)
async def create_label(
    body: LabelIn,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if (body.clip_id is None) == (body.event_id is None):
        raise HTTPException(status_code=422, detail="Provide exactly one of clip_id or event_id")
    if body.clip_id is not None and await db.get(Clip, body.clip_id) is None:
        raise HTTPException(status_code=404, detail="Unknown clip")
    if body.event_id is not None and await db.get(Event, body.event_id) is None:
        raise HTTPException(status_code=404, detail="Unknown event")
    label = Label(clip_id=body.clip_id, event_id=body.event_id, user_id=user.id,
                  label=body.label, correct=body.correct)
    db.add(label)
    await db.commit()
    return {"label_id": label.id}


@router.get("/metrics")
async def get_metrics(home_id: int, days: int = 7, db: AsyncSession = Depends(get_db)):
    since = datetime.now(timezone.utc) - timedelta(days=days)
    rows = (
        await db.execute(
            select(Metric).where(Metric.home_id == home_id, Metric.ts >= since).order_by(Metric.ts)
        )
    ).scalars().all()
    return {"metrics": [_row(m) for m in rows]}


@router.get("/models")
async def list_models(db: AsyncSession = Depends(get_db)):
    models = (await db.execute(select(Model).order_by(Model.id.desc()))).scalars().all()
    return {"models": [_row(m) for m in models]}


@router.post("/models/{model_id}/promote")
async def promote_model(model_id: int, db: AsyncSession = Depends(get_db)):
    m = await db.get(Model, model_id)
    if m is None:
        raise HTTPException(status_code=404, detail="Unknown model")
    others = (
        await db.execute(
            select(Model).where(
                Model.kind == m.kind, Model.home_id == m.home_id,
                Model.status == "active", Model.id != m.id,
            )
        )
    ).scalars().all()
    for other in others:
        other.status = "rollback"
    m.status = "active"
    await db.commit()
    return {"id": m.id, "status": m.status}


@router.post("/models/{model_id}/rollback")
async def rollback_model(model_id: int, db: AsyncSession = Depends(get_db)):
    """Mark this model as rolled back and re-activate the most recent prior version."""
    m = await db.get(Model, model_id)
    if m is None:
        raise HTTPException(status_code=404, detail="Unknown model")
    m.status = "rollback"
    prev = (
        await db.execute(
            select(Model)
            .where(Model.kind == m.kind, Model.home_id == m.home_id, Model.id != m.id)
            .order_by(Model.id.desc())
        )
    ).scalars().first()
    if prev is not None:
        prev.status = "active"
    await db.commit()
    return {"id": m.id, "status": m.status, "active_id": prev.id if prev else None}


@router.get("/devices")
async def list_devices(db: AsyncSession = Depends(get_db)):
    devices = (await db.execute(select(Device).order_by(Device.id))).scalars().all()
    r = get_redis()
    out = []
    for d in devices:
        state = await get_device_state(r, d.id)
        out.append({
            "id": d.id, "name": d.name, "home_id": d.home_id,
            "state": state.get("state"),
            "last_seen": state.get("last_seen") or (d.last_seen.isoformat() if d.last_seen else None),
        })
    return {"devices": out}


@router.post("/training/run", status_code=202)
async def run_training(body: TrainingRunIn, db: AsyncSession = Depends(get_db)):
    from ..models import Home

    if await db.get(Home, body.home_id) is None:
        raise HTTPException(status_code=404, detail="Unknown home")
    job_id = await enqueue_job(get_redis(), "train_home", home_id=body.home_id)
    return {"job_id": job_id}


@router.get("/settings")
async def get_settings(user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Cross-device settings: whatever the phone or web saved last."""
    return user.settings or {}


@router.put("/settings")
async def put_settings(patch: dict, user: User = Depends(get_current_user),
                       db: AsyncSession = Depends(get_db)):
    merged = dict(user.settings or {})
    merged.update(patch or {})
    user.settings = merged
    await db.commit()
    return merged
