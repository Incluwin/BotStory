"""The command line, end to end against the fake Discord API.

    python -m unittest discover -s tools/mod-intake/tests
"""
import contextlib
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import mod_intake  # noqa: E402
from fake_discord import TOKEN, FakeDiscord  # noqa: E402


class CliTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mod-intake-cli-")
        self.fake = FakeDiscord()
        Path(self.tmp, ".env").write_text(f"DISCORD_BOT_TOKEN={TOKEN}\nDISCORD_GUILD_ID={self.fake.guild_id}\n",
                                          "utf-8")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = mod_intake.main(["--data", self.tmp, *args], transport=self.fake, sleep=lambda s: None)
        return code, out.getvalue(), err.getvalue()

    def test_status_before_sync(self):
        code, out, _ = self.run_cli("status")
        self.assertEqual(code, 1)
        self.assertIn("run 'sync' first", out)

    def test_channels_marks_tracked(self):
        code, out, _ = self.run_cli("channels")
        self.assertEqual(code, 0)
        self.assertIn("*     client-releases  [forum]", out)
        self.assertIn("      general  [text]", out)

    def test_sync_status_search_thread(self):
        code, out, _ = self.run_cli("sync")
        self.assertEqual(code, 0, out)
        self.assertIn("Synced 6 threads (6 new): 12 new messages", out)

        code, out, _ = self.run_cli("status")
        self.assertEqual(code, 0)
        self.assertIn("Threads: 6 (3 archived)", out)
        self.assertIn("access=forbidden", out)
        self.assertIn("4 stored", out)
        self.assertIn("github_repo", out)

        code, out, _ = self.run_cli("search", "Cash", "Shop")
        self.assertIn("[v83] Boss HP bar hook for kaentake", out)
        self.assertIn("[Cash] [Shop]", out)

        code, out, _ = self.run_cli("thread", "Duey")
        self.assertEqual(code, 0)
        self.assertIn("== [Cosmic] Duey fix + Fredrick", out)
        self.assertIn("[file] duey_fix.patch", out)
        self.assertIn("[github_pull] https://github.com/packetsmith/Cosmic/pull/12", out)

        code, out, _ = self.run_cli("thread", "[v")
        self.assertEqual(code, 1)
        self.assertIn("Several match", out)

    def test_bad_token_exit_code(self):
        Path(self.tmp, ".env").write_text(f"DISCORD_BOT_TOKEN=nope\nDISCORD_GUILD_ID={self.fake.guild_id}\n", "utf-8")
        code, _, err = self.run_cli("sync")
        self.assertEqual(code, 2)
        self.assertIn("refused the bot token", err)
        self.assertNotIn("nope", err)


if __name__ == "__main__":
    unittest.main()
