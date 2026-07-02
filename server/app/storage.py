"""Object storage abstraction.

One interface (put/get/url/delete). Primary: Cloudinary. Automatic fallback to
Cloudflare R2 (S3 API) when the primary fails. Logs which backend served each
object. A LocalDiskBackend is provided for dev/tests.
"""
import logging
import os
from pathlib import Path
from typing import Protocol

from .config import settings

log = logging.getLogger("sonex.storage")


class StorageBackend(Protocol):
    name: str

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> None: ...
    def get(self, key: str) -> bytes: ...
    def url(self, key: str) -> str: ...
    def delete(self, key: str) -> None: ...


class LocalDiskBackend:
    name = "local"

    def __init__(self, root: str | None = None):
        self.root = Path(root or settings.local_storage_dir)

    def _path(self, key: str) -> Path:
        p = (self.root / key).resolve()
        if not str(p).startswith(str(self.root.resolve())):
            raise ValueError("invalid key")
        return p

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> None:
        p = self._path(key)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)

    def get(self, key: str) -> bytes:
        return self._path(key).read_bytes()

    def url(self, key: str) -> str:
        return f"file://{self._path(key)}"

    def exists(self, key: str) -> bool:
        return os.path.exists(self._path(key))

    def delete(self, key: str) -> None:
        try:
            os.remove(self._path(key))
        except FileNotFoundError:
            pass


class CloudinaryBackend:
    name = "cloudinary"

    def __init__(self):
        import cloudinary

        cloudinary.config(
            cloud_name=settings.cloudinary_cloud_name,
            api_key=settings.cloudinary_api_key,
            api_secret=settings.cloudinary_api_secret,
            secure=True,
        )

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> None:
        import cloudinary.uploader

        cloudinary.uploader.upload(data, public_id=key, resource_type="raw", overwrite=True)

    def get(self, key: str) -> bytes:
        import httpx

        resp = httpx.get(self.url(key), timeout=30)
        resp.raise_for_status()
        return resp.content

    def url(self, key: str) -> str:
        import cloudinary.utils

        url, _ = cloudinary.utils.cloudinary_url(key, resource_type="raw", sign_url=True)
        return url

    def exists(self, key: str) -> bool:
        import cloudinary.api

        try:
            cloudinary.api.resource(key, resource_type="raw")
            return True
        except Exception:
            return False

    def delete(self, key: str) -> None:
        import cloudinary.uploader

        cloudinary.uploader.destroy(key, resource_type="raw")


class R2Backend:
    name = "r2"

    def __init__(self):
        import boto3

        self.client = boto3.client(
            "s3",
            endpoint_url=f"https://{settings.r2_account_id}.r2.cloudflarestorage.com",
            aws_access_key_id=settings.r2_access_key_id,
            aws_secret_access_key=settings.r2_secret_access_key,
            region_name="auto",
        )
        self.bucket = settings.r2_bucket

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> None:
        self.client.put_object(Bucket=self.bucket, Key=key, Body=data, ContentType=content_type)

    def get(self, key: str) -> bytes:
        return self.client.get_object(Bucket=self.bucket, Key=key)["Body"].read()

    def url(self, key: str) -> str:
        return self.client.generate_presigned_url(
            "get_object", Params={"Bucket": self.bucket, "Key": key}, ExpiresIn=3600
        )

    def exists(self, key: str) -> bool:
        try:
            self.client.head_object(Bucket=self.bucket, Key=key)
            return True
        except Exception:
            return False

    def delete(self, key: str) -> None:
        self.client.delete_object(Bucket=self.bucket, Key=key)


class FailoverStorage:
    """Tries the primary backend, falls back to the secondary on any failure."""

    def __init__(self, primary: StorageBackend, fallback: StorageBackend):
        self.primary = primary
        self.fallback = fallback

    def _attempt(self, op: str, key: str, fn_name: str, *args, **kwargs):
        for backend in (self.primary, self.fallback):
            try:
                result = getattr(backend, fn_name)(*args, **kwargs)
                log.info("storage %s key=%s backend=%s", op, key, backend.name)
                return result, backend.name
            except Exception:
                log.warning("storage %s key=%s backend=%s FAILED, trying fallback", op, key, backend.name, exc_info=True)
        raise RuntimeError(f"All storage backends failed for {op} {key}")

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> str:
        """Returns the name of the backend that stored the object."""
        _, backend = self._attempt("put", key, "put", key, data, content_type)
        return backend

    def get(self, key: str) -> bytes:
        data, _ = self._attempt("get", key, "get", key)
        return data

    def url(self, key: str) -> str:
        # Unlike get/put, generating a URL never *fails* on a backend that
        # doesn't hold the object (Cloudinary just mints a dead link). Ask
        # each backend whether it actually HAS the object first.
        for backend in (self.primary, self.fallback):
            try:
                if backend.exists(key):
                    log.info("storage url key=%s backend=%s", key, backend.name)
                    return backend.url(key)
            except Exception:
                continue
        # Nothing confirmed ownership — fall back to the old best-effort path.
        url, _ = self._attempt("url", key, "url", key)
        return url

    def delete(self, key: str) -> None:
        # Best-effort delete on both backends (object may live on either).
        deleted_any = False
        for backend in (self.primary, self.fallback):
            try:
                backend.delete(key)
                deleted_any = True
                log.info("storage delete key=%s backend=%s", key, backend.name)
            except Exception:
                log.warning("storage delete key=%s backend=%s failed", key, backend.name)
        if not deleted_any:
            raise RuntimeError(f"All storage backends failed for delete {key}")


_storage: FailoverStorage | None = None


def get_storage() -> FailoverStorage:
    global _storage
    if _storage is None:
        if settings.storage_backend == "local":
            local = LocalDiskBackend()
            _storage = FailoverStorage(local, local)
        else:
            _storage = FailoverStorage(CloudinaryBackend(), R2Backend())
    return _storage
