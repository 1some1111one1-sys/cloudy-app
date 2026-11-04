"""CloudDroid backend entry point.   uvicorn backend.main:app --host 0.0.0.0 --port 8000

Put a TLS reverse proxy (Caddy / nginx) in front: the phone sends an access token and, for MEGA, a password.
"""
from __future__ import annotations

import asyncio
import hmac
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, WebSocket
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from backend import config
from backend.api.routes import router
from backend.services.container import build
from backend.storage.manager import StorageError
from backend.virtualization.adb import AdbError


@asynccontextmanager
async def lifespan(app: FastAPI):
    cont = build(config.load())
    app.state.c = cont

    async def reaper():
        while True:
            await asyncio.sleep(30)
            await run_in_threadpool(cont.streams.reap)
    task = asyncio.create_task(reaper())
    try:
        yield
    finally:
        task.cancel()
        for sid in list(cont.streams.sessions):
            cont.streams.end(sid)


app = FastAPI(title="CloudDroid", version="0.1.0", lifespan=lifespan)
app.include_router(router)


@app.exception_handler(StorageError)
async def storage_error(_: Request, e: StorageError):
    return JSONResponse({"detail": str(e)}, status_code=e.status if 400 <= e.status < 600 else 502)


@app.exception_handler(AdbError)
async def adb_error(_: Request, e: AdbError):
    return JSONResponse({"detail": str(e)}, status_code=502)


@app.websocket("/ws/signal/{session_id}")
async def signal(ws: WebSocket, session_id: str, token: str = ""):
    cont = ws.app.state.c
    if not hmac.compare_digest(token.encode(), cont.settings.token.encode()):
        await ws.close(code=4401)
        return
    await cont.streams.signal(session_id, ws)
