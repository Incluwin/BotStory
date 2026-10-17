"""Link finding and classification.

    python -m unittest discover -s tools/mod-intake/tests
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from intake.links import classify, extract, find_urls  # noqa: E402


class FindUrlsTest(unittest.TestCase):
    def test_strips_markdown_brackets_and_trailing_punctuation(self):
        text = ("See https://github.com/a/b, then [docs](https://example.com/x) and "
                "<https://pastebin.com/raw/Q1>. Also (https://gist.github.com/u/abc).")
        self.assertEqual(find_urls(text), ["https://github.com/a/b", "https://example.com/x",
                                           "https://pastebin.com/raw/Q1", "https://gist.github.com/u/abc"])

    def test_keeps_compare_dots_and_fragments(self):
        self.assertEqual(find_urls("https://github.com/o/r/compare/a1...b2 https://mega.nz/file/x#key!"),
                         ["https://github.com/o/r/compare/a1...b2", "https://mega.nz/file/x#key"])

    def test_no_duplicates(self):
        self.assertEqual(find_urls("https://a.io/x https://a.io/x"), ["https://a.io/x"])


class ClassifyTest(unittest.TestCase):
    def check(self, url, kind, owner=None, repo=None, ref=None, path=None):
        link = classify(url)
        self.assertEqual((link.kind, link.owner, link.repo, link.ref, link.path), (kind, owner, repo, ref, path), url)

    def test_github(self):
        self.check("https://github.com/hookwright/v83-bosshp", "github_repo", "hookwright", "v83-bosshp")
        self.check("https://www.github.com/o/r.git", "github_repo", "o", "r")
        self.check("https://github.com/o/r/commit/3f2a9c1", "github_commit", "o", "r", "3f2a9c1")
        self.check("https://github.com/o/r/pull/12", "github_pull", "o", "r", None, "12")
        self.check("https://github.com/o/r/pull/12/commits/abc", "github_pull", "o", "r", "abc", "12")
        self.check("https://github.com/o/r/blob/master/src/A.java#L120", "github_blob", "o", "r", "master",
                   "src/A.java")
        self.check("https://github.com/o/r/tree/dev", "github_tree", "o", "r", "dev")
        self.check("https://github.com/o/r/compare/a...b", "github_compare", "o", "r", "a...b")
        self.check("https://github.com/o/r/releases/tag/v1.2", "github_release", "o", "r", "v1.2")
        self.check("https://github.com/o/r/releases/download/v1/x.dll", "github_release", "o", "r", "v1", "x.dll")
        self.check("https://github.com/o/r/issues/4", "github_issue", "o", "r", None, "4")
        self.check("https://github.com/o/r/wiki/Setup", "github_repo", "o", "r", None, "wiki/Setup")
        self.check("https://github.com/orgs/o/repositories", "github_other", "orgs")

    def test_other_hosts(self):
        self.check("https://gist.github.com/packetsmith/0a1b", "gist", "packetsmith", None, "0a1b")
        self.check("https://raw.githubusercontent.com/o/r/main/dir/README.md", "github_raw", "o", "r", "main",
                   "dir/README.md")
        self.check("https://pastebin.com/raw/Ab12", "paste", None, None, "Ab12")
        self.check("https://gitlab.com/o/r.git", "git_other", "o", "r")
        self.check("https://discord.com/channels/1/2/3", "discord_message", path="1/2/3")
        self.assertEqual(classify("https://cdn.discordapp.com/attachments/1/2/x.dll").kind, "discord_attachment")
        self.assertEqual(classify("https://www.mediafire.com/file/x").kind, "download")
        self.assertEqual(classify("https://forum.ragezone.com/threads/1").kind, "other")


class ExtractTest(unittest.TestCase):
    def test_embed_urls_follow_text_urls_once(self):
        links = extract("https://github.com/o/r", [{"url": "https://github.com/o/r"}, {"url": "https://x.io/a"},
                                                   {"title": "no url"}])
        self.assertEqual([link.url for link in links], ["https://github.com/o/r", "https://x.io/a"])


if __name__ == "__main__":
    unittest.main()
