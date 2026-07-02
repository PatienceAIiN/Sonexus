import json

import fakeredis.aioredis
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

import app.redisq as redisq
import app.storage as storage_mod
from app.db import Base, get_db
from app.main import app
from app.models import User
from app.security import hash_password
from app.storage import FailoverStorage, LocalDiskBackend


class FailingCloudinary:
    """Mock Cloudinary backend that always fails (for failover tests)."""

    name = "cloudinary"

    def put(self, key, data, content_type="application/octet-stream"):
        raise RuntimeError("cloudinary down")

    def get(self, key):
        raise RuntimeError("cloudinary down")

    def url(self, key):
        raise RuntimeError("cloudinary down")

    def delete(self, key):
        raise RuntimeError("cloudinary down")


class R2Disk(LocalDiskBackend):
    """Local disk standing in for R2 in tests."""

    name = "r2"


@pytest_asyncio.fixture
async def db_session_factory(tmp_path):
    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path}/test.db")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    yield factory
    await engine.dispose()


@pytest_asyncio.fixture
async def db(db_session_factory):
    async with db_session_factory() as session:
        yield session


@pytest_asyncio.fixture
def fake_redis():
    r = fakeredis.aioredis.FakeRedis(decode_responses=True)
    redisq._redis = r
    yield r
    redisq._redis = None


@pytest_asyncio.fixture
def storage(tmp_path):
    """Failover storage with a dead Cloudinary primary and disk-backed 'r2' fallback."""
    s = FailoverStorage(FailingCloudinary(), R2Disk(str(tmp_path / "r2")))
    storage_mod._storage = s
    yield s
    storage_mod._storage = None


@pytest_asyncio.fixture
async def client(db_session_factory, fake_redis, storage):
    async def override_get_db():
        async with db_session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()


# ---- helpers ----

async def create_user(db, email="dev@sonex.test", password="hunter22") -> User:
    user = User(email=email, password_hash=hash_password(password), is_verified=True)
    db.add(user)
    await db.commit()
    return user


async def login(client, db, email="dev@sonex.test", password="hunter22") -> dict:
    await create_user(db, email, password)
    resp = await client.post("/v1/auth/login", json={"email": email, "password": password})
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


async def register_device(client, device_name="Living Room", home_name="Home A") -> dict:
    resp = await client.post("/v1/devices/register",
                             json={"device_name": device_name, "home_name": home_name})
    assert resp.status_code == 201, resp.text
    return resp.json()


async def grant_consent(client, device_key: str, purpose="upload_clips"):
    resp = await client.put("/v1/consents", json={"purpose": purpose, "granted": True},
                            headers={"X-Device-Key": device_key})
    assert resp.status_code == 200, resp.text


async def upload_clip(client, device_key: str, data=b"RIFFfakewav", label=None):
    meta = {"ts": "2026-07-02T12:00:00Z", "duration_ms": 1500, "label": label, "room_state": "TALKING"}
    return await client.post(
        "/v1/clips",
        headers={"X-Device-Key": device_key},
        files={"file": ("clip.wav", data, "audio/wav")},
        data={"meta": json.dumps(meta)},
    )
