"""P3 dossiers: one per release thread (or per release post in a text channel), from the local store.

A dossier holds the thread's title, author, date and forum tags, the starter post with its earlier versions, the
follow-up replies, every link with what P2 pinned for it (and the files that pin changes), attachments with their
real file type, code blocks, and the rule evidence (intake/rules.py) over the release and over the replies.

Dossiers hold message text, so they are written to <data dir>/dossiers/ only, never into the repo. Everything here is
read offline: git runs only local commands with GIT_NO_LAZY_FETCH=1 (a file whose contents P2 did not load is left
unread rather than fetched), and nothing read is built or run.
"""
import hashlib
import json
import os
import subprocess
from pathlib import Path

from . import rules
from .fetch import LEAKY_ENV, GitRunner
from .sync import IntakeError

DOSSIER_VERSION = 1
MAX_LISTED_FILES = 300        # file paths kept per snapshot in the dossier (all are counted)
MAX_READ_FILES = 40           # changed files read per snapshot for evidence
MAX_READ_BYTES = 256 * 1024
EXCERPT_CHARS = 1500
TEXT_CHANNEL_TYPES = (0, 5)
CHANNEL_TYPES = {0: "text", 5: "announcement", 15: "forum", 16: "media"}
CHANGE_KINDS = {"github_commit", "github_pull", "github_compare"}
SINGLE_FILE_KINDS = {"github_blob", "github_raw"}


class SourceReader:
    """Pinned snapshots, read offline: file lists and contents from P2's mirrors, paste text and attachments."""

    def __init__(self, data_dir, git_env=None):
        self.data_dir = Path(data_dir)
        self.git = None
        self.note = None
        hooks = self.data_dir / "sources" / "no-hooks"
        try:
            hooks.mkdir(parents=True, exist_ok=True)
            self.git = GitRunner(hooks, protocols="none", env=git_env)  # local commands only; no transport allowed
        except IntakeError as e:
            self.note = str(e)
        self._files = {}

    def _git_dir(self, local_path):
        git_dir = self.data_dir / local_path / ".git"
        return git_dir if git_dir.is_dir() else None

    def files(self, row):
        """(info, every path) for a stored git snapshot, else (None, []). info = {mode: changed|file|tree|repo,
        count, files: the first MAX_LISTED_FILES paths, status: {path: A|M|D} for changes}."""
        if self.git is None or row["status"] != "stored" or row["method"] != "git" or not row["pinned_sha"]:
            return None, []
        git_dir = self._git_dir(row["local_path"] or "")
        if git_dir is None:
            return None, []
        key = (str(git_dir), row["base_sha"], row["pinned_sha"], row["path"], row["kind"])
        if key in self._files:
            return self._files[key][:2]
        statuses = {}
        if row["base_sha"]:
            mode = "changed"
            result = self.git.run(["diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-status", "-z",
                                   row["base_sha"], row["pinned_sha"]], git_dir)
            parts = result.out.split("\0") if result.code == 0 else []
            names = []
            for status, path in zip(parts[0::2], parts[1::2]):
                if path:
                    names.append(path)
                    statuses[path] = status[:1]
        elif row["path"] and row["kind"] in SINGLE_FILE_KINDS:
            mode, names = "file", [row["path"]]
            statuses[row["path"]] = "F"
        else:
            mode = "tree" if row["path"] else "repo"
            args = ["ls-tree", "-r", "-z", "--name-only", row["pinned_sha"]] + (["--", row["path"]] if row["path"]
                                                                                 else [])
            result = self.git.run(args, git_dir)
            names = [n for n in result.out.split("\0") if n] if result.code == 0 else []
        info = {"mode": mode, "count": len(names), "files": names[:MAX_LISTED_FILES]}
        if statuses:
            info["status"] = {p: statuses[p] for p in names[:MAX_LISTED_FILES]}
        self._files[key] = (info, names, statuses)
        return info, names

    def file_text(self, row, path):
        """A pinned file's text if its contents are local (P2 loads them for what a link changes), else None."""
        git_dir = self._git_dir(row["local_path"] or "")
        if self.git is None or git_dir is None or rules.extension(path) in rules.BINARY_EXT:
            return None
        result = self.git.run(["cat-file", "blob", f"{row['pinned_sha']}:{path}"], git_dir)
        if result.code != 0:
            return None
        return result.out[:MAX_READ_BYTES]

    def blob(self, rel_path, limit=MAX_READ_BYTES):
        """Bytes of a stored paste or attachment (data/<rel_path>), at most limit."""
        if not rel_path:
            return None
        path = (self.data_dir / rel_path).resolve()
        if self.data_dir.resolve() not in path.parents or not path.is_file():
            return None
        with open(path, "rb") as f:
            return f.read(limit)


def in_history(repo, shas):
    """The commits among shas that exist in our own server repo (a release already merged), read locally."""
    shas = sorted({s for s in shas if s})
    if not repo or not shas:
        return set()
    env = {k: v for k, v in os.environ.items() if k not in LEAKY_ENV}
    env["GIT_NO_LAZY_FETCH"] = "1"
    try:
        p = subprocess.run(["git", "-C", str(repo), "cat-file", "--batch-check"],
                           input="".join(s + "^{commit}\n" for s in shas).encode("ascii"), stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, env=env, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        return set()
    found = set()
    for sha, line in zip(shas, p.stdout.decode("utf-8", "replace").splitlines()):
        if not line.endswith("missing") and " commit " in line:
            found.add(sha)
    return found


class Builder:
    def __init__(self, store, data_dir, inventory=None, server_repo=None, git_env=None):
        self.store = store
        self.db = store.db
        self.reader = SourceReader(data_dir, git_env)
        self.server_repo = server_repo
        self.merged = set()  # pinned commits already in our server repo (build_all fills it)
        self.owners = {}
        if inventory:
            from .inventory import address_owners
            self.owners = address_owners(inventory)
        self.channels = {r["id"]: r for r in self.db.execute("SELECT * FROM channels")}
        self.thread_ids = {r["id"] for r in self.db.execute("SELECT id FROM threads")}
        self.notes = [self.reader.note] if self.reader.note else []

    # Units: every thread, and release-looking posts of tracked text channels that start no thread

    def units(self):
        out = []
        for t in self.db.execute("SELECT * FROM threads ORDER BY id"):
            out.append(("thread", t))
        for c in self.db.execute("SELECT * FROM channels WHERE type IN (0, 5) ORDER BY position, id"):
            for m in self.db.execute("SELECT * FROM messages WHERE conversation_id = ? ORDER BY id", (c["id"],)):
                if m["id"] in self.thread_ids or m["reply_to_id"]:
                    continue
                if self._has_release_signal(m["id"]):
                    out.append(("post", m))
        return out

    def _has_release_signal(self, message_id):
        q = self.db.execute
        return bool(q("SELECT 1 FROM links WHERE message_id = ? LIMIT 1", (message_id,)).fetchone()
                    or q("SELECT 1 FROM attachments WHERE message_id = ? LIMIT 1", (message_id,)).fetchone()
                    or q("SELECT 1 FROM code_blocks WHERE message_id = ? LIMIT 1", (message_id,)).fetchone())

    def build_all(self):
        dossiers = []
        units = self.units()
        rows = [self._unit_messages(kind, row) for kind, row in units]
        pins = set()
        for _kind, _row, starter, replies in rows:
            for m in ([starter] if starter else []) + replies:
                for s in self.db.execute("SELECT kind, pinned_sha FROM sources WHERE message_id = ?", (m["id"],)):
                    if s["kind"] in CHANGE_KINDS:
                        pins.add(s["pinned_sha"])
        self.merged = in_history(self.server_repo, pins)
        for kind, row, starter, replies in rows:
            dossiers.append(self.build(kind, row, starter, replies))
        return dossiers

    def _unit_messages(self, kind, row):
        if kind == "post":
            replies = self.db.execute("SELECT * FROM messages WHERE reply_to_id = ? AND conversation_id = ? ORDER BY id",
                                      (row["id"], row["conversation_id"])).fetchall()
            return kind, row, row, [m for m in replies if _worth_keeping(m, self.db)]
        starter = self.db.execute("SELECT * FROM messages WHERE id = ?", (row["id"],)).fetchone()
        own = self.db.execute("SELECT * FROM messages WHERE conversation_id = ? ORDER BY id", (row["id"],)).fetchall()
        if starter is None and own:
            starter = own[0]
        replies = [m for m in own if (starter is None or m["id"] != starter["id"]) and _worth_keeping(m, self.db)]
        return kind, row, starter, replies

    def build(self, kind, row, starter, replies):
        if kind == "thread":
            channel = self.channels.get(row["parent_id"])
            unit_id, title, created = str(row["id"]), row["name"] or "", row["created_at"]
            owner = row["owner_id"]
            tags = _tag_names(channel, row["applied_tags"])
            state = {"archived": bool(row["archived"]), "locked": bool(row["locked"])}
        else:
            channel = self.channels.get(row["conversation_id"])
            unit_id = f"post-{row['id']}"
            title = _first_line(row["content"]) or f"post {row['id']}"
            created, owner, tags, state = row["created_at"], row["author_id"], [], {}
        release, reply_ev = rules.Evidence(), rules.Evidence()
        release.add_text(title)
        starter_d = self._message(starter, release) if starter else None
        reply_ds = [self._message(m, reply_ev) for m in replies]
        thread_author = owner if owner is not None else (starter["author_id"] if starter else None)
        for r in reply_ds:
            r["by_release_author"] = thread_author is not None and r["author_id"] == str(thread_author)
        revisions = []
        if starter:
            for r in self.db.execute("SELECT edited_at, replaced_at, content FROM message_revisions "
                                     "WHERE message_id = ? ORDER BY id", (starter["id"],)):
                revisions.append({"edited_at": r["edited_at"], "replaced_at": r["replaced_at"], "text": r["content"]})
                release.add_text(r["content"])  # an earlier version may name what the release was for
        release_d = release.to_dict(self.owners)
        dossier = {
            "dossier_version": DOSSIER_VERSION,
            "id": unit_id,
            "unit": kind,
            "title": title,
            "channel": {"id": str(channel["id"]) if channel else None, "name": channel["name"] if channel else None,
                        "type": CHANNEL_TYPES.get(channel["type"], str(channel["type"])) if channel else None},
            "tags": tags,
            "author": {"id": str(thread_author) if thread_author is not None else None,
                       "name": self._author_name(thread_author, starter)},
            "created_at": created,
            "discord_url": self._discord_url(channel, unit_id, row, kind),
            **state,
            "starter": starter_d,
            "revisions": revisions,
            "replies": reply_ds,
            "evidence": {"release": release_d, "replies": reply_ev.to_dict(self.owners)},
            "hints": rules.hints(title, release_d),
            "stats": {"replies": len(reply_ds),
                      "links": sum(len(m["links"]) for m in ([starter_d] if starter_d else []) + reply_ds),
                      "attachments": sum(len(m["attachments"]) for m in ([starter_d] if starter_d else []) + reply_ds),
                      "code_blocks": sum(len(m["code_blocks"]) for m in ([starter_d] if starter_d else []) + reply_ds),
                      "edits": len(revisions)},
        }
        dossier["hash"] = content_hash(dossier)
        return dossier

    def _author_name(self, author_id, starter):
        if starter is not None and (author_id is None or starter["author_id"] == author_id):
            return starter["author_name"]
        if author_id is not None:
            row = self.db.execute("SELECT author_name FROM messages WHERE author_id = ? AND author_name IS NOT NULL "
                                  "ORDER BY id LIMIT 1", (author_id,)).fetchone()
            if row:
                return row["author_name"]
        return None

    def _discord_url(self, channel, unit_id, row, kind):
        guild = channel["guild_id"] if channel else None
        if not guild:
            return None
        if kind == "thread":
            return f"https://discord.com/channels/{guild}/{unit_id}"
        return f"https://discord.com/channels/{guild}/{row['conversation_id']}/{row['id']}"

    def _message(self, m, evidence):
        text = m["content"] or ""
        evidence.add_text(text)
        out = {"id": str(m["id"]), "author": m["author_name"], "author_id": str(m["author_id"]) if m["author_id"]
               is not None else None, "bot": bool(m["author_bot"]), "created_at": m["created_at"],
               "edited_at": m["edited_at"], "reply_to": str(m["reply_to_id"]) if m["reply_to_id"] else None,
               "text": text, "links": [], "attachments": [], "code_blocks": []}
        for b in self.db.execute("SELECT idx, language, guessed, text FROM code_blocks WHERE message_id = ? ORDER BY idx",
                                 (m["id"],)):
            out["code_blocks"].append({"index": b["idx"], "language": b["language"], "guessed": bool(b["guessed"]),
                                       "text": b["text"]})
            evidence.add_code(b["language"], b["text"])
        for a in self.db.execute("SELECT * FROM attachments WHERE message_id = ? ORDER BY id", (m["id"],)):
            out["attachments"].append(self._attachment(a, evidence))
        for link in self.db.execute(
                "SELECT l.url, l.kind, l.host, l.owner, l.repo, l.ref, l.path, s.status, s.method, s.local_path, "
                "s.ref AS pin_ref, s.pinned_sha, s.base_sha, s.path AS pin_path, s.error, s.note "
                "FROM links l LEFT JOIN sources s ON s.message_id = l.message_id AND s.url = l.url "
                "WHERE l.message_id = ? ORDER BY l.rowid", (m["id"],)):
            out["links"].append(self._link(link, evidence))
        return out

    def _attachment(self, a, evidence):
        rel = f"attachments/{a['sha256'][:2]}/{a['sha256']}" if a["sha256"] else None
        body = self.reader.blob(rel) if a["status"] == "stored" else None
        kind = rules.file_type(a["filename"], body[:16] if body else None)
        out = {"id": str(a["id"]), "filename": a["filename"], "content_type": a["content_type"], "size": a["size"],
               "status": a["status"], "sha256": a["sha256"], "type": kind, "excerpt": None}
        evidence.file_types.add(kind)
        if body and kind.startswith("text:"):
            text = rules.decode_text(body)
            evidence.add_code(rules.text_language(a["filename"], text), text)
            out["excerpt"] = text[:EXCERPT_CHARS]
        if a["filename"]:
            evidence.add_files([a["filename"]])
        return out

    def _link(self, link, evidence):
        snapshot = None
        if link["status"]:
            snapshot = {"status": link["status"], "method": link["method"], "ref": link["pin_ref"],
                        "pinned_sha": link["pinned_sha"], "base_sha": link["base_sha"], "path": link["pin_path"],
                        "error": link["error"], "note": link["note"]}
        out = {"url": link["url"], "kind": link["kind"], "owner": link["owner"], "repo": link["repo"],
               "snapshot": snapshot, "files": None, "excerpt": None,
               "in_botstory_history": (link["pinned_sha"] in self.merged) if link["kind"] in CHANGE_KINDS
               and link["pinned_sha"] else None}
        if not snapshot or snapshot["status"] != "stored":
            return out
        row = {"status": link["status"], "method": link["method"], "local_path": link["local_path"],
               "pinned_sha": link["pinned_sha"], "base_sha": link["base_sha"], "path": link["pin_path"],
               "kind": link["kind"]}
        if link["method"] == "git":
            info, names = self.reader.files(row)
            if info:
                out["files"] = info
                evidence.add_files(names, whole_tree=info["mode"] in ("repo", "tree"))
                if info["mode"] in ("changed", "file") or link["kind"] == "gist":
                    deleted = {p for p, st in (info.get("status") or {}).items() if st == "D"}
                    readable = [p for p in names if p not in deleted][:MAX_READ_FILES]
                    for path in readable:
                        text = self.reader.file_text(row, path)
                        if text is None:
                            continue
                        evidence.add_code(rules.text_language(path, text), text)
                        if out["excerpt"] is None and (info["mode"] == "file" or link["kind"] == "gist"):
                            out["excerpt"] = text[:EXCERPT_CHARS]
        elif link["local_path"]:  # a paste, or a Discord file link resolved to a stored attachment
            body = self.reader.blob(link["local_path"])
            if body:
                kind = rules.file_type(link["url"].rsplit("/", 1)[-1], body[:16])
                if kind.startswith("text:"):
                    text = rules.decode_text(body)
                    evidence.add_code(rules.text_language("", text), text)
                    out["excerpt"] = text[:EXCERPT_CHARS]
                else:
                    evidence.file_types.add(kind)
        return out


def _worth_keeping(m, db):
    if (m["content"] or "").strip():
        return True
    return db.execute("SELECT 1 FROM attachments WHERE message_id = ? LIMIT 1", (m["id"],)).fetchone() is not None


def _first_line(text):
    for line in (text or "").splitlines():
        line = line.strip()
        if line:
            return line[:80]
    return ""


def _tag_names(channel, applied):
    try:
        ids = [str(t) for t in json.loads(applied or "[]")]
    except ValueError:
        return []
    if not ids:
        return []
    names = {}
    if channel and channel["raw"]:
        try:
            for t in json.loads(channel["raw"]).get("available_tags") or []:
                names[str(t.get("id"))] = t.get("name")
        except ValueError:
            pass
    return [names.get(i) or f"tag {i}" for i in ids]


def content_hash(dossier):
    body = {k: v for k, v in dossier.items() if k != "hash"}
    return hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()


def write(dossiers, folder):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    keep = set()
    for d in dossiers:
        name = f"{d['id']}.json"
        keep.add(name)
        path = folder / name
        text = json.dumps(d, ensure_ascii=False, indent=1)
        if not path.exists() or path.read_text("utf-8") != text:
            part = path.with_suffix(".part")
            part.write_text(text, "utf-8")
            os.replace(part, path)
    for old in folder.glob("*.json"):
        if old.name not in keep:
            old.unlink()


def load_all(folder):
    folder = Path(folder)
    return [json.loads(p.read_text("utf-8")) for p in sorted(folder.glob("*.json"))] if folder.is_dir() else []


def build(store, data_dir, inventory=None, server_repo=None, git_env=None):
    builder = Builder(store, data_dir, inventory, server_repo, git_env)
    return builder.build_all(), builder.notes
