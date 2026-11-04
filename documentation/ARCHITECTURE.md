# CloudDroid — Architecture

CloudDroid runs Android apps and games on a remote Android device and streams the screen to a phone.
The phone is only a display and input device. APKs, installed apps, app data and saves stay on the server.

## 1. Components

```
 Phone (android-client)                        Cloud server (you deploy this)
┌──────────────────────────┐  REST + WS    ┌──────────────────────────────────────────┐
│ Compose UI, Material 3   │──────────────▶│ backend/ (FastAPI)                       │
│ Cloud storage browser    │               │  - sessions, devices, apps, backups      │
│ Player (WebRTC + decoder)│◀── WebRTC ───▶│  - WebRTC signalling + media (aiortc)    │
│ Touch/keyboard capture   │  video/audio  │  - storage service (TeraBox, MEGA)       │
└──────────────────────────┘               │ PostgreSQL                               │
                                           │ Android emulator (KVM) + adb             │
                                           │  - persistent qcow2 userdata disk        │
                                           └──────────────────────────────────────────┘
```

| Component | Folder | Responsibility |
|---|---|---|
| Android client | `android-client/` | UI, account connections, file browsing, player, input capture |
| Backend | `backend/` | REST API, WebSocket signalling, device and app management, backups |
| Streaming engine | `backend/streaming/` | Captures emulator screen and audio, encodes H.264, WebRTC peer, input injection |
| Android VM | `cloud-infrastructure/android-vm/` | Emulator image, launch scripts, persistent disk |
| Database | `database/schema/` | PostgreSQL schema |

## 2. Android virtualization: chosen technology

**Choice: the official Android Emulator (QEMU with KVM acceleration) running an x86_64 Google APIs system image, controlled through `adb`, on a bare-metal or nested-virtualization Linux host.**

Why:
- It is a real Android system, so APKs install and run normally.
- With KVM and x86_64 images it runs near native speed on x86 hosts.
- It supports a persistent `userdata-qemu.img`, so installs and app data survive restarts.
- It exposes `adb` (install, launch, input, `screenrecord`) and a gRPC control endpoint (screenshots, audio, touch events).

Rejected or deferred options:
- **Plain Docker.** A container shares the host kernel and has no Android system in it. Docker is used only to wrap the emulator, and the container still needs `/dev/kvm`.
- **Anbox / Waydroid.** They need specific kernel modules (binder, ashmem) and have limited GPU and Google-services support. Possible later for ARM hosts.
- **Managed cloud phones.** Valid, but ties the project to a vendor and cannot be self-hosted.

### Limitations (read before deploying)
1. **KVM is mandatory.** Without `/dev/kvm` the emulator is unusably slow. Most standard cloud VMs do not expose it. Use bare metal or an instance type with nested virtualization.
2. **x86_64 only.** Apps that ship only ARM native libraries run through the system image's ARM translation, which is slower and not guaranteed for every game.
3. **GPU.** Heavy 3D games need a GPU. Software rendering (SwiftShader) is far too slow for them. A GPU host with hardware GL/Vulkan is needed for those titles.
4. **Anti-emulator checks.** Many games and banking apps detect emulators and refuse to run. This cannot be fixed here.
5. **Google Play Services / Play Integrity.** Apps that require licensed Play checks will fail on an emulator image.
6. **One emulator per user.** Each emulator uses several CPU cores and several GB of RAM, which sets the cost per user.

## 3. Cloud storage integration

TeraBox and MEGA are file stores. They are not execution environments and cannot be mounted as app storage.
APKs are downloaded from the drive onto the server, installed with `adb`, and data lives on the persistent virtual disk.
Backups go back to the drive.

### MEGA
MEGA publishes an official SDK and client tooling (MEGA SDK, MEGAcmd). The backend drives MEGAcmd (`mega-login`, `mega-ls`, `mega-get`, `mega-put`, `mega-df`) as an external process on the server. MEGA is end-to-end encrypted, so MEGAcmd does the decryption locally; the backend never needs a MEGA-hosted API key beyond the SDK's own.

### TeraBox
**No official public storage API for personal accounts could be confirmed.** The libraries that exist (for example `aioterabox` on PyPI) are explicitly unofficial and work by logging in with account credentials and reusing web session cookies. CloudDroid's TeraBox adapter takes the same approach and is therefore:
- **Unofficial.** It can break whenever TeraBox changes its web endpoints.
- **Subject to TeraBox terms of service.** Using it is the account owner's decision and risk.
- **Session-based.** Sessions expire; the adapter reports `AUTH_EXPIRED` and the client prompts a reconnect. Some logins require an extra verification step that must be completed by the user.

If TeraBox publishes an official API, only `backend/storage/terabox.py` needs to change.

### Backups
Android blocks other apps from reading `/data/data/<package>`. On the emulator, `adb root` is available on userdebug/non-production images, so backups use that. On such images:
- Full backup: tar of `/data/data/<pkg>` and `/sdcard/Android/data/<pkg>`.
- Incremental backup: file-level manifest (path, size, mtime, SHA-256). Only new or changed files are uploaded as chunks; unchanged files are referenced from the previous backup.
- Restore is best effort. Apps that bind data to keystore keys, device IDs or accounts may not restore correctly. CloudDroid does not claim perfect restore.

## 4. Streaming

- Video: H.264, from the emulator frame source, encoded on the server, sent over WebRTC.
- Audio: Opus over WebRTC.
- Input: WebRTC data channel carrying touch and key events, injected on the server.
- Adaptive bitrate: the client reports RTT, loss and decode drops; the server steps through LOW / BALANCED / HIGH profiles.
- Reconnect: the session id outlives the peer connection. A dropped client re-signals against the same session and the app keeps running.

| Profile | Resolution | FPS | Video bitrate |
|---|---|---|---|
| LOW | 480p | 24 | 1.0 Mbps |
| BALANCED | 720p | 30 | 3.0 Mbps |
| HIGH | 1080p | 60 | 8.0 Mbps |

## 5. Server specification

Per concurrent user (minimum, for light apps):
- 4 vCPU (x86_64 with VT-x/AMD-V, `/dev/kvm` available)
- 8 GB RAM
- 64 GB SSD for the emulator disk plus APK and backup staging
- 20 Mbps up per active stream

For 3D games add an NVIDIA GPU with working GL/Vulkan in the emulator host, and 8 vCPU / 16 GB RAM.
The control plane (FastAPI + PostgreSQL) is light: 2 vCPU / 4 GB RAM is enough for early use.
OS: Ubuntu 22.04 or 24.04.

## 6. What must be deployed separately
- Linux host with KVM (emulator + adb).
- PostgreSQL.
- Backend service.
- TURN server (coturn) for users behind strict NATs.
- TLS reverse proxy (Caddy or nginx).

## 7. Build status
Tracked in `documentation/STATUS.md`. Nothing in this repository is claimed to work until it has been built and tested; STATUS.md records what has and has not been verified.
