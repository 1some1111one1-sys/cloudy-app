#!/usr/bin/env python3
"""Manual check of a running backend. Usage: smoke_test.py https://your-server TOKEN
Checks health, auth, device info, storage status and the app list. Needs only the Python standard library."""
import json
import sys
import urllib.error
import urllib.request


def call(base, token, path, method="GET"):
    req = urllib.request.Request(base.rstrip("/") + path, method=method,
                                 headers={"Authorization": f"Bearer {token}"} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    base, token = sys.argv[1:]
    ok = True

    def check(name, cond, detail=""):
        nonlocal ok
        ok &= bool(cond)
        print(("PASS " if cond else "FAIL ") + name + (f"  {detail}" if detail and not cond else ""))

    s, _ = call(base, "", "/api/health"); check("health", s == 200, s)
    s, _ = call(base, "wrong-token", "/api/device"); check("rejects a wrong token", s == 401, s)
    s, d = call(base, token, "/api/device"); check("device info", s == 200 and "status" in d, (s, d))
    for p in ("terabox", "mega"):
        s, d = call(base, token, f"/api/storage/{p}/status"); check(f"{p} status", s == 200 and "connected" in d, (s, d))
    s, d = call(base, token, "/api/storage/gdrive/status"); check("other providers are refused", s == 404, s)
    s, d = call(base, token, "/api/apps"); check("app list", s == 200 and isinstance(d, list), (s, d))
    s, d = call(base, token, "/api/resources"); check("resources", s == 200 and "host" in d, (s, d))
    print("\nAll checks passed." if ok else "\nSome checks FAILED.")
    sys.exit(0 if ok else 1)


main()
