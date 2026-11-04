"""Screen capture from the emulator: `adb screenrecord` (H.264) -> PyAV decode -> latest frame.

Requires PyAV (installed with aiortc). Not exercised by the automated tests, which cannot run an emulator.
"""
from __future__ import annotations

import subprocess
import threading
import time
from typing import Callable, Optional

from backend.streaming.abr import PROFILES, AbrController
from backend.virtualization.adb import Adb

SCREENRECORD_LIMIT_S = 170  # screenrecord stops at 180 s; we restart it before that


class ScreenCapture:
    def __init__(self, adb: Adb, abr: AbrController, popen: Callable[..., subprocess.Popen] = subprocess.Popen):
        self.adb, self.abr, self._popen = adb, abr, popen
        self._latest = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._restart = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._proc: Optional[subprocess.Popen] = None
        self.fps = PROFILES[abr.profile].fps

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="screen-capture")
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._kill()

    def set_profile(self) -> None:
        """Called after the ABR controller changed profile: restart screenrecord at the new size and bitrate."""
        self.fps = PROFILES[self.abr.profile].fps
        self._restart.set()
        self._kill()

    def latest(self):
        with self._lock:
            return self._latest

    def _kill(self) -> None:
        p = self._proc
        if p and p.poll() is None:
            try:
                p.kill()
            except OSError:
                pass

    def _command(self) -> list[str]:
        w, h = self.abr.size_for(*self.adb.display_size())
        p = PROFILES[self.abr.profile]
        return self.adb._base() + ["exec-out", "screenrecord", "--output-format=h264", f"--size={w}x{h}",
                                   f"--bit-rate={p.kbps * 1000}", f"--time-limit={SCREENRECORD_LIMIT_S}", "-"]

    def _loop(self) -> None:
        import av  # noqa: PLC0415 - heavy optional dependency
        while not self._stop.is_set():
            self._restart.clear()
            try:
                self._proc = self._popen(self._command(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
                container = av.open(self._proc.stdout, format="h264", mode="r",
                                    options={"fflags": "nobuffer", "flags": "low_delay"})
                for packet in container.demux(video=0):
                    if self._stop.is_set() or self._restart.is_set():
                        break
                    for frame in packet.decode():
                        with self._lock:
                            self._latest = frame
                container.close()
            except Exception:  # noqa: BLE001 - device rebooting, screenrecord ended, adb hiccup: just retry
                time.sleep(0.5)
            finally:
                self._kill()
