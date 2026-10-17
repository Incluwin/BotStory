"""Sync against the fake Discord API (tests/fake_discord.py, fixtures/guild.json).

    python -m unittest discover -s tools/mod-intake/tests
"""
import hashlib
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from fake_discord import TOKEN, FakeDiscord  # noqa: E402
from intake import config as configlib  # noqa: E402
from intake.api import DiscordClient, Unauthorized  # noqa: E402
from intake.store import Store  # noqa: E402
from intake.sync import IntakeError, run_sync, select_tracked  # noqa: E402

CLIENT_RELEASES, SERVER_RELEASES, PATCHES, STAFF, GENERAL = (
    "900000000000000011", "900000000000000012", "900000000000000013", "900000000000000014", "900000000000000021")
BOSS_HP, DAMAGE_SKIN, DUEY, MOB_HP = ("1000000000000000100", "1000000000000000200", "1000000000000000300",
                                      "1000000000000000500")
ALL_THREADS = 6
ALL_MESSAGES = 12  # tracked: 3 + 1 + 1 (#client-releases) + 2 + 1 (#server-releases) + 2 (#patches) + 2 (its thread)


class SyncTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mod-intake-test-")
        self.fake = FakeDiscord()
        self.sleeps = []
        self.store = Store(os.path.join(self.tmp, "intake.sqlite3"))
        self.attachments = os.path.join(self.tmp, "attachments")
        self.logs = []

    def tearDown(self):
        self.store.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def client(self, token=TOKEN):
        return DiscordClient(token, transport=self.fake, sleep=self.sleeps.append)

    def sync(self, page_size=100, **kwargs):
        self.fake.log.clear()
        return run_sync(self.client(), self.store, self.fake.guild_id, self.attachments, page_size=page_size,
                        log=self.logs.append, **kwargs)

    def count(self, sql, *args):
        return self.store.db.execute(sql, args).fetchone()[0]


class FirstSyncTest(SyncTestBase):
    def test_reads_release_channels_threads_and_messages(self):
        stats = self.sync()
        self.assertEqual(stats.threads, ALL_THREADS)
        self.assertEqual(stats.new_threads, ALL_THREADS)
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), ALL_THREADS)
        self.assertEqual(self.count("SELECT COUNT(*) FROM messages"), ALL_MESSAGES)
        self.assertEqual(stats.new_messages, ALL_MESSAGES)
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads WHERE archived = 1"), 3)
        self.assertNotIn(GENERAL, self.fake.message_listings(), "untracked channel was read")
        tracked = {r[0] for r in self.store.db.execute("SELECT id FROM channels WHERE tracked = 1")}
        self.assertEqual(tracked, {int(CLIENT_RELEASES), int(SERVER_RELEASES), int(PATCHES), int(STAFF)})

    def test_forbidden_channel_is_noted_and_skipped(self):
        stats = self.sync()
        access = self.count("SELECT access FROM channels WHERE id = ?", int(STAFF))
        self.assertEqual(access, "forbidden")
        self.assertTrue(any("staff-releases" in n for n in stats.notes))

    def test_message_fields(self):
        self.sync()
        row = self.store.db.execute("SELECT * FROM messages WHERE id = 1000000000000000120").fetchone()
        self.assertEqual(row["conversation_id"], int(BOSS_HP))
        self.assertEqual(row["author_name"], "Hookwright")
        self.assertEqual(row["reply_to_id"], 1000000000000000110)
        bot = self.store.db.execute("SELECT author_bot FROM messages WHERE id = 1000000000000000610").fetchone()
        self.assertEqual(bot[0], 1)

    def test_links_are_classified(self):
        self.sync()
        kinds = dict(self.store.db.execute("SELECT url, kind FROM links").fetchall())
        self.assertEqual(kinds["https://github.com/hookwright/v83-bosshp"], "github_repo")
        self.assertEqual(kinds["https://github.com/packetsmith/Cosmic/pull/12"], "github_pull")
        self.assertEqual(kinds["https://pastebin.com/raw/Ab12Cd34"], "paste")
        self.assertEqual(kinds["https://github.com/wzfox/heavenms-skillfixes.git"], "github_repo")
        self.assertEqual(
            kinds["https://raw.githubusercontent.com/wzfox/heavenms-skillfixes/main/README.md"], "github_raw")
        self.assertEqual(kinds["https://mega.nz/file/abcd#key"], "download")
        self.assertIn("discord_message", kinds.values())

    def test_attachments_land_in_quarantine_by_hash(self):
        stats = self.sync()
        self.assertEqual(stats.stored_attachments, 4)
        body = b"MZ fake dll bytes one"
        sha = hashlib.sha256(body).hexdigest()
        path = Path(self.attachments) / sha[:2] / sha
        self.assertEqual(path.read_bytes(), body)
        self.assertEqual(path.suffix, "", "stored files must not keep an executable extension")
        # BossHP.dll and BossHP-copy.dll have the same bytes: one file, two rows
        self.assertEqual(self.count("SELECT COUNT(*) FROM attachments WHERE sha256 = ?", sha), 2)
        files = [p for p in Path(self.attachments).rglob("*") if p.is_file()]
        self.assertEqual(len(files), 3)
        self.assertFalse([p for p in files if p.name.endswith(".part")])

    def test_paging_with_tiny_pages_reads_the_same(self):
        self.sync(page_size=1)
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), ALL_THREADS)
        self.assertEqual(self.count("SELECT COUNT(*) FROM messages"), ALL_MESSAGES)
        archived_calls = self.fake.api_calls(f"/channels/{CLIENT_RELEASES}/threads/archived/public")
        self.assertEqual(len(archived_calls), 2)  # two archived threads, one per page
        self.assertEqual(archived_calls[1][1]["before"], "2025-03-01T00:00:00+00:00")

    def test_search_index(self):
        self.sync()
        hits, threads = self.store.search("Cash Shop")
        self.assertEqual([h["id"] for h in hits], [1000000000000000110])
        hits, _ = self.store.search("BossHP.dll")
        self.assertEqual([h["id"] for h in hits], [int(BOSS_HP)])
        _, threads = self.store.search("Duey")
        self.assertEqual([t["id"] for t in threads], [int(DUEY)])


class IncrementalSyncTest(SyncTestBase):
    def test_nothing_new_reads_no_message_lists(self):
        self.sync()
        stats = self.sync()
        self.assertEqual(self.fake.message_listings(), [])
        self.assertEqual((stats.new_messages, stats.edited_messages, stats.new_threads), (0, 0, 0))
        starters = [b for b, _q in self.fake.api_calls() if "/messages/" in b]
        self.assertEqual(len(starters), ALL_THREADS)
        self.assertEqual(stats.stored_attachments, 0)

    def test_new_reply_and_new_thread(self):
        self.sync()
        reply = self.fake.add_message(DAMAGE_SKIN, "hookwright", "Works on v83 with https://github.com/x/y/pull/3")
        thread = self.fake.add_thread(SERVER_RELEASES, "[v83] New quest fix", "packetsmith", "Quest fix here.")
        stats = self.sync()
        self.assertEqual(sorted(self.fake.message_listings()), sorted([DAMAGE_SKIN, thread]))
        self.assertEqual(stats.new_messages, 2)
        self.assertEqual(stats.new_threads, 1)
        after = [q["after"] for b, q in self.fake.api_calls(f"/channels/{DAMAGE_SKIN}/messages") if "after" in q]
        self.assertEqual(after, [DAMAGE_SKIN])  # resumed after the newest message already stored
        self.assertEqual(self.count("SELECT archived FROM threads WHERE id = ?", int(DAMAGE_SKIN)), 0)
        self.assertEqual(self.count("SELECT kind FROM links WHERE message_id = ?", int(reply)), "github_pull")

    def test_edited_starter_keeps_the_old_text(self):
        self.sync()
        self.fake.edit_message(MOB_HP, MOB_HP, "Mob HP percentage tag v2: https://github.com/hookwright/mobhp")
        stats = self.sync()
        self.assertEqual(stats.edited_messages, 1)
        row = self.store.db.execute("SELECT content, edited_at FROM messages WHERE id = ?", (int(MOB_HP),)).fetchone()
        self.assertIn("v2", row["content"])
        self.assertEqual(row["edited_at"], "2025-07-01T12:00:00+00:00")
        old = self.store.db.execute("SELECT content, edited_at FROM message_revisions WHERE message_id = ?",
                                    (int(MOB_HP),)).fetchall()
        self.assertEqual(len(old), 1)
        self.assertIn("```cpp", old[0]["content"])
        self.assertIsNone(old[0]["edited_at"])
        self.assertEqual(self.count("SELECT kind FROM links WHERE message_id = ?", int(MOB_HP)), "github_repo")
        hits, _ = self.store.search("mobhp")
        self.assertEqual([h["id"] for h in hits], [int(MOB_HP)])

    def test_deleted_last_message_is_not_read_again(self):
        self.sync()
        gone = self.fake.add_message(MOB_HP, "wzfox", "posted then deleted", unarchive=False)
        self.fake.messages[MOB_HP] = [m for m in self.fake.messages[MOB_HP] if m["id"] != gone]
        self.fake._thread_obj = self._stale_last_message(self.fake._thread_obj, MOB_HP, gone)
        self.sync()
        self.assertEqual(self.fake.message_listings(), [MOB_HP])
        self.sync()
        self.assertEqual(self.fake.message_listings(), [])

    @staticmethod
    def _stale_last_message(thread_obj, thread_id, message_id):
        """Discord keeps last_message_id on a thread after that message is deleted."""
        def patched(t):
            out = thread_obj(t)
            if out["id"] == thread_id:
                out["last_message_id"] = message_id
            return out
        return patched

    def test_no_starter_refresh_when_disabled(self):
        self.sync()
        self.sync(refresh_starters=False)
        self.assertEqual([b for b, _q in self.fake.api_calls() if "/messages/" in b], [])

    def test_blank_messages_never_overwrite_stored_text(self):
        self.sync()
        self.fake.content_intent = False
        self.sync()
        content = self.count("SELECT content FROM messages WHERE id = ?", int(BOSS_HP))
        self.assertIn("Boss HP bar", content)
        self.assertEqual(self.count("SELECT COUNT(*) FROM message_revisions"), 0)


class AttachmentTest(SyncTestBase):
    def test_expired_link_is_refreshed_from_the_message(self):
        self.sync(fetch_attachments=False)
        self.fake.now += 1000  # every stored signed URL is now past its expiry
        stats = self.sync()
        self.assertEqual(stats.stored_attachments, 4)
        self.assertEqual(stats.failed_attachments, 0)

    def test_too_large_is_skipped(self):
        stats = self.sync(max_attachment_bytes=15)
        self.assertEqual(stats.skipped_attachments, 3)  # 21-byte DLLs and the patch; the 13-byte .wz fits
        self.assertEqual(self.count("SELECT COUNT(*) FROM attachments WHERE status = 'too_large'"), 3)
        self.assertEqual(stats.stored_attachments, 1)

    def test_failed_download_is_retried_next_sync(self):
        self.sync(fetch_attachments=False)
        saved = dict(self.fake.files)
        self.fake.files.clear()
        stats = self.sync()
        self.assertEqual(stats.failed_attachments, 4)
        self.fake.files.update(saved)
        stats = self.sync()
        self.assertEqual(stats.stored_attachments, 4)

    def test_only_discord_cdn_urls_are_fetched(self):
        self.sync(fetch_attachments=False)
        self.store.db.execute("UPDATE attachments SET url = 'https://evil.example/x.dll'")
        self.store.commit()
        self.fake.files.clear()  # the re-read message has no attachment bytes either
        self.sync()
        self.assertFalse([b for _m, b, _q in self.fake.log if "evil.example" in b])


class ErrorsTest(SyncTestBase):
    def test_bad_token(self):
        with self.assertRaises(Unauthorized):
            run_sync(self.client("wrong"), self.store, self.fake.guild_id, self.attachments, log=self.logs.append)
        run = self.store.db.execute("SELECT status FROM sync_runs").fetchone()
        self.assertTrue(run["status"].startswith("failed"))

    def test_wrong_guild(self):
        with self.assertRaises(IntakeError):
            run_sync(self.client(), self.store, "123", self.attachments, log=self.logs.append)

    def test_rate_limit_waits_and_retries(self):
        self.fake.rate_limited["/threads/active"] = 2
        self.sync()
        self.assertEqual(self.sleeps[:2], [0.25, 0.25])
        self.assertEqual(self.count("SELECT COUNT(*) FROM threads"), ALL_THREADS)

    def test_empty_bucket_waits_for_reset(self):
        self.fake.bucket_empty["/guilds/"] = 1.5
        self.sync()
        self.assertIn(1.5, self.sleeps)

    def test_missing_content_intent_is_reported(self):
        self.fake.content_intent = False
        stats = self.sync()
        self.assertTrue(any("Message Content Intent" in n for n in stats.notes))


class SelectTrackedTest(unittest.TestCase):
    def setUp(self):
        fake = FakeDiscord()
        self.channels = [fake._channel_obj(c) for c in fake.channels.values()]

    def test_category_id_means_its_channels(self):
        self.assertEqual(select_tracked(self.channels, [900000000000000020]), {int(GENERAL)})

    def test_explicit_channel(self):
        self.assertEqual(select_tracked(self.channels, [int(CLIENT_RELEASES)]), {int(CLIENT_RELEASES)})

    def test_unknown_or_voice_channel_is_an_error(self):
        with self.assertRaises(IntakeError):
            select_tracked(self.channels, [42])
        with self.assertRaises(IntakeError):
            select_tracked(self.channels, [900000000000000022])


class ConfigTest(unittest.TestCase):
    def test_env_file_and_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, ".env").write_text("# bot\nDISCORD_BOT_TOKEN='abc'\nDISCORD_GUILD_ID=1\n"
                                         "INTAKE_CHANNELS=10, 11\nINTAKE_MAX_ATTACHMENT_MB=0.5\n", "utf-8")
            cfg = configlib.load(tmp, environ={"DISCORD_GUILD_ID": "2"})
            self.assertEqual((cfg.token, cfg.guild_id, cfg.channel_ids), ("abc", "2", (10, 11)))
            self.assertEqual(cfg.max_attachment_bytes, 512 * 1024)

    def test_env_file_with_bom(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, ".env").write_bytes("DISCORD_BOT_TOKEN=abc\r\n".encode("utf-8-sig"))
            self.assertEqual(configlib.load(tmp, environ={}).token, "abc")

    def test_missing_token_is_explained(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(IntakeError, "DISCORD_BOT_TOKEN"):
                configlib.load(tmp, environ={}).require_discord()

    def test_data_dir_inside_a_repo_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.mkdir(os.path.join(tmp, ".git"))
            with self.assertRaisesRegex(IntakeError, "never be committed"):
                configlib.load(os.path.join(tmp, "data"), environ={})


if __name__ == "__main__":
    unittest.main()
