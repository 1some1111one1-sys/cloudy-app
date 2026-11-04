# Testing

## Automated (run in CI-less form with `scripts/run_tests.sh`; 33 tests)
| Area | What is tested | Against |
|---|---|---|
| TeraBox client | sign-in cookie, quota, list, mkdir, delete, multi-block upload, download incl. fallback, expired session, error codes | an in-process imitation of TeraBox's web endpoints |
| Storage manager | connect, persistence across restart (encrypted), bad/expired cookie, only two providers, folder creation | same imitation |
| MEGA | output parsers, login failure and expired-session mapping | sample MEGAcmd text, NOT a real account |
| Database | schema, upserts | SQLite (PostgreSQL uses the same SQL; not run here) |
| APK inspection | `aapt2` badging parser, ARM-only warning | sample text |
| Install pipeline | download, inspect, install, list, launch, uninstall, failure paths | simulated device and drives |
| Backup / restore | full then incremental (only changed files uploaded), exact restore, removal of extra files, older-backup restore, refusal without root | simulated device (tar, stat) |
| Adaptive bitrate | step down / up, hysteresis, manual override, output size | pure logic |
| Input injection | coordinate scaling, ordering, single touch, keys, text | recorded adb commands |

The Android app is compiled in CI (debug and release). It has no automated UI tests.

## Also automated in CI: on-phone MEGA and TeraBox clients
`android-client/tests` (182 checks: MEGA login v1/v2, 2FA, sessions, browse, upload, download, TeraBox listing, upload, errors) run on every build against in-process imitations. Run locally: `javac -d out --release 17 android-client/app/src/main/java/com/clouddroid/app/core/*.java android-client/tests/*.java && java -cp out com.clouddroid.app.core.TestMain`

## NOT automated, and not yet verified by anyone
These need a KVM server, a phone and real accounts. Do them in this order after deployment:
1. `scripts/smoke_test.py` passes.
2. **TeraBox** (on the phone, no server): Cloud -> sign in on the TeraBox website -> status shows usage; browse; upload a small file; delete it.
3. **MEGA** (on the phone, no server): Sign in with e-mail and password (and 2FA code if asked; if MEGA says it needs an app key, use Advanced); browse; upload; confirm `mega-df` output parses (usage bar fills). If not, send the output of `mega-df` and `mega-ls -l`.
4. **Device**: Device -> Start; wait for "Running"; Android version and CPU / RAM appear.
5. **APK install**: upload a small x86-compatible APK to the drive; Add -> select -> reaches "Installed".
6. **Launch + stream**: Library -> Launch. Picture appears; tap and swipe work; Back/Home/Recents work; keyboard sends text.
7. **Quality**: switch LOW / BALANCED / HIGH; throttle the phone's network and watch Automatic step down.
8. **Reconnect**: toggle airplane mode for ~10 s during a session: the player says "Reconnecting" and returns to the same app state.
9. **Persistence**: Device -> Restart. Installed apps are still there; an app's saved progress survives.
10. **Backup / restore**: Library -> Backup; change data in the app; Restore; confirm the earlier state. Expected to fail for apps that block data access or bind data to device keys.

Known gaps to expect: no audio; single-touch input with noticeable latency; ARM-only apps may not run.
