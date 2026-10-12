"""Turn a stock v83 client into the BotStory client files, byte for byte.

Inputs (not in this repo, they are Nexon's files):
  --base-wz   the Community Repack WZ folder (Character.wz, Etc.wz, ... from "Community Repack WZs.zip")
  --base-exe  the stock GMS v83 MapleStory.exe
Output: --out folder with all 17 Community Repack WZ files (7 patched, 10 copied unchanged) and
the patched MapleStory.exe.
Every input and output is checked against the SHA-256 in recipe.json; nothing is written
in place, and a mismatch stops with an error. Python 3.8+, standard library only.

    python tools/client-kit/apply_kit.py --base-wz "<Community Repack>" \
        --base-exe "<v83 Client>/MapleStory/MapleStory.exe" --out "<new Game Files>"
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check(path: Path, size: int, digest: str, what: str):
    if not path.is_file():
        sys.exit(f"missing {what}: {path}")
    if path.stat().st_size != size or sha256_file(path) != digest:
        sys.exit(f"{what} does not match the kit: {path} (expected {size} bytes, sha256 {digest[:16]}...)")


def apply_wz(r: dict, base_dir: Path, out_dir: Path):
    base = base_dir / r["file"]
    check(base, r["base_size"], r["base_sha256"], "stock file")
    out = out_dir / r["file"]
    tmp = out.with_suffix(out.suffix + ".part")
    with base.open("rb") as src, tmp.open("wb") as dst:
        for s in r["segments"]:
            if "copy" in s:
                src.seek(s["copy"])
                left = s["size"]
                while left:
                    chunk = src.read(min(left, 1 << 20))
                    if not chunk:
                        sys.exit(f"{base} ended early")
                    dst.write(chunk)
                    left -= len(chunk)
            else:
                data = (KIT / s["literal"]).read_bytes()
                if len(data) != s["size"] or hashlib.sha256(data).hexdigest() != s["sha256"]:
                    sys.exit(f"kit file is damaged: {s['literal']}")
                dst.write(data)
    check(tmp, r["result_size"], r["result_sha256"], "result")
    tmp.replace(out)
    print(f"  {r['file']:15s} ok  {r['result_sha256'][:16]}")


def apply_exe(r: dict, base_exe: Path, out_dir: Path):
    check(base_exe, r["base_size"], r["base_sha256"], "stock MapleStory.exe")
    data = bytearray(base_exe.read_bytes())
    for p in r["patches"]:
        if data[p["offset"]] != p["old"]:
            sys.exit(f"unexpected byte at {p['offset']:#x} in {base_exe}")
        data[p["offset"]] = p["new"]
    out = out_dir / r["file"]
    tmp = out.with_suffix(".part")
    tmp.write_bytes(data)
    check(tmp, r["result_size"], r["result_sha256"], "result")
    tmp.replace(out)
    print(f"  {r['file']:15s} ok  {r['result_sha256'][:16]}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base-wz", required=True, type=Path)
    ap.add_argument("--base-exe", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()
    recipe = json.loads((KIT / "recipe.json").read_text(encoding="utf-8"))
    if recipe.get("format") != 1:
        sys.exit("unknown recipe format")
    args.out.mkdir(parents=True, exist_ok=True)
    for r in recipe["files"]:
        if r["file"].endswith(".wz"):
            apply_wz(r, args.base_wz, args.out)
        else:
            apply_exe(r, args.base_exe, args.out)
    for u in recipe["unchanged"]:
        src = args.base_wz / u["file"]
        check(src, u["size"], u["sha256"], "stock file")
        (args.out / u["file"]).write_bytes(src.read_bytes())
        print(f"  {u['file']:15s} copied unchanged")
    print(f"done: {len(recipe['files']) + len(recipe['unchanged'])} files in {args.out}")


if __name__ == "__main__":
    main()
