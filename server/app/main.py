import logging

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.gzip import GZipMiddleware

from . import scheduler
from .routers import admin, auth, device_api, portal, public

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    scheduler.start()   # nightly auto-train at 02:00 IST
    try:
        yield
    finally:
        scheduler.stop()


app = FastAPI(title="SoNex API", version="1.0", lifespan=lifespan)
app.add_middleware(GZipMiddleware, minimum_size=512)
app.include_router(auth.router, prefix="/v1")
app.include_router(device_api.router, prefix="/v1")
app.include_router(portal.router, prefix="/v1")
app.include_router(public.router)  # /, /download/*, /v1/app/releases
app.include_router(admin.router)  # /admin dashboard
app.mount("/app", StaticFiles(directory=Path(__file__).resolve().parent.parent / "webapp", html=True), name="webapp")  # SoNex Web PWA


@app.get("/healthz")
async def healthz():
    return {"ok": True}
