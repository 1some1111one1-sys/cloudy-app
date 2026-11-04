"""Touch / keyboard injection from the WebRTC data channel into the Android device.

LIMITATION (honest): input goes through `adb shell input`, which is single-touch and adds roughly 50-200 ms per
event. That is fine for menus, tapping and dragging, and not good enough for fast action games or multi-touch
gestures. Pointer ids other than the first are ignored. Replacing this with emulator gRPC / sendevent injection is
the main upgrade path.
"""
from __future__ import annotations

import json
import queue
import threading
import time
from typing import Callable, Optional

from backend.virtualization.adb import Adb, AdbError

MOVE_INTERVAL = 1 / 30


class InputInjector:
    def __init__(self, adb: Adb, on_profile: Optional[Callable[[str, bool], None]] = None,
                 on_stats: Optional[Callable[[float, float], None]] = None):
        self.adb = adb
        self.on_profile = on_profile
        self.on_stats = on_stats
        self._q: "queue.Queue[tuple]" = queue.Queue(maxsize=256)
        self._size: Optional[tuple[int, int]] = None
        self._size_at = 0.0
        self._primary: Optional[int] = None
        self._last_move = 0.0
        self._stop = threading.Event()
        self._t = threading.Thread(target=self._worker, daemon=True)
        self._t.start()

    def close(self) -> None:
        self._stop.set()

    def _display(self) -> tuple[int, int]:
        if self._size is None or time.monotonic() - self._size_at > 5:
            self._size = self.adb.display_size()
            self._size_at = time.monotonic()
        return self._size

    # ---------------------------------------------------------------- entry point
    def handle(self, raw: str) -> None:
        try:
            m = json.loads(raw)
        except ValueError:
            return
        t = m.get("t")
        if t == "touch":
            self._touch(m)
        elif t == "key":
            self._enqueue(("key", str(m.get("k", ""))))
        elif t == "text":
            self._enqueue(("text", str(m.get("s", ""))[:500]))
        elif t == "orientation":
            self._enqueue(("rot", m.get("o") == "landscape"))
        elif t == "profile" and self.on_profile:
            self.on_profile(str(m.get("name", "")), bool(m.get("auto", False)))
        elif t == "stats" and self.on_stats:
            self.on_stats(float(m.get("rtt", -1)), float(m.get("loss", 0)))

    def _touch(self, m: dict) -> None:
        action, pid = m.get("a"), m.get("id", 0)
        if action == "down" and self._primary is None:
            self._primary = pid
        if pid != self._primary:
            return
        if action == "move":
            now = time.monotonic()
            if now - self._last_move < MOVE_INTERVAL:
                return                      # drop intermediate moves instead of lagging behind
            self._last_move = now
        if action == "up":
            self._primary = None
        if action in ("down", "move", "up"):
            self._enqueue(("touch", action, float(m.get("x", 0)), float(m.get("y", 0))))

    def _enqueue(self, item: tuple) -> None:
        try:
            self._q.put_nowait(item)
        except queue.Full:
            # keep ordering of down/up events: drop the oldest pending move instead
            try:
                self._q.get_nowait()
                self._q.put_nowait(item)
            except queue.Empty:
                pass

    def drain(self) -> None:
        """Process everything queued now (used by tests; the worker thread does this in production)."""
        while not self._q.empty():
            self._run(self._q.get_nowait())

    def _worker(self) -> None:
        while not self._stop.is_set():
            try:
                item = self._q.get(timeout=0.5)
            except queue.Empty:
                continue
            self._run(item)

    def _run(self, item: tuple) -> None:
        try:
            kind = item[0]
            if kind == "touch":
                w, h = self._display()
                x = min(max(int(item[2] * w), 0), w - 1)
                y = min(max(int(item[3] * h), 0), h - 1)
                self.adb.motion(item[1], x, y)
            elif kind == "key":
                self.adb.keyevent(item[1])
            elif kind == "text":
                self.adb.text(item[1])
            elif kind == "rot":
                self.adb.set_orientation(item[1])
                self._size = None
        except AdbError:
            pass
