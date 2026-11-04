"""Adaptive bitrate / quality controller. Pure logic, no I/O, so it is fully unit-tested.

The phone reports round-trip time, packet loss and delivered fps every 2 seconds. We step DOWN quickly when the
network is bad and step UP slowly when it has been good for a while (hysteresis), never leaving manual override.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class Profile:
    name: str
    height: int
    fps: int
    kbps: int


PROFILES = {
    "LOW": Profile("LOW", 480, 24, 1000),
    "BALANCED": Profile("BALANCED", 720, 30, 3000),
    "HIGH": Profile("HIGH", 1080, 60, 8000),
}
ORDER = ["LOW", "BALANCED", "HIGH"]

BAD_LOSS, BAD_RTT = 5.0, 300
GOOD_LOSS, GOOD_RTT = 1.0, 120
DOWN_AFTER = 2      # consecutive bad reports  (~4 s)
UP_AFTER = 10       # consecutive good reports (~20 s)


class AbrController:
    def __init__(self, profile: str = "BALANCED", auto: bool = True):
        self.profile = profile if profile in PROFILES else "BALANCED"
        self.auto = auto
        self._bad = 0
        self._good = 0

    def set_manual(self, profile: str, auto: bool) -> Profile:
        if profile in PROFILES:
            self.profile = profile
        self.auto = auto
        self._bad = self._good = 0
        return PROFILES[self.profile]

    def update(self, rtt_ms: float, loss_percent: float) -> Optional[Profile]:
        """Returns a new profile when the quality should change, else None."""
        if not self.auto:
            return None
        bad = loss_percent >= BAD_LOSS or (rtt_ms >= 0 and rtt_ms >= BAD_RTT)
        good = loss_percent <= GOOD_LOSS and (rtt_ms < 0 or rtt_ms <= GOOD_RTT)
        self._bad = self._bad + 1 if bad else 0
        self._good = self._good + 1 if good else 0
        i = ORDER.index(self.profile)
        if self._bad >= DOWN_AFTER and i > 0:
            self._bad = self._good = 0
            self.profile = ORDER[i - 1]
            return PROFILES[self.profile]
        if self._good >= UP_AFTER and i < len(ORDER) - 1:
            self._bad = self._good = 0
            self.profile = ORDER[i + 1]
            return PROFILES[self.profile]
        return None

    def size_for(self, device_w: int, device_h: int) -> tuple[int, int]:
        """Encoded picture size for the current profile: shortest side = profile height, aspect ratio kept, even numbers."""
        p = PROFILES[self.profile]
        short, long_ = min(device_w, device_h), max(device_w, device_h)
        scale = min(1.0, p.height / short)
        s, l = int(short * scale) // 2 * 2, int(long_ * scale) // 2 * 2
        return (l, s) if device_w >= device_h else (s, l)
