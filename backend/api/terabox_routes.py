"""FastAPI routes for the TeraBox session (cookie from the phone's WebView sign-in).

NOTE: not yet run — FastAPI could not be installed in the development workspace. The storage client these
routes call (backend/storage/terabox.py) is tested separately.
"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from backend.storage.terabox import TeraBoxClient, TeraBoxError

router = APIRouter(prefix="/api/storage/terabox", tags=["terabox"])

# Single-user placeholder; Stage 2 replaces this with per-user storage in PostgreSQL (cookie encrypted at rest).
_sessions: dict[str, TeraBoxClient] = {}
_USER = "default"


class SessionIn(BaseModel):
    cookie: str
    site: str = "https://www.1024terabox.com"


def _client() -> TeraBoxClient:
    c = _sessions.get(_USER)
    if c is None:
        raise HTTPException(401, "TeraBox is not connected")
    return c


def _fail(e: TeraBoxError):
    if e.auth_expired:
        _sessions.pop(_USER, None)
    raise HTTPException(e.status if 400 <= e.status < 600 else 502, str(e))


@router.post("/session")
def connect(body: SessionIn):
    try:
        c = TeraBoxClient(body.cookie, body.site)
        c.quota()  # proves the cookie works before we keep it
    except ValueError as e:
        raise HTTPException(400, str(e))
    except TeraBoxError as e:
        _fail(e)
    _sessions[_USER] = c
    return {"ok": True}


@router.delete("/session")
def disconnect():
    _sessions.pop(_USER, None)
    return {"ok": True}


@router.get("/status")
def status():
    c = _sessions.get(_USER)
    if c is None:
        return {"provider": "terabox", "connected": False}
    try:
        used, total = c.quota()
    except TeraBoxError as e:
        if e.auth_expired:
            _sessions.pop(_USER, None)
        return {"provider": "terabox", "connected": False, "error": str(e)}
    return {"provider": "terabox", "connected": True, "used_bytes": used, "total_bytes": total}


@router.get("/list")
def list_dir(path: str = "/"):
    try:
        return [{"path": e.path, "name": e.name, "is_dir": e.is_dir, "size": max(e.size, 0)}
                for e in _client().list(path)]
    except TeraBoxError as e:
        _fail(e)
