import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from backend.database.db import open_database  # noqa: E402
from backend.storage.manager import StorageError, StorageManager  # noqa: E402
from backend.storage.mega import MegaClient, MegaError, parse_df, parse_ls, parse_size, parse_whoami  # noqa: E402
from backend.storage.terabox import TeraBoxClient  # noqa: E402
from backend.tests.mock_terabox import COOKIE, MockTeraBox  # noqa: E402
from backend.virtualization.adb import Result  # noqa: E402


class PlainBox:  # test double for the encryption box
    def encrypt(self, s): return "enc:" + s
    def decrypt(self, t): return t[4:]


@pytest.fixture()
def server():
    s = MockTeraBox().start()
    yield s
    s.stop()


@pytest.fixture()
def db():
    d = open_database("sqlite:///:memory:")
    yield d
    d.close()


def manager(db, server):
    return StorageManager(db, "u", PlainBox(), mega=MegaClient(runner=lambda *a: Result(0, b"", b"")),
                          terabox_factory=lambda cookie, site: TeraBoxClient(cookie, site=server.url, timeout=10))


def test_terabox_connect_list_status_and_persistence(db, server):
    m = manager(db, server)
    assert m.status("terabox")["connected"] is False
    m.connect_terabox(COOKIE, server.url)
    s = m.status("terabox")
    assert s["connected"] and (s["used_bytes"], s["total_bytes"]) == (250, 1000)
    m.mkdir("terabox", "/CloudDroid")
    assert any(e.name == "CloudDroid" for e in m.list("terabox", "/"))
    # a restarted server restores the connection from the (encrypted) stored session
    m2 = manager(db, server)
    assert m2.status("terabox")["connected"]
    assert db.one("SELECT secret FROM cloud_accounts WHERE provider='terabox'")["secret"].startswith("enc:")


def test_terabox_bad_cookie_is_rejected_and_not_stored(db, server):
    m = manager(db, server)
    with pytest.raises(StorageError) as e:
        m.connect_terabox("ndus=WRONG", server.url)
    assert e.value.status == 401
    assert db.query("SELECT * FROM cloud_accounts") == []


def test_expired_session_is_dropped(db, server):
    m = manager(db, server)
    m.connect_terabox(COOKIE, server.url)
    m._tb.cookie = "ndus=EXPIRED; PANWEB=1"  # TeraBox stops accepting the cookie
    with pytest.raises(StorageError) as e:
        m.list("terabox", "/")
    assert e.value.auth_expired if hasattr(e.value, "auth_expired") else e.value.status == 401
    assert m.status("terabox")["connected"] is False
    assert db.query("SELECT * FROM cloud_accounts") == []


def test_only_two_providers_exist(db, server):
    with pytest.raises(StorageError) as e:
        manager(db, server).list("gdrive", "/")
    assert e.value.status == 404


def test_upload_creates_parent_folders_and_downloads_back(db, server, tmp_path):
    m = manager(db, server)
    m.connect_terabox(COOKIE, server.url)
    src = tmp_path / "x.bin"
    src.write_bytes(b"hello" * 1000)
    m.upload("terabox", "/CloudDroid/backups/pkg/x.bin", str(src))
    out = tmp_path / "y.bin"
    m.download("terabox", "/CloudDroid/backups/pkg/x.bin", str(out))
    assert out.read_bytes() == src.read_bytes()


# ---------------------------------------------------------------- MEGAcmd parsing (sample text, not a real account)
LS = """FLAGS        VERS             SIZE DATE                NAME
d---         -                   - 07Nov2023 12:34:56  Games
-----        1           123456789 07Nov2023 12:34:56  big game.apk
"""


def test_mega_parsers():
    es = parse_ls(LS, "/")
    assert [(e.name, e.is_dir, e.size) for e in es] == [("Games", True, -1), ("big game.apk", False, 123456789)]
    assert es[1].path == "/big game.apk"
    assert parse_df("USED STORAGE:               7516192768    35.00% of 21474836480") == (7516192768, 21474836480)
    assert parse_df("USED STORAGE:  7.0 GB   35.00% of 20.0 GB")[1] == 20 * 1024 ** 3
    assert parse_whoami("Account e-mail: me@example.com\n") == "me@example.com"
    assert parse_size("1.5 KB") == 1536


def test_mega_login_failure_and_expired_mapping():
    def bad(args, stdin, timeout):
        return Result(1, b"", b"Failed to login: Invalid credentials") if args[0] == "mega-login" else Result(0, b"", b"")
    with pytest.raises(MegaError) as e:
        MegaClient(runner=bad).login("a@b.c", "x")
    assert e.value.status == 401

    def expired(args, stdin, timeout):
        return Result(1, b"Not logged in.", b"")
    with pytest.raises(MegaError) as e2:
        MegaClient(runner=expired).list("/")
    assert e2.value.auth_expired
