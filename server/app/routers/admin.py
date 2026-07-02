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
    }, ttl_sec=2)


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
</style></head><body>
<div id="login"><h1>SoNex Admin</h1>
 <input id="u" placeholder="Username" value="admin"><input id="p" type="password" placeholder="Password">
 <button onclick="login()">Sign in</button><div id="err"></div></div>
<div id="dash" style="display:none">
 <h1>SoNex system health <span class="muted" id="uptime"></span></h1>
 <div class="grid" id="counts"></div>
 <h2>Live metrics (model performance)</h2><svg id="chart" preserveAspectRatio="none"></svg>
 <div class="muted" id="chartlabel"></div>
 <h2>Model training runs</h2><table id="models"><tr><th>ID</th><th>Home</th><th>Kind</th><th>Version</th><th>Status</th><th>Created</th></tr></table>
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
 counts.innerHTML=Object.entries(c).map(([k,v])=>`<div class="card"><b>${v}</b><span>${k}</span></div>`).join('')
  +`<div class="card"><b class="${s.health.db?'ok':'bad'}">${s.health.db?'✓':'✗'}</b><span>database</span></div>`
  +`<div class="card"><b class="${s.health.redis?'ok':'bad'}">${s.health.redis?'✓':'✗'}</b><span>redis</span></div>`;
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
}
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
