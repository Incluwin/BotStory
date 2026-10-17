"""P3 rule tags (intake/rules.py): evidence from plain matching, and the hints drawn from it.

    python -m unittest discover -s tools/mod-intake/tests
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from intake import rules  # noqa: E402


class EvidenceTest(unittest.TestCase):
    def test_v83_addresses(self):
        text = "Hook 0x00A2C8E0 and 0x5336CA, patch 0x0098812c; not 0x00400000, 0x12345678, 0x0D000000 or 10x00A2C8E0"
        self.assertEqual(rules.addresses(text), ["0x005336CA", "0x0098812C", "0x00A2C8E0"])

    def test_version_words(self):
        self.assertEqual(rules.title_versions("[v83] Boss HP (works on v.95 and GMS 62, v83.1)"), ["v62", "v83", "v95"])
        self.assertEqual(rules.title_versions("Version 2 of the dev83 tool, v1.2"), [])

    def test_family_words(self):
        self.assertEqual(rules.family_words("Cosmic fix, also HeavenMS and MapleSolaxia; heaven ms too; Swordie"),
                         ["Cosmic", "HeavenMS", "Solaxia", "Swordie"])
        self.assertEqual(rules.family_words("cosmicray"), [])

    def test_paths_skip_urls_and_diff_prefixes(self):
        text = ("--- a/src/main/java/net/server/channel/handlers/DueyHandler.java\n"
                "+++ b/src/main/java/net/server/channel/handlers/DueyHandler.java\n"
                "see scripts/npc/9000000.js and BossHP.dll, https://github.com/x/y/blob/main/src/Other.java")
        self.assertEqual(rules.paths(text), ["src/main/java/net/server/channel/handlers/DueyHandler.java",
                                             "scripts/npc/9000000.js", "BossHP.dll"])

    def test_packages(self):
        code = ("package net.server.handlers;\nimport client.Character;\nimport tools.PacketCreator;\n"
                "import java.util.List;\n+import server.maps.MapleMap;\nimport static constants.Game.*;\n")
        self.assertEqual(rules.packages(code), ["client", "constants", "net.server.handlers", "server.maps",
                                                "tools"])
        self.assertEqual(rules.path_packages(["src/main/java/net/server/A.java", "src/client/inventory/Item.java",
                                              "docs/x.java"]), ["client.inventory", "net.server"])

    def test_identifiers(self):
        self.assertEqual(rules.identifiers("MaplePacketCreator.enableActions(); c.getPlayer(); CWvsContext"),
                         ["MaplePacketCreator", "CWvsContext"])
        self.assertEqual(rules.identifiers("PacketCreator.x(); import client.Character;"),
                         ["PacketCreator", "client.Character"])

    def test_layouts(self):
        self.assertEqual(rules.layouts(["src/main/java/a/B.java", "scripts/npc/1.js", "wz/Map.wz/0.img.xml",
                                        "sql/db.sql", "src/hook.cpp", "pom.xml"]),
                         ["maven_java", "scripts", "wz_xml", "sql", "cpp", "maven_pom"])
        self.assertEqual(rules.layouts(["src/net/server/A.java"]), ["flat_src_java"])

    def test_file_types(self):
        self.assertEqual(rules.file_type("x.dll", b"MZ\x90\x00"), "pe")
        self.assertEqual(rules.file_type("x.bin", b"PKG1\x00"), "wz")
        self.assertEqual(rules.file_type("x.wz", b"\x00\x01\x02"), "wz")
        self.assertEqual(rules.file_type("fix.patch", b"--- a/x"), "text:diff")
        self.assertEqual(rules.file_type("Npc.js", "é".encode("utf-8")[:1]), "text:javascript")  # cut character
        self.assertEqual(rules.file_type("x.zip", None), "zip")
        self.assertEqual(rules.file_type("x.java", None), "text:java")
        self.assertEqual(rules.file_type("Map.img.xml", None), "unknown")
        self.assertEqual(rules.file_type("clip.mp4", b"\x00\x00\x00\x18ftypmp42"), "video")


class HintsTest(unittest.TestCase):
    def evidence(self, **texts):
        ev = rules.Evidence()
        for text in texts.get("text", []):
            ev.add_text(text)
        for lang, code in texts.get("code", []):
            ev.add_code(lang, code)
        ev.add_files(texts.get("files", []))
        for t in texts.get("types", []):
            ev.file_types.add(t)
        return ev.to_dict(texts.get("owners"))

    def test_client_hook(self):
        ev = self.evidence(text=["Hooks 0x005336CA"], types=["pe"], owners={"0x005336CA": {"mob_hp_tag"}})
        h = rules.hints("[v83] HP bar", ev)
        self.assertEqual(h["kinds"], ["client_hook"])
        self.assertEqual(ev["addresses_our_client_also_uses"], [{"address": "0x005336CA", "our_modules":
                                                                 ["mob_hp_tag"]}])
        self.assertIn(("v83", "uses addresses our v83 client hooks"), h["versions"])
        self.assertEqual(h["why"], ["client hook: 1 v83-range address(es), DLL/EXE attached"])

    def test_server_kinds_and_family(self):
        ev = self.evidence(code=[("java", "import client.MapleCharacter;\nMaplePacketCreator.x();")],
                           files=["src/net/server/A.java", "scripts/npc/1.js", "sql/fix.sql"])
        h = rules.hints("Skill fixes", ev)
        self.assertEqual(h["kinds"], ["server_java", "server_script", "database"])
        self.assertEqual(sorted({f for f, _ in h["families"]}), ["heavenms_line"])

    def test_versions_from_title_win_over_the_body(self):
        ev = self.evidence(text=["works on v83 too"])
        self.assertEqual(rules.hints("[v95] Damage skin", ev)["versions"], [("other", "title says v95")])
        self.assertEqual(rules.hints("Damage skin", ev)["versions"], [("v83", "post says v83")])

    def test_nothing_found(self):
        h = rules.hints("Question about drops", self.evidence(text=["how do I add drops?"]))
        self.assertEqual((h["kinds"], h["versions"], h["families"]), ([], [], []))


if __name__ == "__main__":
    unittest.main()
