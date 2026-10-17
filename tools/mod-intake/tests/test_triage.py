"""P3 dossiers, triage, catalog, overrides and board, on the fake Discord fixture after a P2 fetch from local git
remotes, with a fake Jev (tests/fake_jev.py). No network, no real key.

    python -m unittest discover -s tools/mod-intake/tests
"""
import contextlib
import http.client
import io
import json
import shutil
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import mod_intake  # noqa: E402
from fake_discord import TOKEN, FakeDiscord  # noqa: E402
from fake_jev import KEY, FakeJev  # noqa: E402
from git_fixture import FakeRemote, FakeWeb, build_bosshp, build_gist, have_git, isolated_env, text  # noqa: E402
from intake import board as boardlib  # noqa: E402
from intake import dossier as dossierlib  # noqa: E402
from intake import fetch as fetchlib  # noqa: E402
from intake import jev as jevlib  # noqa: E402
from intake import triage as triagelib  # noqa: E402
from intake import yamlish  # noqa: E402
from intake.api import DiscordClient  # noqa: E402
from intake.store import Store  # noqa: E402
from intake.sync import run_sync  # noqa: E402

CATALOG = Path(__file__).resolve().parent.parent / "catalog"
BOSS_HP, DAMAGE_SKIN, DUEY, SKILLS, MOB_HP, LOGIN = (
    "1000000000000000100", "1000000000000000200", "1000000000000000300", "1000000000000000400",
    "1000000000000000500", "1000000000000000600")
SCRIPT = {
    "Boss HP bar": {"kind": "client_hook", "version": "v83", "family": "no_server_part", "applies": "conflicts",
                    "match": "boss_hp", "includes_client_hook": 0.97},
    "Damage skin": {"kind": "client_wz", "version": "other", "family": "no_server_part", "applies": "new"},
    "Mob HP percent": {"kind": "client_hook", "version": "v83", "family": "no_server_part",
                       "applies": "already_have", "match": "mob_hp_tag"},
    "Duey fix": {"kind": "server_java", "version": "v83", "family": "cosmic", "applies": "new",
                 "includes_server_java": 0.95, "includes_database": 0.7},
    "HeavenMS skill": {"kind": "server_java", "kind_p": 0.5, "version": "v83", "family": "heavenms_line",
                       "applies": "new"},
    "login patch": {"kind": "not_a_mod", "version": "v83", "family": "cosmic", "applies": "new"},
}
FIXTURE = {}  # built once: a data folder after sync + fetch, copied for each test


def setUpModule():
    if not have_git():
        return
    root = Path(tempfile.mkdtemp(prefix="mod-intake-p3-"))
    env = isolated_env(root)
    bosshp, c = build_bosshp(root, env)
    gist, _ = build_gist(root, env)
    cosmic = FakeRemote(root, "cosmic", env)
    base = cosmic.commit({"src/main/java/net/server/channel/handlers/DueyHandler.java":
                          "package net.server.channel.handlers;\nimport client.Character;\nclass DueyHandler {}\n",
                          "README.md": "Cosmic\n"}, "base")
    cosmic.run("checkout", "--quiet", "-b", "pr12")
    head = cosmic.commit({"src/main/java/net/server/channel/handlers/DueyHandler.java":
                          "package net.server.channel.handlers;\nimport client.Character;\nimport tools.PacketCreator;\n"
                          "class DueyHandler { void cc() { c.sendPacket(PacketCreator.enableActions()); } }\n",
                          "src/main/resources/db/duey.sql": "ALTER TABLE dueypackages ADD COLUMN x INT;\n"},
                         "keep packages on CC")
    cosmic.run("checkout", "--quiet", "main")
    cosmic.push("main", f"{head}:refs/pull/12/head")
    data = root / "data"
    data.mkdir()
    store = Store(data / "intake.sqlite3")
    fake = FakeDiscord()
    run_sync(DiscordClient(TOKEN, transport=fake, sleep=lambda s: None), store, fake.guild_id,
             str(data / "attachments"), log=lambda s: None)
    urls = {"https://github.com/hookwright/v83-bosshp.git": bosshp.uri,
            "https://gist.github.com/0a1b2c3d4e5f6a7b8c9d.git": gist.uri,
            "https://github.com/packetsmith/cosmic.git": cosmic.uri}
    web = FakeWeb({"https://pastebin.com/raw/Ab12Cd34": text("UPDATE dueypackages SET checked = 0;\n")})
    fetchlib.run_fetch(store, data, transport=web, remote_url=lambda u: urls.get(u.lower(),
                       (root / "missing.git").as_uri()), sleep=lambda s: None, log=lambda s: None, delay=0,
                       git_protocols="file", git_env=env)
    store.close()
    FIXTURE.update(root=root, env=env, data=data, head=head, base=base, c=c, cosmic=cosmic)


def tearDownModule():
    if FIXTURE:
        fetchlib._rmtree(FIXTURE["root"])


@unittest.skipUnless(have_git(), "git is not on PATH")
class TriageTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="mod-intake-triage-"))
        self.data = self.tmp / "data"
        shutil.copytree(FIXTURE["data"], self.data)
        self.catalog = self.tmp / "catalog"
        self.catalog.mkdir()
        shutil.copy(CATALOG / "inventory.yaml", self.catalog / "inventory.yaml")
        self.env = FIXTURE["env"]
        self.jev = FakeJev(SCRIPT)
        self.sleeps = []

    def tearDown(self):
        fetchlib._rmtree(self.tmp)

    def cli(self, *args, environ=None, transport=None, serve=None):
        out, err = io.StringIO(), io.StringIO()
        environ = {"TYPESAFE_API_KEY": KEY} if environ is None else environ
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = mod_intake.main(["--data", str(self.data), "--catalog", str(self.catalog), *args],
                                   transport=transport, sleep=self.sleeps.append, jev_transport=self.jev,
                                   environ=environ, serve=serve, git_env=self.env)
        return code, out.getvalue(), err.getvalue()

    def paths(self):
        return triagelib.Paths(self.data, self.catalog)

    def run_triage(self, ask=True, server_repo=None, write=True):
        store = Store(self.data / "intake.sqlite3")
        try:
            jev = jevlib.JevClient(KEY, transport=self.jev, sleep=self.sleeps.append) if ask else None
            return triagelib.run(store, self.paths(), jev=jev, ask=ask, write=write, server_repo=server_repo,
                                 git_env=self.env, log=lambda s: None)
        finally:
            store.close()

    def catalog_entries(self):
        data = yamlish.load((self.catalog / "mods.yaml").read_text("utf-8"))
        return {e["thread"]: e for e in data["mods"]}

    def sync_more(self, change):
        """Apply change(fake) to a fresh fake Discord and sync it into the copied store."""
        fake = FakeDiscord()
        store = Store(self.data / "intake.sqlite3")
        try:
            run_sync(DiscordClient(TOKEN, transport=fake, sleep=lambda s: None), store, fake.guild_id,
                     str(self.data / "attachments"), log=lambda s: None)
            change(fake)
            run_sync(DiscordClient(TOKEN, transport=fake, sleep=lambda s: None), store, fake.guild_id,
                     str(self.data / "attachments"), log=lambda s: None)
        finally:
            store.close()


class DryRunTest(TriageTestBase):
    def test_every_thread_has_a_dossier_and_a_triage_result(self):
        code, out, err = self.cli("triage", "--dry-run")
        self.assertEqual(code, 0, out + err)
        for title in ("[v83] Boss HP bar hook for kaentake", "[v95] Damage skin loader", "[Cosmic] Duey fix + Fredrick",
                      "HeavenMS skill fixes", "[v83] Mob HP percent tag", "v83 login patch discussion"):
            self.assertIn(title, out)
        self.assertIn("6 threads:", out)
        self.assertRegex(out, r"Jev \(jev-1\.13\.0\): 6 requests to send for 6 threads, ~[\d,]+ input tokens, "
                              r"~\$0\.\d{4} at \$0\.042/Mtok; 0 already cached\.")
        self.assertEqual(sorted(p.stem for p in (self.data / "dossiers").glob("*.json")),
                         [BOSS_HP, DAMAGE_SKIN, DUEY, SKILLS, MOB_HP, LOGIN])
        self.assertEqual(self.jev.calls, 0)
        self.assertFalse((self.catalog / "mods.yaml").exists())
        # rules alone already skip the thread whose title names another version
        self.assertRegex(out, r"\? skipped\s+client_wz\s+other .*Damage skin")
        self.assertIn("made for another game version (v95)", out)

    def test_dry_run_needs_no_key(self):
        code, out, err = self.cli("triage", "--dry-run", environ={})
        self.assertEqual(code, 0, err)

    def test_real_run_without_a_key_says_where_to_put_it(self):
        code, out, err = self.cli("triage", environ={})
        self.assertEqual(code, 2)
        self.assertIn("TYPESAFE_API_KEY not found", err)
        self.assertEqual(self.jev.calls, 0)


class DossierTest(TriageTestBase):
    def setUp(self):
        super().setUp()
        store = Store(self.data / "intake.sqlite3")
        from intake import inventory as invlib
        self.dossiers = {d["id"]: d for d in dossierlib.build(store, self.data, invlib.load(self.catalog /
                                                                  "inventory.yaml"), None, self.env)[0]}
        store.close()

    def test_thread_parts(self):
        d = self.dossiers[BOSS_HP]
        self.assertEqual((d["title"], d["author"]["name"], d["channel"]["name"], d["unit"]),
                         ("[v83] Boss HP bar hook for kaentake", "Hookwright", "client-releases", "thread"))
        self.assertEqual(d["tags"], ["tag 700000000000000001"])
        self.assertEqual(d["discord_url"], f"https://discord.com/channels/900000000000000001/{BOSS_HP}")
        self.assertEqual(d["starter"]["id"], BOSS_HP)
        self.assertEqual([r["id"] for r in d["replies"]], ["1000000000000000110", "1000000000000000120"])
        self.assertEqual(d["replies"][1]["reply_to"], "1000000000000000110")
        self.assertTrue(d["replies"][1]["by_release_author"])
        self.assertFalse(d["replies"][0]["by_release_author"])
        self.assertEqual(d["starter"]["attachments"][0]["type"], "pe")
        self.assertEqual(d["replies"][0]["code_blocks"][0]["language"], "cpp")

    def test_links_carry_their_pins_and_files(self):
        links = {link["url"]: link for link in self.dossiers[BOSS_HP]["starter"]["links"]}
        repo = links["https://github.com/hookwright/v83-bosshp"]
        self.assertEqual(repo["snapshot"]["pinned_sha"], FIXTURE["c"]["c2"])
        self.assertEqual(repo["files"]["mode"], "repo")
        self.assertEqual(sorted(repo["files"]["files"]),
                         ["README.md", "docs/notes.txt", "src/BossHp.cpp", "src/Extra.cpp"])
        commit = {link["url"]: link for link in self.dossiers[BOSS_HP]["replies"][1]["links"]}
        self.assertEqual(next(iter(commit.values()))["snapshot"]["status"], "failed")
        gist = {link["url"]: link for link in self.dossiers[BOSS_HP]["replies"][0]["links"]}
        self.assertEqual(next(iter(gist.values()))["excerpt"].strip(), "if (!stage) return;")  # CRLF on Windows

    def test_pull_request_changes_are_listed_and_read_offline(self):
        d = self.dossiers[DUEY]
        pr = next(link for link in d["starter"]["links"] if link["kind"] == "github_pull")
        self.assertEqual(pr["snapshot"]["pinned_sha"], FIXTURE["head"])
        self.assertEqual(pr["files"]["mode"], "changed")
        self.assertEqual(pr["files"]["status"], {"src/main/java/net/server/channel/handlers/DueyHandler.java": "M",
                                                 "src/main/resources/db/duey.sql": "A"})
        ev = d["evidence"]["release"]
        self.assertIn("PacketCreator", ev["identifiers"])          # read from the pinned file, not the message
        self.assertIn("client.Character", ev["identifiers"])
        self.assertIn("net.server.channel.handlers", ev["java_packages"])
        self.assertIn("maven_java", ev["path_layouts"])
        self.assertIn("sql", ev["path_layouts"])
        paste = next(link for link in d["starter"]["links"] if link["kind"] == "paste")
        self.assertIn("UPDATE dueypackages", paste["excerpt"])
        self.assertEqual(d["starter"]["attachments"][0]["type"], "text:diff")
        self.assertIn("server_java", d["hints"]["kinds"])
        self.assertIn("database", d["hints"]["kinds"])
        self.assertIn(("cosmic", "uses PacketCreator, client.Character"), [tuple(f) for f in d["hints"]["families"]])

    def test_client_addresses_and_versions(self):
        d = self.dossiers[BOSS_HP]
        self.assertEqual(d["evidence"]["release"]["addresses"], ["0x00A2C8E0"])
        self.assertEqual(d["hints"]["kinds"][0], "client_hook")
        self.assertIn(("v83", "title says v83"), [tuple(v) for v in d["hints"]["versions"]])
        self.assertIn(("other", "title says v95"), [tuple(v) for v in self.dossiers[DAMAGE_SKIN]["hints"]["versions"]])

    def test_text_channel_thread_takes_its_starter_from_the_channel(self):
        d = self.dossiers[LOGIN]
        self.assertEqual(d["channel"]["name"], "patches")
        self.assertEqual(d["starter"]["id"], LOGIN)
        self.assertEqual([r["text"] for r in d["replies"]], ["Is this for Cosmic?",
                                                             "Yes, Cosmic master. Mirror: https://mega.nz/file/abcd#key"])
        self.assertNotIn("post-1000000000000000610", self.dossiers)  # the bot's link-less post is no release

    def test_hash_changes_with_content_only(self):
        store = Store(self.data / "intake.sqlite3")
        again = {d["id"]: d for d in dossierlib.build(store, self.data, None, None, self.env)[0]}
        store.close()
        self.assertEqual(again[MOB_HP]["hash"], dossierlib.content_hash(again[MOB_HP]))
        self.sync_more(lambda fake: fake.add_message(MOB_HP, "wzfox", "crashes on login for me"))
        store = Store(self.data / "intake.sqlite3")
        later = {d["id"]: d for d in dossierlib.build(store, self.data, None, None, self.env)[0]}
        store.close()
        self.assertNotEqual(later[MOB_HP]["hash"], again[MOB_HP]["hash"])
        self.assertEqual(later[DUEY]["hash"], again[DUEY]["hash"])

    def test_edited_starter_keeps_earlier_versions(self):
        self.sync_more(lambda fake: fake.edit_message(MOB_HP, MOB_HP, "Mob HP tag v2 for [v83]: 0x005336CA"))
        store = Store(self.data / "intake.sqlite3")
        d = {d["id"]: d for d in dossierlib.build(store, self.data, None, None, self.env)[0]}[MOB_HP]
        store.close()
        self.assertEqual(len(d["revisions"]), 1)
        self.assertIn("Mob HP percentage tag.", d["revisions"][0]["text"])
        self.assertIn("0x005336CA", d["evidence"]["release"]["addresses"])


class JudgedRunTest(TriageTestBase):
    def test_triage_writes_the_catalog(self):
        code, out, err = self.cli("triage")
        self.assertEqual(code, 0, out + err)
        self.assertIn("Judged 6 requests", out)
        e = self.catalog_entries()
        self.assertEqual({t: (x["status"], x["reason"]) for t, x in e.items()}, {
            BOSS_HP: ("candidate", None),
            DAMAGE_SKIN: ("skipped", "made for another game version (v95)"),
            DUEY: ("candidate", None),
            SKILLS: ("candidate", None),
            MOB_HP: ("skipped", "already have it: mob_hp_tag"),
            LOGIN: ("skipped", "not a mod"),
        })
        self.assertTrue(e[BOSS_HP]["review"])            # applies = conflicts (with boss_hp)
        self.assertEqual(e[BOSS_HP]["match"], "boss_hp")
        self.assertTrue(e[SKILLS]["review"])             # kind only 0.5
        self.assertFalse(e[DUEY]["review"])
        self.assertEqual(e[DUEY]["includes"], ["server_java", "database"])
        self.assertEqual(e[DUEY]["family"], "cosmic")
        self.assertEqual(e[DUEY]["judged"]["kind"]["server_java"], 0.9)
        self.assertEqual(e[BOSS_HP]["fixes"], ["1000000000000000110", "1000000000000000120"])
        self.assertEqual(e[BOSS_HP]["replies"], {"total": 2, "fix": 2})
        self.assertEqual(e[BOSS_HP]["id"], "boss_hp_bar_hook_for_kaentake")
        self.assertEqual(e[DUEY]["id"], "duey_fix_fredrick")
        pr = next(s for s in e[DUEY]["sources"] if s["kind"] == "github_pull")
        self.assertEqual((pr["pin"], pr["base"], pr["files"]), (FIXTURE["head"], FIXTURE["base"], 2))
        self.assertTrue((self.data / "board.html").exists())

    def test_catalog_is_metadata_only(self):
        self.cli("triage")
        text = (self.catalog / "mods.yaml").read_text("utf-8")
        for fragment in ("Crashes in Cash Shop", "m_pStage", "Drop the DLL", "Duey loses packages", "Is this for",
                         "UPDATE dueypackages", "if (!stage)", "fake dll bytes"):
            self.assertNotIn(fragment, text)
        self.assertIn("https://github.com/packetsmith/Cosmic/pull/12", text)

    def test_requests_carry_facts_not_rule_conclusions(self):
        self.cli("triage")
        for body in self.jev.requests:
            state = json.dumps(body["state"])
            for word in ("hints", "client_hook", "server_java", "heavenms_line", "already_have", "skipped"):
                self.assertNotIn(word, state)
            self.assertEqual(body["model"], "jev-1.13.0")
        boss = next(b for b in self.jev.requests if b["state"]["release"]["title"].startswith("[v83] Boss"))
        self.assertEqual(boss["state"]["evidence"]["client_addresses_in_text_or_files"], ["0x00A2C8E0"])
        self.assertEqual(set(boss["questions"]) - {"reply_0", "reply_1"},
                         {"kind", "version", "family", "applies", "match"} | {"includes_" + k for k in jevlib.MOD_KINDS})
        self.assertIn("mob_hp_tag", boss["questions"]["match"]["criteria"])
        self.assertIn("none", boss["questions"]["match"]["criteria"])
        self.assertEqual(boss["state"]["replies"][1]["reply"], 2)

    def test_rerun_pays_only_for_changed_threads(self):
        self.cli("triage")
        self.assertEqual(len(self.jev.requests), 6)
        code, out, _ = self.cli("triage")
        self.assertIn("Judged 0 requests", out)
        self.assertEqual(len(self.jev.requests), 6)
        self.sync_more(lambda fake: fake.add_message(MOB_HP, "wzfox", "crashes on login for me"))
        code, out, _ = self.cli("triage")
        self.assertIn("Judged 1 requests", out)
        self.assertEqual(self.jev.requests[-1]["state"]["release"]["title"], "[v83] Mob HP percent tag")
        e = self.catalog_entries()
        self.assertEqual(len(e[MOB_HP]["bug_reports"]), 1)
        self.assertEqual(e[MOB_HP]["replies"], {"total": 1, "bug_report": 1})
        code, out, _ = self.cli("triage", "--dry-run")
        self.assertIn("0 requests to send", out)

    def test_limit_judges_some_threads_and_leaves_the_rest(self):
        code, out, _ = self.cli("triage", "--limit", "2")
        self.assertIn("Judged 2 requests", out)
        self.assertIn("4 left for the next run", out)
        e = self.catalog_entries()
        self.assertEqual(sum(1 for x in e.values() if x.get("judged")), 2)

    def test_no_jev_uses_the_cache_and_never_calls(self):
        self.cli("triage", "--limit", "1")
        calls = self.jev.calls
        code, out, _ = self.cli("triage", "--no-jev", environ={})
        self.assertEqual(code, 0)
        self.assertEqual(self.jev.calls, calls)
        self.assertIn("Cached judgments only: 1 of 6 requests have answers.", out)

    def test_ids_stay_when_a_title_changes(self):
        self.cli("triage")
        store = Store(self.data / "intake.sqlite3")
        store.db.execute("UPDATE threads SET name = 'Boss HP bar v2' WHERE id = ?", (int(BOSS_HP),))
        store.commit()
        store.close()
        self.cli("triage", "--no-jev")
        e = self.catalog_entries()
        self.assertEqual(e[BOSS_HP]["id"], "boss_hp_bar_hook_for_kaentake")
        self.assertEqual(e[BOSS_HP]["title"], "Boss HP bar v2")

    def test_commits_already_in_our_server_are_skipped(self):
        out = self.run_triage(server_repo=FIXTURE["cosmic"].bare)
        e = {x["thread"]: x for x in out.entries}
        self.assertEqual((e[DUEY]["status"], e[DUEY]["reason"]), ("skipped", "already in our server history"))
        pr = next(s for s in e[DUEY]["sources"] if s["kind"] == "github_pull")
        self.assertTrue(pr["in_our_history"])


class ApiTest(TriageTestBase):
    def test_rate_limits_are_retried(self):
        self.jev.queue = [429, 529]
        code, out, err = self.cli("triage", "--limit", "1")
        self.assertEqual(code, 0, err)
        self.assertIn("Judged 1 requests", out)
        self.assertEqual(len(self.sleeps), 2)

    def test_bad_key_stops_the_run_without_printing_it(self):
        code, out, err = self.cli("triage", environ={"TYPESAFE_API_KEY": "ts-wrong"})
        self.assertEqual(code, 2)
        self.assertIn("refused the API key", err)
        self.assertNotIn("ts-wrong", out + err)

    def test_a_rejected_request_is_reported_and_the_rest_go_on(self):
        self.jev.queue = [422]
        code, out, err = self.cli("triage")
        self.assertEqual(code, 0, err)
        self.assertIn("Judged 5 requests", out)
        self.assertIn("1 failed", out)
        self.assertIn("1 left for the next run", out)

    def test_key_from_the_env_file_next_to_the_data_folder(self):
        (self.tmp / ".env").write_text(f"TYPESAFE_API_KEY={KEY}\n", "utf-8")
        code, out, err = self.cli("triage", "--limit", "1", environ={})
        self.assertEqual(code, 0, err)
        self.assertNotIn(KEY, out + err)
        self.assertEqual(jevlib.load_key({}, [self.data / ".env", self.tmp / ".env"]), KEY)
        self.assertNotIn(KEY, repr(jevlib.JevClient(KEY)))

    def test_long_threads_are_split_into_several_requests(self):
        d = {"id": "1", "title": "t", "channel": {"name": "c"}, "tags": [], "author": {"name": "a"},
             "created_at": "2025-01-01", "starter": {"text": "x", "links": [], "attachments": [], "code_blocks": []},
             "revisions": [], "evidence": {"release": {}},
             "replies": [{"id": str(100 + i), "author": "b", "text": "y" * 800, "links": [], "attachments": [],
                          "code_blocks": []} for i in range(90)]}
        reqs = jevlib.requests_for(d, [])
        self.assertGreater(len(reqs), 2)
        self.assertEqual(reqs[0].part, "main")
        self.assertIn("kind", reqs[0].questions)
        self.assertNotIn("kind", reqs[1].questions)
        ids = [mid for r in reqs for mid in r.replies.values()]
        self.assertEqual(ids, [str(100 + i) for i in range(90)])
        for r in reqs:
            self.assertLessEqual(len(json.dumps(r.state)), jevlib.MAX_STATE_CHARS + 1000)
            self.assertLessEqual(len(r.replies), jevlib.REPLIES_PER_REQUEST)


class PolicyTest(unittest.TestCase):
    """decide() on hand-made dossiers and judgments: when a judgment may skip a thread on its own."""

    def decide(self, title, versions=(), **choices):
        d = {"id": "1", "title": title, "starter": {"links": []}, "replies": [],
             "hints": {"kinds": [], "versions": list(versions), "families": [], "why": []}}
        answers = {qid: {"type": "choice", "choice": c, "probabilities": {c: p}} for qid, (c, p) in choices.items()}
        answers.setdefault("kind", {"type": "choice", "choice": "server_java", "probabilities": {"server_java": 0.95}})
        return triagelib.decide(d, {"answers": answers, "reply_answers": {}, "parts": 1, "answered": 1,
                                    "model": "jev-1.13.0"})

    def test_sure_skips_need_no_review(self):
        res = self.decide("x", kind=("not_a_mod", 0.95), version=("v83", 0.95))
        self.assertEqual((res.status, res.reason, res.review), ("skipped", "not a mod", False))

    def test_less_sure_skips_are_marked_for_review(self):
        res = self.decide("x", kind=("not_a_mod", 0.7), version=("v83", 0.95))
        self.assertEqual((res.status, res.review), ("skipped", True))
        res = self.decide("x", version=("v83", 0.95), applies=("already_have", 0.95), match=("mob_hp_tag", 0.8))
        self.assertEqual((res.status, res.reason, res.review), ("skipped", "already have it: mob_hp_tag", True))

    def test_a_v83_title_is_not_overruled_by_a_doubtful_other_version(self):
        res = self.decide("[v83] Thing", versions=[("v83", "title says v83")], version=("other", 0.75))
        self.assertEqual((res.status, res.review), ("candidate", True))
        res = self.decide("[v83] Thing", versions=[("v83", "title says v83")], version=("other", 0.95))
        self.assertEqual(res.status, "skipped")

    def test_already_have_needs_a_match(self):
        res = self.decide("x", version=("v83", 0.95), applies=("already_have", 0.95), match=("none", 0.9))
        self.assertEqual(res.status, "candidate")

    def test_rules_only(self):
        d = {"id": "1", "title": "[v95] Skin", "starter": {"links": []}, "replies": [],
             "hints": {"kinds": ["client_wz"], "versions": [("other", "title says v95")], "families": [], "why": []}}
        res = triagelib.decide(d, None)
        self.assertEqual((res.status, res.reason, res.review, res.kind, res.family),
                         ("skipped", "made for another game version (v95)", True, "client_wz", "no_server_part"))


class OverrideTest(TriageTestBase):
    def test_corrections_survive_reruns(self):
        self.cli("triage")
        code, out, err = self.cli("overrides", "set", DAMAGE_SKIN, "status=porting", "kind=client_hook",
                                  "note=ported by hand for v83")
        self.assertEqual(code, 0, err)
        self.cli("triage")
        e = self.catalog_entries()[DAMAGE_SKIN]
        self.assertEqual((e["status"], e["kind"], e["reason"]), ("porting", "client_hook", None))
        self.assertEqual(e["auto"], {"status": "skipped", "kind": "client_wz", "reason":
                                     "made for another game version (v95)"})
        self.assertEqual(e["overridden"], ["kind", "status"])
        self.assertEqual(e["note"], "ported by hand for v83")
        self.assertFalse(e["review"])
        code, out, _ = self.cli("overrides", "list")
        self.assertIn(f"{DAMAGE_SKIN}: status=porting, kind=client_hook", out)

    def test_bad_values_are_refused(self):
        code, _, err = self.cli("overrides", "set", DUEY, "status=done")
        self.assertEqual(code, 1)
        self.assertIn("status must be one of", err)
        code, _, err = self.cli("overrides", "set", DUEY, "colour=red")
        self.assertIn("unknown field", err)
        code, _, err = self.cli("overrides", "set", DUEY, "id=Bad Id")
        self.assertIn("snake_case", err)

    def test_empty_value_removes_a_correction(self):
        self.cli("overrides", "set", DUEY, "status=porting", "kind=database")
        self.cli("overrides", "set", DUEY, "kind=")
        self.assertEqual(triagelib.load_overrides(self.catalog / "overrides.yaml"), {DUEY: {"status": "porting"}})

    def test_reply_corrections_and_mod_id_rename(self):
        self.cli("triage")
        self.cli("overrides", "set", DUEY, "id=duey_cc_fix")
        triagelib.save_override(self.catalog / "overrides.yaml", BOSS_HP, {"replies": {"1000000000000000110":
                                                                                       "bug_report"}})
        self.cli("triage", "--no-jev")
        e = self.catalog_entries()
        self.assertEqual(e[DUEY]["id"], "duey_cc_fix")
        self.assertEqual(e[BOSS_HP]["fixes"], ["1000000000000000120"])
        self.assertEqual(e[BOSS_HP]["bug_reports"], ["1000000000000000110"])
        self.assertEqual(e[BOSS_HP]["auto"], {"replies": {"1000000000000000110": "fix"}})

    def test_import_a_board_export(self):
        export = self.tmp / "board-corrections.json"
        export.write_text(json.dumps({"overrides": {LOGIN: {"status": "candidate", "kind": "client_hook"}}}), "utf-8")
        code, out, err = self.cli("overrides", "import", str(export))
        self.assertEqual(code, 0, err)
        self.assertIn("Imported corrections for 1 threads", out)
        self.cli("triage")
        e = self.catalog_entries()[LOGIN]
        self.assertEqual((e["status"], e["kind"]), ("candidate", "client_hook"))
        bad = self.tmp / "bad.json"
        bad.write_text(json.dumps({"overrides": {LOGIN: {"status": "nope"}}}), "utf-8")
        code, _, err = self.cli("overrides", "import", str(bad))
        self.assertEqual(code, 1)
        self.assertEqual(triagelib.load_overrides(self.catalog / "overrides.yaml")[LOGIN]["status"], "candidate")


class BoardTest(TriageTestBase):
    def test_board_embeds_untrusted_text_safely(self):
        self.sync_more(lambda fake: fake.add_message(DUEY, "wzfox", "</script><script>alert(1)</script> & <b>x</b>"))
        code, out, err = self.cli("board")
        self.assertEqual(code, 0, err)
        html = (self.data / "board.html").read_text("utf-8")
        self.assertNotIn("<script>alert(1)", html)
        self.assertEqual(html.count("</script>"), 2)   # the data tag and the code tag, nothing from Discord
        start = html.index('type="application/json">') + len('type="application/json">')
        data = json.loads(html[start:html.index("</script>", start)])
        self.assertEqual(len(data["units"]), 6)
        texts = [r["text"] for u in data["units"] for r in u["dossier"]["replies"]]
        self.assertIn("</script><script>alert(1)</script> & <b>x</b>", texts)
        self.assertIn('content=""', html)  # no token in a file board
        self.assertIn("textContent", html)
        self.assertNotIn("innerHTML", html)

    def test_served_board_saves_corrections(self):
        self.cli("triage")
        result = {}

        def serve(server):
            t = threading.Thread(target=server.serve_forever, daemon=True)
            t.start()
            try:
                result.update(self.exercise(server))
            finally:
                server.httpd.shutdown()
                t.join(5)

        code, out, err = self.cli("board", "--serve", "--port", "0", serve=serve)
        self.assertEqual(code, 0, err)
        self.assertEqual(result["page"], 200)
        self.assertEqual(result["no_token"], 403)
        self.assertEqual(result["wrong_host"], 403)
        self.assertEqual(result["bad_value"], 400)
        self.assertEqual(result["saved"], 200)
        self.assertEqual(result["unit_status"], "porting")
        self.assertEqual(triagelib.load_overrides(self.catalog / "overrides.yaml"), {SKILLS: {"status": "porting"}})
        self.assertEqual(self.catalog_entries()[SKILLS]["status"], "porting")

    def exercise(self, server):
        def request(method, path, body=None, headers=None):
            conn = http.client.HTTPConnection("127.0.0.1", server.port, timeout=10)
            conn.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers or {})
            r = conn.getresponse()
            payload = r.read()
            conn.close()
            return r.status, payload

        page_status, page = request("GET", "/")
        token = page.decode("utf-8").split('name="board-token" content="')[1].split('"')[0]
        out = {"page": page_status}
        body = {"unit": SKILLS, "fields": {"status": "porting"}}
        out["no_token"] = request("POST", "/api/override", body)[0]
        out["wrong_host"] = request("POST", "/api/override", body, {"X-Board-Token": token,
                                                                     "Host": "evil.example:80"})[0]
        out["bad_value"] = request("POST", "/api/override", {"unit": SKILLS, "fields": {"status": "x"}},
                                   {"X-Board-Token": token})[0]
        status, payload = request("POST", "/api/override", body, {"X-Board-Token": token})
        out["saved"] = status
        out["unit_status"] = json.loads(payload)["unit"]["entry"]["status"] if status == 200 else None
        return out


if __name__ == "__main__":
    unittest.main()
