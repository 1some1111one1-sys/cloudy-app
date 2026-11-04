"""Builds the object graph once at start-up."""
from __future__ import annotations

import os
from dataclasses import dataclass

from backend.config import Settings
from backend.database.db import Database, now, open_database
from backend.services.apps import AppService
from backend.services.backup import BackupService
from backend.storage.manager import FernetBox, StorageManager
from backend.streaming.session import StreamManager
from backend.virtualization.adb import Adb
from backend.virtualization.emulator import EmulatorManager


@dataclass
class Container:
    settings: Settings
    db: Database
    adb: Adb
    emulator: EmulatorManager
    storage: StorageManager
    apps: AppService
    backups: BackupService
    streams: StreamManager
    device_id: str


def build(settings: Settings) -> Container:
    if not settings.token or len(settings.token) < 16:
        raise RuntimeError("CLOUDDROID_TOKEN must be set to a random string of at least 16 characters.")
    os.makedirs(settings.data_dir, exist_ok=True)
    os.makedirs(settings.staging_dir, exist_ok=True)
    db = open_database(settings.db_url)
    user = settings.user_id
    if db.one("SELECT 1 AS x FROM users WHERE id = ?", (user,)) is None:
        db.execute("INSERT INTO users (id, name, created_at) VALUES (?, ?, ?)", (user, user, now()))
    device_id = f"dev-{user}"
    if db.one("SELECT 1 AS x FROM devices WHERE id = ?", (device_id,)) is None:
        db.execute("INSERT INTO devices (id, user_id, avd_name, cpu_cores, ram_mb, disk_gb, created_at) "
                   "VALUES (?, ?, ?, ?, ?, ?, ?)",
                   (device_id, user, settings.avd_name, settings.cpu_cores, settings.ram_mb, settings.disk_gb, now()))
    adb = Adb(settings.adb_path)
    emulator = EmulatorManager(settings, adb)
    storage = StorageManager(db, user, FernetBox(settings.secret_key))
    apps = AppService(db, device_id, adb, emulator, storage, settings.staging_dir, settings.aapt2_path)
    backups = BackupService(db, device_id, adb, storage, settings.staging_dir)
    streams = StreamManager(settings, adb, emulator, apps, db, device_id)
    return Container(settings, db, adb, emulator, storage, apps, backups, streams, device_id)
