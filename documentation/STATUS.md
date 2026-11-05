# CloudDroid - build and verification status

"Verified" means it was actually compiled or run. Updated at the end of each work session.

| Area | State | Verified how |
|---|---|---|
| Android app: Home, Cloud, Library, Add, Device, Player, Settings | written | compiles in GitHub Actions (debug + release APK). Never run on a phone. |
| On-phone TeraBox (web sign-in) and MEGA (e-mail + password, 2FA) - no server needed | written | clients ported from the `cloudmemory` repo; 253 checks run in CI against in-process imitations of both services. NEVER run against real TeraBox or MEGA. |
| Server-side MEGA (MEGAcmd) and TeraBox (for the server to download APKs) | written | parsers tested on sample text; never run against MEGA |
| Backend REST + WebSocket (FastAPI) | written | syntax-checked only: FastAPI could not be installed here |
| Backend services (install, apps, backup, storage, ABR, input) | written | 33 unit tests pass |
| PostgreSQL | schema written | SQLite runs the same SQL; PostgreSQL never run |
| Android emulator control (adb, lifecycle, persistent AVD) | written | tested with a simulated device; no KVM here |
| WebRTC streaming (aiortc capture/encode, Android decode) | written | not run: needs emulator, aiortc, a phone |
| Streaming a drive file on the phone without downloading it (range reads, capped RAM + disk cache, proxy file descriptor, in-app player) | written | range reads, cache limits, read-ahead and concurrency: 71 checks in CI against imitations of MEGA and TeraBox. The Android player (MediaPlayer on the proxy descriptor) compiles but was NEVER run on a phone. Whether the real MEGA and TeraBox file servers honour range requests as the imitations do is UNVERIFIED (MEGA's `url/first-last` form is what its own SDK uses; TeraBox's Range support is assumed). |
| Running another app's code on the phone from the drive | NOT implemented | see below |
| Audio | NOT implemented | emulator runs with -no-audio |
| Multi-touch / low-latency input | NOT implemented | single touch via `adb shell input` |
| Multi-user | schema only | API uses one shared token |
| Release APK | built by CI | signature checked in CI; throwaway key unless you add secrets |

## Running apps on the phone ("without installing", from the drive, RAM and cache only)
Not built. What exists is the storage half (read a file on demand, bounded memory and cache). The execution half is a
different and much larger problem:
- Android runs only installed apps. Running a guest APK without installing it means an app-virtualization container
  (what VirtualApp / BlackBox-style engines do): intercepting the system's package and activity services, loading the
  guest's dex and native libraries from a private location, and redirecting its file access. That is a large, fragile
  engine that depends on the phone's Android version and vendor, and it cannot be tested without a phone.
- Many games and Google-Play-dependent apps detect and refuse such containers.
- Native libraries must be real files (or memory-backed) when loaded, and games read big asset files at random; over a
  phone connection that means stalls. "Without downloading the whole app" and "only RAM and cache" would have to be
  traded against play quality.
- The server-streaming path (needs a server) runs any app, because the app really runs on a real Android device.

## Why things could not be verified here
- The development workspace has no KVM or GPU, no phone, and no TeraBox / MEGA accounts.
- PyPI is blocked there, so FastAPI, aiortc, psycopg and cryptography were never installed.
- TeraBox has no public API: the integration is unofficial and can break whenever TeraBox changes its site.

## Most likely first failures on a real deployment
1. `pip install` version conflicts (requirements are ranges, not pins).
2. MEGAcmd output formats differing from the parsers.
3. TeraBox changing a web endpoint or requiring a security check.
4. aiortc / PyAV H.264 encode CPU load on small servers.
5. R8 (release build) stripping something at runtime: try the debug APK to tell.
