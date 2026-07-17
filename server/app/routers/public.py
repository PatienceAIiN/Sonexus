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
    request actually arrived on (custom domain aware via proxy header)."""
    if settings.site_url:
        return settings.site_url.rstrip("/")
    host = request.headers.get("x-forwarded-host") or request.headers.get("host") or request.url.hostname or "sonex.patienceai.in"
    if not host or "127.0.0.1" in host or "localhost" in host:
        return "https://sonex.patienceai.in"
    scheme = request.headers.get("x-forwarded-proto", "https")
    return f"{scheme}://{host}"


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
# Small inline logos for the download menu items (Android green, Apple grey).
ANDROID_MARK = """<svg width="18" height="18" viewBox="0 0 24 24" fill="#3ddc84" style="vertical-align:-3px;margin-right:6px" aria-hidden="true"><path d="M17.6 9.48l1.84-3.18c.16-.31.04-.7-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24c-2.86-1.21-6.08-1.21-8.94 0L5.65 5.67c-.19-.29-.58-.38-.87-.2-.28.18-.37.54-.22.83L6.4 9.48C3.3 11.25 1.28 14.44 1 18h22c-.28-3.56-2.3-6.75-5.4-8.52zM7 15.25c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25zm10 0c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25z"/></svg>"""
APPLE_MARK = """<svg width="17" height="17" viewBox="0 0 24 24" fill="#8a8f98" style="vertical-align:-3px;margin-right:6px" aria-hidden="true"><path d="M17.05 20.28c-.98.95-2.05.8-3.08.35-1.09-.46-2.09-.48-3.24 0-1.44.62-2.2.44-3.06-.35C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.53 4.08zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z"/></svg>"""

LANDING = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>SoNex — Volume that listens to the room</title>
<meta name="description" content="SoNex automatically lowers your TV and phone volume when someone talks, and restores it when the room is quiet.">
__GSV__
<link rel="canonical" href="__BASE__/">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='7' fill='%23050505'/%3E%3Crect x='6' y='13' width='3.5' height='8' rx='1.7' fill='%23FFFFFF'/%3E%3Crect x='11.5' y='9' width='3.5' height='16' rx='1.7' fill='%23FFFFFF'/%3E%3Crect x='17' y='5' width='3.5' height='24' rx='1.7' fill='%23FFFFFF'/%3E%3Crect x='22.5' y='11' width='3.5' height='12' rx='1.7' fill='%23FFFFFF'/%3E%3C/svg%3E">
<meta property="og:title" content="SoNex — Volume that listens to the room">
<meta property="og:description" content="Someone talks, your TV gets quiet. Room settles, volume comes back. On-device AI, private by default.">
<meta property="og:type" content="website">
<meta property="og:url" content="__BASE__/">
<meta name="twitter:card" content="summary_large_image">
<meta name="robots" content="index,follow">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet">
<style>
  :root { --bg: #000000; --ink: #ffffff; --sub: #888888; --line: rgba(255,255,255,0.12); --accent: #ffffff; }
  * { margin:0; padding:0; box-sizing:border-box; }
  html { scroll-behavior:smooth; }
  body { font-family: 'Inter', system-ui, sans-serif; background:var(--bg); color:var(--ink); line-height:1.6; font-size:16px; overflow-x:hidden; }
  
  ::selection { background: var(--accent); color: var(--bg); }
  
  /* Navbar */
  nav { position:fixed; top:0; width:100%; z-index:100; display:flex; align-items:center; justify-content:space-between; padding:20px 5vw; background:rgba(0,0,0,0.85); backdrop-filter:blur(12px); border-bottom:1px solid var(--line); }
  nav .logo { font-weight:800; font-size:1.75rem; letter-spacing:-0.8px; color:#ffffff; text-shadow:0 0 16px rgba(255,255,255,0.4), 0 0 30px rgba(255,255,255,0.15); text-decoration:none; display:flex; align-items:center; gap:10px; transition:text-shadow 0.3s ease; }
  nav .logo:hover { text-shadow:0 0 25px rgba(255,255,255,0.7), 0 0 45px rgba(255,255,255,0.3); }
  nav .logo span { font-weight:400; font-size:0.85rem; color:var(--sub); letter-spacing:0.5px; text-shadow:none; background:rgba(255,255,255,0.06); padding:3px 10px; border-radius:99px; border:1px solid var(--line); font-family:monospace; text-transform:uppercase; }
  nav .links { display:flex; gap:32px; }
  nav a { color:var(--sub); text-decoration:none; font-size:0.95rem; font-weight:500; transition:color 0.2s; }
  nav a:hover { color:var(--ink); }
  
  /* Hero */
  .hero { min-height:100vh; display:flex; flex-direction:column; justify-content:center; padding:120px 5vw 80px; position:relative; border-bottom:1px solid var(--line); }
  @keyframes fadeInTag { 0% { opacity:0; transform:translateY(-8px); } 100% { opacity:1; transform:translateY(0); } }
  .hero-tag { font-family:monospace; font-size:0.85rem; letter-spacing:1px; text-transform:uppercase; color:var(--sub); margin-left:-12px; margin-bottom:24px; border:1px solid var(--line); padding:6px 12px; border-radius:99px; display:inline-block; animation:fadeInTag 1s cubic-bezier(0.16, 1, 0.3, 1) forwards; }
  .rotator { min-height: 80px; height: auto; display: flex; align-items: flex-start; position: relative; margin-bottom: 28px; }
  .rotator > span { font-size: clamp(2.8rem, 6.5vw, 4.5rem); font-weight: 700; letter-spacing: -2px; line-height: 1.1; color: var(--ink); display: block; width: 100%; transition: opacity 0.4s ease, transform 0.4s cubic-bezier(0.16, 1, 0.3, 1); }
  .rotator > span.fade-out { opacity: 0; transform: translateY(6px); }
  .cursor { display: inline-block; width: 5px; height: 0.85em; background-color: var(--accent); vertical-align: baseline; margin-left: 4px; animation: blink 1s step-end infinite; }
  @keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
  @media (max-width: 768px) { .rotator { min-height: 60px; } }
  .hero .sub { font-size:clamp(1.1rem, 2vw, 1.4rem); color:var(--sub); max-width:640px; margin-bottom:48px; font-weight:400; line-height:1.5; }
  
  .cta-group { display:flex; gap:16px; align-items:center; flex-wrap:wrap; position:relative;}
  .btn { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:16px 32px; font-weight:600; font-size:1rem; text-decoration:none; transition:all 0.3s ease; border-radius:4px; cursor:pointer; }
  .btn.primary { background:var(--accent); color:var(--bg); border:1px solid var(--accent); }
  .btn.primary:hover { background:transparent; color:var(--accent); }
  
  /* Dropdown */
  #dd { display:none; position:absolute; top:calc(100% + 12px); left:0; background:#0a0a0a; border:1px solid var(--line); border-radius:8px; padding:8px; min-width:320px; z-index:50; box-shadow:0 24px 60px rgba(0,0,0,0.8); }
  .dd-header { font-size:0.75rem; color:var(--sub); padding:8px 12px; letter-spacing:1px; text-transform:uppercase; font-family:monospace; }
  .ddi { display:flex; justify-content:space-between; align-items:center; padding:12px; border-radius:6px; color:var(--ink); text-decoration:none; font-size:0.95rem; font-weight:500; transition:background 0.2s; }
  .ddi:hover { background:rgba(255,255,255,0.08); }
  .ddi span.tag { font-size:0.75rem; color:var(--sub); font-family:monospace; background:rgba(255,255,255,0.1); padding:4px 8px; border-radius:4px; }
  .ddi.off { opacity:0.5; pointer-events:none; }
  .dd-note { font-size:0.8rem; color:var(--sub); padding:12px; border-top:1px solid var(--line); margin-top:8px; line-height:1.4; }
  
  /* Sections Focus & Scroll */
  section { scroll-margin-top: 80px; position:relative; }
  .focus-section { animation: sectionFocusGlow 2s cubic-bezier(0.16, 1, 0.3, 1) forwards; }
  @keyframes sectionFocusGlow {
    0% { box-shadow: 0 0 0 2px rgba(255,255,255,0.8), 0 0 40px rgba(255,255,255,0.3); }
    50% { box-shadow: 0 0 0 2px rgba(255,255,255,0.9), 0 0 60px rgba(255,255,255,0.4); }
    100% { box-shadow: 0 0 0 0px transparent, 0 0 0px transparent; }
  }
  /* Sections */
  section { padding:140px 5vw; border-bottom:1px solid var(--line); }
  .section-header { margin-bottom:80px; }
  .section-tag { font-family:monospace; font-size:0.85rem; color:var(--sub); text-transform:uppercase; letter-spacing:1px; margin-bottom:20px; display:block; }
  section h2 { font-size:clamp(3rem, 6vw, 4.5rem); font-weight:700; letter-spacing:-2px; line-height:1.05; }
  
  /* Features Grid */
  .features { display:grid; grid-template-columns:repeat(auto-fit, minmax(320px, 1fr)); gap:1px; background:var(--line); border:1px solid var(--line); }
  .f { background:var(--bg); padding:64px 48px; transition:background 0.3s; }
  .f:hover { background:#080808; }
  .f .em { font-size:3rem; margin-bottom:32px; display:inline-block; font-weight:300; }
  .f h3 { font-size:1.5rem; margin-bottom:16px; font-weight:600; letter-spacing:-0.5px; }
  .f p { color:var(--sub); font-size:1.05rem; line-height:1.6; }
  
  /* How it works */
  .how-grid { display:grid; grid-template-columns:1fr 1fr; gap:80px; }
  .step { padding:40px 0; border-bottom:1px solid var(--line); }
  .step:last-child { border-bottom:none; }
  .step-num { font-family:monospace; color:var(--sub); font-size:0.9rem; margin-bottom:16px; display:block; letter-spacing:1px; }
  .step h3 { font-size:1.8rem; margin-bottom:16px; letter-spacing:-0.5px; font-weight:600;}
  .step p { color:var(--sub); line-height:1.6; font-size:1.05rem; }
  
  /* Theme Switcher Button */
  .theme-btn {
    background: rgba(255,255,255,0.06);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 6px 12px;
    border-radius: 99px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 0.82rem;
    font-family: inherit;
    font-weight: 500;
    transition: all 0.3s ease;
  }
  .theme-btn:hover {
    background: rgba(255,255,255,0.12);
    transform: scale(1.04);
  }
  .theme-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: transform 0.4s cubic-bezier(0.16, 1, 0.3, 1);
  }
  .theme-btn:active .theme-icon {
    transform: rotate(180deg) scale(0.85);
  }
  
  /* Light Theme Variables */
  body { transition: background 0.4s ease, color 0.4s ease; }
  body.light-mode {
    --bg: #f8f9fa;
    --ink: #111827;
    --sub: #4b5563;
    --line: #e5e7eb;
    --accent: #111827;
  }
  body.light-mode nav {
    background: rgba(248,249,250,0.85);
  }
  body.light-mode .cm-box, body.light-mode #consent {
    background: #ffffff;
    box-shadow: 0 20px 60px rgba(0,0,0,0.1);
  }
  body.light-mode section#how {
    background: #f1f3f5 !important;
  }
  body.light-mode footer {
    background: #f1f3f5 !important;
  }
  body.light-mode nav .logo {
    color: #111827;
    text-shadow: 0 0 16px rgba(0,0,0,0.15);
  }
  body.light-mode nav .logo span {
    background: rgba(0,0,0,0.06);
    color: #4b5563;
  }
  body.light-mode .theme-btn {
    background: rgba(0,0,0,0.06);
    border-color: var(--line);
    color: var(--ink);
  }
  body.light-mode .theme-btn:hover {
    background: rgba(0,0,0,0.1);
  }
  body.light-mode .f { background:#ffffff; border-color:#e5e7eb; }
  body.light-mode .f:hover { background:#f1f3f5; border-color:#9ca3af; box-shadow:0 10px 30px rgba(0,0,0,0.06); }
  body.light-mode .f h3 { color:#111827; }
  body.light-mode .f p { color:#4b5563; }
  body.light-mode .f .em { color:#111827; }
  body.light-mode .step { background:transparent; border-color:#e5e7eb; }
  body.light-mode .step:hover { background:rgba(0,0,0,0.02); border-color:#9ca3af; }
  body.light-mode .step h3 { color:#111827; }
  body.light-mode .step p { color:#4b5563; }
  body.light-mode .btn-primary { background:#111827; color:#ffffff; }
  body.light-mode .btn-primary:hover { background:#1f2937; }
  body.light-mode .btn-secondary { background:#ffffff; color:#111827; border-color:#e5e7eb; }
  body.light-mode .btn-secondary:hover { background:#f3f4f6; border-color:#d1d5db; color:#111827; }
  body.light-mode .dd-header { color:#111827; }
  body.light-mode .ddi { background:#ffffff; border-color:#e5e7eb; }
  body.light-mode .ddi:hover { background:#f3f4f6; border-color:#d1d5db; }
  body.light-mode .ddi h4 { color:#111827; }
  body.light-mode .ddi p { color:#4b5563; }
  body.light-mode nav a { color:#4b5563; }
  body.light-mode nav a:hover { color:#111827; }
  body.light-mode footer a { color:#4b5563; }
  body.light-mode footer a:hover { color:#111827; }

  /* Footer */
  footer { padding:80px 5vw; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:32px; font-size:0.95rem; color:var(--sub); background:#050505;}
  footer a { color:var(--sub); text-decoration:none; transition:color 0.2s; }
  footer a:hover { color:var(--ink); }
  .footer-links { display:flex; gap:32px; font-weight:500; }
  
  /* Modals */
  #contactModal, #downloadModal { display:none; position:fixed; inset:0; z-index:200; background:rgba(0,0,0,0.8); backdrop-filter:blur(10px); align-items:center; justify-content:center; padding:5vw; }
  #contactModal { display:none; position:fixed; inset:0; z-index:200; background:rgba(0,0,0,0.8); backdrop-filter:blur(10px); align-items:center; justify-content:center; padding:5vw; }
  .cm-box { background:#080808; border:1px solid var(--line); border-radius:8px; max-width:500px; width:100%; max-height:90vh; overflow-y:auto; padding:32px 24px; box-shadow:0 32px 80px rgba(0,0,0,0.8); }
  .cm-head { display:flex; align-items:center; justify-content:space-between; margin-bottom:16px; }
  .cm-head h2 { font-size:2rem; letter-spacing:-1px; font-weight:600; }
  .cm-sub { color:var(--sub); font-size:1rem; margin-bottom:32px; }
  .cm-x { background:transparent; border:none; color:var(--sub); font-size:2rem; cursor:pointer; line-height:1; }
  .cm-x:hover { color:var(--ink); }
  .cm-in { width:100%; padding:16px; margin-bottom:16px; background:transparent; border:1px solid var(--line); color:var(--ink); font-family:inherit; font-size:1rem; border-radius:4px; outline:none; transition:border-color 0.3s; }
  .cm-in:focus { border-color:var(--accent); }
  textarea.cm-in { min-height:120px; resize:vertical; }
  .cm-send { width:100%; padding:16px; background:var(--accent); color:var(--bg); font-weight:600; font-size:1rem; border:none; border-radius:4px; cursor:pointer; transition:opacity 0.3s; }
  .cm-send:hover { opacity:0.9; }
  #contact-status { text-align:center; margin-top:16px; font-size:0.9rem; color:var(--sub); }
  
  /* Consent */
  #consent { display:none; position:fixed; bottom:24px; left:5vw; right:5vw; max-width:1200px; margin:0 auto; z-index:150; background:#0a0a0a; border:1px solid var(--line); padding:24px 32px; border-radius:8px; flex-wrap:wrap; gap:24px; align-items:center; justify-content:space-between; box-shadow:0 24px 80px rgba(0,0,0,0.8); }
  #consent p { font-size:0.9rem; color:var(--sub); max-width:800px; line-height:1.6; }
  #consent a { color:var(--ink); text-decoration:underline; }
  
  .menu-toggle {
    display: none;
    background: transparent;
    border: none;
    color: var(--ink);
    cursor: pointer;
    padding: 8px;
    margin-right: -8px;
    z-index: 101;
  }

  @media (max-width: 768px) {
    nav { padding: 14px 5vw; position: fixed; top: 0; left: 0; right: 0; z-index: 100; backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); justify-content: space-between; }
    nav .logo { font-size: 1.3rem; }
    nav .logo span { font-size: 0.7rem; padding: 2px 6px; }
    .menu-toggle { display: flex; align-items: center; justify-content: center; }
    nav .links {
      display: none;
      position: absolute;
      top: 100%;
      left: 0;
      right: 0;
      background: var(--bg);
      border-bottom: 1px solid var(--line);
      flex-direction: column;
      padding: 18px 5vw;
      gap: 16px;
      box-shadow: 0 20px 40px rgba(0,0,0,0.5);
    }
    nav.open .links { display: flex; }
    .hero { padding: 110px 5vw 60px; }
    .hero-tag { margin-left: 0; font-size: 0.75rem; }
    .rotator { min-height: 70px; margin-bottom: 16px; }
    .rotator > span { font-size: clamp(1.8rem, 7.5vw, 2.8rem); }
    .hero .sub { font-size: 0.95rem; margin-bottom: 28px; line-height: 1.5; }
    section { padding: 80px 5vw; }
    .section-header { margin-bottom: 40px; }
    section h2 { font-size: clamp(2rem, 7vw, 3rem); }
    .how-grid { grid-template-columns: 1fr; gap: 20px; }
    .features { grid-template-columns: 1fr; gap: 1px; }
    .f { padding: 40px 24px; }
    footer { padding: 48px 5vw; flex-direction: column; align-items: flex-start; gap: 24px; }
    .footer-links { width: 100%; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
    .cta-group { width: 100%; }
    .cta-group .btn { width: 100%; justify-content: center; padding: 16px 24px; }
    .cm-box { width: 92vw; max-width: 440px; padding: 24px; }
  }

  .reveal { opacity:0; transform:translateY(30px); transition:all 0.8s cubic-bezier(0.16, 1, 0.3, 1); }
  .reveal.active { opacity:1; transform:translateY(0); }
  .features .f.reveal { transition-delay: 0.1s; }
  .features .f.reveal:nth-child(2) { transition-delay: 0.2s; }
  .features .f.reveal:nth-child(3) { transition-delay: 0.3s; }
  .features .f.reveal:nth-child(4) { transition-delay: 0.4s; }
  .step.reveal:nth-child(1) { transition-delay: 0.1s; }
  .step.reveal:nth-child(2) { transition-delay: 0.2s; }
</style>
</head><body>

<nav id="navbar">
  <a href="/" class="logo">SoNex <span>Audio Engine</span></a>
  <button class="menu-toggle" onclick="toggleMobileMenu()" aria-label="Toggle Navigation Menu">
    <svg class="hamburger-icon" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="12" x2="21" y2="12"></line><line x1="3" y1="6" x2="21" y2="6"></line><line x1="3" y1="18" x2="21" y2="18"></line></svg>
    <svg class="close-icon" style="display:none;" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
  </button>
  <div class="links">
    <a href="#features" onclick="closeMobileMenu(); return scrollToSection('features', event)">Features</a>
    <a href="#how" onclick="closeMobileMenu(); return scrollToSection('how', event)">Process</a>
    <a href="#" onclick="closeMobileMenu(); return openContact(event)">Inquiries</a>
  </div>
</nav>

<div class="hero">
  <div style="display:flex; align-items:center; margin-bottom:24px;"><span class="hero-tag" style="margin-bottom:0;">Intelligent Volume</span></div>
  <div class="rotator" id="rotator"><span>Volume that listens.<span class="cursor"></span></span></div>
  <p class="sub">An adaptive AI audio engine that detects human speech and instantly ducks your media. Works instantly offline; cloud-smart by default, with one-tap on-device privacy.</p>
  
  <div class="cta-group" id="download">
    <button class="btn primary" onclick="openDownloadModal(event)">
      <span id="platicon">__ANDROID__</span> Download v__VER__
    </button>
  </div>
</div>

<section id="features">
  <div class="section-header reveal">
    <span class="section-tag">Capabilities</span>
    <h2>Engineered for<br>real environments.</h2>
  </div>
  <div class="features">
    <div class="f reveal">
      <div class="em">01</div>
      <h3>Advanced Speech Recognition</h3>
      <p>Local neural networks distinguish genuine human conversation from ambient noise, ignoring fans and appliances entirely.</p>
    </div>
    <div class="f reveal">
      <div class="em">02</div>
      <h3>Multi-Target Control</h3>
      <p>Automatically duck, mute, or pause your TV, Bluetooth speakers, or Cast targets independently.</p>
    </div>
    <div class="f reveal">
      <div class="em">03</div>
      <h3>Seamless Over-The-Air</h3>
      <p>Acoustic models refine themselves silently and download in the background. Your engine improves without app updates.</p>
    </div>
    <div class="f reveal">
      <div class="em">04</div>
      <h3>Privacy you control</h3>
      <p>Cloud-smart by default so detection keeps improving — or switch to fully on-device processing in one tap. Audio used only to improve detection, then deleted.</p>
    </div>
  </div>
</section>

<section id="how" style="background:#030303;">
  <div class="section-header reveal">
    <span class="section-tag">Deployment</span>
    <h2>Integration protocol.</h2>
  </div>
  <div class="how-grid">
    <div>
      <div class="step reveal">
        <span class="step-num">STEP 01</span>
        <h3>Install the ecosystem</h3>
        <p>Deploy the SoNex client to your primary device and the companion application to your smart TV.</p>
      </div>
      <div class="step reveal">
        <span class="step-num">STEP 02</span>
        <h3>Initialize handshake</h3>
        <p>Establish a secure local connection via a simple 4-digit code displayed on the TV screen.</p>
      </div>
    </div>
    <div>
      <div class="step reveal">
        <span class="step-num">STEP 03</span>
        <h3>Acoustic calibration</h3>
        <p>Perform a one-time, three-stage room analysis to establish noise floors and trigger thresholds.</p>
      </div>
      <div class="step reveal">
        <span class="step-num">STEP 04</span>
        <h3>Autonomous operation</h3>
        <p>The system runs invisibly. When conversation begins, media yields immediately.</p>
      </div>
    </div>
  </div>
</section>

<footer>
  <div style="display:flex; align-items:center; gap:16px; flex-wrap:wrap;">
    <div>© 2026 Patience AI. All rights reserved.</div>
    <button id="themeToggle" class="theme-btn" onclick="toggleTheme()" title="Toggle Light/Dark Theme" aria-label="Toggle Theme">
      <span class="theme-icon moon-icon">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>
      </span>
      <span class="theme-icon sun-icon" style="display:none;">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>
      </span>
      <span id="themeLabel">Dark Mode</span>
    </button>
  </div>
  <div class="footer-links">
    <a href="/terms">Terms</a>
    <a href="/privacy">Privacy</a>
    <a href="/changelog">Changelog</a>
    <a href="#" onclick="return openContact(event)">Contact</a>
  </div>
</footer>

<div id="downloadModal" onclick="if(event.target===this)closeDownloadModal()">
  <div class="cm-box">
    <div class="cm-head" style="margin-bottom:24px;">
      <h2>Get SoNex.</h2>
      <button class="cm-x" onclick="closeDownloadModal()">&times;</button>
    </div>
    
    <div class="dd-header" style="display:flex; align-items:center; justify-content:space-between;">
      <span>Android Platform</span>
      <span class="tag" style="background:rgba(255,255,255,0.08); border:1px solid var(--line); font-size:0.75rem; padding:2px 8px; border-radius:99px; text-transform:none; font-weight:500; color:var(--sub);">Android 8+</span>
    </div>
    <a class="ddi" href="/download/mobile" style="margin-bottom:8px;">
      <div>
        <div style="font-weight:600;">Android Phone / Mobile</div>
        <div style="font-size:0.8rem; color:var(--sub);">Handheld devices (v5.0)</div>
      </div>
      <span class="tag">arm64 APK</span>
    </a>
    <a class="ddi" href="/download/tv" style="margin-bottom:16px;">
      <div>
        <div style="font-weight:600;">Android TV</div>
        <div style="font-size:0.8rem; color:var(--sub);">Smart TVs & Streaming Sticks (v5.0)</div>
      </div>
      <span class="tag">TV APK</span>
    </a>
    
    <div class="dd-header" style="border-top:1px solid var(--line); padding-top:16px; margin-top:16px;">Apple Ecosystem</div>
    <div class="ddi off">
      <div>
        <div style="font-weight:600;">iOS & macOS</div>
        <div style="font-size:0.8rem; color:var(--sub);">Apple Silicon & Mobile</div>
      </div>
      <span class="tag">Coming Soon</span>
    </div>
    
    <div class="dd-note" style="margin-top:16px;">Sideloading required for Android APKs. Transfer TV APK over local network using Send Files to TV.</div>
  </div>
</div>

<div id="contactModal" onclick="if(event.target===this)closeContact()">
  <div class="cm-box">
    <div class="cm-head">
      <h2>Inquiries.</h2>
      <button class="cm-x" onclick="closeContact()">&times;</button>
    </div>
    <p class="cm-sub">Direct contact for support, licensing, or feedback.</p>
    <form onsubmit="return sendContact(event)">
      <input class="cm-in" name="name" placeholder="Name" required maxlength="100">
      <input class="cm-in" name="email" type="email" placeholder="Email Address" required maxlength="200">
      <textarea class="cm-in" name="message" placeholder="Message..." required maxlength="4000"></textarea>
      <button class="cm-send" type="submit">Submit Inquiry</button>
      <div id="contact-status"></div>
    </form>
  </div>
</div>

<div id="consent">
  <p>We respect your privacy. This site uses one strictly-necessary cookie for authentication. Acoustic models run locally. If you opt-in to telemetry, audio is processed ephemerally and deleted instantly. See our <a href="/privacy">Privacy Policy</a>.</p>
  <button class="btn primary" onclick="acceptConsent()">Acknowledge</button>
</div>

<script>
if(!localStorage.getItem('sonex-consent')) { document.getElementById('consent').style.display='flex'; }
function acceptConsent() { localStorage.setItem('sonex-consent','1'); document.getElementById('consent').style.display='none'; }
function toggleTheme() {
  const isLight = document.body.classList.toggle('light-mode');
  localStorage.setItem('sonex-theme', isLight ? 'light' : 'dark');
  updateThemeUI(isLight);
}

function updateThemeUI(isLight) {
  const moon = document.querySelector('.moon-icon');
  const sun = document.querySelector('.sun-icon');
  const label = document.getElementById('themeLabel');
  if (moon && sun && label) {
    moon.style.display = isLight ? 'none' : 'inline-flex';
    sun.style.display = isLight ? 'inline-flex' : 'none';
    label.textContent = isLight ? 'Light Mode' : 'Dark Mode';
  }
}

(function(){
  const saved = localStorage.getItem('sonex-theme');
  if (saved === 'light') {
    document.body.classList.add('light-mode');
    window.addEventListener('DOMContentLoaded', () => updateThemeUI(true));
  }
})();

function scrollToSection(id, e) {
  if (e) e.preventDefault();
  const el = document.getElementById(id);
  if (!el) return false;
  el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  
  // Instantly reveal all inner steps/cards
  const reveals = el.querySelectorAll('.reveal');
  reveals.forEach(r => r.classList.add('active'));
  
  // Trigger focus glow animation
  el.classList.remove('focus-section');
  void el.offsetWidth;
  el.classList.add('focus-section');
  setTimeout(() => el.classList.remove('focus-section'), 2100);
  return false;
}

function openContact(e) { if(e) e.preventDefault(); document.getElementById('contactModal').style.display='flex'; return false; }
function closeContact() { document.getElementById('contactModal').style.display='none'; }

function toggleMobileMenu() {
  const nav = document.getElementById('navbar');
  if (!nav) return;
  const isOpen = nav.classList.toggle('open');
  const hIcon = nav.querySelector('.hamburger-icon');
  const cIcon = nav.querySelector('.close-icon');
  if (hIcon) hIcon.style.display = isOpen ? 'none' : 'inline-block';
  if (cIcon) cIcon.style.display = isOpen ? 'inline-block' : 'none';
}
function closeMobileMenu() {
  const nav = document.getElementById('navbar');
  if (nav) nav.classList.remove('open');
  const hIcon = document.querySelector('.hamburger-icon');
  const cIcon = document.querySelector('.close-icon');
  if (hIcon) hIcon.style.display = 'inline-block';
  if (cIcon) cIcon.style.display = 'none';
}


  // Stagger hero elements
  const heroEls = document.querySelectorAll('.hero > div, .hero > p');
  heroEls.forEach((el, i) => {
    el.style.opacity = '0';
    el.style.transform = 'translateY(20px)';
    el.style.transition = 'all 0.8s cubic-bezier(0.16, 1, 0.3, 1) ' + (0.1 * i) + 's';
    setTimeout(() => {
      el.style.opacity = '1';
      el.style.transform = 'translateY(0)';
    }, 100);
  });

function openDownloadModal(e) { if(e) e.preventDefault(); document.getElementById('downloadModal').style.display='flex'; return false; }
function closeDownloadModal() { document.getElementById('downloadModal').style.display='none'; }

const LINES = [
  "Volume that listens.",
  "Media fades automatically.",
  "Silence restores volume.",
  "Zero remotes needed.",
  "Private acoustic AI.",
  "Smart room audio.",
  "Fades on speech.",
  "On-device intelligence."
];
(function(){
  const el = document.querySelector('#rotator span');
  let i = 0;
  let charIdx = LINES[0].length;
  let isDeleting = true;
  
  function type() {
    const currentLine = LINES[i];
    
    if (isDeleting) {
      charIdx--;
      el.innerHTML = currentLine.substring(0, charIdx) + '<span class="cursor"></span>';
    } else {
      charIdx++;
      el.innerHTML = currentLine.substring(0, charIdx) + '<span class="cursor"></span>';
    }
    
    let typeSpeed = isDeleting ? 25 : 55;
    
    if (!isDeleting && charIdx === currentLine.length) {
      typeSpeed = 3200; // Pause at end of phrase
      isDeleting = true;
    } else if (isDeleting && charIdx === 0) {
      el.classList.add('fade-out');
      setTimeout(() => {
        i = (i + 1) % LINES.length;
        isDeleting = false;
        el.classList.remove('fade-out');
        setTimeout(type, 200);
      }, 350);
      return;
    }
    
    setTimeout(type, typeSpeed);
  }
  
  setTimeout(type, 2500);
})();

async function sendContact(e) {
  e.preventDefault();
  const f = e.target, s = document.getElementById('contact-status');
  s.textContent = 'Transmitting...';
  try {
    const r = await fetch('/v1/contact', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({name: f.name.value, email: f.email.value, message: f.message.value})
    });
    s.textContent = r.ok ? 'Message received.' : 'Transmission failed. Contact info@patienceai.in';
    if(r.ok) f.reset();
  } catch(_) { s.textContent = 'Network error.'; }
  return false;
}

function reveal() {
  const reveals = document.querySelectorAll('.reveal');
  for (let i = 0; i < reveals.length; i++) {
    const windowHeight = window.innerHeight;
    const elementTop = reveals[i].getBoundingClientRect().top;
    if (elementTop < windowHeight - 80) reveals[i].classList.add('active');
  }
}
window.addEventListener('scroll', reveal);
reveal(); // initial trigger
</script>
</body></html>""".replace("__ANDROID__", ANDROID_SVG).replace("__ANDMARK__", ANDROID_MARK).replace("__APLMARK__", APPLE_MARK)


def _legal_page(title: str, body: str) -> str:
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{title} — SoNex</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet">
<style>
:root {{
  --bg: #050505;
  --ink: #f3f3f3;
  --sub: #a0a0a0;
  --line: #222222;
  --card-bg: #080808;
}}
body {{
  font-family: 'Inter', system-ui, sans-serif;
  background: var(--bg);
  color: var(--ink);
  max-width: 760px;
  margin: 0 auto;
  padding: 64px 5vw;
  line-height: 1.7;
  font-size: 16px;
  transition: background 0.4s ease, color 0.4s ease;
}}
body.light-mode {{
  --bg: #f8f9fa;
  --ink: #111827;
  --sub: #4b5563;
  --line: #e5e7eb;
  --card-bg: #ffffff;
}}
h1 {{ font-size: 2.5rem; font-weight: 700; letter-spacing: -1px; margin-bottom: 32px; color: var(--ink); }}
h2 {{ margin-top: 36px; font-size: 1.3rem; font-weight: 600; letter-spacing: -0.5px; color: var(--ink); }}
a {{ color: var(--ink); text-underline-offset: 4px; transition: opacity 0.2s; }}
a:hover {{ opacity: 0.75; }}
p, li {{ color: var(--sub); margin-bottom: 16px; }}
ul {{ padding-left: 20px; }}
.cl-item {{ margin-bottom: 24px; padding: 24px; background: var(--card-bg); border: 1px solid var(--line); border-radius: 12px; transition: border-color 0.2s; }}
.cl-item h2 {{ margin-top: 0; margin-bottom: 12px; font-size: 1.3rem; }}
.cl-item p {{ margin-bottom: 0; font-size: 0.95rem; line-height: 1.6; color: var(--sub); }}
.footer-logo {{ margin-top: 64px; color: var(--sub); font-size: 0.9rem; }}
.footer-logo a {{ color: var(--ink); text-decoration: none; font-weight: 500; }}
.footer-logo a:hover {{ text-decoration: underline; }}
.back-link {{ color: var(--sub); text-decoration: none; font-size: 0.9rem; transition: color 0.2s; }}
.back-link:hover {{ color: var(--ink); }}
.theme-btn {{
  background: rgba(255,255,255,0.06);
  border: 1px solid var(--line);
  color: var(--ink);
  padding: 6px 12px;
  border-radius: 99px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.82rem;
  font-family: inherit;
  font-weight: 500;
  transition: all 0.3s ease;
}}
.theme-btn:hover {{ background: rgba(255,255,255,0.12); transform: scale(1.04); }}
body.light-mode .theme-btn {{ background: rgba(0,0,0,0.06); border-color: var(--line); color: var(--ink); }}
body.light-mode .theme-btn:hover {{ background: rgba(0,0,0,0.1); }}
.theme-icon {{ display: inline-flex; align-items: center; justify-content: center; transition: transform 0.4s cubic-bezier(0.16, 1, 0.3, 1); }}
.theme-btn:active .theme-icon {{ transform: rotate(180deg) scale(0.85); }}
::selection {{ background: var(--ink); color: var(--bg); }}
</style></head>
<body>
<div style="margin-bottom:24px;">
  <a href="/" class="back-link">← Back to SoNex</a>
</div>
<h1>{title}</h1>{body}
<p class="footer-logo">A product of <a href="https://patienceai.in">Patience AI</a></p>
<script>
(function(){{
  if (localStorage.getItem('sonex-theme') === 'light') {{
    document.body.classList.add('light-mode');
  }}
}})();
</script>
</body></html>"""


TERMS_BODY = """
<p>By using SoNex you agree to these terms. SoNex is provided as-is, without warranty;
it adjusts media volume on devices you own and pair yourself.</p>
<h2>Your account</h2><p>You are responsible for keeping your password safe. We may suspend
accounts used to abuse the service.</p>
<h2>Acceptable use</h2><p>Don't attempt to access other users' data, disrupt the service,
or reverse the pairing protocol against devices you don't own.</p>
<h2>Audio &amp; training data</h2><p>If — and only if — you enable "Let SoNex learn my home", short audio
clips are collected to improve detection. That audio is used only for training/improvement, is never
shared with third parties, and is deleted automatically after it has been used for training. You can turn
this off at any time. See the <a href="/privacy">Privacy Policy</a> for full detail.</p>
<h2>Changes</h2><p>We may update these terms; continued use after changes means acceptance.
Questions: <a href="mailto:info@patienceai.in">info@patienceai.in</a>.</p>"""

PRIVACY_BODY = """
<p>SoNex uses secure cloud processing by default so detection keeps improving across all your
devices out of the box. Short audio clips, detection events and volume corrections are uploaded
over HTTPS and processed on our servers. You agree to this at sign-up, and you can switch SoNex to
<b>on-device-only mode any time</b> in Settings → Privacy ("Keep my data on this device"), which
stops all uploads immediately.</p>
<h2>Audio for improvement ("Let SoNex learn my home")</h2>
<p>This is <b>on by default</b> (you agree at sign-up, and there is an in-app confirmation when you
change it). SoNex uploads short audio clips to improve the detection model. We are clear about
exactly what happens:</p>
<ul>
<li><b>Purpose only:</b> the audio is used solely to train and improve SoNex's detection. Nothing else.</li>
<li><b>Never shared:</b> it is not sold, rented, or shared with any third party.</li>
<li><b>Auto-deleted after training:</b> once a training run has used a clip, that clip is deleted
automatically — audio is not retained after it has served its single purpose.</li>
<li><b>Fully revocable:</b> turn it off any time in Settings; collection stops immediately and SoNex
switches to fully on-device processing.</li>
<li><b>Secure:</b> clips are transmitted over HTTPS and stored in access-controlled storage until deletion.</li>
</ul>
<h2>What we store</h2><p>With an account: your email and a salted password hash. With consents
enabled: detection events, volume corrections, and (only with "Let SoNex learn my home") short audio
clips that are deleted right after training.</p>
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


@router.get("/app", response_class=HTMLResponse)
@router.get("/app/", response_class=HTMLResponse)
async def app_coming_soon():
    return _legal_page("SoNex Web", "<p>SoNex Web is an upcoming feature. Stay tuned!</p>")


@router.get("/robots.txt", response_class=PlainTextResponse)
async def robots(request: Request):
    return f"User-agent: *\nAllow: /\nSitemap: {_base(request)}/sitemap.xml\n"


@router.get("/sitemap.xml")
async def sitemap(request: Request):
    from datetime import date

    base = _base(request)
    today = date.today().isoformat()
    pages = [("/", "weekly", "1.0"), ("/changelog", "monthly", "0.6"), ("/terms", "yearly", "0.3"), ("/privacy", "yearly", "0.3")]
    urls = "\n".join(
        f"  <url>\n"
        f"    <loc>{base}{path}</loc>\n"
        f"    <lastmod>{today}</lastmod>\n"
        f"    <changefreq>{freq}</changefreq>\n"
        f"    <priority>{prio}</priority>\n"
        f"  </url>"
        for path, freq, prio in pages
    )
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
        f'{urls}\n'
        '</urlset>'
    )
    return Response(content=xml, media_type="application/xml", headers={"Cache-Control": "public, max-age=3600"})


CHANGELOG = [
    ("5.1", "A fresh, modern look and smoother everything. SoNex now has a dark, animated 'studio' design across the phone and TV apps — a live waveform that moves with your room, rounded iOS-style cards and buttons, and a new app icon. Added Sign in with Google. Pairing your TV is more reliable: if your Wi-Fi blocks auto-discovery, the TV now shows its IP so you can connect directly. Plus fixes to dark-mode text and settings toggles."),
    ("5.0", "The big reliability release. Two fixes you'll feel immediately. First, the home screen now clearly shows SoNex is working: the moment you press Start it says 'Listening · mic active' with a live room-level meter that moves with the sound around you — so it never looks frozen or 'dead' again. Second, detection is genuinely reliable now: SoNex recognises a real human voice by the natural harmonic tone of speech — something a fan, cooler, airflow or hiss physically cannot fake — so it lowers your media the instant someone actually talks, raises it for machines and vehicles, and no longer shows 'Talking' when the room is just noisy. A brief stray sound can no longer get the room stuck on 'Talking' either. All verified on real devices."),
    ("4.9", "Fixed the app sometimes showing only 'Ready' and not starting on certain phones — it now falls back to a working microphone automatically. Added a microphone picker so you can choose which mic to listen with, and a dashboard card that shows how much SoNex has improved itself over time, with the models and data behind it."),
    ("4.8", "Detection now decides by the TYPE of sound — a real human voice versus a steady machine — instead of guessing from how loud it is. Talking ducks your media at any volume; a cooler, fan or motor raises it."),
    ("4.7", "Big cut in false 'Talking'. SoNex also stops fighting your own phone's audio: when you're watching reels or playing music on the same phone, it hears its own speaker and keeps the volume normal instead of ducking it."),
    ("4.6", "Fixed a running cooler being mistaken for someone talking, and made detection sharpen faster when you reopen the app. Added a plain-language note about the brief moment SoNex takes to learn your room."),
    ("4.5", "Catches soft, mild conversation and gentle gossip — not just loud talking — while removing a flicker that briefly false-triggered on the phone."),
    ("4.4", "Sharper detection on the phone using the neural voice model, which now adapts to your room's actual sound level. A one-time, clearly-shown download fetches the model the first time."),
    ("4.3", "Fixed 'detects first, then stops': during a long conversation or with a cooler running, SoNex could pick up the sound and then fall silent — it now keeps tracking the whole time."),
    ("4.2", "Stability: steadier moment-to-moment tracking of your room's sound level, and a simpler, more reliable three-way read of the room (quiet, talking, or loud)."),
    ("4.1", "Fixed SoNex Web missing your voice (stuck on 'Listening') when a mic runs quiet — common in browsers. It now automatically calibrates to your microphone's actual level within a second or two, so talking is detected and media reacts even with a loud cooler in the background, matching the phone app."),
    ("4.0", "No more false 'Whispering' from machines. A cooler or fan is breathy like a whisper, so SoNex could mistake it for someone whispering — now it also checks the natural flutter of a human voice (the rapid change between breaths and consonants) that machines don't have. So Whispering shows only for a real human whisper or murmur (one person or several), and steady machines are left as background. Fixed on both the phone app and SoNex Web."),
    ("3.9", "Fixed SoNex Web falsely showing 'Talking' when only a cooler or fan was running — it now matches the phone and only says Talking for real human speech. Reacts almost instantly and no longer sits idle in a noisy room. Two fixes: SoNex now responds about twice as fast (roughly a third of a second) so the status, ducking, muting and pausing change right as things happen; and detection adapts to your room's actual sound level, so a loud cooler or a nearby conversation is picked up straight away instead of staying on 'Listening'. Works on the phone app and SoNex Web."),
    ("3.8", "Hears you over even a very loud machine. When a cooler, fan or motor is much louder than you, its volume can hide your voice — so SoNex now also listens to how your voice 'flickers' (the rapid change between vowels and consonants), which a steady machine never does. That cue works no matter how loud the machine is, so talking is caught and media ducks, while the steady machine still gets boosted over when no one speaks. Detection accuracy climbed to about 97%, and this improvement applies to both the phone and SoNex Web automatically."),
    ("3.7", "SoNex now genuinely learns and gets better on its own — and that improvement reaches every device without any update. Detection accuracy jumped from about 84% to 95% thanks to a smarter model, and from now on it keeps improving automatically each night from a rich, balanced mix of real (opted-in) sounds — talking, discussions, murmuring, coolers, fans, machines, vehicles and more — which is deleted right after training. Crucially: a new model is only ever used if it's at least as accurate, so it never gets worse. These accuracy and smart-listening gains flow automatically to the phone AND SoNex Web with no app or site update (updates are only ever needed for new features or fixes). The admin panel now shows the live accuracy trend and improvement per run, and the Contact form is a cleaner pop-up."),
    ("3.6", "The Android app now detects just as sharply as SoNex Web. It listens to your room with the raw microphone (no automatic gain levelling), which preserves the subtle rise-and-fall that tells a talking person apart from a steady cooler or fan — so talking ducks reliably and machines boost, matching the web experience. Admins get a new 'Uploaded audio' card: open it to see consented clips awaiting training and play any of them right in the browser (they're still deleted automatically after training)."),
    ("3.5", "SoNex now improves itself automatically — with your permission. If you turn on 'Let SoNex learn my home' (a clear pop-up explains exactly what happens), SoNex sends short audio clips that are used only to improve detection, are never shared, and are deleted right after training. Training runs on its own every night at 2:00 AM IST, and improved models reach your phone automatically — no app update needed. Turn it off any time and collection stops instantly; if you never turn it on, no audio is ever collected. We've updated the Privacy Policy, Terms and cookie notice to spell all of this out, added an agreement checkbox at sign-up, moved Contact into a quick pop-up, and — importantly — fixed the web app so playing music and video stay full quality (no more phone-call sound)."),
    ("3.4", "SoNex now learns. A new lightweight detection model is trained on open speech and noise datasets plus real (opted-in) usage, then delivered to your phone automatically over the air - so telling apart a person from a loud cooler, fan or vehicle keeps getting better without any app update. The phone uses the learned model when it's available and safely falls back to the built-in detection otherwise. Admins can retrain and publish a fresh model in one click, and see exactly what data went into it."),
    ("3.3", "Smarter in noisy rooms: when someone is talking, SoNex now lowers your media even if a cooler, fan or machine is running - a conversation always wins over background noise, while steady machines and outside sounds (vehicles, etc.) still raise the volume when no one is speaking. The status now tells you exactly what SoNex did: it shows Muted, Paused, Volume raised or Volume lowered to match the action you chose for that device - instead of always saying 'lowered'. The web app is now branded 'SoNex Web' once you open it."),
    ("3.2", "Detection fix: talking is picked up reliably again on both the phone app and SoNex Web (a recent change had made higher-pitched and softer voices slip through). On the web, playing audio (YouTube, music) now stays full quality instead of dropping to phone-call sound - SoNex listens on your built-in mic so Bluetooth stays in high-quality playback. Connected earbuds and Bluetooth speakers show up the moment you open the app, before you press Start. Added a BETA badge."),
    ("3.1", "Whispering is recognised properly now - even a loud whisper close to the mic no longer shows as talking. SoNex Web detects your earbuds and headphones the instant you connect them (no refresh needed) and shows exactly which mic and speaker are in use. You can now set what each device does when someone talks - lower, mute, pause or boost - right from the web app, and your choices sync instantly across your phone, TV and web. The admin panel now shows every dataset and piece of data used to improve SoNex, with a live training feed."),
    ("3.0", "SoNex now starts on its own the moment your phone joins the same Wi-Fi as your paired TV - no tapping Start. Whisper detection is far more reliable (soft breathy voices are caught properly now), and when several people are whispering together SoNex shows 'Whispering' and eases the volume down a touch instead of holding it. On SoNex Web you can now pick which microphone and speaker to use - listen through your earbuds or headphones - and the Bluetooth status finally tells you the truth about what's connected."),
    ("2.9", "Coolers, fans and machines no longer trick SoNex into lowering your sound - steady outside noise now raises the volume to cut through it, while real talking still lowers it and whispering still holds it."),
    ("2.8", "Fully hands-free: SoNex now manages every device automatically - the manual mute buttons are gone, because you should never need them."),
    ("2.7", "Calibration got a brain upgrade: it now ignores mic warm-up noise, measures each step the smart way, and tells you if the room was too unsteady to trust."),
    ("2.6", "Room level now reads as a simple 0-100 meter - no more confusing negative numbers."),
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
 <button id="prev" class="theme-btn" style="padding:8px 16px;">← Newer</button>
 <span id="pinfo" style="color:var(--sub);font-size:.9rem"></span>
 <button id="next" class="theme-btn" style="padding:8px 16px;">Older →</button>
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
