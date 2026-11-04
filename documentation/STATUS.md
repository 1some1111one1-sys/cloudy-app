# CloudDroid - build and verification status

"Verified" means it was actually compiled or run. Updated at the end of each work session.

| Area | State | Verified how |
|---|---|---|
| Android app: Home, Cloud, Library, Add, Device, Player, Settings | written | compiles in GitHub Actions (debug + release APK). Never run on a phone. |
| On-phone TeraBox (web sign-in) and MEGA (e-mail + password, 2FA) - no server needed | written | clients ported from the `cloudmemory` repo; 182 checks run in CI against in-process imitations of both services. NEVER run against real TeraBox or MEGA. |
| Server-side MEGA (MEGAcmd) and TeraBox (for the server to download APKs) | written | parsers tested on sample text; never run against MEGA |
| Backend REST + WebSocket (FastAPI) | written | syntax-checked only: FastAPI could not be installed here |
| Backend services (install, apps, backup, storage, ABR, input) | written | 33 unit tests pass |
| PostgreSQL | schema written | SQLite runs the same SQL; PostgreSQL never run |
| Android emulator control (adb, lifecycle, persistent AVD) | written | tested with a simulated device; no KVM here |
| WebRTC streaming (aiortc capture/encode, Android decode) | written | not run: needs emulator, aiortc, a phone |
| Audio | NOT implemented | emulator runs with -no-audio |
| Multi-touch / low-latency input | NOT implemented | single touch via `adb shell input` |
| Multi-user | schema only | API uses one shared token |
| Release APK | built by CI | signature checked in CI; throwaway key unless you add secrets |

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
