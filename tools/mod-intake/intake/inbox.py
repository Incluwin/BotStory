"""Manual inbox: Discord text you copied yourself, one file per thread in <data dir>/inbox/ (.txt or .md).

A file may start with the thread's Discord link (it becomes the thread's id, so the record lines up with what a bot
would read) and a 'Title: ...' line; the rest is the copied messages. Both Discord copy layouts are understood:
'name — date time' header lines (cozy) and '[date time] name: text' lines (compact). Text without such headers is one
message. The files are the record: a changed file is imported again, a deleted file's thread is dropped.
"""
import hashlib
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .store import synthetic_id

CHANNEL_TYPE = 1002
SUFFIXES = (".txt", ".md")
MAX_MESSAGES = 500
DISCORD_LINK_RE = re.compile(r"<?https?://(?:(?:ptb|canary)\.)?discord(?:app)?\.com/channels/(\d+)/(\d+)(?:/(\d+))?>?")
TITLE_RE = re.compile(r"^title\s*:\s*(.+)$", re.IGNORECASE)
TIME = r"\d{1,2}:\d{2}(?::\d{2})?(?:\s?[AaPp]\.?[Mm]\.?)?"
WHEN = r"(?:(?:Today|Yesterday)(?: at)?|\d{1,4}[/.-]\d{1,2}[/.-]\d{2,4},?(?: at)?)\s*" + TIME
COZY_RE = re.compile(r"^(?P<author>\S.{0,60}?)\s+[—–]\s+(?P<when>" + WHEN + r")\s*$")
COMPACT_RE = re.compile(r"^\[(?P<when>[^\]\n]{0,30}?" + TIME + r")\]\s*(?P<author>[^:\n]{1,60}):\s?(?P<text>.*)$")
DASHES = {"—", "–"}


@dataclass
class Pasted:
    link: tuple = None   # (guild, channel or thread, message or None) from a Discord link at the top
    title: str = None
    messages: list = field(default_factory=list)  # {"author", "when", "text"}


@dataclass
class InboxStats:
    files: int = 0
    imported: int = 0
    unchanged: int = 0
    skipped: int = 0
    removed: int = 0
    notes: list = field(default_factory=list)


def parse(text):
    lines = _join_split_headers(text.replace("\r\n", "\n").replace("\r", "\n").split("\n"))
    out = Pasted()
    i = 0
    while i < len(lines):  # the head: blank lines, the thread's Discord link, a Title: line
        line = lines[i].strip()
        link = DISCORD_LINK_RE.fullmatch(line)
        title = TITLE_RE.match(line)
        if not line:
            pass
        elif link and out.link is None:
            out.link = link.groups()
        elif title and out.title is None:
            out.title = title.group(1).strip()
        else:
            break
        i += 1
    current, fenced = None, False
    for raw in lines[i:]:
        line = raw.strip()
        header = None if fenced else (COZY_RE.match(line) or COMPACT_RE.match(line))
        if line.startswith("```"):
            fenced = not fenced
        if header:
            current = {"author": header.group("author").strip(), "when": header.group("when"), "lines": []}
            if "text" in header.groupdict() and header.group("text"):
                current["lines"].append(header.group("text"))
            out.messages.append(current)
            continue
        if current is None:
            current = {"author": None, "when": None, "lines": []}
            out.messages.append(current)
        current["lines"].append(raw)
    for m in out.messages:
        m["text"] = "\n".join(m.pop("lines")).strip("\n")
    out.messages = [m for m in out.messages if m["text"].strip()][:MAX_MESSAGES]
    return out


def _join_split_headers(lines):
    """Some Discord copies put 'name', '—' and the date on three lines: make them one header line."""
    out = []
    i = 0
    while i < len(lines):
        if lines[i].strip() in DASHES and out and i + 1 < len(lines):
            out[-1] = f"{out[-1].strip()} {lines[i].strip()} {lines[i + 1].strip()}"
            i += 2
            continue
        out.append(lines[i])
        i += 1
    return out


def run_inbox(store, inbox_dir, log=print):
    inbox_dir = Path(inbox_dir)
    inbox_dir.mkdir(parents=True, exist_ok=True)
    stats = InboxStats()
    known = store.inbox_files()
    taken = {row["thread_id"]: name for name, row in known.items()}
    channel_id = synthetic_id("source:inbox")
    present = set()
    for path in sorted(p for p in inbox_dir.iterdir() if p.is_file() and p.suffix.lower() in SUFFIXES):
        stats.files += 1
        present.add(path.name)
        data = path.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        row = known.get(path.name)
        if row is not None and row["sha256"] == sha:
            stats.unchanged += 1
            continue
        pasted = parse(data.decode("utf-8-sig", errors="replace"))
        if not pasted.messages:
            _note(stats, log, f"{path.name}: no text to import")
            stats.skipped += 1
            continue
        tid = _unit_id(pasted, path.name, taken)
        existing = store.thread(tid)
        if existing is not None and existing["parent_id"] != channel_id:
            _note(stats, log, f"{path.name}: thread {tid} is already in the store from Discord; not imported")
            stats.skipped += 1
            continue
        if row is not None and row["thread_id"] != tid:  # the file's link changed
            store.delete_conversation(row["thread_id"])
            taken.pop(row["thread_id"], None)
        _import(store, channel_id, tid, path, pasted)
        store.save_inbox_file(path.name, sha, tid)
        taken[tid] = path.name
        stats.imported += 1
    for name, row in known.items():
        if name not in present:
            store.delete_conversation(row["thread_id"])
            store.delete_inbox_file(name)
            stats.removed += 1
    store.commit()
    return stats


def _import(store, channel_id, tid, path, pasted):
    stamp = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(timespec="seconds")
    guild = pasted.link[0] if pasted.link else None
    store.save_source_channel(channel_id, CHANNEL_TYPE, "inbox (pasted by hand)", guild_id=guild,
                              raw={"source": "inbox"})
    messages = []
    for i, m in enumerate(pasted.messages):
        author_id = synthetic_id("inbox-author:" + m["author"].lower()) if m["author"] else None
        messages.append({"id": str(tid + i), "type": 0,
                         "author": {"id": author_id, "username": m["author"] or "unknown"},
                         "content": m["text"], "timestamp": stamp, "pasted_when": m["when"]})
    title = pasted.title or _first_line(pasted.messages[0]["text"]) or path.stem
    store.replace_conversation(tid, messages)
    store.save_thread({"id": tid, "parent_id": channel_id, "name": title[:120], "owner_id": messages[0]["author"]["id"],
                       "created_at": stamp, "message_count": len(messages), "last_message_id": tid + len(messages) - 1,
                       "thread_metadata": {}, "source": "inbox", "file": path.name})


def _unit_id(pasted, name, taken):
    """The thread id from the file's Discord link (the message id when another file already uses that thread), else
    a stable id from the file name."""
    if pasted.link:
        _guild, conversation, message = pasted.link
        for candidate in (conversation, message):
            if candidate and taken.get(int(candidate), name) == name:
                return int(candidate)
    return synthetic_id("inbox:" + name.lower())


def _first_line(text):
    for line in (text or "").splitlines():
        if line.strip():
            return line.strip()[:120]
    return ""


def _note(stats, log, text):
    stats.notes.append(text)
    log("Note: " + text)
