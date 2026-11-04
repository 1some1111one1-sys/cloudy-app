# CloudDroid

Run Android apps and games on a remote Android device and stream the screen to your phone. The phone is only
a display and input device: APKs, installed apps, app data and saves stay on the server. APKs come from, and
backups go to, your **TeraBox** or **MEGA** drive.

> **Status: early, unverified end to end.** The Android app compiles and the backend logic is unit-tested, but the
> full chain (emulator, WebRTC stream, real TeraBox / MEGA accounts) has not been run together yet.
> Read `documentation/STATUS.md` for exactly what has and has not been verified.

## What works without a server
Install the APK and use **TeraBox and MEGA right away**: sign in to MEGA with e-mail and password (plus the 6-digit
code if your account has two-factor), sign in to TeraBox on TeraBox's own website inside the app. Browse, upload from
the phone, create folders and delete, all directly between the phone and the drive. No URL, no token.

## What needs a server
**Installing and running Android apps** needs your own CloudDroid server: a Linux machine with KVM that runs the
backend and the Android emulator. Nothing else can provide a remote Android device. Add it later in Settings
("Remote device server", optional). See `documentation/DEPLOYMENT.md`.

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
