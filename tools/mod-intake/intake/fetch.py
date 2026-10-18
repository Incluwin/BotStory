"""P2: snapshot what each release link points at, so triage and porting work from pinned local copies.

    GitHub, GitLab, Bitbucket, Codeberg repos   partial clones (--filter=blob:none, never checked out),
                                                one per repo: sources/github/<owner>/<repo>, sources/git/<host>/...
    gists                                       full clones (they are small): sources/gist/<id>
    pastes                                      the raw text by sha256, no extension: sources/files/ab/abcd...
    Discord file links                          the attachment sync already stored, if it did
    download hosts (mega, mediafire, drive)     recorded as 'manual', never requested

Every links row gets one sources row: 'stored' with the commit it pins (the sha256 for text), 'failed' with the
reason (retried on later runs), 'manual' or 'skipped' with a note. Nothing fetched is built, run or imported: clones
have no work tree and run no hooks, git may only speak https, and local git commands never fetch on demand.
"""
import hashlib
import http.client
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, namedtuple
from dataclasses import dataclass, field
from pathlib import Path

from .api import Response
from .store import now_iso
from .sync import IntakeError, write_blob

USER_AGENT = "botstory-mod-intake/2 (+https://github.com/Incluwin/BotStory)"
MAX_SOURCE_ATTEMPTS = 3
MAX_TEXT_BYTES = 5 * 1024 * 1024
MAX_DIFF_FILES = 300            # bigger changes keep their file contents on the host until something reads them
RATE_LIMIT_WAITS = (60, 180, 600)
HTTP_RETRIES = 3
LOCAL_TIMEOUT = 120

GITHUB_KINDS = {"github_repo", "github_commit", "github_pull", "github_blob", "github_tree", "github_compare",
                "github_release", "github_raw", "github_issue"}
NAME_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
REF_RE = re.compile(r"^(?!-)(?!.*\.\.)[A-Za-z0-9_./+-]+$")
HEX_RE = re.compile(r"^[0-9a-fA-F]{7,40}$")
FULL_SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")
GIST_ID_RE = re.compile(r"^(?:[0-9a-fA-F]{20,32}|\d+)$")
PASTE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{2,64}$")
RATE_LIMIT_RE = re.compile(r"returned error: 429|429 Too Many Requests|rate limit", re.IGNORECASE)
# GitHub answers 401 for a repo that is missing, private or deleted; with prompts off git says one of these
LOGIN_ASKED = ("terminal prompts disabled", "could not read Username", "could not read Password",
               "unable to get password", "Authentication failed")

PASTE_RAW = {
    "pastebin.com": "https://pastebin.com/raw/{id}",
    "hastebin.com": "https://hastebin.com/raw/{id}",
    "paste.ee": "https://paste.ee/r/{id}",
    "ghostbin.com": "https://ghostbin.com/paste/{id}/raw",
    "pastie.io": "https://pastie.io/raw/{id}",
    "rentry.co": "https://rentry.co/{id}/raw",
    "dpaste.org": "https://dpaste.org/{id}/raw",
}
PASTE_WORDS = {"raw", "r", "p", "paste", "dl", "embed", "embed_iframe", "embed_js", "print", "clone"}

# A caller's git session (a hook, an IDE) must not leak into the mirrors, and nothing may ask for a login
LEAKY_ENV = ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES",
             "GIT_NAMESPACE", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT", "GIT_ASKPASS", "SSH_ASKPASS", "GIT_SSH",
             "GIT_SSH_COMMAND", "GIT_EXTERNAL_DIFF")


class RateLimited(Exception):
    pass


@dataclass
class FetchStats:
    links: int = 0
    stored: int = 0
    failed: int = 0
    manual: int = 0
    skipped: int = 0
    deferred: int = 0
    clones: int = 0
    updates: int = 0
    git_requests: int = 0
    http_requests: int = 0
    dry_run: bool = False
    failures: list = field(default_factory=list)
    notes: list = field(default_factory=list)


@dataclass
class Target:
    """One link and how to snapshot it."""
    link: object
    method: str = "skip"      # git | http | attachment | message | manual | skip | invalid
    mirror: str = None        # git: folder under sources/, e.g. github/owner/repo
    remote: str = None        # git: the https clone URL; http: the raw text URL
    partial: bool = True
    ref: str = None           # commit, branch or tag the link names; a gist revision
    path: str = None
    pull: str = None
    tag: str = None
    asset: str = None
    compare: tuple = None
    fixed: tuple = None       # names that, once local, need no mirror update (None: wants the newest state)
    note: str = None
    done: bool = False

    @property
    def kind(self):
        return self.link["kind"]

    def invalid(self, reason):
        self.method, self.note = "invalid", reason
        return self


# Planning: what a link points at, from its links row (P1 classification) and its URL

def plan(link):
    t = Target(link)
    kind, host = link["kind"], link["host"]
    if kind in GITHUB_KINDS:
        return _plan_github(t)
    if kind == "gist" or host == "gist.githubusercontent.com":
        return _plan_gist(t)
    if kind == "git_other":
        owner, repo = link["owner"], link["repo"]
        if not (_name_ok(owner) and _name_ok(repo)):
            return t.invalid(f"not an owner/repository path on {host}")
        t.method, t.mirror = "git", f"git/{host}/{owner.lower()}/{repo.lower()}"
        t.remote = f"https://{host}/{owner}/{repo}.git"
        t.note = f"{host}: only the default branch head is pinned (a ref in the link is not read)"
        return t
    if kind == "paste":
        raw = paste_raw_url(link["url"], host)
        if not raw:
            return t.invalid(f"not a paste URL this tool knows how to read raw ({host})")
        t.method, t.remote = "http", raw
        return t
    if kind == "download":
        t.method = "manual"
        t.note = f"{host} is a download host: never fetched automatically; get the file by hand if it is worth studying"
        return t
    if kind == "discord_attachment":
        t.method = "attachment"
        return t
    if kind == "discord_message":
        t.method = "message"
        return t
    t.note = "a GitHub page that is not a repository" if kind == "github_other" else "not a code or paste host"
    return t


def _plan_github(t):
    link = t.link
    owner, repo, kind, ref, path = link["owner"], link["repo"], link["kind"], link["ref"], link["path"]
    if not (_name_ok(owner) and _name_ok(repo)):
        return t.invalid(f"not a GitHub owner/repository: {owner}/{repo}")
    t.method, t.mirror = "git", f"github/{owner.lower()}/{repo.lower()}"
    t.remote = f"https://github.com/{owner}/{repo}.git"
    if kind == "github_commit":
        t.ref = _strip_suffix(ref, (".patch", ".diff"))
        if not HEX_RE.match(t.ref or ""):
            return t.invalid(f"not a commit id: {ref}")
        t.fixed = (t.ref,)
    elif kind == "github_pull":
        if not (path or "").isdigit():
            return t.invalid(f"not a pull request number: {path}")
        t.pull, t.fixed = path, ()  # the pull request has its own fetch
        t.ref = ref if ref and HEX_RE.match(ref) else None
    elif kind in ("github_blob", "github_tree", "github_raw"):
        if ref and not REF_RE.match(ref):
            return t.invalid(f"not a branch, tag or commit name: {ref}")
        t.ref, t.path = ref, path
        t.fixed = (ref,) if ref and HEX_RE.match(ref) else None
    elif kind == "github_compare":
        spec = _strip_suffix(ref, (".patch", ".diff")) or ""
        base, sep, head = spec.partition("...")
        if not sep:
            base, sep, head = spec.partition("..")
        if not sep:
            base, head = None, spec
        sides = []
        for side in (base, head):
            if side and ":" in side:  # owner:branch or owner:repo:branch
                fork = side.split(":")
                if fork[0].lower() != owner.lower() or len(fork) > 2:
                    return t.invalid(f"compare against another fork ({side}): link or clone that fork by hand")
                side = fork[-1]
            if side is not None and not REF_RE.match(side):
                return t.invalid(f"not a branch, tag or commit name: {side}")
            sides.append(side)
        t.compare = tuple(sides)
        t.fixed = t.compare if all(s and HEX_RE.match(s) for s in t.compare) else None
    elif kind == "github_release":
        rest = _path_parts(link["url"])[3:]
        if rest[:1] == ["tag"] and len(rest) > 1:
            t.tag = "/".join(rest[1:])
        elif rest[:1] == ["download"] and len(rest) > 2:
            t.tag, t.asset = "/".join(rest[1:-1]), rest[-1]
        elif rest[:2] == ["latest", "download"] and len(rest) > 2:
            t.asset = rest[-1]
        if t.tag and not REF_RE.match(t.tag):
            return t.invalid(f"not a tag name: {t.tag}")
        t.fixed = ("refs/tags/" + t.tag,) if t.tag else None
    elif kind == "github_issue":
        t.note = f"issue #{path}: the issue page itself is not fetched (that needs the GitHub API)"
    elif path:
        t.note = f"links /{path}; pinned the default branch head"
    return t


def _plan_gist(t):
    url = t.link["url"]
    parts = _path_parts(url)
    rev = None
    if t.link["host"] == "gist.githubusercontent.com":  # <user>/<id>/raw/<rev>/<file>
        gid = parts[1] if len(parts) > 1 else None
        if len(parts) > 4 and parts[2] == "raw" and HEX_RE.match(parts[3]):
            rev = parts[3]
    elif len(parts) == 1:
        gid = parts[0]
    else:
        gid, rest = (parts[1] if len(parts) > 1 else None), parts[2:]
        if rest[:1] == ["raw"]:
            rest = rest[1:]
        if rest and HEX_RE.match(rest[0]):
            rev = rest[0]
    gid = _strip_suffix(gid, (".git", ".js"))
    if not gid or not GIST_ID_RE.match(gid):
        t.note = "a gist page that names no gist (a user's gist list?)"
        return t
    t.method, t.mirror, t.remote, t.partial = "git", f"gist/{gid.lower()}", f"https://gist.github.com/{gid}.git", False
    t.ref = rev
    t.fixed = (rev,) if rev else None
    return t


def paste_raw_url(url, host):
    """The raw-text form of a paste link (pastebin.com/abc -> pastebin.com/raw/abc), or None."""
    template = PASTE_RAW.get(host)
    if not template:
        return None
    parsed = urllib.parse.urlparse(url)
    parts = _path_parts(url)
    if parts[:1] == ["raw.php"]:  # old pastebin form: raw.php?i=<id>
        parts = urllib.parse.parse_qs(parsed.query).get("i", [])
    ids = [p for p in parts if p.lower() not in PASTE_WORDS]
    if not ids:
        return None
    paste_id = ids[0].split(".")[0]  # hastebin adds the language: abc.java
    return template.format(id=paste_id) if PASTE_ID_RE.match(paste_id) else None


def _name_ok(name):
    return bool(name and NAME_RE.match(name) and name not in (".", ".."))


def _strip_suffix(value, suffixes):
    for suffix in suffixes:
        if value and value.endswith(suffix):
            return value[:-len(suffix)]
    return value


def _path_parts(url):
    return [urllib.parse.unquote(p) for p in urllib.parse.urlparse(url).path.split("/") if p]


# Git

GitResult = namedtuple("GitResult", "code out err")


class GitRunner:
    """git with hooks, credential helpers and prompts off, only the allowed transports, no on-demand fetches for
    local commands. `calls` records (kind, args) for each command."""

    def __init__(self, hooks_dir, protocols="https", env=None, timeout=1800):
        self.git = shutil.which("git")
        if not self.git:
            raise IntakeError("git is not on PATH: 'fetch' needs the git command line")
        hooks = str(hooks_dir)
        self.config = []
        for setting in (f"core.hooksPath={hooks}", "credential.helper=", "core.askPass=",
                        "credential.interactive=never", "core.fsmonitor=false", "gc.auto=0", "maintenance.auto=false",
                        "fetch.recurseSubmodules=false", "submodule.recurse=false", "protocol.ext.allow=never"):
            self.config += ["-c", setting]
        base = dict(os.environ if env is None else env)
        for key in LEAKY_ENV:
            base.pop(key, None)
        base.update(GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never", GIT_ALLOW_PROTOCOL=protocols,
                    GIT_LFS_SKIP_SMUDGE="1")
        self.network_env = base
        self.local_env = dict(base, GIT_NO_LAZY_FETCH="1", GIT_LITERAL_PATHSPECS="1")  # a path from a URL is no glob
        self.timeout = timeout
        self.cwd = str(Path(hooks_dir).parent)  # sources/: holds no fetched files itself
        self.calls = []

    def run(self, args, git_dir=None, network=False, stdin=None):
        argv = [self.git, *self.config, *([f"--git-dir={git_dir}"] if git_dir else []), *args]
        self.calls.append(("network" if network else "local", list(args)))
        timeout = self.timeout if network else LOCAL_TIMEOUT
        try:
            p = subprocess.run(argv, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=self.cwd,
                               env=self.network_env if network else self.local_env, timeout=timeout)
        except subprocess.TimeoutExpired:
            return GitResult(-1, "", f"timed out after {timeout} s")
        return GitResult(p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace"))

    def network_calls(self):
        return [args for kind, args in self.calls if kind == "network"]


class Mirror:
    """A local clone with no work tree. Every lookup here is local."""

    def __init__(self, git, sources_dir, key, remote, partial):
        self.git = git
        self.key = key
        self.path = Path(sources_dir) / key
        self.git_dir = self.path / ".git"
        self.rel = "sources/" + key
        self.remote = remote
        self.partial = partial
        self.refs = {}
        self.default_branch = None

    def exists(self):
        return self.git_dir.is_dir()

    def run(self, *args, stdin=None):
        return self.git.run(list(args), self.git_dir, stdin=stdin)

    def load_refs(self):
        self.refs.clear()
        result = self.run("for-each-ref", "--format=%(refname)%00%(objectname)%00%(*objectname)%00%(symref)")
        for line in result.out.splitlines():
            name, obj, peeled, symref = (line.split("\0") + ["", "", ""])[:4]
            self.refs[name] = peeled or obj
            if name == "refs/remotes/origin/HEAD" and symref.startswith("refs/remotes/origin/"):
                self.default_branch = symref[len("refs/remotes/origin/"):]

    def read_ref(self, name):
        result = self.run("rev-parse", "--verify", "--quiet", name)
        if result.code == 0:
            self.refs[name] = result.out.strip()
        return self.refs.get(name)

    def resolve(self, name):
        """A branch, tag, full ref or commit id -> the full commit sha, or None."""
        if not name or not REF_RE.match(name):
            return None
        if name.startswith("refs/heads/"):
            name = name[len("refs/heads/"):]
        for ref in (f"refs/remotes/origin/{name}", f"refs/tags/{name}", name):
            if ref in self.refs:
                return self.refs[ref]
        return self._commit(name) if HEX_RE.match(name) else None

    def _commit(self, expr):
        result = self.run("rev-parse", "--verify", "--quiet", expr + "^{commit}")
        return result.out.strip() if result.code == 0 else None

    def parent(self, sha):
        return self._commit(sha + "^")

    def default_head(self):
        """(branch, sha) of the remote's default branch, or (None, None) for an empty repository."""
        if "refs/remotes/origin/HEAD" in self.refs:
            return self.default_branch, self.refs["refs/remotes/origin/HEAD"]
        branches = sorted(r[len("refs/remotes/origin/"):] for r in self.refs if r.startswith("refs/remotes/origin/"))
        for branch in ("main", "master", *branches):
            if f"refs/remotes/origin/{branch}" in self.refs:
                return branch, self.refs[f"refs/remotes/origin/{branch}"]
        return None, None

    def resolve_ref_path(self, ref, path):
        """blob/tree links put branch names with slashes and the path in one string: feature/x/src/A.java.
        The shortest leading part that names a commit is the ref; the rest is the path."""
        parts = [p for p in [ref or "", *(path or "").split("/")] if p]
        for i in range(1, len(parts) + 1):
            name = "/".join(parts[:i])
            sha = self.resolve(name)
            if sha:
                return name, sha, "/".join(parts[i:]) or None
        return None, None, None

    def entry(self, sha, path):
        """(type, object id) of path in that commit, or None. Reads trees only."""
        result = self.run("ls-tree", sha, "--", path.strip("/"))
        line = result.out.splitlines()[0] if result.code == 0 and result.out.strip() else ""
        meta = line.split("\t", 1)[0].split()
        return (meta[1], meta[2]) if len(meta) == 3 else None

    def changed_files(self, base, head):
        result = self.run("diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", base, head)
        return len(result.out.splitlines()) if result.code == 0 else 0

    def merge_base(self, a, b):
        result = self.run("merge-base", a, b)
        return result.out.strip() if result.code == 0 else None

    def has_object(self, oid):
        return self.run("cat-file", "-e", oid).code == 0

    def pin(self, shas):
        """A ref per pinned commit, so a later fetch or gc can never drop it even when the remote rewrites history."""
        lines = "".join(f"update refs/intake/pins/{s} {s}\n" for s in sorted(shas) if s)
        if lines:
            self.run("update-ref", "--stdin", stdin=lines.encode("ascii"))


def git_reason(err):
    text = err or ""
    if any(needle in text for needle in LOGIN_ASKED):
        return "repository not found, private or deleted (the host asked for a login)"
    for needle, reason in (("DMCA", "repository disabled by a DMCA takedown"),
                           ("returned error: 451", "unavailable for legal reasons (HTTP 451)"),
                           ("Repository not found", "repository not found"),
                           ("returned error: 404", "repository not found (HTTP 404)"),
                           ("returned error: 403", "access denied (HTTP 403)")):
        if needle in text:
            return reason
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    fatal = [line for line in lines if line.startswith(("fatal:", "error:"))]
    return (fatal[0] if fatal else lines[-1] if lines else "git failed")[:300]


# HTTP (pastes)

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # urllib then raises HTTPError with the 3xx; the fetcher follows same-host redirects itself


class HttpTransport:
    """GET with no automatic redirects and a capped body. Tests replace it with a fake."""

    def __init__(self, timeout=60, max_bytes=MAX_TEXT_BYTES):
        self.timeout = timeout
        self.max_bytes = max_bytes
        self.opener = urllib.request.build_opener(_NoRedirect)

    def request(self, method, url, headers):
        req = urllib.request.Request(url, method=method, headers=headers)
        try:
            try:
                with self.opener.open(req, timeout=self.timeout) as r:
                    return Response(r.status, dict(r.headers.items()), r.read(self.max_bytes + 1))
            except urllib.error.HTTPError as e:
                return Response(e.code, dict(e.headers.items()), e.read(self.max_bytes + 1))
        except http.client.HTTPException as e:  # IncompleteRead...: the connection dropped mid-body; callers retry
            raise ConnectionError(f"connection dropped: {e!r}"[:300]) from e


class Pacer:
    """Waits `delay` seconds before every request to a host after the first one in this run."""

    def __init__(self, delay, sleep):
        self.delay, self.sleep, self.seen = delay, sleep, set()

    def wait(self, host):
        if host in self.seen and self.delay > 0:
            self.sleep(self.delay)
        self.seen.add(host)


def _rate_limit_wait(resp):
    """Seconds to wait when the reply is a rate limit (429, or a 403 that says so), else None."""
    limited = resp.status == 429 or (resp.status == 403 and (resp.headers.get("x-ratelimit-remaining") == "0"
                                                             or "retry-after" in resp.headers))
    if not limited:
        return None
    try:
        return min(float(resp.headers.get("retry-after", "30")), 600.0)
    except ValueError:
        return 30.0


# The run

class Fetcher:
    def __init__(self, store, data_dir, transport=None, remote_url=None, sleep=time.sleep, log=print, delay=1.0,
                 git_protocols="https", git_env=None):
        self.store = store
        self.data_dir = Path(data_dir)
        self.sources_dir = self.data_dir / "sources"
        self.hooks_dir = self.sources_dir / "no-hooks"
        self.transport = transport or HttpTransport()
        self.remote_url = remote_url or (lambda url: url)
        self.sleep, self.log = sleep, log
        self.pacer = Pacer(delay, sleep)
        self.git_protocols, self.git_env = git_protocols, git_env
        self.git = None
        self.limited = set()
        self.text_cache = {}
        self.stats = FetchStats()

    def run(self, limit=None, retry_failed=False, dry_run=False):
        links = self.store.links_to_fetch(MAX_SOURCE_ATTEMPTS, retry_failed)
        targets = [plan(link) for link in (links if limit is None else links[:limit])]
        self.stats.links = len(targets)
        if dry_run:
            return self._dry_run(targets)
        groups = {}
        for t in targets:
            if t.method == "git":
                groups.setdefault(t.mirror, []).append(t)
                continue
            try:
                self._without_git(t)
            except RateLimited:
                self.stats.deferred += 1
            self.store.commit()
        if groups:
            self._prepare_git()
        for key, group in groups.items():
            try:
                self._mirror(key, group)
            except RateLimited as e:
                left = sum(not t.done for t in group)
                self.stats.deferred += left
                self._note(f"{e} is rate limiting: {key} ({left} link(s)) left for the next run")
            self.store.commit()
        self.store.set_meta("last_fetch", json.dumps({
            "at": now_iso(), "links": self.stats.links, "stored": self.stats.stored, "failed": self.stats.failed,
            "manual": self.stats.manual, "skipped": self.stats.skipped, "deferred": self.stats.deferred}))
        self.store.commit()
        return self.stats

    def _prepare_git(self):
        self.hooks_dir.mkdir(parents=True, exist_ok=True)
        if any(self.hooks_dir.iterdir()):
            raise IntakeError(f"{self.hooks_dir} must stay empty: git uses it as the hooks and template folder")
        self.git = GitRunner(self.hooks_dir, self.git_protocols, self.git_env)

    def _dry_run(self, targets):
        self.stats.dry_run = True
        methods = Counter(t.method for t in targets)
        mirrors = sorted({t.mirror for t in targets if t.method == "git"})
        new = [k for k in mirrors if not (self.sources_dir / k / ".git").is_dir()]
        self.log(f"Would clone {len(new)} repositories, update up to {len(mirrors) - len(new)}, read "
                 f"{methods['http']} pastes; {methods['manual']} manual, "
                 f"{methods['skip'] + methods['message'] + methods['invalid']} skipped or invalid, "
                 f"{methods['attachment']} Discord file links.")
        for key in new:
            self.log(f"  clone {key}")
        return self.stats

    def _record(self, t, status, **fields):
        link = t.link
        if t.method == "git":
            fields.setdefault("local_path", "sources/" + t.mirror)
        self.store.save_source(link["message_id"], link["url"], link["kind"], status, method=t.method,
                               remote=t.remote, **fields)
        t.done = True
        setattr(self.stats, status, getattr(self.stats, status) + 1)
        if status == "failed":
            self.stats.failures.append((link["url"], fields.get("error")))

    def _note(self, text):
        self.stats.notes.append(text)
        self.log("Note: " + text)

    # Links that need no git

    def _without_git(self, t):
        if t.method == "http":
            return self._fetch_text(t)
        if t.method == "attachment":
            return self._attachment(t)
        if t.method == "message":
            parts = (t.link["path"] or "").split("/")
            known = parts[-1].isdigit() and self.store.message_exists(int(parts[-1]))
            note = "Discord message link: " + ("the message is in the store" if known else
                                               "not in the store (an untracked channel?)")
            return self._record(t, "skipped", note=note)
        if t.method == "invalid":
            return self._record(t, "skipped", note=t.note)
        return self._record(t, "manual" if t.method == "manual" else "skipped", note=t.note)

    def _attachment(self, t):
        parts = _path_parts(t.link["url"])  # attachments/<channel>/<attachment>/<file name>
        aid = int(parts[2]) if len(parts) >= 4 and parts[0].endswith("attachments") and parts[2].isdigit() else None
        row = self.store.attachment(aid) if aid else None
        if row and row["status"] == "stored":
            sha = row["sha256"]
            return self._record(t, "stored", local_path=f"attachments/{sha[:2]}/{sha}", pinned_sha=sha,
                                note=f"attachment {row['filename']}, stored by sync")
        if row:
            return self._record(t, "failed", error=f"attachment {aid} is {row['status']}, not stored yet: run 'sync'")
        t.method = "manual"
        self._record(t, "manual", note="a Discord file link that is not an attachment sync stored (signed links "
                                       "expire): download it by hand if it matters")

    def _fetch_text(self, t):
        if t.remote not in self.text_cache:
            self.text_cache[t.remote] = self._get_text(t.remote)
        body, error = self.text_cache[t.remote]
        if error:
            return self._record(t, "failed", error=error)
        sha = hashlib.sha256(body).hexdigest()
        write_blob(str(self.sources_dir / "files"), sha, body)
        self._record(t, "stored", local_path=f"sources/files/{sha[:2]}/{sha}", pinned_sha=sha,
                     note=f"{len(body)} bytes of raw text")

    def _get_text(self, url):
        """(body, None) or (None, reason). Follows same-host redirects only; raises RateLimited when a host keeps
        refusing, so the link waits for the next run instead of using up its tries."""
        for _hop in range(4):
            host = urllib.parse.urlparse(url).hostname or ""
            if host in self.limited:
                raise RateLimited(host)
            resp = None
            for attempt in range(HTTP_RETRIES + 1):
                self.pacer.wait(host)
                try:
                    resp = self.transport.request("GET", url, {"User-Agent": USER_AGENT, "Accept": "text/plain, */*"})
                except OSError as e:
                    self.stats.http_requests += 1
                    if attempt == HTTP_RETRIES:
                        return None, f"network error: {e}"
                    self.sleep(2 ** attempt)
                    continue
                self.stats.http_requests += 1
                wait = _rate_limit_wait(resp)
                if wait is not None:
                    if attempt == HTTP_RETRIES:
                        self.limited.add(host)
                        raise RateLimited(host)
                    self.log(f"{host} is rate limiting: waiting {wait:.0f} s")
                    self.sleep(wait)
                    continue
                if resp.status >= 500 and attempt < HTTP_RETRIES:
                    self.sleep(2 ** attempt)
                    continue
                break
            if resp.status in (301, 302, 303, 307, 308):
                target = urllib.parse.urljoin(url, resp.headers.get("location", ""))
                other = urllib.parse.urlparse(target).hostname or ""
                if other != host:
                    return None, f"HTTP {resp.status} to another host ({other}): not followed"
                url = target
                continue
            if resp.status != 200:
                return None, f"HTTP {resp.status}"
            if len(resp.body) > MAX_TEXT_BYTES:
                return None, f"larger than {MAX_TEXT_BYTES // (1024 * 1024)} MB: not stored"
            if "html" in resp.headers.get("content-type", "").lower():
                return None, "an HTML page instead of raw text (removed, private, or a bot check)"
            return resp.body, None
        return None, "too many redirects"

    # Git mirrors

    def _net(self, host, args, git_dir=None):
        """A git command that talks to `host`: paced, and retried after rate-limit replies."""
        if host in self.limited:
            raise RateLimited(host)
        for wait in (*RATE_LIMIT_WAITS, None):
            self.pacer.wait(host)
            self.stats.git_requests += 1
            result = self.git.run(args, git_dir, network=True)
            if result.code == 0 or not RATE_LIMIT_RE.search(result.err):
                return result
            if wait is None:
                break
            self.log(f"{host} is rate limiting: waiting {wait} s")
            self.sleep(wait)
        self.limited.add(host)
        raise RateLimited(host)

    def _mirror(self, key, group):
        first = group[0]
        m = Mirror(self.git, self.sources_dir, key, first.remote, first.partial)
        host = urllib.parse.urlparse(first.remote).hostname
        stale = None
        if not m.exists():
            self.log(f"clone {key}")
            self.stats.clones += 1
            result = self._clone(m, host)
            if result.code != 0:
                reason = git_reason(result.err)
                for t in group:
                    self._record(t, "failed", error=f"clone failed: {reason}")
                return
            m.load_refs()
        else:
            m.load_refs()
            if any(t.fixed is None or any(m.resolve(n) is None for n in t.fixed) for t in group):
                self.log(f"update {key}")
                self.stats.updates += 1
                result = self._net(host, ["fetch", "--quiet", "--tags", "--force", "origin"], m.git_dir)
                if result.code == 0:
                    m.load_refs()
                else:
                    stale = f"update failed ({git_reason(result.err)}): pinned from the local mirror"
        pins = set()
        try:
            for t in group:
                fields = self._resolve(m, t, host)
                status = fields.pop("status", "stored")
                if stale and status != "failed":
                    fields["note"] = "; ".join(x for x in (stale, fields.get("note")) if x)
                pins.update(x for x in (fields.get("pinned_sha"), fields.get("base_sha")) if x)
                self._record(t, status, **fields)
        finally:
            m.pin(pins)

    def _clone(self, m, host):
        part = m.path.with_name(m.path.name + ".part")
        for leftover in (part, m.path):  # an interrupted run's folder (m.path has no .git, or we would not clone)
            if leftover.exists():
                _rmtree(leftover)
        part.parent.mkdir(parents=True, exist_ok=True)
        hooks = str(self.hooks_dir)
        args = ["clone", "--quiet", "--no-checkout", f"--template={hooks}", "--config", f"core.hooksPath={hooks}",
                "--config", "gc.auto=0", "--config", "maintenance.auto=false"]
        if m.partial:
            args.append("--filter=blob:none")
        result = self._net(host, [*args, "--", self.remote_url(m.remote), str(part)])
        if result.code == 0:
            _move(part, m.path)
        elif part.exists():
            _rmtree(part)
        return result

    def _resolve(self, m, t, host):
        """The fields of t's sources row. Network only for a pull request head, a commit no branch holds, and the
        file contents of what the link points at (the linked file, a commit's or PR's changed files)."""
        kind = t.kind
        if kind == "github_pull":
            ref = f"refs/intake/pull/{t.pull}"
            result = self._net(host, ["fetch", "--quiet", "--no-tags", "origin", f"+refs/pull/{t.pull}/head:{ref}"],
                               m.git_dir)
            head = m.read_ref(ref)
            if head is None:
                return _failed(f"pull request #{t.pull}: {git_reason(result.err)}")
            note = None if result.code == 0 else (f"PR fetch failed ({git_reason(result.err)}): "
                                                  f"pinned the head fetched earlier")
            pin = head
            if t.ref:
                pin = m.resolve(t.ref)
                if not pin:
                    return _failed(f"commit {t.ref} is not in pull request #{t.pull}")
            _branch, default = m.default_head()
            base = m.merge_base(pin, default) if default else None
            return self._with_diff(m, host, base, pin, ref=f"pull/{t.pull}", pinned_sha=pin, base_sha=base,
                                   note=_join(note, "base = merge-base with the default branch" if base else None))
        if kind == "github_commit":
            sha = m.resolve(t.ref)
            if not sha and FULL_SHA_RE.match(t.ref):
                self._net(host, ["fetch", "--quiet", "--no-tags", "origin", t.ref], m.git_dir)
                sha = m.resolve(t.ref)
            if not sha:
                return _failed(f"commit {t.ref} is not in the repository (force-pushed away, or only in a fork)")
            base = m.parent(sha)
            return self._with_diff(m, host, base, sha, ref=t.ref, pinned_sha=sha, base_sha=base)
        if kind == "github_compare":
            base_name, head_name = t.compare
            branch, default = m.default_head()
            base = m.resolve(base_name) if base_name else default
            head = m.resolve(head_name)
            missing = [n for n, sha in ((base_name or branch or "default branch", base), (head_name, head)) if not sha]
            if missing:
                return _failed("compare: " + ", ".join(f"{n} not found" for n in missing))
            return self._with_diff(m, host, base, head, ref=t.link["ref"], pinned_sha=head, base_sha=base)
        if kind in ("github_blob", "github_tree", "github_raw"):
            name, sha, path = m.resolve_ref_path(t.ref, t.path)
            if not sha:
                return _failed(f"branch, tag or commit {t.ref!r} not found")
            note = None
            if path:
                entry = m.entry(sha, path)
                if entry is None:
                    note = f"{path} is not in that commit"
                elif entry[0] == "blob":
                    note = self._load_blob(m, host, entry[1])
            return {"ref": name, "pinned_sha": sha, "path": path, "note": note}
        if kind == "github_release":
            if t.tag:
                sha = m.refs.get("refs/tags/" + t.tag)
                if not sha:
                    return _failed(f"tag {t.tag} not found")
                ref, note = t.tag, None
            else:
                ref, sha = m.default_head()
                note = "no tag in the link: pinned the default branch head"
            if not sha:
                return _failed("empty repository (no branches)")
            if t.asset:
                return {"status": "manual", "ref": ref, "pinned_sha": sha, "path": t.asset,
                        "note": _join(note, f"release asset {t.asset}: a binary download, not fetched "
                                            f"(get it by hand to study it)")}
            return {"ref": ref, "pinned_sha": sha, "note": note}
        if t.ref:  # a gist revision
            sha = m.resolve(t.ref)
            if not sha:
                return _failed(f"revision {t.ref} not found")
            return {"ref": t.ref, "pinned_sha": sha, "note": t.note}
        branch, sha = m.default_head()
        if not sha:
            return _failed("empty repository (no branches)")
        return {"ref": branch, "pinned_sha": sha, "note": t.note}

    def _with_diff(self, m, host, base, head, **fields):
        """Load the file contents of a change (both sides of each changed file) in one batch, so the change itself
        survives the repository being deleted; the rest of the tree stays on the host until something reads it."""
        if not base or not m.partial:
            return fields
        count = m.changed_files(base, head)
        if count > MAX_DIFF_FILES:
            fields["note"] = _join(fields.get("note"), f"{count} files changed: contents left on the host")
            return fields
        if count:
            args = ["diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--shortstat", base, head]
            result = m.run(*args)  # local first: when the contents are already here this asks the host nothing
            if result.code != 0:
                result = self._net(host, args, m.git_dir)
            summary = (result.out.strip() if result.code == 0
                       else f"change contents not loaded ({git_reason(result.err)})")
            fields["note"] = _join(fields.get("note"), summary)
        return fields

    def _load_blob(self, m, host, oid):
        if not m.partial or m.has_object(oid):
            return None
        result = self._net(host, ["cat-file", "-t", oid], m.git_dir)
        return None if result.code == 0 else f"file contents not loaded ({git_reason(result.err)})"


def run_fetch(store, data_dir, transport=None, remote_url=None, sleep=time.sleep, log=print, limit=None,
              retry_failed=False, delay=1.0, dry_run=False, git_protocols="https", git_env=None):
    """Snapshot every link that has none yet (and failed ones with tries left). remote_url maps a clone URL to the
    one to use (tests point it at local bare repos); git_protocols is GIT_ALLOW_PROTOCOL."""
    fetcher = Fetcher(store, data_dir, transport, remote_url, sleep, log, delay, git_protocols, git_env)
    return fetcher.run(limit, retry_failed, dry_run)


def _failed(error):
    return {"status": "failed", "error": error}


def _join(*parts):
    return "; ".join(p for p in parts if p) or None


def _move(src, dst):
    for attempt in range(5):  # a virus scanner or indexer can hold a fresh clone for a moment on Windows
        try:
            os.replace(src, dst)
            return
        except PermissionError:
            if attempt == 4:
                raise
            time.sleep(0.5)


def _rmtree(path):
    def make_writable(func, p, _exc):  # git writes read-only pack files
        os.chmod(p, stat.S_IWRITE)
        func(p)
    if sys.version_info >= (3, 12):
        shutil.rmtree(path, onexc=make_writable)
    else:
        shutil.rmtree(path, onerror=make_writable)
