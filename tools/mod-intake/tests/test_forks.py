"""Fork mining against the in-memory GitHub API (tests/fake_github.py).

    python -m unittest discover -s tools/mod-intake/tests
"""
import contextlib
import http.client
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import mod_intake  # noqa: E402
from fake_github import TOKEN, FakeGitHub, commit, file_change  # noqa: E402
from intake import dossier as dossierlib  # noqa: E402
from intake import forks as forklib  # noqa: E402
from intake.api import UrllibTransport  # noqa: E402
from intake.store import Store, synthetic_id  # noqa: E402
from intake.sync import IntakeError  # noqa: E402

DUEY = "src/main/java/net/server/channel/handlers/DueyHandler.java"
MONSTER = "src/main/java/server/life/Monster.java"


def scenario():
    gh = FakeGitHub()
    gh.add_repo("P0nk/Cosmic", 1, branches={"master": "u1", "release": "u2"})
    gh.add_repo("alice/Cosmic", 11, parent="P0nk/Cosmic", stars=5, description="Duey fix and boss HP",
                branches={"master": "a1", "feature/bosshp": "a2", "sync": "u1"})
    gh.add_repo("erin/Cosmic", 15, parent="P0nk/Cosmic", stars=3, branches={"master": "e1"})
    gh.add_repo("dave/Cosmic", 13, parent="P0nk/Cosmic", stars=2, branches={"master": "d1"})
    gh.add_repo("Incluwin/Cosmic", 14, parent="P0nk/Cosmic", stars=1, branches={"master": "i1"})
    gh.add_repo("bob/Cosmic", 12, parent="P0nk/Cosmic", stars=0, created="2024-02-01T00:00:00Z",
                pushed="2024-01-15T00:00:00Z", branches={"master": "b1"})
    gh.add_repo("carol/Cosmic-fork", 31, parent="alice/Cosmic", default="main", branches={"main": "c1"})
    gh.add_repo("iw2d/kaentake", 2, default="main", description="resizable HD client for v83", branches={"main": "k1"})
    gh.add_repo("frank/kaentake", 21, parent="iw2d/kaentake", default="main", description="resizable HD client for v83",
                branches={"main": "f1"})

    gh.set_compare("alice/Cosmic", "a1", 2, [commit("a1" + "0" * 38, "Fix Duey losing packages on channel change"),
                                             commit("a1" + "1" * 38, "Duey: refund the fee")],
                   [file_change(DUEY), file_change("scripts/npc/9010000.js", status="added")], merge_base="mb1")
    gh.set_compare("alice/Cosmic", "a2", 1, [commit("a2" + "0" * 38, "Boss HP shown above the boss")],
                   [file_change(MONSTER)])
    gh.set_compare("dave/Cosmic", "d1", 1, [commit("d1" + "0" * 38, "Merge branch 'master' of P0nk/Cosmic",
                                                   login="dave", merge=True)], [])
    gh.failing.add(("erin/Cosmic", "e1"))
    gh.set_compare("carol/Cosmic-fork", "c1", 1, [commit("c1" + "0" * 38, "Rename Duey NPC", login="carol")],
                   [file_change("scripts/npc/9010009.js")])
    gh.set_compare("frank/kaentake", "f1", 1, [commit("f1" + "0" * 38, "Damage skin hook", login="frank")],
                   [file_change("src/damageskin.cpp", status="added",
                                patch="@@ -0,0 +1 @@\n+void AttachDamageSkin() {}"),
                    file_change("Custom.wz", status="added", patch=None)])
    return gh


class ForkTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mod-intake-forks-")
        self.gh = scenario()
        self.store = Store(Path(self.tmp, "intake.sqlite3"))
        self.attachments = Path(self.tmp, "attachments")
        self.sleeps, self.logs = [], []

    def tearDown(self):
        self.store.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def mine(self, **kwargs):
        self.gh.log.clear()
        return forklib.run_forks(self.store, self.attachments, {"GITHUB_TOKEN": TOKEN}, transport=self.gh,
                                 sleep=self.sleeps.append, log=self.logs.append, **kwargs)

    def thread_id(self, repo_id, branch):
        return synthetic_id(f"github-fork:{repo_id}:{branch}")

    def count(self, sql, *args):
        return self.store.db.execute(sql, args).fetchone()[0]


class FirstRunTest(ForkTestBase):
    def test_counts(self):
        stats = self.mine()
        self.assertEqual(stats.forks, 7)           # 5 of Cosmic + carol (fork of a fork) + frank
        self.assertEqual(stats.skipped_owner, 1)   # Incluwin
        self.assertEqual(stats.never_pushed, 1)    # bob
        self.assertEqual(stats.compares, 6)        # alice x2, erin, dave, carol, frank (alice's 'sync' = upstream head)
        self.assertEqual(stats.no_own_commits, 2)  # alice sync, dave's merge-only branch
        self.assertEqual((stats.threads, stats.new_threads), (4, 4))
        self.assertIsNone(stats.stopped)
        self.assertTrue(any("erin/Cosmic (master): compare failed" in n for n in stats.notes))
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), 4)

    def test_thread_shape(self):
        self.mine()
        tid = self.thread_id(11, "master")
        t = self.store.thread(tid)
        self.assertEqual(t["name"], "alice/Cosmic (master): Duey fix and boss HP")
        self.assertEqual(t["created_at"], "2024-01-01T00:00:00Z")
        channel = self.store.db.execute("SELECT * FROM channels WHERE id = ?", (t["parent_id"],)).fetchone()
        self.assertEqual((channel["name"], channel["type"], channel["tracked"]), ("forks of P0nk/Cosmic", 1001, 0))
        starter = self.store.db.execute("SELECT * FROM messages WHERE id = ?", (tid,)).fetchone()
        self.assertEqual(starter["author_name"], "alice")
        self.assertIn("2 commit(s) ahead of P0nk:master, 3 behind", starter["content"])
        self.assertIn("Changes: https://github.com/alice/Cosmic/compare/mb1...a1", starter["content"])
        self.assertIn("M " + DUEY, starter["content"])
        self.assertIn("A scripts/npc/9010000.js", starter["content"])
        replies = self.store.db.execute("SELECT id, content, author_name FROM messages WHERE conversation_id = ? AND "
                                        "id != ? ORDER BY id", (tid, tid)).fetchall()
        self.assertEqual([r["id"] for r in replies], [tid + 1, tid + 2])
        self.assertTrue(replies[0]["content"].startswith("a10000000000 Fix Duey losing packages"))
        self.assertEqual(replies[0]["author_name"], "alice")

    def test_nested_fork_and_other_upstream(self):
        self.mine()
        carol = self.store.thread(self.thread_id(31, "main"))
        self.assertEqual(carol["name"], "carol/Cosmic-fork (main): Rename Duey NPC")
        compare = [c for c in self.gh.calls("/compare/") if "carol" in c]
        self.assertEqual(compare, ["/repos/carol/Cosmic-fork/compare/P0nk:master...main"])
        frank = self.store.thread(self.thread_id(21, "main"))
        channel = self.store.db.execute("SELECT name FROM channels WHERE id = ?", (frank["parent_id"],)).fetchone()
        self.assertEqual(channel["name"], "forks of iw2d/kaentake")

    def test_an_old_upstream_description_many_forks_kept_is_not_the_title(self):
        old = "An improved server based on HeavenMS"
        for i, login in enumerate(("gina", "hank", "ivy")):
            self.gh.add_repo(f"{login}/Cosmic", 40 + i, parent="P0nk/Cosmic", description=old,
                             branches={"master": f"{login}1"})
        self.gh.set_compare("gina/Cosmic", "gina1", 1, [commit("9" * 40, "Spawn max 1 boss", login="gina")],
                            [file_change(MONSTER)])
        self.mine()
        self.assertEqual(self.store.thread(self.thread_id(40, "master"))["name"],
                         "gina/Cosmic (master): Spawn max 1 boss")
        self.assertEqual(self.store.thread(self.thread_id(11, "master"))["name"],  # one fork's own words stay
                         "alice/Cosmic (master): Duey fix and boss HP")

    def test_inherited_description_is_not_the_title(self):
        self.mine()
        tid = self.thread_id(21, "main")
        self.assertEqual(self.store.thread(tid)["name"], "frank/kaentake (main): Damage skin hook")
        starter = self.store.db.execute("SELECT content FROM messages WHERE id = ?", (tid,)).fetchone()[0]
        self.assertNotIn("resizable HD client", starter)

    def test_diff_is_a_stored_attachment(self):
        self.mine()
        tid = self.thread_id(11, "master")
        a = self.store.db.execute("SELECT * FROM attachments WHERE message_id = ?", (tid,)).fetchall()
        self.assertEqual(len(a), 1)
        self.assertEqual((a[0]["status"], a[0]["filename"]), ("stored", "alice-Cosmic-master.diff"))
        body = Path(self.attachments, a[0]["sha256"][:2], a[0]["sha256"]).read_text("utf-8")
        self.assertIn(f"diff --git a/{DUEY} b/{DUEY}\n--- a/{DUEY}\n+++ b/{DUEY}\n@@ -1 +1 @@", body)
        self.assertIn("--- /dev/null\n+++ b/scripts/npc/9010000.js", body)
        frank = self.store.db.execute("SELECT sha256 FROM attachments WHERE message_id = ?",
                                      (self.thread_id(21, "main"),)).fetchone()
        text = Path(self.attachments, frank[0][:2], frank[0]).read_text("utf-8")
        self.assertIn("# no patch from GitHub (added, +1 -1): binary or too large", text)

    def test_fetch_never_clones_a_fork(self):
        self.mine()
        tid = self.thread_id(11, "master")
        src = self.store.db.execute("SELECT * FROM sources WHERE message_id = ?", (tid,)).fetchone()
        self.assertEqual((src["status"], src["pinned_sha"], src["base_sha"]), ("skipped", "a1", "mb1"))
        queued = [r["url"] for r in self.store.links_to_fetch(3)]
        self.assertFalse([u for u in queued if "/compare/" in u], queued)

    def test_dossier_reads_a_fork_like_a_release(self):
        self.mine()
        dossiers, _notes = dossierlib.build(self.store, self.tmp)
        d = next(x for x in dossiers if x["id"] == str(self.thread_id(11, "master")))
        self.assertEqual(d["channel"]["type"], "github forks")
        self.assertIsNone(d["discord_url"])
        self.assertEqual(len(d["replies"]), 2)
        self.assertEqual(d["starter"]["attachments"][0]["type"], "text:diff")
        release = d["evidence"]["release"]
        self.assertIn(DUEY, release["file_paths_in_text"])
        self.assertIn("net.server.channel.handlers", release["java_packages"])


class LaterRunTest(ForkTestBase):
    def test_unchanged_forks_cost_no_branch_or_compare_calls(self):
        self.mine()
        stats = self.mine()
        self.assertEqual(stats.unchanged, 5)  # alice, erin, dave, carol, frank
        self.assertEqual(self.gh.calls("/compare/"), [])
        self.assertEqual([c for c in self.gh.calls("/branches") if "P0nk" not in c and "iw2d" not in c], [])
        self.assertEqual((stats.threads, self.count("SELECT COUNT(*) FROM threads")), (0, 4))

    def test_a_new_push_replaces_the_thread(self):
        self.mine()
        tid = self.thread_id(11, "master")
        old_diff = self.store.db.execute("SELECT id FROM attachments WHERE message_id = ?", (tid,)).fetchone()[0]
        self.gh.push("alice/Cosmic", "master", "a3", "2024-07-01T00:00:00Z")
        self.gh.set_compare("alice/Cosmic", "a3", 3, [commit("a1" + "0" * 38, "Fix Duey losing packages"),
                                                      commit("a1" + "1" * 38, "Duey: refund the fee"),
                                                      commit("a3" + "0" * 38, "Duey: log refunds")],
                            [file_change(DUEY), file_change("src/main/java/client/Client.java")], merge_base="mb1")
        stats = self.mine()
        self.assertEqual(self.gh.calls("/compare/"), ["/repos/alice/Cosmic/compare/P0nk:master...master"])
        self.assertEqual((stats.threads, stats.new_threads), (1, 0))
        replies = self.count("SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND id != ?", tid, tid)
        self.assertEqual(replies, 3)
        diffs = [r[0] for r in self.store.db.execute("SELECT id FROM attachments WHERE message_id = ?", (tid,))]
        self.assertEqual(len(diffs), 1)
        self.assertNotEqual(diffs[0], old_diff)
        urls = [r[0] for r in self.store.db.execute("SELECT url FROM sources WHERE message_id = ?", (tid,))]
        self.assertEqual(urls, ["https://github.com/alice/Cosmic/compare/mb1...a3"])
        self.assertEqual(self.count("SELECT COUNT(*) FROM message_revisions"), 0)

    def test_deleted_or_merged_branches_lose_their_threads(self):
        self.mine()
        feature, master = self.thread_id(11, "feature/bosshp"), self.thread_id(11, "master")
        del self.gh.branches["alice/Cosmic"]["feature/bosshp"]
        self.gh.push("alice/Cosmic", "master", "a9", "2024-08-01T00:00:00Z")  # a9: nothing ahead of upstream
        stats = self.mine()
        self.assertEqual(stats.dropped, 2)
        for tid in (feature, master):
            self.assertIsNone(self.store.thread(tid))
            self.assertEqual(self.count("SELECT COUNT(*) FROM messages WHERE conversation_id = ?", tid), 0)
            self.assertEqual(self.count("SELECT COUNT(*) FROM sources WHERE message_id = ?", tid), 0)
        branches = [r[0] for r in self.store.db.execute(
            "SELECT branch FROM github_fork_branches WHERE repo_id = 11 ORDER BY branch")]
        self.assertEqual(branches, ["master", "sync"])
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), 2)

    def test_limit_stops_and_the_next_run_continues(self):
        stats = self.mine(limit=2)
        self.assertEqual(stats.compares, 2)
        self.assertIn("--limit", stats.stopped)
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), 2)
        stats = self.mine()
        self.assertIsNone(stats.stopped)
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), 4)
        self.assertEqual([c for c in self.gh.calls("/compare/") if "alice" in c], [])  # alice was finished

    def test_rate_limit_stops_cleanly_and_keeps_what_it_has(self):
        self.gh.requests_left = 9
        stats = self.mine()
        self.assertIn("GitHub API limit reached", stats.stopped)
        saved = self.count("SELECT COUNT(*) FROM threads")
        self.assertGreater(saved, 0)
        self.gh.requests_left = None
        self.mine()
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), 4)

    def test_a_failing_github_is_asked_again_next_run(self):
        self.gh.flaky.add(("alice/Cosmic", "a2"))
        stats = self.mine()
        self.assertTrue(any("compare failed, retried next run" in n for n in stats.notes))
        self.assertIsNone(self.store.thread(self.thread_id(11, "feature/bosshp")))
        self.gh.flaky.clear()
        stats = self.mine()
        self.assertEqual(self.gh.calls("/compare/"), ["/repos/alice/Cosmic/compare/P0nk:master...feature/bosshp"])
        self.assertIsNotNone(self.store.thread(self.thread_id(11, "feature/bosshp")))

    def test_secondary_limit_waits_then_retries(self):
        self.gh.secondary["/forks"] = 1
        self.mine()
        self.assertIn(7.0, self.sleeps)
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), 4)


class AccessTest(ForkTestBase):
    def test_bad_token(self):
        self.gh.token = "other"
        with self.assertRaisesRegex(forklib.GitHubError, "401"):
            self.mine()

    def test_no_token(self):
        with self.assertRaisesRegex(IntakeError, "gh auth login"):
            forklib.run_forks(self.store, self.attachments, {}, transport=self.gh, token_lookup=lambda env: None)

    def test_token_lookup(self):
        class Done:
            def __init__(self, code, out):
                self.returncode, self.stdout = code, out

        self.assertEqual(forklib.github_token({"GITHUB_TOKEN": "a", "GH_TOKEN": "b"}, run=None), "a")
        self.assertEqual(forklib.github_token({}, run=lambda *a, **k: Done(0, "from-gh\n")), "from-gh")
        self.assertIsNone(forklib.github_token({}, run=lambda *a, **k: Done(1, "")))

        def missing(*a, **k):
            raise FileNotFoundError("gh")
        self.assertIsNone(forklib.github_token({}, run=missing))


class DroppedConnectionTest(unittest.TestCase):
    def test_cut_off_body_is_a_retryable_connection_error(self):
        class CutOff:
            status, headers = 200, {}

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                raise http.client.IncompleteRead(b"x" * 10, 90)

        with mock.patch("urllib.request.urlopen", lambda *a, **k: CutOff()):
            with self.assertRaises(ConnectionError):
                UrllibTransport().request("GET", "https://api.github.com/repos/P0nk/Cosmic", {})

    def test_client_retries_a_dropped_connection(self):
        gh, calls = scenario(), []

        class Flaky:
            def request(self, method, url, headers):
                calls.append(url)
                if len(calls) == 1:
                    raise ConnectionError("connection dropped")
                return gh.request(method, url, headers)

        client = forklib.GitHubClient(TOKEN, Flaky(), sleep=lambda s: None)
        self.assertEqual(client.get("/repos/P0nk/Cosmic")["full_name"], "P0nk/Cosmic")
        self.assertEqual(len(calls), 2)


class CliTest(unittest.TestCase):
    def test_forks_then_status(self):
        tmp = tempfile.mkdtemp(prefix="mod-intake-forks-cli-")
        try:
            gh = scenario()
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = mod_intake.main(["--data", tmp, "forks", "--upstream", "iw2d/kaentake"], github_transport=gh,
                                       environ={"GITHUB_TOKEN": TOKEN}, sleep=lambda s: None)
                self.assertEqual(code, 0)
                code = mod_intake.main(["--data", tmp, "status"])
            self.assertEqual(code, 0)
            text = out.getvalue()
            self.assertIn("Read 1 forks", text)
            self.assertIn("1 fork threads saved (1 new)", text)
            self.assertIn("Other sources: 1 GitHub forks read, 1 fork branches with work of their own", text)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
