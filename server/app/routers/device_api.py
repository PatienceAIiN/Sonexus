import json
import secrets
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Response, UploadFile
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import get_db
from ..models import Clip, Consent, Device, Event, Label, Model, Session, User
from pydantic import BaseModel

from ..redisq import get_redis, rate_limited, set_device_state
from ..schemas import ClipMeta, ConsentIn, EventsIn
from ..security import get_current_device, get_current_user, get_device_or_user
from ..storage import get_storage

router = APIRouter(tags=["device"])


async def _has_consent(db: AsyncSession, home_id: int, purpose: str) -> bool:
    result = await db.execute(
        select(Consent).where(Consent.home_id == home_id, Consent.purpose == purpose, Consent.granted.is_(True))
    )
    return result.scalar_one_or_none() is not None


@router.post("/events", status_code=202, dependencies=[Depends(rate_limited)])
async def ingest_events(
    body: EventsIn,
    device: Device = Depends(get_current_device),
    db: AsyncSession = Depends(get_db),
):
    for e in body.events:
        db.add(Event(device_id=device.id, **e.model_dump()))
    last_state = next((e.room_state for e in reversed(body.events) if e.room_state), None)
    device.last_seen = datetime.now(timezone.utc)
    await db.commit()
    await set_device_state(get_redis(), device.id, last_state)
    return {"accepted": len(body.events)}


@router.post("/clips", status_code=201, dependencies=[Depends(rate_limited)])
async def upload_clip(
    file: UploadFile = File(...),
    meta: str = Form(...),
    device: Device = Depends(get_current_device),
    db: AsyncSession = Depends(get_db),
):
    if not await _has_consent(db, device.home_id, "upload_clips"):
        raise HTTPException(status_code=403, detail="upload_clips consent not granted")
    try:
        clip_meta = ClipMeta.model_validate(json.loads(meta))
    except (json.JSONDecodeError, ValueError) as exc:
        raise HTTPException(status_code=422, detail=f"Invalid meta: {exc}")

    data = await file.read()
    ext = (file.filename or "clip.bin").rsplit(".", 1)[-1][:8]
    key = f"clips/{device.home_id}/{device.id}/{uuid.uuid4().hex}.{ext}"
    backend = get_storage().put(key, data, file.content_type or "application/octet-stream")

    clip = Clip(
        device_id=device.id,
        home_id=device.home_id,
        ts=clip_meta.ts,
        duration_ms=clip_meta.duration_ms,
        label=clip_meta.label,
        room_state=clip_meta.room_state,
        storage_key=key,
        backend=backend,
        size_bytes=len(data),
    )
    db.add(clip)
    await db.commit()
    await set_device_state(get_redis(), device.id)
    return {"clip_id": clip.id, "storage_key": key, "backend": backend}


@router.put("/consents")
async def put_consent(
    body: ConsentIn,
    device: Device = Depends(get_current_device),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Consent).where(Consent.home_id == device.home_id, Consent.purpose == body.purpose)
    )
    consent = result.scalar_one_or_none()
    if consent is None:
        consent = Consent(home_id=device.home_id, purpose=body.purpose)
        db.add(consent)
    consent.granted = body.granted
    await db.commit()
    return {"purpose": body.purpose, "granted": body.granted}


# ---- Models / OTA ----

_MODEL_KINDS = ("vad", "sound", "home")


@router.get("/models/manifest")
async def model_manifest(
    device: int,
    caller: Device = Depends(get_current_device),
    db: AsyncSession = Depends(get_db),
):
    dev = await db.get(Device, device)
    if dev is None:
        raise HTTPException(status_code=404, detail="Unknown device")
    # ONE query for every candidate model (this home + global), newest first;
    # pick per kind in Python. Was 6 sequential round-trips.
    rows = (await db.execute(
        select(Model)
        .where(
            Model.status == "active",
            Model.kind.in_(_MODEL_KINDS),
            (Model.home_id == dev.home_id) | (Model.home_id.is_(None)),
        )
        .order_by(Model.id.desc())
    )).scalars().all()
    models: dict = {}
    for kind in _MODEL_KINDS:
        m = (next((r for r in rows if r.kind == kind and r.home_id == dev.home_id), None)
             or next((r for r in rows if r.kind == kind and r.home_id is None), None))
        if m is not None:
            models[kind] = {
                "id": m.id, "file": m.file, "version": m.version, "sha256": m.sha256,
                "min_app_version": m.min_app_version, "url": f"/v1/models/{m.id}/download",
            }
    from ..models import Home

    home = await db.get(Home, dev.home_id)
    thresholds = home.thresholds if home and home.thresholds else {"sensitivity": 0.5, "boost_margin_db": 8.0}
    return {"models": models, "thresholds": thresholds}


@router.get("/models/{model_id}/download")
async def download_model(
    model_id: int,
    caller: Device | User = Depends(get_device_or_user),
    db: AsyncSession = Depends(get_db),
):
    m = await db.get(Model, model_id)
    if m is None:
        raise HTTPException(status_code=404, detail="Unknown model")
    data = get_storage().get(m.storage_key)
    return Response(content=data, media_type="application/octet-stream",
                    headers={"Content-Disposition": f'attachment; filename="{m.file}"'})


# ---- Privacy (device or user auth) ----

@router.post("/data/delete")
async def data_delete(
    caller: Device | User = Depends(get_device_or_user),
    db: AsyncSession = Depends(get_db),
):
    storage = get_storage()
    counts: dict[str, int] = {}
    if isinstance(caller, Device):
        clips = (await db.execute(select(Clip).where(Clip.device_id == caller.id))).scalars().all()
        for clip in clips:
            storage.delete(clip.storage_key)
        clip_ids = [c.id for c in clips]
        if clip_ids:
            counts["labels"] = (await db.execute(delete(Label).where(Label.clip_id.in_(clip_ids)))).rowcount
        event_ids = [e for (e,) in (await db.execute(select(Event.id).where(Event.device_id == caller.id))).all()]
        if event_ids:
            counts["labels"] = counts.get("labels", 0) + (
                await db.execute(delete(Label).where(Label.event_id.in_(event_ids)))
            ).rowcount
        counts["clips"] = (await db.execute(delete(Clip).where(Clip.device_id == caller.id))).rowcount
        counts["events"] = (await db.execute(delete(Event).where(Event.device_id == caller.id))).rowcount
        counts["sessions"] = (await db.execute(delete(Session).where(Session.device_id == caller.id))).rowcount
        await db.delete(caller)
        counts["devices"] = 1
        await get_redis().delete(f"sonex:device:{caller.id}")
    else:
        counts["labels"] = (await db.execute(delete(Label).where(Label.user_id == caller.id))).rowcount
        await db.delete(caller)
        counts["users"] = 1
    await db.commit()
    return {"deleted": True, **counts}


@router.get("/data/export")
async def data_export(
    caller: Device | User = Depends(get_device_or_user),
    db: AsyncSession = Depends(get_db),
):
    def row(obj) -> dict:
        return {c.name: getattr(obj, c.name) for c in obj.__table__.columns}

    if isinstance(caller, Device):
        events = (await db.execute(select(Event).where(Event.device_id == caller.id))).scalars().all()
        clips = (await db.execute(select(Clip).where(Clip.device_id == caller.id))).scalars().all()
        sessions = (await db.execute(select(Session).where(Session.device_id == caller.id))).scalars().all()
        consents = (await db.execute(select(Consent).where(Consent.home_id == caller.home_id))).scalars().all()
        device_row = row(caller)
        device_row.pop("api_key_hash", None)
        return {
            "device": device_row,
            "events": [row(e) for e in events],
            "clips": [row(c) for c in clips],
            "sessions": [row(s) for s in sessions],
            "consents": [row(c) for c in consents],
        }
    labels = (await db.execute(select(Label).where(Label.user_id == caller.id))).scalars().all()
    user_row = row(caller)
    user_row.pop("password_hash", None)
    return {"user": user_row, "labels": [row(lb) for lb in labels]}


# ---- TV cloud relay: lets SoNex Web pair with and control a TV from any
# network. The TV registers its pairing code and polls for queued commands;
# the browser pairs by code and pushes commands through the server. ----

class TvRegisterIn(BaseModel):
    code: str
    name: str = "SoNex TV"


class TvPairIn(BaseModel):
    code: str


class TvCommandIn(BaseModel):
    action: str
    level: int = -1


@router.post("/tv/register")
async def tv_register(body: TvRegisterIn):
    """TV announces its 4-digit code. Returns the key it polls with."""
    r = get_redis()
    tv_key = secrets.token_urlsafe(24)
    await r.setex(f"tvcode:{body.code}", 900, tv_key)          # code valid 15 min
    await r.setex(f"tv:{tv_key}:name", 86400, body.name)
    return {"tv_key": tv_key}


@router.post("/tv/pair")
async def tv_pair(body: TvPairIn, user: User = Depends(get_current_user)):
    """Browser submits the code shown on the TV."""
    r = get_redis()
    tv_key = await r.get(f"tvcode:{body.code}")
    if not tv_key:
        raise HTTPException(status_code=404, detail="Wrong code, or the TV code expired — reopen the TV app")
    tv_key = tv_key.decode() if isinstance(tv_key, bytes) else tv_key
    await r.setex(f"tv:{tv_key}:user", 86400 * 30, str(user.id))
    await r.setex(f"user:{user.id}:tv", 86400 * 30, tv_key)
    name = await r.get(f"tv:{tv_key}:name")
    return {"ok": True, "tv_name": (name.decode() if isinstance(name, bytes) else name) or "SoNex TV"}


@router.get("/tv/poll")
async def tv_poll(x_tv_key: str | None = Header(default=None)):
    """TV polls: am I paired, and what commands are queued?"""
    if not x_tv_key:
        raise HTTPException(status_code=401, detail="Missing X-Tv-Key")
    r = get_redis()
    paired = await r.get(f"tv:{x_tv_key}:user") is not None
    commands = []
    while True:
        raw = await r.lpop(f"tv:{x_tv_key}:queue")
        if raw is None:
            break
        commands.append(json.loads(raw))
    return {"paired": paired, "commands": commands}


@router.post("/tv/command")
async def tv_command(body: TvCommandIn, user: User = Depends(get_current_user)):
    """Browser sends a command to the user's paired TV."""
    r = get_redis()
    tv_key = await r.get(f"user:{user.id}:tv")
    if not tv_key:
        raise HTTPException(status_code=404, detail="No TV paired yet")
    tv_key = tv_key.decode() if isinstance(tv_key, bytes) else tv_key
    await r.rpush(f"tv:{tv_key}:queue",
                  json.dumps({"action": body.action, "level": body.level, "reason": "web"}))
    await r.ltrim(f"tv:{tv_key}:queue", -20, -1)  # never grow unbounded
    return {"queued": True}
