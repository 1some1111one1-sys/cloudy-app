# Deployment

## 1. What you must deploy yourself
| Piece | Where | Notes |
|---|---|---|
| Android emulator + backend | Linux host with `/dev/kvm` | bare metal or a VM type with nested virtualization |
| PostgreSQL | same host or managed | `database/schema/schema.sql` |
| TLS reverse proxy | same host | Caddy config included |
| TURN server (coturn) | same host, UDP 3478 + 49160-49200 open | needed on mobile networks / strict NAT |
| MEGAcmd | same host | only if you use MEGA |

## 2. Server specification
Per user, minimum for light apps: 4 vCPU (x86_64 with VT-x/AMD-V), 8 GB RAM, 64 GB SSD, 20 Mbps upstream per stream.
3D games additionally need a GPU host (set `EMULATOR_GPU=host`) and 8 vCPU / 16 GB RAM. Ubuntu 22.04 or 24.04.
Most ordinary cloud VMs do **not** expose `/dev/kvm`; check with `ls /dev/kvm` before paying for a server.

## 3. Steps (Ubuntu, as a normal user with sudo)
```bash
git clone https://github.com/1some1111one1-sys/cloudy-app.git /opt/clouddroid && cd /opt/clouddroid
sudo usermod -aG kvm $USER        # then log out and in again

# Android SDK, emulator, system image (refuses to continue without /dev/kvm)
cloud-infrastructure/provisioning/install_android_sdk.sh /opt/android-sdk

# Optional, for MEGA
cloud-infrastructure/provisioning/install_megacmd.sh

# Configuration
cp cloud-infrastructure/configuration/clouddroid.env.example cloud-infrastructure/configuration/clouddroid.env
#   edit it: set CLOUDDROID_TOKEN, CLOUDDROID_SECRET_KEY, DATABASE_URL, the SDK paths printed by the SDK script

# PostgreSQL + coturn + Caddy (edit the Caddyfile domain first; export POSTGRES_PASSWORD, TURN_REALM, TURN_USER, TURN_PASSWORD)
docker compose -f cloud-infrastructure/configuration/docker-compose.yml up -d

# Backend
cloud-infrastructure/provisioning/install_backend.sh
cloud-infrastructure/android-vm/create_avd.sh        # optional: the backend creates the AVD on first start
scripts/run_backend.sh                               # or install the systemd unit in cloud-infrastructure/android-vm/

# Check it
python3 scripts/smoke_test.py https://clouddroid.example.com YOUR_TOKEN
```

## 4. Connect the phone
1. Install `CloudDroid-release.apk` from the repository's Releases page (allow installs from this source).
2. Settings -> enter `https://your-domain` and the access token -> *Save and test*.
3. Cloud -> sign in to TeraBox (on TeraBox's own website inside the app) and/or connect MEGA.
4. Upload an APK to the drive (Cloud -> Upload), then Add -> select it. The server downloads it and installs it.
5. Library -> Launch. The first launch boots the emulator, which takes a few minutes.

## 5. Security notes
- Always use HTTPS. The access token and (for MEGA) the password cross the network.
- MEGA's password is passed to `mega-login` as a command-line argument, visible in `ps` on that host: do not share the host with untrusted users.
- TeraBox sessions are encrypted at rest with `CLOUDDROID_SECRET_KEY`. Losing the key means reconnecting TeraBox.
- The single shared token means one user per server today. The schema is multi-user ready; the API is not.
