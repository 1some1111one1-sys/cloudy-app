# CloudDroid — Build and verification status

Updated as work proceeds. "Verified" means it was actually built or run.

| Area | State | Verified |
|---|---|---|
| Repository cleared (old code in history at 0f9b344) | done | yes |
| Project structure | created | yes |
| Architecture and limitations document | written | n/a |
| Stage 1: Android client | minimal screen only (full UI not built) | debug APK compiles in CI; not run on a device |
| Stage 2: Backend | not started | no |
| TeraBox web sign-in (WebView cookie) + Python client | written | Python client: 8 tests pass against a mock server (not real TeraBox). Android side: compiles in GitHub Actions (debug APK built), not run on a device. FastAPI routes: syntax-checked only |
| Stage 3: MEGA, backup sync | not started | no |
| Stage 4: Android virtualization | not started | no |
| Stage 5: APK installation | not started | no |
| Stage 6: WebRTC streaming | not started | no |
| Stage 7: Cloud persistence | not started | no |
| Stage 8 to 10: performance, tests, release | not started | no |

## Not possible from this workspace
- The workspace has no KVM and no GPU, so the emulator and streaming pipeline cannot be run end to end here.
- It has no TeraBox or MEGA credentials, so cloud integrations cannot be tested against real accounts here.
- A release-signed APK needs a keystore that only the owner should hold.

## Notes
- PyPI is blocked in the development workspace, so FastAPI, httpx and a PostgreSQL driver could not be installed there. The TeraBox client uses only the Python standard library for that reason.
- TeraBox sign-in is unofficial and has only been tested against an imitation server. The first real sign-in is the true test.
