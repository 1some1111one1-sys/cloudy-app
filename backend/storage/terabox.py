"""TeraBox client — EXPERIMENTAL and UNOFFICIAL.

TeraBox has no public storage API. This talks to the same web endpoints the TeraBox website uses, authenticated
with the session cookie the user obtains by signing in on the real TeraBox website (inside a WebView on the phone).
CloudDroid never sees the TeraBox password; it only stores the cookie.

TeraBox can change these endpoints at any time, and automated access may be against its terms of service.
Failures carry TeraBox's own error number so they can be diagnosed. Ported from the approach used in the
`cloudmemory` repository (TeraBoxClient.java). Standard library only.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable, Optional

DEFAULT_SITE = "https://www.1024terabox.com"
APP_ID = "250528"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
BLOCK = 4 * 1024 * 1024
_JS_TOKEN = re.compile(r'fn\("([^"\s]{16,})"\)')
_BDS_TOKEN = re.compile(r'bdstoken(?:\\"|%22|"|\')?\s*(?::|%3A|=)\s*(?:\\?"|%22|\')?([0-9a-fA-F]{32})')
_TERABOX_DOMAINS = ("terabox.com", "1024terabox.com", "terabox.app", "teraboxapp.com", "1024tera.com",
                    "4funbox.com", "mirrobox.com", "nephobox.com", "momerybox.com", "tibibox.com", "freeterabox.com")


class TeraBoxError(Exception):
    """status: 401 auth expired, 403 security check, 404 not found, 412 exists, 507 full, 502 other."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status

    @property
    def auth_expired(self) -> bool:
        return self.status == 401


@dataclass
class Entry:
    path: str
    name: str
    is_dir: bool
    size: int
    modified: int  # epoch seconds


def errno_error(errno: int, what: str) -> TeraBoxError:
    status, why = 502, "TeraBox refused"
    if errno in (-6, 31045):
        status, why = 401, "TeraBox says you are not signed in. Sign in again"
    elif errno in (-9, 31066, 31190) or (errno == 2 and what.startswith("Listing")):
        status, why = 404, "not found"
    elif errno in (-8, 31061, 12):
        status, why = 412, "it already exists"
    elif errno == 31062:
        status, why = 400, "TeraBox does not allow that name"
    elif errno in (4000023, 400031, 400040):
        status, why = 403, "TeraBox wants a security check; open the TeraBox website once, solve it, then sign in again"
    elif errno in (31064, 31208, 10):
        status, why = 507, "your TeraBox storage may be full"
    return TeraBoxError(status, f"{what} failed: {why} (TeraBox error {errno})")


def normalize(path: str) -> str:
    parts = [p for p in path.split("/") if p and p != "."]
    out: list[str] = []
    for p in parts:
        if p == "..":
            if out:
                out.pop()
        else:
            out.append(p)
    return "/" + "/".join(out)


def _join(d: str, name: str) -> str:
    return normalize(d + "/" + name)


def _parent(p: str) -> str:
    return normalize(p.rsplit("/", 1)[0] or "/")


def _hostname(url: str) -> str:
    return urllib.parse.urlparse(url).hostname or ""


def _registrable(host: str) -> str:
    parts = host.split(".")
    if len(parts) <= 2 or re.fullmatch(r"[\d.:]+", host):
        return host
    return ".".join(parts[-2:])


class TeraBoxClient:
    def __init__(self, cookie: str, site: str = DEFAULT_SITE, api_bases: Optional[list[str]] = None,
                 timeout: float = 60.0):
        if not cookie or "ndus=" not in cookie:
            raise ValueError("Sign in on the TeraBox website first (no session cookie).")
        site = (site or DEFAULT_SITE).strip()
        if "://" not in site:
            site = "https://" + site
        u = urllib.parse.urlparse(site)
        if not u.hostname or u.scheme not in ("http", "https"):
            raise ValueError("The TeraBox address must look like https://www.1024terabox.com")
        self.scheme = u.scheme
        self.site_host = u.hostname + (f":{u.port}" if u.port else "")
        self.site = f"{self.scheme}://{self.site_host}"
        self.cookie = cookie.strip() if "PANWEB=" in cookie else cookie.strip() + "; PANWEB=1"
        self.timeout = timeout
        self.bases = api_bases or self._candidate_bases()
        self.base: Optional[str] = None
        self.upload_base: Optional[str] = None
        self.js_token: Optional[str] = None
        self.bdstoken: Optional[str] = None
        self._tokens_fetched = False
        self._failed_bases: set[str] = set()
        self._listings: dict[str, list[Entry]] = {}

    # ------------------------------------------------------------------ plumbing
    def _candidate_bases(self) -> list[str]:
        bare = re.sub(r":\d+$", "", self.site_host)
        local = bool(re.fullmatch(r"[\d.:]+", bare)) or bare == "localhost"
        out: list[str] = []
        if not local and not bare.startswith("dm."):
            root = bare[4:] if bare.startswith("www.") else bare
            out.append(f"{self.scheme}://dm.{root}")
        out.append(f"{self.scheme}://{self.site_host}")
        return out

    def _base(self) -> str:
        return self.base or self.bases[0]

    def _same_family(self, url: str) -> bool:
        h = _hostname(url).lower()
        if not h:
            return False
        if any(h == _hostname(b).lower() for b in self.bases):
            return True
        bare = re.sub(r":\d+$", "", self.site_host).lower()
        mine = _registrable(bare)
        if h == bare or h == mine or h.endswith("." + mine):
            return True
        return any(h == d or h.endswith("." + d) for d in _TERABOX_DOMAINS)

    def _headers(self, url: str, extra: Optional[dict] = None) -> dict:
        h = {"User-Agent": UA, "Accept": "application/json, text/plain, */*",
             "Referer": self._base() + "/main?category=all", "Origin": self._base()}
        if self._same_family(url):
            h["Cookie"] = self.cookie
        if extra:
            h.update(extra)
        return h

    def _open(self, req: urllib.request.Request):
        # Never follow redirects automatically: a redirect from an API call means "signed out".
        class _NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):  # noqa: D401
                return None
        opener = urllib.request.build_opener(_NoRedirect)
        return opener.open(req, timeout=self.timeout)

    def _request(self, method: str, url: str, body: Optional[bytes] = None, headers: Optional[dict] = None):
        req = urllib.request.Request(url, data=body, method=method, headers=self._headers(url, headers))
        try:
            resp = self._open(req)
            return resp.status, dict(resp.headers.items()), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers.items()), e.read()
        except (urllib.error.URLError, OSError) as e:
            raise TeraBoxError(502, f"could not reach {_hostname(url)}: {e}") from e

    def _finish(self, status: int, raw: bytes, url: str, what: str) -> dict:
        if status in (301, 302, 303, 307):
            raise TeraBoxError(401, f"{what} failed: TeraBox sent us to its sign-in page. Sign in again.")
        if status // 100 != 2:
            where = re.sub(r"^[a-z]+://", "", url.split("?")[0])
            raise TeraBoxError(status, f"{what} failed: TeraBox answered HTTP {status} [{where}]")
        try:
            m = json.loads(raw.decode("utf-8"))
            if not isinstance(m, dict):
                raise ValueError
        except ValueError:
            snippet = re.sub(r"\s+", " ", raw[:120].decode("utf-8", "replace"))
            raise TeraBoxError(502, f"{what} failed: TeraBox did not answer with data CloudDroid understands "
                                    f"(its website may have changed). Start of the reply: {snippet}")
        errno = int(m.get("errno", 0) or 0)
        if errno != 0:
            raise errno_error(errno, what)
        return m

    def _wrong_server(self, e: TeraBoxError) -> bool:
        return e.status == 404 and "answered HTTP 404" in str(e)

    def _failover(self, call: Callable[[], dict]) -> dict:
        first: Optional[TeraBoxError] = None
        for _ in range(len(self.bases)):
            try:
                return call()
            except TeraBoxError as e:
                if not self._wrong_server(e):
                    raise
                first = first or e
                self._failed_bases.add(self._base())
                nxt = next((b for b in self.bases if b not in self._failed_bases), None)
                if nxt is None:
                    break
                self.base = nxt
        assert first is not None
        raise first

    def _common(self) -> str:
        s = f"app_id={APP_ID}&web=1&channel=dubox&clienttype=0"
        if self.js_token:
            s += "&jsToken=" + urllib.parse.quote(self.js_token, safe="")
        if self.bdstoken:
            s += "&bdstoken=" + urllib.parse.quote(self.bdstoken, safe="")
        return s

    def _tokens(self) -> None:
        if self._tokens_fetched:
            return
        page_base = None
        for b in self.bases:
            try:
                status, _, raw = self._request("GET", b + "/main?category=all")
            except TeraBoxError:
                continue
            if status // 100 != 2:
                continue
            page = urllib.parse.unquote(raw.decode("utf-8", "replace"))
            m = _JS_TOKEN.search(page)
            if m:
                self.js_token = m.group(1)
            t = _BDS_TOKEN.search(page)
            if t:
                self.bdstoken = t.group(1)
            page_base = page_base or b
            if self.js_token:
                page_base = b
                break
        if page_base:
            self.base = page_base
        fields = f"app_id={APP_ID}&web=1&channel=dubox&clienttype=0"
        js = f"&jsToken={urllib.parse.quote(self.js_token, safe='')}" if self.js_token else ""
        if not self.bdstoken:
            try:
                url = (self._base() + "/api/gettemplatevariable?" + fields + "&fields="
                       + urllib.parse.quote('["bdstoken"]', safe="") + js)
                s, _, raw = self._request("GET", url)
                self.bdstoken = (self._finish(s, raw, url, "Opening TeraBox").get("result") or {}).get("bdstoken")
            except TeraBoxError:
                pass
        if not self.bdstoken:
            try:
                url = self._base() + "/api/home/info?" + fields + js
                s, _, raw = self._request("GET", url)
                m = self._finish(s, raw, url, "Opening TeraBox")
                self.bdstoken = (m.get("data") or {}).get("bdstoken") or m.get("bdstoken")
            except TeraBoxError:
                pass
        self._tokens_fetched = True

    def _api_get(self, endpoint: str, extra: str, what: str) -> dict:
        self._tokens()

        def call() -> dict:
            url = f"{self._base()}/api/{endpoint}?{self._common()}{extra}"
            s, _, raw = self._request("GET", url)
            return self._finish(s, raw, url, what)
        return self._failover(call)

    def _api_post(self, endpoint: str, extra_query: str, form: str, what: str) -> dict:
        self._tokens()

        def call() -> dict:
            url = f"{self._base()}/api/{endpoint}?{self._common()}{extra_query}"
            s, _, raw = self._request("POST", url, form.encode("utf-8"),
                                      {"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"})
            return self._finish(s, raw, url, what)
        return self._failover(call)

    @staticmethod
    def _q(s: str) -> str:
        return urllib.parse.quote(s, safe="")

    # ------------------------------------------------------------------ operations
    def quota(self) -> tuple[int, int]:
        """(used_bytes, total_bytes)."""
        m = self._api_get("quota", "&checkfree=1&checkexpire=1", "Reading storage")
        return int(m.get("used", -1)), int(m.get("total", -1))

    def list(self, directory: str = "/") -> list[Entry]:
        d = normalize(directory)
        out: list[Entry] = []
        for page in range(1, 501):
            m = self._api_get("list", f"&dir={self._q(d)}&order=name&desc=0&showempty=1&num=1000&page={page}",
                              f'Listing "{d}"')
            items = m.get("list") or []
            for f in items:
                name = f.get("server_filename")
                if not name:
                    continue
                is_dir = int(f.get("isdir", 0)) == 1
                out.append(Entry(_join(d, name), name, is_dir, -1 if is_dir else int(f.get("size", -1)),
                                 int(f.get("server_mtime", 0))))
            if len(items) < 1000:
                break
        self._listings[d] = out
        return out

    def _find(self, path: str) -> Entry:
        p = normalize(path)
        for e in self.list(_parent(p)):
            if e.path == p:
                return e
        raise TeraBoxError(404, f"Not found: {p}")

    def mkdir(self, path: str) -> None:
        p = normalize(path)
        if p == "/":
            return
        self._api_post("create", "", f"path={self._q(p)}&isdir=1&size=0&block_list={self._q('[]')}&rtype=0",
                       f'Creating the folder "{p}"')

    def delete(self, path: str) -> None:
        p = normalize(path)
        if p == "/":
            raise TeraBoxError(400, "Cannot delete the root")
        self._find(p)
        self._api_post("filemanager", "&opera=delete&async=0&onnest=fail",
                       "filelist=" + self._q(json.dumps([p])), f'Deleting "{p}"')

    def download(self, path: str, dest: str, progress: Optional[Callable[[int, int], None]] = None) -> None:
        p = normalize(path)
        e = self._find(p)
        if e.is_dir:
            raise TeraBoxError(400, f'"{e.name}" is a folder')
        failures: list[str] = []
        dlink = None
        try:
            m = self._api_get("filemetas", f"&target={self._q(json.dumps([p]))}&dlink=1", "Downloading")
            info = m.get("info") or []
            dlink = info[0].get("dlink") if info and isinstance(info[0], dict) else None
            if not dlink:
                failures.append("TeraBox gave no download address")
        except TeraBoxError as ex:
            failures.append(str(ex))
        for url in ([dlink] if dlink else []) + [
                f"{self._base()}/rest/2.0/pcs/file?method=download&app_id={APP_ID}&path={self._q(p)}"]:
            err = self._fetch_file(url, dest, e.size, progress)
            if err is None:
                return
            failures.append(err)
        raise TeraBoxError(502, f'Downloading "{e.name}" failed: ' + "; ".join(failures))

    def _fetch_file(self, url: str, dest: str, expected: int, progress) -> Optional[str]:
        current = url
        for _ in range(6):
            host = _hostname(current)
            headers = {"Referer": self.site + "/main?category=all", "Origin": self.site}
            req = urllib.request.Request(current, headers=self._headers(current, headers))
            try:
                resp = self._open(req)
            except urllib.error.HTTPError as ex:
                loc = ex.headers.get("Location")
                if ex.code in (301, 302, 307, 308) and loc:
                    current = urllib.parse.urljoin(current, loc)
                    continue
                body = re.sub(r"\s+", " ", ex.read(160).decode("utf-8", "replace")).strip()
                return f"{host}: HTTP {ex.code} {body}".strip()
            except (urllib.error.URLError, OSError) as ex:
                return f"{host}: {ex}"
            ctype = resp.headers.get("Content-Type", "")
            got = 0
            with open(dest, "wb") as fh:
                while True:
                    chunk = resp.read(256 * 1024)
                    if not chunk:
                        break
                    fh.write(chunk)
                    got += len(chunk)
                    if progress:
                        progress(got, expected)
            page_type = ctype.startswith("text/") or "json" in ctype
            if (expected >= 0 and got != expected) or (page_type and expected < 0):
                os.remove(dest)
                return f"{host}: got {got} bytes" + (f" instead of {expected}" if expected >= 0 else "")
            return None
        return "too many redirects"

    def upload(self, path: str, src: str, progress: Optional[Callable[[int, int], None]] = None) -> None:
        p = normalize(path)
        if p == "/":
            raise TeraBoxError(400, "Cannot write to the root")
        parent = _parent(p)
        if parent != "/" and not self._find(parent).is_dir:
            raise TeraBoxError(409, "The destination is not a folder")
        existing: Optional[Entry] = None
        try:
            existing = self._find(p)
        except TeraBoxError as e:
            if e.status != 404:
                raise
        if existing and existing.is_dir:
            raise TeraBoxError(409, f'A folder named "{existing.name}" already exists')

        size = os.path.getsize(src)
        md5s: list[str] = []
        with open(src, "rb") as fh:
            while True:
                chunk = fh.read(BLOCK)
                if chunk or not md5s:
                    md5s.append(hashlib.md5(chunk).hexdigest())
                if len(chunk) < BLOCK:
                    break
        block_list = json.dumps(md5s)
        rtype = "3" if existing else "1"
        mtime = int(os.path.getmtime(src))
        pre = self._api_post(
            "precreate", "",
            f"path={self._q(p)}&target_path={self._q(parent)}&autoinit=1&isdir=0&size={size}"
            f"&block_list={self._q(block_list)}&rtype={rtype}&local_mtime={mtime}",
            f'Starting the upload of "{p.rsplit("/", 1)[-1]}"')
        upload_id = pre.get("uploadid") or ""
        needed = pre.get("block_list")
        send = [True] * len(md5s)
        if isinstance(needed, list) and needed:
            send = [i in needed for i in range(len(md5s))]
        elif isinstance(needed, list):  # empty list: TeraBox already has every block
            send = [False] * len(md5s)
        if int(pre.get("return_type", 1)) == 2:
            send = [False] * len(md5s)

        uploaded = 0
        bases: Optional[list[str]] = None
        with open(src, "rb") as fh:
            for i, md5 in enumerate(md5s):
                chunk = fh.read(BLOCK)
                if not send[i]:
                    uploaded += len(chunk)
                    continue
                if bases is None:
                    bases = self._upload_candidates(p, upload_id)
                used = self._send_block(bases, p, upload_id, i, chunk, md5)
                bases = [used] + [b for b in bases if b != used]
                self.upload_base = used
                uploaded += len(chunk)
                if progress:
                    progress(uploaded, size)

        created = self._api_post(
            "create", "",
            f"path={self._q(p)}&target_path={self._q(parent if parent.endswith('/') else parent + '/')}&size={size}"
            f"&isdir=0&rtype={rtype}&uploadid={self._q(upload_id)}&block_list={self._q(block_list)}&local_mtime={mtime}",
            f'Saving "{p.rsplit("/", 1)[-1]}"')
        saved = created.get("path")
        if saved and saved != p:
            raise TeraBoxError(409, f'TeraBox saved the file as "{saved.rsplit("/", 1)[-1]}" instead of '
                                    f'"{p.rsplit("/", 1)[-1]}"')

    def _local_site(self) -> bool:
        return self.site_host.startswith("127.") or self.site_host.startswith("localhost")

    def _upload_candidates(self, path: str, upload_id: str) -> list[str]:
        out: list[str] = []
        if self.upload_base:
            out.append(self.upload_base)
        else:
            out.extend(self._locate_upload_hosts(path, upload_id))
        if not self._local_site():
            root = _registrable(re.sub(r":\d+$", "", self.site_host))
            out += [f"{self.scheme}://c-jp.{root}", "https://c-jp.terabox.com", self._base(), "https://c.terabox.com"]
        out.append(self.site)
        seen: list[str] = []
        for b in out:
            if b not in seen:
                seen.append(b)
        return seen

    def _locate_upload_hosts(self, path: str, upload_id: str) -> list[str]:
        bare = re.sub(r":\d+$", "", self.site_host)
        root = _registrable(bare)
        asks = [self.site] if self._local_site() else [
            self._base(), f"{self.scheme}://d.{root}", "https://d.terabox.com"]
        for base in asks:
            try:
                url = (f"{base}/rest/2.0/pcs/file?method=locateupload&appid={APP_ID}&path={self._q(path)}"
                       f"&uploadid={self._q(upload_id)}&upload_version=2.0")
                s, _, raw = self._request("GET", url)
                m = self._finish(s, raw, url, "Finding the upload server")
                found: list[str] = []
                host = m.get("host")
                if host:
                    found.append(host if "://" in host else f"{self.scheme}://{host}")
                for sv in m.get("servers") or []:
                    server = sv.get("server") if isinstance(sv, dict) else None
                    if server and server.startswith("https://") and server not in found:
                        found.append(server)
                if found:
                    return found
            except TeraBoxError:
                continue
        return []

    def _send_block(self, bases: list[str], path: str, upload_id: str, index: int, chunk: bytes, md5: str) -> str:
        notes: list[str] = []
        last: Optional[TeraBoxError] = None
        for b in bases:
            try:
                self._send_block_to(b, path, upload_id, index, chunk, md5)
                return b
            except TeraBoxError as e:
                if e.status not in (403, 404, 405, 502) and e.status // 100 != 5:
                    raise
                last = e
                notes.append(f"{_hostname(b)}: {e}")
        raise TeraBoxError(last.status if last else 502,
                           "Uploading failed: no TeraBox upload server took the file. " + "; ".join(notes))

    def _send_block_to(self, base: str, path: str, upload_id: str, index: int, chunk: bytes, md5: str) -> None:
        boundary = "----CloudDroid" + secrets.token_hex(8)
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="blob"\r\n'
                f"Content-Type: application/octet-stream\r\n\r\n").encode("latin-1") + chunk + \
               f"\r\n--{boundary}--\r\n".encode("latin-1")
        url = (f"{base}/rest/2.0/pcs/superfile2?method=upload&type=tmpfile&app_id={APP_ID}&channel=dubox"
               f"&clienttype=0&web=1&path={self._q(path)}&uploadid={self._q(upload_id)}&uploadsign=0&partseq={index}")
        status, _, raw = self._request("POST", url, body, {
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Origin": self.site, "Referer": self.site + "/main?category=all"})
        if status // 100 != 2:
            raise TeraBoxError(status, f"TeraBox's upload server {_hostname(base)} answered HTTP {status}")
        m = self._finish(status, raw, url, "Uploading")
        got = m.get("md5")
        if got and got.lower() != md5.lower():
            raise TeraBoxError(500, f"TeraBox received part {index + 1} damaged (checksum mismatch)")
