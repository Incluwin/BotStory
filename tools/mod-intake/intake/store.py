"""SQLite store: channels, threads, messages with edit history, attachments, links, sync runs."""
import json
import sqlite3
from datetime import datetime, timezone

from . import links as linklib

DISCORD_EPOCH_MS = 1420070400000

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS channels(
    id INTEGER PRIMARY KEY, guild_id INTEGER, parent_id INTEGER, type INTEGER, name TEXT, position INTEGER,
    tracked INTEGER NOT NULL DEFAULT 0, access TEXT, raw TEXT, updated_at TEXT);
CREATE TABLE IF NOT EXISTS threads(
    id INTEGER PRIMARY KEY, parent_id INTEGER, name TEXT, owner_id INTEGER, created_at TEXT,
    archived INTEGER, locked INTEGER, archive_timestamp TEXT, message_count INTEGER, applied_tags TEXT,
    last_message_id INTEGER, raw TEXT, first_seen_at TEXT, updated_at TEXT);
CREATE TABLE IF NOT EXISTS cursors(
    conversation_id INTEGER PRIMARY KEY, last_message_id INTEGER NOT NULL, synced_at TEXT);
CREATE TABLE IF NOT EXISTS messages(
    id INTEGER PRIMARY KEY, conversation_id INTEGER NOT NULL, author_id INTEGER, author_name TEXT,
    author_bot INTEGER, type INTEGER, content TEXT, created_at TEXT, edited_at TEXT, reply_to_id INTEGER,
    raw TEXT, first_seen_at TEXT, updated_at TEXT);
CREATE INDEX IF NOT EXISTS messages_by_conversation ON messages(conversation_id, id);
CREATE TABLE IF NOT EXISTS message_revisions(
    id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER NOT NULL, edited_at TEXT, content TEXT, raw TEXT,
    replaced_at TEXT);
CREATE INDEX IF NOT EXISTS revisions_by_message ON message_revisions(message_id);
CREATE TABLE IF NOT EXISTS attachments(
    id INTEGER PRIMARY KEY, message_id INTEGER NOT NULL, filename TEXT, content_type TEXT, size INTEGER,
    url TEXT, status TEXT NOT NULL, sha256 TEXT, detail TEXT, attempts INTEGER NOT NULL DEFAULT 0,
    fetched_at TEXT);
CREATE INDEX IF NOT EXISTS attachments_by_message ON attachments(message_id);
CREATE TABLE IF NOT EXISTS links(
    message_id INTEGER NOT NULL, url TEXT NOT NULL, kind TEXT, host TEXT, owner TEXT, repo TEXT, ref TEXT,
    path TEXT, PRIMARY KEY(message_id, url));
CREATE TABLE IF NOT EXISTS sync_runs(
    id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT, finished_at TEXT, status TEXT, requests INTEGER,
    threads INTEGER, new_threads INTEGER, new_messages INTEGER, edited_messages INTEGER,
    stored_attachments INTEGER, failed_attachments INTEGER, skipped_attachments INTEGER, notes TEXT);
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(content, files);
"""


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def snowflake_iso(snowflake):
    ms = (int(snowflake) >> 22) + DISCORD_EPOCH_MS
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat(timespec="seconds")


def _int(value):
    return int(value) if value not in (None, "") else None


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(str(path))
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)
        self.db.execute("INSERT OR IGNORE INTO meta(key, value) VALUES('schema_version', '1')")
        self.db.commit()

    def close(self):
        self.db.close()

    def commit(self):
        self.db.commit()

    # Channels and threads

    def save_channels(self, channels, tracked_ids):
        stamp = now_iso()
        listed = set()
        for c in channels:
            cid = int(c["id"])
            listed.add(cid)
            self.db.execute(
                """INSERT INTO channels(id, guild_id, parent_id, type, name, position, tracked, raw, updated_at)
                   VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET guild_id=excluded.guild_id, parent_id=excluded.parent_id,
                       type=excluded.type, name=excluded.name, position=excluded.position,
                       tracked=excluded.tracked, raw=excluded.raw, updated_at=excluded.updated_at""",
                (cid, _int(c.get("guild_id")), _int(c.get("parent_id")), c.get("type"), c.get("name"),
                 c.get("position"), int(cid in tracked_ids), json.dumps(c), stamp))
        for row in self.db.execute("SELECT id FROM channels WHERE tracked = 1").fetchall():
            if row["id"] not in listed:
                self.db.execute("UPDATE channels SET tracked = 0, access = 'gone' WHERE id = ?", (row["id"],))

    def set_channel_access(self, channel_id, access):
        self.db.execute("UPDATE channels SET access = ? WHERE id = ?", (access, channel_id))

    def save_thread(self, t):
        """Insert or refresh a thread; True when it is new."""
        tid = int(t["id"])
        meta = t.get("thread_metadata") or {}
        stamp = now_iso()
        is_new = self.db.execute("SELECT 1 FROM threads WHERE id = ?", (tid,)).fetchone() is None
        self.db.execute(
            """INSERT INTO threads(id, parent_id, name, owner_id, created_at, archived, locked, archive_timestamp,
                   message_count, applied_tags, last_message_id, raw, first_seen_at, updated_at)
               VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET parent_id=excluded.parent_id, name=excluded.name,
                   owner_id=excluded.owner_id, archived=excluded.archived, locked=excluded.locked,
                   archive_timestamp=excluded.archive_timestamp, message_count=excluded.message_count,
                   applied_tags=excluded.applied_tags, last_message_id=excluded.last_message_id,
                   raw=excluded.raw, updated_at=excluded.updated_at""",
            (tid, _int(t.get("parent_id")), t.get("name"), _int(t.get("owner_id")), snowflake_iso(tid),
             int(bool(meta.get("archived"))), int(bool(meta.get("locked"))), meta.get("archive_timestamp"),
             t.get("message_count"), json.dumps(t.get("applied_tags") or []), _int(t.get("last_message_id")),
             json.dumps(t), stamp, stamp))
        return is_new

    # Cursors: the newest message id read from each conversation (a thread or a text channel)

    def cursor(self, conversation_id):
        row = self.db.execute("SELECT last_message_id FROM cursors WHERE conversation_id = ?",
                              (conversation_id,)).fetchone()
        return row["last_message_id"] if row else None

    def set_cursor(self, conversation_id, message_id):
        self.db.execute(
            """INSERT INTO cursors(conversation_id, last_message_id, synced_at) VALUES(?, ?, ?)
               ON CONFLICT(conversation_id) DO UPDATE SET
                   last_message_id = MAX(last_message_id, excluded.last_message_id), synced_at = excluded.synced_at""",
            (conversation_id, message_id, now_iso()))

    # Messages

    def save_message(self, conversation_id, m):
        """Store a message as the API returned it: 'new', 'edited' (old text kept as a revision) or 'same'.

        Only a changed edited_timestamp counts as an edit, so a message that comes back blank (bot without the
        Message Content intent) never overwrites text already stored.
        """
        mid = int(m["id"])
        author = m.get("author") or {}
        content = m.get("content") or ""
        edited = m.get("edited_timestamp")
        stamp = now_iso()
        row = self.db.execute("SELECT edited_at, content, raw FROM messages WHERE id = ?", (mid,)).fetchone()
        if row is None:
            self.db.execute(
                """INSERT INTO messages(id, conversation_id, author_id, author_name, author_bot, type, content,
                       created_at, edited_at, reply_to_id, raw, first_seen_at, updated_at)
                   VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (mid, conversation_id, _int(author.get("id")), author.get("global_name") or author.get("username"),
                 int(bool(author.get("bot"))), m.get("type"), content, m.get("timestamp") or snowflake_iso(mid),
                 edited, _int((m.get("message_reference") or {}).get("message_id")), json.dumps(m), stamp, stamp))
            status = "new"
        elif row["edited_at"] != edited:
            self.db.execute(
                "INSERT INTO message_revisions(message_id, edited_at, content, raw, replaced_at) VALUES(?, ?, ?, ?, ?)",
                (mid, row["edited_at"], row["content"], row["raw"], stamp))
            self.db.execute("UPDATE messages SET content = ?, edited_at = ?, raw = ?, updated_at = ? WHERE id = ?",
                            (content, edited, json.dumps(m), stamp, mid))
            status = "edited"
        else:
            return "same"
        for a in m.get("attachments") or []:
            self.db.execute(
                """INSERT INTO attachments(id, message_id, filename, content_type, size, url, status)
                   VALUES(?, ?, ?, ?, ?, ?, 'pending')
                   ON CONFLICT(id) DO UPDATE SET url = excluded.url""",
                (int(a["id"]), mid, a.get("filename"), a.get("content_type"), a.get("size"), a.get("url")))
        self.db.execute("DELETE FROM links WHERE message_id = ?", (mid,))
        for link in linklib.extract(content, m.get("embeds")):
            self.db.execute(
                "INSERT OR IGNORE INTO links(message_id, url, kind, host, owner, repo, ref, path) "
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                (mid, link.url, link.kind, link.host, link.owner, link.repo, link.ref, link.path))
        files = " ".join(r["filename"] or "" for r in self.db.execute(
            "SELECT filename FROM attachments WHERE message_id = ? ORDER BY id", (mid,)))
        self.db.execute("DELETE FROM messages_fts WHERE rowid = ?", (mid,))
        self.db.execute("INSERT INTO messages_fts(rowid, content, files) VALUES(?, ?, ?)", (mid, content, files))
        return status

    def refresh_attachment_urls(self, m):
        """Take fresh signed CDN URLs from a re-fetched message without touching its text."""
        for a in m.get("attachments") or []:
            self.db.execute("UPDATE attachments SET url = ? WHERE id = ?", (a.get("url"), int(a["id"])))

    # Attachments

    def attachments_to_fetch(self, max_attempts):
        return self.db.execute(
            """SELECT a.id, a.message_id, m.conversation_id, a.filename, a.size, a.url, a.attempts
               FROM attachments a JOIN messages m ON m.id = a.message_id
               WHERE a.status = 'pending' OR (a.status = 'failed' AND a.attempts < ?)
               ORDER BY a.id""", (max_attempts,)).fetchall()

    def set_attachment(self, attachment_id, status, sha256=None, detail=None):
        self.db.execute(
            """UPDATE attachments SET status = ?, sha256 = COALESCE(?, sha256), detail = ?,
                   attempts = attempts + 1, fetched_at = ? WHERE id = ?""",
            (status, sha256, detail, now_iso(), attachment_id))

    # Sync runs

    def begin_run(self):
        cur = self.db.execute("INSERT INTO sync_runs(started_at, status) VALUES(?, 'running')", (now_iso(),))
        self.db.commit()
        return cur.lastrowid

    def finish_run(self, run_id, status, stats, requests):
        self.db.execute(
            """UPDATE sync_runs SET finished_at = ?, status = ?, requests = ?, threads = ?, new_threads = ?,
                   new_messages = ?, edited_messages = ?, stored_attachments = ?, failed_attachments = ?,
                   skipped_attachments = ?, notes = ? WHERE id = ?""",
            (now_iso(), status, requests, stats.threads, stats.new_threads, stats.new_messages,
             stats.edited_messages, stats.stored_attachments, stats.failed_attachments, stats.skipped_attachments,
             "\n".join(stats.notes), run_id))
        self.db.commit()

    # Reading

    def status(self):
        q = self.db.execute
        return {
            "tracked_channels": [dict(r) for r in q(
                "SELECT id, name, type, access FROM channels WHERE tracked = 1 ORDER BY position, id")],
            "threads": q("SELECT COUNT(*), COALESCE(SUM(archived), 0) FROM threads").fetchone(),
            "messages": q("SELECT COUNT(*) FROM messages").fetchone()[0],
            "revisions": q("SELECT COUNT(*) FROM message_revisions").fetchone()[0],
            "attachments": q("SELECT status, COUNT(*), COALESCE(SUM(size), 0) FROM attachments "
                             "GROUP BY status ORDER BY status").fetchall(),
            "links": q("SELECT kind, COUNT(*) FROM links GROUP BY kind ORDER BY COUNT(*) DESC, kind").fetchall(),
            "last_run": q("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1").fetchone(),
        }

    def search(self, text, limit=20):
        query = " ".join('"' + word.replace('"', '""') + '"' for word in text.split())
        if not query:
            return [], []
        hits = self.db.execute(
            """SELECT m.id, m.conversation_id, m.author_name, m.created_at,
                      snippet(messages_fts, -1, '[', ']', ' ... ', 12) AS snippet,
                      COALESCE(t.name, '#' || c.name) AS place
               FROM messages_fts JOIN messages m ON m.id = messages_fts.rowid
               LEFT JOIN threads t ON t.id = m.conversation_id LEFT JOIN channels c ON c.id = m.conversation_id
               WHERE messages_fts MATCH ? ORDER BY rank LIMIT ?""", (query, limit)).fetchall()
        threads = self.db.execute("SELECT id, name FROM threads WHERE name LIKE ? ORDER BY id LIMIT ?",
                                  ("%" + text + "%", limit)).fetchall()
        return hits, threads

    def find_conversations(self, key):
        """Threads or tracked channels matching an id or a name fragment."""
        if key.isdigit():
            rows = self.db.execute("SELECT id, name FROM threads WHERE id = ? UNION ALL "
                                   "SELECT id, '#' || name FROM channels WHERE id = ?", (int(key), int(key)))
        else:
            rows = self.db.execute("SELECT id, name FROM threads WHERE name LIKE ? ORDER BY id",
                                   ("%" + key + "%",))
        return rows.fetchall()

    def conversation(self, conversation_id):
        messages = self.db.execute(
            "SELECT id, author_name, created_at, edited_at, content FROM messages WHERE conversation_id = ? "
            "ORDER BY id", (conversation_id,)).fetchall()
        out = []
        for m in messages:
            attachments = self.db.execute(
                "SELECT filename, size, status, sha256 FROM attachments WHERE message_id = ? ORDER BY id",
                (m["id"],)).fetchall()
            links = self.db.execute("SELECT kind, url FROM links WHERE message_id = ? ORDER BY rowid",
                                    (m["id"],)).fetchall()
            revisions = self.db.execute("SELECT COUNT(*) FROM message_revisions WHERE message_id = ?",
                                        (m["id"],)).fetchone()[0]
            out.append((m, attachments, links, revisions))
        return out
