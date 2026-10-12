"""Build the BotStory client kit from a stock client and the live BotStory client.

For each patched WZ file, the kit stores a recipe: the live file is the concatenation of
segments that are either copied from the stock (Community Repack) file or stored in the kit.
Unchanged .img blocks are copied, so the kit holds only the directory, the changed images and
the added images. apply_kit.py rebuilds the exact live bytes and checks every SHA-256.

Usage (host only; needs a MapleEdit checkout for its WZ reader):
    python tools/client-kit/build_kit.py --mapleedit <MapleEdit>/app \
        --base-wz "<Community Repack>" --base-exe "<v83 Client>/MapleStory/MapleStory.exe" \
        --live "<BotStory Client>/Game Files"
"""
import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent
DATA = KIT / "data"
WZ_FILES = ["Character.wz", "Etc.wz", "Npc.wz", "Quest.wz", "Skill.wz", "String.wz", "UI.wz"]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def flat_imgs(entries, prefix=""):
    out = []
    for e in entries:
        path = f"{prefix}{e.name}"
        if e.entry_type == "dir":
            out.extend(flat_imgs(e.children, path + "/"))
        else:
            out.append((path, e))
    return out


def wz_checksum(block: bytes) -> int:
    total = sum(block) & 0xFFFFFFFF
    return total - 0x100000000 if total >= 0x80000000 else total


def build_wz(name: str, base_dir: Path, live_dir: Path, WzFile) -> dict:
    base_path, live_path = base_dir / name, live_dir / name
    base_bytes, live_bytes = base_path.read_bytes(), live_path.read_bytes()
    base_wz, live_wz = WzFile(str(base_path)), WzFile(str(live_path))
    base_imgs = {p: e for p, e in flat_imgs(base_wz.entries)}
    live_imgs = sorted(flat_imgs(live_wz.entries), key=lambda pe: pe[1].offset)

    out_dir = DATA / name
    segments, pos, gap_no = [], 0, 0

    def literal(label: str, start: int, end: int):
        rel = f"data/{name}/{label}.bin"
        dest = KIT / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(live_bytes[start:end])
        segments.append({"literal": rel, "size": end - start, "sha256": sha256(live_bytes[start:end])})

    for path, e in live_imgs:
        block = live_bytes[e.offset:e.offset + e.block_size]
        if wz_checksum(block) != e.checksum:
            raise SystemExit(f"{name}/{path}: checksum mismatch at the computed offset; layout is not sequential")
        if pos < e.offset:
            literal("header" if pos == 0 else f"gap-{gap_no:03d}", pos, e.offset)
            gap_no += pos != 0
        b = base_imgs.get(path)
        if b is not None and b.block_size == e.block_size and \
                base_bytes[b.offset:b.offset + b.block_size] == block:
            last = segments[-1] if segments else None
            if last and "copy" in last and last["copy"] + last["size"] == b.offset:
                last["size"] += e.block_size
            else:
                segments.append({"copy": b.offset, "size": e.block_size})
        else:
            literal(path + (".added" if b is None else ""), e.offset, e.offset + e.block_size)
        pos = e.offset + e.block_size
    if pos < len(live_bytes):
        literal("trailer", pos, len(live_bytes))

    stored = sum(s["size"] for s in segments if "literal" in s)
    print(f"{name:13s} {len(segments):5d} segments, {stored / 1048576:7.2f} MB stored")
    return {"file": name, "base_size": len(base_bytes), "base_sha256": sha256(base_bytes),
            "result_size": len(live_bytes), "result_sha256": sha256(live_bytes), "segments": segments}


def build_exe(base_exe: Path, live_exe: Path) -> dict:
    a, b = base_exe.read_bytes(), live_exe.read_bytes()
    if len(a) != len(b):
        raise SystemExit("MapleStory.exe sizes differ; only byte patches are supported")
    patches = [{"offset": i, "old": a[i], "new": b[i]} for i in range(len(a)) if a[i] != b[i]]
    print(f"MapleStory.exe {len(patches)} patched bytes")
    return {"file": "MapleStory.exe", "base_size": len(a), "base_sha256": sha256(a),
            "result_size": len(b), "result_sha256": sha256(b), "patches": patches,
            "note": "large-address-aware flag (IMAGE_FILE_LARGE_ADDRESS_AWARE) and the PE header bytes it moves"}


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--mapleedit", required=True, help="MapleEdit app folder (for wz.reader)")
    ap.add_argument("--base-wz", required=True, type=Path, help="Community Repack WZ folder")
    ap.add_argument("--base-exe", required=True, type=Path, help="stock v83 MapleStory.exe")
    ap.add_argument("--live", required=True, type=Path, help="live BotStory Game Files folder")
    args = ap.parse_args()
    sys.path.insert(0, args.mapleedit)
    from wz.reader import WzFile  # noqa: E402

    if DATA.exists():
        shutil.rmtree(DATA)
    recipes = [build_wz(n, args.base_wz, args.live, WzFile) for n in WZ_FILES]
    recipes.append(build_exe(args.base_exe, args.live / "MapleStory.exe"))
    unchanged = []
    for wz in sorted(args.base_wz.glob("*.wz")):
        if wz.name in WZ_FILES:
            continue
        data = wz.read_bytes()
        if (args.live / wz.name).read_bytes() != data:
            raise SystemExit(f"{wz.name} differs from the stock file but is not in WZ_FILES")
        unchanged.append({"file": wz.name, "size": len(data), "sha256": sha256(data)})
    (KIT / "recipe.json").write_text(json.dumps({"format": 1, "files": recipes, "unchanged": unchanged},
                                                indent=1) + "\n", encoding="utf-8")
    print(f"wrote {KIT / 'recipe.json'} ({len(recipes)} patched, {len(unchanged)} unchanged)")
    write_inventory(args, recipes, unchanged)


# Where each file of a working Game Files folder comes from. Host-only files (config.ini,
# manifest.txt, *.bak, diagnostics) are left out: the installer and the patch server make them.
def write_inventory(args, recipes, unchanged):
    patched = {r["file"] for r in recipes}
    stock = {u["file"] for u in unchanged}
    skip_suffix = (".bak", ".part", ".tmp", ".txt", ".ini", ".log")
    rows = []
    for f in sorted(p for p in args.live.rglob("*") if p.is_file()):
        rel = f.relative_to(args.live).as_posix()
        if rel.lower().endswith(skip_suffix) or rel.startswith("."):
            continue
        if rel in patched:
            src = "client kit (apply_kit.py)"
        elif rel in stock:
            src = "Community Repack, unchanged"
        elif rel in ("Kaentake.dll", "Launch Game.exe", "Custom.wz"):
            src = "Incluwin/kaentake build (Launch Game.exe = Kaentake.exe; Custom.wz = src/Custom.wz)"
        elif (args.base_exe.parent / rel).is_file() and (args.base_exe.parent / rel).read_bytes() == f.read_bytes():
            src = "stock GMS v83 client, unchanged"
        else:
            src = "NOT stock: host-only file, check before shipping"
        rows.append({"path": rel, "size": f.stat().st_size, "sha256": sha256(f.read_bytes()), "source": src})
    (KIT / "client-files.json").write_text(json.dumps(rows, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {KIT / 'client-files.json'} ({len(rows)} files)")


if __name__ == "__main__":
    main()
