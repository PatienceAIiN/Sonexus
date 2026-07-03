"""Public, unauthenticated routes: landing page, app releases, APK downloads.

APKs live in object storage under apk/ next to a releases.json:
  {"mobile": {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-mobile.apk"},
   "tv":     {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-tv.apk"}}
Publishing a new build = upload new APK + bump releases.json. No redeploy.
"""
import json

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, PlainTextResponse, RedirectResponse, Response
from pydantic import BaseModel

from ..config import settings
from ..emailer import send_email
from ..storage import get_storage

router = APIRouter(tags=["public"])

RELEASES_KEY = "apk/releases.json"


def _base(request: Request) -> str:
    """Canonical origin for SEO links: SITE_URL env wins, else the host the
    request actually arrived on (custom domain aware via the proxy header)."""
    if settings.site_url:
        return settings.site_url.rstrip("/")
    host = request.headers.get("x-forwarded-host") or request.url.hostname or "sonex.patienceai.in"
    return f"https://{host}"


def _releases() -> dict:
    from .. import cache

    hit = cache.get("releases")
    if hit is not None:
        return hit
    try:
        data = get_storage().get(RELEASES_KEY)
        return cache.put("releases", json.loads(data), ttl_sec=30)
    except Exception:
        raise HTTPException(status_code=404, detail="No releases published yet")


def _signed_url(key: str) -> str:
    """Presigned URLs live 1h; cache 30min so downloads skip storage entirely."""
    from .. import cache

    hit = cache.get(f"url:{key}")
    if hit is not None:
        return hit
    return cache.put(f"url:{key}", get_storage().url(key), ttl_sec=1800)


@router.get("/v1/app/releases")
async def app_releases():
    rel = _releases()
    out = {}
    for target, info in rel.items():
        out[target] = {
            "version_code": info["version_code"],
            "version_name": info.get("version_name", ""),
            "url": _signed_url(info["key"]),
        }
    return out


@router.get("/download/{target}")
async def download(target: str):
    rel = _releases()
    if target not in rel:
        raise HTTPException(status_code=404, detail="Unknown app")
    return RedirectResponse(_signed_url(rel[target]["key"]), status_code=307)


ANDROID_SVG = """<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M17.6 9.48l1.84-3.18c.16-.31.04-.7-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24c-2.86-1.21-6.08-1.21-8.94 0L5.65 5.67c-.19-.29-.58-.38-.87-.2-.28.18-.37.54-.22.83L6.4 9.48C3.3 11.25 1.28 14.44 1 18h22c-.28-3.56-2.3-6.75-5.4-8.52zM7 15.25c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25zm10 0c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25z"/></svg>"""

LANDING = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>SoNex — volume that listens to the room</title>
<meta name="description" content="SoNex automatically lowers your TV and phone volume when someone talks, and restores it when the room is quiet.">
__GSV__
<link rel="canonical" href="__BASE__/">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='7' fill='%230E0B1A'/%3E%3Crect x='6' y='13' width='3.5' height='8' rx='1.7' fill='%237C4DFF'/%3E%3Crect x='11.5' y='9' width='3.5' height='16' rx='1.7' fill='%237C4DFF'/%3E%3Crect x='17' y='5' width='3.5' height='24' rx='1.7' fill='%232DD4BF'/%3E%3Crect x='22.5' y='11' width='3.5' height='12' rx='1.7' fill='%237C4DFF'/%3E%3C/svg%3E">
<meta property="og:title" content="SoNex — volume that listens to the room">
<meta property="og:description" content="Someone talks, your TV gets quiet. Room settles, volume comes back. On-device AI, private by default.">
<meta property="og:type" content="website">
<meta property="og:url" content="__BASE__/">
<meta name="twitter:card" content="summary">
<meta name="robots" content="index,follow">
<style>
  :root { --violet:#7C4DFF; --teal:#0d9488; --ink:#202124; --sub:#5f6368; --line:#e8eaed; }
  * { margin:0; box-sizing:border-box; }
  html { scroll-behavior:smooth; }
  body { font-family:'Google Sans',system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;
         color:var(--ink); background:#fff; line-height:1.6; }
  nav { position:sticky; top:0; z-index:10; display:flex; align-items:center; gap:28px;
        padding:14px 6vw; background:rgba(255,255,255,.92); backdrop-filter:blur(10px);
        border-bottom:1px solid var(--line); }
  nav .logo { font-weight:900; font-size:1.8rem; letter-spacing:-.5px; margin-right:auto;
      background:linear-gradient(120deg,#7C4DFF,#2DD4BF,#FF5C7A,#7C4DFF); background-size:300% 300%;
      -webkit-background-clip:text; background-clip:text; color:transparent;
      animation:shimmer 6s ease infinite, breathe 2.6s ease-in-out infinite; display:inline-block; }
  @keyframes shimmer { 0%{background-position:0% 50%} 50%{background-position:100% 50%} 100%{background-position:0% 50%} }
  @keyframes breathe { 0%,100%{transform:scale(1)} 50%{transform:scale(1.05)} }
  nav a { color:var(--sub); text-decoration:none; font-size:.95rem; }
  nav a:hover { color:var(--ink); }
  .hero { text-align:center; padding:70px 6vw 60px; }
  .rotator { min-height:7rem; display:flex; align-items:center; justify-content:center; padding:0 4vw; }
  .rotator span { font-size:clamp(2rem,6.5vw,3.8rem); font-weight:800; letter-spacing:-1.5px; line-height:1.15;
      background:linear-gradient(120deg,#7C4DFF,#2DD4BF); -webkit-background-clip:text;
      background-clip:text; color:transparent; opacity:0; transform:translateY(18px);
      transition:opacity .9s ease, transform .9s ease; }
  .rotator span.show { opacity:1; transform:translateY(0); }
  .hero .sub { margin-top:10px; font-size:1.05rem; color:var(--sub); }
  .bars { display:flex; gap:5px; align-items:flex-end; height:40px; justify-content:center; margin:28px 0 4px; }
  .bars i { width:7px; border-radius:4px; background:var(--violet); animation:eq 1.2s ease-in-out infinite; }
  .bars i:nth-child(2){animation-delay:.15s; background:var(--teal);} .bars i:nth-child(3){animation-delay:.3s;}
  .bars i:nth-child(4){animation-delay:.45s; background:var(--teal);} .bars i:nth-child(5){animation-delay:.6s;}
  @keyframes eq { 0%,100%{height:20%} 50%{height:100%} }
  .cta { display:flex; gap:14px; justify-content:center; flex-wrap:wrap; margin-top:34px; }
  .btn { display:inline-flex; align-items:center; gap:10px; padding:14px 28px; border-radius:26px;
         font-weight:600; font-size:1rem; text-decoration:none; transition:box-shadow .2s, transform .15s; }
  .btn:hover { transform:translateY(-1px); box-shadow:0 6px 20px rgba(124,77,255,.25); }
  .btn.primary { background:var(--violet); color:#fff; }
  .btn.secondary { background:#fff; color:var(--violet); border:1.5px solid var(--violet); }
  .btn.web { background:linear-gradient(120deg,#7C4DFF,#2DD4BF); color:#fff;
    animation:webglow 2.2s ease-in-out infinite; }
  @keyframes webglow { 0%,100%{box-shadow:0 0 10px rgba(124,77,255,.45)}
    50%{box-shadow:0 0 26px rgba(45,212,191,.65)} }
  section { padding:64px 6vw; max-width:1100px; margin:0 auto; }
  section h2 { font-size:2rem; font-weight:700; text-align:center; margin-bottom:38px; }
  .features { display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:22px; }
  .f { border:1px solid var(--line); border-radius:16px; padding:26px; transition:box-shadow .2s; }
  .f:hover { box-shadow:0 8px 28px rgba(32,33,36,.08); }
  .f .em { font-size:1.9rem; } .f h3 { margin:10px 0 6px; font-size:1.05rem; }
  .f p { color:var(--sub); font-size:.93rem; }
  .how { background:#f8f9fa; }
  .how ol { max-width:640px; margin:0 auto; color:var(--sub); font-size:1.02rem; }
  .how li { margin:14px 0; padding-left:6px; } .how b { color:var(--ink); }
  .contact form { max-width:520px; margin:0 auto; display:grid; gap:14px; }
  .contact input, .contact textarea { padding:13px 16px; border:1px solid var(--line);
     border-radius:10px; font:inherit; width:100%; }
  .contact input:focus, .contact textarea:focus { outline:2px solid var(--violet); border-color:transparent; }
  .contact button { justify-self:center; border:0; cursor:pointer; }
  #contact-status { text-align:center; color:var(--teal); min-height:1.4em; }
  .ddi { display:flex; justify-content:space-between; gap:16px; padding:10px; border-radius:9px;
     color:var(--ink); text-decoration:none; font-weight:600; font-size:.95rem; }
  .ddi span { color:var(--sub); font-weight:400; font-size:.8rem; }
  .ddi:hover { background:#f8f9fa; } .ddi.off { opacity:.5; cursor:default; }
  footer { border-top:1px solid var(--line); padding:26px 6vw; display:flex; flex-wrap:wrap;
           gap:14px; align-items:center; justify-content:space-between; color:var(--sub); font-size:.92rem; }
  footer .links { display:flex; gap:22px; } footer a { color:var(--sub); text-decoration:none; }
  footer a:hover { color:var(--violet); }
  @media (max-width:640px) {
    nav { gap:16px; padding:12px 5vw; }
    nav a { font-size:.85rem; }
    .hero { padding:44px 5vw 40px; }
    .rotator { min-height:5.4rem; }
    section { padding:44px 5vw; }
    .cta { flex-direction:column; align-items:stretch; }
    #dd { position:static !important; transform:none !important; min-width:0 !important; width:100%; margin-top:10px; }
    .btn { justify-content:center; }
    footer { flex-direction:column; text-align:center; }
  }
</style></head><body>
<nav>
  <span class="logo">SoNex</span>
  <a href="#features">Features</a><a href="#how">How it works</a><a href="#download" onclick="openDl(event)">Download</a><a href="#contact">Contact</a>
</nav>
<div class="hero">
  <div class="bars"><i></i><i></i><i></i><i></i><i></i></div>
  <div class="rotator" id="rotator"><span class="show">The volume that listens back.</span></div>
  <p class="sub">On-device AI · Private by default</p>
  <div class="cta" id="download" style="position:relative">
    <button class="btn primary" onclick="dd.style.display=dd.style.display==='block'?'none':'block'">
      <span id="platicon">__ANDROID__</span> Download <span style="font-size:.8em">▾</span>
    </button>
    <div id="dd" style="display:none;position:absolute;top:110%;left:50%;transform:translateX(-50%);
      background:#fff;border:1px solid var(--line);border-radius:14px;box-shadow:0 12px 40px rgba(32,33,36,.14);
      padding:10px;min-width:290px;text-align:left;z-index:5">
      <div style="font-size:.75rem;color:var(--sub);padding:4px 10px">ANDROID · v__VER__ · Android 8.0+ (API 26)</div>
      <a class="ddi" href="/download/mobile">📱 Android phone <span>arm64 APK</span></a>
      <a class="ddi" href="/download/tv">📺 Android TV <span>APK · sideload</span></a>
      <div style="font-size:.75rem;color:var(--sub);padding:8px 10px 4px;border-top:1px solid var(--line);margin-top:6px">
        APPLE · coming soon</div>
      <span class="ddi off"> iPhone <span>iOS 16+ · in review</span></span>
      <span class="ddi off">📺 Apple TV <span>tvOS 16+ · in review</span></span>
      <div style="font-size:.7rem;color:var(--sub);padding:6px 10px">Enable "install unknown apps" to sideload APKs.
      On Android TV use a file manager or "Send files to TV".</div>
    </div>
    <a class="btn web" href="/app/">✨ SoNex Web ›</a>
  </div>
</div>
<section id="features">
  <h2>Made for real living rooms</h2>
  <div class="features">
    <div class="f"><div class="em">🗣️</div><h3>Knows real speech</h3><p>On-device AI tells conversation apart from kitchen noise — audio never leaves your phone by default.</p></div>
    <div class="f"><div class="em">📺</div><h3>Every screen &amp; speaker</h3><p>TV, Bluetooth, earphones and Chromecast — each with its own rule: duck, mute, pause or boost.</p></div>
    <div class="f"><div class="em">🎙️</div><h3>Voice control</h3><p>"SoNex, lower volume" — offline, in English and Hindi, only after your explicit consent.</p></div>
    <div class="f"><div class="em">📞</div><h3>Call aware</h3><p>Phone rings? Everything ducks instantly and stays down until the call is over and the room is quiet.</p></div>
    <div class="f"><div class="em">🧠</div><h3>Learns your home</h3><p>Your corrections train a per-home model, delivered over the air. It keeps getting better.</p></div>
    <div class="f"><div class="em">🔒</div><h3>Private by default</h3><p>Every sharing option is off until you turn it on, and "delete all my data" means exactly that.</p></div>
  </div>
</section>
<section class="how" id="how">
  <h2>Up and running in two minutes</h2>
  <ol>
    <li><b>Install both apps</b> — SoNex on your phone, SoNex TV on your television.</li>
    <li><b>Pair with a 4-digit code</b> shown on the TV, over your own Wi-Fi.</li>
    <li><b>Calibrate once</b> — three quick steps teach SoNex your room.</li>
    <li><b>Talk.</b> The volume gets out of the way, then comes back on its own.</li>
  </ol>
</section>
<section class="contact" id="contact">
  <h2>Talk to us</h2>
  <form onsubmit="return sendContact(event)">
    <input name="name" placeholder="Your name" required maxlength="100">
    <input name="email" type="email" placeholder="Email" required maxlength="200">
    <textarea name="message" placeholder="How can we help?" rows="4" required maxlength="4000"></textarea>
    <button class="btn primary" type="submit">Send message</button>
    <div id="contact-status"></div>
  </form>
</section>
<footer>
  <span>A product of <a href="https://patienceai.in"><b>Patience AI</b></a></span>
  <div class="links"><a href="/terms">Terms</a><a href="/privacy">Privacy</a><a href="/changelog">Changelog</a><a href="#contact">Contact</a></div>
</footer>
<div id="consent" style="display:none;position:fixed;bottom:0;left:0;right:0;z-index:50;
  background:#fff;border-top:1px solid var(--line);box-shadow:0 -4px 24px rgba(32,33,36,.08);
  padding:14px 6vw;display:none;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between">
  <span style="font-size:.9rem;color:var(--sub)">We use one strictly-necessary cookie for admin sign-in and
  local storage to remember this choice — no tracking, no ads, no analytics.
  <a href="/privacy" style="color:var(--violet)">Privacy policy</a></span>
  <button class="btn primary" style="padding:9px 22px" onclick="acceptConsent()">Got it</button>
</div>
<script>
if(!localStorage.getItem('sonex-consent')){document.getElementById('consent').style.display='flex';}
function acceptConsent(){localStorage.setItem('sonex-consent','1');document.getElementById('consent').style.display='none';}
const LINES = [
  "The volume that listens back.",
  "Someone talks. TV goes quiet.",
  "Room settles. Volume returns.",
  "Blender roars. SoNex turns it up.",
  "No remotes. No shushing."
];
const APPLE='<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M17.05 20.28c-.98.95-2.05.8-3.08.35-1.09-.46-2.09-.48-3.24 0-1.44.62-2.2.44-3.06-.35C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.53 4.08zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z"/></svg>';
const AND=document.getElementById('platicon').innerHTML;
let flip=false; setInterval(()=>{flip=!flip;document.getElementById('platicon').innerHTML=flip?APPLE:AND;},2600);
function openDl(e){
  e.preventDefault(); e.stopPropagation();
  document.getElementById('download').scrollIntoView({behavior:'smooth',block:'center'});
  setTimeout(()=>{dd.style.display='block';},400);
}
document.addEventListener('click',e=>{if(!e.target.closest('#download'))dd.style.display='none';});
(function(){
  const box=document.getElementById('rotator'), el=box.querySelector('span');
  let i=0, paused=false;
  box.addEventListener('mouseenter',()=>paused=true);
  box.addEventListener('mouseleave',()=>paused=false);
  setInterval(()=>{
    if(paused) return;
    el.classList.remove('show');
    setTimeout(()=>{ i=(i+1)%LINES.length; el.textContent=LINES[i]; el.classList.add('show'); }, 900);
  }, 4500);
})();
async function sendContact(e){
  e.preventDefault();
  const f=e.target, s=document.getElementById('contact-status');
  s.textContent='Sending…';
  try{
    const r=await fetch('/v1/contact',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({name:f.name.value,email:f.email.value,message:f.message.value})});
    s.textContent=r.ok?'Thanks! We\\'ll get back to you soon. ✓':'Something went wrong — email info@patienceai.in';
    if(r.ok)f.reset();
  }catch(_){s.textContent='Network error — try again';}
  return false;
}
</script>
</body></html>""".replace("__ANDROID__", ANDROID_SVG)


def _legal_page(title: str, body: str) -> str:
    return f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{title} — SoNex</title>
<style>body{{font-family:system-ui,sans-serif;color:#202124;max-width:760px;margin:0 auto;padding:48px 24px;line-height:1.7}}
h1{{color:#7C4DFF}} a{{color:#7C4DFF}} h2{{margin-top:28px;font-size:1.15rem}}</style></head>
<body><h1>{title}</h1>{body}
<p style="margin-top:40px;color:#5f6368">A product of <a href="https://patienceai.in"><b>Patience AI</b></a></p>
</body></html>"""


TERMS_BODY = """
<p>By using SoNex you agree to these terms. SoNex is provided as-is, without warranty;
it adjusts media volume on devices you own and pair yourself.</p>
<h2>Your account</h2><p>You are responsible for keeping your password safe. We may suspend
accounts used to abuse the service.</p>
<h2>Acceptable use</h2><p>Don't attempt to access other users' data, disrupt the service,
or reverse the pairing protocol against devices you don't own.</p>
<h2>Changes</h2><p>We may update these terms; continued use after changes means acceptance.
Questions: <a href="mailto:info@patienceai.in">info@patienceai.in</a>.</p>"""

PRIVACY_BODY = """
<p>SoNex is private by default: all audio is processed on your phone and immediately
discarded. Nothing leaves your device unless you switch on a specific, revocable consent.</p>
<h2>What we store</h2><p>With an account: your email and a salted password hash. With consents
enabled: detection events, volume corrections, and (only with the upload toggle) short audio clips.</p>
<h2>Your rights</h2><p>Export everything we hold or delete it — in the app
(Settings → Delete all my data) or via the API. Deletion removes database rows and stored files.</p>
<h2>Email</h2><p>We send one-time verification codes and password-reset codes via Brevo.
No marketing email without separate opt-in.</p>
<h2>Contact</h2><p><a href="mailto:info@patienceai.in">info@patienceai.in</a> ·
grievance officer to be announced (DPDP 2023).</p>"""
@router.get("/", response_class=HTMLResponse)
async def landing(request: Request):
    gsv = (f'<meta name="google-site-verification" content="{settings.google_site_verification}">'
           if settings.google_site_verification else "")
    ver = ""
    try:
        ver = _releases().get("mobile", {}).get("version_name", "")
    except Exception:
        pass
    return (LANDING.replace("__GSV__", gsv).replace("__BASE__", _base(request))
            .replace("__VER__", ver or "latest"))


@router.get("/robots.txt", response_class=PlainTextResponse)
async def robots(request: Request):
    return f"User-agent: *\nAllow: /\nSitemap: {_base(request)}/sitemap.xml\n"


@router.get("/sitemap.xml")
async def sitemap(request: Request):
    from datetime import date

    base = _base(request)
    today = date.today().isoformat()
    pages = [("/", "weekly", "1.0"), ("/terms", "yearly", "0.3"), ("/privacy", "yearly", "0.3")]
    urls = "".join(
        f"<url><loc>{base}{path}</loc><lastmod>{today}</lastmod>"
        f"<changefreq>{freq}</changefreq><priority>{prio}</priority></url>"
        for path, freq, prio in pages
    )
    xml = ('<?xml version="1.0" encoding="UTF-8"?>\n'
           f'<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{urls}</urlset>')
    return Response(content=xml, media_type="application/xml", headers={"Cache-Control": "max-age=3600"})


CHANGELOG = [
    ("2.5", "The home animation now dances all the time - soft grey while resting, glowing with the room's mood while listening."),
    ("2.4", "One login, everywhere: change a setting on your phone and SoNex Web follows within seconds - and vice versa. The web now shows the app's live animation, and browser back works naturally."),
    ("2.3", "Pair your TV straight from SoNex Web and control it from any network — no app needed. Smarter theme button too."),
    ("2.2", "Meet SoNex Web — SoNex in your browser, installable as an app: live listening on whatever device you open it on, per-source rules for Bluetooth, Cast and TV, dark mode, and the same look you know. Plus a brand-new live animation on the phone home screen."),
    ("2.1", "Sharper focus: SoNex now puts everything into flawless automatic volume — leaner, smaller and noticeably snappier. Cleaner home screen too."),
    ("2.0", "Voice volume now shows the on-screen slider gliding on phone and TV. Voice reacts in a blink. Clearer status when the mic is off."),
    ("1.9", "TV pairing fix. TV app is now light-themed. Friendlier error messages. What's-new page (you're reading it)."),
    ("1.8", "Say amounts: \u201cSoNex, increase volume by 20\u201d. Wake-word on/off switch. Privacy policy opens inside the app. Smoother, minimal status animation. Smaller, faster app."),
    ("1.7", "One tidy Calibrate button. Instant reactions on every speaker at once."),
    ("1.6", "Whisper-aware: whispering never lowers your volume. Volume now fades gently instead of jumping. Big reliability fixes for detection."),
    ("1.5", "Update checker built in. Pick your room size instead of measuring. Fresh look for the logo."),
    ("1.4", "Light theme by default. Start/Stop control. Sign-out stops the mic."),
    ("1.3", "Email code verification at sign-up. Forgot/change password. In-app updates."),
    ("1.2", "Faster, safer sign-in. New landing page."),
    ("1.1", "Log out, themes, feedback button, earphone support."),
    ("1.0", "First release: TV pairing, room calibration, automatic volume."),
]


@router.get("/changelog", response_class=HTMLResponse)
async def changelog():
    items = "".join(
        f'<div class="cl-item"><h2>v{v}</h2><p>{note}</p></div>' for v, note in CHANGELOG
    )
    pager = """
<div id="pager" style="display:flex;gap:12px;align-items:center;margin-top:26px">
 <button id="prev" style="padding:9px 18px;border-radius:9px;border:1.5px solid #7C4DFF;background:#fff;color:#7C4DFF;cursor:pointer">← Newer</button>
 <span id="pinfo" style="color:#5f6368;font-size:.9rem"></span>
 <button id="next" style="padding:9px 18px;border-radius:9px;border:1.5px solid #7C4DFF;background:#fff;color:#7C4DFF;cursor:pointer">Older →</button>
</div>
<script>
const PER=5,items=[...document.querySelectorAll('.cl-item')];let page=0;
const pages=Math.max(1,Math.ceil(items.length/PER));
function render(){
 items.forEach((el,i)=>el.style.display=(i>=page*PER&&i<(page+1)*PER)?'block':'none');
 document.getElementById('pinfo').textContent=`Page ${page+1} of ${pages}`;
 document.getElementById('prev').style.visibility=page>0?'visible':'hidden';
 document.getElementById('next').style.visibility=page<pages-1?'visible':'hidden';
 window.scrollTo({top:0,behavior:'smooth'})}
document.getElementById('prev').onclick=()=>{page--;render()};
document.getElementById('next').onclick=()=>{page++;render()};
render();
</script>"""
    return _legal_page("What's new", items + pager)


@router.get("/terms", response_class=HTMLResponse)
async def terms():
    return _legal_page("Terms of Service", TERMS_BODY)


@router.get("/privacy", response_class=HTMLResponse)
async def privacy():
    return _legal_page("Privacy Policy", PRIVACY_BODY)


class ContactIn(BaseModel):
    name: str
    email: str
    message: str


@router.post("/v1/contact", status_code=202)
async def contact(body: ContactIn):
    if not body.message.strip() or len(body.message) > 4000:
        raise HTTPException(status_code=422, detail="Message is empty or too long")
    html = (f"<p><b>From:</b> {body.name} &lt;{body.email}&gt;</p>"
            f"<p style='white-space:pre-wrap'>{body.message}</p>")
    sent = await send_email(settings.contact_to_email, f"SoNex contact: {body.name}", html)
    if not sent:
        raise HTTPException(status_code=502, detail="Couldn't send right now — email info@patienceai.in")
    return {"detail": "Thanks! We'll get back to you."}


class FeedbackIn(BaseModel):
    email: str = ""
    message: str
    diagnostics: dict | None = None


@router.post("/v1/feedback", status_code=202)
async def feedback(body: FeedbackIn):
    """In-app feedback -> growth team, with optional diagnostics the user
    chose to attach (app version, calibration, consents — never audio)."""
    if not body.message.strip() or len(body.message) > 4000:
        raise HTTPException(status_code=422, detail="Message is empty or too long")
    diag = ""
    if body.diagnostics:
        rows = "".join(f"<tr><td><b>{k}</b></td><td>{v}</td></tr>" for k, v in body.diagnostics.items())
        diag = f"<h3>Diagnostics</h3><table border='1' cellpadding='6'>{rows}</table>"
    html = (f"<p><b>From:</b> {body.email or 'anonymous'}</p>"
            f"<p style='white-space:pre-wrap'>{body.message}</p>{diag}")
    sent = await send_email(settings.feedback_to_email, "SoNex app feedback", html)
    if not sent:
        raise HTTPException(status_code=502, detail="Couldn't send right now — try again later")
    return {"detail": "Thanks for the feedback!"}
