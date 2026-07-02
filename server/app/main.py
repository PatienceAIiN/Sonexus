import logging

from fastapi import FastAPI

from .routers import auth, device_api, portal, public

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="SoNex API", version="1.0")
app.include_router(auth.router, prefix="/v1")
app.include_router(device_api.router, prefix="/v1")
app.include_router(portal.router, prefix="/v1")
app.include_router(public.router)  # /, /download/*, /v1/app/releases


@app.get("/healthz")
async def healthz():
    return {"ok": True}
