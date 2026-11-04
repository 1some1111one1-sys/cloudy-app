"""Incremental backup and restore of app data between the remote device and TeraBox / MEGA.

How it works
- Android keeps app data private. On the emulator we use `adb root`, which exists on google_apis / AOSP images
  (NOT on Google Play images). Without root this service refuses to run instead of pretending.
- A manifest lists every file under the app's data directories with its size and modification time.
- A backup uploads ONLY files whose (size, mtime) differ from the previous backup's manifest, packed into tar chunks.
  Unchanged files point at chunks uploaded earlier, so the whole data directory is never re-uploaded.
- Restore replays the needed chunks oldest to newest, removes files that are not in the manifest, and fixes
  ownership. Apps that bind data to device keys, accounts or the Keystore may not work after a restore.
- Not backed up: /sdcard/Android/obb (large, normally re-downloadable) and anything the app marks as not readable.
"""
from __future__ import annotations

import json
import os
import shlex
import uuid
from typing import Optional

from backend.database.db import Database, now
from backend.storage.manager import StorageError, StorageManager
from backend.virtualization.adb import Adb, AdbError

CHUNK_BYTES = 64 * 1024 * 1024
BATCH_PATHS = 150
ROOT = "/CloudDroid/backups"


def data_roots(pkg: str) -> list[str]:
    return [f"/data/data/{pkg}", f"/sdcard/Android/data/{pkg}"]


def diff_manifests(previous: dict, current: dict) -> list[str]:
    """Paths that are new or changed since `previous`."""
    return sorted(p for p, meta in current.items()
                  if p not in previous or previous[p]["size"] != meta["size"] or previous[p]["mtime"] != meta["mtime"])


def batches(paths: list[str], sizes: dict[str, int]) -> list[list[str]]:
    out: list[list[str]] = [[]]
    total = 0
    for p in paths:
        if out[-1] and (len(out[-1]) >= BATCH_PATHS or total + sizes.get(p, 0) > CHUNK_BYTES):
            out.append([])
            total = 0
        out[-1].append(p)
        total += sizes.get(p, 0)
    return [b for b in out if b]


class BackupService:
    def __init__(self, db: Database, device_id: str, adb: Adb, storage: StorageManager, staging_dir: str):
        self.db, self.device_id, self.adb, self.storage, self.staging = db, device_id, adb, storage, staging_dir

    # ---------------------------------------------------------------- scan
    def scan(self, pkg: str) -> dict:
        manifest: dict[str, dict] = {}
        for root in data_roots(pkg):
            out = self.adb.shell(f"find {shlex.quote(root)} -type f -exec stat -c '%s %Y %n' {{}} + 2>/dev/null",
                                 check=False, timeout=300)
            for line in out.splitlines():
                parts = line.strip().split(" ", 2)
                if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit():
                    manifest[parts[2]] = {"size": int(parts[0]), "mtime": int(parts[1])}
        return manifest

    def history(self, pkg: str) -> list[dict]:
        rows = self.db.query("SELECT id, package_name, provider, created_at, size_bytes, files_changed, files_total, kind "
                             "FROM backups WHERE device_id = ? AND package_name = ? ORDER BY created_at DESC",
                             (self.device_id, pkg))
        return rows

    def _last(self, pkg: str, provider: str) -> Optional[dict]:
        return self.db.one("SELECT * FROM backups WHERE device_id = ? AND package_name = ? AND provider = ? "
                           "ORDER BY created_at DESC, id DESC LIMIT 1", (self.device_id, pkg, provider))

    # ---------------------------------------------------------------- backup
    def backup(self, pkg: str, provider: str) -> dict:
        if not self.adb.root():
            raise StorageError(400, "This device image does not allow root, so app data cannot be read. "
                                    "Use a google_apis or AOSP system image (not Google Play).")
        current = self.scan(pkg)
        if not current:
            raise StorageError(400, f"No readable data found for {pkg}. Launch the app once, or the app blocks access.")
        last = self._last(pkg, provider)
        previous = json.loads(last["manifest"]) if last else {}
        changed = diff_manifests(previous, current)
        bid = now().replace(":", "").replace("-", "") + "-" + uuid.uuid4().hex[:6]
        remote_dir = f"{ROOT}/{pkg}/{bid}"
        changed_set = set(changed)
        manifest = {p: {**meta, "chunk": previous[p]["chunk"] if p in previous and p not in changed_set else None}
                    for p, meta in current.items()}
        uploaded = 0
        os.makedirs(self.staging, exist_ok=True)
        for i, batch in enumerate(batches(changed, {p: current[p]["size"] for p in changed})):
            args = " ".join(shlex.quote(p.lstrip("/")) for p in batch)
            data = self.adb.exec_out(f"tar -cf - -C / {args} 2>/dev/null")
            chunk = f"{remote_dir}/chunk-{i:03d}.tar"
            local = os.path.join(self.staging, f"{bid}-{i}.tar")
            with open(local, "wb") as fh:
                fh.write(data)
            try:
                self.storage.upload(provider, chunk, local)
            finally:
                os.remove(local)
            uploaded += len(data)
            for p in batch:
                manifest[p]["chunk"] = chunk
        text = json.dumps(manifest)
        mlocal = os.path.join(self.staging, f"{bid}.manifest.json")
        with open(mlocal, "w", encoding="utf-8") as fh:
            fh.write(text)
        try:
            self.storage.upload(provider, f"{remote_dir}/manifest.json", mlocal)
        finally:
            os.remove(mlocal)
        kind = "full" if not previous else "incremental"
        self.db.execute(
            "INSERT INTO backups (id, device_id, package_name, provider, created_at, size_bytes, files_changed, files_total, "
            "kind, remote_dir, manifest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (bid, self.device_id, pkg, provider, now(), uploaded, len(changed), len(current), kind, remote_dir, text))
        return {"id": bid, "package_name": pkg, "provider": provider, "created_at": now(), "size_bytes": uploaded,
                "files_changed": len(changed), "files_total": len(current), "kind": kind}

    # ---------------------------------------------------------------- restore
    def restore(self, pkg: str, backup_id: str) -> None:
        row = self.db.one("SELECT * FROM backups WHERE id = ? AND device_id = ? AND package_name = ?",
                          (backup_id, self.device_id, pkg))
        if row is None:
            raise StorageError(404, "Backup not found")
        if not self.adb.root():
            raise StorageError(400, "This device image does not allow root, so app data cannot be restored.")
        manifest: dict = json.loads(row["manifest"])
        provider = row["provider"]
        chunks = sorted({m["chunk"] for m in manifest.values() if m.get("chunk")})  # names sort oldest -> newest
        uid = self.adb.uid(pkg)
        self.adb.force_stop(pkg)
        self.adb.clear_data(pkg)
        os.makedirs(self.staging, exist_ok=True)
        # Chunk paths contain the backup id (a UTC timestamp), so sorting by (backup id, chunk number) is chronological.
        for chunk in sorted(chunks, key=lambda c: (c.rsplit("/", 2)[-2], c.rsplit("/", 1)[-1])):
            local = os.path.join(self.staging, f"restore-{uuid.uuid4().hex[:8]}.tar")
            try:
                self.storage.download(provider, chunk, local)
                with open(local, "rb") as fh:
                    self.adb.shell_stdin("tar -xf - -C /", fh.read())
            finally:
                if os.path.exists(local):
                    os.remove(local)
        # Remove files that did not exist at backup time.
        current = self.scan(pkg)
        for p in sorted(set(current) - set(manifest)):
            self.adb.shell(f"rm -f {shlex.quote(p)}", check=False, timeout=15)
        if uid is not None:
            for root in data_roots(pkg):
                self.adb.shell(f"chown -R {uid}:{uid} {shlex.quote(root)} 2>/dev/null; restorecon -R {shlex.quote(root)} 2>/dev/null",
                               check=False, timeout=120)
