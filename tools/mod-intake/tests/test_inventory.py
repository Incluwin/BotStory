"""Our current mods (intake/inventory.py): kaentake's feature_list.h, kept descriptions, the committed file.

    python -m unittest discover -s tools/mod-intake/tests
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from git_fixture import git, have_git, isolated_env  # noqa: E402
from intake import fetch as fetchlib  # noqa: E402
from intake import inventory as invlib  # noqa: E402

FEATURE_LIST = r'''#define KAENTAKE_FEATURES(X) \
    X(client_bypass,  "Client bypass",            1, FEATURE_CORE,                          none,      AttachClientBypass) \
    X(mob_hp_tag,     "Mob HP bar with numbers",  1, FEATURE_LIVE,                          none,      AttachMobHpTagMod) \
    X(boss_hp,        "Boss HP % under the bar",  0, 0,                                     mob_hp_tag, AttachBossHPMod) \
    X(free_addy,      "Gameplay patches",         1, FEATURE_SERVER_GATED | FEATURE_LIVE,   none,      AttachFreeAddy) \
    X(version_guard,  "v83 client version guard", 1, FEATURE_CORE | FEATURE_EARLY,          none,      v83::EnsureClientVersionOrExit)
'''
CATALOG = Path(__file__).resolve().parent.parent / "catalog"


class ParseTest(unittest.TestCase):
    def test_feature_lines(self):
        modules = invlib.parse_feature_list(FEATURE_LIST)
        self.assertEqual([m["id"] for m in modules], ["client_bypass", "mob_hp_tag", "boss_hp", "free_addy",
                                                      "version_guard"])
        self.assertEqual(modules[2], {"id": "boss_hp", "title": "Boss HP % under the bar", "default": False,
                                      "flags": [], "requires": "mob_hp_tag", "attach": "AttachBossHPMod"})
        self.assertEqual(modules[3]["flags"], ["server_gated", "live"])
        self.assertEqual(modules[4]["attach"], "EnsureClientVersionOrExit")
        with self.assertRaises(invlib.InventoryError):
            invlib.parse_feature_list("nothing here")

    def test_committed_inventory_matches_its_source(self):
        inv = invlib.load(CATALOG / "inventory.yaml")
        ids = [e["id"] for e in inv["client"]]
        self.assertIn("mob_hp_tag", ids)
        self.assertIn("crew_window", ids)
        self.assertEqual(len(ids), len(set(ids)))
        for e in inv["client"]:
            self.assertTrue(e["description"], e["id"])
            self.assertIsNotNone(e["source"], e["id"])
        for e in inv["server"]:
            self.assertIn(e["side"], ("server", "tool", "data"), e["id"])
            self.assertIn("server_mod", e)   # room for the P5 server.mods id
            self.assertTrue(e["description"], e["id"])
        all_ids = [e["id"] for e in invlib.entries(inv)]
        self.assertEqual(len(all_ids), len(set(all_ids)))
        self.assertIn("mob_hp_tag", invlib.address_owners(inv)["0x005336CA"])


@unittest.skipUnless(have_git(), "git is not on PATH")
class RegenerateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="mod-intake-inventory-"))
        self.env = isolated_env(self.tmp)
        repo = self.tmp / "kaentake"
        git("init", "--quiet", str(repo), env=self.env)
        (repo / "src").mkdir()
        (repo / "src" / "feature_list.h").write_text(FEATURE_LIST, "utf-8")
        (repo / "src" / "mobhptag.cpp").write_text(
            "MEMBER_HOOK(void, 0x005336CA, ShowMobHPTag)\nvoid AttachMobHpTagMod() {\n PatchCall(0x00533AFD);\n}\n",
            "utf-8")
        (repo / "src" / "BossHP.cpp").write_text("void AttachBossHPMod()\n{\n  Hook(0x005336CA); }\n", "utf-8")
        (repo / "src" / "addies.cpp").write_text("static void Helper() {}\nvoid AttachFreeAddy() { 0x00400000; }\n",
                                                 "utf-8")
        git("-C", str(repo), "add", "-A", env=self.env)
        git("-C", str(repo), "commit", "--quiet", "-m", "modules", env=self.env)
        git("-C", str(repo), "branch", "feat/feature-toggles", env=self.env)
        self.repo = repo
        self.inventory = self.tmp / "inventory.yaml"
        self.inventory.write_text("client:\n  - id: mob_hp_tag\n    description: HP bars over monsters.\n"
                                  "server:\n  - id: companions\n    side: server\n    description: Bots.\n"
                                  "    server_mod: null\n", "utf-8")

    def tearDown(self):
        fetchlib._rmtree(self.tmp)

    def test_regenerate_keeps_descriptions_and_the_server_part(self):
        source = invlib.KaentakeSource(self.repo, "feat/feature-toggles")
        data = invlib.regenerate(self.inventory, source)
        again = invlib.load(self.inventory)
        self.assertEqual(again, data)
        by_id = {e["id"]: e for e in again["client"]}
        self.assertEqual(by_id["mob_hp_tag"]["description"], "HP bars over monsters.")
        self.assertEqual(by_id["boss_hp"]["description"], "Boss HP % under the bar")  # the title until written
        self.assertEqual(by_id["mob_hp_tag"]["addresses"], ["0x005336CA", "0x00533AFD"])
        self.assertEqual(by_id["mob_hp_tag"]["source"], "src/mobhptag.cpp")
        self.assertEqual(by_id["boss_hp"]["source"], "src/BossHP.cpp")
        self.assertEqual(by_id["free_addy"]["addresses"], [])      # the image base is no hook
        self.assertIsNone(by_id["client_bypass"]["source"])        # no file defines it in this repo
        self.assertEqual(again["server"], [{"id": "companions", "side": "server", "description": "Bots.",
                                            "server_mod": None}])
        self.assertEqual(again["client_source"]["commit"], source.commit[:12])
        self.assertEqual(invlib.address_owners(again)["0x005336CA"], {"mob_hp_tag", "boss_hp"})

    def test_work_tree_and_bad_repo(self):
        source = invlib.KaentakeSource(self.repo, None)
        self.assertEqual(len(invlib.scan_client(source)), 5)
        with self.assertRaises(invlib.InventoryError):
            invlib.KaentakeSource(self.tmp / "nowhere")
        with self.assertRaises(invlib.InventoryError):
            invlib.KaentakeSource(self.repo, "no-such-branch")


if __name__ == "__main__":
    unittest.main()
