#!/usr/bin/env bash
# Installs MEGAcmd (MEGA's official command-line client). Ubuntu 22.04 / 24.04, run with sudo rights.
set -euo pipefail
. /etc/os-release
url="https://mega.nz/linux/repo/xUbuntu_${VERSION_ID}/amd64/megacmd-xUbuntu_${VERSION_ID}_amd64.deb"
tmp="$(mktemp -d)"
curl -fsSL -o "$tmp/megacmd.deb" "$url"
sudo apt-get install -y "$tmp/megacmd.deb"
rm -rf "$tmp"
echo "MEGAcmd installed. Start the server once as the backend user with: mega-cmd-server &  (or run any mega-* command)"
