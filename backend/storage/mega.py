"""MEGA via MEGAcmd (MEGA's official command-line client, built on the official MEGA SDK).

MEGA is end-to-end encrypted: MEGAcmd decrypts locally on this server. The session is kept by the MEGAcmd server
process, so CloudDroid never stores the MEGA password.

NOTE: output parsing below follows MEGAcmd's documented output format but has NOT been run against a real MEGA
account in the development environment. Parsers are unit-tested against sample text only.
"""
from __future__ import annotations

import os
import re
import threading
from typing import Callable, Optional

from backend.storage.terabox import Entry, normalize
from backend.virtualization.adb import AdbError, Result, Runner, subprocess_runner


class MegaError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status

    @property
    def auth_expired(self) -> bool:
        return self.status == 401


_UNITS = {"B": 1, "KB": 1024, "MB": 1024 ** 2, "GB": 1024 ** 3, "TB": 1024 ** 4}


def parse_size(token: str) -> int:
    m = re.fullmatch(r"([\d.]+)\s*([KMGT]?B)?", token.strip(), re.I)
    if not m:
        return -1
    return int(float(m.group(1)) * _UNITS[(m.group(2) or "B").upper()])


_LS = re.compile(r"^(\S{4,6})\s+(\S+)\s+(\S+)\s+(\d{2}[A-Za-z]{3}\d{4}\s+\d{2}:\d{2}:\d{2})\s+(.+)$")


def parse_ls(text: str, directory: str) -> list[Entry]:
    out: list[Entry] = []
    for line in text.splitlines():
        m = _LS.match(line.strip())
        if not m or m.group(1).upper() == "FLAGS":
            continue
        flags, size, name = m.group(1), m.group(3), m.group(5).strip()
        is_dir = flags.startswith("d")
        out.append(Entry(normalize(directory + "/" + name), name, is_dir, -1 if is_dir else max(parse_size(size), 0), 0))
    return out


def parse_df(text: str) -> tuple[int, int]:
    m = re.search(r"USED STORAGE:\s+(\S+(?:\s*[KMGT]?B)?)\s+[\d.]+%\s+of\s+(\S+(?:\s*[KMGT]?B)?)", text, re.I)
    if not m:
        return -1, -1
    return parse_size(m.group(1)), parse_size(m.group(2))


def parse_whoami(text: str) -> Optional[str]:
    m = re.search(r"e-?mail:\s*(\S+@\S+)", text, re.I)
    return m.group(1) if m else None


class MegaClient:
    def __init__(self, runner: Runner = subprocess_runner, prefix: str = "mega-"):
        self.run = runner
        self.prefix = prefix

    def _cmd(self, name: str, *args: str, timeout: float = 120, check: bool = True) -> Result:
        try:
            r = self.run([self.prefix + name, *args], None, timeout)
        except AdbError as e:
            raise MegaError(502, str(e).replace("Command not found", "MEGAcmd not found")) from e
        if check and r.rc != 0:
            msg = (r.out + r.err).decode("utf-8", "replace").strip()
            low = msg.lower()
            if "not logged in" in low or "login required" in low or "session" in low and "expired" in low:
                raise MegaError(401, "MEGA session expired. Connect MEGA again.")
            if "not found" in low or "couldn't find" in low:
                raise MegaError(404, f"Not found: {msg}")
            raise MegaError(502, f"MEGA {name} failed: {msg[-300:]}")
        return r

    # ---- session
    def login(self, email: str, password: str) -> None:
        self._cmd("logout", check=False)
        r = self.run([self.prefix + "login", email, password], None, 120)
        if r.rc != 0:
            msg = (r.out + r.err).decode("utf-8", "replace").strip()
            if "invalid" in msg.lower() or "credentials" in msg.lower() or "failed to login" in msg.lower():
                raise MegaError(401, "MEGA refused the e-mail or password (or two-factor is required).")
            raise MegaError(502, "MEGA login failed: " + msg[-300:])

    def logout(self) -> None:
        self._cmd("logout", check=False)

    def account(self) -> Optional[str]:
        r = self._cmd("whoami", check=False, timeout=30)
        return parse_whoami(r.text) if r.rc == 0 else None

    def quota(self) -> tuple[int, int]:
        r = self._cmd("df", timeout=30)
        return parse_df(r.text)

    # ---- files
    def list(self, directory: str = "/") -> list[Entry]:
        d = normalize(directory)
        return parse_ls(self._cmd("ls", "-l", d).text, d)

    def mkdir(self, path: str) -> None:
        self._cmd("mkdir", "-p", normalize(path))

    def delete(self, path: str) -> None:
        self._cmd("rm", "-r", "-f", normalize(path))

    def download(self, path: str, dest: str, size: int = -1,
                 progress: Optional[Callable[[int, int], None]] = None) -> None:
        p = normalize(path)
        folder = os.path.dirname(dest) or "."
        os.makedirs(folder, exist_ok=True)
        stop = threading.Event()
        name = p.rsplit("/", 1)[-1]
        partial = os.path.join(folder, name)

        def watch() -> None:  # MEGAcmd writes the file in place; report its growth as progress
            while not stop.wait(1.0):
                try:
                    if progress and size > 0:
                        progress(os.path.getsize(partial), size)
                except OSError:
                    pass
        t = threading.Thread(target=watch, daemon=True)
        t.start()
        try:
            self._cmd("get", p, folder, timeout=6 * 3600)
        finally:
            stop.set()
        if partial != dest:
            os.replace(partial, dest)

    def upload(self, path: str, src: str, progress: Optional[Callable[[int, int], None]] = None) -> None:
        p = normalize(path)
        parent = p.rsplit("/", 1)[0] or "/"
        self._cmd("mkdir", "-p", parent, check=False)
        staged = src
        want = p.rsplit("/", 1)[-1]
        if os.path.basename(src) != want:  # MEGAcmd names the remote file after the local one
            staged = os.path.join(os.path.dirname(src) or ".", want)
            os.replace(src, staged)
        try:
            self._cmd("put", "-c", staged, parent, timeout=6 * 3600)
        finally:
            if staged != src and os.path.exists(staged):
                os.replace(staged, src)
        if progress:
            size = os.path.getsize(src)
            progress(size, size)
