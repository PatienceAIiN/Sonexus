import hashlib
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import get_db
from ..emailer import otp_html, send_email
from ..models import Device, Home, OtpCode, User
from ..schemas import DeviceRegisterIn, DeviceRegisterOut, LoginIn, TokenOut
from ..security import create_access_token, hash_api_key, hash_password, verify_password

router = APIRouter(tags=["auth"])

OTP_TTL_MINUTES = 10
OTP_MAX_ATTEMPTS = 5


class OtpVerifyIn(BaseModel):
    email: str
    code: str


class ForgotIn(BaseModel):
    email: str


class ResetIn(BaseModel):
    email: str
    code: str
    new_password: str


def _hash_code(code: str) -> str:
    return hashlib.sha256(code.encode()).hexdigest()


async def _issue_otp(db: AsyncSession, email: str, purpose: str) -> None:
    """Create + email a fresh 6-digit code, replacing any previous one."""
    await db.execute(delete(OtpCode).where(OtpCode.email == email, OtpCode.purpose == purpose))
    code = f"{secrets.randbelow(1_000_000):06d}"
    db.add(OtpCode(
        email=email, code_hash=_hash_code(code), purpose=purpose,
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=OTP_TTL_MINUTES),
    ))
    await db.commit()
    subject = "Your SoNex verification code" if purpose == "signup" else "Reset your SoNex password"
    await send_email(email, subject, otp_html(code, purpose))


async def _check_otp(db: AsyncSession, email: str, purpose: str, code: str) -> None:
    """Raise a friendly 4xx unless the code is valid; consume it on success."""
    row = (await db.execute(
        select(OtpCode).where(OtpCode.email == email, OtpCode.purpose == purpose)
    )).scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=400, detail="No code was requested — try again")
    expires = row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    if expires < datetime.now(timezone.utc):
        raise HTTPException(status_code=400, detail="That code expired — request a new one")
    if row.attempts >= OTP_MAX_ATTEMPTS:
        raise HTTPException(status_code=429, detail="Too many wrong attempts — request a new code")
    if row.code_hash != _hash_code(code.strip()):
        row.attempts += 1
        await db.commit()
        raise HTTPException(status_code=400, detail="Wrong code — check the email and try again")
    await db.delete(row)


@router.post("/auth/signup", status_code=201)
async def signup(body: LoginIn, db: AsyncSession = Depends(get_db)):
    if len(body.password) < 8:
        raise HTTPException(status_code=422, detail="Password must be at least 8 characters")
    email = body.email.strip().lower()
    existing = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
    if existing is not None and existing.is_verified:
        raise HTTPException(status_code=409, detail="An account with this email already exists — try signing in")
    if existing is None:
        db.add(User(email=email, password_hash=hash_password(body.password)))
    else:  # unverified retry: allow a fresh password + fresh code
        existing.password_hash = hash_password(body.password)
    await db.commit()
    await _issue_otp(db, email, "signup")
    return {"pending": True, "detail": "We emailed you a 6-digit code"}


@router.post("/auth/verify", response_model=TokenOut)
async def verify(body: OtpVerifyIn, db: AsyncSession = Depends(get_db)):
    email = body.email.strip().lower()
    user = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=404, detail="No signup found for this email")
    await _check_otp(db, email, "signup", body.code)
    user.is_verified = True
    await db.commit()
    return TokenOut(access_token=create_access_token(user.id, user.token_version))


class GoogleAuthIn(BaseModel):
    id_token: str


@router.post("/auth/google", response_model=TokenOut)
async def google_auth(body: GoogleAuthIn, db: AsyncSession = Depends(get_db)):
    """Sign in / sign up with Google. The app sends a Google ID token; we verify
    it with Google, then create or link the account by (verified) email and issue
    our own session JWT. No password is needed for Google accounts."""
    import httpx
    from ..config import settings

    if not settings.google_client_id:
        raise HTTPException(status_code=503, detail="Google sign-in isn't set up yet")
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get("https://oauth2.googleapis.com/tokeninfo",
                                  params={"id_token": body.id_token})
    except Exception:
        raise HTTPException(status_code=502, detail="Couldn't reach Google — try again")
    if r.status_code != 200:
        raise HTTPException(status_code=401, detail="Google sign-in failed — try again")
    info = r.json()
    # The token must be issued by Google, minted FOR our app, with a verified email.
    if info.get("iss") not in ("accounts.google.com", "https://accounts.google.com"):
        raise HTTPException(status_code=401, detail="Invalid Google token")
    if info.get("aud") != settings.google_client_id:
        raise HTTPException(status_code=401, detail="This Google token wasn't issued for SoNex")
    if str(info.get("email_verified")).lower() != "true":
        raise HTTPException(status_code=401, detail="Your Google email isn't verified")
    email = (info.get("email") or "").strip().lower()
    if not email:
        raise HTTPException(status_code=401, detail="Google didn't return an email")

    user = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
    if user is None:
        # No password for Google accounts — store a random unusable hash.
        user = User(email=email,
                    password_hash=hash_password(secrets.token_urlsafe(32)),
                    is_verified=True)
        db.add(user)
    else:
        user.is_verified = True  # Google already verified the email
    await db.commit()
    await db.refresh(user)
    return TokenOut(access_token=create_access_token(user.id, user.token_version))


@router.post("/auth/login", response_model=TokenOut)
async def login(body: LoginIn, db: AsyncSession = Depends(get_db)):
    email = body.email.strip().lower()
    user = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
    if user is None or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Wrong email or password")
    if not user.is_verified:
        await _issue_otp(db, email, "signup")
        raise HTTPException(status_code=403, detail="Email not verified — we just sent you a new code")
    return TokenOut(access_token=create_access_token(user.id, user.token_version))


@router.post("/auth/forgot")
async def forgot(body: ForgotIn, db: AsyncSession = Depends(get_db)):
    email = body.email.strip().lower()
    user = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
    # Same answer whether or not the account exists — no address probing.
    if user is not None:
        await _issue_otp(db, email, "reset")
    return {"detail": "If that email has an account, a code is on its way"}


@router.post("/auth/reset")
async def reset(body: ResetIn, db: AsyncSession = Depends(get_db)):
    if len(body.new_password) < 8:
        raise HTTPException(status_code=422, detail="Password must be at least 8 characters")
    email = body.email.strip().lower()
    user = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=404, detail="No account for this email")
    await _check_otp(db, email, "reset", body.code)
    user.password_hash = hash_password(body.new_password)
    user.token_version += 1  # tear down every existing session instantly
    user.is_verified = True
    await db.commit()
    return {"detail": "Password changed — sign in with your new password"}


@router.post("/devices/register", response_model=DeviceRegisterOut, status_code=201)
async def register_device(body: DeviceRegisterIn, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Home).where(Home.name == body.home_name))
    home = result.scalar_one_or_none()
    if home is None:
        home = Home(name=body.home_name)
        db.add(home)
        await db.flush()
    api_key = secrets.token_urlsafe(32)
    device = Device(home_id=home.id, name=body.device_name, api_key_hash=hash_api_key(api_key))
    db.add(device)
    await db.commit()
    return DeviceRegisterOut(device_id=device.id, api_key=api_key)
