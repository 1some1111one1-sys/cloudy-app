-- CloudDroid schema. Portable SQL: runs unchanged on PostgreSQL (production) and SQLite (tests, quick start).
-- Timestamps are ISO-8601 UTC strings.

CREATE TABLE IF NOT EXISTS users (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

-- One row per connected drive. `secret` is encrypted (Fernet). For MEGA it is empty: MEGAcmd keeps its own session.
CREATE TABLE IF NOT EXISTS cloud_accounts (
    user_id     TEXT NOT NULL,
    provider    TEXT NOT NULL,            -- 'terabox' | 'mega'
    secret      TEXT NOT NULL DEFAULT '',
    meta        TEXT NOT NULL DEFAULT '{}',
    updated_at  TEXT NOT NULL,
    PRIMARY KEY (user_id, provider)
);

CREATE TABLE IF NOT EXISTS devices (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL,
    avd_name    TEXT NOT NULL,
    cpu_cores   INTEGER NOT NULL,
    ram_mb      INTEGER NOT NULL,
    disk_gb     INTEGER NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS apps (
    device_id       TEXT NOT NULL,
    package_name    TEXT NOT NULL,
    label           TEXT NOT NULL DEFAULT '',
    version_name    TEXT NOT NULL DEFAULT '',
    status          TEXT NOT NULL DEFAULT 'installed',   -- installing | installed | failed
    installed_at    TEXT NOT NULL,
    last_launched   TEXT,
    source_provider TEXT,
    source_path     TEXT,
    PRIMARY KEY (device_id, package_name)
);

CREATE TABLE IF NOT EXISTS backups (
    id            TEXT PRIMARY KEY,
    device_id     TEXT NOT NULL,
    package_name  TEXT NOT NULL,
    provider      TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    size_bytes    BIGINT NOT NULL DEFAULT 0,
    files_changed INTEGER NOT NULL DEFAULT 0,
    files_total   INTEGER NOT NULL DEFAULT 0,
    kind          TEXT NOT NULL,                         -- full | incremental
    remote_dir    TEXT NOT NULL,
    manifest      TEXT NOT NULL                          -- JSON: path -> {size, mtime, chunk}
);

CREATE TABLE IF NOT EXISTS sessions (
    id            TEXT PRIMARY KEY,
    device_id     TEXT NOT NULL,
    package_name  TEXT,
    profile       TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    ended_at      TEXT
);

CREATE INDEX IF NOT EXISTS idx_backups_pkg ON backups (device_id, package_name, created_at);
