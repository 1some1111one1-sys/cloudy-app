"""Unified cloud storage manager: exactly two providers, TeraBox and MEGA."""
from __future__ import annotations

import json
from typing import Callable, Optional, Protocol

from backend.database.db import Database, now
from backend.storage.mega import MegaClient, MegaError
from backend.storage.terabox import DEFAULT_SITE, Entry, TeraBoxClient, TeraBoxError

PROVIDERS = ("terabox", "mega")
Progress = Optional[Callable[[int, int], None]]


class StorageError(Exception):
    """Provider-neutral error: status 401 means the account session expired / is missing."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class SecretBox(Protocol):
    def encrypt(self, plain: str) -> str: ...
    def decrypt(self, token: str) -> str: ...


class FernetBox:
    """Encrypts stored cloud sessions. Needs the `cryptography` package and CLOUDDROID_SECRET_KEY."""

    def __init__(self, key: str):
        if not key:
            raise ValueError("CLOUDDROID_SECRET_KEY is not set. Generate one with: "
                             "python -c \"from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())\"")
        from cryptography.fernet import Fernet  # noqa: PLC0415
        self._f = Fernet(key.encode())

    def encrypt(self, plain: str) -> str:
        return self._f.encrypt(plain.encode()).decode()

    def decrypt(self, token: str) -> str:
        return self._f.decrypt(token.encode()).decode()


def _wrap(fn):
    def inner(*a, **k):
        try:
            return fn(*a, **k)
        except (TeraBoxError, MegaError) as e:
            raise StorageError(e.status, str(e)) from e
    return inner


class StorageManager:
    def __init__(self, db: Database, user_id: str, box: SecretBox, mega: Optional[MegaClient] = None,
                 terabox_factory: Callable[[str, str], TeraBoxClient] = lambda cookie, site: TeraBoxClient(cookie, site)):
        self.db, self.user_id, self.box = db, user_id, box
        self.mega = mega or MegaClient()
        self._tb_factory = terabox_factory
        self._tb: Optional[TeraBoxClient] = None
        self._mega_connected = False
        self._restore()

    # ---------------------------------------------------------------- sessions
    def _restore(self) -> None:
        for row in self.db.query("SELECT provider, secret, meta FROM cloud_accounts WHERE user_id = ?", (self.user_id,)):
            try:
                if row["provider"] == "terabox" and row["secret"]:
                    meta = json.loads(row["meta"] or "{}")
                    self._tb = self._tb_factory(self.box.decrypt(row["secret"]), meta.get("site", DEFAULT_SITE))
                elif row["provider"] == "mega":
                    self._mega_connected = True
            except Exception:  # an undecryptable or invalid stored session is simply treated as "not connected"
                pass

    def _save(self, provider: str, secret: str, meta: dict) -> None:
        self.db.execute(
            "INSERT INTO cloud_accounts (user_id, provider, secret, meta, updated_at) VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT (user_id, provider) DO UPDATE SET secret = excluded.secret, meta = excluded.meta, "
            "updated_at = excluded.updated_at",
            (self.user_id, provider, secret, json.dumps(meta), now()))

    def connect_terabox(self, cookie: str, site: str) -> None:
        try:
            client = self._tb_factory(cookie, site)
        except ValueError as e:
            raise StorageError(400, str(e)) from e
        _wrap(client.quota)()  # proves the cookie works before it is kept
        self._tb = client
        self._save("terabox", self.box.encrypt(cookie), {"site": site})

    def connect_mega(self, email: str, password: str) -> None:
        _wrap(self.mega.login)(email, password)
        self._mega_connected = True
        self._save("mega", "", {"email": email})

    def disconnect(self, provider: str) -> None:
        self._check(provider)
        if provider == "terabox":
            self._tb = None
        else:
            _wrap(self.mega.logout)()
            self._mega_connected = False
        self.db.execute("DELETE FROM cloud_accounts WHERE user_id = ? AND provider = ?", (self.user_id, provider))

    @staticmethod
    def _check(provider: str) -> None:
        if provider not in PROVIDERS:
            raise StorageError(404, f"Unknown provider {provider}. Only TeraBox and MEGA are supported.")

    def _expire(self, provider: str) -> None:
        if provider == "terabox":
            self._tb = None
        else:
            self._mega_connected = False
        self.db.execute("DELETE FROM cloud_accounts WHERE user_id = ? AND provider = ?", (self.user_id, provider))

    def _call(self, provider: str, fn: Callable):
        self._check(provider)
        if (provider == "terabox" and self._tb is None) or (provider == "mega" and not self._mega_connected):
            raise StorageError(401, f"{provider} is not connected")
        try:
            return fn()
        except (TeraBoxError, MegaError) as e:
            if e.status == 401:
                self._expire(provider)
            raise StorageError(e.status, str(e)) from e

    # ---------------------------------------------------------------- operations
    def status(self, provider: str) -> dict:
        self._check(provider)
        base = {"provider": provider, "connected": False, "account": None, "used_bytes": 0, "total_bytes": 0, "error": None}
        try:
            if provider == "terabox":
                used, total = self._call(provider, lambda: self._tb.quota())
            else:
                used, total = self._call(provider, lambda: self.mega.quota())
        except StorageError as e:
            if e.status != 401:
                base["error"] = str(e)
            return base
        base.update(connected=True, used_bytes=max(used, 0), total_bytes=max(total, 0))
        if provider == "mega":
            base["account"] = self.mega.account()
        else:
            row = self.db.one("SELECT meta FROM cloud_accounts WHERE user_id = ? AND provider = 'terabox'", (self.user_id,))
            base["account"] = json.loads(row["meta"]).get("site", "").replace("https://", "") if row else None
        return base

    def list(self, provider: str, path: str) -> list[Entry]:
        return self._call(provider, lambda: self._tb.list(path) if provider == "terabox" else self.mega.list(path))

    def mkdir(self, provider: str, path: str) -> None:
        self._call(provider, lambda: self._tb.mkdir(path) if provider == "terabox" else self.mega.mkdir(path))

    def delete(self, provider: str, path: str) -> None:
        self._call(provider, lambda: self._tb.delete(path) if provider == "terabox" else self.mega.delete(path))

    def exists(self, provider: str, path: str) -> Optional[Entry]:
        parent = path.rsplit("/", 1)[0] or "/"
        try:
            for e in self.list(provider, parent):
                if e.path == path:
                    return e
        except StorageError as e:
            if e.status != 404:
                raise
        return None

    def ensure_dir(self, provider: str, path: str) -> None:
        parts = [p for p in path.split("/") if p]
        cur = ""
        for p in parts:
            cur += "/" + p
            if self.exists(provider, cur) is None:
                self.mkdir(provider, cur)

    def upload(self, provider: str, path: str, local: str, progress: Progress = None) -> None:
        self.ensure_dir(provider, path.rsplit("/", 1)[0] or "/")
        self._call(provider, lambda: self._tb.upload(path, local, progress) if provider == "terabox"
                   else self.mega.upload(path, local, progress))

    def download(self, provider: str, path: str, local: str, progress: Progress = None, size: int = -1) -> None:
        self._call(provider, lambda: self._tb.download(path, local, progress) if provider == "terabox"
                   else self.mega.download(path, local, size, progress))
