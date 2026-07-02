from .conftest import grant_consent, login, register_device, upload_clip


async def test_clip_rejected_without_consent(client):
    reg = await register_device(client)
    resp = await upload_clip(client, reg["api_key"])
    assert resp.status_code == 403
    assert "consent" in resp.json()["detail"]


async def test_clip_accepted_with_consent_and_failover(client, tmp_path):
    reg = await register_device(client)
    await grant_consent(client, reg["api_key"], "upload_clips")
    resp = await upload_clip(client, reg["api_key"], data=b"WAVDATA", label="speech")
    assert resp.status_code == 201
    body = resp.json()
    assert body["clip_id"] > 0
    assert body["storage_key"].startswith("clips/")
    # Cloudinary mock is failing -> object landed on R2
    assert body["backend"] == "r2"
    assert (tmp_path / "r2" / body["storage_key"]).read_bytes() == b"WAVDATA"


async def test_consent_revocation_blocks_upload(client):
    reg = await register_device(client)
    await grant_consent(client, reg["api_key"], "upload_clips")
    resp = await client.put("/v1/consents", json={"purpose": "upload_clips", "granted": False},
                            headers={"X-Device-Key": reg["api_key"]})
    assert resp.status_code == 200
    resp = await upload_clip(client, reg["api_key"])
    assert resp.status_code == 403


async def test_portal_clip_list_and_audio(client, db):
    reg = await register_device(client)
    await grant_consent(client, reg["api_key"])
    up = (await upload_clip(client, reg["api_key"], data=b"BYTES", label="speech")).json()

    headers = await login(client, db)
    # home_id 1 was created by device registration
    clips = (await client.get("/v1/clips?home_id=1", headers=headers)).json()["clips"]
    assert len(clips) == 1 and clips[0]["id"] == up["clip_id"]
    assert clips[0]["storage_key"] == up["storage_key"]

    audio = await client.get(f"/v1/clips/{up['clip_id']}/audio", headers=headers)
    assert audio.status_code == 200
    assert audio.content == b"BYTES"
