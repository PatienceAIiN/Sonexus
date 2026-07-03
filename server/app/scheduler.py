"""In-process daily scheduler for automatic model training.

Runs the training job every day at 02:00 IST (India Standard Time = UTC+5:30,
i.e. 20:30 UTC the previous day). No external cron or worker needed — a single
asyncio task sleeps until the next 02:00 IST and fires the job. Best-effort: if
the process restarts it simply recomputes the next fire time.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, time, timedelta, timezone

log = logging.getLogger("sonex.scheduler")

IST = timezone(timedelta(hours=5, minutes=30))
TRAIN_AT = time(hour=2, minute=0)  # 02:00 IST


def _seconds_until_next_run(now_utc: datetime) -> float:
    now_ist = now_utc.astimezone(IST)
    target = now_ist.replace(hour=TRAIN_AT.hour, minute=TRAIN_AT.minute, second=0, microsecond=0)
    if target <= now_ist:
        target += timedelta(days=1)
    return (target - now_ist).total_seconds()


async def _run_once() -> None:
    from .db import SessionLocal
    from .routers import admin
    from . import trainer_job
    try:
        async with SessionLocal() as db:
            report = await trainer_job.train_and_publish(db)
        admin.LAST_TRAIN.clear()
        admin.LAST_TRAIN.update(report)
        log.info("Nightly training done: %s", report)
    except Exception as exc:  # never let a bad run kill the loop
        log.warning("Nightly training failed: %s", exc)


async def _loop() -> None:
    while True:
        delay = _seconds_until_next_run(datetime.now(timezone.utc))
        log.info("Next auto-train in %.0f min", delay / 60)
        try:
            await asyncio.sleep(delay)
        except asyncio.CancelledError:
            break
        await _run_once()


_task: asyncio.Task | None = None


def start() -> None:
    global _task
    if _task is None or _task.done():
        _task = asyncio.create_task(_loop())


def stop() -> None:
    global _task
    if _task is not None:
        _task.cancel()
        _task = None
