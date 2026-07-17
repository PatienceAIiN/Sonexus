"""Publish the built APKs + release manifest to object storage (Cloudinary/R2).

The phone's in-app updater and /download read apk/releases.json + the APKs from
storage, so a release = upload these three files. Run from the server/ dir with
storage creds in the environment (same ones the server uses):

    cd server
    R2_ACCOUNT_ID=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=... R2_BUCKET=sonex \
    CLOUDINARY_CLOUD_NAME=... CLOUDINARY_API_KEY=... CLOUDINARY_API_SECRET=... \
    .venv/bin/python scripts/publish_release.py

(Only the backend you actually use needs its creds; STORAGE_BACKEND defaults to failover.)
"""
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from app.storage import get_storage  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "apk"))
FILES = [
    ("sonex-mobile.apk", "apk/sonex-mobile.apk", "application/vnd.android.package-archive"),
    ("sonex-tv.apk", "apk/sonex-tv.apk", "application/vnd.android.package-archive"),
    ("releases.json", "apk/releases.json", "application/json"),
]


def main() -> int:
    st = get_storage()
    for local, key, ct in FILES:
        path = os.path.join(ROOT, local)
        data = open(path, "rb").read()
        backend = st.put(key, data, ct)
        print(f"  uploaded {key:<24} {len(data):>10,} bytes  via {backend}")
    print("Release published. The app's updater will offer v5.0 on next check.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
