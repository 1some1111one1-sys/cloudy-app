# BG App Runner

An Android app that runs other apps inside itself. You pick an APK, it installs into a virtual
container, and you launch it from there. A foreground service plus a wake lock tries to keep it
running while the screen is off or you are in another app.

It is built on the open-source [NewBlackbox](https://github.com/ALEX5402/NewBlackbox) virtual engine
(Apache-2.0). This repo only holds the overlay: a launcher screen (`RunnerActivity`), the keep-alive
service (`KeepAliveService`), and a script that patches them into the engine at build time. GitHub
Actions fetches the engine, applies the overlay, and builds the APK.

## Get the APK

1. Create a new empty repository on GitHub (any name).
2. Push this folder to it:
   ```
   git init -b main
   git add .
   git commit -m "BG App Runner"
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Open the **Actions** tab. The "Build APK" workflow starts on push (about 10 to 20 minutes the
   first time). If it does not, open it and press **Run workflow**.
4. When it finishes, open the **Releases** page of the repo and download the APK there. (The same files are also attached to the run as the `bg-app-runner-apk` artifact.)
5. Install the APK that matches your phone: `arm64-v8a` for almost every phone made after 2017.
   `universal` also works, and `armeabi-v7a` is for old 32-bit phones.

The APK is debug-signed, so you may need to allow "install unknown apps" for your browser or file
manager.

## Use it

**Look:** calm liquid glass. A deep blue backdrop with soft lights that drift, swell and slowly change
shade (mostly blue, with violet, teal and a touch of rose), flowing contour lines, faint rings, tiny
floating lights and a grid of dots that creeps along, with frosted glass panels on top. The same
backdrop is behind the BlackBox manager screens. It moves all the time while the screen shows it, at
about 30 frames per second from a few cached gradients, whatever the phone's animation or battery
saver settings say. It only stops while the screen is off or another app is in front.

The main screen is laid out top to bottom:

- **BlackBox manager** (the big card at the top): the engine's own manager. Install apps from your
  phone or a file, create home-screen shortcuts, clear app data, set a fake location, Google
  services and settings. Its screens use the same colours as the main screen.
- **Your apps**: everything installed in the container. Tap a row (or **Launch**) to start an app;
  the three dots stop it or remove it (removing asks first, because it deletes the app's data).
  **+ Add APK** installs a single APK file.
- **Keep running in the background**, **Sound**, **Allow on this phone**, **Opening files**,
  **Google services**, **Help**: the settings, grouped by purpose. The two phone settings the app can
  read (battery use, notifications) show **Allowed / Not allowed** and **On / Blocked**.
- Messages appear in a bar at the bottom of the screen and go away by themselves.

Getting started:

1. Open **BG App Runner** and tap **Open BlackBox manager** (or **+ Add APK**) to add an app.
2. Tap **Launch** next to the app that appears.
3. Turn on **Keep apps running** and allow the notification.
4. Tap **Allow unrestricted battery use** and approve the dialog.
5. Open **Autostart in background** and **Battery saver** (or **App info**) and allow background
   activity if your phone has that option (many brands hide it under different names).

## Floating button

While a virtual app is on screen, a small round **BG** button floats on top. Drag it anywhere. Tap it
to keep the app running in the background and go to the home screen. Hold it to hide it until the
next time an app opens. It needs "Display over other apps" permission: tap **Floating button** in
the app once to allow it.

While keep-alive is on, the app also binds to the processes the virtual apps run in with
high importance, which raises their priority so the phone is less likely to stop them.

**Stay active in background** (on by default, experimental): after you tap BG, the virtual app is
not told that its screen went away. It never gets its pause/stop callbacks, so it keeps believing
it is on screen and does not reload when you come back. Android still removes the window surface
of a hidden app, so a game that draws every frame may stall its picture while hidden, even though
the app itself keeps running. Turn the switch off if an app misbehaves.

**Mute app sound in background** (on by default): after you tap BG, the virtual app's own audio
players are set to volume 0 and restored when you come back, so only this app's media player is
left. It covers sound played through Android's normal players (MediaPlayer, ExoPlayer, SoundPool,
Java AudioTrack) and, through a small native hook on the system's AudioTrack, most game audio
(OpenSL ES and the older AAudio path). Low-latency AAudio streams that bypass AudioTrack may still
be heard. The debug log shows `BgMute: native audio hooks installed: N of 5` when it is active. The native
part is only put into a game when this switch is on at the moment the game starts, so if a game
closes by itself, turn the switch off, stop the game (three dots, Stop app) and start it again.

## Links and Google services

Web links (`http` and `https`) that a virtual app opens go to the phone's own browser.

**Open with:** when you open a file from another app (a PDF from your file manager, say), the
list now includes **Open in BG App Runner**. It lists every app in the container, with the ones that
say they support that kind of file first, and sends the file to the one you pick, even if it does
not declare support for it. It never refuses a file. Android only lets an
app list itself once, so every container app is behind that one entry rather than listed by its
own name. The file is copied into this app first, so edits are saved to the copy, not the original.
Turn it off with the **Show in the "Open with" menu** switch.

**Sign-in links that return to an app** (for example Minecraft's Microsoft sign-in, which opens your
browser and then comes back through an `ms-xal-...://auth` address): Android only knows this
app, not the apps inside it, so a small screen in this app catches that address and passes it to
the container app. The build registers it for Minecraft's address. Other apps that sign in through
the browser need their own address added to the manifest in `scripts/apply_overlay.py`.

When the browser sends you back, the answer goes to the screen of the app that is waiting for it,
the way Android would deliver it, and only then is the app brought to the front. If the real
Minecraft is also installed, Android asks which one should open the link: pick **BG App Runner**
("Continue in BG App Runner"). **Always** is fine if you only sign in inside the container, but then
the real Minecraft's own sign-in links also come here, so **Just once** is the safer choice. The
browser page you signed in on can still be open on top of the game after returning; close it
(back or X) and the game is behind it.

**Google Play services in the container** copies the phone's Google Play services, Play Store and
Play Games into the container (the same switch the full BlackBox manager has). The phone must
have them installed. Apps then find Google services as on a normal phone. Features that check the
app's original package name and signing key, such as Google sign-in or Play Games for some apps,
can still refuse to work because the app is not running as itself.

**Signing in to Google** (Play Store, or any app that asks for a Google account): install Play
services and Play Store into the same space as the app, then tap Sign in. The engine's account
service never recorded which space an account belongs to, so every account looked like it was in
space 0 and sign-in from any other space died with `Not allowed to bind to service ...`
(`GoogleAccountAuthenticatorService`) and closed the app that asked. `apply_overlay.py` now sets the
space on every lookup, rebuilds the list of sign-in services when an app is installed (it used to
keep only the newest app's), and turns a refused connection into a normal "sign-in failed"
answer. The steps after that (Google's own sign-in screens, device registration, tokens) have not
been seen working yet. The log lines to look for carry the tag `BlackBoxAccounts`.

A **Sign in** button sits next to every space that has Google services turned on (Settings > GMS
Manager) and in the "Google services" card of the main screen. It sends the same "add a Google
account" request Android's Settings sends (`GoogleSignIn.java`), opens the sign-in screen Google
returns inside the container, and opens Play Store once an account was added.

## If apps still stop

Phone battery managers can be aggressive. Try these first: **Autostart in background** and
**Battery saver** (No restrictions) in the app, lock the app in recents, and use the BG button
instead of swiping the app away. On phones without those screens, the buttons open App info.

If that is not enough, turn on **Backup: silent audio mode**. It plays inaudible audio and shows a
media player in the notification shade, so the phone treats the app like a music app. It is off
by default because it uses more battery. The switch shows its live status, and notifications
must be allowed for the player to appear.

## Privacy

The engine this app is built on contains a built-in "send logs" feature: it uploads the app's whole
log, with device details, to a server run by the engine's author, and does so automatically after a
crash. This build switches it off (`apply_overlay.py` blanks the chat id it needs) and removes the
**Send Logs** entry from the manager's settings. **Copy debug log** only copies to your clipboard.

## What to expect

- On phones where the app runs in a second user (Xiaomi Second space, work profile, "user 10"),
  apps used to crash on launch with `requires INTERACT_ACROSS_USERS`. The build patches the engine
  so guests see the real user. The debug log shows `androidUser=` so you can tell which user you are in.

- The container runs apps inside this app's processes, so any phone's battery manager can still
  stop them. Steps 3 to 5 improve the odds a lot, but nothing here can guarantee it.
- Only one complete `.apk` file works. Split bundles (`.apks`, `.xapk`, `.apkm`) do not, and the base
  APK taken from a Play Store split install installs but shows a blank screen. The app warns you
  when it detects either case.
- If an app opens to a blank or black screen, or closes by itself, tap **Copy debug log**, then paste
  it to get the real error. The report holds: the settings, how each container process ended (Android's
  own record: exit reason, signal, and for a native crash the call stack and the last log lines),
  the crash reports the container saved (the engine hides the error message of an app that dies from
  an uncaught exception; `BlackBoxCrash` in the log carries it from now on), the apps inside the
  container with their files (APK, libraries, game data and OBB), and the recent log.
- Apps that depend on Google Play services, root or integrity checks, banking apps, and some games
  may refuse to run or crash.
- The engine says it supports Android 5 to 14+. Newer versions may have problems. If the build
  breaks after the engine changes, pin it: run the workflow manually and set `engine_ref` to a
  commit that worked.
- Android 15 and later limit what foreground services can start from the background. The keep-alive
  service is started while the app is open, which is allowed.

## Files

| Path | What it is |
| --- | --- |
| `overlay/app/src/main/java/.../runner/RunnerActivity.java` | Main screen: BlackBox manager card, app list, grouped settings |
| `overlay/app/src/main/java/.../runner/Glass*.java` | The glass look: live backdrop, frosted panel, switch, colours |
| `overlay/app/src/main/java/.../view/base/BaseActivity.kt` | Puts the live backdrop behind every manager screen |
| `overlay/app/src/main/res/` | Colours, theme and layouts that restyle the BlackBox manager screens |
| `overlay/Bcore/src/main/java/.../fake/frameworks/GoogleSignIn.java` | Starts "add a Google account" for one space and opens Google's sign-in screen in the container |
| `overlay/app/src/main/java/.../view/gms/` | GMS Manager list with the Sign in button (copies of the engine's two files, changed) |
| `overlay/app/src/main/java/.../runner/DebugReport.java` | Pieces of the debug report: process exit reasons, saved crash reports, apps and files inside the container |
| `overlay/app/src/main/java/.../runner/Tombstone.java` | Reads the native crash record Android keeps for a process (signal, call stack, last log lines) |
| `overlay/app/src/main/java/.../runner/KeepAliveService.java` | Foreground service with a partial wake lock |
| `overlay/Bcore/.../StayActive.java` | Engine-side hook behind "Stay active in background" and the Java side of the mute |
| `overlay/Bcore/src/main/cpp/BgMute/BgMute.cpp` | Native AudioTrack hook that mutes game audio in the background |
| `scripts/apply_overlay.py` | Copies the overlay into the engine and patches its manifest and sources |
| `.github/workflows/build-apk.yml` | Builds the APK on GitHub Actions |
