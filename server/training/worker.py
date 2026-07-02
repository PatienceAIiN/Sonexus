"""Job-queue worker: python -m training.worker

Pops jobs from the Redis queue and runs them (currently only `train_home`).
"""
import asyncio
import logging

from app.db import SessionLocal
from app.redisq import get_redis, pop_job, set_job_status
from app.storage import get_storage
from .pipeline import run_training

log = logging.getLogger("sonex.worker")


async def handle_job(job: dict) -> None:
    r = get_redis()
    await set_job_status(r, job["id"], "running")
    try:
        if job["name"] == "train_home":
            async with SessionLocal() as db:
                model = await run_training(db, get_storage(), job["kwargs"]["home_id"])
            await set_job_status(r, job["id"], "done", model_id=model.id, sha256=model.sha256)
        else:
            await set_job_status(r, job["id"], "failed", error=f"unknown job {job['name']}")
    except Exception as exc:
        log.exception("job %s failed", job["id"])
        await set_job_status(r, job["id"], "failed", error=str(exc))


async def main() -> None:
    logging.basicConfig(level=logging.INFO)
    log.info("worker started")
    r = get_redis()
    while True:
        job = await pop_job(r)
        if job is not None:
            await handle_job(job)


if __name__ == "__main__":
    asyncio.run(main())
