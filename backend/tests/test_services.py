import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from backend.database.db import open_database  # noqa: E402
from backend.services.apk import ApkInfo, parse_badging  # noqa: E402
from backend.services.apps import AppService  # noqa: E402
from backend.services.backup import BackupService, batches, diff_manifests  # noqa: E402
from backend.storage.manager import StorageError  # noqa: E402
from backend.tests.fakes import FakeAdb, FakeEmulator, FakeStorage  # noqa: E402

PKG = "com.example.game"
D = f"/data/data/{PKG}"


@pytest.fixture()
def db():
    d = open_database("sqlite:///:memory:")
    yield d
    d.close()


# ---------------------------------------------------------------- database
def test_schema_and_upsert(db):
    for _ in range(2):
        db.execute("INSERT INTO cloud_accounts (user_id, provider, secret, meta, updated_at) VALUES (?,?,?,?,?) "
                   "ON CONFLICT (user_id, provider) DO UPDATE SET secret = excluded.secret",
                   ("u", "mega", "s", "{}", "t"))
    assert len(db.query("SELECT * FROM cloud_accounts")) == 1


# ---------------------------------------------------------------- apk
BADGING = """package: name='com.example.game' versionCode='12' versionName='1.2.0' compileSdkVersion='34'
sdkVersion:'24'
application-label:'Example Game'
native-code: 'arm64-v8a' 'armeabi-v7a'
"""


def test_parse_badging():
    i = parse_badging(BADGING)
    assert (i.package, i.version_name, i.label, i.min_sdk) == ("com.example.game", "1.2.0", "Example Game", 24)
    assert i.abis == ["arm64-v8a", "armeabi-v7a"] and i.warnings  # ARM-only warning


def test_parse_badging_rejects_garbage():
    with pytest.raises(Exception):
        parse_badging("not an apk")


# ---------------------------------------------------------------- backup
def test_diff_and_batches():
    prev = {"/a": {"size": 1, "mtime": 1}, "/b": {"size": 2, "mtime": 2}}
    cur = {"/a": {"size": 1, "mtime": 1}, "/b": {"size": 3, "mtime": 2}, "/c": {"size": 1, "mtime": 5}}
    assert diff_manifests(prev, cur) == ["/b", "/c"]
    assert [len(b) for b in batches([str(i) for i in range(400)], {})] == [150, 150, 100]


def make_backup(db):
    adb, st = FakeAdb(), FakeStorage()
    adb.put(f"{D}/save1.dat", b"save-one")
    adb.put(f"{D}/prefs.xml", b"<prefs/>")
    adb.put(f"/sdcard/Android/data/{PKG}/files/cfg.json", b'{"a":1}')
    return adb, st, BackupService(db, "dev", adb, st, "/tmp/clouddroid-test-staging")


def test_full_then_incremental_backup_uploads_only_changes(db):
    adb, st, svc = make_backup(db)
    first = svc.backup(PKG, "mega")
    assert first["kind"] == "full" and first["files_changed"] == 3 == first["files_total"]
    n_uploads = len(st.uploads)

    adb.put(f"{D}/save1.dat", b"save-one-UPDATED")
    second = svc.backup(PKG, "mega")
    assert second["kind"] == "incremental"
    assert second["files_changed"] == 1 and second["files_total"] == 3
    # one new chunk + manifest, not a re-upload of everything
    assert len(st.uploads) == n_uploads + 2
    assert [h["kind"] for h in svc.history(PKG)] == ["incremental", "full"]


def test_restore_returns_exact_state_and_removes_extra_files(db):
    adb, st, svc = make_backup(db)
    svc.backup(PKG, "mega")
    adb.put(f"{D}/save1.dat", b"save-one-v2")
    second = svc.backup(PKG, "mega")

    adb.put(f"{D}/save1.dat", b"CORRUPTED")
    adb.put(f"{D}/junk.tmp", b"should disappear")
    svc.restore(PKG, second["id"])

    assert adb.files[f"{D}/save1.dat"][0] == b"save-one-v2"
    assert adb.files[f"{D}/prefs.xml"][0] == b"<prefs/>"
    assert f"{D}/junk.tmp" not in adb.files


def test_restore_older_backup_gives_older_data(db):
    adb, st, svc = make_backup(db)
    first = svc.backup(PKG, "mega")
    adb.put(f"{D}/save1.dat", b"newer")
    svc.backup(PKG, "mega")
    svc.restore(PKG, first["id"])
    assert adb.files[f"{D}/save1.dat"][0] == b"save-one"


def test_backup_refuses_without_root(db):
    adb, st, svc = make_backup(db)
    adb.can_root = False
    with pytest.raises(StorageError) as e:
        svc.backup(PKG, "mega")
    assert e.value.status == 400 and "root" in str(e.value)


def test_backup_of_app_without_data_is_an_error_not_an_empty_backup(db):
    adb, st = FakeAdb(), FakeStorage()
    svc = BackupService(db, "dev", adb, st, "/tmp/clouddroid-test-staging")
    with pytest.raises(StorageError):
        svc.backup(PKG, "mega")


# ---------------------------------------------------------------- install pipeline
def make_apps(db, storage):
    adb = FakeAdb()
    adb.installed_packages = {PKG}
    em = FakeEmulator("stopped")
    info = ApkInfo(PKG, "1.2.0", "12", "Example Game")
    svc = AppService(db, "dev", adb, em, storage, "/tmp/clouddroid-test-staging", inspector=lambda p: info, run_async=False)
    return svc, adb, em


def test_install_pipeline_end_to_end(db):
    st = FakeStorage()
    st.blobs[("terabox", "/apks/game.apk")] = b"PK-fake-apk"
    svc, adb, em = make_apps(db, st)
    job = svc.submit_install("terabox", "/apks/game.apk")
    assert job.state == "done", job.error
    assert job.package_name == PKG and em.ensured == 1
    apps = svc.list_apps()
    assert apps[0]["package_name"] == PKG and apps[0]["status"] == "installed" and apps[0]["size_mb"] == 42

    svc.launch(PKG)
    assert adb.launched == [PKG] and svc.list_apps()[0]["last_launched"]
    svc.uninstall(PKG)
    assert svc.list_apps() == []


def test_install_rejects_non_apk_and_reports_missing_file(db):
    svc, _, _ = make_apps(db, FakeStorage())
    with pytest.raises(StorageError):
        svc.submit_install("mega", "/x/game.xapk")
    job = svc.submit_install("mega", "/x/missing.apk")
    assert job.state == "failed" and "Not found" in job.error


def test_launch_unknown_app(db):
    svc, _, _ = make_apps(db, FakeStorage())
    with pytest.raises(StorageError) as e:
        svc.launch("nope")
    assert e.value.status == 404
