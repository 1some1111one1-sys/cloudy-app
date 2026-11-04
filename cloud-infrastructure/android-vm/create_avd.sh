#!/usr/bin/env bash
# Creates the persistent Android Virtual Device (once). The backend can also do this itself on first start.
# The AVD's userdata image (~/.android/avd/<name>.avd/userdata-qemu.img) holds all installed apps and app data:
# BACK IT UP and never run the emulator with -wipe-data.
set -euo pipefail
. "${CLOUDDROID_ENV:-$(dirname "$0")/../configuration/clouddroid.env}"
echo no | "$AVDMANAGER_PATH" create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_5 --force
cfg="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
set_kv() { grep -q "^$1=" "$cfg" && sed -i "s|^$1=.*|$1=$2|" "$cfg" || echo "$1=$2" >> "$cfg"; }
set_kv hw.ramSize "$DEVICE_RAM_MB"
set_kv hw.cpu.ncore "$DEVICE_CPU_CORES"
set_kv disk.dataPartition.size "${DEVICE_DISK_GB}G"
set_kv hw.keyboard yes
echo "AVD $AVD_NAME ready: ${DEVICE_CPU_CORES} cores, ${DEVICE_RAM_MB} MB RAM, ${DEVICE_DISK_GB} GB disk"
