"""In-memory stand-ins for the Android device and cloud drives, used by the service tests."""
from __future__ import annotations

import io
import shlex
import tarfile

from backend.storage.terabox import Entry
from backend.virtualization.adb import Adb


class FakeAdb(Adb):
    """Simulates just enough of a rooted device: a file system, packages, and tar."""

    def __init__(self):
        super().__init__("adb-fake")
        self.files: dict[str, tuple[bytes, int]] = {}
        self.clock = 1000
        self.installed: dict[str, str] = {}
        self.launched: list[str] = []
        self.can_root = True
        self.owner = 10123

    # file helpers
    def put(self, path: str, data: bytes) -> None:
        self.clock += 10
        self.files[path] = (data, self.clock)

    def root(self) -> bool:
        return self.can_root

    def uid(self, package: str):
        return self.owner

    def force_stop(self, package: str) -> None:
        pass

    def clear_data(self, package: str) -> None:
        for p in [p for p in self.files if p.startswith(f"/data/data/{package}/")]:
            del self.files[p]

    def shell(self, command: str, timeout: float = 60, check: bool = True) -> str:
        if command.startswith("find "):
            root = shlex.split(command)[1]
            return "".join(f"{len(d)} {m} {p}\n" for p, (d, m) in sorted(self.files.items()) if p.startswith(root + "/"))
        if command.startswith("rm -f "):
            self.files.pop(shlex.split(command)[2], None)
        return ""

    def exec_out(self, command: str, timeout: float = 600) -> bytes:
        assert command.startswith("tar -cf - -C / ")
        names = shlex.split(command.split(" 2>/dev/null")[0])[5:]
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w") as tar:
            for n in names:
                data, mtime = self.files["/" + n]
                ti = tarfile.TarInfo(n)
                ti.size, ti.mtime = len(data), mtime
                tar.addfile(ti, io.BytesIO(data))
        return buf.getvalue()

    def shell_stdin(self, command: str, data: bytes, timeout: float = 600) -> None:
        assert command == "tar -xf - -C /"
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            for m in tar.getmembers():
                self.files["/" + m.name] = (tar.extractfile(m).read(), m.mtime)

    # apps
    def install(self, apk_path: str, timeout: float = 1800) -> None:
        self.installed["installed"] = apk_path

    def packages(self):
        return sorted(self.installed_packages) if hasattr(self, "installed_packages") else []

    def launch(self, package: str) -> None:
        self.launched.append(package)

    def uninstall(self, package: str) -> None:
        getattr(self, "installed_packages", set()).discard(package)

    def app_size_mb(self, package: str) -> int:
        return 42

    def state(self) -> str:
        return "device"


class FakeEmulator:
    def __init__(self, status: str = "running"):
        self._status = status
        self.ensured = 0

    def status(self) -> str:
        return self._status

    def ensure_running(self, timeout: float = 0) -> None:
        self.ensured += 1
        self._status = "running"


class FakeStorage:
    """Drop-in for StorageManager's file operations, backed by a dict."""

    def __init__(self):
        self.blobs: dict[tuple[str, str], bytes] = {}
        self.uploads: list[tuple[str, str]] = []

    def upload(self, provider, path, local, progress=None):
        with open(local, "rb") as fh:
            self.blobs[(provider, path)] = fh.read()
        self.uploads.append((provider, path))

    def download(self, provider, path, local, progress=None, size=-1):
        data = self.blobs[(provider, path)]
        with open(local, "wb") as fh:
            fh.write(data)
        if progress:
            progress(len(data), len(data))

    def exists(self, provider, path):
        d = self.blobs.get((provider, path))
        return None if d is None else Entry(path, path.rsplit("/", 1)[-1], False, len(d), 0)
