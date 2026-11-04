"""Thin wrapper around the `adb` command line. All process execution goes through an injectable runner so the
logic can be tested without a device."""
from __future__ import annotations

import re
import shlex
import subprocess
import time
from dataclasses import dataclass
from typing import Callable, Optional


class AdbError(Exception):
    pass


@dataclass
class Result:
    rc: int
    out: bytes
    err: bytes

    @property
    def text(self) -> str:
        return self.out.decode("utf-8", "replace")


Runner = Callable[[list[str], Optional[bytes], float], Result]


def subprocess_runner(args: list[str], stdin: Optional[bytes] = None, timeout: float = 60) -> Result:
    try:
        p = subprocess.run(args, input=stdin, capture_output=True, timeout=timeout)
    except FileNotFoundError as e:
        raise AdbError(f"Command not found: {args[0]}. Is the Android SDK installed on this server?") from e
    except subprocess.TimeoutExpired as e:
        raise AdbError(f"Timed out: {' '.join(args[:4])}") from e
    return Result(p.returncode, p.stdout, p.stderr)


KEYCODES = {"back": 4, "home": 3, "recents": 187, "enter": 66, "backspace": 67}


class Adb:
    def __init__(self, adb_path: str = "adb", serial: Optional[str] = None, runner: Runner = subprocess_runner):
        self.adb_path = adb_path
        self.serial = serial
        self.run = runner

    def _base(self) -> list[str]:
        return [self.adb_path] + (["-s", self.serial] if self.serial else [])

    def adb(self, *args: str, timeout: float = 60, check: bool = True, stdin: Optional[bytes] = None) -> Result:
        r = self.run(self._base() + list(args), stdin, timeout)
        if check and r.rc != 0:
            msg = (r.err or r.out).decode("utf-8", "replace").strip()
            raise AdbError(f"adb {' '.join(args[:3])} failed: {msg}")
        return r

    def shell(self, command: str, timeout: float = 60, check: bool = True) -> str:
        return self.adb("shell", command, timeout=timeout, check=check).text

    def exec_out(self, command: str, timeout: float = 600) -> bytes:
        return self.adb("exec-out", command, timeout=timeout).out

    def shell_stdin(self, command: str, data: bytes, timeout: float = 600) -> None:
        self.adb("shell", command, timeout=timeout, stdin=data)

    # ---- device state
    def state(self) -> str:
        r = self.adb("get-state", check=False, timeout=10)
        return r.text.strip() if r.rc == 0 else "offline"

    def booted(self) -> bool:
        return self.state() == "device" and self.shell("getprop sys.boot_completed", check=False, timeout=10).strip() == "1"

    def wait_boot(self, timeout: float = 300, poll: float = 3, sleep: Callable[[float], None] = time.sleep) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                if self.booted():
                    return True
            except AdbError:
                pass
            sleep(poll)
        return False

    def prop(self, name: str) -> str:
        return self.shell(f"getprop {shlex.quote(name)}", check=False, timeout=10).strip()

    def root(self) -> bool:
        """Needed for backups. Works on emulator images without Play Store (google_apis / AOSP)."""
        r = self.adb("root", check=False, timeout=30)
        if r.rc != 0 or b"cannot" in r.out + r.err:
            return False
        time.sleep(1)
        return True

    # ---- apps
    def install(self, apk_path: str, timeout: float = 1800) -> None:
        # -r replace (update, keeps data), -g grant all runtime permissions requested by the app
        r = self.adb("install", "-r", "-g", apk_path, timeout=timeout, check=False)
        text = (r.out + r.err).decode("utf-8", "replace")
        if r.rc != 0 or "Success" not in text:
            raise AdbError("Install failed: " + (re.search(r"Failure \[(.*?)\]", text) or [None, text.strip()[-300:]])[1])

    def uninstall(self, package: str) -> None:
        r = self.adb("uninstall", package, check=False)
        if b"Success" not in r.out:
            raise AdbError(f"Could not uninstall {package}: {r.text.strip()}")

    def launch(self, package: str) -> None:
        r = self.shell(f"monkey -p {shlex.quote(package)} -c android.intent.category.LAUNCHER 1", timeout=30)
        if "No activities found" in r:
            raise AdbError(f"{package} has no launchable activity")

    def force_stop(self, package: str) -> None:
        self.shell(f"am force-stop {shlex.quote(package)}", timeout=15)

    def clear_data(self, package: str) -> None:
        self.shell(f"pm clear {shlex.quote(package)}", timeout=60)

    def packages(self) -> list[str]:
        out = self.shell("pm list packages -3", timeout=30)
        return sorted(l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:"))

    def version_name(self, package: str) -> str:
        out = self.shell(f"dumpsys package {shlex.quote(package)} | grep -m1 versionName", check=False, timeout=30)
        m = re.search(r"versionName=(\S+)", out)
        return m.group(1) if m else ""

    def app_size_mb(self, package: str) -> int:
        """Code + data size in MB. Needs root; returns 0 if it cannot be read."""
        q = shlex.quote(package)
        out = self.shell(f"du -sk /data/data/{q} /data/app/*{q}* /sdcard/Android/data/{q} /sdcard/Android/obb/{q} 2>/dev/null",
                         check=False, timeout=60)
        total_kb = sum(int(m.group(1)) for m in re.finditer(r"^(\d+)\s", out, re.M))
        return total_kb // 1024

    def uid(self, package: str) -> Optional[int]:
        out = self.shell(f"stat -c %u /data/data/{shlex.quote(package)}", check=False, timeout=10).strip()
        return int(out) if out.isdigit() else None

    # ---- display + input
    def display_size(self) -> tuple[int, int]:
        out = self.shell("dumpsys window displays | grep -m1 'cur='", check=False, timeout=15)
        m = re.search(r"cur=(\d+)x(\d+)", out)
        if not m:
            m = re.search(r"(\d+)x(\d+)", self.shell("wm size", check=False, timeout=10))
        if not m:
            raise AdbError("Cannot read the display size")
        return int(m.group(1)), int(m.group(2))

    def motion(self, action: str, x: int, y: int) -> None:
        self.shell(f"input motionevent {action.upper()} {x} {y}", timeout=10, check=False)

    def keyevent(self, name: str) -> None:
        code = KEYCODES.get(name)
        if code is None:
            raise AdbError(f"Unknown key {name}")
        self.shell(f"input keyevent {code}", timeout=10, check=False)

    def text(self, s: str) -> None:
        # `input text` takes %s for a space and cannot type non-ASCII characters.
        safe = "".join(ch for ch in s if 32 <= ord(ch) < 127).replace(" ", "%s")
        if safe:
            self.shell(f"input text {shlex.quote(safe)}", timeout=15, check=False)

    def set_orientation(self, landscape: bool) -> None:
        self.shell("settings put system accelerometer_rotation 0", check=False, timeout=10)
        self.shell(f"settings put system user_rotation {1 if landscape else 0}", check=False, timeout=10)
