"""In-memory Discord API v10 and CDN for tests, following the real API's paging rules.

Messages with `after` come back as the `limit` messages right after it, newest first; archived threads page by
`before=<archive_timestamp>` with `has_more`; attachment URLs are signed with an expiry (`ex`, hex) and the CDN
answers 404 once the fake clock passes it.
"""
import copy
import json
import sys
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from intake.api import Response  # noqa: E402

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "guild.json"
API_PREFIX = "https://discord.com/api/v10"
CDN_PREFIX = "https://cdn.discordapp.com/attachments/"
TOKEN = "test-token"
DISCORD_EPOCH_MS = 1420070400000


def snowflake_iso(snowflake):
    ms = (int(snowflake) >> 22) + DISCORD_EPOCH_MS
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat()


def json_response(status, payload, headers=None):
    return Response(status, dict(headers or {}, **{"Content-Type": "application/json"}),
                    json.dumps(payload).encode("utf-8"))


class FakeDiscord:
    def __init__(self, fixture=FIXTURE):
        data = json.loads(Path(fixture).read_text("utf-8"))
        self.guild_id = data["guild_id"]
        self.authors = data["authors"]
        self.channels = {c["id"]: dict(c) for c in data["channels"]}
        self.forbidden = {cid for cid, c in self.channels.items() if c.pop("forbidden", False)}
        self.threads = {t["id"]: dict(t) for t in data["threads"]}
        self.files = {}
        self.messages = {}
        for conv, msgs in data["messages"].items():
            for m in msgs:
                self.add_message(conv, m["author"], m["content"], m.get("attachments"), m["id"], m.get("reply_to"),
                                 unarchive=False)
        self.now = 1000             # CDN clock; signed URLs carry ex = now + url_lifetime at serve time
        self.url_lifetime = 100
        self.content_intent = True  # False = what a bot without the Message Content intent sees
        self.token = TOKEN
        self.rate_limited = {}      # path substring -> number of 429s still to send
        self.bucket_empty = {}      # path substring -> x-ratelimit-reset-after to report with remaining 0
        self.log = []               # (method, url without query, query dict)
        self._next_id = 1000000000000009000

    # Changing the guild between syncs

    def new_id(self):
        self._next_id += 10
        return str(self._next_id)

    def add_message(self, conv, author, content, attachments=None, message_id=None, reply_to=None, unarchive=True):
        message_id = message_id or self.new_id()
        msg = {
            "id": message_id, "channel_id": conv, "type": 19 if reply_to else 0,
            "author": dict(self.authors[author]), "content": content,
            "timestamp": snowflake_iso(message_id), "edited_timestamp": None,
            "attachments": [], "embeds": [], "pinned": False,
        }
        if reply_to:
            msg["message_reference"] = {"message_id": reply_to, "channel_id": conv}
        for a in attachments or []:
            body = a["data"].encode("utf-8")
            self.files[a["id"]] = body
            msg["attachments"].append({"id": a["id"], "filename": a["filename"],
                                       "content_type": a.get("content_type"), "size": len(body)})
        self.messages.setdefault(conv, []).append(msg)
        self.messages[conv].sort(key=lambda m: int(m["id"]))
        if unarchive and conv in self.threads:
            self.threads[conv]["archived"] = False
        return message_id

    def edit_message(self, conv, message_id, content, when="2025-07-01T12:00:00+00:00"):
        for m in self.messages[conv]:
            if m["id"] == message_id:
                m["content"] = content
                m["edited_timestamp"] = when
                return
        raise KeyError(message_id)

    def add_thread(self, parent_id, name, owner, first_message, attachments=None):
        thread_id = self.new_id()
        self.threads[thread_id] = {"id": thread_id, "parent_id": parent_id, "name": name, "owner": owner,
                                   "archived": False, "archive_timestamp": "2025-07-01T00:00:00+00:00",
                                   "applied_tags": []}
        self.add_message(thread_id, owner, first_message, attachments, message_id=thread_id)
        return thread_id

    # Transport

    def request(self, method, url, headers):
        parsed = urllib.parse.urlparse(url)
        query = dict(urllib.parse.parse_qsl(parsed.query))
        base = f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
        self.log.append((method, base, query))
        if base.startswith(CDN_PREFIX):
            if "Authorization" in headers:
                raise AssertionError("token sent to the CDN")
            return self._cdn(base, query)
        assert base.startswith(API_PREFIX), url
        if headers.get("Authorization") != "Bot " + self.token:
            return json_response(401, {"message": "401: Unauthorized", "code": 0})
        path = base[len(API_PREFIX):]
        for pattern, remaining in self.rate_limited.items():
            if pattern in path and remaining > 0:
                self.rate_limited[pattern] = remaining - 1
                return json_response(429, {"message": "You are being rate limited.", "retry_after": 0.25,
                                           "global": False}, {"Retry-After": "1"})
        resp = self._route(path.strip("/").split("/"), query)
        for pattern, reset_after in self.bucket_empty.items():
            if pattern in path:
                resp.headers["x-ratelimit-remaining"] = "0"
                resp.headers["x-ratelimit-reset-after"] = str(reset_after)
        return resp

    def _route(self, parts, query):
        if parts[:1] == ["guilds"]:
            if parts[1] != self.guild_id:
                return json_response(404, {"message": "Unknown Guild", "code": 10004})
            if parts[2:] == ["channels"]:
                return json_response(200, [self._channel_obj(c) for c in self.channels.values()])
            if parts[2:] == ["threads", "active"]:
                active = [self._thread_obj(t) for t in self.threads.values()
                          if not t["archived"] and t["parent_id"] not in self.forbidden]
                return json_response(200, {"threads": active, "members": []})
        if parts[:1] == ["channels"]:
            cid = parts[1]
            if cid not in self.channels and cid not in self.threads:
                return json_response(404, {"message": "Unknown Channel", "code": 10003})
            parent = self.threads[cid]["parent_id"] if cid in self.threads else cid
            if parent in self.forbidden:
                return json_response(403, {"message": "Missing Access", "code": 50001})
            if parts[2:] == ["threads", "archived", "public"]:
                return self._archived(cid, query)
            if parts[2:] == ["messages"]:
                return self._messages(cid, query)
            if len(parts) == 4 and parts[2] == "messages":
                for m in self.messages.get(cid, []):
                    if m["id"] == parts[3]:
                        return json_response(200, self._serve(m))
                return json_response(404, {"message": "Unknown Message", "code": 10008})
        return json_response(404, {"message": "404: Not Found", "code": 0})

    def _archived(self, cid, query):
        limit = int(query.get("limit", 50))
        archived = sorted((t for t in self.threads.values() if t["parent_id"] == cid and t["archived"]),
                          key=lambda t: t["archive_timestamp"], reverse=True)
        if "before" in query:
            archived = [t for t in archived if t["archive_timestamp"] < query["before"]]
        page = archived[:limit]
        return json_response(200, {"threads": [self._thread_obj(t) for t in page], "members": [],
                                   "has_more": len(archived) > limit})

    def _messages(self, cid, query):
        limit = min(int(query.get("limit", 50)), 100)
        msgs = self.messages.get(cid, [])
        if "after" in query:
            page = [m for m in msgs if int(m["id"]) > int(query["after"])][:limit]
        else:
            page = msgs[-limit:]
        return json_response(200, [self._serve(m) for m in reversed(page)])

    def _cdn(self, base, query):
        conv, attachment_id, _filename = base[len(CDN_PREFIX):].split("/", 2)
        if attachment_id not in self.files:
            return Response(404, {}, b"Not Found")
        if int(query.get("ex", "0"), 16) < self.now:
            return Response(404, {}, b"This content is no longer available.")
        return Response(200, {"Content-Type": "application/octet-stream"}, self.files[attachment_id])

    # Objects as the API serves them

    def _serve(self, m):
        out = copy.deepcopy(m)
        expiry = self.now + self.url_lifetime
        for a in out["attachments"]:
            a["url"] = f"{CDN_PREFIX}{m['channel_id']}/{a['id']}/{a['filename']}?ex={expiry:x}&is={self.now:x}&hm=fake"
            a["proxy_url"] = a["url"].replace("cdn.discordapp.com", "media.discordapp.net")
        if not self.content_intent:
            out["content"] = ""
            out["attachments"] = []
            out["embeds"] = []
        return out

    def _channel_obj(self, c):
        out = {"id": c["id"], "type": c["type"], "guild_id": self.guild_id, "name": c["name"],
               "position": c.get("position", 0), "parent_id": c.get("parent_id")}
        if c["type"] in (0, 5):
            msgs = self.messages.get(c["id"], [])
            out["last_message_id"] = msgs[-1]["id"] if msgs else None
        return out

    def _thread_obj(self, t):
        msgs = self.messages.get(t["id"], [])
        return {
            "id": t["id"], "type": 11, "guild_id": self.guild_id, "parent_id": t["parent_id"],
            "owner_id": self.authors[t["owner"]]["id"], "name": t["name"],
            "last_message_id": msgs[-1]["id"] if msgs else None, "message_count": len(msgs),
            "applied_tags": t["applied_tags"],
            "thread_metadata": {"archived": t["archived"], "archive_timestamp": t["archive_timestamp"],
                                "locked": False, "auto_archive_duration": 4320},
        }

    # Assertions helpers

    def api_calls(self, fragment=""):
        return [(base, q) for _m, base, q in self.log if base.startswith(API_PREFIX) and fragment in base]

    def message_listings(self):
        """Conversation ids whose message list was fetched (not single-message gets)."""
        return [base.split("/")[-2] for base, _q in self.api_calls() if base.endswith("/messages")]
