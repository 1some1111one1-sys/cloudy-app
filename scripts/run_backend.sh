#!/usr/bin/env bash
# Starts the backend on port 8000. Put a TLS reverse proxy in front (see cloud-infrastructure/configuration/Caddyfile).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
. "${CLOUDDROID_ENV:-cloud-infrastructure/configuration/clouddroid.env}"
set +a
exec .venv/bin/uvicorn backend.main:app --host 127.0.0.1 --port 8000
