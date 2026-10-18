"""Unit tests for the pack-free `exe` command (no game files needed).

    python -m unittest discover -s tools/wz-patcher/tests
"""
import argparse
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import botstory_wz  # noqa: E402

STOCK = bytes(range(256)) * 2
PATCHED = bytearray(STOCK)
PATCHED[10], PATCHED[20] = 0x2F, 0xFC
SPEC = {
    "base": botstory_wz.fingerprint(STOCK),
    "result": botstory_wz.fingerprint(bytes(PATCHED)),
    "patches": [{"offset": 10, "old": 10, "new": 0x2F}, {"offset": 20, "old": 20, "new": 0xFC}],
}


class ExeCommandTest(unittest.TestCase):
    def run_exe(self, exe):
        with mock.patch.object(botstory_wz, "STOCK_EXE_PATCH", SPEC):
            botstory_wz.cmd_exe(argparse.Namespace(exe=str(exe)))

    def test_sets_the_recorded_bytes_and_keeps_the_original(self):
        with tempfile.TemporaryDirectory() as folder:
            exe = Path(folder) / "MapleStory.exe"
            exe.write_bytes(STOCK)

            self.run_exe(exe)
            self.assertEqual(exe.read_bytes(), bytes(PATCHED))
            backups = list((Path(folder) / "botstory-wz-backup").glob("*/MapleStory.exe"))
            self.assertEqual([b.read_bytes() for b in backups], [STOCK])

            self.run_exe(exe)  # already patched: left alone
            self.assertEqual(exe.read_bytes(), bytes(PATCHED))

    def test_refuses_an_exe_that_is_not_the_stock_one(self):
        with tempfile.TemporaryDirectory() as folder:
            exe = Path(folder) / "MapleStory.exe"
            exe.write_bytes(b"not the stock client")
            with self.assertRaises(SystemExit):
                self.run_exe(exe)
            self.assertEqual(exe.read_bytes(), b"not the stock client")

    def test_built_in_patch_is_the_large_address_aware_flag(self):
        flag, checksum = botstory_wz.STOCK_EXE_PATCH["patches"]
        self.assertEqual(flag["new"] - flag["old"], 0x20)  # IMAGE_FILE_LARGE_ADDRESS_AWARE
        self.assertEqual(checksum["new"] - checksum["old"], 0x20)  # the PE checksum moves with it


if __name__ == "__main__":
    unittest.main()
