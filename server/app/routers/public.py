"""Public, unauthenticated routes: landing page, app releases, APK downloads.

APKs live in object storage under apk/ next to a releases.json:
  {"mobile": {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-mobile.apk"},
   "tv":     {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-tv.apk"}}
Publishing a new build = upload new APK + bump releases.json. No redeploy.
"""
import json

from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse, PlainTextResponse, RedirectResponse, Response
from pydantic import BaseModel

from ..config import settings
from ..emailer import send_email
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


ANDROID_SVG = """<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M17.6 9.48l1.84-3.18c.16-.31.04-.7-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24c-2.86-1.21-6.08-1.21-8.94 0L5.65 5.67c-.19-.29-.58-.38-.87-.2-.28.18-.37.54-.22.83L6.4 9.48C3.3 11.25 1.28 14.44 1 18h22c-.28-3.56-2.3-6.75-5.4-8.52zM7 15.25c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25zm10 0c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25z"/></svg>"""

LANDING = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>SoNex — volume that listens to the room</title>
<meta name="description" content="SoNex automatically lowers your TV and phone volume when someone talks, and restores it when the room is quiet.">
__GSV__
<link rel="canonical" href="https://sonexus.onrender.com/">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='7' fill='%230E0B1A'/%3E%3Crect x='6' y='13' width='3.5' height='8' rx='1.7' fill='%237C4DFF'/%3E%3Crect x='11.5' y='9' width='3.5' height='16' rx='1.7' fill='%237C4DFF'/%3E%3Crect x='17' y='5' width='3.5' height='24' rx='1.7' fill='%232DD4BF'/%3E%3Crect x='22.5' y='11' width='3.5' height='12' rx='1.7' fill='%237C4DFF'/%3E%3C/svg%3E">
<meta property="og:title" content="SoNex — volume that listens to the room">
<meta property="og:description" content="Someone talks, your TV gets quiet. Room settles, volume comes back. On-device AI, private by default.">
<meta property="og:type" content="website">
<meta property="og:url" content="https://sonexus.onrender.com/">
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
  nav .logo { font-weight:900; font-size:1.5rem; letter-spacing:-.5px; margin-right:auto;
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
    .btn { justify-content:center; }
    footer { flex-direction:column; text-align:center; }
  }
</style></head><body>
<nav>
  <span class="logo">SoNex</span>
  <a href="#features">Features</a><a href="#how">How it works</a><a href="#download">Download</a><a href="#contact">Contact</a>
</nav>
<div class="hero">
  <div class="bars"><i></i><i></i><i></i><i></i><i></i></div>
  <div class="rotator" id="rotator"><span class="show">The volume that listens back.</span></div>
  <p class="sub">On-device AI · Private by default</p>
  <div class="cta" id="download">
    <a class="btn primary" href="/download/mobile">__ANDROID__ Download for Android</a>
    <a class="btn secondary" href="/download/tv">__ANDROID__ Get the TV app</a>
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
  <div class="links"><a href="/terms">Terms</a><a href="/privacy">Privacy</a><a href="#contact">Contact</a></div>
</footer>
<script>
const LINES = [
  "The volume that listens back.",
  "Someone talks. TV goes quiet.",
  "Room settles. Volume returns.",
  "Blender roars. SoNex turns it up.",
  "\u201CSoNex, awaaz kam karo.\u201D",
  "No remotes. No shushing."
];
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
async def landing():
    gsv = (f'<meta name="google-site-verification" content="{settings.google_site_verification}">'
           if settings.google_site_verification else "")
    return LANDING.replace("__GSV__", gsv)


@router.get("/robots.txt", response_class=PlainTextResponse)
async def robots():
    return "User-agent: *\nAllow: /\nSitemap: https://sonexus.onrender.com/sitemap.xml\n"


@router.get("/sitemap.xml")
async def sitemap():
    from datetime import date

    base = "https://sonexus.onrender.com"
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
