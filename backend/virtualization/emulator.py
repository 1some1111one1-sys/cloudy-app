"""Lifecycle of the remote Android device: the official Android Emulator (QEMU + KVM) with a persistent AVD.

The AVD's userdata image lives in the AVD directory and is never wiped, so installed apps and their data survive
restarts of the emulator and of this server.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import threading
import time
from typing import Callable, Optional

from backend.config import Settings
from backend.virtualization.adb import Adb, AdbError, subprocess_runner

PopenFactory = Callable[..., "subprocess.Popen"]


class EmulatorManager:
    def __init__(self, settings: Settings, adb: Adb, popen: PopenFactory = subprocess.Popen,
                 sleep: Callable[[float], None] = time.sleep):
        self.s = settings
        self.adb = adb
        self._popen = popen
        self._sleep = sleep
        self.proc: Optional[subprocess.Popen] = None
        self._lock = threading.Lock()
        self._status = "stopped"
        self._error: Optional[str] = None
        self._cpu_prev: Optional[tuple[float, float]] = None

    # ---------------------------------------------------------------- paths
    def avd_home(self) -> str:
        return os.environ.get("ANDROID_AVD_HOME") or os.path.join(os.path.expanduser("~"), ".android", "avd")

    def avd_dir(self) -> str:
        return os.path.join(self.avd_home(), f"{self.s.avd_name}.avd")

    def exists(self) -> bool:
        return os.path.isdir(self.avd_dir())

    # ---------------------------------------------------------------- create
    def create(self) -> None:
        """Creates the AVD once (idempotent) and applies the configured CPU / RAM / disk allocation."""
        if not self.exists():
            r = subprocess_runner(
                [self.s.avdmanager_path, "create", "avd", "-n", self.s.avd_name, "-k", self.s.system_image,
                 "-d", "pixel_5", "--force"], b"no\n", 300)
            if r.rc != 0:
                raise AdbError("avdmanager failed: " + (r.err or r.out).decode("utf-8", "replace")[-400:])
        self.apply_allocation()

    def apply_allocation(self) -> None:
        cfg = os.path.join(self.avd_dir(), "config.ini")
        if not os.path.isfile(cfg):
            raise AdbError(f"AVD config not found at {cfg}")
        wanted = {
            "hw.ramSize": str(self.s.ram_mb),
            "hw.cpu.ncore": str(self.s.cpu_cores),
            "disk.dataPartition.size": f"{self.s.disk_gb}G",
            "hw.keyboard": "yes",
            "hw.gpu.enabled": "yes",
        }
        with open(cfg, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
        seen = set()
        out = []
        for line in lines:
            key = line.split("=", 1)[0].strip()
            if key in wanted:
                out.append(f"{key}={wanted[key]}")
                seen.add(key)
            else:
                out.append(line)
        out += [f"{k}={v}" for k, v in wanted.items() if k not in seen]
        with open(cfg, "w", encoding="utf-8") as fh:
            fh.write("\n".join(out) + "\n")

    # ---------------------------------------------------------------- lifecycle
    def _command(self) -> list[str]:
        return [self.s.emulator_path, "-avd", self.s.avd_name, "-no-window", "-no-boot-anim", "-no-audio",
                "-no-snapshot", "-gpu", self.s.emulator_gpu, "-memory", str(self.s.ram_mb),
                "-cores", str(self.s.cpu_cores), "-accel", "on", "-netfast"]

    def start(self) -> None:
        with self._lock:
            if self._status in ("starting", "running") and self.proc and self.proc.poll() is None:
                return
            if not self.exists():
                self.create()
            self._error = None
            self._status = "starting"
            try:
                self.proc = self._popen(self._command(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except FileNotFoundError:
                self._status, self._error = "error", f"Emulator not found: {self.s.emulator_path}"
                raise AdbError(self._error)
        threading.Thread(target=self._await_boot, daemon=True).start()

    def _await_boot(self) -> None:
        ok = self.adb.wait_boot(timeout=420, sleep=self._sleep)
        with self._lock:
            if self._status != "starting":
                return
            if ok and self.proc and self.proc.poll() is None:
                self._status = "running"
                try:
                    self.adb.root()
                except AdbError:
                    pass
            else:
                self._status = "error"
                self._error = ("The emulator exited during startup. /dev/kvm must be available to this user."
                               if self.proc and self.proc.poll() is not None else "Boot timed out.")

    def stop(self) -> None:
        with self._lock:
            if self.proc is None or self.proc.poll() is not None:
                self._status = "stopped"
                return
            self._status = "stopping"
        try:
            self.adb.adb("emu", "kill", check=False, timeout=15)
        except AdbError:
            pass
        try:
            self.proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        with self._lock:
            self._status = "stopped"
            self.proc = None

    def restart(self) -> None:
        self.stop()
        self.start()

    def ensure_running(self, timeout: float = 420) -> None:
        """Starts the device if needed and waits until it has booted."""
        if self.status() == "stopped" or self.status() == "error":
            self.start()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            st = self.status()
            if st == "running":
                return
            if st == "error":
                raise AdbError(self._error or "The device failed to start")
            self._sleep(2)
        raise AdbError("The device did not finish booting in time")

    def status(self) -> str:
        with self._lock:
            if self.proc is not None and self.proc.poll() is not None and self._status in ("running", "starting"):
                self._status, self._error = "error", self._error or "The emulator stopped unexpectedly"
            return self._status

    # ---------------------------------------------------------------- monitoring
    def _proc_stats(self) -> tuple[float, int]:
        """(cpu_percent of one core * ncores normalised to 0-100 of the allocation, rss_mb) for the emulator process."""
        if not self.proc or self.proc.poll() is not None:
            return 0.0, 0
        pid = self.proc.pid
        try:
            with open(f"/proc/{pid}/stat") as fh:
                parts = fh.read().rsplit(")", 1)[1].split()
            ticks = (int(parts[11]) + int(parts[12])) / os.sysconf("SC_CLK_TCK")
            with open(f"/proc/{pid}/status") as fh:
                m = re.search(r"VmRSS:\s+(\d+) kB", fh.read())
            rss = int(m.group(1)) // 1024 if m else 0
        except (OSError, IndexError, ValueError):
            return 0.0, 0
        now = time.monotonic()
        cpu = 0.0
        if self._cpu_prev:
            dt = now - self._cpu_prev[0]
            if dt > 0:
                cpu = max(0.0, min(100.0, (ticks - self._cpu_prev[1]) / dt / max(self.s.cpu_cores, 1) * 100))
        self._cpu_prev = (now, ticks)
        return cpu, rss

    def info(self, device_id: str) -> dict:
        st = self.status()
        d = {
            "id": device_id, "status": st, "android_version": "", "model": "",
            "cpu_cores": self.s.cpu_cores, "ram_mb": self.s.ram_mb, "disk_gb": self.s.disk_gb,
            "disk_used_mb": 0, "cpu_percent": 0.0, "ram_used_mb": 0, "error": self._error,
        }
        if st == "running":
            try:
                d["android_version"] = self.adb.prop("ro.build.version.release")
                d["model"] = self.adb.prop("ro.product.model")
                df = self.adb.shell("df -k /data | tail -1", check=False, timeout=10).split()
                if len(df) >= 3 and df[2].isdigit():
                    d["disk_used_mb"] = int(df[2]) // 1024
            except AdbError:
                pass
            d["cpu_percent"], d["ram_used_mb"] = self._proc_stats()
        elif self.exists():
            try:
                d["disk_used_mb"] = os.path.getsize(os.path.join(self.avd_dir(), "userdata-qemu.img")) // (1024 * 1024)
            except OSError:
                pass
        return d

    def host_disk_free_gb(self) -> float:
        return shutil.disk_usage(self.s.data_dir if os.path.isdir(self.s.data_dir) else "/").free / 1e9
