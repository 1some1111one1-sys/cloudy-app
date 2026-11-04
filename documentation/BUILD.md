# Building

## Android app
CI does it for you: every push that touches `android-client/` runs `.github/workflows/build-android.yml`, which builds
a debug and a release APK, checks the signature, uploads them as a workflow artifact and publishes a GitHub Release.

Locally (JDK 17, Android SDK with platform 35 and build-tools 35.0.0, Gradle 8.9):
```bash
cd android-client
gradle :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```
Or open `android-client/` in Android Studio.

Signing: add repository secrets `CLOUDDROID_KEYSTORE_B64` (base64 of a .jks), `CLOUDDROID_KEYSTORE_PASSWORD`,
`CLOUDDROID_KEY_ALIAS`, `CLOUDDROID_KEY_PASSWORD` to sign with your own key. Without them CI generates a throwaway key,
so each build is signed differently and Android will refuse to update one build with the next: uninstall first.
Never commit a keystore.

## Backend tests
```bash
pip install pytest        # the tests use only the standard library plus pytest
scripts/run_tests.sh
```
