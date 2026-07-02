"""Redis helpers: device state, rate limiting, and a minimal RQ-style job queue."""
import json
import uuid
from datetime import datetime, timezone

import redis.asyncio as aioredis
from fastapi import Depends, HTTPException, Request

from .config import settings

JOB_QUEUE_KEY = "sonex:jobs"

_redis: aioredis.Redis | None = None


def get_redis() -> aioredis.Redis:
    global _redis
    if _redis is None:
        _redis = aioredis.from_url(settings.redis_url, decode_responses=True)
    return _redis


# ---- device state ----

async def set_device_state(r: aioredis.Redis, device_id: int, state: str | None = None) -> None:
    mapping = {"last_seen": datetime.now(timezone.utc).isoformat()}
    if state:
        mapping["state"] = state
    await r.hset(f"sonex:device:{device_id}", mapping=mapping)


async def get_device_state(r: aioredis.Redis, device_id: int) -> dict:
    return await r.hgetall(f"sonex:device:{device_id}") or {}


# ---- rate limiting (fixed window) ----

async def check_rate_limit(r: aioredis.Redis, identity: str) -> None:
    key = f"sonex:rl:{identity}"
    count = await r.incr(key)
    if count == 1:
        await r.expire(key, settings.rate_limit_window)
    if count > settings.rate_limit_requests:
        raise HTTPException(status_code=429, detail="Rate limit exceeded")


async def rate_limited(request: Request) -> None:
    identity = request.headers.get("x-device-key") or request.headers.get("authorization") or (
        request.client.host if request.client else "anon"
    )
    await check_rate_limit(get_redis(), f"{request.url.path}:{identity[:64]}")


# ---- minimal RQ-style job queue ----

async def enqueue_job(r: aioredis.Redis, name: str, **kwargs) -> str:
    job_id = uuid.uuid4().hex
    await r.hset(f"sonex:job:{job_id}", mapping={"status": "queued", "name": name, "kwargs": json.dumps(kwargs)})
    await r.lpush(JOB_QUEUE_KEY, json.dumps({"id": job_id, "name": name, "kwargs": kwargs}))
    return job_id


async def pop_job(r: aioredis.Redis, timeout: int = 5) -> dict | None:
    item = await r.brpop(JOB_QUEUE_KEY, timeout=timeout)
    if item is None:
        return None
    return json.loads(item[1])


async def set_job_status(r: aioredis.Redis, job_id: str, status: str, **extra) -> None:
    await r.hset(f"sonex:job:{job_id}", mapping={"status": status, **{k: str(v) for k, v in extra.items()}})
