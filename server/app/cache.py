"""Tiny in-process TTL cache for hot read paths.

The data cached here (release manifests, presigned URLs, device-key lookups,
dashboard stats) tolerates seconds of staleness, and one API instance serves
all traffic — so a dict beats a network hop to Redis for these.
"""
import time
from typing import Any, Callable

_store: dict[str, tuple[float, Any]] = {}


def get(key: str) -> Any | None:
    hit = _store.get(key)
    if hit is None:
        return None
    expires, value = hit
    if time.monotonic() > expires:
        _store.pop(key, None)
        return None
    return value


def put(key: str, value: Any, ttl_sec: float) -> Any:
    # Opportunistic sweep so the dict can't grow without bound.
    if len(_store) > 4096:
        now = time.monotonic()
        for k in [k for k, (exp, _) in _store.items() if exp < now]:
            _store.pop(k, None)
    _store[key] = (time.monotonic() + ttl_sec, value)
    return value


def invalidate(prefix: str = "") -> None:
    for k in [k for k in _store if k.startswith(prefix)]:
        _store.pop(k, None)


async def get_or_load(key: str, ttl_sec: float, loader: Callable) -> Any:
    cached = get(key)
    if cached is not None:
        return cached
    return put(key, await loader(), ttl_sec)
