import hashlib

from app.models import Model

from .conftest import register_device


async def seed_model(db, storage, *, kind, home_id=None, version="1.0", data=b"model-bytes"):
    key = f"models/{kind}/{home_id or 'global'}/{version}.onnx"
    backend = storage.put(key, data)
    m = Model(home_id=home_id, kind=kind, file=f"{kind}.onnx", version=version,
              sha256=hashlib.sha256(data).hexdigest(), min_app_version=1,
              status="active", storage_key=key, backend=backend)
    db.add(m)
    await db.commit()
    return m


async def test_manifest_checksums_and_fallback(client, db, storage):
    reg = await register_device(client)
    vad = await seed_model(db, storage, kind="vad", data=b"vad-onnx")
    sound = await seed_model(db, storage, kind="sound", data=b"sound-onnx")

    resp = await client.get(f"/v1/models/manifest?device={reg['device_id']}",
                            headers={"X-Device-Key": reg["api_key"]})
    assert resp.status_code == 200
    body = resp.json()
    assert set(body) == {"models", "thresholds"}
    assert body["thresholds"]["sensitivity"] == 0.5
    for kind, m in (("vad", vad), ("sound", sound)):
        entry = body["models"][kind]
        assert entry["sha256"] == m.sha256 and len(entry["sha256"]) == 64
        assert entry["url"] == f"/v1/models/{m.id}/download"
        assert entry["min_app_version"] == 1

    # download bytes match the checksum in the manifest
    dl = await client.get(body["models"]["vad"]["url"], headers={"X-Device-Key": reg["api_key"]})
    assert dl.status_code == 200
    assert hashlib.sha256(dl.content).hexdigest() == vad.sha256


async def test_manifest_prefers_per_home_model(client, db, storage):
    reg = await register_device(client)
    await seed_model(db, storage, kind="home", home_id=None, version="0.9", data=b"global")
    per_home = await seed_model(db, storage, kind="home", home_id=1, version="1.0.1", data=b"home")

    body = (await client.get(f"/v1/models/manifest?device={reg['device_id']}",
                             headers={"X-Device-Key": reg["api_key"]})).json()
    assert body["models"]["home"]["id"] == per_home.id
    assert body["models"]["home"]["version"] == "1.0.1"


async def test_manifest_requires_device_auth(client):
    resp = await client.get("/v1/models/manifest?device=1")
    assert resp.status_code == 401
