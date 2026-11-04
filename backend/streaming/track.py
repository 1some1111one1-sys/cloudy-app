"""aiortc video track fed by ScreenCapture. Paced at the profile's frame rate; repeats the last frame when the
screen is static (screenrecord only emits frames when the picture changes)."""
from __future__ import annotations

import asyncio
import fractions
import time

import av
from aiortc import VideoStreamTrack
from aiortc.mediastreams import MediaStreamError

CLOCK = 90000


class ScreenTrack(VideoStreamTrack):
    kind = "video"

    def __init__(self, capture):
        super().__init__()
        self.capture = capture
        self._start = None
        self._ts = 0

    async def recv(self):
        if self.readyState != "live":
            raise MediaStreamError
        fps = max(1, getattr(self.capture, "fps", 30))
        if self._start is None:
            self._start = time.time()
        else:
            self._ts += int(CLOCK / fps)
            wait = self._start + self._ts / CLOCK - time.time()
            if wait > 0:
                await asyncio.sleep(wait)
        frame = self.capture.latest() if self.capture else None
        if frame is None:
            frame = av.VideoFrame(width=640, height=360, format="yuv420p")   # black until the first real frame
            for i, plane in enumerate(frame.planes):
                plane.update(bytes([0 if i == 0 else 128]) * plane.buffer_size)
        out = frame.reformat(format="yuv420p")
        out.pts = self._ts
        out.time_base = fractions.Fraction(1, CLOCK)
        return out
