"""The manual inbox: parsing text copied out of Discord, and importing inbox files into the store.

    python -m unittest discover -s tools/mod-intake/tests
"""
import contextlib
import io
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import mod_intake  # noqa: E402
from intake import dossier as dossierlib  # noqa: E402
from intake.inbox import parse, run_inbox  # noqa: E402
from intake.store import Store, synthetic_id  # noqa: E402

THREAD = 1000000000000000777
GUILD = 900000000000000001
COZY = f"""https://discord.com/channels/{GUILD}/{THREAD}
Title: [v83] Quest log colours

hookwright — 7/22/25, 11:22 AM
Colours for the quest log.
```cpp
void AttachQuestListColorMod() {{}}
```
packetsmith — Today at 3:14 PM
Works on Cosmic. Source: https://github.com/packetsmith/questcolor
hookwright — Today at 3:20 PM
Fixed the Cash Shop crash too.
"""


class ParseTest(unittest.TestCase):
    def test_cozy_copy_with_link_and_title(self):
        p = parse(COZY)
        self.assertEqual(p.link, (str(GUILD), str(THREAD), None))
        self.assertEqual(p.title, "[v83] Quest log colours")
        self.assertEqual([m["author"] for m in p.messages], ["hookwright", "packetsmith", "hookwright"])
        self.assertEqual(p.messages[0]["when"], "7/22/25, 11:22 AM")
        self.assertIn("```cpp\nvoid AttachQuestListColorMod() {}\n```", p.messages[0]["text"])
        self.assertEqual(p.messages[1]["text"], "Works on Cosmic. Source: https://github.com/packetsmith/questcolor")

    def test_compact_copy(self):
        p = parse("[7/22/25, 11:22 AM] hookwright: first line\nsecond line\n[11:30 AM] WZ Fox: a reply\n")
        self.assertEqual([(m["author"], m["text"]) for m in p.messages],
                         [("hookwright", "first line\nsecond line"), ("WZ Fox", "a reply")])

    def test_name_dash_date_on_three_lines(self):
        p = parse("hookwright\n—\nToday at 11:22 AM\nhello\nWZ Fox\n—\nYesterday at 9:05 PM\nhi")
        self.assertEqual([(m["author"], m["text"]) for m in p.messages], [("hookwright", "hello"), ("WZ Fox", "hi")])

    def test_no_headers_is_one_message(self):
        p = parse("Just some notes\nwith https://github.com/a/b\n")
        self.assertEqual(len(p.messages), 1)
        self.assertIsNone(p.messages[0]["author"])

    def test_lines_that_only_look_like_headers(self):
        text = ("hookwright — Today at 11:22 AM\nFix - see 10:30 for details\n```\n[11:30 AM] x: inside code\n"
                "```\n")
        p = parse(text)
        self.assertEqual(len(p.messages), 1)
        self.assertIn("[11:30 AM] x: inside code", p.messages[0]["text"])

    def test_message_link_and_angle_brackets(self):
        p = parse(f"<https://discord.com/channels/{GUILD}/{THREAD}/1000000000000000999>\nhello")
        self.assertEqual(p.link, (str(GUILD), str(THREAD), "1000000000000000999"))


class ImportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="mod-intake-inbox-"))
        self.inbox = self.tmp / "inbox"
        self.inbox.mkdir()
        self.store = Store(self.tmp / "intake.sqlite3")
        self.logs = []

    def tearDown(self):
        self.store.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, name, text):
        path = self.inbox / name
        path.write_text(text, "utf-8")
        return path

    def run_inbox(self):
        return run_inbox(self.store, self.inbox, log=self.logs.append)

    def messages(self, tid):
        return self.store.db.execute("SELECT * FROM messages WHERE conversation_id = ? ORDER BY id", (tid,)).fetchall()

    def test_import_lines_up_with_the_discord_thread(self):
        self.write("questcolor.txt", COZY)
        stats = self.run_inbox()
        self.assertEqual((stats.files, stats.imported), (1, 1))
        t = self.store.thread(THREAD)
        self.assertEqual(t["name"], "[v83] Quest log colours")
        channel = self.store.db.execute("SELECT * FROM channels WHERE id = ?", (t["parent_id"],)).fetchone()
        self.assertEqual((channel["type"], channel["guild_id"], channel["tracked"]), (1002, GUILD, 0))
        msgs = self.messages(THREAD)
        self.assertEqual([m["id"] for m in msgs], [THREAD, THREAD + 1, THREAD + 2])
        self.assertEqual(self.store.db.execute("SELECT language FROM code_blocks WHERE message_id = ?",
                                               (THREAD,)).fetchone()[0], "cpp")
        queued = [r["url"] for r in self.store.links_to_fetch(3)]
        self.assertEqual(queued, ["https://github.com/packetsmith/questcolor"])  # 'fetch' snapshots it as usual

    def test_dossier_of_an_inbox_thread(self):
        self.write("questcolor.txt", COZY)
        self.run_inbox()
        dossiers, _notes = dossierlib.build(self.store, self.tmp)
        d = next(x for x in dossiers if x["id"] == str(THREAD))
        self.assertEqual(d["channel"]["type"], "inbox")
        self.assertEqual(d["discord_url"], f"https://discord.com/channels/{GUILD}/{THREAD}")
        self.assertEqual(d["author"]["name"], "hookwright")
        self.assertEqual([r["by_release_author"] for r in d["replies"]], [False, True])

    def test_unchanged_changed_and_deleted_files(self):
        path = self.write("questcolor.txt", COZY)
        self.run_inbox()
        self.store.save_source(THREAD + 2, "https://example.com/x", "other", "skipped")
        self.store.commit()
        self.assertEqual(self.run_inbox().unchanged, 1)

        path.write_text(COZY.split("hookwright — Today at 3:20 PM")[0], "utf-8")  # the last message cut
        stats = self.run_inbox()
        self.assertEqual(stats.imported, 1)
        self.assertEqual(len(self.messages(THREAD)), 2)
        self.assertEqual(self.store.db.execute("SELECT COUNT(*) FROM sources WHERE message_id = ?",
                                               (THREAD + 2,)).fetchone()[0], 0)

        os.remove(path)
        stats = self.run_inbox()
        self.assertEqual(stats.removed, 1)
        self.assertIsNone(self.store.thread(THREAD))
        self.assertEqual(self.messages(THREAD), [])
        self.assertEqual(self.store.inbox_files(), {})

    def test_file_without_a_link_gets_a_stable_id(self):
        self.write("notes.md", "hookwright — Today at 1:00 PM\nA Duey fix: https://pastebin.com/raw/Ab12Cd34")
        self.run_inbox()
        tid = synthetic_id("inbox:notes.md")
        self.assertEqual(self.store.thread(tid)["name"], "A Duey fix: https://pastebin.com/raw/Ab12Cd34")
        self.assertLess(tid, 2 ** 52)

    def test_two_files_for_one_thread(self):
        self.write("a.txt", f"https://discord.com/channels/{GUILD}/{THREAD}/1000000000000000801\nfirst post")
        self.write("b.txt", f"https://discord.com/channels/{GUILD}/{THREAD}/1000000000000000802\nsecond post")
        self.run_inbox()
        self.assertEqual(self.store.thread(THREAD)["name"], "first post")
        self.assertEqual(self.store.thread(1000000000000000802)["name"], "second post")

    def test_thread_already_read_from_discord_is_left_alone(self):
        self.store.save_thread({"id": THREAD, "parent_id": 900000000000000011, "name": "from the bot"})
        self.write("questcolor.txt", COZY)
        stats = self.run_inbox()
        self.assertEqual(stats.skipped, 1)
        self.assertEqual(self.store.thread(THREAD)["name"], "from the bot")
        self.assertTrue(any("already in the store from Discord" in n for n in stats.notes))

    def test_empty_file_is_skipped(self):
        self.write("empty.txt", "\n\n")
        stats = self.run_inbox()
        self.assertEqual((stats.skipped, stats.imported), (1, 0))

    def test_other_files_are_ignored(self):
        self.write("readme.pdf", "not text")
        self.assertEqual(self.run_inbox().files, 0)


class CliTest(unittest.TestCase):
    def test_inbox_command_creates_the_folder(self):
        tmp = tempfile.mkdtemp(prefix="mod-intake-inbox-cli-")
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(mod_intake.main(["--data", tmp, "inbox"]), 0)
                Path(tmp, "inbox", "questcolor.txt").write_text(COZY, "utf-8")
                self.assertEqual(mod_intake.main(["--data", tmp, "inbox"]), 0)
            self.assertIn("0 files, 0 imported", out.getvalue())
            self.assertIn("1 files, 1 imported", out.getvalue())
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
