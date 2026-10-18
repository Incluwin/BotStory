"""Fork mining: the forks of an upstream repository whose branches carry commits of their own, read into the store.

Each such fork branch becomes one thread under a 'forks of <upstream>' channel: the starter post says what the fork
is, how far it is ahead and behind, and which files it changes; the branch's own commits are the replies; and its diff
against the upstream default branch (from GitHub's compare API) is a stored .diff attachment. Dossiers and triage then
read a fork like a Discord release thread.

Only the GitHub REST API is used. Nothing is cloned (P2 would clone every fork whole, each with the full history),
built or run; the compare link in a starter is recorded as a skipped snapshot, so 'fetch' leaves it alone. Unchanged
forks (same pushed_at) and unchanged branches (same head) cost no API calls on later runs.
"""
import hashlib
import re
import subprocess
import time
import urllib.parse
from collections import Counter, namedtuple
from dataclasses import dataclass, field

from .api import UrllibTransport
from .store import synthetic_id
from .sync import IntakeError, write_blob

API = "https://api.github.com"
USER_AGENT = "botstory-mod-intake (+https://github.com/Incluwin/BotStory)"
UPSTREAMS = ("P0nk/Cosmic", "iw2d/kaentake")
SKIP_OWNERS = ("Incluwin",)       # BotStory's own forks: triage knows their work as the inventory
CHANNEL_TYPE = 1001
MAX_DEPTH = 3                     # forks of forks of forks
INHERITED_MIN = 3                 # a description this many forks share is an upstream one they kept
MAX_BRANCHES = 30                 # read per fork; more are noted
MAX_REPLY_COMMITS = 50            # newest own commits kept as replies
MAX_LISTED_FILES = 200            # changed files named in the starter (the .diff has every file GitHub sends)
MAX_DIFF_BYTES = 2 * 1024 * 1024
MAX_COMMIT_TEXT = 4000
SECONDARY_WAIT_CAP = 120
MAX_ATTEMPTS = 4

Upstream = namedtuple("Upstream", "full owner default heads channel_id inherited")


class GitHubError(Exception):
    pass


class NotFound(GitHubError):
    pass


class Transient(GitHubError):
    """The network or GitHub failed even after retries: worth asking again on the next run."""


class RateLimited(GitHubError):
    def __init__(self, reset_epoch):
        when = time.strftime("%H:%M", time.localtime(reset_epoch)) if reset_epoch else "later"
        super().__init__(f"GitHub API limit reached (resets at {when})")
        self.reset_epoch = reset_epoch


class Stop(Exception):
    pass


def github_token(environ, run=subprocess.run):
    """GITHUB_TOKEN or GH_TOKEN, else the GitHub CLI's login (gh auth token). Kept in memory only, never printed."""
    for key in ("GITHUB_TOKEN", "GH_TOKEN"):
        if environ.get(key):
            return environ[key]
    try:
        p = run(["gh", "auth", "token"], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    token = (p.stdout or "").strip()
    return token if p.returncode == 0 and token else None


class GitHubClient:
    def __init__(self, token, transport=None, sleep=time.sleep, api=API):
        self.token = token
        self.transport = transport or UrllibTransport()
        self.sleep = sleep
        self.api = api
        self.requests = 0

    def get(self, path, **params):
        url = self.api + path + ("?" + urllib.parse.urlencode(params) if params else "")
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": USER_AGENT}
        for attempt in range(MAX_ATTEMPTS):
            try:
                resp = self.transport.request("GET", url, headers)
            except OSError as e:
                self.requests += 1
                if attempt == MAX_ATTEMPTS - 1:
                    raise Transient(f"network error on {path}: {e}") from e
                self.sleep(2 ** attempt)
                continue
            self.requests += 1
            if resp.status in (403, 429):
                if resp.headers.get("x-ratelimit-remaining") == "0":
                    raise RateLimited(int(resp.headers.get("x-ratelimit-reset") or 0))
                if "retry-after" in resp.headers and attempt < MAX_ATTEMPTS - 1:  # secondary limit: wait, retry
                    self.sleep(min(float(resp.headers["retry-after"]), SECONDARY_WAIT_CAP))
                    continue
            if resp.status >= 500 and attempt < MAX_ATTEMPTS - 1:
                self.sleep(2 ** attempt)
                continue
            if resp.status == 404:
                raise NotFound(f"{path}: not found")
            if resp.status >= 500:
                raise Transient(f"HTTP {resp.status} on {path}: {_message(resp)}")
            if resp.status >= 400:
                raise GitHubError(f"HTTP {resp.status} on {path}: {_message(resp)}")
            return resp.json()
        raise Transient(f"{path}: gave up after {MAX_ATTEMPTS} tries")

    def pages(self, path, per_page=100, **params):
        page = 1
        while True:
            items = self.get(path, per_page=per_page, page=page, **params)
            yield from items
            if len(items) < per_page:
                return
            page += 1


@dataclass
class ForkStats:
    forks: int = 0
    skipped_owner: int = 0
    never_pushed: int = 0
    unchanged: int = 0
    compares: int = 0
    no_own_commits: int = 0
    threads: int = 0
    new_threads: int = 0
    dropped: int = 0
    requests: int = 0
    stopped: str = None
    notes: list = field(default_factory=list)


class ForkMiner:
    def __init__(self, client, store, attachments_dir, log=print, limit=None, max_depth=MAX_DEPTH,
                 skip_owners=SKIP_OWNERS):
        self.client = client
        self.store = store
        self.attachments_dir = str(attachments_dir)
        self.log = log
        self.limit = limit
        self.max_depth = max_depth
        self.skip_owners = {o.lower() for o in skip_owners}
        self.stats = ForkStats()

    def run(self, upstreams=UPSTREAMS):
        try:
            for upstream in upstreams:
                self._upstream(upstream)
        except RateLimited as e:
            self.stats.stopped = f"{e}; run 'forks' again after that to continue"
        except Stop:
            self.stats.stopped = f"stopped after {self.limit} compares (--limit); run 'forks' again to continue"
        finally:
            self.store.commit()
        if self.stats.stopped:
            self._note(self.stats.stopped)
        self.stats.requests = self.client.requests
        return self.stats

    def _upstream(self, upstream):
        up = self.client.get(f"/repos/{upstream}")
        full = up["full_name"]
        channel_id = synthetic_id(f"source:github-forks:{full.lower()}")
        self.store.save_source_channel(channel_id, CHANNEL_TYPE, f"forks of {full}",
                                       raw={"source": "github_forks", "upstream": full,
                                            "default_branch": up["default_branch"]})
        heads = {b["commit"]["sha"] for b in self.client.pages(f"/repos/{full}/branches")}
        ctx = Upstream(full, up["owner"]["login"], up["default_branch"], heads, channel_id,
                       frozenset({(up.get("description") or "").strip()}))
        self.log(f"{full}: {up.get('forks_count', 0)} forks")
        queue, seen = [(full, 1)], set()
        while queue:
            parent, depth = queue.pop(0)
            try:
                forks = list(self.client.pages(f"/repos/{parent}/forks", sort="stargazers"))
            except RateLimited:
                raise
            except GitHubError as e:
                self._note(f"forks of {parent}: {e}")
                continue
            if parent == full:  # a fork keeps the description the upstream had when it was made: old ones repeat
                counts = Counter((f.get("description") or "").strip() for f in forks)
                ctx = ctx._replace(inherited=ctx.inherited | {d for d, n in counts.items() if n >= INHERITED_MIN})
            for fork in forks:
                if fork["id"] in seen:
                    continue
                seen.add(fork["id"])
                self.stats.forks += 1
                if fork.get("forks_count") and depth < self.max_depth:
                    queue.append((fork["full_name"], depth + 1))
                try:
                    self._fork(ctx, fork)
                except RateLimited:
                    raise
                except GitHubError as e:
                    self._note(f"{fork['full_name']}: {e}")
                self.store.commit()

    def _fork(self, ctx, fork):
        name = fork["full_name"]
        if fork["owner"]["login"].lower() in self.skip_owners:
            self.stats.skipped_owner += 1
            return
        if not _pushed_after_fork(fork):
            self.stats.never_pushed += 1
            return
        pushed_at, known = self.store.fork_state(fork["id"])
        if pushed_at == fork["pushed_at"]:
            self.stats.unchanged += 1
            return
        branches = list(self.client.pages(f"/repos/{name}/branches"))
        listed_all = len(branches) <= MAX_BRANCHES
        if not listed_all:
            self._note(f"{name}: {len(branches)} branches, read the first {MAX_BRANCHES}")
            branches = branches[:MAX_BRANCHES]
        if listed_all:  # a branch deleted from the fork takes its thread with it
            for gone in set(known) - {b["name"] for b in branches}:
                self._drop_branch(fork["id"], gone, known[gone])
        finished = True
        for b in branches:
            branch, head = b["name"], b["commit"]["sha"]
            prev = known.get(branch)
            if prev is not None and prev["head_sha"] == head:
                continue
            if head in ctx.heads:
                self._drop_branch(fork["id"], branch, prev)
                self.store.save_fork_branch(fork["id"], branch, head, 0, None, None)
                self.stats.no_own_commits += 1
                continue
            if self.limit is not None and self.stats.compares >= self.limit:
                raise Stop()
            self.stats.compares += 1
            try:
                cmp = self.client.get(f"/repos/{name}/compare/{_q(ctx.owner)}:{_q(ctx.default)}...{_q(branch)}")
            except RateLimited:
                raise
            except Transient as e:  # the fork stays unfinished, so the next run asks again
                self._note(f"{name} ({branch}): compare failed, retried next run: {e}")
                finished = False
                continue
            except GitHubError as e:  # unrelated history, empty repo...: not asked again until the head moves
                self._note(f"{name} ({branch}): compare failed: {e}")
                self.store.save_fork_branch(fork["id"], branch, head, None, None, None)
                continue
            own = [c for c in cmp.get("commits") or [] if len(c.get("parents") or []) < 2]  # merges are not work
            files = cmp.get("files") or []
            if not cmp.get("ahead_by") or not (own or files):  # nothing (left) of its own, e.g. merged upstream
                self._drop_branch(fork["id"], branch, prev)
                self.store.save_fork_branch(fork["id"], branch, head, cmp.get("ahead_by", 0), cmp.get("behind_by"),
                                            None)
                self.stats.no_own_commits += 1
                continue
            tid, is_new = self._save_thread(ctx, fork, branch, head, cmp, own, files)
            self.store.save_fork_branch(fork["id"], branch, head, cmp["ahead_by"], cmp.get("behind_by"), tid)
            self.stats.threads += 1
            self.stats.new_threads += is_new
        if finished:  # only once every branch is handled
            self.store.save_fork(fork["id"], ctx.full, name, fork["pushed_at"])

    def _save_thread(self, ctx, fork, branch, head, cmp, own, files):
        name = fork["full_name"]
        owner = fork.get("owner") or {}
        tid = synthetic_id(f"github-fork:{fork['id']}:{branch}")
        is_new = self.store.thread(tid) is None
        merge_base = (cmp.get("merge_base_commit") or {}).get("sha")
        compare_url = f"https://github.com/{name}/compare/{merge_base}...{head}"
        ahead, behind = cmp.get("ahead_by", 0), cmp.get("behind_by", 0)
        description = (fork.get("description") or "").strip()
        if description in ctx.inherited:  # an upstream description the fork kept: it says nothing about the fork
            description = ""
        newest = _first_line(((own[-1].get("commit") or {}).get("message")) if own else "")
        title = f"{name} ({branch}): {description or newest or f'{ahead} commits'}"[:120]

        lines = [f"Fork {name} of {ctx.full}, branch {branch}: {ahead} commit(s) ahead of {ctx.owner}:{ctx.default}, "
                 f"{behind} behind."]
        if description:
            lines.append(description)
        lines.append(f"Changes: {compare_url}")
        capped = " (GitHub sends at most 300)" if len(files) >= 300 else ""
        listed = f", the first {MAX_LISTED_FILES} listed" if len(files) > MAX_LISTED_FILES else ""
        lines.append(f"Files changed: {len(files)}{capped}{listed}")
        lines += [f"{(f.get('status') or '?')[:1].upper()} {f['filename']}" for f in files[:MAX_LISTED_FILES]]
        if len(own) > MAX_REPLY_COMMITS:
            lines.append(f"Replies: the newest {MAX_REPLY_COMMITS} of {len(own)} own commits.")
        lines.append(f"Stars {fork.get('stargazers_count', 0)}; forked {fork['created_at'][:10]}, last push "
                     f"{fork['pushed_at'][:10]}{'; archived' if fork.get('archived') else ''}.")
        starter = {"id": str(tid), "type": 0, "author": {"id": owner.get("id"), "username": owner.get("login")},
                   "content": "\n".join(lines), "timestamp": fork["created_at"]}
        replies = []
        for i, c in enumerate(own[-MAX_REPLY_COMMITS:]):
            commit = c.get("commit") or {}
            gh_author, git_author = c.get("author") or {}, commit.get("author") or {}
            replies.append({"id": str(tid + 1 + i), "type": 0,
                            "author": {"id": gh_author.get("id"),
                                       "username": gh_author.get("login") or git_author.get("name")},
                            "content": f"{c['sha'][:12]} {commit.get('message') or ''}"[:MAX_COMMIT_TEXT],
                            "timestamp": git_author.get("date")})

        diff = unified_diff(files, compare_url)
        sha = hashlib.sha256(diff).hexdigest()
        write_blob(self.attachments_dir, sha, diff)
        attachment = {"id": synthetic_id(f"github-diff:{fork['id']}:{branch}:{head}"), "message_id": tid,
                      "filename": _safe_name(f"{name.replace('/', '-')}-{branch}") + ".diff",
                      "content_type": "text/x-diff", "size": len(diff), "sha256": sha}
        self.store.replace_conversation(tid, [starter] + replies, [attachment])
        self.store.save_thread({"id": tid, "parent_id": ctx.channel_id, "name": title, "owner_id": owner.get("id"),
                                "created_at": fork["created_at"], "message_count": 1 + len(replies),
                                "last_message_id": tid + len(replies),
                                "thread_metadata": {"archived": bool(fork.get("archived")), "locked": False},
                                "source": "github_fork", "fork": name, "branch": branch, "head": head,
                                "html_url": fork.get("html_url")})
        self.store.save_source(tid, compare_url, "github_compare", "skipped", method="skip",
                               remote=f"https://github.com/{name}.git", ref=f"{merge_base}...{head}",
                               pinned_sha=head, base_sha=merge_base,
                               note="the fork's diff is this thread's .diff attachment (GitHub compare API); clone "
                                    "the fork by hand to port it")
        return tid, is_new

    def _drop_branch(self, repo_id, branch, prev):
        """Forget a branch that is gone or has no work of its own any more, and the thread it had."""
        if prev is None:
            return
        if prev["thread_id"]:
            self.store.delete_conversation(prev["thread_id"])
            self.stats.dropped += 1
        self.store.forget_fork_branch(repo_id, branch)

    def _note(self, text):
        self.stats.notes.append(text)
        self.log("Note: " + text)


def unified_diff(files, header):
    """The compare API's per-file patches as one unified diff, so path and code evidence read it like a .patch."""
    out = [f"# {header}\n"]
    for f in files:
        new = f["filename"]
        old = f.get("previous_filename") or new
        status = f.get("status")
        out.append(f"diff --git a/{old} b/{new}\n"
                   f"--- {'/dev/null' if status == 'added' else 'a/' + old}\n"
                   f"+++ {'/dev/null' if status == 'removed' else 'b/' + new}\n")
        patch = f.get("patch")
        out.append(patch + "\n" if patch else
                   f"# no patch from GitHub ({status}, +{f.get('additions', 0)} -{f.get('deletions', 0)}): binary or "
                   f"too large\n")
    data = "".join(out).encode("utf-8")
    if len(data) > MAX_DIFF_BYTES:
        data = data[:MAX_DIFF_BYTES] + b"\n# cut at 2 MB\n"
    return data


def run_forks(store, attachments_dir, environ, upstreams=UPSTREAMS, transport=None, sleep=time.sleep, log=print,
              limit=None, max_depth=MAX_DEPTH, token_lookup=github_token):
    token = token_lookup(environ)
    if not token:
        raise IntakeError("fork mining needs GitHub access: log in with the GitHub CLI (gh auth login) or set "
                          "GITHUB_TOKEN (without a login GitHub allows 60 requests an hour)")
    client = GitHubClient(token, transport, sleep)
    return ForkMiner(client, store, attachments_dir, log, limit, max_depth).run(upstreams)


def _pushed_after_fork(fork):
    """A fork that was never pushed to after it was made has no commits of its own (ISO times compare as text)."""
    return bool(fork.get("pushed_at") and fork.get("created_at") and fork["pushed_at"] > fork["created_at"])


def _first_line(text):
    for line in (text or "").splitlines():
        if line.strip():
            return line.strip()[:100]
    return ""


def _q(value):
    return urllib.parse.quote(value, safe="/")


def _safe_name(value):
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value)[:120]


def _message(resp):
    try:
        return resp.json().get("message", "")
    except ValueError:
        return resp.body[:200].decode("utf-8", "replace")
