"""P3 judgments by TypeSafe Jev (https://docs.typesafe.ai/api): typed answers over a thread's facts.

One request per thread holds the thread-level questions (kind, includes-kind, version, server family, which of our
mods it matches, how it relates to them) and one question per reply (fix, bug report or neither); a long thread's
remaining replies go in more requests with a short header. State carries what was observed (titles, text, links and
their pins, file lists, rule evidence) and never a conclusion: no rule hint goes into a request.

Answers are cached in <data dir>/triage.sqlite3 by a hash of the exact request (model + state + questions), so a
re-run pays only for threads whose content changed. The API key (TYPESAFE_API_KEY) is read from the environment or
a .env file and never printed.
"""
import hashlib
import json
import sqlite3
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

from .store import now_iso

API_URL = "https://api.typesafe.ai/v1/systemone"
DEFAULT_MODEL = "jev-1.13.0"     # pinned: thresholds in triage.py were set against it; jev-latest moves
PRICE_PER_MTOK = 0.042           # USD per million input tokens, output free (docs.typesafe.ai/models, 2026-10-17)
CHARS_PER_TOKEN = 3.2            # JSON-heavy state with code: an estimate for --dry-run, real runs use usage
MAX_STATE_CHARS = 36000          # ~11k tokens: far under the 32k per-question limit; accuracy falls with size
STARTER_CHARS = 6000
STARTER_CHARS_SHORT = 1500       # the first post again, in requests that carry only more replies
REPLY_CHARS = 900
CODE_CHARS = 1500
REPLY_CODE_CHARS = 600
LISTED_PATHS = 25
MAX_BLOCKS, MAX_ATTACHMENTS, MAX_LINKS = 6, 8, 15   # per message; the count of the rest is stated
REPLIES_PER_REQUEST = 40
MAX_ATTEMPTS = 5
USER_AGENT = "botstory-mod-intake/3 (+https://github.com/Incluwin/BotStory)"

KINDS = ("client_hook", "client_wz", "server_java", "server_script", "database", "tool", "not_a_mod")
MOD_KINDS = KINDS[:-1]
VERSIONS = ("v83", "other", "unknown")
FAMILIES = ("cosmic", "heavenms_line", "other_base", "no_server_part")
APPLIES = ("already_have", "conflicts", "new")
REPLY_CLASSES = ("fix", "bug_report", "neither")

LINK_KINDS = {
    "github_repo": "GitHub repository", "github_commit": "GitHub commit", "github_pull": "GitHub pull request",
    "github_blob": "file in a GitHub repository", "github_tree": "folder in a GitHub repository",
    "github_compare": "GitHub comparison of two versions", "github_release": "GitHub release",
    "github_raw": "raw file from GitHub", "github_issue": "GitHub issue", "gist": "GitHub gist", "paste": "paste",
    "git_repo": "git repository", "download": "file download host", "discord_attachment": "Discord file",
    "discord_message": "link to another Discord message", "other": "web page",
}
FILE_TYPES = {
    "pe": "Windows DLL or EXE", "wz": "WZ archive (.wz)", "wz_img": "WZ image (.img)", "zip": "zip archive",
    "rar": "rar archive", "7z": "7z archive", "image": "image", "video": "video", "binary": "binary file",
    "unknown": "unknown",
}
LAYOUT_NAMES = {
    "maven_java": "Java under src/main/java (Maven layout)",
    "flat_src_java": "Java under src/net, src/client, src/server (flat src layout)",
    "scripts": "files under scripts/npc, quest, event, portal, reactor or map", "wz_xml": "WZ XML (.img.xml)",
    "sql": "SQL files", "cpp": "C or C++ sources", "maven_pom": "a pom.xml", "gradle": "a build.gradle",
}

CONTEXT = ("Release threads from a MapleStory private-server development community. A release is something "
           "others can add to their own game client or server.")
KIND_CRITERIA = {
    "client_hook": {"what": "Code that runs inside the MapleStory game client: a DLL or C++ hook, a memory patch, or "
                            "edits at client addresses",
                    "signs": "C++ code, client addresses such as 0x00A2C8E0, client class names such as CWvsContext "
                             "or CField, a DLL file"},
    "client_wz": {"what": "Client data: .wz archives, .img files, or exported WZ data (maps, items, UI, effects, "
                          "skins)"},
    "server_java": {"what": "Java source code of a game server (handlers, packets, game logic), as a patch, commit, "
                            "pull request, file or repository"},
    "server_script": {"what": "Server scripts: NPC, quest, event, portal, reactor or map scripts, usually "
                              "JavaScript files under scripts/"},
    "database": {"what": "Database changes: SQL for tables, rows, drops, shops or other game data"},
    "tool": {"what": "A program used outside the game: an editor, launcher, packet tool, converter or website"},
    "not_a_mod": {"what": "Nothing to add to a client or server: a question, a request, a discussion, an "
                          "announcement, or a link with no release behind it"},
}
INCLUDES_QUESTIONS = {
    "client_hook": "code that runs inside the game client (C++ or DLL hook code, memory patches, or client "
                   "addresses to patch)",
    "client_wz": "client WZ data (.wz or .img files, or exported WZ data)",
    "server_java": "Java source code for a game server",
    "server_script": "server scripts (NPC, quest, event, portal, reactor or map scripts)",
    "database": "SQL or other database changes",
    "tool": "a standalone program or tool used outside the game",
}
VERSION_CRITERIA = {
    "v83": "Global MapleStory (GMS) version 83, including v83 server sources such as Cosmic, HeavenMS, MapleSolaxia "
           "and MoopleDEV, and the kaentake v83 client",
    "other": "A different game version, such as v62, v90, v95, v111, v117, v176 or a current client",
    "unknown": "Neither the thread nor its files show which game version it is for",
}
FAMILY_CRITERIA = {
    "cosmic": {"what": "P0nk's Cosmic server, or code written against it",
               "signs": "names Cosmic; uses client.Character, tools.PacketCreator, YamlConfig; Java under "
                        "src/main/java"},
    "heavenms_line": {"what": "HeavenMS or an older v83 source of the same line: MapleSolaxia, MoopleDEV, an "
                              "OdinMS-based v83 source",
                      "signs": "names HeavenMS or Solaxia; uses MapleCharacter, MapleClient, MaplePacketCreator; "
                               "Java under src/net, src/client, src/server"},
    "other_base": {"what": "A server source of a different family or language, such as Swordie, Kinoko, Lithium "
                           "or AzureMS"},
    "no_server_part": {"what": "The release has no server-side code, scripts or database changes (client only, a "
                               "tool, or no release)"},
}
# "new" first: Jev leans toward the first option, and a wrong "new" costs a look while a wrong "already_have" loses a mod
APPLIES_CRITERIA = {
    "new": {"what": "No entry of `botstory_mods` makes this change, and it does not change the same thing as any of "
                    "them",
            "includes": "A release on the same topic as an entry (skills, HP bars, login) that adds or fixes something "
                        "that entry does not"},
    "already_have": {"what": "An entry of `botstory_mods` makes the same change to the game: the same feature, or a "
                             "fix for the same problem",
                     "not_for": "An entry that only shares a name or a topic with the release but changes something "
                                "else"},
    "conflicts": {"what": "It changes the same client function, client address or server system as an entry of "
                          "`botstory_mods`, but in a different way, so the two cannot both be used as they are"},
}
REPLY_CRITERIA = {
    "fix": {"what": "Gives a change that repairs or updates the release: a patch, code, a link to a fixing commit "
                    "or a new version, or says the problem is now fixed",
            "note": "A reply that reports a problem and also gives its fix is a fix"},
    "bug_report": {"what": "Reports that the release fails, crashes or misbehaves, without giving a fix"},
    "neither": {"what": "Anything else: thanks, questions, discussion, unrelated chat"},
}


class JevError(Exception):
    def __init__(self, message, status=None, retryable=False):
        super().__init__(message)
        self.status = status
        self.retryable = retryable


class JevAuthError(JevError):
    pass


@dataclass
class Request:
    unit_id: str
    part: str                       # "main", or "replies-2", "replies-3", ... for a long thread's later replies
    state: dict
    questions: dict
    replies: dict = field(default_factory=dict)  # question id -> message id

    def body(self, model):
        return {"model": model, "state": self.state, "questions": self.questions}

    def key(self, model):
        text = json.dumps(self.body(model), sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        return hashlib.sha256(text.encode("utf-8")).hexdigest()

    def estimated_tokens(self, model):
        return int(len(json.dumps(self.body(model), ensure_ascii=False)) / CHARS_PER_TOKEN) + 1


# Questions and state

def thread_questions(our_mods):
    """The questions asked about every release thread. our_mods: inventory.entries() (id, side, description)."""
    botstory_mods = [f"{m['id']} ({m['side']}): {m['description']}" for m in our_mods]
    q = {
        "kind": {"type": "choice",
                 "instructions": {"context": CONTEXT,
                                  "question": "What is the main thing the release in `release` and `first_post` "
                                              "provides or changes?"},
                 "criteria": KIND_CRITERIA},
        "version": {"type": "choice",
                    "instructions": {"context": CONTEXT,
                                     "question": "Which MapleStory game version is the release in `release` and "
                                                 "`first_post` made for?"},
                    "criteria": VERSION_CRITERIA},
        "family": {"type": "choice",
                   "instructions": {"context": CONTEXT,
                                    "question": "Which game server source is the server-side part of the release in "
                                                "`release` and `first_post` written for?"},
                   "criteria": FAMILY_CRITERIA},
        "applies": {"type": "choice",
                    "instructions": {"botstory_mods": botstory_mods,
                                     "question": "`botstory_mods` lists the client modules and server features "
                                                 "BotStory already has. How does the release in `release` and "
                                                 "`first_post` relate to them?"},
                    "criteria": APPLIES_CRITERIA},
    }
    for kind, what in INCLUDES_QUESTIONS.items():
        q["includes_" + kind] = {"type": "noul",
                                 "instructions": f"Does the release in `release` and `first_post` include {what}?"}
    if our_mods:
        criteria = {m["id"]: f"{m['side']}: {m['description']}" for m in our_mods}
        criteria["none"] = "None of these provides the feature or fix the release provides"
        q["match"] = {"type": "choice",
                      "instructions": "BotStory already has the client modules and server features listed in the "
                                      "options. Which one provides the same feature or fix as the release in "
                                      "`release` and `first_post`?",
                      "criteria": criteria}
    return q


def reply_question(index):
    return {"type": "choice",
            "instructions": f"What does `replies[{index}]`, a reply in the thread of the release in `release`, "
                            f"contribute to that release?",
            "criteria": REPLY_CRITERIA}


def release_state(d, short=False):
    s = d.get("starter") or {}
    first_post = {"text": _cut(s.get("text"), STARTER_CHARS_SHORT if short else STARTER_CHARS)}
    if d.get("revisions"):
        first_post["earlier_versions"] = len(d["revisions"])
    if not short:
        if s.get("code_blocks"):
            first_post["code_blocks"] = [{"language": b["language"], "text": _cut(b["text"], CODE_CHARS)}
                                         for b in s["code_blocks"][:MAX_BLOCKS]]
            _more(first_post, "code_blocks", s["code_blocks"], MAX_BLOCKS)
        if s.get("attachments"):
            first_post["attachments"] = [_attachment(a, CODE_CHARS) for a in s["attachments"][:MAX_ATTACHMENTS]]
            _more(first_post, "attachments", s["attachments"], MAX_ATTACHMENTS)
        if s.get("links"):
            first_post["links"] = [_link(link, full=True) for link in s["links"][:MAX_LINKS]]
            _more(first_post, "links", s["links"], MAX_LINKS)
    state = {"release": {"title": d["title"], "channel": d["channel"]["name"], "forum_tags": d.get("tags") or [],
                         "author": d["author"]["name"], "posted": (d.get("created_at") or "")[:10]},
             "first_post": first_post}
    if not short:
        evidence = _evidence(d["evidence"]["release"])
        if evidence:
            state["evidence"] = evidence
    return state


def _more(target, key, items, limit):
    if len(items) > limit:
        target[key + "_not_shown"] = len(items) - limit


def _reply_state(r, index):
    out = {"reply": index + 1, "author": r["author"], "by_release_author": bool(r.get("by_release_author")),
           "text": _cut(r["text"], REPLY_CHARS)}
    if r.get("links"):
        out["links"] = [_link(link, full=False) for link in r["links"][:MAX_LINKS]]
        _more(out, "links", r["links"], MAX_LINKS)
    if r.get("attachments"):
        out["attachments"] = [_attachment(a, REPLY_CODE_CHARS) for a in r["attachments"][:MAX_ATTACHMENTS]]
        _more(out, "attachments", r["attachments"], MAX_ATTACHMENTS)
    if r.get("code_blocks"):
        out["code_blocks"] = [{"language": b["language"], "text": _cut(b["text"], REPLY_CODE_CHARS)}
                              for b in r["code_blocks"][:MAX_BLOCKS]]
        _more(out, "code_blocks", r["code_blocks"], MAX_BLOCKS)
    return out


def _attachment(a, excerpt_chars):
    kind = a.get("type") or "unknown"
    out = {"file": a.get("filename"),
           "type": ("text (" + kind[5:] + ")") if kind.startswith("text:") else FILE_TYPES.get(kind, kind)}
    if a.get("size") is not None:
        out["size_kb"] = round((a["size"] or 0) / 1024, 1)
    if a.get("status") != "stored":
        out["downloaded"] = False
    if a.get("excerpt"):
        out["text"] = _cut(a["excerpt"], excerpt_chars)
    return out


def _link(link, full):
    out = {"url": link["url"], "points_to": LINK_KINDS.get(link["kind"], link["kind"])}
    snap = link.get("snapshot")
    if not snap:
        out["snapshot"] = "not fetched yet"
    elif snap["status"] == "stored":
        out["snapshot"] = "pinned" + (f" at {snap['pinned_sha'][:12]}" if snap.get("pinned_sha") else "")
    elif snap["status"] == "failed":
        out["snapshot"] = "fetch failed: " + (snap.get("error") or "unknown error")
    else:  # P2's manual (download hosts) and skipped (not a source): never fetched
        out["snapshot"] = "not fetched: " + (snap.get("note") or snap["status"])
    if link.get("in_botstory_history") is not None:
        out["commit_already_in_botstory_server"] = link["in_botstory_history"]
    files = link.get("files")
    if files:
        from .rules import ext_counts
        label = {"changed": "files_changed", "file": "file", "tree": "files_in_folder",
                 "repo": "files_in_repository"}[files["mode"]]
        listed = files["files"][:LISTED_PATHS if full else 8]
        out[label] = {"count": files["count"], "paths": listed}
        if files["count"] > len(listed):
            out[label]["extensions"] = ext_counts(files["files"])
    if full and link.get("excerpt"):
        out["text"] = _cut(link["excerpt"], CODE_CHARS)
    return out


def _evidence(ev):
    out = {}
    if ev.get("addresses"):
        out["client_addresses_in_text_or_files"] = ev["addresses"][:40]
    if ev.get("addresses_our_client_also_uses"):
        out["addresses_botstory_client_modules_also_use"] = ev["addresses_our_client_also_uses"][:20]
    if ev.get("file_paths_in_text"):
        out["file_paths_in_text"] = ev["file_paths_in_text"][:30]
    if ev.get("path_layouts"):
        out["source_layouts"] = [LAYOUT_NAMES.get(x, x) for x in ev["path_layouts"]]
    if ev.get("java_packages"):
        out["java_packages"] = ev["java_packages"][:25]
    if ev.get("identifiers"):
        out["class_names_seen"] = ev["identifiers"]
    if ev.get("family_words"):
        out["source_names_mentioned"] = ev["family_words"]
    if ev.get("version_words"):
        out["version_words"] = ev["version_words"]
    if ev.get("code_languages"):
        out["code_languages"] = ev["code_languages"]
    return out


def _cut(text, limit):
    text = text or ""
    return text if len(text) <= limit else text[:limit] + f" [... {len(text) - limit} more characters]"


def requests_for(dossier, our_mods):
    """The requests that judge one dossier: main (thread questions + the first replies), then more replies."""
    replies = dossier.get("replies") or []
    head = release_state(dossier)
    questions = thread_questions(our_mods)
    budget = MAX_STATE_CHARS - len(json.dumps(head, ensure_ascii=False))
    chunks = _chunks(replies, budget)
    first = chunks[0] if chunks else []
    main = Request(dossier["id"], "main", dict(head, replies=[_reply_state(replies[i], i) for i in first])
                   if first else head, dict(questions))
    for n, i in enumerate(first):
        qid = f"reply_{n}"
        main.questions[qid] = reply_question(n)
        main.replies[qid] = replies[i]["id"]
    out = [main]
    short = release_state(dossier, short=True)
    for part, chunk in enumerate(chunks[1:], start=2):
        req = Request(dossier["id"], f"replies-{part}",
                      dict(short, replies=[_reply_state(replies[i], i) for i in chunk]), {})
        for n, i in enumerate(chunk):
            qid = f"reply_{n}"
            req.questions[qid] = reply_question(n)
            req.replies[qid] = replies[i]["id"]
        out.append(req)
    return out


def _chunks(replies, first_budget):
    chunks, current, size = [], [], 0
    budget = max(first_budget, MAX_STATE_CHARS // 4)
    for i, r in enumerate(replies):
        n = len(json.dumps(_reply_state(r, i), ensure_ascii=False)) + 2
        if current and (size + n > budget or len(current) >= REPLIES_PER_REQUEST):
            chunks.append(current)
            current, size = [], 0
            budget = MAX_STATE_CHARS - 4000  # later requests carry only a short header
        current.append(i)
        size += n
    if current:
        chunks.append(current)
    return chunks


# The API

class HttpTransport:
    def __init__(self, timeout=60):
        self.timeout = timeout

    def post(self, url, headers, body):
        req = urllib.request.Request(url, data=body, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
        except urllib.error.HTTPError as e:
            return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()
        except (urllib.error.URLError, OSError) as e:
            raise JevError(f"TypeSafe API unreachable: {getattr(e, 'reason', e)}", retryable=True) from None


class JevClient:
    def __init__(self, key, transport=None, model=DEFAULT_MODEL, url=API_URL, sleep=time.sleep):
        if not key:
            raise JevAuthError("TYPESAFE_API_KEY is not set")
        self._key = key
        self.transport = transport or HttpTransport()
        self.model = model
        self.url = url
        self.sleep = sleep
        self.requests = 0

    def __repr__(self):
        return f"JevClient(model={self.model!r})"  # never the key

    def ask(self, request):
        body = json.dumps(request.body(self.model), ensure_ascii=False).encode("utf-8")
        headers = {"Authorization": f"Bearer {self._key}", "Content-Type": "application/json",
                   "User-Agent": USER_AGENT}
        for attempt in range(1, MAX_ATTEMPTS + 1):
            self.requests += 1
            try:
                status, resp_headers, payload = self.transport.post(self.url, headers, body)
            except JevError as e:
                if attempt == MAX_ATTEMPTS:
                    raise
                self.sleep(min(2 ** attempt, 30))
                continue
            if status == 200:
                try:
                    data = json.loads(payload.decode("utf-8"))
                except ValueError:
                    raise JevError("TypeSafe answered with something that is not JSON", status) from None
                missing = [q for q in request.questions if q not in (data.get("answers") or {})]
                if missing:
                    raise JevError(f"answers missing for {', '.join(missing[:5])}", status)
                return data
            if status == 401:
                raise JevAuthError("TypeSafe refused the API key (401): check TYPESAFE_API_KEY", status)
            if status in (429, 529) or status >= 500:
                if attempt == MAX_ATTEMPTS:
                    raise JevError(f"TypeSafe HTTP {status} after {attempt} tries", status, retryable=True)
                self.sleep(_retry_after(resp_headers, attempt))
                continue
            raise JevError(f"TypeSafe HTTP {status}: {_detail(payload)}", status)
        raise JevError("unreachable")


def _retry_after(headers, attempt):
    try:
        return min(max(float(headers.get("retry-after")), 0.5), 120)
    except (TypeError, ValueError):
        return min(2 ** attempt, 60)


def _detail(payload):
    try:
        data = json.loads(payload.decode("utf-8"))
        detail = data.get("detail") or data.get("error") or data.get("message") or data
    except (ValueError, AttributeError):
        detail = payload[:200].decode("utf-8", "replace")
    return json.dumps(detail)[:300] if not isinstance(detail, str) else detail[:300]


# The cache

class JudgmentCache:
    SCHEMA = """
    CREATE TABLE IF NOT EXISTS judgments(
        key TEXT PRIMARY KEY, unit_id TEXT NOT NULL, part TEXT NOT NULL, model TEXT NOT NULL, answered_by TEXT,
        answers TEXT NOT NULL, replies TEXT, input_tokens INTEGER, output_tokens INTEGER, created_at TEXT);
    CREATE INDEX IF NOT EXISTS judgments_by_unit ON judgments(unit_id, part);
    """

    def __init__(self, path):
        self.db = sqlite3.connect(str(path))
        self.db.row_factory = sqlite3.Row
        self.db.executescript(self.SCHEMA)
        self.db.commit()

    def close(self):
        self.db.close()

    def get(self, key):
        row = self.db.execute("SELECT * FROM judgments WHERE key = ?", (key,)).fetchone()
        if row is None:
            return None
        return {"answers": json.loads(row["answers"]), "model": row["answered_by"] or row["model"],
                "usage": {"input_tokens": row["input_tokens"], "output_tokens": row["output_tokens"]},
                "created_at": row["created_at"]}

    def put(self, key, request, model, response):
        usage = response.get("usage") or {}
        self.db.execute(
            "INSERT OR REPLACE INTO judgments(key, unit_id, part, model, answered_by, answers, replies, input_tokens, "
            "output_tokens, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (key, request.unit_id, request.part, model, response.get("model"), json.dumps(response["answers"]),
             json.dumps(request.replies), usage.get("input_tokens"), usage.get("output_tokens"), now_iso()))
        self.db.commit()

    def spent(self):
        row = self.db.execute("SELECT COUNT(*), COALESCE(SUM(input_tokens), 0) FROM judgments").fetchone()
        return row[0], row[1]


def cost_usd(tokens):
    return tokens * PRICE_PER_MTOK / 1_000_000


def load_key(environ, env_files):
    """TYPESAFE_API_KEY from the environment, else the first .env file that has it."""
    from .config import read_env_file
    if environ.get("TYPESAFE_API_KEY"):
        return environ["TYPESAFE_API_KEY"]
    for path in env_files:
        value = read_env_file(path).get("TYPESAFE_API_KEY")
        if value:
            return value
    return None
