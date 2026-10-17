"""The YAML subset the catalog files use (intake/yamlish.py).

    python -m unittest discover -s tools/mod-intake/tests
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from intake import yamlish  # noqa: E402


class RoundTripTest(unittest.TestCase):
    def roundtrip(self, value):
        text = yamlish.dump(value, header="a header\n\nsecond line")
        self.assertEqual(yamlish.load(text), value, text)
        return text

    def test_catalog_shapes(self):
        text = self.roundtrip({"summary": {"entries": 2, "status": {"candidate": 1}},
                               "mods": [{"id": "boss_hp", "thread": "1000000000000000100", "review": True,
                                         "reason": None, "p": {"v83": 0.93}, "includes": ["client_hook", "tool"],
                                         "sources": [{"url": "https://github.com/a/b", "pin": "ab" * 20}],
                                         "empty": [], "none": {}}]})
        self.assertIn('thread: "1000000000000000100"', text)   # numeric-looking text stays text
        self.assertIn("includes: [client_hook, tool]", text)
        self.assertTrue(text.startswith("# a header\n#\n# second line\n"))

    def test_scalars_that_need_quotes(self):
        values = ["yes", "no", "null", "true", "123", "1.5", "0x00A2C8E0", "- dash", "a: b", "x #y", " pad",
                  "[v83] t", "{x}", "it's", 'say "hi"', "multi\nline", "tab\there", "é ✓ 😀", "", "a,b"]
        self.roundtrip({"values": values, "flow": ["a,b", "c"]})

    def test_nested_lists(self):
        self.roundtrip({"rows": [[1, 2], [], [{"a": 1}]], "deep": {"x": {"y": {"z": [None, False, -3]}}}})

    def test_long_scalar_lists_go_block_style(self):
        text = self.roundtrip({"addresses": ["0x00%06X" % (0x400000 + i) for i in range(30)]})
        self.assertIn('\n  - "0x00400000"', text)


class HandEditTest(unittest.TestCase):
    def test_what_people_write(self):
        text = """# corrections
overrides:
  1000000000000000200:      # v95 one
    status: skipped
    reason: v95 only: not ours   # trailing comment
    note: 'it''s # not a comment'
    kinds: [client_wz, "a, b", 3]
    map: {a: 1, b: x}
    text: |
      line one # kept
      line two
    folded: >-
      one
      two
    after: ~
  "300":
  - plain
  - key: 1
    other: 2
list_at_same_indent:
- a
- b
"""
        self.assertEqual(yamlish.load(text), {
            "overrides": {
                "1000000000000000200": {"status": "skipped", "reason": "v95 only: not ours",
                                        "note": "it's # not a comment", "kinds": ["client_wz", "a, b", 3],
                                        "map": {"a": 1, "b": "x"}, "text": "line one # kept\nline two\n",
                                        "folded": "one two", "after": None},
                "300": ["plain", {"key": 1, "other": 2}]},
            "list_at_same_indent": ["a", "b"]})

    def test_errors_name_the_line(self):
        for text, fragment in (("a: 1\na: 2\n", "duplicate key"), ("a: [1, 2\n", "same line"),
                               ("a: &anchor x\n", "unsupported"), ("a:\n\t- 1\n", "tab"),
                               ("a: 1\n  b: 2\n", "indentation"), ('a: "open\n', "unterminated"),
                               ("---\na: 1\n---\nb: 2\n", "one document")):
            with self.assertRaises(yamlish.YamlError, msg=text) as ctx:
                yamlish.load(text)
            self.assertIn(fragment, str(ctx.exception))
            self.assertIn("line", str(ctx.exception))

    def test_empty_file(self):
        self.assertIsNone(yamlish.load("# nothing\n"))
        self.assertEqual(yamlish.load("overrides: {}\n"), {"overrides": {}})


if __name__ == "__main__":
    unittest.main()
