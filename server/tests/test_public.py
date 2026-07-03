import json

from app.routers.public import RELEASES_KEY


RELEASES = {
    "mobile": {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-mobile.apk"},
    "tv": {"version_code": 2, "version_name": "1.1", "key": "apk/sonex-tv.apk"},
}


def _publish(storage):
    storage.put(RELEASES_KEY, json.dumps(RELEASES).encode(), "application/json")
    storage.put("apk/sonex-mobile.apk", b"apk-bytes", "application/vnd.android.package-archive")


async def test_landing_page_serves_html(client):
    r = await client.get("/")
    assert r.status_code == 200
    assert "SoNex" in r.text
    assert "patienceai.in" in r.text
    assert "/download/mobile" in r.text


async def test_releases_reports_versions_and_urls(client, storage):
    _publish(storage)
    r = await client.get("/v1/app/releases")
    assert r.status_code == 200
    body = r.json()
    assert body["mobile"]["version_code"] == 2
    assert body["mobile"]["url"]
    assert body["tv"]["version_name"] == "1.1"


async def test_releases_404_before_publish(client):
    r = await client.get("/v1/app/releases")
    assert r.status_code == 404


async def test_download_redirects_to_storage(client, storage):
    _publish(storage)
    r = await client.get("/download/mobile", follow_redirects=False)
    assert r.status_code == 307
    assert r.headers["location"]


async def test_download_unknown_target_404(client, storage):
    _publish(storage)
    assert (await client.get("/download/windows")).status_code == 404


async def test_seo_favicon_robots_sitemap(client):
    landing = (await client.get("/")).text
    assert "rel=\"icon\"" in landing        # browser tab icon
    assert "og:title" in landing
    robots = await client.get("/robots.txt")
    assert robots.status_code == 200 and "Sitemap:" in robots.text
    sm = await client.get("/sitemap.xml")
    assert sm.status_code == 200 and "/privacy" in sm.text


async def test_admin_dashboard_gated(client, monkeypatch):
    from app.config import settings
    # Disabled without a password
    assert (await client.get("/admin")).status_code == 404
    monkeypatch.setattr(settings, "admin_password", "Test@10")
    assert (await client.get("/admin")).status_code == 200
    # Stats need a login
    assert (await client.get("/admin/api/stats")).status_code == 401
    bad = await client.post("/admin/login", json={"username": "admin", "password": "nope"})
    assert bad.status_code == 401
    ok = await client.post("/admin/login", json={"username": "admin", "password": "Test@10"})
    assert ok.status_code == 200
    client.cookies.update(ok.cookies)
    stats = await client.get("/admin/api/stats")
    assert stats.status_code == 200
    body = stats.json()
    assert {"counts", "health", "models", "metrics", "uptime_sec"} <= body.keys()


async def test_admin_table_crud(client, db, monkeypatch):
    from app.config import settings
    from .conftest import create_user
    monkeypatch.setattr(settings, "admin_password", "Test@10")
    await create_user(db, "crud@sonex.test", "longenough8")
    ok = await client.post("/admin/login", json={"username": "admin", "password": "Test@10"})
    client.cookies.update(ok.cookies)

    rows = (await client.get("/admin/api/table/users")).json()["rows"]
    assert rows and "password_hash" not in rows[0]
    uid = rows[0]["id"]

    upd = await client.put(f"/admin/api/table/users/{uid}", json={"email": "renamed@sonex.test"})
    assert upd.status_code == 200 and upd.json()["email"] == "renamed@sonex.test"

    assert (await client.delete(f"/admin/api/table/users/{uid}")).status_code == 200
    assert uid not in [r["id"] for r in (await client.get("/admin/api/table/users")).json()["rows"]]
    assert (await client.get("/admin/api/table/nope")).status_code == 404


async def test_admin_train_publishes_lite_model(client, db, storage, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "admin_password", "Test@10")
    ok = await client.post("/admin/login", json={"username": "admin", "password": "Test@10"})
    client.cookies.update(ok.cookies)

    r = await client.post("/admin/api/train")
    assert r.status_code == 200
    d = r.json()
    assert d["version"].startswith("1.") and 0.0 <= d["accuracy"] <= 1.0 and d["n_samples"] > 0

    rows = (await client.get("/admin/api/table/models")).json()["rows"]
    lite = [x for x in rows if x["kind"] == "lite" and x["status"] == "active"]
    assert len(lite) == 1  # exactly one active lite model after publishing

    # Training again supersedes the previous one (only newest stays active).
    assert (await client.post("/admin/api/train")).status_code == 200
    rows2 = (await client.get("/admin/api/table/models")).json()["rows"]
    assert len([x for x in rows2 if x["kind"] == "lite" and x["status"] == "active"]) == 1


async def test_training_consumes_and_deletes_consented_audio(client, db, storage, monkeypatch):
    import io
    import wave

    from app.config import settings
    from .conftest import register_device, grant_consent
    # A device with the audio consent uploads a real WAV clip labelled SPEECH.
    reg = await register_device(client)
    key = reg["api_key"]
    await grant_consent(client, key, "upload_clips")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes(bytes(2 * 16000))  # ~1s of silence PCM16
    up = await client.post("/v1/clips", headers={"X-Device-Key": key},
                           files={"file": ("clip.wav", buf.getvalue(), "audio/wav")},
                           data={"meta": '{"ts":"2026-07-02T12:00:00Z","duration_ms":1000,'
                                         '"label":"SPEECH","room_state":"TALKING"}'})
    assert up.status_code == 201
    clip_id = up.json()["clip_id"]

    # Train (same job the 02:00 IST scheduler runs): it must use and then DELETE it.
    monkeypatch.setattr(settings, "admin_password", "Test@10")
    ok = await client.post("/admin/login", json={"username": "admin", "password": "Test@10"})
    client.cookies.update(ok.cookies)
    r = await client.post("/admin/api/train")
    assert r.status_code == 200 and r.json()["clips_used"] >= 1 and r.json()["clips_deleted"] >= 1

    # The audio is gone — row deleted, nothing lingering.
    from app.models import Clip
    assert await db.get(Clip, clip_id) is None


async def test_admin_can_list_and_play_uploaded_audio(client, db, storage, monkeypatch):
    import io
    import wave

    from app.config import settings
    from .conftest import register_device, grant_consent
    reg = await register_device(client)
    key = reg["api_key"]
    await grant_consent(client, key, "upload_clips")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(bytes(16000))
    up = await client.post("/v1/clips", headers={"X-Device-Key": key},
                           files={"file": ("clip.wav", buf.getvalue(), "audio/wav")},
                           data={"meta": '{"ts":"2026-07-02T12:00:00Z","duration_ms":500,'
                                         '"label":"SPEECH","room_state":"TALKING"}'})
    cid = up.json()["clip_id"]

    monkeypatch.setattr(settings, "admin_password", "Test@10")
    ok = await client.post("/admin/login", json={"username": "admin", "password": "Test@10"})
    client.cookies.update(ok.cookies)

    lst = await client.get("/admin/api/clips")
    assert lst.status_code == 200 and any(c["id"] == cid for c in lst.json()["clips"])
    audio = await client.get(f"/admin/api/clip/{cid}/audio")
    assert audio.status_code == 200 and audio.headers["content-type"] == "audio/wav" and audio.content[:4] == b"RIFF"
    # Not reachable without the admin cookie.
    client.cookies.clear()
    assert (await client.get("/admin/api/clips")).status_code == 401


async def test_admin_table_pagination(client, db, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "admin_password", "Test@10")
    ok = await client.post("/admin/login", json={"username": "admin", "password": "Test@10"})
    client.cookies.update(ok.cookies)
    r = await client.get("/admin/api/table/users?offset=0&limit=10")
    assert r.status_code == 200
    body = r.json()
    assert {"rows", "total", "offset", "limit"} <= body.keys() and body["limit"] == 10


async def test_changelog_page_and_footer_link(client):
    landing = (await client.get("/")).text
    assert "/changelog" in landing
    page = await client.get("/changelog")
    assert page.status_code == 200 and "v1.8" in page.text and "SoNex" in page.text


async def test_sonex_web_pwa_served(client):
    landing = (await client.get("/")).text
    # Landing links to the web app but does NOT brand it "SoNex Web" — that name
    # only appears once you've opened the web app itself.
    assert "/app/" in landing and "SoNex Web" not in landing
    page = await client.get("/app/")
    assert page.status_code == 200 and "manifest.webmanifest" in page.text and "getUserMedia" in page.text
    assert "SoNex Web" in page.text  # branded inside the app
    assert "Demo player" not in page.text and "Live room state" not in page.text
    assert "SoNex TV" in page.text and "Bluetooth" in page.text and "Cast" in page.text
    assert "Log out?" in page.text and 'class="eye"' in page.text
    # Full app parity surface
    for feature in ["Calibrate", "Forgot password", "Change password", "Send feedback",
                    "Delete my data", "Room size", "serviceWorker", "dotlottie",
                    "getUserMedia", "WHISPER", "v1/auth/verify", "v1/auth/reset",
                    "v1/feedback", "v1/data/delete", "v1/consents", "v1/devices/register",
                    "v1/tv/pair", "v1/tv/command", "Pair your TV", "v1/settings", "pullSettings"]:
        assert feature in page.text, f"web app missing: {feature}"
    assert (await client.get("/app/manifest.webmanifest")).status_code == 200
    assert (await client.get("/app/sw.js")).status_code == 200
    assert (await client.get("/app/icon.svg")).status_code == 200


async def test_tv_cloud_relay_pair_and_command(client, db):
    from .conftest import login
    auth = await login(client, db, "tvweb@sonex.test", "longenough8")
    # TV registers its code
    reg = await client.post("/v1/tv/register", json={"code": "4321", "name": "Bravia"})
    key = reg.json()["tv_key"]
    hdr = {"X-Tv-Key": key}
    assert (await client.get("/v1/tv/poll", headers=hdr)).json()["paired"] is False
    # Wrong code rejected; right code pairs
    assert (await client.post("/v1/tv/pair", json={"code": "0000"}, headers=auth)).status_code == 404
    ok = await client.post("/v1/tv/pair", json={"code": "4321"}, headers=auth)
    assert ok.status_code == 200 and ok.json()["tv_name"] == "Bravia"
    assert (await client.get("/v1/tv/poll", headers=hdr)).json()["paired"] is True
    # Web queues a command; TV receives it exactly once
    assert (await client.post("/v1/tv/command", json={"action": "DUCK", "level": 30}, headers=auth)).status_code == 200
    cmds = (await client.get("/v1/tv/poll", headers=hdr)).json()["commands"]
    assert cmds == [{"action": "DUCK", "level": 30, "reason": "web"}]
    assert (await client.get("/v1/tv/poll", headers=hdr)).json()["commands"] == []


async def test_cross_device_settings_sync(client, db):
    from .conftest import login
    auth = await login(client, db, "sync@sonex.test", "longenough8")
    assert (await client.get("/v1/settings", headers=auth)).json() == {}
    r = await client.put("/v1/settings", json={"duck": 40, "room": "HALL"}, headers=auth)
    assert r.status_code == 200
    # merge, not replace
    await client.put("/v1/settings", json={"theme": "dark"}, headers=auth)
    got = (await client.get("/v1/settings", headers=auth)).json()
    assert got == {"duck": 40, "room": "HALL", "theme": "dark"}
    assert (await client.get("/v1/settings")).status_code == 401
