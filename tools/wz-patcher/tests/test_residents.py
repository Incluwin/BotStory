"""Unit tests for the Resident portraits and quest-log writer (no game files needed).

    python -m unittest discover -s tools/wz-patcher/tests
"""
import random
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import residents  # noqa: E402
from test_wzlib import _FakeSource, img_entry, prop  # noqa: E402
from wzlib.crypto import GMS_IV, generate_key_stream  # noqa: E402
from wzlib.reader import WzFile  # noqa: E402
from wzlib.types import PropNode, PROP_EXTENDED, PROP_STRING  # noqa: E402
from wzlib.writer import save_wz_file  # noqa: E402

try:
    from PIL import Image
except ImportError:
    Image = None

KS = generate_key_stream(GMS_IV, 0x10000)


def rgba(width, height, seed):
    rng = random.Random(seed)
    data = bytearray()
    for _ in range(width * height):
        data += bytes((rng.randrange(256), rng.randrange(256), rng.randrange(256),
                       rng.choice((0, 0, 255, 255, rng.randrange(256)))))
    return residents.Rgba(width, height, data)


def text(name, value):
    return PropNode(name, PROP_STRING, value)


def quest(log_id, **extra):
    entry = {"logId": log_id, "name": f"Q{log_id}", "parent": f"Q{log_id}", "available": "a", "inProgress": "b",
             "completed": "c", "area": 31, "lvmin": 5, "npc": 0, "mobs": [], "items": []}
    entry.update(extra)
    return entry


def wz_file(tmp, name, entries):
    path = Path(tmp) / name
    save_wz_file(_FakeSource(Path(tmp) / ("src-" + name), entries), path)
    return path


class CompositeTest(unittest.TestCase):
    @unittest.skipIf(Image is None, "Pillow not installed")
    def test_matches_pillow_byte_for_byte(self):
        for seed in range(20):
            dst, src = rgba(9, 8, seed), rgba(5, 4, 1000 + seed)
            expected = Image.frombytes("RGBA", (9, 8), bytes(dst.data))
            expected.alpha_composite(Image.frombytes("RGBA", (5, 4), bytes(src.data)), (2, 3))
            dst.composite(src, 2, 3)
            self.assertEqual(bytes(dst.data), expected.tobytes())

    def test_transparent_source_keeps_and_opaque_source_replaces(self):
        dst = residents.Rgba(1, 1, b"\x10\x20\x30\x80")
        dst.composite(residents.Rgba(1, 1, b"\xff\xff\xff\x00"), 0, 0)
        self.assertEqual(bytes(dst.data), b"\x10\x20\x30\x80")
        empty = residents.Rgba(1, 1)
        empty.composite(residents.Rgba(1, 1, b"\x01\x80\xfe\xff"), 0, 0)
        self.assertEqual(bytes(empty.data), b"\x01\x80\xfe\xff")


class CanvasTest(unittest.TestCase):
    def test_canvas_tail_decodes_back(self):
        image = rgba(6, 3, 7)
        canvas = PropNode("0", PROP_EXTENDED, None, raw_bytes=residents.canvas_tail(image), extended_type="Canvas")
        back = residents.decode(canvas, KS)
        self.assertEqual((back.width, back.height, bytes(back.data)), (6, 3, bytes(image.data)))

    def test_bgra4444_is_expanded(self):
        pixels = zlib.compress(bytes((0x21, 0x43)))  # B=1 G=2 R=3 A=4
        tail = b"\x01\x01\x01\x00" + b"\x00" * 4 + struct.pack("<i", len(pixels) + 1) + b"\x00" + pixels
        image = residents.decode(PropNode("0", PROP_EXTENDED, None, raw_bytes=tail, extended_type="Canvas"), KS)
        self.assertEqual(bytes(image.data), bytes((3 * 17, 2 * 17, 1 * 17, 4 * 17)))

    def test_png_holds_the_pixels(self):
        image = rgba(3, 2, 9)
        data = residents.png(image)
        self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
        width, height, depth, kind = struct.unpack(">IIBB", data[16:26])
        self.assertEqual((width, height, depth, kind), (3, 2, 8, 6))
        length = struct.unpack(">I", data[33:37])[0]
        rows = zlib.decompress(data[41:41 + length])
        self.assertEqual(rows, b"\x00" + bytes(image.data[:12]) + b"\x00" + bytes(image.data[12:]))


class WornItemsTest(unittest.TestCase):
    def test_cash_look_first_and_slots_in_order(self):
        equipped = {"-11": 1302000, "-1": 1002001, "-101": 1002999, "-7": 1072001, "-5": 1040002}
        self.assertEqual(residents.worn_items(equipped), [1002999, 1040002, 1072001, 1302000])


class QuestNodesTest(unittest.TestCase):
    def test_start_check_carries_level_expired_end_and_portrait(self):
        start = residents.check_node(quest(30000, lvmin=10, npc=9970000)).value[0]
        self.assertEqual({c.name: c.value for c in start.value},
                         {"lvmin": 10, "end": residents.HIDE_FROM_AVAILABLE_END, "npc": 9970000})

    def test_start_check_without_portrait_or_level(self):
        start = residents.check_node(quest(30001, lvmin=0)).value[0]
        self.assertEqual({c.name: c.value for c in start.value}, {"lvmin": 1, "end": residents.HIDE_FROM_AVAILABLE_END})

    def test_end_check_lists_what_the_quest_helper_counts(self):
        end = residents.check_node(quest(30002, items=[{"id": 4000020, "count": 12}],
                                         mobs=[{"id": 100100, "count": 20}])).value[1]
        self.assertEqual([c.name for c in end.value], ["item", "mob"])
        mob = end.find("mob").find("0")
        self.assertEqual((mob.find("id").value, mob.find("count").value), (100100, 20))


class PatchTest(unittest.TestCase):
    def test_quests_replace_the_range_and_keep_stock_quests(self):
        with tempfile.TemporaryDirectory() as tmp:
            stock = prop("1000", [text("name", "stock")])
            old = prop("30000", [text("name", "old")])
            stale = prop("30005", [text("name", "stale")])
            path = wz_file(tmp, "Quest.wz", [img_entry(n, prop("", [stock, old, stale]))
                                             for n in residents.QUEST_IMGS])
            with WzFile(path) as wz:
                self.assertEqual(residents.patch_quests(wz, [quest(30000), quest(30001, npc=9970002)]),
                                 list(residents.QUEST_IMGS))
                save_wz_file(wz, Path(tmp) / "out.wz")
            with WzFile(Path(tmp) / "out.wz") as wz:
                info = wz.parse_img(wz.index()["QuestInfo.img"])
                self.assertEqual([c.name for c in info.value], ["1000", "30000", "30001"])
                self.assertEqual(info.find("30000").find("name").value, "Q30000")
                self.assertEqual(info.find("30001").find("order").value, 2)
                check = wz.parse_img(wz.index()["Check.img"])
                self.assertEqual(check.find("30001").find("0").find("npc").value, 9970002)

    def test_category_names_the_area(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = wz_file(tmp, "Etc.wz", [img_entry("QuestCategory.img", prop("", [text("30", "Victoria Island")]))])
            with WzFile(path) as wz:
                self.assertEqual(residents.patch_category(wz, {31}), ["QuestCategory.img"])
                root = wz.parse_img(wz.index()["QuestCategory.img"])
            self.assertEqual({c.name: c.value for c in root.value}, {"30": "Victoria Island", "31": "Residents"})

    def test_npc_and_string_get_the_portraits_and_the_hitbox(self):
        image = rgba(2, 3, 5)
        portraits = {9970000: (image, {"x": 1, "y": 3}, "Fenna")}
        with tempfile.TemporaryDirectory() as tmp:
            npc = wz_file(tmp, "Npc.wz", [img_entry("1012100.img", prop("", [prop("info", [])])),
                                          img_entry("9970003.img", prop("", [prop("info", [])]))])
            names = wz_file(tmp, "String.wz", [img_entry("Npc.img", prop("", [
                prop("1012100", [text("name", "Athena Pierce")]), prop("9970003", [text("name", "Gone")])]))])
            with WzFile(npc) as wz:
                residents.patch_npc(wz, portraits, 9970999)
                save_wz_file(wz, Path(tmp) / "npc-out.wz")
            with WzFile(names) as wz:
                residents.patch_string(wz, portraits, 9970999)
                save_wz_file(wz, Path(tmp) / "string-out.wz")
            with WzFile(Path(tmp) / "npc-out.wz") as wz:
                index = wz.index()
                self.assertEqual(sorted(index), ["1012100.img", "9970000.img", "9970999.img"])
                frame = wz.parse_img(index["9970000.img"]).find("stand").find("0")
                self.assertEqual(frame.find("origin").value, {"x": 1, "y": 3})
                self.assertEqual(bytes(residents.decode(frame, wz.key_stream).data), bytes(image.data))
                hitbox = wz.parse_img(index["9970999.img"])
                self.assertEqual(hitbox.find("info").find("hideName").value, 1)
            with WzFile(Path(tmp) / "string-out.wz") as wz:
                root = wz.parse_img(wz.index()["Npc.img"])
                self.assertEqual({c.name: c.find("name").value for c in root.value},
                                 {"1012100": "Athena Pierce", "9970000": "Fenna", "9970999": "Quest"})


if __name__ == "__main__":
    unittest.main()
