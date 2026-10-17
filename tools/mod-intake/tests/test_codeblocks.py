"""Code blocks in messages: parsing, language from the fence or a guess, and the store's code_blocks table.

    python -m unittest discover -s tools/mod-intake/tests
"""
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from intake.codeblocks import extract, guess  # noqa: E402
from intake.store import SCHEMA_VERSION, Store  # noqa: E402


class ExtractTest(unittest.TestCase):
    def test_fence_language_and_order(self):
        text = ("Crashes in Cash Shop, fix:\n```cpp\nif (CWvsContext::GetInstance()->m_pStage == nullptr) return;\n"
                "```\nand the SQL:\n```SQL\nUPDATE characters SET meso = 0;\n```")
        blocks = extract(text)
        self.assertEqual([(b.index, b.language, b.guessed) for b in blocks], [(0, "cpp", False), (1, "sql", False)])
        self.assertEqual(blocks[0].text, "if (CWvsContext::GetInstance()->m_pStage == nullptr) return;")

    def test_aliases(self):
        self.assertEqual(extract("```c++\nint x;\n```")[0].language, "cpp")
        self.assertEqual(extract("```js\nvar a = 1;\n```")[0].language, "javascript")
        self.assertEqual(extract("```patch\n+a\n```")[0].language, "diff")

    def test_no_language_is_guessed(self):
        block = extract("```\n    public void handle(Client c) {\n        c.getPlayer().dropMessage(5, \"hi\");\n"
                        "    }\n```")[0]
        self.assertEqual((block.language, block.guessed), ("java", True))
        self.assertTrue(block.text.startswith("    public void"), "indentation kept")

    def test_one_line_fence_has_no_language(self):
        block = extract("try ```cm.sendOk(\"hi\");``` in the NPC")[0]
        self.assertEqual((block.language, block.guessed, block.text), ("javascript", True, 'cm.sendOk("hi");'))

    def test_unclosed_and_empty_fences_are_left_out(self):
        self.assertEqual(extract("```cpp\nint x;"), [])
        self.assertEqual(extract("``````  ```\n\n```"), [])
        self.assertEqual(extract(None), [])

    def test_guesses(self):
        cases = {
            "--- a/src/A.java\n+++ b/src/A.java\n@@ -1,2 +1,2 @@\n-a\n+b": "diff",
            '<imgdir name="8800000">\n  <int name="hp" value="1"/>\n</imgdir>': "xml",
            "UPDATE characters SET meso = 0 WHERE id = 1;": "sql",
            "var status = -1;\nfunction start() {\n    cm.sendOk(\"Hello\");\n}": "javascript",
            "package server.life;\nimport java.util.List;": "java",
            "#include <windows.h>\nDWORD WINAPI Attach(LPVOID p) { return 0; }": "cpp",
            "mov eax, [ecx+0x10]\ncall 0x00A2C8E0": "asm",
            "def patch(path):\n    return path": "python",
            '{"mods": [1, 2]}': "json",
            "[features]\nbosshp=1": "ini",
            "just some words about the fix": "text",
        }
        for text, language in cases.items():
            self.assertEqual(guess(text), language, text)


class StoreCodeBlocksTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mod-intake-code-")
        self.path = os.path.join(self.tmp, "intake.sqlite3")
        self.store = Store(self.path)

    def tearDown(self):
        self.store.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def save(self, content, edited=None):
        self.store.save_message(1, {"id": "10", "content": content, "edited_timestamp": edited, "author": {"id": "1"}})
        self.store.commit()

    def rows(self):
        return [tuple(r) for r in self.store.db.execute(
            "SELECT message_id, idx, language, guessed, text FROM code_blocks ORDER BY message_id, idx")]

    def test_saved_with_the_message_and_replaced_on_edit(self):
        self.save("```cpp\nint a;\n```\n```\nSELECT * FROM mobs;\n```")
        self.assertEqual(self.rows(), [(10, 0, "cpp", 0, "int a;"), (10, 1, "sql", 1, "SELECT * FROM mobs;")])
        self.save("now only ```java\nint b;\n```", edited="2025-06-02T00:00:00+00:00")
        self.assertEqual(self.rows(), [(10, 0, "java", 0, "int b;")])

    def test_p1_store_is_backfilled_on_upgrade(self):
        self.save("```cpp\nint a;\n```")
        self.store.close()
        db = sqlite3.connect(self.path)  # make it look like a P1 store: no code blocks yet, schema 1
        db.execute("DELETE FROM code_blocks")
        db.execute("UPDATE meta SET value = '1' WHERE key = 'schema_version'")
        db.commit()
        db.close()
        self.store = Store(self.path)
        self.assertEqual(self.rows(), [(10, 0, "cpp", 0, "int a;")])
        self.assertEqual(self.store.meta("schema_version"), str(SCHEMA_VERSION))


if __name__ == "__main__":
    unittest.main()
