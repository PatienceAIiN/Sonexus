from .conftest import login
from .test_manifest import seed_model


async def test_promote_and_rollback(client, db, storage):
    v1 = await seed_model(db, storage, kind="home", home_id=None, version="1.0.1", data=b"v1")
    v2 = await seed_model(db, storage, kind="home", home_id=None, version="1.0.2", data=b"v2")
    headers = await login(client, db)

    models = (await client.get("/v1/models", headers=headers)).json()["models"]
    assert {m["id"] for m in models} == {v1.id, v2.id}

    # promote v2: v1 (also active from seeding) demoted to rollback
    resp = await client.post(f"/v1/models/{v2.id}/promote", headers=headers)
    assert resp.status_code == 200 and resp.json()["status"] == "active"
    by_id = {m["id"]: m for m in (await client.get("/v1/models", headers=headers)).json()["models"]}
    assert by_id[v2.id]["status"] == "active"
    assert by_id[v1.id]["status"] == "rollback"

    # rollback v2: previous version becomes active again
    resp = await client.post(f"/v1/models/{v2.id}/rollback", headers=headers)
    assert resp.status_code == 200
    assert resp.json() == {"id": v2.id, "status": "rollback", "active_id": v1.id}
    by_id = {m["id"]: m for m in (await client.get("/v1/models", headers=headers)).json()["models"]}
    assert by_id[v1.id]["status"] == "active"
    assert by_id[v2.id]["status"] == "rollback"


async def test_promote_unknown_model_404(client, db):
    headers = await login(client, db)
    assert (await client.post("/v1/models/999/promote", headers=headers)).status_code == 404
