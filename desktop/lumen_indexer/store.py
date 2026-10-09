"""Local cache of computed vectors, so a cancelled run (or a later run with new photos) continues
where it stopped and an export never needs the model."""
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterable, Iterator

from .paths import cache_db

_SCHEMA = """
CREATE TABLE IF NOT EXISTS vectors (
    root  TEXT NOT NULL,
    model TEXT NOT NULL,
    rel   TEXT NOT NULL,
    size  INTEGER NOT NULL,
    mtime INTEGER NOT NULL,
    vec   BLOB,
    PRIMARY KEY (root, model, rel)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS faces (
    root    TEXT NOT NULL,
    model   TEXT NOT NULL,
    rel     TEXT NOT NULL,
    size    INTEGER NOT NULL,
    mtime   INTEGER NOT NULL,
    variant TEXT NOT NULL,
    n       INTEGER NOT NULL,   -- number of faces, -1 = file could not be read
    boxes   BLOB NOT NULL,      -- n x (left, top, right, bottom, score) float32 LE, normalised
    vecs    BLOB NOT NULL,      -- n x dim fp16 LE, L2-normalised
    PRIMARY KEY (root, model, rel)
) WITHOUT ROWID;
"""


class Store:
    def __init__(self, path: Path | None = None):
        self.path = Path(path) if path else cache_db()
        with self._conn() as c:
            c.executescript(_SCHEMA)

    @contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path, timeout=60)
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            with conn:
                yield conn
        finally:
            conn.close()

    def known(self, root: str, model: str) -> dict[str, tuple[int, int, bool]]:
        """rel -> (size, mtime, has_vector). Failed files are known too, so they are not retried."""
        with self._conn() as c:
            rows = c.execute("SELECT rel, size, mtime, vec IS NOT NULL FROM vectors WHERE root=? AND model=?", (root, model))
            return {rel: (size, mtime, bool(has)) for rel, size, mtime, has in rows}

    def put_many(self, root: str, model: str, rows: Iterable[tuple[str, int, int, bytes | None]]) -> None:
        with self._conn() as c:
            c.executemany(
                "INSERT OR REPLACE INTO vectors (root, model, rel, size, mtime, vec) VALUES (?,?,?,?,?,?)",
                [(root, model, rel, size, mtime, vec) for rel, size, mtime, vec in rows],
            )

    def prune(self, root: str, model: str, present: set[str]) -> int:
        stale = [rel for rel in self.known(root, model) if rel not in present]
        with self._conn() as c:
            for i in range(0, len(stale), 500):
                chunk = stale[i:i + 500]
                c.execute(
                    f"DELETE FROM vectors WHERE root=? AND model=? AND rel IN ({','.join('?' * len(chunk))})",
                    (root, model, *chunk),
                )
        return len(stale)

    def count(self, root: str, model: str) -> int:
        with self._conn() as c:
            return c.execute("SELECT COUNT(*) FROM vectors WHERE root=? AND model=? AND vec IS NOT NULL", (root, model)).fetchone()[0]

    def clear(self, root: str, model: str) -> None:
        with self._conn() as c:
            c.execute("DELETE FROM vectors WHERE root=? AND model=?", (root, model))

    # ------------------------------------------------------------------ faces

    def face_known(self, root: str, model: str) -> dict[str, tuple[int, int, str]]:
        """rel -> (size, mtime, variant)."""
        with self._conn() as c:
            rows = c.execute("SELECT rel, size, mtime, variant FROM faces WHERE root=? AND model=?", (root, model))
            return {rel: (size, mtime, variant) for rel, size, mtime, variant in rows}

    def face_put_many(self, root: str, model: str, rows) -> None:
        with self._conn() as c:
            c.executemany(
                "INSERT OR REPLACE INTO faces (root, model, rel, size, mtime, variant, n, boxes, vecs) VALUES (?,?,?,?,?,?,?,?,?)",
                [(root, model, *r) for r in rows],
            )

    def face_prune(self, root: str, model: str, present: set[str]) -> int:
        stale = [rel for rel in self.face_known(root, model) if rel not in present]
        with self._conn() as c:
            for i in range(0, len(stale), 500):
                chunk = stale[i:i + 500]
                c.execute(
                    f"DELETE FROM faces WHERE root=? AND model=? AND rel IN ({','.join('?' * len(chunk))})",
                    (root, model, *chunk),
                )
        return len(stale)

    def face_counts(self, root: str, model: str) -> tuple[int, int]:
        """(scanned photos, faces)."""
        with self._conn() as c:
            row = c.execute("SELECT COUNT(*), COALESCE(SUM(n), 0) FROM faces WHERE root=? AND model=? AND n >= 0", (root, model)).fetchone()
            return int(row[0]), int(row[1])

    @contextmanager
    def snapshot(self) -> Iterator["Snapshot"]:
        """A consistent read-only view, used by the exporter for its two passes over the rows."""
        conn = sqlite3.connect(self.path, timeout=60)
        try:
            conn.execute("BEGIN")
            yield Snapshot(conn)
        finally:
            conn.rollback()
            conn.close()


class Snapshot:
    def __init__(self, conn: sqlite3.Connection):
        self._c = conn

    def count(self, root: str, model: str) -> int:
        return self._c.execute("SELECT COUNT(*) FROM vectors WHERE root=? AND model=? AND vec IS NOT NULL", (root, model)).fetchone()[0]

    def items(self, root: str, model: str) -> Iterator[tuple[str, int, int]]:
        yield from self._c.execute(
            "SELECT rel, size, mtime FROM vectors WHERE root=? AND model=? AND vec IS NOT NULL ORDER BY rel", (root, model)
        )

    def vectors(self, root: str, model: str) -> Iterator[bytes]:
        for (vec,) in self._c.execute("SELECT vec FROM vectors WHERE root=? AND model=? AND vec IS NOT NULL ORDER BY rel", (root, model)):
            yield vec

    def face_rows(self, root: str, model: str) -> Iterator[tuple[str, int, int, int, bytes, bytes]]:
        yield from self._c.execute(
            "SELECT rel, size, mtime, n, boxes, vecs FROM faces WHERE root=? AND model=? AND n >= 0 ORDER BY rel", (root, model)
        )
