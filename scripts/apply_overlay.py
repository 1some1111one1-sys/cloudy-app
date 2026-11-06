#!/usr/bin/env python3
"""Apply the BG App Runner overlay onto a checkout of the NewBlackbox engine.

Usage: apply_overlay.py <path-to-engine-checkout>

It copies overlay/app/** into the engine's app module and patches the app manifest:
  - adds the permissions the keep-alive service needs
  - makes RunnerActivity the launcher (WelcomeActivity stays reachable from it)
  - registers KeepAliveService
It fails loudly if an expected anchor is missing, so a changed upstream is noticed early.
"""
import shutil
import sys
from pathlib import Path

PERMISSIONS = [
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.WAKE_LOCK",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.POST_NOTIFICATIONS",
]

LAUNCHER_CATEGORY = '<category android:name="android.intent.category.LAUNCHER" />'

COMPONENTS = """
        <activity
            android:name="top.niunaijun.blackboxa.runner.RunnerActivity"
            android:label="BG App Runner"
            android:exported="true"
            android:theme="@android:style/Theme.Material.Light.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name="top.niunaijun.blackboxa.runner.KeepAliveService"
            android:exported="false" />
"""


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply_overlay.py <engine-checkout>")

    engine = Path(sys.argv[1]).resolve()
    repo = Path(__file__).resolve().parent.parent
    overlay_app = repo / "overlay" / "app"
    app = engine / "app"
    manifest = app / "src" / "main" / "AndroidManifest.xml"

    if not manifest.is_file():
        fail(f"{manifest} not found; is {engine} a NewBlackbox checkout?")

    shutil.copytree(overlay_app, app, dirs_exist_ok=True)

    text = manifest.read_text(encoding="utf-8")
    if "runner.RunnerActivity" in text:
        print("manifest already patched, nothing to do")
        return

    if "<application" not in text or "</application>" not in text:
        fail("manifest has no <application> element")
    if text.count(LAUNCHER_CATEGORY) != 1:
        fail(f"expected exactly one LAUNCHER category, found {text.count(LAUNCHER_CATEGORY)}")

    missing = [p for p in PERMISSIONS if f'android:name="{p}"' not in text]
    block = "".join(f'<uses-permission android:name="{p}" />\n    ' for p in missing)
    text = text.replace("<application", block + "<application", 1)

    # The engine's own welcome screen stops being a launcher; RunnerActivity opens it.
    text = text.replace(LAUNCHER_CATEGORY, "", 1)

    text = text.replace("</application>", COMPONENTS + "    </application>", 1)
    manifest.write_text(text, encoding="utf-8")
    print(f"patched {manifest}")


if __name__ == "__main__":
    main()
