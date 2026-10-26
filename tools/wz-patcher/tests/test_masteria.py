"""Cryssea's Custom.wz look (masteria.py), on throwaway WZ files in a temporary folder.

    python -m unittest discover -s tools/wz-patcher/tests
"""
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import botstory_wz  # noqa: E402
import masteria  # noqa: E402
from test_wzlib import _FakeSource, img_entry, prop  # noqa: E402
from wzlib.reader import WzFile  # noqa: E402
from wzlib.types import PropNode, WzEntry, PROP_INT  # noqa: E402
from wzlib.writer import save_wz_file  # noqa: E402


def write_wz(path, entries):
    save_wz_file(_FakeSource(Path(path).with_suffix(".src"), entries), path)


def custom_wz(path):
    """A Custom.wz with an overlay image of its own, like kaentake's."""
    ui = WzEntry(name="UI", entry_type="dir", original_name_type=3,
                 children=[img_entry("UIWindowEx.img", prop("", [PropNode("MobHPRatioInfo", PROP_INT, 1)]))])
    write_wz(path, [ui])


def npc_wz(path, *ids):
    write_wz(path, [img_entry(f"{i:07d}.img", prop("", [prop("info", [])])) for i in ids])


class MasteriaTest(unittest.TestCase):
    def test_cryssea_links_the_look_and_keeps_the_rest_of_custom_wz(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp) / "Custom.wz", Path(tmp) / "out.wz"
            custom_wz(src)
            with WzFile(src) as wz:
                self.assertIsNone(masteria.linked(wz))
                self.assertEqual("Npc/9010018.img", masteria.patch_custom(wz))
                save_wz_file(wz, out)
            with WzFile(out) as written:
                self.assertEqual(sorted(written.index()), ["Npc/9010018.img", "UI/UIWindowEx.img"])
                self.assertEqual(masteria.DEFAULT_LOOK, masteria.linked(written))
                link = written.parse_img(written.index()["Npc/9010018.img"]).find_path("info/link")
                self.assertEqual("1052122", link.value, "a string of 7 digits, as stock Npc.wz links are")

    def test_a_second_run_replaces_the_link_instead_of_adding_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            first, second = Path(tmp) / "a.wz", Path(tmp) / "b.wz"
            custom_wz(Path(tmp) / "Custom.wz")
            with WzFile(Path(tmp) / "Custom.wz") as wz:
                masteria.patch_custom(wz)
                save_wz_file(wz, first)
            with WzFile(first) as wz:
                masteria.patch_custom(wz, look_id=9201083)
                save_wz_file(wz, second)
            with WzFile(second) as written:
                self.assertEqual(1, sum(1 for p in written.index() if p.startswith("Npc/")))
                self.assertEqual(9201083, masteria.linked(written))

    def test_the_command_writes_only_to_out_and_refuses_a_look_the_client_cannot_draw(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, out = Path(tmp) / "client", Path(tmp) / "out"
            client.mkdir()
            custom_wz(client / "Custom.wz")
            npc_wz(client / "Npc.wz", 1052122, 9201083)
            before = (client / "Custom.wz").read_bytes()
            botstory_wz.cmd_masteria(SimpleNamespace(client=str(client), out=str(out), look="1052122"))
            self.assertEqual(before, (client / "Custom.wz").read_bytes(), "--out leaves the client alone")
            with WzFile(out / "Custom.wz") as written:
                self.assertEqual(1052122, masteria.linked(written))
            with self.assertRaises(SystemExit):
                botstory_wz.cmd_masteria(SimpleNamespace(client=str(client), out=str(out), look="1234567"))


if __name__ == "__main__":
    unittest.main()
