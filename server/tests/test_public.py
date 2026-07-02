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
