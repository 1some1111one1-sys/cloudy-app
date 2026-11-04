"""REST API. Every route except /api/health needs `Authorization: Bearer <CLOUDDROID_TOKEN>`.

NOTE: this module needs FastAPI, which could not be installed in the development workspace, so the HTTP layer
has NOT been run by the automated tests. The services behind it are tested; see documentation/TESTING.md for the
manual API check (scripts/smoke_test.py).
"""
from __future__ import annotations

import hmac
import os
import uuid
from typing import Optional

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel

from backend.services.monitor import host_resources
from backend.storage.manager import PROVIDERS, StorageError

router = APIRouter()


def c(request: Request):
    return request.app.state.c


def auth(request: Request, authorization: Optional[str] = Header(default=None)) -> None:
    token = (authorization or "").removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token.encode(), c(request).settings.token.encode()):
        raise HTTPException(401, "Invalid access token")


def check_provider(provider: str) -> str:
    if provider not in PROVIDERS:
        raise HTTPException(404, "Unknown provider. Only TeraBox and MEGA are supported.")
    return provider


# ---------------------------------------------------------------- health
@router.get("/api/health")
def health():
    return {"ok": True}


# ---------------------------------------------------------------- cloud storage
class TeraBoxSession(BaseModel):
    cookie: str
    site: str = "https://www.1024terabox.com"


class MegaSession(BaseModel):
    email: str
    password: str


@router.post("/api/storage/terabox/session", dependencies=[Depends(auth)])
def connect_terabox(body: TeraBoxSession, request: Request):
    c(request).storage.connect_terabox(body.cookie, body.site)
    return {"ok": True}


@router.post("/api/storage/mega/session", dependencies=[Depends(auth)])
def connect_mega(body: MegaSession, request: Request):
    c(request).storage.connect_mega(body.email, body.password)
    return {"ok": True}


@router.delete("/api/storage/{provider}/session", dependencies=[Depends(auth)])
def disconnect(provider: str, request: Request):
    c(request).storage.disconnect(check_provider(provider))
    return {"ok": True}


@router.get("/api/storage/{provider}/status", dependencies=[Depends(auth)])
def storage_status(provider: str, request: Request):
    return c(request).storage.status(check_provider(provider))


@router.get("/api/storage/{provider}/list", dependencies=[Depends(auth)])
def storage_list(provider: str, request: Request, path: str = "/"):
    entries = c(request).storage.list(check_provider(provider), path)
    return [{"path": e.path, "name": e.name, "is_dir": e.is_dir, "size": max(e.size, 0)} for e in entries]


@router.post("/api/storage/{provider}/mkdir", dependencies=[Depends(auth)])
def storage_mkdir(provider: str, request: Request, path: str):
    c(request).storage.mkdir(check_provider(provider), path)
    return {"ok": True}


@router.delete("/api/storage/{provider}/file", dependencies=[Depends(auth)])
def storage_delete(provider: str, request: Request, path: str):
    c(request).storage.delete(check_provider(provider), path)
    return {"ok": True}


@router.put("/api/storage/{provider}/file", dependencies=[Depends(auth)])
async def storage_upload(provider: str, request: Request, path: str):
    """The phone streams the file body; it is spooled to staging, then uploaded to the drive."""
    check_provider(provider)
    cont = c(request)
    name = os.path.join(cont.settings.staging_dir, f"up-{uuid.uuid4().hex}")
    try:
        with open(name, "wb") as fh:
            async for chunk in request.stream():
                fh.write(chunk)
        await run_in_threadpool(cont.storage.upload, provider, path, name)
    finally:
        if os.path.exists(name):
            os.remove(name)
    return {"ok": True}


# ---------------------------------------------------------------- device
@router.get("/api/device", dependencies=[Depends(auth)])
def device(request: Request):
    cont = c(request)
    return cont.emulator.info(cont.device_id)


@router.post("/api/device/{action}", dependencies=[Depends(auth)])
def device_action(action: str, request: Request):
    cont = c(request)
    if action == "start":
        cont.emulator.start()
    elif action == "stop":
        for sid in list(cont.streams.sessions):
            cont.streams.end(sid)
        cont.emulator.stop()
    elif action == "restart":
        for sid in list(cont.streams.sessions):
            cont.streams.end(sid)
        cont.emulator.stop()
        cont.emulator.start()
    else:
        raise HTTPException(404, "Unknown action")
    return cont.emulator.info(cont.device_id)


@router.get("/api/resources", dependencies=[Depends(auth)])
def resources(request: Request):
    cont = c(request)
    return {"host": host_resources(cont.settings.data_dir), "device": cont.emulator.info(cont.device_id)}


# ---------------------------------------------------------------- apps
class InstallBody(BaseModel):
    provider: str
    path: str


class BackupBody(BaseModel):
    provider: str
    backup_id: Optional[str] = None


@router.get("/api/apps", dependencies=[Depends(auth)])
def apps(request: Request):
    return c(request).apps.list_apps()


@router.post("/api/apps/install", dependencies=[Depends(auth)])
def install(body: InstallBody, request: Request):
    return c(request).apps.submit_install(check_provider(body.provider), body.path).public()


@router.get("/api/jobs/{job_id}", dependencies=[Depends(auth)])
def job(job_id: str, request: Request):
    j = c(request).apps.job(job_id)
    if j is None:
        raise HTTPException(404, "Unknown job")
    return j.public()


@router.post("/api/apps/{pkg}/stop", dependencies=[Depends(auth)])
def stop_app(pkg: str, request: Request):
    c(request).apps.stop(pkg)
    return {"ok": True}


@router.delete("/api/apps/{pkg}", dependencies=[Depends(auth)])
def uninstall(pkg: str, request: Request):
    c(request).apps.uninstall(pkg)
    return {"ok": True}


@router.post("/api/apps/{pkg}/backup", dependencies=[Depends(auth)])
def backup(pkg: str, body: BackupBody, request: Request):
    return c(request).backups.backup(pkg, check_provider(body.provider))


@router.get("/api/apps/{pkg}/backups", dependencies=[Depends(auth)])
def backups(pkg: str, request: Request):
    return c(request).backups.history(pkg)


@router.post("/api/apps/{pkg}/restore", dependencies=[Depends(auth)])
def restore(pkg: str, body: BackupBody, request: Request):
    if not body.backup_id:
        raise HTTPException(400, "backup_id is required")
    c(request).backups.restore(pkg, body.backup_id)
    return {"ok": True}


# ---------------------------------------------------------------- streaming
class StreamBody(BaseModel):
    package_name: Optional[str] = None
    profile: str = "BALANCED"


@router.post("/api/stream/session", dependencies=[Depends(auth)])
def stream_create(body: StreamBody, request: Request):
    return c(request).streams.create(body.package_name, body.profile)


@router.delete("/api/stream/session/{session_id}", dependencies=[Depends(auth)])
def stream_end(session_id: str, request: Request):
    c(request).streams.end(session_id)
    return {"ok": True}
