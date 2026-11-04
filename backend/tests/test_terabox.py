import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from backend.storage.terabox import TeraBoxClient, TeraBoxError, errno_error  # noqa: E402
from backend.tests.mock_terabox import COOKIE, MockTeraBox  # noqa: E402


@pytest.fixture()
def server():
    s = MockTeraBox().start()
    yield s
    s.stop()


def client(server, cookie=COOKIE):
    return TeraBoxClient(cookie, site=server.url, timeout=10)


def test_requires_session_cookie(server):
    with pytest.raises(ValueError):
        TeraBoxClient("browserid=abc", site=server.url)


def test_quota(server):
    assert client(server).quota() == (250, 1000)


def test_expired_session_is_reported_as_auth_expired(server):
    c = client(server, cookie="ndus=EXPIRED")
    with pytest.raises(TeraBoxError) as e:
        c.quota()
    assert e.value.auth_expired


def test_mkdir_list_and_not_found(server):
    c = client(server)
    c.mkdir("/apps")
    names = [(e.name, e.is_dir) for e in c.list("/")]
    assert ("apps", True) in names
    with pytest.raises(TeraBoxError) as e:
        c.list("/missing")
    assert e.value.status == 404


def test_upload_then_download_roundtrip_multi_block(server, tmp_path):
    c = client(server)
    c.mkdir("/apps")
    data = os.urandom(4 * 1024 * 1024 * 2 + 12345)  # three blocks
    src = tmp_path / "game.apk"
    src.write_bytes(data)
    seen = []
    c.upload("/apps/game.apk", str(src), progress=lambda d, t: seen.append((d, t)))
    assert server.files["/apps/game.apk"] == data
    assert seen and seen[-1] == (len(data), len(data))

    listed = {e.name: e.size for e in c.list("/apps")}
    assert listed["game.apk"] == len(data)

    out = tmp_path / "back.apk"
    c.download("/apps/game.apk", str(out))
    assert out.read_bytes() == data


def test_download_falls_back_to_direct_server_when_dlink_refused(server, tmp_path):
    c = client(server)
    server.files["/a.bin"] = b"hello world"
    server.fail_download_dlink = True
    out = tmp_path / "a.bin"
    c.download("/a.bin", str(out))
    assert out.read_bytes() == b"hello world"


def test_delete(server):
    c = client(server)
    server.files["/x.apk"] = b"1"
    c.delete("/x.apk")
    assert "/x.apk" not in server.files
    with pytest.raises(TeraBoxError):
        c.delete("/x.apk")


def test_errno_mapping():
    assert errno_error(-6, "x").status == 401
    assert errno_error(31064, "x").status == 507
    assert errno_error(4000023, "x").status == 403
    assert errno_error(99999, "x").status == 502
