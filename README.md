# CloudDroid

Run Android apps and games on a remote Android device and stream the screen to your phone. The phone is only
a display and input device: APKs, installed apps, app data and saves stay on the server. APKs come from, and
backups go to, your **TeraBox** or **MEGA** drive.

> **Status: early, unverified end to end.** The Android app compiles and the backend logic is unit-tested, but the
> full chain (emulator, WebRTC stream, real TeraBox / MEGA accounts) has not been run together yet.
> Read `documentation/STATUS.md` for exactly what has and has not been verified.

## Run APKs INSIDE CloudDroid (no Android install, no server) - experimental
CloudDroid embeds a virtualization engine (NewBlackbox, Apache-2.0, vendored in `android-client/engine/`). In the Add tab
(default "Run inside CloudDroid") pick a single `.apk` on your drive; it is streamed into the engine and the app then
runs inside CloudDroid's own processes. It does not appear in Android's app list.
- **Storage is the drive:** *Save & free space* zips the app's data (readable here because the app lives inside
  CloudDroid) to `/CloudDroid/data/<package>/` and removes the app and its data from the phone. *Run* brings the app back
  (APK + latest saved data) when it is not on the phone.
- **Honest limits:** the engine uses hidden Android APIs and native hooks, so it can fail on some Android versions or
  vendors. Many apps refuse to run in it (games with anti-tamper, banking, Google Play services apps, some online
  games). Only single `.apk` files work (not `.xapk` / `.apks` / split bundles). The release build is 64-bit only.
  While an app runs it needs its APK and data on the phone (in CloudDroid's private storage): the drive is the
  permanent store, the phone holds what is in use. Apps are not streamed block by block while running.
- **Side effects of embedding it:** CloudDroid now targets Android API 28 and allows cleartext traffic (guest apps need
  both); release lint checks are off because of the vendored code.

## Apps on this phone, saved on your drive (no server)
- **App library** lists the apps installed on this phone. They run on the phone, with its own RAM and cache. Open and
  uninstall work as usual.
- **Save app** copies an app's APK file(s) (base + splits) to `/CloudDroid/apps/<package>/<version>/` on TeraBox or MEGA.
- **Add tab → Install** brings a saved app (or any APK you choose on the drive) back: the APK is read from the drive
  piece by piece straight into Android's installer, not stored in the app's storage. Android asks you to confirm, and
  the first time you must allow CloudDroid to install apps (Android shows the settings screen).
- **Save data / Restore data** zips a folder **you choose** (once per app) into `/CloudDroid/data/<package>/` and restores
  it later. **Limit:** Android does not let one app read another app's private data (`/data/data`, `Android/data`),
  and games' main data usually lives there. Only data in folders Android lets you pick (for example a save folder in
  shared storage) can be saved; the rest needs a rooted phone. Uninstalling an app deletes its private data for good.
- Updating an app from a different signing key than the installed one is refused by Android.

## What works without a server
Install the APK and use **TeraBox and MEGA right away**: sign in to MEGA with e-mail and password (plus the 6-digit
code if your account has two-factor), sign in to TeraBox on TeraBox's own website inside the app. Browse, upload from
the phone, create folders and delete, all directly between the phone and the drive. No URL, no token.

## Play files from the drive without downloading them (on the phone, RAM + cache only)
In the Cloud tab, audio and video files get a play button. The file is **not downloaded**: the app asks the drive for
the parts being played (HTTP range requests; for MEGA the ranges are decrypted on the phone), keeps recent pieces in a
capped amount of memory (at most 64 MB) and a capped amount of cache space (256 MB to 2 GB, your choice), and drops the
oldest pieces first. Seeking jumps straight to the wanted part. Under the hood this is a general read-on-demand file
(`core/RemoteFile`, `core/BlockCache`, and an Android proxy file descriptor in `stream/StreamHub.kt`).

**This is not "run an app from the drive".** Android only runs apps that are installed, and nothing in this project
runs another app's code on your phone. See `documentation/STATUS.md` ("Running apps on the phone") for why, and for what
it would take.

## What needs a server (optional)
Only the **remote device** mode: running apps on a separate Android device in the cloud and streaming its screen. It
needs your own Linux machine with KVM running the backend and an emulator. Add it in Settings ("Remote device server").
See `documentation/DEPLOYMENT.md`.

## Layout
```
android-client/          Kotlin + Jetpack Compose app (Material 3, WebRTC)
  app/src/main/java/com/clouddroid/app/
    ui/                  screens: Home, Cloud, Library, Add, Device, Player, Settings
    cloud/               on-phone TeraBox + MEGA (LocalCloud), backend REST client, TeraBox web sign-in
    core/                MEGA and TeraBox protocol clients (pure Java, tested in CI)
    streaming/           WebRTC client, touch/keyboard capture, reconnect, stats
    device/              data models     settings/   DataStore settings
backend/                 Python FastAPI
  api/ main.py           REST + WebSocket signalling
  services/              APK install, app management, incremental backups, monitoring
  streaming/             aiortc session, screen capture, input injection, adaptive bitrate
  virtualization/        adb wrapper, emulator lifecycle
  storage/               TeraBox (unofficial web session), MEGA (MEGAcmd), unified manager
  database/              PostgreSQL / SQLite access
cloud-infrastructure/    SDK + MEGAcmd install scripts, AVD creation, systemd, docker-compose, Caddy, env example
database/schema/         schema.sql
scripts/                 run_backend.sh, run_tests.sh, smoke_test.py
documentation/           ARCHITECTURE, DEPLOYMENT, BUILD, TESTING, STATUS
```

## Documentation
- `documentation/ARCHITECTURE.md` - design, chosen virtualization, limitations
- `documentation/DEPLOYMENT.md` - server specs, step-by-step setup, connecting the phone
- `documentation/BUILD.md` - building the APK and running the backend tests
- `documentation/TESTING.md` - what is automated and the manual end-to-end checklist
- `documentation/STATUS.md` - verified vs unverified
