"""Configuration, read from environment variables (see cloud-infrastructure/configuration/clouddroid.env.example)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass
class Settings:
    token: str = ""                       # shared bearer token the phone must present
    data_dir: str = "/var/lib/clouddroid"
    database_url: str = ""                # postgresql://user:pass@host/db, or sqlite:///path (tests / quick start)
    secret_key: str = ""                  # Fernet key used to encrypt cloud sessions at rest
    adb_path: str = "adb"
    emulator_path: str = "emulator"
    avdmanager_path: str = "avdmanager"
    aapt2_path: str = "aapt2"
    avd_name: str = "clouddroid"
    system_image: str = "system-images;android-34;google_apis;x86_64"
    cpu_cores: int = 4
    ram_mb: int = 6144
    disk_gb: int = 32
    emulator_gpu: str = "swiftshader_indirect"   # use "host" on a GPU machine
    stun_urls: list[str] = field(default_factory=lambda: ["stun:stun.l.google.com:19302"])
    turn_url: str = ""
    turn_user: str = ""
    turn_password: str = ""
    session_idle_seconds: int = 900       # a disconnected session is kept this long so the phone can reconnect
    user_id: str = "default"

    @property
    def staging_dir(self) -> str:
        return os.path.join(self.data_dir, "staging")

    @property
    def db_url(self) -> str:
        return self.database_url or f"sqlite:///{os.path.join(self.data_dir, 'clouddroid.db')}"


def load() -> Settings:
    e = os.environ.get
    s = Settings(
        token=e("CLOUDDROID_TOKEN", ""),
        data_dir=e("CLOUDDROID_DATA_DIR", "/var/lib/clouddroid"),
        database_url=e("DATABASE_URL", ""),
        secret_key=e("CLOUDDROID_SECRET_KEY", ""),
        adb_path=e("ADB_PATH", "adb"),
        emulator_path=e("EMULATOR_PATH", "emulator"),
        avdmanager_path=e("AVDMANAGER_PATH", "avdmanager"),
        aapt2_path=e("AAPT2_PATH", "aapt2"),
        avd_name=e("AVD_NAME", "clouddroid"),
        system_image=e("SYSTEM_IMAGE", "system-images;android-34;google_apis;x86_64"),
        cpu_cores=_int("DEVICE_CPU_CORES", 4),
        ram_mb=_int("DEVICE_RAM_MB", 6144),
        disk_gb=_int("DEVICE_DISK_GB", 32),
        emulator_gpu=e("EMULATOR_GPU", "swiftshader_indirect"),
        turn_url=e("TURN_URL", ""),
        turn_user=e("TURN_USER", ""),
        turn_password=e("TURN_PASSWORD", ""),
        session_idle_seconds=_int("SESSION_IDLE_SECONDS", 900),
    )
    if e("STUN_URLS"):
        s.stun_urls = [u.strip() for u in e("STUN_URLS", "").split(",") if u.strip()]
    return s
