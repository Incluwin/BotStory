"""P2 fetch against local bare repos (tests/git_fixture.py) and a fake web. No network.

    python -m unittest discover -s tools/mod-intake/tests
"""
import contextlib
import hashlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import mod_intake  # noqa: E402
from fake_discord import TOKEN, FakeDiscord  # noqa: E402
from git_fixture import FakeWeb, build_bosshp, build_gist, git, have_git, isolated_env, text  # noqa: E402
from intake import fetch as fetchlib  # noqa: E402
from intake.api import DiscordClient  # noqa: E402
from intake.fetch import paste_raw_url, plan, run_fetch  # noqa: E402
from intake.store import Store  # noqa: E402
from intake.sync import run_sync  # noqa: E402

REPO = "https://github.com/hookwright/v83-bosshp"
RAW = "https://raw.githubusercontent.com/hookwright/v83-bosshp"
GIST_ID = "0a1b2c3d4e5f6a7b8c9d"
PASTE_BODY = "UPDATE characters SET meso = 0;\n"
REMOTES = {}  # built once for the module; tests only read them (a test that pushes builds its own)


def tearDownModule():
    if REMOTES:
        fetchlib._rmtree(REMOTES["root"])


@unittest.skipUnless(have_git(), "git is not on PATH")
class FetchTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not REMOTES:
            root = tempfile.mkdtemp(prefix="mod-intake-remotes-")
            env = isolated_env(root)
            bosshp, c = build_bosshp(root, env)
            gist, gist_sha = build_gist(root, env)
            REMOTES.update(root=root, env=env, bosshp=bosshp, c=c, gist=gist, gist_sha=gist_sha)
        cls.env, cls.bosshp, cls.c = REMOTES["env"], REMOTES["bosshp"], REMOTES["c"]
        cls.gist, cls.gist_sha = REMOTES["gist"], REMOTES["gist_sha"]

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mod-intake-fetch-")
        self.data = Path(self.tmp, "data")
        self.data.mkdir()
        self.store = Store(self.data / "intake.sqlite3")
        self.web = FakeWeb({"https://pastebin.com/raw/Ab12Cd34": text(PASTE_BODY)})
        self.urls = {f"{REPO}.git": self.bosshp.uri, f"https://gist.github.com/{GIST_ID}.git": self.gist.uri}
        self.sleeps = []
        self.logs = []
        self.git_calls = []  # (network, args) of the last fetch
        self.next_id = 1000

    def tearDown(self):
        self.store.close()
        fetchlib._rmtree(self.tmp)

    def remote_url(self, url):
        """Known repos to their bare copies; anything else to a folder that does not exist (a deleted repo)."""
        return self.urls.get(url.lower(), Path(self.tmp, "missing", "repo.git").as_uri())

    def post(self, content, message_id=None):
        message_id = message_id or self.next_id
        self.next_id += 1
        self.store.save_message(1, {"id": str(message_id), "content": content, "author": {"id": "1", "username": "u"}})
        self.store.commit()
        return message_id

    def fetch(self, **kwargs):
        kwargs.setdefault("delay", 0)
        calls = self.git_calls = []
        original = fetchlib.GitRunner.run

        def recording(runner, args, git_dir=None, network=False, stdin=None):
            calls.append((network, list(args)))
            return original(runner, args, git_dir, network, stdin)
        with mock.patch.object(fetchlib.GitRunner, "run", recording):
            return run_fetch(self.store, self.data, transport=self.web, remote_url=self.remote_url,
                             sleep=self.sleeps.append, log=self.logs.append, git_protocols="file", git_env=self.env,
                             **kwargs)

    def source(self, message_id, url):
        row = self.store.db.execute("SELECT * FROM sources WHERE message_id = ? AND url = ?",
                                    (message_id, url)).fetchone()
        self.assertIsNotNone(row, f"no sources row for {url}")
        return row

    def mirror_git(self, *args):
        return git("--git-dir", str(self.data / "sources/github/hookwright/v83-bosshp/.git"), *args, env=self.env)

    def has_object(self, spec):
        env = dict(self.env, GIT_NO_LAZY_FETCH="1")
        git_dir = str(self.data / "sources/github/hookwright/v83-bosshp/.git")
        return subprocess.run(["git", "--git-dir", git_dir, "cat-file", "-e", spec], env=env,
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode == 0


class PinningTest(FetchTestBase):
    """One fetch over links of every kind; each test reads its part of the result."""
    shared = None
    SHARED = ("tmp", "data", "store", "web", "urls", "sleeps", "logs", "git_calls", "next_id", "m", "stats")

    def setUp(self):
        if PinningTest.shared is None:
            super().setUp()
            self.post_and_fetch()
            PinningTest.shared = {name: getattr(self, name) for name in self.SHARED}
        for name, value in PinningTest.shared.items():
            setattr(self, name, value)

    def tearDown(self):
        pass  # tearDownClass

    @classmethod
    def tearDownClass(cls):
        if cls.shared:
            cls.shared["store"].close()
            fetchlib._rmtree(cls.shared["tmp"])
            cls.shared = None

    def post_and_fetch(self):
        c = self.c
        self.m = {
            "repo": self.post(f"Source: {REPO}"),
            "commit": self.post(f"Fixed in {REPO}/commit/{c['c1']}, and {REPO}/pull/7 plus "
                                f"{REPO}/pull/7/commits/{c['c4']}"),
            "paths": self.post(f"{REPO}/blob/feature/x/src/BossHp.cpp#L1 {REPO}/tree/main/src "
                               f"{RAW}/refs/heads/main/README.md"),
            "release": self.post(f"{REPO}/releases/tag/v1.0 {REPO}/releases/download/v1.0/BossHP.dll "
                                 f"{REPO}/compare/v1.0...main"),
            "dangling": self.post(f"{REPO}/commit/{c['c5']}"),
            "missing": self.post(f"{REPO}/commit/deadbeefdeadbeef"),
            "other": self.post(f"gist https://gist.github.com/packetsmith/{GIST_ID} "
                               f"paste https://pastebin.com/Ab12Cd34 mirror https://mega.nz/file/abcd#key "
                               f"see https://discord.com/channels/1/1/1000 "
                               f"https://forum.ragezone.com/threads/1 {REPO}/issues/4"),
            "deleted": self.post("https://github.com/ghost/deleted-repo"),
        }
        self.stats = self.fetch()

    def pin(self, key, url):
        row = self.source(self.m[key], url)
        return row["status"], row["pinned_sha"], row["base_sha"]

    def test_github_kinds_pin_the_commit_the_link_names(self):
        c = self.c
        self.assertEqual(self.pin("repo", REPO), ("stored", c["c2"], None))
        self.assertEqual(self.source(self.m["repo"], REPO)["ref"], "main")
        self.assertEqual(self.pin("commit", f"{REPO}/commit/{c['c1']}"), ("stored", c["c1"], c["c0"]))
        self.assertEqual(self.pin("commit", f"{REPO}/pull/7"), ("stored", c["c4"], c["c1"]))
        self.assertEqual(self.source(self.m["commit"], f"{REPO}/pull/7")["ref"], "pull/7")
        self.assertEqual(self.pin("commit", f"{REPO}/pull/7/commits/{c['c4']}")[1], c["c4"])
        blob = self.source(self.m["paths"], f"{REPO}/blob/feature/x/src/BossHp.cpp#L1")
        self.assertEqual((blob["ref"], blob["pinned_sha"], blob["path"]), ("feature/x", c["c3"], "src/BossHp.cpp"))
        tree = self.source(self.m["paths"], f"{REPO}/tree/main/src")
        self.assertEqual((tree["ref"], tree["pinned_sha"], tree["path"]), ("main", c["c2"], "src"))
        raw = self.source(self.m["paths"], f"{RAW}/refs/heads/main/README.md")
        self.assertEqual((raw["ref"], raw["pinned_sha"], raw["path"]), ("refs/heads/main", c["c2"], "README.md"))
        self.assertEqual(self.pin("release", f"{REPO}/releases/tag/v1.0"), ("stored", c["c1"], None))
        self.assertEqual(self.pin("release", f"{REPO}/compare/v1.0...main"), ("stored", c["c2"], c["c1"]))
        self.assertEqual(self.pin("dangling", f"{REPO}/commit/{c['c5']}"), ("stored", c["c5"], c["c2"]))
        self.assertEqual(self.pin("other", f"{REPO}/issues/4"), ("stored", c["c2"], None))
        self.assertIn("issue #4", self.source(self.m["other"], f"{REPO}/issues/4")["note"])

    def test_release_asset_is_manual_with_its_tag_pinned(self):
        row = self.source(self.m["release"], f"{REPO}/releases/download/v1.0/BossHP.dll")
        self.assertEqual((row["status"], row["pinned_sha"], row["path"]), ("manual", self.c["c1"], "BossHP.dll"))
        self.assertIn("binary download, not fetched", row["note"])

    def test_gist_and_paste(self):
        gist = self.source(self.m["other"], f"https://gist.github.com/packetsmith/{GIST_ID}")
        self.assertEqual((gist["status"], gist["pinned_sha"], gist["local_path"]),
                         ("stored", self.gist_sha, f"sources/gist/{GIST_ID}"))
        paste = self.source(self.m["other"], "https://pastebin.com/Ab12Cd34")
        sha = hashlib.sha256(PASTE_BODY.encode()).hexdigest()
        self.assertEqual((paste["status"], paste["pinned_sha"], paste["remote"]),
                         ("stored", sha, "https://pastebin.com/raw/Ab12Cd34"))
        self.assertEqual(Path(self.data, paste["local_path"]).read_text("utf-8"), PASTE_BODY)
        self.assertEqual(self.web.requests, ["https://pastebin.com/raw/Ab12Cd34"])

    def test_failures_carry_their_reason(self):
        missing = self.source(self.m["missing"], f"{REPO}/commit/deadbeefdeadbeef")
        self.assertEqual(missing["status"], "failed")
        self.assertIn("not in the repository", missing["error"])
        deleted = self.source(self.m["deleted"], "https://github.com/ghost/deleted-repo")
        self.assertEqual(deleted["status"], "failed")
        self.assertIn("clone failed", deleted["error"])
        self.assertIn("does not appear to be a git repository", deleted["error"])
        self.assertFalse(Path(self.data, "sources/github/ghost/deleted-repo").exists())
        self.assertFalse(Path(self.data, "sources/github/ghost/deleted-repo.part").exists())

    def test_every_link_has_a_snapshot_or_a_reason(self):
        links = self.store.db.execute("SELECT COUNT(*) FROM links").fetchone()[0]
        rows = self.store.db.execute("SELECT * FROM sources").fetchall()
        self.assertEqual(len(rows), links)
        for row in rows:
            if row["status"] == "stored":
                self.assertTrue(row["pinned_sha"] and row["local_path"], row["url"])
            elif row["status"] == "failed":
                self.assertTrue(row["error"], row["url"])
            else:
                self.assertTrue(row["note"], row["url"])
        self.assertEqual((self.stats.stored, self.stats.failed, self.stats.manual, self.stats.skipped), (13, 2, 2, 2))

    def test_non_sources_are_noted_never_requested(self):
        mega = self.source(self.m["other"], "https://mega.nz/file/abcd#key")
        self.assertEqual(mega["status"], "manual")
        self.assertIn("download host", mega["note"])
        message = self.source(self.m["other"], "https://discord.com/channels/1/1/1000")
        self.assertEqual((message["status"], message["note"]),
                         ("skipped", "Discord message link: the message is in the store"))
        forum = self.source(self.m["other"], "https://forum.ragezone.com/threads/1")
        self.assertEqual(forum["status"], "skipped")
        self.assertFalse(any("mega" in u or "ragezone" in u for u in self.web.requests))

    def test_mirror_is_a_partial_clone_with_no_work_tree_or_hooks(self):
        mirror = self.data / "sources/github/hookwright/v83-bosshp"
        self.assertEqual([p.name for p in mirror.iterdir()], [".git"])
        self.assertFalse((mirror / ".git/hooks").exists())
        self.assertEqual(self.mirror_git("config", "remote.origin.promisor"), "true")
        self.assertEqual(self.mirror_git("config", "core.hooksPath"), str(self.data / "sources/no-hooks"))
        self.assertEqual(list((self.data / "sources/no-hooks").iterdir()), [])
        # what the links point at is local: the linked file, and both sides of each linked change
        self.assertTrue(self.has_object(f"{self.c['c3']}:src/BossHp.cpp"))
        self.assertTrue(self.has_object(f"{self.c['c4']}:src/Fix.cpp"))
        self.assertTrue(self.has_object(f"{self.c['c1']}:src/BossHp.cpp"))
        self.assertTrue(self.has_object(f"{self.c['c0']}:src/BossHp.cpp"))
        # the rest of the tree stays on the host
        self.assertFalse(self.has_object(f"{self.c['c2']}:docs/notes.txt"))

    def test_pinned_commits_are_kept_by_refs(self):
        pins = set(self.mirror_git("for-each-ref", "--format=%(objectname)", "refs/intake/pins").split())
        self.assertTrue({self.c[k] for k in ("c0", "c1", "c2", "c3", "c4", "c5")} <= pins)

    def test_one_clone_per_repository_and_no_update_right_after_it(self):
        clones = [args for network, args in self.git_calls if args[0] == "clone"]
        self.assertEqual(len(clones), 3)  # bosshp, the gist, the deleted repo
        self.assertEqual((self.stats.clones, self.stats.updates), (3, 0))
        self.assertTrue(all(network for network, args in self.git_calls if args[0] in ("clone", "fetch")))


class RefetchTest(FetchTestBase):
    def test_second_run_touches_nothing_already_stored(self):
        m = self.post(f"{REPO} {REPO}/pull/7 https://pastebin.com/Ab12Cd34 https://mega.nz/file/x")
        first = self.fetch()
        self.assertEqual((first.stored, first.failed), (3, 0))
        before = [dict(r) for r in self.store.db.execute("SELECT * FROM sources ORDER BY url")]
        requests = len(self.web.requests)
        second = self.fetch()
        self.assertEqual((second.links, second.clones, second.updates, second.git_requests), (0, 0, 0, 0))
        self.assertEqual(self.git_calls, [])
        self.assertEqual(len(self.web.requests), requests)
        self.assertEqual([dict(r) for r in self.store.db.execute("SELECT * FROM sources ORDER BY url")], before)
        self.assertEqual(self.source(m, REPO)["attempts"], 1)

    def test_failed_links_are_retried_three_times_then_left(self):
        m = self.post("https://github.com/ghost/deleted-repo")
        for _ in range(3):
            self.assertEqual(self.fetch().failed, 1)
        self.assertEqual(self.source(m, "https://github.com/ghost/deleted-repo")["attempts"], 3)
        self.assertEqual(self.fetch().links, 0)
        self.assertEqual(self.fetch(retry_failed=True).failed, 1)

    def test_later_run_updates_the_mirror_and_pins_the_new_head(self):
        remote, c = build_bosshp(Path(self.tmp), self.env)
        self.urls[f"{REPO}.git"] = remote.uri
        old = self.post(f"v1: {REPO}")
        self.fetch()
        c6 = remote.commit({"src/New.cpp": "int v2;\n"}, "c6")
        remote.push("main")
        new = self.post(f"v2 is out: {REPO}")
        stats = self.fetch()
        self.assertEqual((stats.links, stats.clones, stats.updates), (1, 0, 1))
        self.assertEqual(self.source(old, REPO)["pinned_sha"], c["c2"])
        self.assertEqual(self.source(new, REPO)["pinned_sha"], c6)

    def test_local_commit_needs_no_update(self):
        self.post(f"{REPO}/commit/{self.c['c1']}")
        self.fetch()
        self.post(f"again {REPO}/commit/{self.c['c2']}")
        stats = self.fetch()
        # one request, for the changed file's contents; no update: the commit was already local
        self.assertEqual((stats.stored, stats.clones, stats.updates, stats.git_requests), (1, 0, 0, 1))


class SafetyTest(FetchTestBase):
    def test_download_hosts_are_never_requested(self):
        self.post("https://mega.nz/file/abc#k https://www.mediafire.com/file/x/y.rar https://drive.google.com/file/d/1")
        with mock.patch.object(fetchlib, "GitRunner", side_effect=AssertionError("git started")):
            stats = self.fetch()
        self.assertEqual((stats.manual, self.web.requests), (3, []))

    def test_hooks_never_run(self):
        hooks = Path(self.tmp, "hooks")
        hooks.mkdir()
        marker = Path(self.tmp, "hook-ran")
        hook = hooks / "reference-transaction"
        hook.write_bytes(f"#!/bin/sh\necho ran >> '{marker.as_posix()}'\n".encode("utf-8"))
        hook.chmod(0o755)
        config = Path(self.tmp, "hooky.gitconfig")
        config.write_text(f"[core]\n\thooksPath = {hooks.as_posix()}\n", "utf-8")
        env = dict(self.env, GIT_CONFIG_GLOBAL=str(config))
        scratch = Path(self.tmp, "scratch")
        git("init", "--quiet", str(scratch), env=self.env)
        git("-C", str(scratch), "commit", "--quiet", "--allow-empty", "-m", "x", env=env)
        if not marker.exists():
            self.skipTest("this git does not run hooks from core.hooksPath here")
        marker.unlink()
        self.env, saved = env, self.env
        try:
            self.post(f"{REPO}/pull/7 {REPO}/commit/{self.c['c5']}")
            stats = self.fetch()
        finally:
            self.env = saved
        self.assertEqual(stats.stored, 2)
        self.assertFalse(marker.exists(), "a hook ran during fetch")

    def test_https_is_the_only_transport_in_real_use(self):
        runner = fetchlib.GitRunner(Path(self.tmp, "sources", "no-hooks"))
        self.assertEqual(runner.network_env["GIT_ALLOW_PROTOCOL"], "https")
        self.assertEqual(runner.local_env["GIT_NO_LAZY_FETCH"], "1")
        self.assertIn("credential.helper=", runner.config)
        self.assertEqual(runner.network_env["GIT_TERMINAL_PROMPT"], "0")

    def test_dry_run_touches_nothing(self):
        self.post(f"{REPO} https://pastebin.com/Ab12Cd34")
        stats = self.fetch(dry_run=True)
        self.assertTrue(stats.dry_run)
        self.assertEqual(self.store.db.execute("SELECT COUNT(*) FROM sources").fetchone()[0], 0)
        self.assertEqual(self.web.requests, [])
        self.assertFalse(Path(self.data, "sources/github").exists())
        self.assertIn("  clone github/hookwright/v83-bosshp", self.logs)


class RateLimitTest(FetchTestBase):
    def test_paste_429_waits_then_stores(self):
        self.web.routes["https://pastebin.com/raw/Ab12Cd34"] = [text("slow down", 429, **{"Retry-After": "7"}),
                                                               text(PASTE_BODY)]
        m = self.post("https://pastebin.com/Ab12Cd34")
        stats = self.fetch()
        self.assertEqual(stats.stored, 1)
        self.assertEqual(self.sleeps, [7.0])
        self.assertEqual(self.source(m, "https://pastebin.com/Ab12Cd34")["status"], "stored")

    def test_paste_host_that_keeps_limiting_is_left_for_the_next_run(self):
        self.web.routes["https://pastebin.com/raw/Ab12Cd34"] = [text("no", 403, **{"X-RateLimit-Remaining": "0"})]
        self.web.routes["https://pastebin.com/raw/Zz99Yy88"] = [text("fine")]
        self.post("https://pastebin.com/Ab12Cd34 https://pastebin.com/Zz99Yy88")
        stats = self.fetch()
        self.assertEqual((stats.deferred, stats.stored), (2, 0))
        self.assertEqual(len(self.web.requests), fetchlib.HTTP_RETRIES + 1)  # the second paste is not even asked
        self.assertEqual(self.store.db.execute("SELECT COUNT(*) FROM sources").fetchone()[0], 0)

    def test_paste_failures(self):
        self.web.routes["https://pastebin.com/raw/Gone0001"] = text("<html>removed</html>", content_type="text/html")
        self.web.routes["https://pastebin.com/raw/Moved001"] = text("", 302, Location="https://evil.example/x")
        m = self.post("https://pastebin.com/Gone0001 https://pastebin.com/Nope0001 https://pastebin.com/Moved001")
        self.assertEqual(self.fetch().failed, 3)
        self.assertIn("HTML page", self.source(m, "https://pastebin.com/Gone0001")["error"])
        self.assertEqual(self.source(m, "https://pastebin.com/Nope0001")["error"], "HTTP 404")
        self.assertIn("another host (evil.example)", self.source(m, "https://pastebin.com/Moved001")["error"])
        self.assertNotIn("https://evil.example/x", self.web.requests)

    def test_git_rate_limit_backs_off_then_defers_the_host(self):
        class LimitedRunner(fetchlib.GitRunner):
            def run(self, args, git_dir=None, network=False, stdin=None):
                if not network:
                    return super().run(args, git_dir, network, stdin)
                self.calls.append(("network", list(args)))
                return fetchlib.GitResult(128, "", "fatal: unable to access 'https://github.com/a/b.git/': "
                                                   "The requested URL returned error: 429")
        self.post(f"{REPO} https://github.com/other/repo")
        with mock.patch.object(fetchlib, "GitRunner", LimitedRunner):
            stats = self.fetch()
        self.assertEqual(self.sleeps, list(fetchlib.RATE_LIMIT_WAITS))
        self.assertEqual((stats.deferred, stats.failed, stats.git_requests), (2, 0, len(fetchlib.RATE_LIMIT_WAITS) + 1))
        self.assertEqual(self.store.db.execute("SELECT COUNT(*) FROM sources").fetchone()[0], 0)


class AttachmentLinkTest(FetchTestBase):
    def test_discord_file_link_resolves_to_the_stored_attachment(self):
        self.store.save_message(1, {"id": "500", "content": "dll", "author": {"id": "1"},
                                    "attachments": [{"id": "501", "filename": "a.dll", "url": "https://cdn/x"}]})
        self.store.set_attachment(501, "stored", sha256="ab" * 32)
        m = self.post("https://cdn.discordapp.com/attachments/1/501/a.dll?ex=1 "
                      "https://cdn.discordapp.com/attachments/1/999/b.dll")
        self.fetch()
        stored = self.source(m, "https://cdn.discordapp.com/attachments/1/501/a.dll?ex=1")
        self.assertEqual((stored["status"], stored["pinned_sha"], stored["local_path"]),
                         ("stored", "ab" * 32, f"attachments/ab/{'ab' * 32}"))
        self.assertEqual(self.source(m, "https://cdn.discordapp.com/attachments/1/999/b.dll")["status"], "manual")


class AfterSyncTest(FetchTestBase):
    def test_every_synced_link_ends_with_a_snapshot_or_a_reason(self):
        fake = FakeDiscord()
        run_sync(DiscordClient(TOKEN, transport=fake, sleep=lambda s: None), self.store, fake.guild_id,
                 str(self.data / "attachments"), log=lambda s: None)
        stats = self.fetch()
        unresolved = self.store.db.execute(
            "SELECT l.url FROM links l LEFT JOIN sources s ON s.message_id = l.message_id AND s.url = l.url "
            "WHERE s.status IS NULL OR (s.status = 'failed' AND s.error IS NULL) "
            "OR (s.status IN ('manual', 'skipped') AND s.note IS NULL)").fetchall()
        self.assertEqual([r["url"] for r in unresolved], [])
        self.assertEqual(stats.links, self.store.db.execute("SELECT COUNT(*) FROM links").fetchone()[0])
        # the fixture's hookwright/v83-bosshp and packetsmith gist are the local remotes; its commit link is not
        repo = self.store.db.execute("SELECT * FROM sources WHERE url = 'https://github.com/hookwright/v83-bosshp'")
        self.assertEqual(repo.fetchone()["pinned_sha"], self.c["c2"])
        self.assertEqual(self.store.db.execute("SELECT status FROM sources WHERE url LIKE '%/commit/3f2a9c1d%'")
                         .fetchone()[0], "failed")
        self.assertEqual(self.store.db.execute("SELECT status FROM sources WHERE kind = 'gist'").fetchone()[0],
                         "stored")
        code = self.store.db.execute("SELECT language, guessed FROM code_blocks ORDER BY message_id").fetchall()
        self.assertEqual([tuple(r) for r in code], [("cpp", 0), ("cpp", 0)])


class PlanTest(unittest.TestCase):
    def link(self, url):
        from intake.links import classify
        link = classify(url)
        return {"message_id": 1, "url": url, "kind": link.kind, "host": link.host, "owner": link.owner,
                "repo": link.repo, "ref": link.ref, "path": link.path}

    def test_git_reasons(self):
        reason = fetchlib.git_reason
        login = "repository not found, private or deleted (the host asked for a login)"
        self.assertEqual(reason("fatal: unable to get password from user"), login)  # Git for Windows, real GitHub
        self.assertEqual(reason("fatal: could not read Username for 'https://github.com': terminal prompts "
                                "disabled"), login)
        self.assertEqual(reason("remote: Repository unavailable due to DMCA takedown.\nfatal: unable to access"),
                         "repository disabled by a DMCA takedown")
        self.assertEqual(reason("warning: x\nfatal: couldn't find remote ref refs/pull/9/head\n"),
                         "fatal: couldn't find remote ref refs/pull/9/head")

    def test_paste_raw_forms(self):
        self.assertEqual(paste_raw_url("https://pastebin.com/Ab12Cd34", "pastebin.com"),
                         "https://pastebin.com/raw/Ab12Cd34")
        self.assertEqual(paste_raw_url("https://pastebin.com/raw/Ab12Cd34", "pastebin.com"),
                         "https://pastebin.com/raw/Ab12Cd34")
        self.assertEqual(paste_raw_url("https://pastebin.com/raw.php?i=Ab12Cd34", "pastebin.com"),
                         "https://pastebin.com/raw/Ab12Cd34")
        self.assertEqual(paste_raw_url("https://hastebin.com/abcdef.java", "hastebin.com"),
                         "https://hastebin.com/raw/abcdef")
        self.assertEqual(paste_raw_url("https://paste.ee/p/XyZ12", "paste.ee"), "https://paste.ee/r/XyZ12")
        self.assertIsNone(paste_raw_url("https://pastebin.com/", "pastebin.com"))

    def test_gist_forms(self):
        t = plan(self.link(f"https://gist.github.com/u/{GIST_ID}/{'f' * 40}"))
        self.assertEqual((t.method, t.mirror, t.ref), ("git", f"gist/{GIST_ID}", "f" * 40))
        t = plan(self.link(f"https://gist.githubusercontent.com/u/{GIST_ID}/raw/{'e' * 40}/fix.cpp"))
        self.assertEqual((t.method, t.remote, t.ref), ("git", f"https://gist.github.com/{GIST_ID}.git", "e" * 40))
        self.assertEqual(plan(self.link("https://gist.github.com/someuser")).method, "skip")

    def test_github_forms(self):
        t = plan(self.link("https://github.com/O/R/compare/main...O:fix.patch"))
        self.assertEqual((t.mirror, t.compare), ("github/o/r", ("main", "fix")))
        self.assertEqual(plan(self.link("https://github.com/O/R/compare/main...fork:fix")).method, "invalid")
        t = plan(self.link("https://github.com/o/r/releases/tag/release/1.0"))
        self.assertEqual((t.tag, t.asset), ("release/1.0", None))
        t = plan(self.link("https://github.com/o/r/commit/abc1234.patch"))
        self.assertEqual((t.ref, t.fixed), ("abc1234", ("abc1234",)))
        self.assertEqual(plan(self.link("https://github.com/o/r/commit/zzz")).method, "invalid")
        self.assertEqual(plan(self.link("https://github.com/o/%2e%2e")).method, "invalid")
        self.assertEqual(plan(self.link("https://github.com/o/r/blob/--upload-pack=x/a")).method, "invalid")


class CliFetchTest(FetchTestBase):
    def run_cli(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = mod_intake.main(["--data", str(self.data), *args], transport=self.web, sleep=self.sleeps.append,
                                   remote_url=self.remote_url, git_protocols="file", git_env=self.env)
        return code, out.getvalue()

    def test_fetch_status_sources_thread(self):
        self.store.save_thread({"id": "1", "parent_id": "2", "name": "Boss HP fix"})
        self.post(f"Fix: {REPO}/commit/{self.c['c1']}\n```cpp\nint hp = 2;\n```\nhttps://mega.nz/file/x "
                  f"https://github.com/ghost/deleted-repo")
        self.store.close()
        code, out = self.run_cli("fetch", "--delay", "0")
        self.assertEqual(code, 0, out)
        self.assertIn("Fetched 3 links: 1 stored, 1 failed, 1 manual, 0 skipped; 2 clones", out)
        self.assertIn("  failed  https://github.com/ghost/deleted-repo", out)
        code, out = self.run_cli("status")
        self.assertIn("Sources: 1 failed, 1 manual, 1 stored", out)
        self.assertIn("Code blocks: 1 in 1 messages", out)
        self.assertIn("Last fetch: ", out)
        code, out = self.run_cli("sources", "--status", "manual")
        self.assertIn("https://mega.nz/file/x", out)
        self.assertNotIn("deleted-repo", out)
        code, out = self.run_cli("thread", "Boss HP")
        self.assertIn(f"[github_commit] {REPO}/commit/{self.c['c1']}  -> {self.c['c1'][:12]}", out)
        self.assertIn("[download] https://mega.nz/file/x  -> manual: mega.nz is a download host", out)
        self.store = Store(self.data / "intake.sqlite3")


if __name__ == "__main__":
    unittest.main()
