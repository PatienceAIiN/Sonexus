"""Transactional email via the Brevo HTTP API. All sends are best-effort and
never raise into request handlers; a False return means "couldn't send"."""
import logging

import httpx

from .config import settings

log = logging.getLogger("sonex.email")

BREVO_URL = "https://api.brevo.com/v3/smtp/email"


async def send_email(to: str, subject: str, html: str) -> bool:
    if not settings.brevo_api_key:
        log.warning("BREVO_API_KEY not set — email to %s not sent (%s)", to, subject)
        return False
    payload = {
        "sender": {"name": settings.brevo_sender_name, "email": settings.brevo_sender_email},
        "to": [{"email": to}],
        "subject": subject,
        "htmlContent": html,
    }
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.post(BREVO_URL, json=payload,
                                  headers={"api-key": settings.brevo_api_key})
        ok = r.status_code in (200, 201, 202)
        if not ok:
            log.warning("Brevo send failed (%s): %s", r.status_code, r.text[:200])
        return ok
    except Exception as exc:  # network problems must not 500 the API
        log.warning("Brevo send error: %s", exc)
        return False


def otp_html(code: str, purpose: str) -> str:
    action = "verify your email" if purpose == "signup" else "reset your password"
    return f"""
    <div style="font-family:system-ui,sans-serif;max-width:440px;margin:0 auto;padding:24px">
      <h2 style="color:#7C4DFF;margin-bottom:4px">SoNex</h2>
      <p>Use this code to {action}:</p>
      <div style="font-size:36px;font-weight:800;letter-spacing:10px;background:#f4f1ff;
                  border-radius:12px;padding:16px;text-align:center">{code}</div>
      <p style="color:#666;font-size:13px">The code expires in 10 minutes.
      If you didn't request this, you can ignore this email.</p>
      <p style="color:#999;font-size:12px">A product of
        <a href="https://patienceai.in" style="color:#7C4DFF">Patience AI</a></p>
    </div>"""
