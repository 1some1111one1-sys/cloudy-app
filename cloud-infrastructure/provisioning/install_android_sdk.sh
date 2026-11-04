#!/usr/bin/env bash
# Installs the Android SDK pieces the backend needs on an Ubuntu 22.04/24.04 host that has /dev/kvm.
# Run as the user that will run the backend.  Usage: ./install_android_sdk.sh [/opt/android-sdk]
set -euo pipefail

SDK="${1:-/opt/android-sdk}"
IMAGE="${SYSTEM_IMAGE:-system-images;android-34;google_apis;x86_64}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"   # Google's published command-line tools build

if [ ! -e /dev/kvm ]; then
  echo "ERROR: /dev/kvm not found. The emulator needs hardware virtualization (bare metal or nested virtualization)." >&2
  exit 1
fi
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
  echo "ERROR: this user cannot use /dev/kvm. Run: sudo usermod -aG kvm \$USER   (then log in again)" >&2
  exit 1
fi

sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless unzip curl libpulse0 libgl1 libnss3 libxcomposite1 libxcursor1 \
  libxi6 libxtst6 libasound2t64 2>/dev/null || sudo apt-get install -y libasound2

sudo mkdir -p "$SDK" && sudo chown "$USER" "$SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export ANDROID_SDK_ROOT="$SDK"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "emulator" "build-tools;35.0.0" "$IMAGE"

echo
echo "Done. Add to clouddroid.env:"
echo "  ADB_PATH=$SDK/platform-tools/adb"
echo "  EMULATOR_PATH=$SDK/emulator/emulator"
echo "  AVDMANAGER_PATH=$SDK/cmdline-tools/latest/bin/avdmanager"
echo "  AAPT2_PATH=$SDK/build-tools/35.0.0/aapt2"
