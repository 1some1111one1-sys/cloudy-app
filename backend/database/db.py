"""Tiny database layer. PostgreSQL (psycopg 3) in production, sqlite3 for tests and quick starts.

SQL is written with `?` placeholders; for PostgreSQL they are rewritten to `%s`.
"""
from __future__ import annotations

import os
import sqlite3
import threading
from datetime import datetime, timezone
from typing import Any, Iterable


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _statements(sql: str) -> list[str]:
    return [s.strip() for s in sql.split(";") if s.strip() and not all(
        line.strip().startswith("--") or not line.strip() for line in s.splitlines())]


class Database:
    def __init__(self, url: str):
        self.url = url
        self._lock = threading.RLock()
        if url.startswith("sqlite:///"):
            path = url[len("sqlite:///"):]
            if path != ":memory:":
                os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            self._kind = "sqlite"
            self._conn: Any = sqlite3.connect(path, check_same_thread=False)
            self._conn.row_factory = sqlite3.Row
        elif url.startswith(("postgresql://", "postgres://")):
            import psycopg  # noqa: PLC0415 - optional dependency, only needed for PostgreSQL
            from psycopg.rows import dict_row
            self._kind = "pg"
            self._conn = psycopg.connect(url, autocommit=True, row_factory=dict_row)
        else:
            raise ValueError("DATABASE_URL must start with postgresql:// or sqlite:///")

    def _sql(self, sql: str) -> str:
        return sql.replace("?", "%s") if self._kind == "pg" else sql

    def init_schema(self, schema_sql: str) -> None:
        for stmt in _statements(schema_sql):
            self.execute(stmt)

    def execute(self, sql: str, params: Iterable[Any] = ()) -> None:
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(self._sql(sql), tuple(params))
            if self._kind == "sqlite":
                self._conn.commit()
            cur.close()

    def query(self, sql: str, params: Iterable[Any] = ()) -> list[dict]:
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(self._sql(sql), tuple(params))
            rows = cur.fetchall()
            cur.close()
            return [dict(r) for r in rows]

    def one(self, sql: str, params: Iterable[Any] = ()) -> dict | None:
        rows = self.query(sql, params)
        return rows[0] if rows else None

    def close(self) -> None:
        with self._lock:
            self._conn.close()


def default_schema_path() -> str:
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.normpath(os.path.join(here, "..", "..", "database", "schema", "schema.sql"))


def open_database(url: str) -> Database:
    db = Database(url)
    with open(default_schema_path(), encoding="utf-8") as fh:
        db.init_schema(fh.read())
    return db
