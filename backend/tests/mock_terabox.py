"""In-process imitation of the TeraBox web endpoints used by TeraBoxClient. This is NOT real TeraBox."""
from __future__ import annotations

import hashlib
import json
import re
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE = "ndus=GOODSESSION; browserid=abc"


class MockTeraBox:
    def __init__(self):
        self.files: dict[str, bytes] = {}
        self.dirs: set[str] = {"/"}
        self.uploads: dict[str, dict] = {}
        self.fail_download_dlink = False
        mock = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _json(self, obj, code=200):
                data = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _authed(self):
                return "ndus=GOODSESSION" in (self.headers.get("Cookie") or "")

            def _body(self):
                n = int(self.headers.get("Content-Length") or 0)
                return self.rfile.read(n) if n else b""

            def do_GET(self):
                u = urllib.parse.urlparse(self.path)
                q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
                if u.path == "/main":
                    page = b'<html><script>fn("ABCDEFGHIJKLMNOPQRSTUVWX")</script>bdstoken:"0123456789abcdef0123456789abcdef"</html>'
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html")
                    self.send_header("Content-Length", str(len(page)))
                    self.end_headers()
                    self.wfile.write(page)
                    return
                if u.path.startswith("/api/") and not self._authed():
                    return self._json({"errno": -6})
                if u.path == "/api/quota":
                    return self._json({"errno": 0, "total": 1000, "used": 250})
                if u.path == "/api/list":
                    d = q["dir"]
                    if d not in mock.dirs:
                        return self._json({"errno": -9})
                    items = []
                    for p in sorted(mock.dirs):
                        if p != "/" and p.rsplit("/", 1)[0] in (d.rstrip("/"), "") and (p.rsplit("/", 1)[0] or "/") == d:
                            items.append({"server_filename": p.rsplit("/", 1)[1], "isdir": 1, "size": 0,
                                          "server_mtime": 1})
                    for p, b in sorted(mock.files.items()):
                        if (p.rsplit("/", 1)[0] or "/") == d:
                            items.append({"server_filename": p.rsplit("/", 1)[1], "isdir": 0, "size": len(b),
                                          "server_mtime": 2})
                    return self._json({"errno": 0, "list": items})
                if u.path == "/api/filemetas":
                    target = json.loads(q["target"])[0]
                    host = self.headers.get("Host")
                    return self._json({"errno": 0, "info": [{"dlink": f"http://{host}/dl?p={urllib.parse.quote(target)}"}]})
                if u.path == "/dl":
                    p = q["p"]
                    if mock.fail_download_dlink or p not in mock.files:
                        self.send_response(403)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    b = mock.files[p]
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(len(b)))
                    self.end_headers()
                    self.wfile.write(b)
                    return
                if u.path == "/rest/2.0/pcs/file" and q.get("method") == "locateupload":
                    return self._json({"errno": 0, "host": self.headers.get("Host")})
                if u.path == "/rest/2.0/pcs/file" and q.get("method") == "download":
                    p = q["path"]
                    b = mock.files.get(p)
                    if b is None:
                        self.send_response(404)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(len(b)))
                    self.end_headers()
                    self.wfile.write(b)
                    return
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def do_POST(self):
                u = urllib.parse.urlparse(self.path)
                q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
                body = self._body()
                if not self._authed():
                    return self._json({"errno": -6})
                if u.path == "/rest/2.0/pcs/superfile2":
                    m = re.search(rb"\r\n\r\n(.*)\r\n--", body, re.S)
                    chunk = m.group(1)
                    up = mock.uploads[q["uploadid"]]
                    up["parts"][int(q["partseq"])] = chunk
                    return self._json({"errno": 0, "md5": hashlib.md5(chunk).hexdigest()})
                form = {k: v[0] for k, v in urllib.parse.parse_qs(body.decode()).items()}
                if u.path == "/api/precreate":
                    uid = "UP" + str(len(mock.uploads))
                    mock.uploads[uid] = {"path": form["path"], "parts": {}}
                    return self._json({"errno": 0, "uploadid": uid, "return_type": 1, "block_list": []
                                       if False else [0, 1, 2][:len(json.loads(form["block_list"]))]})
                if u.path == "/api/create":
                    if form.get("isdir") == "1":
                        mock.dirs.add(form["path"])
                        return self._json({"errno": 0, "path": form["path"]})
                    up = mock.uploads[form["uploadid"]]
                    data = b"".join(up["parts"][i] for i in sorted(up["parts"]))
                    mock.files[form["path"]] = data
                    return self._json({"errno": 0, "path": form["path"]})
                if u.path == "/api/filemanager":
                    for p in json.loads(form["filelist"]):
                        mock.files.pop(p, None)
                        mock.dirs.discard(p)
                    return self._json({"errno": 0})
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
