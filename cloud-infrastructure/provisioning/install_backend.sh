#!/usr/bin/env bash
# Installs the backend into a virtualenv. Run from the repository root.
set -euo pipefail
python3 -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
echo "Installed. Configure cloud-infrastructure/configuration/clouddroid.env, then run scripts/run_backend.sh"
