from .conftest import create_user, login, register_device


async def test_login_ok_and_events_ingest(client, db):
    headers = await login(client, db)
    assert headers["Authorization"].startswith("Bearer ")

    reg = await register_device(client)
    events = {"events": [
        {"ts": "2026-07-02T12:00:00Z", "type": "STATE_CHANGE", "room_state": "TALKING",
         "action": "DUCK", "level": 30, "db": -32.5, "source": "engine"},
        {"ts": "2026-07-02T12:00:05Z", "type": "OVERRIDE", "action": "BOOST", "source": "user"},
    ]}
    resp = await client.post("/v1/events", json=events, headers={"X-Device-Key": reg["api_key"]})
    assert resp.status_code == 202
    assert resp.json() == {"accepted": 2}


async def test_login_wrong_password_401(client, db):
    await create_user(db)
    resp = await client.post("/v1/auth/login", json={"email": "dev@sonex.test", "password": "wrong"})
    assert resp.status_code == 401


async def test_login_unknown_user_401(client):
    resp = await client.post("/v1/auth/login", json={"email": "nobody@x.y", "password": "x"})
    assert resp.status_code == 401


async def test_device_endpoints_require_key(client):
    resp = await client.post("/v1/events", json={"events": []})
    assert resp.status_code == 401
    resp = await client.post("/v1/events", json={"events": []}, headers={"X-Device-Key": "bogus"})
    assert resp.status_code == 401


async def test_portal_requires_jwt(client):
    resp = await client.get("/v1/devices")
    assert resp.status_code == 401
    resp = await client.get("/v1/devices", headers={"Authorization": "Bearer garbage"})
    assert resp.status_code == 401


async def test_rate_limit(client, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "rate_limit_requests", 3)
    reg = await register_device(client)
    key = {"X-Device-Key": reg["api_key"]}
    body = {"events": [{"ts": "2026-07-02T12:00:00Z", "type": "CALL"}]}
    statuses = [(await client.post("/v1/events", json=body, headers=key)).status_code for _ in range(5)]
    assert statuses[:3] == [202, 202, 202]
    assert 429 in statuses[3:]


async def test_signup_creates_account_and_login_works(client):
    r = await client.post("/v1/auth/signup", json={"email": "new@sonex.test", "password": "longenough8"})
    assert r.status_code == 201
    assert r.json()["access_token"]
    r2 = await client.post("/v1/auth/login", json={"email": "new@sonex.test", "password": "longenough8"})
    assert r2.status_code == 200


async def test_signup_duplicate_email_409(client):
    body = {"email": "dup@sonex.test", "password": "longenough8"}
    assert (await client.post("/v1/auth/signup", json=body)).status_code == 201
    assert (await client.post("/v1/auth/signup", json=body)).status_code == 409


async def test_signup_short_password_422(client):
    r = await client.post("/v1/auth/signup", json={"email": "x@sonex.test", "password": "short"})
    assert r.status_code == 422
