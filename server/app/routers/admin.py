"""Login-gated admin dashboard: system health, training runs, live metrics.

Credentials come from ADMIN_USERNAME / ADMIN_PASSWORD env vars; the dashboard
is disabled entirely when no password is configured. Auth is a short-lived
admin JWT in an HttpOnly cookie.
"""
from datetime import datetime, timedelta, timezone

import jwt
from fastapi import APIRouter, Cookie, Depends, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db import get_db
from ..models import Clip, Device, Event, Home, Metric, Model, User
from ..redisq import get_redis

router = APIRouter(tags=["admin"])

STARTED_AT = datetime.now(timezone.utc)


class AdminLoginIn(BaseModel):
    username: str
    password: str


def _make_token() -> str:
    return jwt.encode(
        {"sub": "sonex-admin", "exp": datetime.now(timezone.utc) + timedelta(hours=8)},
        settings.jwt_secret, algorithm=settings.jwt_algorithm,
    )


def require_admin(admin_token: str | None = Cookie(default=None)) -> None:
    if not settings.admin_password:
        raise HTTPException(status_code=404, detail="Admin dashboard is not enabled")
    try:
        payload = jwt.decode(admin_token or "", settings.jwt_secret,
                             algorithms=[settings.jwt_algorithm])
        if payload.get("sub") != "sonex-admin":
            raise jwt.PyJWTError()
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail="Not signed in")


@router.post("/admin/login")
async def admin_login(body: AdminLoginIn):
    if not settings.admin_password:
        raise HTTPException(status_code=404, detail="Admin dashboard is not enabled")
    if body.username != settings.admin_username or body.password != settings.admin_password:
        raise HTTPException(status_code=401, detail="Wrong username or password")
    resp = JSONResponse({"ok": True})
    resp.set_cookie("admin_token", _make_token(), httponly=True, samesite="strict",
                    max_age=8 * 3600)
    return resp


TABLES = {"users": User, "homes": Home, "devices": Device, "events": Event,
          "clips": Clip, "models": Model, "metrics": Metric}
HIDDEN_COLS = {"password_hash", "api_key_hash"}

# Open-source audio/voice corpora SoNex's detection is tuned and evaluated
# against. Consented user clips (the "clips" table) are added on top of these to
# personalise per-home. Shown in the admin panel so it's clear what data is used.
DATASETS = [
    {"name": "Mozilla Common Voice", "kind": "voiced speech", "license": "CC0-1.0",
     "use": "talk vs. quiet trigger, many accents"},
    {"name": "Google Speech Commands", "kind": "short utterances", "license": "CC-BY-4.0",
     "use": "onset / short-word detection"},
    {"name": "LibriSpeech", "kind": "read speech", "license": "CC-BY-4.0",
     "use": "clean speech baseline"},
    {"name": "CHiME / whisper sets", "kind": "whispered speech", "license": "research",
     "use": "unvoiced whisper band tuning"},
    {"name": "MUSAN", "kind": "music + noise + speech", "license": "CC-BY-4.0",
     "use": "speech vs. music/noise separation"},
    {"name": "ESC-50", "kind": "environmental sounds", "license": "CC-BY-NC",
     "use": "fans, appliances, ambient noise"},
    {"name": "AudioSet (subset)", "kind": "labelled everyday audio", "license": "CC-BY-4.0",
     "use": "machine/cooler steady-noise class"},
    {"name": "On-device personalisation", "kind": "your room", "license": "stays on device",
     "use": "adaptive floor + calibration, realtime"},
]


def _row_dict(obj) -> dict:
    return {c.name: (str(v) if v is not None and not isinstance(v, (int, float, bool)) else v)
            for c in obj.__table__.columns
            if c.name not in HIDDEN_COLS
            for v in [getattr(obj, c.name)]}


@router.get("/admin/api/table/{table}", dependencies=[Depends(require_admin)])
async def table_rows(table: str, offset: int = 0, limit: int = 50,
                     db: AsyncSession = Depends(get_db)):
    model = TABLES.get(table)
    if model is None:
        raise HTTPException(status_code=404, detail="Unknown table")
    limit = max(1, min(limit, 200))
    offset = max(0, offset)
    total = (await db.execute(select(func.count()).select_from(model))).scalar() or 0
    rows = (await db.execute(
        select(model).order_by(model.id.desc()).offset(offset).limit(limit)
    )).scalars().all()
    return {"table": table, "rows": [_row_dict(r) for r in rows],
            "total": total, "offset": offset, "limit": limit}


@router.delete("/admin/api/table/{table}/{row_id}", dependencies=[Depends(require_admin)])
async def table_delete(table: str, row_id: int, db: AsyncSession = Depends(get_db)):
    model = TABLES.get(table)
    if model is None:
        raise HTTPException(status_code=404, detail="Unknown table")
    obj = await db.get(model, row_id)
    if obj is None:
        raise HTTPException(status_code=404, detail="Row not found")
    await db.delete(obj)
    await db.commit()
    from .. import cache
    cache.invalidate("admin:")
    return {"deleted": row_id}


@router.put("/admin/api/table/{table}/{row_id}", dependencies=[Depends(require_admin)])
async def table_update(table: str, row_id: int, patch: dict, db: AsyncSession = Depends(get_db)):
    model = TABLES.get(table)
    if model is None:
        raise HTTPException(status_code=404, detail="Unknown table")
    obj = await db.get(model, row_id)
    if obj is None:
        raise HTTPException(status_code=404, detail="Row not found")
    cols = {c.name for c in model.__table__.columns} - HIDDEN_COLS - {"id"}
    for k, v in patch.items():
        if k in cols:
            setattr(obj, k, v)
    await db.commit()
    from .. import cache
    cache.invalidate("admin:")
    return _row_dict(obj)


@router.get("/admin/api/stats", dependencies=[Depends(require_admin)])
async def admin_stats(db: AsyncSession = Depends(get_db)):
    from .. import cache

    cached = cache.get("admin:stats")
    if cached is not None:
        return cached

    counts_row = (await db.execute(select(
        select(func.count()).select_from(User).scalar_subquery(),
        select(func.count()).select_from(Home).scalar_subquery(),
        select(func.count()).select_from(Device).scalar_subquery(),
        select(func.count()).select_from(Event).scalar_subquery(),
        select(func.count()).select_from(Clip).scalar_subquery(),
        select(func.count()).select_from(Model).scalar_subquery(),
    ))).one()

    models = (await db.execute(
        select(Model).order_by(Model.id.desc()).limit(12)
    )).scalars().all()
    metrics = (await db.execute(
        select(Metric).order_by(Metric.id.desc()).limit(60)
    )).scalars().all()

    # Live training feed: the most recent labelled detections flowing in, and how
    # many consented clips exist per label (the data actually used to improve).
    recent_events = (await db.execute(
        select(Event).order_by(Event.id.desc()).limit(30)
    )).scalars().all()
    label_rows = (await db.execute(
        select(Clip.label, func.count()).group_by(Clip.label)
    )).all()

    redis_ok = True
    try:
        await get_redis().ping()
    except Exception:
        redis_ok = False

    return cache.put("admin:stats", {
        "uptime_sec": int((datetime.now(timezone.utc) - STARTED_AT).total_seconds()),
        "health": {"db": True, "redis": redis_ok},
        "counts": dict(zip(
            ("users", "homes", "devices", "events", "clips", "models"), counts_row
        )),
        "models": [
            {"id": m.id, "home_id": m.home_id, "kind": m.kind, "version": m.version,
             "status": m.status, "created_at": str(m.created_at)}
            for m in models
        ],
        "metrics": [
            {"ts": str(m.ts), "home_id": m.home_id, "name": m.name, "value": m.value}
            for m in reversed(metrics)
        ],
        "datasets": DATASETS,
        "training": [
            {"ts": str(e.ts)[:19], "type": e.type, "room_state": e.room_state,
             "action": e.action, "db": e.db, "source": e.source}
            for e in recent_events
        ],
        "labels": {(lbl or "unlabelled"): n for lbl, n in label_rows},
        "last_train": LAST_TRAIN,
    }, ttl_sec=2)


# Last automatic/manual training run, surfaced on the dashboard.
LAST_TRAIN: dict = {}


@router.post("/admin/api/train", dependencies=[Depends(require_admin)])
async def admin_train(db: AsyncSession = Depends(get_db)):
    """Manually run the same automated job that fires nightly at 02:00 IST:
    train on consented audio + priors, publish a new model OTA, delete the audio."""
    from .. import trainer_job
    report = await trainer_job.train_and_publish(db)
    LAST_TRAIN.clear()
    LAST_TRAIN.update(report)
    return report


@router.get("/admin/api/clips", dependencies=[Depends(require_admin)])
async def admin_clips(offset: int = 0, limit: int = 50, db: AsyncSession = Depends(get_db)):
    """Consented audio clips awaiting the next training run (then auto-deleted)."""
    limit = max(1, min(limit, 200)); offset = max(0, offset)
    total = (await db.execute(select(func.count()).select_from(Clip))).scalar() or 0
    rows = (await db.execute(
        select(Clip).order_by(Clip.id.desc()).offset(offset).limit(limit)
    )).scalars().all()
    return {"total": total, "offset": offset, "limit": limit, "clips": [
        {"id": c.id, "label": c.label, "room_state": c.room_state,
         "ts": str(c.ts)[:19], "duration_ms": c.duration_ms, "size_bytes": c.size_bytes,
         "backend": c.backend} for c in rows]}


@router.get("/admin/api/clip/{clip_id}/audio", dependencies=[Depends(require_admin)])
async def admin_clip_audio(clip_id: int, db: AsyncSession = Depends(get_db)):
    from fastapi.responses import Response
    from ..storage import get_storage
    clip = await db.get(Clip, clip_id)
    if clip is None:
        raise HTTPException(status_code=404, detail="Unknown clip")
    try:
        data = get_storage().get(clip.storage_key)
    except Exception:
        raise HTTPException(status_code=404, detail="Audio no longer available")
    return Response(content=data, media_type="audio/wav")


DASH = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SoNex Admin</title><meta name="robots" content="noindex">
<style>
 body{font-family:system-ui,sans-serif;background:#fff;color:#202124;margin:0;padding:24px}
 h1{color:#7C4DFF;font-size:1.5rem} h2{font-size:1rem;color:#0d9488;margin:22px 0 8px}
 .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:12px}
 .card{background:#f8f9fa;border:1px solid #e8eaed;border-radius:12px;padding:14px;text-align:center}
 .card b{font-size:1.7rem;display:block} .card span{color:#5f6368;font-size:.8rem}
 .ok{color:#0d9488}.bad{color:#d93025}
 table{width:100%;border-collapse:collapse;font-size:.85rem}
 td,th{padding:6px 8px;border-bottom:1px solid #e8eaed;text-align:left}
 #login{max-width:320px;margin:14vh auto;display:grid;gap:10px}
 input{padding:11px;border-radius:8px;border:1px solid #dadce0;background:#fff;color:#202124}
 button{padding:11px;border-radius:8px;border:0;background:#7C4DFF;color:#fff;font-weight:700;cursor:pointer}
 #err{color:#d93025;min-height:1.2em;font-size:.85rem}
 svg{width:100%;height:70px} .muted{color:#5f6368;font-size:.8rem}
 .card{cursor:pointer;transition:box-shadow .15s} .card:hover{box-shadow:0 4px 16px rgba(32,33,36,.12)}
 #modal{display:none;position:fixed;inset:0;background:rgba(32,33,36,.45);z-index:20;
   align-items:center;justify-content:center;padding:4vw}
 #modal .box{background:#fff;border-radius:14px;max-width:940px;width:100%;max-height:82vh;
   overflow:auto;padding:20px;box-shadow:0 18px 60px rgba(0,0,0,.25)}
 #modal h3{margin:0 0 12px;color:#7C4DFF;text-transform:capitalize}
 .rowbtn{padding:4px 10px;border-radius:6px;border:1px solid #dadce0;background:#fff;
   color:#5f6368;cursor:pointer;font-size:.78rem;margin-right:4px}
 .rowbtn.del{color:#d93025;border-color:#f5c6c0}
 .close{float:right;background:#f1f3f4;color:#202124}
</style></head><body>
<div id="login"><h1>SoNex Admin</h1>
 <input id="u" placeholder="Username" value="admin"><input id="p" type="password" placeholder="Password">
 <button onclick="login()">Sign in</button><div id="err"></div></div>
<div id="modal" onclick="if(event.target===this)closeModal()"><div class="box">
 <button class="rowbtn close" onclick="closeModal()">✕ Close</button>
 <h3 id="mtitle"></h3><div id="mbody" style="overflow:auto"></div>
</div></div>
<div id="dash" style="display:none">
 <h1>SoNex system health <span class="muted" id="uptime"></span></h1>
 <div class="grid" id="counts"></div>
 <h2>Live metrics (model performance)</h2><svg id="chart" preserveAspectRatio="none"></svg>
 <div class="muted" id="chartlabel"></div>
 <h2>Model training <span class="muted">· automatic, daily at 02:00 IST</span>
   <button id="trainBtn" onclick="trainNow()" style="margin-left:10px;padding:6px 14px;border:0;border-radius:8px;background:#7C4DFF;color:#fff;font-weight:700;cursor:pointer;font-size:.8rem">Run now</button>
   <span class="muted" id="trainMsg"></span></h2>
 <div class="muted" id="lastTrain"></div>
 <table id="models"><tr><th>ID</th><th>Home</th><th>Kind</th><th>Version</th><th>Status</th><th>Created</th></tr></table>
 <h2>Datasets &amp; data used to improve SoNex</h2>
 <div class="muted" id="labels"></div>
 <table id="datasets"><tr><th>Source</th><th>Type</th><th>Licence</th><th>What it improves</th></tr></table>
 <h2>Live training feed <span class="muted">(newest detections, auto-refresh)</span></h2>
 <table id="training"><tr><th>Time</th><th>Type</th><th>Room state</th><th>Action</th><th>Level (dB)</th><th>Source</th></tr></table>
</div>
<script>
async function login(){
 const r=await fetch('/admin/login',{method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({username:u.value,password:p.value})});
 if(r.ok){start()}else{err.textContent=(await r.json()).detail||'Failed'}
}
let timer=null;
async function refresh(){
 const r=await fetch('/admin/api/stats');
 if(!r.ok){clearInterval(timer);dash.style.display='none';login_.style.display='grid';return}
 const s=await r.json();
 login_.style.display='none';dash.style.display='block';
 uptime.textContent='· up '+Math.floor(s.uptime_sec/60)+' min';
 const c=s.counts;
 counts.innerHTML=Object.entries(c).map(([k,v])=>`<div class="card" onclick="openTable('${k}')"><b>${v}</b><span>${k}</span></div>`).join('')
  +`<div class="card" onclick="openClips()" style="outline:2px solid #7C4DFF33"><b>${c.clips??0}</b><span>🎧 uploaded audio ▶</span></div>`
  +`<div class="card"><b class="${s.health.db?'ok':'bad'}">${s.health.db?'✓':'✗'}</b><span>database</span></div>`
  +`<div class="card"><b class="${s.health.redis?'ok':'bad'}">${s.health.redis?'✓':'✗'}</b><span>redis</span></div>`;
 const lt=s.last_train||{};
 document.getElementById('lastTrain').textContent=lt.version
  ?`Last run: v${lt.version} · accuracy ${(lt.accuracy*100).toFixed(1)}% · ${lt.clips_used} clips used & deleted · ${lt.n_samples} samples · ${lt.at||''}`
  :'No training run yet this session — fires automatically at 02:00 IST.';
 const rows=s.models.map(m=>`<tr><td>${m.id}</td><td>${m.home_id??'—'}</td><td>${m.kind}</td><td>${m.version}</td><td>${m.status}</td><td>${m.created_at.slice(0,19)}</td></tr>`).join('');
 models.innerHTML='<tr><th>ID</th><th>Home</th><th>Kind</th><th>Version</th><th>Status</th><th>Created</th></tr>'+rows;
 const pts=s.metrics.filter(m=>m.name==='accuracy');
 if(pts.length>1){
  const w=600,h=70,min=Math.min(...pts.map(p=>p.value)),max=Math.max(...pts.map(p=>p.value))||1;
  const path=pts.map((p,i)=>`${i?'L':'M'}${i*w/(pts.length-1)},${h-4-(p.value-min)/(max-min||1)*(h-8)}`).join(' ');
  chart.setAttribute('viewBox',`0 0 ${w} ${h}`);
  chart.innerHTML=`<path d="${path}" fill="none" stroke="#0d9488" stroke-width="2"/>`;
  chartlabel.textContent=`accuracy: latest ${(pts.at(-1).value*100).toFixed(1)}% · ${pts.length} points (auto-refreshes every 3s)`;
 } else { chartlabel.textContent='No metrics yet — they appear after the first training run.'; }
 // Datasets + consented-clip label distribution.
 if(s.datasets){datasets.innerHTML='<tr><th>Source</th><th>Type</th><th>Licence</th><th>What it improves</th></tr>'+
  s.datasets.map(d=>`<tr><td><b>${d.name}</b></td><td>${d.kind}</td><td>${d.license}</td><td>${d.use}</td></tr>`).join('');}
 const lb=s.labels||{};const total=Object.values(lb).reduce((a,b)=>a+b,0);
 labels.textContent=total?('Consented training clips: '+Object.entries(lb).map(([k,v])=>`${k} ${v}`).join(' · ')+` · ${total} total`)
  :'No consented clips collected yet — users opt in via "Let SoNex learn my home".';
 // Live training feed.
 const t=s.training||[];
 training.innerHTML='<tr><th>Time</th><th>Type</th><th>Room state</th><th>Action</th><th>Level (dB)</th><th>Source</th></tr>'+
  (t.length?t.map(e=>`<tr><td>${e.ts}</td><td>${e.type}</td><td>${e.room_state??'—'}</td><td>${e.action??'—'}</td><td>${e.db!=null?e.db.toFixed(1):'—'}</td><td>${e.source??'—'}</td></tr>`).join('')
   :'<tr><td colspan="6" class="muted">No detections received yet.</td></tr>');
}
let _tbl={name:'',offset:0,limit:50};
async function openTable(name,offset){
 _tbl.name=name;_tbl.offset=Math.max(0,offset||0);
 const r=await fetch(`/admin/api/table/${name}?offset=${_tbl.offset}&limit=${_tbl.limit}`);
 if(!r.ok){alert('Unavailable');return}
 const d=await r.json();
 const from=d.total?d.offset+1:0,to=d.offset+d.rows.length;
 mtitle.textContent=`${name} · ${from}-${to} of ${d.total}`;
 const hasPrev=d.offset>0,hasNext=to<d.total;
 const pager=`<div style="display:flex;gap:8px;justify-content:flex-end;margin-top:10px">
   <button class="rowbtn" ${hasPrev?'':'disabled'} onclick="openTable('${name}',${Math.max(0,d.offset-d.limit)})">‹ Prev</button>
   <button class="rowbtn" ${hasNext?'':'disabled'} onclick="openTable('${name}',${d.offset+d.limit})">Next ›</button></div>`;
 if(!d.rows.length){mbody.innerHTML='<p class="muted">No rows.</p>'+pager}
 else{
  const cols=Object.keys(d.rows[0]);
  mbody.innerHTML='<table><tr>'+cols.map(x=>'<th>'+x+'</th>').join('')+'<th></th></tr>'+
   d.rows.map(row=>'<tr>'+cols.map(x=>'<td>'+String(row[x]??'—').slice(0,60)+'</td>').join('')+
   `<td><button class="rowbtn" onclick='editRow("${name}",${row.id},${JSON.stringify(JSON.stringify(row))})'>Edit</button>`+
   `<button class="rowbtn del" onclick="delRow('${name}',${row.id})">Delete</button></td></tr>`).join('')+'</table>'+pager;
 }
 modal.style.display='flex';
}
function closeModal(){modal.style.display='none'}
async function delRow(name,id){
 if(!confirm('Delete '+name+' #'+id+'? This cannot be undone.'))return;
 const r=await fetch(`/admin/api/table/${name}/${id}`,{method:'DELETE'});
 if(r.ok){openTable(name,_tbl.offset);refresh()}else{alert((await r.json()).detail||'Failed')}
}
async function editRow(name,id,rowJson){
 const edited=prompt('Edit fields as JSON, then OK to save:',rowJson);
 if(edited===null)return;
 try{
  const r=await fetch(`/admin/api/table/${name}/${id}`,{method:'PUT',
   headers:{'Content-Type':'application/json'},body:edited});
  if(r.ok){openTable(name,_tbl.offset);refresh()}else{alert((await r.json()).detail||'Rejected')}
 }catch(_){alert('Invalid JSON')}
}
let _clipOff=0;
async function openClips(offset){
 _clipOff=Math.max(0,offset||0);
 const r=await fetch(`/admin/api/clips?offset=${_clipOff}&limit=25`);
 if(!r.ok){alert('Unavailable');return}
 const d=await r.json();
 const from=d.total?d.offset+1:0,to=d.offset+d.clips.length;
 mtitle.textContent=`Uploaded audio · ${from}-${to} of ${d.total}`;
 const hasPrev=d.offset>0,hasNext=to<d.total;
 const pager=`<div style="display:flex;gap:8px;justify-content:flex-end;margin-top:10px">
   <button class="rowbtn" ${hasPrev?'':'disabled'} onclick="openClips(${Math.max(0,d.offset-d.limit)})">‹ Prev</button>
   <button class="rowbtn" ${hasNext?'':'disabled'} onclick="openClips(${d.offset+d.limit})">Next ›</button></div>`;
 if(!d.clips.length){mbody.innerHTML='<p class="muted">No audio collected yet. Clips appear here only for users who enabled "Let SoNex learn my home", and are deleted right after each training run.</p>'+pager}
 else{
  mbody.innerHTML='<audio id="clipPlayer" controls style="width:100%;margin-bottom:12px"></audio>'+
   '<table><tr><th>ID</th><th>Label</th><th>Room state</th><th>When</th><th>ms</th><th>KB</th><th></th></tr>'+
   d.clips.map(x=>`<tr><td>${x.id}</td><td>${x.label??'—'}</td><td>${x.room_state??'—'}</td><td>${x.ts}</td><td>${x.duration_ms??'—'}</td><td>${x.size_bytes?Math.round(x.size_bytes/1024):'—'}</td>`+
    `<td><button class="rowbtn" onclick="playClip(${x.id})">▶ Play</button></td></tr>`).join('')+'</table>'+pager;
 }
 modal.style.display='flex';
}
function playClip(id){const p=document.getElementById('clipPlayer');if(!p)return;p.src='/admin/api/clip/'+id+'/audio';p.play().catch(()=>{});}
async function trainNow(){
 const b=document.getElementById('trainBtn'),m=document.getElementById('trainMsg');
 b.disabled=true;b.textContent='Training…';m.textContent='';
 try{const r=await fetch('/admin/api/train',{method:'POST'});
  const d=await r.json();
  if(r.ok){m.textContent=` published v${d.version} · accuracy ${(d.accuracy*100).toFixed(1)}% · ${d.clips_used} clips used & deleted · ${d.backend}`;refresh()}
  else{m.textContent=' '+(d.detail||'Failed')}
 }catch(_){m.textContent=' Network error'}
 b.disabled=false;b.textContent='⚡ Train now'}
function start(){refresh();timer=setInterval(refresh,3000)}
const login_=document.getElementById('login');
p.addEventListener('keydown',e=>{if(e.key==='Enter')login()});
refresh(); // already signed in? jump straight to the dashboard
</script></body></html>"""


@router.get("/admin", response_class=HTMLResponse)
async def admin_page():
    if not settings.admin_password:
        raise HTTPException(status_code=404, detail="Not found")
    return DASH
