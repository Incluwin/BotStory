#!/usr/bin/env python3
"""BotStory WZ patcher: add BotStory's client content to a v83 client's WZ files.

Works on either base the BotStory server supports:
  * the clean Cosmic client: P0nk/Cosmic-client's cosmic-wz files plus the other WZ files of
    the GMS v83 setup (Base, Effect, Morph, Sound, TamingMob);
  * the Community Repack WZ set.

The pack (Nexon game data, so not in git: see the README) holds only BotStory's own content and
the images the server knows that a base lacks:
  * botstory: what the BotStory client changed or added (Resident quests, portraits and names,
    skill and item edits, cash shop edits, the large quick-slot bar) and Custom.wz;
  * server:   images in the server's WZ XML that the clean Cosmic client lacks (307 equips the
    server took from the Community Repack) and their names in String.wz.
Where a base image is byte-identical to the Community Repack image BotStory was built on, the
patcher writes the BotStory image as is (a patched Community Repack is byte-identical to the
shipped BotStory client). Elsewhere it applies BotStory's edits node by node to the base's own
image, so the base keeps its own content.

    python botstory_wz.py patch --client "<client folder>"            # in place, with backups
    python botstory_wz.py patch --client "<WZ folder>" --out "<dir>"  # write patched files to <dir>
    python botstory_wz.py check --client "<client folder>"            # verify a patched client
    python botstory_wz.py residents --client "<client folder>"        # Resident portraits + quest log, made
                                                                      # from this client (see residents.py)
    python botstory_wz.py exe --exe "<client folder>/MapleStory.exe"  # large-address-aware flag only, no pack
    python botstory_wz.py build ...                                   # maintainers, see README

Python 3.8+, standard library only.
"""
import argparse
import datetime
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
from types import SimpleNamespace

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from wzlib.crypto import GMS_IV, generate_key_stream  # noqa: E402
from wzlib.reader import WzFile, WzImgParser  # noqa: E402
from wzlib.tree import (apply_edit, blob_subtree, diff, equal, leaf_json,  # noqa: E402
                        leaf_node, subtree_blob)
from wzlib.types import PropNode, PROP_EXTENDED, WzEntry  # noqa: E402
from wzlib.writer import compute_checksum, save_wz_file, write_img  # noqa: E402

# The pack is Nexon game data and stays off GitHub. On the BotStory host it lives next to the
# patch server (FriendServer/patch-server/wz-pack); elsewhere pass --pack or set BOTSTORY_WZ_PACK.
PACK = Path(os.environ.get("BOTSTORY_WZ_PACK") or HERE.parents[2] / "patch-server" / "wz-pack")
FORMAT = 1
DEFAULT_SERVER_WZ = HERE.parent.parent / "wz"
DEFAULT_EXPORT = HERE.parent.parent / "target"  # where tools\living-world-client.bat has the server export
SKIP_WZ = {"List.wz", "Custom.wz"}  # List.wz is not a PKG1 archive; Custom.wz is copied whole

# The stock GMS v83 MapleStory.exe needs only its large-address-aware flag and the PE checksum byte
# that changes with it (what cmd_build records in the pack). Neither is game data, so `exe` applies
# them without a pack.
STOCK_EXE_PATCH = {
    "base": {"size": 4286088, "sha256": "ed5a699407b9705528b6a653cdee7395e6e1b7317b9711dcfb34681092072848"},
    "result": {"size": 4286088, "sha256": "33bb280921cd93dad593080e9e44a2cfe1af507d71f76f4b64c6d80613d52e77"},
    "patches": [{"offset": 318, "old": 15, "new": 47}, {"offset": 384, "old": 220, "new": 252}],
}


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fingerprint(data):
    return {"size": len(data), "sha256": sha256_bytes(data)}


def matches(data, fp):
    return fp is not None and len(data) == fp["size"] and sha256_bytes(data) == fp["sha256"]


def gms_key():
    return generate_key_stream(GMS_IV)


def predecessors(wz):
    """{img path: the name of the entry before it in its directory (None = first)}."""
    out = {}

    def walk(entries, prefix):
        prev = None
        for e in entries:
            if e.entry_type == "dir":
                walk(e.children, prefix + e.name + "/")
            else:
                out[prefix + e.name] = prev
            prev = e.name
    walk(wz.entries, "")
    return out


# ---------------------------------------------------------------------------------- pack I/O

class Pack:
    def __init__(self, root=PACK):
        self.root = Path(root)
        if not (self.root / "pack.json").is_file():
            sys.exit(f"no BotStory WZ pack in {self.root}: the pack is not in git; "
                     "pass --pack <folder> or set BOTSTORY_WZ_PACK (see the README)")
        self.meta = json.loads((self.root / "pack.json").read_text(encoding="utf-8"))
        if self.meta.get("format") != FORMAT:
            sys.exit(f"{self.root / 'pack.json'}: unknown pack format {self.meta.get('format')}")
        self._blobs = {}

    def data(self, entry):
        data = (self.root / entry["data"]).read_bytes()
        if not matches(data, entry):
            sys.exit(f"pack file is damaged: {entry['data']}")
        return data

    def edits(self, entry):
        """[(op, path, after, node)] with nodes in the GMS key."""
        doc = json.loads((self.root / entry["edits"]).read_text(encoding="utf-8"))
        blobs = None
        if doc.get("blobs"):
            raw = (self.root / doc["blobs"]).read_bytes()
            if sha256_bytes(raw) != doc["blobs_sha256"]:
                sys.exit(f"pack file is damaged: {doc['blobs']}")
            blobs = WzImgParser(raw, gms_key()).parse()
        out = []
        for e in doc["edits"]:
            node = None
            if "leaf" in e:
                node = leaf_node(e["path"].split("/")[-1], e["leaf"])
            elif "blob" in e:
                node = blobs.find(str(e["blob"]))
            out.append((e["op"], e["path"], e.get("after"), node))
        return out


# ---------------------------------------------------------------------------------- patching

class WzPatch:
    """Applies the pack's image entries for one WZ file to an open base file."""

    def __init__(self, pack, spec, wz):
        self.pack, self.spec, self.wz = pack, spec, wz
        self.index = wz.index()
        self.gms = wz.iv == GMS_IV
        self.counts = {"exact": 0, "edited": 0, "added": 0, "replaced": 0, "current": 0, "kept": 0}
        self.notes = []
        self.touched = {}

    def _rekey(self, data):
        if self.gms:
            return data
        return write_img(WzImgParser(data, gms_key()).parse(), self.wz.key_stream)

    def _set(self, entry, data):
        entry.data = data
        entry.block_size = len(data)
        entry.checksum = compute_checksum(data)

    def _insert(self, path, data, after):
        parts = path.split("/")
        container = self.wz.entries
        for d in parts[:-1]:
            found = next((e for e in container if e.entry_type == "dir" and e.name == d), None)
            if found is None:
                found = WzEntry(name=d, entry_type="dir", original_name_type=3)
                container.append(found)
            container = found.children
        new = WzEntry(name=parts[-1], entry_type="img", original_name_type=4)
        self._set(new, data)
        pos = None
        if after is not None:
            j = next((i for i, e in enumerate(container) if e.name == after), None)
            if j is not None:
                pos = j + 1
        if pos is None:
            pos = next((i for i, e in enumerate(container)
                        if e.entry_type == "img" and e.name > new.name), len(container))
        container.insert(pos, new)
        self.index[path] = new
        return new

    def run(self):
        for item in self.spec["imgs"]:
            path = item["path"]
            entry = self.index.get(path)
            if entry is None:
                self._insert(path, self._rekey(self.pack.data(item)), item.get("after"))
                self.touched[path] = self.index[path].data
                self.counts["added"] += 1
                continue
            current = self.wz.read_img_data(entry)
            if self.gms and matches(current, item):
                self.counts["current"] += 1
                continue
            if self.gms and matches(current, item.get("base")):
                new = self.pack.data(item)
                kind = "exact"
            elif item.get("edits"):
                root = WzImgParser(current, self.wz.key_stream).parse()
                for op, p, after, node in self.pack.edits(item):
                    note = apply_edit(root, op, p, after, node)
                    if note:
                        self.notes.append(f"{self.spec['file']}/{path}: {note}")
                new = write_img(root, self.wz.key_stream)
                kind = "edited"
            elif item["kind"] == "server":
                self.counts["kept"] += 1
                continue
            else:
                new = self._rekey(self.pack.data(item))
                kind = "replaced"
            if new == current:
                self.counts["current"] += 1
                continue
            self._set(entry, new)
            self.touched[path] = new
            self.counts[kind] += 1
        return bool(self.touched)


def verify_written(path, expected_imgs, touched):
    """Re-open a written file: same image set and sizes, touched images byte-equal."""
    with WzFile(path) as f:
        index = f.index()
        if set(index) != set(expected_imgs):
            sys.exit(f"{path}: written directory does not list the expected images")
        for p, size in expected_imgs.items():
            if index[p].block_size != size:
                sys.exit(f"{path}: {p} has the wrong size after writing")
        for p, data in touched.items():
            got = f.read_img_data(index[p])
            if got != data or compute_checksum(got) != index[p].checksum:
                sys.exit(f"{path}: {p} did not come back as written")
            WzImgParser(got, f.key_stream).parse()


def identify(client, pack):
    """Name the known base the client's WZ files match by size (a cheap hint for the report)."""
    rows = []
    for name, files in pack.meta.get("bases", {}).items():
        hits = sum(1 for f, fp in files.items()
                   if (client / f).is_file() and (client / f).stat().st_size == fp["size"])
        rows.append((hits / max(1, len(files)), name, hits, len(files)))
    rows.sort(reverse=True)
    return rows


def cmd_patch(args):
    pack = Pack(args.pack)
    client = Path(args.client)
    out = Path(args.out) if args.out else None
    if not client.is_dir():
        sys.exit(f"no such folder: {client}")
    for score, name, hits, total in identify(client, pack)[:2]:
        print(f"base check: {hits}/{total} WZ files have the {name} sizes")
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = client / "botstory-wz-backup" / stamp
    if out:
        out.mkdir(parents=True, exist_ok=True)
    changed_any = False
    notes = []
    for spec in pack.meta["files"]:
        src = client / spec["file"]
        if not src.is_file():
            sys.exit(f"missing {src}: the client folder needs every WZ file of the base")
        wz = WzFile(src)
        try:
            before = {p: e.block_size for p, e in wz.index().items()}
            patch = WzPatch(pack, spec, wz)
            changed = patch.run()
            notes += patch.notes
            c = patch.counts
            summary = ", ".join(f"{v} {k}" for k, v in c.items() if v)
            if not changed:
                print(f"  {spec['file']:13s} up to date ({summary})")
                if out and args.copy_unchanged:
                    shutil.copy2(src, out / spec["file"])
                continue
            target = (out / spec["file"]) if out else src.with_name(src.name + ".botstory-new")
            save_wz_file(wz, target)
            expected = dict(before)
            expected.update({p: len(d) for p, d in patch.touched.items()})
        finally:
            wz.close()
        verify_written(target, expected, patch.touched)
        if not out:
            backup.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(backup / spec["file"]))
            target.replace(src)
        changed_any = True
        print(f"  {spec['file']:13s} patched ({summary})")
    for extra in pack.meta.get("copy", []):
        data = pack.data(extra)
        dest = (out or client) / extra["file"]
        if dest.is_file() and matches(dest.read_bytes(), extra):
            print(f"  {extra['file']:13s} up to date")
            continue
        if dest.is_file() and not out:
            backup.mkdir(parents=True, exist_ok=True)
            shutil.copy2(dest, backup / extra["file"])
        dest.write_bytes(data)
        changed_any = True
        print(f"  {extra['file']:13s} written")
    if out and args.copy_unchanged:
        handled = {spec["file"] for spec in pack.meta["files"]} | {e["file"] for e in pack.meta.get("copy", [])}
        for wz in sorted(client.glob("*.wz")):
            if wz.name not in handled and not (out / wz.name).exists():
                shutil.copy2(wz, out / wz.name)
                print(f"  {wz.name:13s} copied unchanged")
    if args.exe:
        changed_any |= patch_exe(pack, Path(args.exe), out, backup)
    for n in notes:
        print(f"  note: {n}")
    if changed_any and not out:
        print(f"originals moved to {backup}")
    print("done" if changed_any else "nothing to do: the client already has the BotStory content")


def patch_exe(pack, exe, out, backup):
    spec = pack.meta.get("exe")
    if not spec:
        sys.exit("this pack has no MapleStory.exe patch")
    data = bytearray(exe.read_bytes())
    if matches(bytes(data), spec["result"]):
        print("  MapleStory.exe up to date")
        if out:
            (out / "MapleStory.exe").write_bytes(data)
        return False
    if not matches(bytes(data), spec["base"]):
        sys.exit(f"{exe} is not the stock GMS v83 MapleStory.exe (sha256 {spec['base']['sha256'][:16]}...)")
    for p in spec["patches"]:
        if data[p["offset"]] != p["old"]:
            sys.exit(f"unexpected byte at {p['offset']:#x} in {exe}")
        data[p["offset"]] = p["new"]
    if not matches(bytes(data), spec["result"]):
        sys.exit("patched MapleStory.exe does not match the pack")
    if out:
        (out / "MapleStory.exe").write_bytes(data)
    else:
        backup.mkdir(parents=True, exist_ok=True)
        shutil.copy2(exe, backup / exe.name)
        exe.write_bytes(data)
    print("  MapleStory.exe large-address-aware flag set")
    return True


def cmd_exe(args):
    exe = Path(args.exe)
    if not exe.is_file():
        sys.exit(f"no such file: {exe}")
    backup = exe.parent / "botstory-wz-backup" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    if patch_exe(SimpleNamespace(meta={"exe": STOCK_EXE_PATCH}), exe, None, backup):
        print(f"original moved to {backup}")


# ---------------------------------------------------------------------------------- Residents

def cmd_residents(args):
    """Resident portraits and quest-log entries from the server's export, rendered from this client's own files."""
    import residents
    client = Path(args.client)
    out = Path(args.out) if args.out else None
    export = Path(args.export)
    needed = ["Character.wz", "Base.wz", "Npc.wz", "String.wz", "Quest.wz", "Etc.wz"]
    missing = [n for n in needed if not (client / n).is_file()]
    if missing:
        sys.exit(f"{client} lacks {', '.join(missing)}")
    sources = {"portraits": export / "resident-portraits.json", "quests": export / "living-quest-log.json"}
    for path in sources.values():
        if not path.is_file():
            sys.exit(f"no {path}: run tools\\living-world-client.bat (it exports, then runs this command)")
    doc = json.loads(sources["portraits"].read_text(encoding="utf-8"))
    quests = json.loads(sources["quests"].read_text(encoding="utf-8"))
    bad = [q["questId"] for q in quests if not residents.QUEST_MIN <= int(q["logId"]) <= residents.QUEST_MAX]
    if bad or len({q["logId"] for q in quests}) != len(quests):
        sys.exit(f"{sources['quests']}: quest-log ids outside {residents.QUEST_MIN}-{residents.QUEST_MAX} "
                 f"or repeated ({bad[:5]})")
    hitbox = int(doc["hitboxNpcId"])

    portraits = residents.render_portraits(client, doc)
    print(f"  rendered {len(portraits)} portraits from {client / 'Character.wz'}")
    if args.preview:
        preview = Path(args.preview)
        preview.mkdir(parents=True, exist_ok=True)
        persona = {p["npcId"]: p["persona"] for p in doc["portraits"]}
        for npc_id, (image, _, _) in portraits.items():
            (preview / f"{persona[npc_id]}.png").write_bytes(residents.png(image))
        print(f"  previews in {preview}")
    areas = {int(q["area"]) for q in quests if q.get("area")}
    jobs = [
        ("Npc.wz", lambda wz: residents.patch_npc(wz, portraits, hitbox),
         [f"{i}.img" for i in sorted(portraits)] + [f"{hitbox}.img"], f"{len(portraits)} portraits + quest-marker hitbox"),
        ("String.wz", lambda wz: residents.patch_string(wz, portraits, hitbox), ["Npc.img"], "their names"),
        ("Quest.wz", lambda wz: residents.patch_quests(wz, quests), list(residents.QUEST_IMGS),
         f"{len(quests)} living-world quests"),
        ("Etc.wz", lambda wz: residents.patch_category(wz, areas), ["QuestCategory.img"],
         f"quest-list group '{residents.CATEGORY_LABEL}' (area {', '.join(map(str, sorted(areas)))})"),
    ]
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = client / "botstory-wz-backup" / stamp
    if out:
        out.mkdir(parents=True, exist_ok=True)
    changed_any = False
    for name, job, expect, what in jobs:
        src = client / name
        target = (out / name) if out else src.with_name(src.name + ".botstory-new")
        wz = WzFile(src)
        try:
            job(wz)
            save_wz_file(wz, target)
        finally:
            wz.close()
        with WzFile(target) as written:
            index = written.index()
            lacking = [p for p in expect if p not in index]
            if lacking:
                sys.exit(f"{target}: written file lacks {lacking[:5]}")
            for p in expect:
                written.parse_img(index[p])
        if sha256_file(target) == sha256_file(src):
            if not out:
                target.unlink()
            print(f"  {name:13s} up to date ({what})")
            continue
        if not out:
            backup.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(backup / name))
            target.replace(src)
        changed_any = True
        print(f"  {name:13s} written ({what})")
    if changed_any and not out:
        print(f"originals moved to {backup}")
    print("done" if changed_any else "nothing to do: the client already has these Residents")


# ---------------------------------------------------------------------------------- checking

def server_imgs(server_wz):
    out = {}
    for wzdir in sorted(Path(server_wz).iterdir()):
        if not wzdir.is_dir():
            continue
        out[wzdir.name] = sorted(p.relative_to(wzdir).as_posix()[:-4] for p in wzdir.rglob("*.img.xml"))
    return out


def cmd_check(args):
    pack = Pack(args.pack)
    client = Path(args.client)
    problems = 0
    for score, name, hits, total in identify(client, pack)[:2]:
        print(f"base check: {hits}/{total} WZ files have the {name} sizes")
    for spec in pack.meta["files"]:
        src = client / spec["file"]
        if not src.is_file():
            print(f"  {spec['file']:13s} MISSING")
            problems += 1
            continue
        with WzFile(src) as wz:
            index = wz.index()
            bad = []
            for item in spec["imgs"]:
                entry = index.get(item["path"])
                if entry is None:
                    bad.append(f"{item['path']} missing")
                    continue
                if item["kind"] == "server":
                    continue
                current = wz.read_img_data(entry)
                if wz.iv == GMS_IV and matches(current, item):
                    continue
                if not item.get("edits"):
                    bad.append(f"{item['path']} is not the BotStory image")
                    continue
                root = WzImgParser(current, wz.key_stream).parse()
                after = WzImgParser(current, wz.key_stream).parse()
                for op, p, aft, node in pack.edits(item):
                    apply_edit(after, op, p, aft, node)
                if not equal(root, after):
                    bad.append(f"{item['path']} lacks BotStory edits")
        problems += len(bad)
        print(f"  {spec['file']:13s} {'ok' if not bad else 'NOT PATCHED: ' + '; '.join(bad[:4])}")
    for extra in pack.meta.get("copy", []):
        dest = client / extra["file"]
        ok = dest.is_file() and matches(dest.read_bytes(), extra)
        problems += not ok
        print(f"  {extra['file']:13s} {'ok' if ok else 'MISSING or different'}")
    server_wz = Path(args.server_wz) if args.server_wz else DEFAULT_SERVER_WZ
    if server_wz.is_dir():
        custom = set()
        if (client / "Custom.wz").is_file():
            with WzFile(client / "Custom.wz") as cw:
                custom = set(cw.index())
        missing_total = 0
        for wzname, paths in server_imgs(server_wz).items():
            src = client / wzname
            if not src.is_file():
                print(f"  server {wzname}: client has no {wzname}")
                missing_total += len(paths)
                continue
            with WzFile(src) as wz:
                have = set(wz.index())
            prefix = wzname[:-3] + "/"
            missing = [p for p in paths if p not in have and prefix + p not in custom]
            # World maps are client UI: the server never sends them, so a gap only blanks the
            # world map window there (the Community Repack lacks five Cosmic ones).
            ui_only = [p for p in missing if wzname == "Map.wz" and p.startswith("WorldMap/")]
            missing = [p for p in missing if p not in ui_only]
            if ui_only:
                print(f"  server {wzname}: {len(ui_only)} world maps the server knows are missing "
                      f"(UI only, not an error): {', '.join(ui_only)}")
            if missing:
                missing_total += len(missing)
                print(f"  server {wzname}: {len(missing)} images the server knows are missing, "
                      f"e.g. {', '.join(missing[:3])}")
        problems += missing_total
        print(f"  server WZ XML ({server_wz}): "
              f"{'every image is in the client' if not missing_total else str(missing_total) + ' missing'}")
    else:
        print(f"  (no server WZ XML at {server_wz}; skipped the server comparison)")
    print("OK: the client has the BotStory content" if not problems else f"{problems} problem(s)")
    return 1 if problems else 0


# ---------------------------------------------------------------------------------- building

def cmd_build(args):
    repack, live, cosmic = Path(args.repack), Path(args.live), Path(args.cosmic)
    out = Path(args.pack)
    gms = gms_key()
    if out.exists():
        for sub in ("data", "edits"):
            shutil.rmtree(out / sub, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)

    server = server_imgs(args.server_wz) if args.server_wz else {}
    files = []
    eqp_ids = []
    for live_path in sorted(live.glob("*.wz")):
        name = live_path.name
        if name in SKIP_WZ:
            continue
        base_path = repack / name
        same_file = live_path.stat().st_size == base_path.stat().st_size and \
            sha256_file(live_path) == sha256_file(base_path)
        lw, bw, cw = WzFile(live_path), WzFile(base_path), WzFile(cosmic / name)
        try:
            li, bi, ci = lw.index(), bw.index(), cw.index()
            before = predecessors(lw)
            imgs = []
            for path, le in ({} if same_file else li).items():
                be = bi.get(path)
                ldata = lw.read_img_data(le)
                if be is not None:
                    bdata = bw.read_img_data(be)
                    if bdata == ldata:
                        continue
                item = {"path": path, "kind": "botstory", "after": before[path],
                        "data": f"data/{name}/{path}"}
                item.update(fingerprint(ldata))
                if be is not None:
                    item["base"] = fingerprint(bdata)
                    edits = diff(WzImgParser(bdata, bw.key_stream).parse(),
                                 WzImgParser(ldata, lw.key_stream).parse())
                    item["_edits"] = edits
                imgs.append(item)
                write_data(out, item, ldata)
            # images the server knows that the clean Cosmic client lacks
            for path in server.get(name, []):
                if path in ci or path not in li or any(i["path"] == path for i in imgs):
                    continue
                ldata = lw.read_img_data(li[path])
                item = {"path": path, "kind": "server", "after": before[path],
                        "data": f"data/{name}/{path}"}
                item.update(fingerprint(ldata))
                imgs.append(item)
                write_data(out, item, ldata)
                if name == "Character.wz":
                    eqp_ids.append(str(int(path.split("/")[-1].split(".")[0])))
            files.append({"file": name, "imgs": imgs, "_live": lw.filepath})
        finally:
            lw.close()
            bw.close()
            cw.close()

    # names of the server-only equips: ensure them in String.wz/Eqp.img
    if eqp_ids:
        spec = next(f for f in files if f["file"] == "String.wz")
        with WzFile(live / "String.wz") as sw:
            root = sw.parse_img(sw.index()["Eqp.img"])
        ensures = []
        eqp = root.find("Eqp")
        for cat in eqp.children():
            prev = None
            for node in cat.children():
                if node.name in eqp_ids:
                    ensures.append(("ensure", f"Eqp/{cat.name}/{node.name}", prev, node))
                prev = node.name
        item = next((i for i in spec["imgs"] if i["path"] == "Eqp.img"), None)
        if item is None:
            sys.exit("String.wz/Eqp.img is not a BotStory image; add a server-only entry for it")
        item["_edits"] = item.get("_edits", []) + ensures
        print(f"String.wz/Eqp.img: {len(ensures)} names for {len(eqp_ids)} server-only equips")

    for spec in files:
        for item in spec["imgs"]:
            edits = item.pop("_edits", None)
            if edits is not None:
                item["edits"] = write_edits(out, spec["file"], item["path"], edits, gms)
        spec.pop("_live")
        n = {"botstory": 0, "server": 0}
        for i in spec["imgs"]:
            n[i["kind"]] += 1
        print(f"{spec['file']:13s} {n['botstory']} BotStory images, {n['server']} server images")
    files = [f for f in files if f["imgs"]]

    copy = []
    custom = Path(args.custom) if args.custom else live / "Custom.wz"
    data = custom.read_bytes()
    (out / "data").mkdir(exist_ok=True)
    (out / "data" / "Custom.wz").write_bytes(data)
    entry = {"file": "Custom.wz", "data": "data/Custom.wz"}
    entry.update(fingerprint(data))
    copy.append(entry)

    stock = Path(args.stock_client)
    meta = {"format": FORMAT,
            "built": datetime.date.today().isoformat(),
            "note": "Nexon game data; keep this repository private.",
            "files": files, "copy": copy,
            "exe": build_exe(stock / "MapleStory.exe", live / "MapleStory.exe"),
            "bases": {"cosmic": base_sizes(cosmic), "community-repack": base_sizes(repack)}}
    write_json(out / "pack.json", meta, indent=1)
    total = sum(p.stat().st_size for p in out.rglob("*") if p.is_file())
    print(f"wrote {out} ({total / 1048576:.1f} MB)")
    write_inventory(live, stock, repack, {f["file"] for f in files} | {c["file"] for c in copy})


def build_exe(stock_exe, live_exe):
    a, b = stock_exe.read_bytes(), live_exe.read_bytes()
    if len(a) != len(b):
        sys.exit("MapleStory.exe sizes differ; only byte patches are supported")
    patches = [{"offset": i, "old": a[i], "new": b[i]} for i in range(len(a)) if a[i] != b[i]]
    print(f"MapleStory.exe: {len(patches)} patched bytes")
    return {"base": fingerprint(a), "result": fingerprint(b), "patches": patches,
            "note": "large-address-aware flag (IMAGE_FILE_LARGE_ADDRESS_AWARE)"}


def write_json(path, obj, indent):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(obj, indent=indent, ensure_ascii=False) + "\n")


def base_sizes(folder):
    return {p.name: {"size": p.stat().st_size} for p in sorted(Path(folder).glob("*.wz"))}


# Where each file of a working BotStory Game Files folder comes from. Host-only files
# (config.ini, manifest.txt, *.bak, diagnostics) are left out: the installer and the patch
# server make them.
def write_inventory(live, stock, repack, patched):
    skip = (".bak", ".part", ".tmp", ".txt", ".ini", ".log", ".partial")
    rows = []
    for f in sorted((p for p in live.rglob("*") if p.is_file()), key=lambda p: p.relative_to(live).as_posix().lower()):
        rel = f.relative_to(live).as_posix()
        if rel.lower().endswith(skip) or rel.startswith(".") or "botstory-wz-backup" in rel:
            continue
        digest = sha256_file(f)
        if rel in patched or rel == "MapleStory.exe":
            src = "BotStory WZ patcher (tools/wz-patcher)"
        elif rel in ("Kaentake.dll", "Launch Game.exe"):
            src = "Incluwin/kaentake build (Launch Game.exe = Kaentake.exe)"
        elif (repack / rel).is_file() and (repack / rel).stat().st_size == f.stat().st_size:
            src = "base WZ, unchanged (Community Repack or clean Cosmic)"
        elif (stock / rel).is_file() and sha256_file(stock / rel) == digest:
            src = "stock GMS v83 client, unchanged"
        else:
            src = "NOT stock: host-only file, check before shipping"
        rows.append({"path": rel, "size": f.stat().st_size, "sha256": digest, "source": src})
    write_json(HERE / "client-files.json", rows, indent=1)
    print(f"wrote {HERE / 'client-files.json'} ({len(rows)} files)")


def write_data(out, item, data):
    dest = out / item["data"]
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)


def write_edits(out, wzname, path, edits, gms):
    rel = f"edits/{wzname}/{path}.json"
    blobs = []
    doc = {"edits": []}
    for op, p, after, node in edits:
        e = {"op": op, "path": p}
        if after is not None:
            e["after"] = after
        if node is not None:
            leaf = leaf_json(node)
            if leaf is not None:
                e["leaf"] = leaf
            else:
                e["blob"] = len(blobs)
                child = node.__class__(str(len(blobs)), node.type_byte, node.value, node.raw_bytes,
                                       node.extended_type)
                child.__dict__.update({k: v for k, v in node.__dict__.items() if k.startswith("_")})
                blobs.append(child)
        doc["edits"].append(e)
    dest = out / rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    if blobs:
        raw = write_img(PropNode("", PROP_EXTENDED, blobs, extended_type="Property"), gms)
        blob_rel = f"edits/{wzname}/{path}.blobs.img"
        (out / blob_rel).write_bytes(raw)
        doc["blobs"] = blob_rel
        doc["blobs_sha256"] = sha256_bytes(raw)
    write_json(dest, doc, indent=0)
    return rel


def main():
    ap = argparse.ArgumentParser(description="BotStory WZ patcher")
    ap.add_argument("--pack", default=str(PACK),
                    help="pack folder (default: $BOTSTORY_WZ_PACK, else patch-server/wz-pack beside the server repo)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("patch", help="add the BotStory content to a client's WZ files")
    p.add_argument("--client", required=True, help="folder with the base WZ files")
    p.add_argument("--out", help="write the patched files here instead of patching in place")
    p.add_argument("--copy-unchanged", action="store_true", help="with --out: also copy files that need no change")
    p.add_argument("--exe", help="also set the large-address-aware flag on this stock MapleStory.exe")
    c = sub.add_parser("check", help="verify that a client has the BotStory content")
    c.add_argument("--client", required=True)
    c.add_argument("--server-wz", help=f"server WZ XML folder (default {DEFAULT_SERVER_WZ})")
    r = sub.add_parser("residents", help="render Resident portraits and quest-log entries from this client's own files")
    r.add_argument("--client", required=True, help="folder with the client's WZ files")
    r.add_argument("--out", help="write the four patched files here instead of patching in place")
    r.add_argument("--export", default=str(DEFAULT_EXPORT),
                   help=f"folder with the server's resident-portraits.json and living-quest-log.json (default {DEFAULT_EXPORT})")
    r.add_argument("--preview", help="also write each portrait as <persona>.png into this folder")
    e = sub.add_parser("exe", help="set the large-address-aware flag on a stock MapleStory.exe (no pack needed)")
    e.add_argument("--exe", required=True, help="the stock GMS v83 MapleStory.exe")
    b = sub.add_parser("build", help="maintainers: rebuild the pack from the live BotStory client")
    b.add_argument("--repack", required=True, help="Community Repack WZ folder (the base BotStory was built on)")
    b.add_argument("--live", required=True, help="live BotStory 'Game Files' folder")
    b.add_argument("--cosmic", required=True, help="clean Cosmic client WZ folder")
    b.add_argument("--server-wz", default=str(DEFAULT_SERVER_WZ), help="server WZ XML folder")
    b.add_argument("--stock-client", required=True,
                   help="stock GMS v83 install (MapleStory.exe and the DLLs, from the v83 setup)")
    b.add_argument("--custom", help="Custom.wz to ship (default: the live one)")
    args = ap.parse_args()
    if args.cmd == "patch":
        cmd_patch(args)
    elif args.cmd == "check":
        sys.exit(cmd_check(args))
    elif args.cmd == "residents":
        cmd_residents(args)
    elif args.cmd == "exe":
        cmd_exe(args)
    else:
        cmd_build(args)


if __name__ == "__main__":
    main()
