import secrets

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import get_db
from ..models import Device, Home, User
from ..schemas import DeviceRegisterIn, DeviceRegisterOut, LoginIn, TokenOut
from ..security import create_access_token, hash_api_key, hash_password, verify_password

router = APIRouter(tags=["auth"])


@router.post("/auth/signup", response_model=TokenOut, status_code=201)
async def signup(body: LoginIn, db: AsyncSession = Depends(get_db)):
    if len(body.password) < 8:
        raise HTTPException(status_code=422, detail="Password must be at least 8 characters")
    result = await db.execute(select(User).where(User.email == body.email))
    if result.scalar_one_or_none() is not None:
        raise HTTPException(status_code=409, detail="Account already exists")
    user = User(email=body.email, password_hash=hash_password(body.password))
    db.add(user)
    await db.commit()
    return TokenOut(access_token=create_access_token(user.id))


@router.post("/auth/login", response_model=TokenOut)
async def login(body: LoginIn, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(User).where(User.email == body.email))
    user = result.scalar_one_or_none()
    if user is None or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    return TokenOut(access_token=create_access_token(user.id))


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
