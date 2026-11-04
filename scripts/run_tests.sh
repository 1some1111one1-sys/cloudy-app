#!/usr/bin/env bash
# Backend unit tests (no emulator, no cloud accounts needed).
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -I -m pytest -q backend/tests
