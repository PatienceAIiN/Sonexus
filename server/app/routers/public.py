"""Public, unauthenticated routes: landing page, app releases, APK downloads.

APKs live in object storage under apk/ next to a releases.json:
  {"mobile": {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-mobile.apk"},
   "tv":     {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-tv.apk"}}
Publishing a new build = upload new APK + bump releases.json. No redeploy.
"""
import json

from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse, RedirectResponse

from ..storage import get_storage

router = APIRouter(tags=["public"])

RELEASES_KEY = "apk/releases.json"


def _releases() -> dict:
    try:
        data = get_storage().get(RELEASES_KEY)
        return json.loads(data)
    except Exception:
        raise HTTPException(status_code=404, detail="No releases published yet")


@router.get("/v1/app/releases")
async def app_releases():
    rel = _releases()
    storage = get_storage()
    out = {}
    for target, info in rel.items():
        out[target] = {
            "version_code": info["version_code"],
            "version_name": info.get("version_name", ""),
            "url": storage.url(info["key"]),
        }
    return out


@router.get("/download/{target}")
async def download(target: str):
    rel = _releases()
    if target not in rel:
        raise HTTPException(status_code=404, detail="Unknown app")
    return RedirectResponse(get_storage().url(rel[target]["key"]), status_code=307)


LANDING = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>SoNex — volume that listens to the room</title>
<style>
  :root { --violet:#7C4DFF; --teal:#2DD4BF; --ink:#0E0B1A; --coral:#FF5C7A; }
  * { margin:0; box-sizing:border-box; }
  body { font-family:system-ui,-apple-system,sans-serif; background:var(--ink); color:#fff;
         min-height:100vh; overflow-x:hidden; }
  .bg { position:fixed; inset:0; z-index:0;
        background:linear-gradient(-45deg,#0E0B1A,#1E1836,#2a1a4d,#0e2a28,#0E0B1A);
        background-size:400% 400%; animation:drift 18s ease infinite; }
  @keyframes drift { 0%{background-position:0% 50%} 50%{background-position:100% 50%} 100%{background-position:0% 50%} }
  .orb { position:fixed; border-radius:50%; filter:blur(70px); opacity:.45; z-index:0; }
  .o1 { width:420px; height:420px; background:var(--violet); top:-90px; left:-90px; animation:float1 13s ease-in-out infinite; }
  .o2 { width:340px; height:340px; background:var(--teal); bottom:-70px; right:-70px; animation:float2 16s ease-in-out infinite; }
  .o3 { width:220px; height:220px; background:var(--coral); top:55%; left:60%; animation:float1 21s ease-in-out infinite reverse; }
  @keyframes float1 { 0%,100%{transform:translate(0,0) scale(1)} 50%{transform:translate(70px,45px) scale(1.15)} }
  @keyframes float2 { 0%,100%{transform:translate(0,0) scale(1)} 50%{transform:translate(-60px,-40px) scale(1.1)} }
  main { position:relative; z-index:1; max-width:960px; margin:0 auto; padding:9vh 24px 40px; }
  h1 { font-size:clamp(3rem,9vw,5.5rem); font-weight:900; letter-spacing:-2px;
       background:linear-gradient(90deg,var(--violet),var(--teal)); -webkit-background-clip:text;
       background-clip:text; color:transparent; animation:pop .8s ease both; }
  @keyframes pop { from{opacity:0; transform:translateY(24px)} to{opacity:1} }
  .bars { display:flex; gap:6px; align-items:flex-end; height:46px; margin:18px 0 10px; }
  .bars span { width:9px; border-radius:5px; background:var(--violet); animation:eq 1.1s ease-in-out infinite; }
  .bars span:nth-child(2){ animation-delay:.15s; background:var(--teal);}
  .bars span:nth-child(3){ animation-delay:.3s; }
  .bars span:nth-child(4){ animation-delay:.45s; background:var(--teal);}
  .bars span:nth-child(5){ animation-delay:.6s; }
  @keyframes eq { 0%,100%{height:22%} 50%{height:100%} }
  .tag { font-size:1.3rem; color:#cfc8e8; max-width:640px; line-height:1.6; animation:pop 1s .15s ease both; }
  .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(230px,1fr)); gap:16px; margin:52px 0; }
  .card { background:rgba(255,255,255,.05); border:1px solid rgba(255,255,255,.09);
          border-radius:18px; padding:22px; backdrop-filter:blur(8px); animation:pop .9s .25s ease both; }
  .card h3 { margin-bottom:8px; font-size:1.03rem; } .card p { color:#b9b3cc; font-size:.92rem; line-height:1.55; }
  .dl { display:flex; gap:14px; flex-wrap:wrap; margin-top:8px; }
  .btn { display:inline-flex; align-items:center; gap:10px; padding:15px 28px; border-radius:14px;
         font-weight:700; font-size:1.02rem; text-decoration:none; transition:transform .15s ease, box-shadow .15s; }
  .btn:hover { transform:translateY(-2px); box-shadow:0 10px 30px rgba(124,77,255,.35); }
  .btn.m { background:linear-gradient(90deg,var(--violet),#9d7bff); color:#fff; }
  .btn.t { background:rgba(255,255,255,.08); color:#fff; border:1px solid rgba(255,255,255,.16); }
  footer { position:relative; z-index:1; text-align:center; padding:26px; color:#8f88a8; font-size:.9rem; }
  footer a { color:var(--teal); text-decoration:none; }
</style></head><body>
<div class="bg"></div><div class="orb o1"></div><div class="orb o2"></div><div class="orb o3"></div>
<main>
  <h1>SoNex</h1>
  <div class="bars"><span></span><span></span><span></span><span></span><span></span></div>
  <p class="tag">Your TV listens to the room. Someone starts talking — the volume ducks.
     Quiet returns — so does your volume. Loud blender? SoNex turns it up instead.
     On-device AI, private by default.</p>
  <div class="grid">
    <div class="card"><h3>🗣 Detects real speech</h3><p>Silero VAD + YAMNet on your phone tell conversation apart from kitchen noise — no audio ever leaves the device by default.</p></div>
    <div class="card"><h3>📺 Every output</h3><p>Phone, Android TV companion, Bluetooth speakers and Chromecast — each with its own rule: duck, mute, pause or boost.</p></div>
    <div class="card"><h3>🎙 “SoNex, lower volume”</h3><p>Offline voice control in English and Hindi, gated behind a wake word and an explicit consent toggle.</p></div>
    <div class="card"><h3>🧠 Learns your home</h3><p>With consent, your corrections train a per-home model delivered over the air — no app updates needed.</p></div>
  </div>
  <div class="dl">
    <a class="btn m" href="/download/mobile">📱 Download for Android</a>
    <a class="btn t" href="/download/tv">📺 Download for Android TV</a>
  </div>
</main>
<footer>a product of <a href="https://patienceai.in">Patience AI · patienceai.in</a></footer>
</body></html>"""


@router.get("/", response_class=HTMLResponse)
async def landing():
    return LANDING
