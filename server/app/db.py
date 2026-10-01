"""SQLite storage. Small, dependency-free and good enough for a family server on a Raspberry Pi.

All access goes through `DB` which serializes writes with a lock. Full-text search over chat
history uses FTS5 with the trigram tokenizer (works for Japanese without a morphological analyzer)
and is the retrieval part of the AI chibi's RAG.
"""
from __future__ import annotations

import json
import secrets
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Iterable

SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  token TEXT UNIQUE NOT NULL,
  avatar TEXT NOT NULL DEFAULT '',
  color TEXT NOT NULL DEFAULT '#ff8a65',
  birthday TEXT NOT NULL DEFAULT '',          -- 'MM-DD'
  voice_pitch REAL NOT NULL DEFAULT 1.0,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS families (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  emoji TEXT NOT NULL DEFAULT '🏠',
  code TEXT UNIQUE NOT NULL,
  owner_id TEXT NOT NULL,
  ai_enabled INTEGER NOT NULL DEFAULT 1,
  iot_enabled INTEGER NOT NULL DEFAULT 1,
  ai_name TEXT NOT NULL DEFAULT 'ちびAI',
  ai_avatar TEXT NOT NULL DEFAULT '',
  settings TEXT NOT NULL DEFAULT '{}',
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS members (
  family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL DEFAULT 'member',        -- owner | member
  nickname TEXT NOT NULL DEFAULT '',          -- '母', '妹' ...
  calendar_private INTEGER NOT NULL DEFAULT 0,
  quiet_start TEXT NOT NULL DEFAULT '',       -- 'HH:MM'
  quiet_end TEXT NOT NULL DEFAULT '',
  joined_at REAL NOT NULL,
  PRIMARY KEY (family_id, user_id)
);

CREATE TABLE IF NOT EXISTS channels (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'text',          -- text | voice
  position INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id TEXT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  family_id TEXT NOT NULL,
  user_id TEXT,                               -- NULL for AI / system
  kind TEXT NOT NULL DEFAULT 'text',          -- text | voice | ai | system | memo | stamp
  text TEXT NOT NULL DEFAULT '',
  media TEXT NOT NULL DEFAULT '',
  analysis TEXT NOT NULL DEFAULT '{}',
  meta TEXT NOT NULL DEFAULT '{}',
  reply_to INTEGER,
  created_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_channel ON messages(channel_id, id);
CREATE INDEX IF NOT EXISTS idx_messages_family ON messages(family_id, id);

CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(text, content='messages', content_rowid='id', tokenize='trigram');
CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
  INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text);
END;
CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.id, old.text);
END;

CREATE TABLE IF NOT EXISTS reads (
  user_id TEXT NOT NULL,
  channel_id TEXT NOT NULL,
  last_id INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, channel_id)
);

CREATE TABLE IF NOT EXISTS acks (
  message_id INTEGER NOT NULL,
  user_id TEXT NOT NULL,
  at REAL NOT NULL,
  PRIMARY KEY (message_id, user_id)
);

CREATE TABLE IF NOT EXISTS memos (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  author_id TEXT,
  text TEXT NOT NULL,
  color TEXT NOT NULL DEFAULT '#fff3a3',
  notify_at REAL,
  target_id TEXT,                             -- NULL = everyone
  emotion TEXT NOT NULL DEFAULT '',
  motion TEXT NOT NULL DEFAULT '',
  party INTEGER NOT NULL DEFAULT 0,
  media TEXT NOT NULL DEFAULT '',
  done INTEGER NOT NULL DEFAULT 0,
  delivered INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  user_id TEXT,
  title TEXT NOT NULL,
  start REAL NOT NULL,
  end REAL,
  all_day INTEGER NOT NULL DEFAULT 0,
  private INTEGER NOT NULL DEFAULT 0,
  note TEXT NOT NULL DEFAULT '',
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  assignee_id TEXT,
  done INTEGER NOT NULL DEFAULT 0,
  due REAL,
  created_by TEXT,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS asks (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  channel_id TEXT,
  group_id TEXT NOT NULL,
  requester_id TEXT,
  target_id TEXT NOT NULL,
  question TEXT NOT NULL,
  options TEXT NOT NULL DEFAULT '[]',
  answer TEXT,
  status TEXT NOT NULL DEFAULT 'open',        -- open | answered | expired
  context TEXT NOT NULL DEFAULT '{}',
  created_at REAL NOT NULL,
  answered_at REAL
);

CREATE TABLE IF NOT EXISTS invites (
  token TEXT PRIMARY KEY,
  family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  preset_name TEXT NOT NULL DEFAULT '',
  preset_nickname TEXT NOT NULL DEFAULT '',
  preset_avatar TEXT NOT NULL DEFAULT '',
  created_by TEXT,
  used_by TEXT,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS avatars (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  file TEXT NOT NULL,
  owner_id TEXT,
  builtin INTEGER NOT NULL DEFAULT 0,
  credit TEXT NOT NULL DEFAULT '',
  size INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS kv (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);
"""

JSON_FIELDS = {"settings", "analysis", "meta", "options", "context"}


def new_id(prefix: str = "") -> str:
    return prefix + secrets.token_urlsafe(9).replace("-", "x").replace("_", "y")


def now() -> float:
    return time.time()


class DB:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.conn = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock:
            self.conn.executescript(SCHEMA)
            for col in ("watch INTEGER NOT NULL DEFAULT 0", "share_presence INTEGER NOT NULL DEFAULT 1"):
                try:
                    self.conn.execute(f"ALTER TABLE members ADD COLUMN {col}")
                except sqlite3.OperationalError:
                    pass  # already migrated

    # -- low level ---------------------------------------------------------------------------
    @staticmethod
    def _row(row: sqlite3.Row | None) -> dict | None:
        if row is None:
            return None
        d = dict(row)
        for k in JSON_FIELDS & d.keys():
            try:
                d[k] = json.loads(d[k]) if d[k] else ({} if k != "options" else [])
            except (TypeError, ValueError):
                pass
        return d

    def one(self, sql: str, args: Iterable[Any] = ()) -> dict | None:
        with self.lock:
            return self._row(self.conn.execute(sql, tuple(args)).fetchone())

    def all(self, sql: str, args: Iterable[Any] = ()) -> list[dict]:
        with self.lock:
            return [self._row(r) for r in self.conn.execute(sql, tuple(args)).fetchall()]

    def exec(self, sql: str, args: Iterable[Any] = ()) -> sqlite3.Cursor:
        with self.lock:
            return self.conn.execute(sql, tuple(args))

    def insert(self, table: str, data: dict) -> dict:
        data = {k: (json.dumps(v, ensure_ascii=False) if k in JSON_FIELDS and not isinstance(v, str) else v) for k, v in data.items()}
        cols = ", ".join(data)
        qs = ", ".join("?" for _ in data)
        with self.lock:
            cur = self.conn.execute(f"INSERT INTO {table} ({cols}) VALUES ({qs})", tuple(data.values()))
            return {"rowid": cur.lastrowid, **data}

    def update(self, table: str, where: dict, data: dict) -> None:
        if not data:
            return
        data = {k: (json.dumps(v, ensure_ascii=False) if k in JSON_FIELDS and not isinstance(v, str) else v) for k, v in data.items()}
        sets = ", ".join(f"{k}=?" for k in data)
        conds = " AND ".join(f"{k}=?" for k in where)
        self.exec(f"UPDATE {table} SET {sets} WHERE {conds}", (*data.values(), *where.values()))

    # -- kv ----------------------------------------------------------------------------------
    def kv_get(self, k: str, default: Any = None) -> Any:
        r = self.one("SELECT v FROM kv WHERE k=?", (k,))
        return json.loads(r["v"]) if r else default

    def kv_set(self, k: str, v: Any) -> None:
        self.exec("INSERT INTO kv(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, json.dumps(v, ensure_ascii=False)))

    # -- domain helpers -----------------------------------------------------------------------
    def user_by_token(self, token: str) -> dict | None:
        return self.one("SELECT * FROM users WHERE token=?", (token,))

    def user(self, uid: str) -> dict | None:
        return self.one("SELECT * FROM users WHERE id=?", (uid,))

    def family(self, fid: str) -> dict | None:
        return self.one("SELECT * FROM families WHERE id=?", (fid,))

    def member(self, fid: str, uid: str) -> dict | None:
        return self.one("SELECT * FROM members WHERE family_id=? AND user_id=?", (fid, uid))

    def members(self, fid: str) -> list[dict]:
        return self.all(
            """SELECT m.*, u.name, u.avatar, u.color, u.birthday, u.voice_pitch
               FROM members m JOIN users u ON u.id = m.user_id
               WHERE m.family_id=? ORDER BY m.joined_at""",
            (fid,),
        )

    def families_of(self, uid: str) -> list[dict]:
        return self.all(
            "SELECT f.* FROM families f JOIN members m ON m.family_id=f.id WHERE m.user_id=? ORDER BY m.joined_at",
            (uid,),
        )

    def channel(self, cid: str) -> dict | None:
        return self.one("SELECT * FROM channels WHERE id=?", (cid,))

    def channels(self, fid: str) -> list[dict]:
        return self.all("SELECT * FROM channels WHERE family_id=? ORDER BY position, created_at", (fid,))

    def message(self, mid: int) -> dict | None:
        return self.one("SELECT * FROM messages WHERE id=?", (mid,))

    def search_messages(self, fid: str, query: str, limit: int = 8) -> list[dict]:
        """Full-text search (trigram). Falls back to LIKE for 1-2 character queries."""
        q = query.strip()
        if not q:
            return []
        terms = [t for t in q.replace("　", " ").split() if t]
        rows: list[dict] = []
        long_terms = [t for t in terms if len(t) >= 3]
        if long_terms:
            match = " OR ".join('"' + t.replace('"', "") + '"' for t in long_terms)
            try:
                rows = self.all(
                    """SELECT m.* FROM messages_fts f JOIN messages m ON m.id=f.rowid
                       WHERE messages_fts MATCH ? AND m.family_id=? ORDER BY rank LIMIT ?""",
                    (match, fid, limit),
                )
            except sqlite3.OperationalError:
                rows = []
        if len(rows) < limit:
            seen = {r["id"] for r in rows}
            for t in terms:
                for r in self.all(
                    "SELECT * FROM messages WHERE family_id=? AND text LIKE ? ORDER BY id DESC LIMIT ?",
                    (fid, f"%{t}%", limit),
                ):
                    if r["id"] not in seen:
                        rows.append(r)
                        seen.add(r["id"])
        return rows[:limit]
