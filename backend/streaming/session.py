"""WebRTC streaming sessions (aiortc) with WebSocket signalling.

A session belongs to the remote device, NOT to the peer connection: when the phone's network drops, the phone
opens a new WebSocket for the same session id and negotiates a new peer connection, while the app keeps running.

NOT exercised by automated tests (needs a running emulator and a WebRTC peer); see documentation/TESTING.md for the
manual end-to-end procedure. Audio is not implemented: the emulator is started with -no-audio.
"""
from __future__ import annotations

import asyncio
import json
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

from backend.config import Settings
from backend.streaming.abr import PROFILES, AbrController
from backend.streaming.capture import ScreenCapture
from backend.streaming.input import InputInjector
from backend.virtualization.adb import Adb


def ice_servers(s: Settings) -> list[dict]:
    out = [{"urls": s.stun_urls}] if s.stun_urls else []
    if s.turn_url:
        out.append({"urls": [s.turn_url], "username": s.turn_user, "credential": s.turn_password})
    return out


@dataclass
class StreamSession:
    id: str
    package: Optional[str]
    abr: AbrController
    created: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    pc: object = None
    ws: object = None
    loop: Optional[asyncio.AbstractEventLoop] = None
    injector: Optional[InputInjector] = None


class StreamManager:
    def __init__(self, settings: Settings, adb: Adb, emulator, apps, db=None, device_id: str = "dev"):
        self.s, self.adb, self.emulator, self.apps, self.db, self.device_id = settings, adb, emulator, apps, db, device_id
        self.sessions: dict[str, StreamSession] = {}
        self.capture: Optional[ScreenCapture] = None
        self._capture_abr: Optional[AbrController] = None

    # ---------------------------------------------------------------- lifecycle (called from threadpool)
    def create(self, package: Optional[str], profile: str) -> dict:
        self.emulator.ensure_running()
        if package:
            self.apps.launch(package)
        for old in list(self.sessions):      # one viewer per device: a new session takes over
            self.end(old)
        abr = AbrController(profile if profile in PROFILES else "BALANCED", auto=True)
        sid = uuid.uuid4().hex
        s = StreamSession(sid, package, abr)

        def on_profile(name: str, auto: bool) -> None:
            abr.set_manual(name, auto)
            self._apply_profile(s)

        def on_stats(rtt: float, loss: float) -> None:
            if abr.update(rtt, loss):
                self._apply_profile(s)

        s.injector = InputInjector(self.adb, on_profile=on_profile, on_stats=on_stats)
        self.sessions[sid] = s
        self.capture = ScreenCapture(self.adb, abr)
        self.capture.start()
        w, h = abr.size_for(*self.adb.display_size())
        return {"session_id": sid, "ice_servers": ice_servers(self.s), "width": w, "height": h}

    def _apply_profile(self, s: StreamSession) -> None:
        if self.capture:
            self.capture.set_profile()
        self._notify(s, {"type": "profile", "name": s.abr.profile})

    def _notify(self, s: StreamSession, msg: dict) -> None:
        if s.ws is not None and s.loop is not None:
            asyncio.run_coroutine_threadsafe(s.ws.send_text(json.dumps(msg)), s.loop)

    def end(self, session_id: str) -> None:
        s = self.sessions.pop(session_id, None)
        if not s:
            return
        if s.injector:
            s.injector.close()
        if s.pc is not None and s.loop is not None:
            asyncio.run_coroutine_threadsafe(s.pc.close(), s.loop)
        if not self.sessions and self.capture:
            self.capture.stop()
            self.capture = None

    def reap(self) -> None:
        """Ends sessions whose phone has been gone longer than SESSION_IDLE_SECONDS."""
        cutoff = time.time() - self.s.session_idle_seconds
        for sid, s in list(self.sessions.items()):
            if s.pc is None and s.last_seen < cutoff or (s.pc is not None and s.last_seen < cutoff
                                                          and getattr(s.pc, "connectionState", "") != "connected"):
                self.end(sid)

    # ---------------------------------------------------------------- signalling (async)
    async def signal(self, session_id: str, ws) -> None:
        from starlette.websockets import WebSocketDisconnect  # noqa: PLC0415
        s = self.sessions.get(session_id)
        await ws.accept()
        if s is None:
            await ws.send_text(json.dumps({"type": "error", "message": "Unknown or expired session"}))
            await ws.close(code=4404)
            return
        s.ws, s.loop = ws, asyncio.get_running_loop()
        try:
            while True:
                msg = json.loads(await ws.receive_text())
                if msg.get("type") == "offer":
                    try:
                        await self._answer(s, msg, ws)
                    except Exception as e:  # noqa: BLE001
                        await ws.send_text(json.dumps({"type": "error", "message": f"Could not start the stream: {e}"}))
        except WebSocketDisconnect:
            pass
        finally:
            if s.ws is ws:
                s.ws = None
            s.last_seen = time.time()

    async def _answer(self, s: StreamSession, msg: dict, ws) -> None:
        from aiortc import (RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCRtpSender,  # noqa: PLC0415
                            RTCSessionDescription)
        from backend.streaming.track import ScreenTrack  # noqa: PLC0415

        if s.pc is not None:                          # reconnect: replace the broken peer, keep the session
            await s.pc.close()
        s.abr.set_manual(str(msg.get("profile", s.abr.profile)), bool(msg.get("auto", True)))
        if self.capture:
            self.capture.set_profile()
        servers = [RTCIceServer(urls=i["urls"], username=i.get("username"), credential=i.get("credential"))
                   for i in ice_servers(self.s)]
        pc = RTCPeerConnection(RTCConfiguration(iceServers=servers))
        s.pc = pc

        @pc.on("datachannel")
        def on_datachannel(channel):
            @channel.on("message")
            def on_message(m):
                if isinstance(m, str) and s.injector:
                    s.last_seen = time.time()
                    s.injector.handle(m)

        @pc.on("connectionstatechange")
        async def on_state():
            s.last_seen = time.time()
            if pc.connectionState in ("failed", "closed"):
                await pc.close()
                if s.pc is pc:
                    s.pc = None

        await pc.setRemoteDescription(RTCSessionDescription(sdp=msg["sdp"], type="offer"))
        track = ScreenTrack(self.capture)
        sender = pc.addTrack(track)
        try:  # prefer H.264 (hardware-decoded on phones)
            h264 = [c for c in RTCRtpSender.getCapabilities("video").codecs if c.mimeType == "video/H264"]
            for t in pc.getTransceivers():
                if t.sender is sender and h264:
                    t.setCodecPreferences(h264)
        except Exception:  # noqa: BLE001 - fall back to the default codec list
            pass
        await pc.setLocalDescription(await pc.createAnswer())
        await ws.send_text(json.dumps({"type": "answer", "sdp": pc.localDescription.sdp}))
        await ws.send_text(json.dumps({"type": "profile", "name": s.abr.profile}))
