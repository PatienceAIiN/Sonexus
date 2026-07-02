from sqlalchemy import func, select

from app.models import Clip, Device, Event

from .conftest import grant_consent, register_device, upload_clip


async def test_data_delete_removes_rows_and_objects(client, db, tmp_path):
    reg = await register_device(client)
    key = {"X-Device-Key": reg["api_key"]}
    await grant_consent(client, reg["api_key"])
    await client.post("/v1/events", headers=key, json={"events": [
        {"ts": "2026-07-02T12:00:00Z", "type": "STATE_CHANGE", "room_state": "QUIET"}]})
    up = (await upload_clip(client, reg["api_key"], data=b"SECRET")).json()
    obj_path = tmp_path / "r2" / up["storage_key"]
    assert obj_path.exists()

    resp = await client.post("/v1/data/delete", headers=key)
    assert resp.status_code == 200
    body = resp.json()
    assert body["deleted"] is True
    assert body["clips"] == 1 and body["events"] == 1 and body["devices"] == 1

    assert not obj_path.exists()  # stored object removed
    assert (await db.execute(select(func.count()).select_from(Clip))).scalar() == 0
    assert (await db.execute(select(func.count()).select_from(Event))).scalar() == 0
    assert (await db.execute(select(func.count()).select_from(Device))).scalar() == 0
    # key no longer valid
    assert (await client.get("/v1/data/export", headers=key)).status_code == 401


async def test_data_export_returns_everything(client):
    reg = await register_device(client)
    key = {"X-Device-Key": reg["api_key"]}
    await grant_consent(client, reg["api_key"])
    await client.post("/v1/events", headers=key, json={"events": [
        {"ts": "2026-07-02T12:00:00Z", "type": "OVERRIDE", "action": "BOOST", "source": "user"}]})
    await upload_clip(client, reg["api_key"], label="speech")

    resp = await client.get("/v1/data/export", headers=key)
    assert resp.status_code == 200
    body = resp.json()
    assert body["device"]["id"] == reg["device_id"]
    assert "api_key_hash" not in body["device"]
    assert len(body["events"]) == 1 and body["events"][0]["action"] == "BOOST"
    assert len(body["clips"]) == 1
    assert any(c["purpose"] == "upload_clips" and c["granted"] for c in body["consents"])


async def test_privacy_requires_auth(client):
    assert (await client.post("/v1/data/delete")).status_code == 401
    assert (await client.get("/v1/data/export")).status_code == 401
