"""Unit tests for the vendored WZ library (no game files needed).

    python -m unittest discover -s tools/wz-patcher/tests
"""
import copy
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from wzlib.crypto import (GMS_IV, aes256_encrypt_block, compute_version_hash, expand_key,  # noqa: E402
                          generate_key_stream)
from wzlib.reader import WzFile, WzImgParser  # noqa: E402
from wzlib.tree import apply_edit, diff, equal  # noqa: E402
from wzlib.types import (AStr, PropNode, WzEntry, PROP_DOUBLE, PROP_EXTENDED, PROP_FLOAT,  # noqa: E402
                         PROP_INT, PROP_LONG, PROP_NULL, PROP_SHORT, PROP_STRING)
from wzlib.writer import compute_checksum, save_wz_file, write_img  # noqa: E402

KS = generate_key_stream(GMS_IV, 0x10000)


def prop(name, children):
    return PropNode(name, PROP_EXTENDED, children, extended_type="Property")


def vec(name, x, y):
    return PropNode(name, PROP_EXTENDED, {'x': x, 'y': y}, extended_type="Shape2D#Vector2D")


def canvas(name, children, pixels=b'\x05\x07\x01\x00\x00\x00\x00\x00\x03\x00\x00\x00\x00\x78\x9c'):
    node = PropNode(name, PROP_EXTENDED, children, raw_bytes=pixels, extended_type="Canvas")
    node._canvas_unk = 0
    node._canvas_has_children = 1 if children else 0
    return node


def sample_tree():
    uol = PropNode("icon", PROP_EXTENDED, "../info/iconRaw", extended_type="UOL")
    uol._uol_reserved = 0
    return prop("", [
        prop("info", [
            PropNode("cash", PROP_INT, 1),
            PropNode("slotMax", PROP_SHORT, 200),
            PropNode("price", PROP_LONG, 1 << 40),
            PropNode("rate", PROP_FLOAT, 1.5),
            PropNode("exact", PROP_DOUBLE, 0.1),
            PropNode("none", PROP_NULL),
            PropNode("name", PROP_STRING, "Weapon Shop"),
            PropNode("korean", PROP_STRING, "자~ 꽃이 피었어!"),
            PropNode("cp1252", PROP_STRING, AStr("Weapon·Armor Shop")),
            canvas("iconRaw", [vec("origin", -3, 30)]),
            uol,
            PropNode("hit", PROP_EXTENDED, [vec("0", 1, 2), vec("1", -40, 7)],
                     extended_type="Shape2D#Convex2D"),
        ]),
        prop("repeated", [PropNode("Weapon Shop", PROP_STRING, "Weapon Shop")]),
    ])


class CryptoTest(unittest.TestCase):
    def test_aes256_fips197_vector(self):
        rk, _ = expand_key(bytes(range(32)))
        out = aes256_encrypt_block(bytes.fromhex("00112233445566778899aabbccddeeff"), rk)
        self.assertEqual(out.hex(), "8ea2b7ca516745bfeafc49904b496089")

    def test_key_stream_chains_blocks(self):
        first = aes256_encrypt_block(bytes(GMS_IV[i % 4] for i in range(16)))
        self.assertEqual(KS[:16], first)
        self.assertEqual(KS[16:32], aes256_encrypt_block(first))

    def test_version_hash_v83(self):
        h, header = compute_version_hash(83)
        self.assertEqual((h, header), (1876, 0xAC))


class ImgTest(unittest.TestCase):
    def test_round_trip_keeps_every_type(self):
        tree = sample_tree()
        data = write_img(tree, KS)
        back = WzImgParser(data, KS).parse()
        self.assertTrue(equal(tree, back))
        self.assertIsInstance(back.find_path("info/cp1252").value, AStr)
        self.assertEqual(back.find_path("info/cp1252").value, "Weapon·Armor Shop")
        self.assertEqual(write_img(back, KS), data)

    def test_repeated_strings_are_offset_references(self):
        data = write_img(sample_tree(), KS)
        self.assertIn(b'\x01', data)  # a name block that points back at "Weapon Shop"
        back = WzImgParser(data, KS).parse()
        self.assertEqual(back.find_path("repeated/Weapon Shop").value, "Weapon Shop")


class TreeTest(unittest.TestCase):
    def target(self):
        t = sample_tree()
        t.find("info").find("cash").value = 0
        t.find("info").value.insert(1, PropNode("quest", PROP_INT, 30000))
        t.find("info").value = [n for n in t.find("info").value if n.name != "none"]
        t.find_path("info/iconRaw").find("origin").value = {'x': 0, 'y': 32}
        t.value.append(prop("new", [canvas("0", [])]))
        return t

    def test_diff_then_apply_reproduces_the_target(self):
        base, target = sample_tree(), self.target()
        out = copy.deepcopy(base)
        for op, path, after, node in diff(base, target):
            apply_edit(out, op, path, after, node)
        self.assertTrue(equal(out, target))

    def test_edits_keep_what_another_base_adds(self):
        base, target = sample_tree(), self.target()
        other = sample_tree()
        other.find("info").value.append(PropNode("cosmicOnly", PROP_INT, 7))
        other.find("info").find("slotMax").value = 100
        for op, path, after, node in diff(base, target):
            apply_edit(other, op, path, after, node)
        self.assertEqual(other.find_path("info/cosmicOnly").value, 7)
        self.assertEqual(other.find_path("info/slotMax").value, 100)
        self.assertEqual(other.find_path("info/cash").value, 0)
        self.assertEqual(other.find_path("info/quest").value, 30000)
        self.assertIsNone(other.find_path("info/none"))
        self.assertEqual([n.name for n in other.find("info").children()][:3], ["cash", "quest", "slotMax"])

    def test_ensure_only_adds(self):
        tree = sample_tree()
        apply_edit(tree, "ensure", "info/cash", None, PropNode("cash", PROP_INT, 99))
        apply_edit(tree, "ensure", "Eqp/Accessory/1152000", None, prop("1152000", []))
        self.assertEqual(tree.find_path("info/cash").value, 1)
        self.assertIsNotNone(tree.find_path("Eqp/Accessory/1152000"))


class _FakeSource:
    """Just enough of a WzFile for save_wz_file: header, entries, key, image bytes."""

    def __init__(self, path, entries):
        self.filepath = str(path)
        self.entries = entries
        self.fstart = 60
        self.header_bytes = b'PKG1' + struct.pack('<QI', 0, 60) + b'test'.ljust(44, b'\x00')
        self.version_header = struct.pack('<H', compute_version_hash(83)[1])
        self.key_stream = generate_key_stream(GMS_IV)
        self.version_hash = compute_version_hash(83)[0]

    def iter_imgs(self, entries=None):
        for e in self.entries if entries is None else entries:
            if e.entry_type == 'img':
                yield e
            else:
                yield from self.iter_imgs(e.children)


def img_entry(name, tree):
    data = write_img(tree, generate_key_stream(GMS_IV))
    return WzEntry(name=name, entry_type='img', block_size=len(data), checksum=compute_checksum(data),
                   data=data, original_name_type=4)


class WzFileTest(unittest.TestCase):
    def test_shared_name_entry_follows_its_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.wz"
            weapon = WzEntry(name="Weapon", entry_type='dir', original_name_type=3,
                             children=[img_entry("01302000.img", prop("", [PropNode("x", PROP_INT, 1)]))])
            shared = WzEntry(name="Weapon", entry_type='dir', original_name_type=2, name_str_offset=0,
                             children=[img_entry("01312000.img", prop("", [PropNode("y", PROP_INT, 2)]))])
            sub = WzEntry(name="Sub", entry_type='dir', original_name_type=3, children=[shared])
            save_wz_file(_FakeSource(Path(tmp) / "src.wz", [weapon, sub]), path)
            with WzFile(path) as f:
                self.assertEqual(sorted(f.index()), ["Sub/Weapon/01312000.img", "Weapon/01302000.img"])
                # grow the directory before the shared entry, write again, read again
                f.entries[0].children.insert(0, img_entry("01300000.img", prop("", [])))
                path2 = Path(tmp) / "b.wz"
                save_wz_file(f, path2)
            with WzFile(path2) as g:
                index = g.index()
                self.assertIn("Sub/Weapon/01312000.img", index)
                self.assertEqual(g.parse_img(index["Sub/Weapon/01312000.img"]).find("y").value, 2)
                self.assertEqual(os.path.getsize(path2), g.filesize)


if __name__ == "__main__":
    unittest.main()
