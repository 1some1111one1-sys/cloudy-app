import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from backend.streaming.abr import AbrController  # noqa: E402
from backend.streaming.input import InputInjector  # noqa: E402
from backend.virtualization.adb import Adb, Result  # noqa: E402


# ---------------------------------------------------------------- adaptive bitrate
def test_steps_down_after_two_bad_reports_then_up_after_long_good_run():
    a = AbrController("HIGH", auto=True)
    assert a.update(400, 0) is None                      # one bad report is not enough
    assert a.update(400, 0).name == "BALANCED"
    assert a.update(50, 8) is None and a.update(50, 8).name == "LOW"
    assert a.update(50, 8) is None and a.update(50, 8) is None   # cannot go below LOW
    ups = [a.update(40, 0) for _ in range(10)]
    assert [u.name for u in ups if u] == ["BALANCED"]


def test_manual_override_stops_automatic_changes():
    a = AbrController("BALANCED", auto=True)
    a.set_manual("HIGH", auto=False)
    assert all(a.update(900, 50) is None for _ in range(10)) and a.profile == "HIGH"


def test_loss_spike_resets_good_counter():
    a = AbrController("LOW", auto=True)
    for _ in range(9):
        assert a.update(30, 0) is None
    assert a.update(200, 3) is None                       # neither good nor bad: resets the good streak
    for _ in range(9):
        assert a.update(30, 0) is None


def test_encoded_size_keeps_aspect_ratio_and_never_upscales():
    a = AbrController("BALANCED")
    assert a.size_for(1080, 2400) == (720, 1600)
    assert a.size_for(2400, 1080) == (1600, 720)
    assert a.size_for(540, 960) == (540, 960)
    a.set_manual("LOW", True)
    w, h = a.size_for(1080, 2400)
    assert h % 2 == 0 and w % 2 == 0 and w == 480


# ---------------------------------------------------------------- input injection
class Recorder:
    def __init__(self):
        self.calls = []

    def __call__(self, args, stdin, timeout):
        cmd = args[-1] if args[-2:-1] == ["shell"] else " ".join(args)
        self.calls.append(cmd)
        if "dumpsys window displays" in cmd:
            return Result(0, b"  init=1080x2400 420dpi cur=1080x2400 app=1080x2337", b"")
        return Result(0, b"", b"")


def injector():
    rec = Recorder()
    got = {}
    inj = InputInjector(Adb("adb", runner=rec), on_profile=lambda n, a: got.update(profile=(n, a)),
                        on_stats=lambda r, l: got.update(stats=(r, l)))
    return inj, rec, got


def test_touch_is_scaled_to_device_pixels_in_order():
    inj, rec, _ = injector()
    inj.handle(json.dumps({"t": "touch", "a": "down", "id": 0, "x": 0.5, "y": 0.25}))
    inj.handle(json.dumps({"t": "touch", "a": "up", "id": 0, "x": 0.5, "y": 0.25}))
    inj.drain()
    inj.close()
    cmds = [c for c in rec.calls if "motionevent" in c]
    assert cmds == ["input motionevent DOWN 540 600", "input motionevent UP 540 600"]


def test_second_finger_is_ignored_and_keys_text_and_messages_work():
    inj, rec, got = injector()
    inj.handle(json.dumps({"t": "touch", "a": "down", "id": 0, "x": 0.1, "y": 0.1}))
    inj.handle(json.dumps({"t": "touch", "a": "down", "id": 1, "x": 0.9, "y": 0.9}))
    inj.handle(json.dumps({"t": "key", "k": "back"}))
    inj.handle(json.dumps({"t": "text", "s": "hi there"}))
    inj.handle(json.dumps({"t": "profile", "name": "LOW", "auto": False}))
    inj.handle(json.dumps({"t": "stats", "rtt": 80, "loss": 0.5}))
    inj.handle("not json")
    inj.drain()
    inj.close()
    joined = "\n".join(rec.calls)
    assert joined.count("motionevent") == 1
    assert "input keyevent 4" in joined and "input text hi%sthere" in joined
    assert got == {"profile": ("LOW", False), "stats": (80.0, 0.5)}
