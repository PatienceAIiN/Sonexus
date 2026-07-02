"""Public, unauthenticated routes: landing page, app releases, APK downloads.

APKs live in object storage under apk/ next to a releases.json:
  {"mobile": {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-mobile.apk"},
   "tv":     {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-tv.apk"}}
Publishing a new build = upload new APK + bump releases.json. No redeploy.
"""
import json

from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse, RedirectResponse
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
<style>
  :root { --violet:#7C4DFF; --teal:#0d9488; --ink:#202124; --sub:#5f6368; --line:#e8eaed; }
  * { margin:0; box-sizing:border-box; }
  html { scroll-behavior:smooth; }
  body { font-family:'Google Sans',system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;
         color:var(--ink); background:#fff; line-height:1.6; }
  nav { position:sticky; top:0; z-index:10; display:flex; align-items:center; gap:28px;
        padding:14px 6vw; background:rgba(255,255,255,.92); backdrop-filter:blur(10px);
        border-bottom:1px solid var(--line); }
  nav .logo { font-weight:800; font-size:1.25rem; color:var(--violet); margin-right:auto; }
  nav a { color:var(--sub); text-decoration:none; font-size:.95rem; }
  nav a:hover { color:var(--ink); }
  .hero { text-align:center; padding:90px 6vw 60px; }
  .hero h1 { font-size:clamp(2.4rem,6vw,4rem); font-weight:700; letter-spacing:-1px; }
  .hero h1 span { color:var(--violet); }
  .hero p { max-width:620px; margin:20px auto 0; font-size:1.2rem; color:var(--sub); }
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
</style></head><body>
<nav>
  <span class="logo">SoNex</span>
  <a href="#features">Features</a><a href="#how">How it works</a><a href="#download">Download</a><a href="#contact">Contact</a>
</nav>
<div class="hero">
  <div class="bars"><i></i><i></i><i></i><i></i><i></i></div>
  <h1>The volume that <span>listens back</span>.</h1>
  <p>When someone starts talking, SoNex quietly turns your TV and phone down.
     When the room settles, your volume comes right back. No remotes, no shushing.</p>
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
    return LANDING


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
