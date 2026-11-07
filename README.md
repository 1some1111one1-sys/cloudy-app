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

1. Open **BG App Runner** and tap **Pick APK and install**.
2. Tap **Launch** next to the app that appears.
3. Turn on **Background keep-alive** and allow the notification.
4. Tap **Allow unrestricted battery use** and approve the dialog.
5. Open **App info** and allow autostart / background activity if your phone has that option
   (Xiaomi, Oppo, Vivo, Realme, Samsung, Huawei all hide it under different names).

## Floating button

While a virtual app is on screen, a small round **BG** button floats on top. Drag it anywhere. Tap it
to keep the app running in the background and go to the home screen. Hold it to hide it until the
next time an app opens. It needs "Display over other apps" permission: tap **Floating button** in
the app once to allow it.

While keep-alive is on, the app also attaches to the processes the virtual apps run in, which
raises their priority so the phone is less likely to stop them.

## Xiaomi / Redmi (HyperOS)

Xiaomi's battery manager is aggressive. Try these first: **Xiaomi: autostart settings**,
**Xiaomi: battery saver** (No restrictions), lock the app in recents, and use the BG button
instead of swiping the app away.

If apps still stop, turn on **Backup: silent audio mode**. It plays inaudible audio and shows a
media player in the notification shade, so the phone treats the app like a music app. It is off
by default because it uses more battery. The switch shows its live status, and notifications
must be allowed for the player to appear.

## What to expect

- The container runs apps inside this app's processes, so any phone's battery manager can still
  stop them. Steps 3 to 5 improve the odds a lot, but nothing here can guarantee it.
- Only one complete `.apk` file works. Split bundles (`.apks`, `.xapk`, `.apkm`) do not, and the base
  APK taken from a Play Store split install installs but shows a blank screen. The app warns you
  when it detects either case.
- If an app opens to a blank or black screen, tap **Copy debug log**, then paste it to get the real error.
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
| `overlay/app/src/main/java/.../runner/RunnerActivity.java` | Launcher screen: pick APK, list, launch, stop, remove, keep-alive toggle |
| `overlay/app/src/main/java/.../runner/KeepAliveService.java` | Foreground service with a partial wake lock |
| `scripts/apply_overlay.py` | Copies the overlay into the engine and patches its manifest |
| `.github/workflows/build-apk.yml` | Builds the APK on GitHub Actions |
