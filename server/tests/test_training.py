import hashlib
import json

from app.models import Event, Model
from app.redisq import pop_job
from training.pipeline import run_training, tune_thresholds

from .conftest import grant_consent, login, register_device, upload_clip


def test_tune_thresholds_direction():
    base = {"sensitivity": 0.5, "boost_margin_db": 8.0}
    boosts = [Event(device_id=1, ts=None, type="OVERRIDE", action="BOOST", source="user")] * 5
    restores = [Event(device_id=1, ts=None, type="OVERRIDE", action="RESTORE", source="user")] * 5
    up = tune_thresholds(base, boosts)
    down = tune_thresholds(base, restores)
    # user kept boosting manually -> engine should trigger more eagerly
    assert up["sensitivity"] < base["sensitivity"]
    assert up["boost_margin_db"] > base["boost_margin_db"]
    # user kept undoing boosts -> engine should trigger less eagerly
    assert down["sensitivity"] > base["sensitivity"]
    assert down["boost_margin_db"] < base["boost_margin_db"]


async def _seed_home(client):
    reg = await register_device(client)
    await grant_consent(client, reg["api_key"])
    for i, label in enumerate(["speech", "background"] * 4):
        r = await upload_clip(client, reg["api_key"], data=f"clip{i}".encode(), label=label)
        assert r.status_code == 201
    return reg


async def test_training_produces_artifact_and_manifest_bump(client, db, storage):
    reg = await _seed_home(client)
    key = {"X-Device-Key": reg["api_key"]}

    before = (await client.get(f"/v1/models/manifest?device={reg['device_id']}", headers=key)).json()
    assert "home" not in before["models"]

    model = await run_training(db, storage, home_id=1)
    assert model.status == "active" and len(model.sha256) == 64

    after = (await client.get(f"/v1/models/manifest?device={reg['device_id']}", headers=key)).json()
    entry = after["models"]["home"]
    assert entry["version"] == "1.0.1" and entry["sha256"] == model.sha256

    # downloaded artifact is valid and checksum-true
    dl = await client.get(entry["url"], headers=key)
    assert hashlib.sha256(dl.content).hexdigest() == model.sha256
    if model.file.endswith(".json"):
        art = json.loads(dl.content)
        assert art["feature_names"] and art["weights"] and art["bias"]
    else:
        import onnx

        onnx.load_from_string(dl.content)  # parses as valid ONNX


async def test_retrain_bumps_version_and_keeps_rollback(client, db, storage):
    await _seed_home(client)
    m1 = await run_training(db, storage, home_id=1)
    m2 = await run_training(db, storage, home_id=1)
    assert (m1.version, m2.version) == ("1.0.1", "1.0.2")
    assert m2.status == "active"
    assert (await db.get(Model, m1.id)).status == "rollback"  # kept for rollback


async def test_override_events_shift_trained_thresholds(client, db, storage):
    reg = await _seed_home(client)
    key = {"X-Device-Key": reg["api_key"]}

    await run_training(db, storage, home_id=1)
    base = (await client.get(f"/v1/models/manifest?device={reg['device_id']}", headers=key)).json()["thresholds"]

    boost_events = {"events": [
        {"ts": "2026-07-02T12:00:00Z", "type": "OVERRIDE", "action": "BOOST", "source": "user"}
        for _ in range(5)]}
    assert (await client.post("/v1/events", json=boost_events, headers=key)).status_code == 202

    await run_training(db, storage, home_id=1)
    tuned = (await client.get(f"/v1/models/manifest?device={reg['device_id']}", headers=key)).json()["thresholds"]
    assert tuned["sensitivity"] < base["sensitivity"]
    assert tuned["boost_margin_db"] > base["boost_margin_db"]


async def test_training_run_enqueues_job(client, db, fake_redis):
    await register_device(client)  # creates home 1
    headers = await login(client, db)
    resp = await client.post("/v1/training/run", json={"home_id": 1}, headers=headers)
    assert resp.status_code == 202
    job_id = resp.json()["job_id"]

    job = await pop_job(fake_redis, timeout=1)
    assert job["id"] == job_id
    assert job["name"] == "train_home" and job["kwargs"] == {"home_id": 1}
    assert await fake_redis.hget(f"sonex:job:{job_id}", "status") == "queued"


async def test_training_run_unknown_home_404(client, db):
    headers = await login(client, db)
    assert (await client.post("/v1/training/run", json={"home_id": 99}, headers=headers)).status_code == 404
