"""One sync pass: tracked channels -> threads -> new messages, starter-post edits, attachments."""
import hashlib
import os
import urllib.parse
from dataclasses import dataclass, field

from .api import DiscordError, Forbidden, NotFound

CATEGORY = 4
TEXT_TYPES = {0, 5}     # text, announcement: messages in the channel itself, plus threads
FORUM_TYPES = {15, 16}  # forum, media: threads only; a thread's id is its starter message's id
TRACKABLE = TEXT_TYPES | FORUM_TYPES
CDN_HOSTS = {"cdn.discordapp.com", "media.discordapp.net"}
MAX_ATTACHMENT_ATTEMPTS = 5
INTENT_PROBE_MIN = 5


class IntakeError(Exception):
    pass


@dataclass
class Stats:
    threads: int = 0
    new_threads: int = 0
    new_messages: int = 0
    edited_messages: int = 0
    stored_attachments: int = 0
    failed_attachments: int = 0
    skipped_attachments: int = 0
    probe_messages: int = 0
    probe_blank: int = 0
    notes: list = field(default_factory=list)


def select_tracked(channels, wanted_ids=()):
    """Channel ids to read. Explicit ids (a category means its channels), else names containing 'release'."""
    by_id = {int(c["id"]): c for c in channels}
    children = {}
    for c in channels:
        if c.get("parent_id"):
            children.setdefault(int(c["parent_id"]), []).append(c)
    tracked = set()
    if wanted_ids:
        for wanted in wanted_ids:
            c = by_id.get(int(wanted))
            if c is None:
                raise IntakeError(f"channel {wanted} is not in the guild's channel list (wrong id, or the bot "
                                  f"cannot see it)")
            if c["type"] == CATEGORY:
                tracked |= {int(k["id"]) for k in children.get(int(wanted), []) if k["type"] in TRACKABLE}
            elif c["type"] in TRACKABLE:
                tracked.add(int(wanted))
            else:
                raise IntakeError(f"channel {wanted} ({c.get('name')}) is type {c['type']}: not a text, forum or "
                                  f"category channel")
        return tracked
    for c in channels:
        if c["type"] not in TRACKABLE:
            continue
        parent = by_id.get(int(c["parent_id"])) if c.get("parent_id") else None
        names = [c.get("name") or "", (parent or {}).get("name") or ""]
        if any("release" in n.lower() for n in names):
            tracked.add(int(c["id"]))
    return tracked


def run_sync(client, store, guild_id, attachments_dir, wanted_ids=(), page_size=100, refresh_starters=True,
             fetch_attachments=True, max_attachment_bytes=100 * 1024 * 1024, log=print):
    run_id = store.begin_run()
    stats = Stats()
    try:
        _sync(client, store, guild_id, attachments_dir, wanted_ids, page_size, refresh_starters,
              fetch_attachments, max_attachment_bytes, stats, log)
    except BaseException as e:
        store.commit()
        store.finish_run(run_id, f"failed: {e}"[:500], stats, client.requests)
        raise
    store.finish_run(run_id, "ok", stats, client.requests)
    return stats


def _sync(client, store, guild_id, attachments_dir, wanted_ids, page_size, refresh_starters, fetch_attachments,
          max_attachment_bytes, stats, log):
    try:
        channels = client.guild_channels(guild_id)
    except (NotFound, Forbidden) as e:
        raise IntakeError(f"cannot list the channels of guild {guild_id}: is DISCORD_GUILD_ID right and is the bot "
                          f"in that server? ({e})") from e
    by_id = {int(c["id"]): c for c in channels}
    tracked = select_tracked(channels, wanted_ids)
    if not tracked:
        raise IntakeError("no channel matched: set INTAKE_CHANNELS to the releases channel or category ids "
                          "(see the 'channels' command)")
    store.save_channels(channels, tracked)
    log("Tracking: " + ", ".join(f"#{by_id[c]['name']}" for c in sorted(tracked, key=_position(by_id))))

    threads = {int(t["id"]): t for t in client.active_threads(guild_id) if _int(t.get("parent_id")) in tracked}
    readable = set()
    for cid in sorted(tracked):
        try:
            for t in client.archived_public_threads(cid, page_size):
                threads.setdefault(int(t["id"]), t)
        except Forbidden:
            store.set_channel_access(cid, "forbidden")
            _note(stats, log, f"#{by_id[cid]['name']}: the bot may not read this channel (Missing Access)")
            continue
        store.set_channel_access(cid, "ok")
        readable.add(cid)

    for cid in sorted(readable):
        if by_id[cid]["type"] in TEXT_TYPES:
            _read_conversation(client, store, cid, by_id[cid].get("last_message_id"), page_size, stats, log)

    for tid in sorted(threads):
        t = threads[tid]
        parent = int(t["parent_id"])
        if parent not in readable:
            continue
        is_new = store.save_thread(t)
        stats.threads += 1
        stats.new_threads += is_new
        _read_conversation(client, store, tid, t.get("last_message_id"), page_size, stats, log)
        if refresh_starters and not is_new:
            starter_home = tid if by_id[parent]["type"] in FORUM_TYPES else parent
            _refresh_starter(client, store, starter_home, tid, stats)
        store.commit()

    if fetch_attachments:
        _fetch_attachments(client, store, attachments_dir, max_attachment_bytes, stats, log)

    if stats.probe_messages >= INTENT_PROBE_MIN and stats.probe_blank == stats.probe_messages:
        _note(stats, log, f"all {stats.probe_messages} new messages came back with no text: turn on the Message "
                          f"Content Intent for the bot (Developer Portal > Bot)")


def _read_conversation(client, store, conversation_id, last_message_id, page_size, stats, log):
    cursor = store.cursor(conversation_id)
    last = _int(last_message_id)
    if cursor is not None and last is not None and last <= cursor:
        return
    newest = None
    try:
        for page in client.messages_after(conversation_id, cursor or 0, page_size):
            for m in page:
                status = store.save_message(conversation_id, m)
                if status == "new":
                    stats.new_messages += 1
                    _probe(m, stats)
                elif status == "edited":
                    stats.edited_messages += 1
            newest = int(page[-1]["id"])
            store.set_cursor(conversation_id, newest)
            store.commit()
    except Forbidden:
        _note(stats, log, f"conversation {conversation_id}: Missing Access")
        return
    if last is not None and (newest is None or last > newest):
        # last_message_id still names a deleted message: move past it, or every sync reads this thread again
        store.set_cursor(conversation_id, last)


def _refresh_starter(client, store, home_id, starter_id, stats):
    """Release posts get edited (new versions, new links) without a new message, so re-read each starter."""
    try:
        m = client.message(home_id, starter_id)
    except (NotFound, Forbidden):
        return
    status = store.save_message(home_id, m)
    if status == "edited":
        stats.edited_messages += 1
    elif status == "new":
        stats.new_messages += 1


def _probe(m, stats):
    """Count new human messages that are completely blank: all blank means the content intent is off."""
    if (m.get("author") or {}).get("bot") or m.get("type") not in (0, 19):
        return
    stats.probe_messages += 1
    if not (m.get("content") or m.get("attachments") or m.get("embeds") or m.get("sticker_items")):
        stats.probe_blank += 1


def _fetch_attachments(client, store, attachments_dir, max_bytes, stats, log):
    """Download every new attachment into a content-addressed quarantine: <sha256[:2]>/<sha256>, no extension.

    The files are only ever read as data (disassembled, diffed); nothing here or later runs them.
    """
    for a in store.attachments_to_fetch(MAX_ATTACHMENT_ATTEMPTS):
        if a["size"] and a["size"] > max_bytes:
            store.set_attachment(a["id"], "too_large", detail=f"{a['size']} bytes > {max_bytes}")
            stats.skipped_attachments += 1
            continue
        status, body = _download(client, a["url"])
        if status in (403, 404, 410):  # signed CDN link expired: the message carries a fresh one
            try:
                fresh = client.message(a["conversation_id"], a["message_id"])
            except DiscordError as e:
                fresh = None
                detail = f"HTTP {status}; message re-read failed: {e}"
            if fresh:
                store.refresh_attachment_urls(fresh)
                url = next((x.get("url") for x in fresh.get("attachments") or [] if int(x["id"]) == a["id"]), None)
                status, body = _download(client, url) if url else (0, None)
                detail = f"HTTP {status}" if url else "attachment no longer on the message"
        else:
            detail = f"HTTP {status}"
        if status != 200:
            store.set_attachment(a["id"], "failed", detail=detail)
            stats.failed_attachments += 1
            continue
        sha = hashlib.sha256(body).hexdigest()
        _write_blob(attachments_dir, sha, body)
        store.set_attachment(a["id"], "stored", sha256=sha)
        stats.stored_attachments += 1
    store.commit()
    if stats.failed_attachments:
        _note(stats, log, f"{stats.failed_attachments} attachment(s) failed; they are retried on the next sync")


def _download(client, url):
    host = (urllib.parse.urlparse(url or "").hostname or "").lower()
    if host not in CDN_HOSTS:
        return 0, None
    try:
        resp = client.download(url)
    except OSError:
        return 0, None
    return resp.status, resp.body


def _write_blob(attachments_dir, sha, body):
    folder = os.path.join(attachments_dir, sha[:2])
    path = os.path.join(folder, sha)
    if os.path.exists(path):
        return path
    os.makedirs(folder, exist_ok=True)
    part = path + ".part"
    with open(part, "wb") as f:
        f.write(body)
    os.replace(part, path)
    return path


def _note(stats, log, text):
    stats.notes.append(text)
    log("Note: " + text)


def _int(value):
    return int(value) if value not in (None, "") else None


def _position(by_id):
    def key(cid):
        c = by_id[cid]
        parent = by_id.get(_int(c.get("parent_id")) or -1, {})
        return (parent.get("position", 0), c.get("position", 0), cid)
    return key
