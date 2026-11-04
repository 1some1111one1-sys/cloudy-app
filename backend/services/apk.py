"""APK inspection using `aapt2 dump badging` (part of the Android SDK build-tools)."""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from backend.virtualization.adb import AdbError, Runner, subprocess_runner


@dataclass
class ApkInfo:
    package: str
    version_name: str = ""
    version_code: str = ""
    label: str = ""
    min_sdk: int = 0
    abis: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def parse_badging(text: str) -> ApkInfo:
    m = re.search(r"package: name='([^']+)'(?: versionCode='([^']*)')?(?: versionName='([^']*)')?", text)
    if not m:
        raise AdbError("Not a valid APK (no package information found).")
    info = ApkInfo(package=m.group(1), version_code=m.group(2) or "", version_name=m.group(3) or "")
    lab = re.search(r"^application-label:'([^']*)'", text, re.M) or re.search(r"application: label='([^']*)'", text)
    info.label = lab.group(1) if lab else info.package
    sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.M)
    info.min_sdk = int(sdk.group(1)) if sdk else 0
    nc = re.search(r"^native-code: (.*)$", text, re.M)
    if nc:
        info.abis = re.findall(r"'([^']+)'", nc.group(1))
    if info.abis and not any(a.startswith("x86") for a in info.abis):
        info.warnings.append("This APK only contains ARM native code. It will run through the emulator's ARM translation, "
                             "which is slower and not guaranteed to work.")
    return info


def inspect_apk(path: str, aapt2: str = "aapt2", runner: Runner = subprocess_runner) -> ApkInfo:
    r = runner([aapt2, "dump", "badging", path], None, 120)
    if r.rc != 0:
        raise AdbError("Could not read the APK: " + (r.err or r.out).decode("utf-8", "replace").strip()[-300:])
    return parse_badging(r.text)
