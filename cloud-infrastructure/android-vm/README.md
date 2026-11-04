# Android VM

The remote device is the official Android Emulator (QEMU + KVM) running a `google_apis` x86_64 image.
The backend starts and stops it (`backend/virtualization/emulator.py`); these files only prepare the host.

- `../provisioning/install_android_sdk.sh` - SDK, emulator, system image (checks `/dev/kvm`)
- `create_avd.sh` - creates the persistent AVD and applies CPU / RAM / disk allocation
- `clouddroid-backend.service` - systemd unit for the backend (the emulator is its child process)

Persistence: all apps and data live in `~/.android/avd/<AVD_NAME>.avd/userdata-qemu.img`. Back this file up
(with the emulator stopped). The backend never starts the emulator with `-wipe-data`.
