#!/usr/bin/env bash
# Compiles the pure-Java parts (drive clients + block store) and runs the tests. Needs only a JDK (11+).
#   bash tests/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -Xlint:all -Xlint:-deprecation -d "$OUT" app/src/main/java/com/cloudy/app/core/*.java app/src/main/java/com/cloudy/app/store/*.java tests/*.java
echo "== block store (cache budgets, crash recovery, outage, read-ahead) =="
java -cp "$OUT" com.cloudy.app.store.StoreSelfTest
echo; echo "== block store on Google Drive, MEGA, TeraBox, WebDAV (imitation servers) =="
LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp "$OUT" com.cloudy.app.core.TestDrives
