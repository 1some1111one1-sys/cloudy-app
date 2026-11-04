"""APK installation pipeline and app management on the remote device."""
from __future__ import annotations

import os
import shutil
import threading
import uuid
from dataclasses import asdict, dataclass
from typing import Callable, Optional

from backend.database.db import Database, now
from backend.services.apk import ApkInfo, inspect_apk
from backend.storage.manager import StorageError, StorageManager
from backend.virtualization.adb import Adb, AdbError
from backend.virtualization.emulator import EmulatorManager


@dataclass
class Job:
    id: str
    state: str = "queued"      # queued | downloading | inspecting | installing | done | failed
    progress: float = 0.0
    package_name: Optional[str] = None
    label: Optional[str] = None
    error: Optional[str] = None

    def public(self) -> dict:
        return asdict(self)


class AppService:
    def __init__(self, db: Database, device_id: str, adb: Adb, emulator: EmulatorManager, storage: StorageManager,
                 staging_dir: str, aapt2: str = "aapt2",
                 inspector: Callable[[str], ApkInfo] = None, run_async: bool = True):
        self.db, self.device_id, self.adb, self.emulator, self.storage = db, device_id, adb, emulator, storage
        self.staging = staging_dir
        self.inspector = inspector or (lambda p: inspect_apk(p, aapt2))
        self.run_async = run_async
        self.jobs: dict[str, Job] = {}
        self._lock = threading.Lock()
        self._size_cache: dict[str, tuple[float, int]] = {}
        os.makedirs(self.staging, exist_ok=True)

    # ---------------------------------------------------------------- install
    def submit_install(self, provider: str, path: str) -> Job:
        if not path.lower().endswith(".apk"):
            raise StorageError(400, "Only single .apk files are supported (not .xapk, .apks or OBB bundles).")
        job = Job(id=uuid.uuid4().hex[:12])
        self.jobs[job.id] = job
        if self.run_async:
            threading.Thread(target=self._run, args=(job, provider, path), daemon=True).start()
        else:
            self._run(job, provider, path)
        return job

    def job(self, job_id: str) -> Optional[Job]:
        return self.jobs.get(job_id)

    def _run(self, job: Job, provider: str, path: str) -> None:
        local = os.path.join(self.staging, f"{job.id}.apk")
        try:
            job.state = "downloading"
            entry = self.storage.exists(provider, path)
            if entry is None:
                raise StorageError(404, f"Not found on {provider}: {path}")

            def prog(done: int, total: int) -> None:
                job.progress = 0.8 * (done / total) if total > 0 else job.progress
            self.storage.download(provider, path, local, prog, size=entry.size)

            job.state, job.progress = "inspecting", 0.82
            info = self.inspector(local)
            job.package_name, job.label = info.package, info.label

            job.state, job.progress = "installing", 0.85
            self.emulator.ensure_running()
            self.db.execute(
                "INSERT INTO apps (device_id, package_name, label, version_name, status, installed_at, source_provider, source_path) "
                "VALUES (?, ?, ?, ?, 'installing', ?, ?, ?) ON CONFLICT (device_id, package_name) DO UPDATE SET "
                "label = excluded.label, version_name = excluded.version_name, status = 'installing', "
                "source_provider = excluded.source_provider, source_path = excluded.source_path",
                (self.device_id, info.package, info.label, info.version_name, now(), provider, path))
            self.adb.install(local)
            self.db.execute("UPDATE apps SET status = 'installed' WHERE device_id = ? AND package_name = ?",
                            (self.device_id, info.package))
            job.state, job.progress = "done", 1.0
            if info.warnings:
                job.error = " ".join(info.warnings)  # shown as a note; the install itself succeeded
        except (StorageError, AdbError) as e:
            self._fail(job, str(e))
        except Exception as e:  # noqa: BLE001 - never leave a job stuck in a running state
            self._fail(job, f"Unexpected error: {e}")
        finally:
            try:
                os.remove(local)
            except OSError:
                pass

    def _fail(self, job: Job, message: str) -> None:
        job.state, job.error = "failed", message
        if job.package_name:
            self.db.execute("UPDATE apps SET status = 'failed' WHERE device_id = ? AND package_name = ? AND status = 'installing'",
                            (self.device_id, job.package_name))

    # ---------------------------------------------------------------- management
    def list_apps(self) -> list[dict]:
        rows = self.db.query("SELECT * FROM apps WHERE device_id = ? ORDER BY label", (self.device_id,))
        running = self.emulator.status() == "running"
        installed = set(self.adb.packages()) if running else None
        out = []
        for r in rows:
            status = r["status"]
            if installed is not None and status == "installed" and r["package_name"] not in installed:
                status = "failed"   # removed on the device behind our back
            out.append({
                "package_name": r["package_name"], "label": r["label"], "version_name": r["version_name"],
                "size_mb": self._size(r["package_name"]) if running and status == "installed" else 0,
                "last_launched": r["last_launched"], "status": status,
            })
        return out

    def _size(self, pkg: str) -> int:
        import time
        cached = self._size_cache.get(pkg)
        if cached and time.monotonic() - cached[0] < 120:
            return cached[1]
        try:
            size = self.adb.app_size_mb(pkg)
        except AdbError:
            size = 0
        self._size_cache[pkg] = (time.monotonic(), size)
        return size

    def launch(self, pkg: str) -> None:
        self._require(pkg)
        self.emulator.ensure_running()
        self.adb.launch(pkg)
        self.db.execute("UPDATE apps SET last_launched = ? WHERE device_id = ? AND package_name = ?",
                        (now(), self.device_id, pkg))

    def stop(self, pkg: str) -> None:
        self._require(pkg)
        if self.emulator.status() == "running":
            self.adb.force_stop(pkg)

    def uninstall(self, pkg: str) -> None:
        self._require(pkg)
        if self.emulator.status() == "running":
            try:
                self.adb.uninstall(pkg)
            except AdbError as e:
                if "not installed" not in str(e).lower() and "unknown package" not in str(e).lower():
                    raise
        self.db.execute("DELETE FROM apps WHERE device_id = ? AND package_name = ?", (self.device_id, pkg))
        self._size_cache.pop(pkg, None)

    def _require(self, pkg: str) -> None:
        if self.db.one("SELECT 1 AS x FROM apps WHERE device_id = ? AND package_name = ?", (self.device_id, pkg)) is None:
            raise StorageError(404, f"{pkg} is not installed")
