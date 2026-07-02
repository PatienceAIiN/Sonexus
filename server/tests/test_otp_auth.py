"""Signup OTP verification, forgot/reset password, instant session teardown."""
import re

import pytest

from app import emailer
from app.routers import auth as auth_router


@pytest.fixture
def outbox(monkeypatch):
    """Capture Brevo sends; expose the last OTP code per recipient."""
    sent = []

    async def fake_send(to, subject, html):
        sent.append({"to": to, "subject": subject, "html": html})
        return True

    monkeypatch.setattr(emailer, "send_email", fake_send)
    monkeypatch.setattr(auth_router, "send_email", fake_send)
    from app.routers import public as public_router
    monkeypatch.setattr(public_router, "send_email", fake_send)

    def code_for(email):
        for m in reversed(sent):
            if m["to"] == email:
                return re.search(r">(\d{6})<", m["html"]).group(1)
        return None

    sent_codes = type("Outbox", (), {"messages": sent, "code_for": staticmethod(code_for)})
    return sent_codes


EMAIL = "otp@sonex.test"
PW = "longenough8"


async def _signup(client):
    return await client.post("/v1/auth/signup", json={"email": EMAIL, "password": PW})


async def test_signup_sends_otp_and_verify_returns_token(client, outbox):
    r = await _signup(client)
    assert r.status_code == 201
    assert r.json()["pending"] is True
    code = outbox.code_for(EMAIL)
    assert code and len(code) == 6

    v = await client.post("/v1/auth/verify", json={"email": EMAIL, "code": code})
    assert v.status_code == 200
    assert v.json()["access_token"]


async def test_login_blocked_until_verified_and_resends_code(client, outbox):
    await _signup(client)
    r = await client.post("/v1/auth/login", json={"email": EMAIL, "password": PW})
    assert r.status_code == 403
    assert "verified" in r.json()["detail"].lower()
    # a fresh code was mailed by the login attempt
    code = outbox.code_for(EMAIL)
    assert (await client.post("/v1/auth/verify", json={"email": EMAIL, "code": code})).status_code == 200
    assert (await client.post("/v1/auth/login", json={"email": EMAIL, "password": PW})).status_code == 200


async def test_wrong_code_rejected_with_friendly_error(client, outbox):
    await _signup(client)
    r = await client.post("/v1/auth/verify", json={"email": EMAIL, "code": "000000"})
    # (000000 could collide 1-in-a-million; the fixture code is what was mailed)
    if outbox.code_for(EMAIL) != "000000":
        assert r.status_code == 400
        assert "wrong code" in r.json()["detail"].lower()


async def test_forgot_reset_changes_password_and_kills_sessions(client, outbox):
    await _signup(client)
    code = outbox.code_for(EMAIL)
    await client.post("/v1/auth/verify", json={"email": EMAIL, "code": code})
    login = await client.post("/v1/auth/login", json={"email": EMAIL, "password": PW})
    old_token = login.json()["access_token"]
    auth_hdr = {"Authorization": f"Bearer {old_token}"}
    assert (await client.get("/v1/models", headers=auth_hdr)).status_code == 200

    assert (await client.post("/v1/auth/forgot", json={"email": EMAIL})).status_code == 200
    reset_code = outbox.code_for(EMAIL)
    r = await client.post("/v1/auth/reset", json={
        "email": EMAIL, "code": reset_code, "new_password": "brand-new-pw9"
    })
    assert r.status_code == 200

    # old session is torn down instantly
    assert (await client.get("/v1/models", headers=auth_hdr)).status_code == 401
    # old password dead, new one works
    assert (await client.post("/v1/auth/login", json={"email": EMAIL, "password": PW})).status_code == 401
    assert (await client.post("/v1/auth/login",
        json={"email": EMAIL, "password": "brand-new-pw9"})).status_code == 200


async def test_forgot_does_not_reveal_unknown_emails(client, outbox):
    r = await client.post("/v1/auth/forgot", json={"email": "ghost@sonex.test"})
    assert r.status_code == 200
    assert outbox.code_for("ghost@sonex.test") is None


async def test_contact_form_emails_the_team(client, outbox):
    r = await client.post("/v1/contact", json={
        "name": "Asha", "email": "asha@example.com", "message": "Love it!"
    })
    assert r.status_code == 202
    assert any("SoNex contact" in m["subject"] for m in outbox.messages)


async def test_landing_footer_and_legal_pages(client):
    landing = (await client.get("/")).text
    assert "Patience AI" in landing and "patienceai.in" in landing
    assert "/terms" in landing and "/privacy" in landing
    assert (await client.get("/terms")).status_code == 200
    assert (await client.get("/privacy")).status_code == 200


async def test_feedback_form_emails_growth_team(client, outbox):
    r = await client.post("/v1/feedback", json={
        "email": "user@sonex.test", "message": "Ducking is too aggressive",
        "diagnostics": {"app_version": "1.3", "calibration": "floor=-55dB"}
    })
    assert r.status_code == 202
    fb = [m for m in outbox.messages if "feedback" in m["subject"].lower()]
    assert fb and "Ducking" in fb[0]["html"] and "floor=-55dB" in fb[0]["html"]


async def test_feedback_empty_message_422(client, outbox):
    r = await client.post("/v1/feedback", json={"message": "   "})
    assert r.status_code == 422
