"""Resident portraits and quest-log entries, generated from the server's export and the client's own WZ files.

The server exports what to show (tools\\living-world-client.bat):
  * ResidentPortraitExportMain -> target/resident-portraits.json: each Resident's NPC id, name, and the look
    the server gave its character (skin, face, hair, equipped items);
  * QuestLogExportMain -> target/living-quest-log.json: each living-world quest's client id and text.
This module renders every portrait from the client's Character.wz (so no Resident image ships with the
repository) and writes

    Npc.wz     <id>.img            info + stand/0 (the rendered look, origin at the feet); 9970999 is the
                                   quest-marker hitbox: an invisible NPC the size of the quest icon
    String.wz  Npc.img/<id>/name   the Resident's name
    Quest.wz   QuestInfo/Check/Act one entry per living quest (ids 30000-32767)
    Etc.wz     QuestCategory.img   the "Residents" group header of the client's quest list

Moved here from MapleEdit (Incluwin/MapleEdit, AGPL-3.0): app/living_world_portraits.py and
app/living_world_quests.py, which now call this module. The rendering is theirs, with the image work done in
plain Python (alpha compositing as Pillow does it, byte for byte) so the patcher needs no packages.

How the v83 client uses the files (checked in a v83 client):
  * the NPC dialog draws an NPC's image and name, never a player's avatar, so every Resident has an NPC;
  * it hit-tests an NPC as its image standing on the NPC's position and ignores a raised origin, so the server
    lifts the hitbox NPC itself (QUEST_MARKER_HITBOX_LIFT) and the image keeps a bottom-centre origin;
  * every NPC name in String.wz needs an image in Npc.wz: a name without one makes the client quit a few
    seconds after entering the game;
  * the quest list groups quests by `area` under the QuestCategory.img header, labels each with `parent` as
    plain text (colour codes print literally), and titles the detail pane with `name`; an expired end date
    keeps a quest off the "available" list, since Residents offer it in person.
"""
import struct
import zlib

from wzlib.reader import WzFile, WzImgParser
from wzlib.types import PropNode, WzEntry, PROP_EXTENDED, PROP_INT, PROP_STRING
from wzlib.writer import ImgWriter, compute_checksum

PORTRAIT_MIN, PORTRAIT_MAX = 9970000, 9970999
HITBOX_SIZE = (44, 46)  # the quest icons' largest frame
QUEST_MIN, QUEST_MAX = 30000, 32767
QUEST_IMGS = ("QuestInfo.img", "Check.img", "Act.img")
HIDE_FROM_AVAILABLE_END = "2000010100"  # an event end date long past: the client never lists the quest as available
CATEGORY_LABEL = "Residents"

# equipped inventory positions (cash look slot first) -> Character.wz folder
SLOTS = [((-101, -1), "Cap"), ((-102, -2), "Accessory"), ((-103, -3), "Accessory"), ((-104, -4), "Accessory"),
         ((-105, -5), None), ((-106, -6), "Pants"), ((-107, -7), "Shoes"), ((-108, -8), "Glove"),
         ((-109, -9), "Cape"), ((-110, -10), "Shield"), ((-11,), "Weapon")]


# ---------------------------------------------------------------------------------- images

class Rgba:
    """An RGBA image: width x height, 4 bytes a pixel, rows top to bottom."""

    def __init__(self, width, height, data=None):
        self.width, self.height = width, height
        self.data = bytearray(width * height * 4) if data is None else bytearray(data)

    def bgra(self):
        out = bytearray(self.data)
        out[0::4], out[2::4] = self.data[2::4], self.data[0::4]
        return bytes(out)

    def composite(self, src, x, y):
        """Pillow's Image.alpha_composite(src, (x, y)): src over this image, the same integer rounding."""
        dst, s, w = self.data, src.data, self.width
        for row in range(src.height):
            ty = y + row
            if not 0 <= ty < self.height:
                continue
            for col in range(src.width):
                tx = x + col
                if not 0 <= tx < w:
                    continue
                si = (row * src.width + col) * 4
                sa = s[si + 3]
                if sa == 0:
                    continue
                di = (ty * w + tx) * 4
                blend = dst[di + 3] * (255 - sa)
                outa255 = sa * 255 + blend
                coef1 = sa * 255 * 255 * 128 // outa255
                coef2 = 255 * 128 - coef1
                for c in range(3):
                    t = s[si + c] * coef1 + dst[di + c] * coef2 + (0x80 << 7)
                    dst[di + c] = ((((t >> 8) + t) >> 8) >> 7) & 0xFF
                t = outa255 + 0x80
                dst[di + 3] = (((t >> 8) + t) >> 8) & 0xFF


def png(image):
    """An RGBA PNG of an Rgba image (portrait previews)."""
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
    stride = image.width * 4
    raw = b"".join(b"\x00" + bytes(image.data[r * stride:(r + 1) * stride]) for r in range(image.height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def decode(canvas, key_stream):
    """Canvas pixels as an Rgba image (formats 1 BGRA4444, 2 BGRA8888, 513 RGB565), else None."""
    p = WzImgParser(canvas.raw_bytes, key_stream)
    width, height = p.read_compressed_int(), p.read_compressed_int()
    fmt = p.read_compressed_int() + p.read_byte()
    p.pos += 4
    size = p.read_int32() - 1
    p.pos += 1
    blob = canvas.raw_bytes[p.pos:p.pos + size]
    if blob[:1] != b"x":  # key-encrypted chunks: [int32 length][bytes xor key] ...
        plain, i = bytearray(), 0
        while i + 4 <= len(blob):
            length = struct.unpack_from("<i", blob, i)[0]
            i += 4
            plain += bytes(c ^ key_stream[j] for j, c in enumerate(blob[i:i + length]))
            i += length
        blob = bytes(plain)
    inflater = zlib.decompressobj()
    pixels = inflater.decompress(blob) + inflater.flush()
    if fmt == 2:
        out = Rgba(width, height, pixels[:width * height * 4])
        out.data[0::4], out.data[2::4] = out.data[2::4], out.data[0::4]
        return out
    out = bytearray()
    if fmt == 1:
        for i in range(0, width * height * 2, 2):
            lo, hi = pixels[i], pixels[i + 1]
            out += bytes(((hi & 0x0F) * 17, (lo >> 4) * 17, (lo & 0x0F) * 17, (hi >> 4) * 17))
        return Rgba(width, height, out)
    if fmt == 513:
        for i in range(0, width * height * 2, 2):
            v = pixels[i] | pixels[i + 1] << 8
            out += bytes((((v >> 11) & 0x1F) * 255 // 31, ((v >> 5) & 0x3F) * 255 // 63, (v & 0x1F) * 255 // 31, 255))
        return Rgba(width, height, out)
    return None


def canvas_tail(image):
    """Canvas raw tail for an Rgba image: BGRA8888, zlib-compressed."""
    def compressed_int(value):
        return struct.pack("<b", value) if -128 < value <= 127 else b"\x80" + struct.pack("<i", value)
    data = zlib.compress(image.bgra())
    return (compressed_int(image.width) + compressed_int(image.height) + compressed_int(2) + b"\x00"
            + b"\x00" * 4 + struct.pack("<i", len(data) + 1) + b"\x00" + data)


# ---------------------------------------------------------------------------------- rendering

def _norm(parts):
    out = []
    for part in parts:
        if part == "..":
            if out:
                out.pop()
        elif part and part != ".":
            out.append(part)
    return out


def resolve(root, parts, depth=0):
    """Node at a slash path inside an img, following UOL links."""
    node, trail = root, []
    for i, name in enumerate(parts):
        child = node.find(name) if node is not None and isinstance(node.value, list) else None
        if child is None:
            return None
        if child.extended_type == "UOL" and depth < 8:
            return resolve(root, _norm(trail + child.value.split("/")) + list(parts[i + 1:]), depth + 1)
        node, trail = child, trail + [name]
    return node


class Wardrobe:
    """Character.wz and the layer tables from Base.wz of one client."""

    def __init__(self, client_dir):
        self.wz = WzFile(client_dir / "Character.wz")
        self.imgs = {}
        base = WzFile(client_dir / "Base.wz")
        try:
            index = base.index()
            zmap, smap = base.parse_img(index["zmap.img"]), base.parse_img(index["smap.img"])
        finally:
            base.close()
        self.z = {node.name: i for i, node in enumerate(zmap.value)}  # 0 = front
        self.slots = {node.name: node.value for node in smap.value if isinstance(node.value, str)}

    def img(self, path):
        if path not in self.imgs:
            entries, node = self.wz.entries, None
            for part in path.split("/"):
                node = next((e for e in entries if e.name == part), None)
                if node is None:
                    break
                entries = node.children
            self.imgs[path] = self.wz.parse_img(node) if node is not None and node.entry_type == "img" else None
        return self.imgs[path]

    def close(self):
        self.wz.close()


def item_img(item_id):
    prefix = item_id // 10000
    folder = {100: "Cap", 101: "Accessory", 102: "Accessory", 103: "Accessory", 104: "Coat", 105: "Longcoat",
              106: "Pants", 107: "Shoes", 108: "Glove", 109: "Shield", 110: "Cape"}.get(prefix)
    if folder is None and 130 <= prefix < 170:
        folder = "Weapon"
    return None if folder is None else f"{folder}/{item_id:08d}.img"


def worn_items(equipped):
    """The items a portrait shows, from {inventory position: item id}: per slot the cash look first."""
    worn = {int(k): int(v) for k, v in equipped.items()}
    return [next(worn[p] for p in positions if p in worn) for positions, _ in SLOTS if any(p in worn for p in positions)]


def render(wardrobe, look):
    """The standing character of look {skin, face, hair, items} as (Rgba image, origin at the feet)."""
    key = wardrobe.wz.key_stream
    items = [(item, item_img(item)) for item in look["items"]]
    items = [(item, path, wardrobe.img(path)) for item, path in items if path and wardrobe.img(path) is not None]
    weapon = next((root for item, path, root in items if path.startswith("Weapon/")), None)
    stand = resolve(weapon, ["info", "stand"]) if weapon is not None else None
    stance = f"stand{stand.value if stand is not None and stand.value in (1, 2) else 1}"
    cap = next((root for item, path, root in items if path.startswith("Cap/")), None)
    cap_slot = resolve(cap, ["info", "vslot"]) if cap is not None else None
    covered = set() if cap_slot is None else {cap_slot.value[i:i + 2] for i in range(0, len(cap_slot.value), 2)}

    parts = []  # (root, path to the frame holding the canvases, is_hair)
    parts.append((wardrobe.img(f"{2000 + look['skin']:08d}.img"), [stance, "0"], False))
    parts.append((wardrobe.img(f"{12000 + look['skin']:08d}.img"), ["front"], False))
    parts.append((wardrobe.img(f"Face/{look['face']:08d}.img"), ["default"], False))
    parts.append((wardrobe.img(f"Hair/{look['hair']:08d}.img"), ["default"], True))
    for item, path, root in items:
        if path.startswith(("Cap/", "Accessory/")):
            parts.append((root, ["default"], False))
        elif resolve(root, [stance, "0"]) is not None:
            parts.append((root, [stance, "0"], False))
        elif resolve(root, ["stand1" if stance == "stand2" else "stand2", "0"]) is not None:
            parts.append((root, ["stand1" if stance == "stand2" else "stand2", "0"], False))

    anchors, layers = {}, []
    for root, frame_path, is_hair in parts:
        frame = resolve(root, frame_path) if root is not None else None
        if frame is None or not isinstance(frame.value, list):
            continue
        for child in frame.value:
            canvas = resolve(root, frame_path + [child.name])
            if is_hair and canvas is not None and canvas.extended_type != "Canvas" and child.name == "hairShade":
                canvas = resolve(root, frame_path + [child.name, str(look["skin"])])
            if canvas is None or canvas.extended_type != "Canvas":
                continue
            origin = canvas.find("origin").value if canvas.find("origin") is not None else {"x": 0, "y": 0}
            points = {v.name: v.value for v in (canvas.find("map").value if canvas.find("map") is not None else [])
                      if isinstance(v.value, dict)}
            z = canvas.find("z").value if canvas.find("z") is not None and isinstance(canvas.find("z").value, str) else ""
            if is_hair and covered and set(wardrobe.slots.get(z, "H?")[i:i + 2]
                                           for i in range(0, len(wardrobe.slots.get(z, "H?")), 2)) <= covered:
                continue
            anchor = next((k for k in points if k in anchors), None)
            if anchors and anchor is None:
                continue
            x = anchors[anchor][0] - points[anchor]["x"] if anchor else 0
            y = anchors[anchor][1] - points[anchor]["y"] if anchor else 0
            for k, v in points.items():
                anchors.setdefault(k, (x + v["x"], y + v["y"]))
            image = decode(canvas, key)
            if image is not None:
                layers.append((wardrobe.z.get(z, wardrobe.z.get("body", 0)), x - origin["x"], y - origin["y"], image))

    left = min(lx for _, lx, _, _ in layers)
    top = min(ly for _, _, ly, _ in layers)
    right = max(lx + im.width for _, lx, _, im in layers)
    bottom = max(ly + im.height for _, _, ly, im in layers)
    out = Rgba(right - left, bottom - top)
    for _, lx, ly, image in sorted(layers, key=lambda layer: -layer[0]):
        out.composite(image, lx - left, ly - top)
    return out, {"x": -left, "y": -top}


# ---------------------------------------------------------------------------------- WZ writing

def _prop(name, children=None):
    return PropNode(name=name, type_byte=PROP_EXTENDED, extended_type="Property", value=list(children or []))


def _int(name, value):
    return PropNode(name=name, type_byte=PROP_INT, value=int(value))


def _str(name, value):
    return PropNode(name=name, type_byte=PROP_STRING, value=str(value))


def _serialize(root, key_stream):
    writer = ImgWriter(key_stream)
    writer.write_property_tree(root)
    return writer.to_bytes()


def _set(entry, data):
    entry.data, entry.block_size, entry.checksum = data, len(data), compute_checksum(data)


def npc_img(image, origin):
    frame = PropNode(name="0", type_byte=PROP_EXTENDED, extended_type="Canvas", raw_bytes=canvas_tail(image),
                     value=[PropNode(name="origin", type_byte=PROP_EXTENDED, extended_type="Shape2D#Vector2D",
                                     value=origin)])
    frame._canvas_unk, frame._canvas_has_children = 0, 1
    return _prop("", [_prop("info"), _prop("stand", [frame])])


def hitbox_img():
    """The quest-marker hitbox: transparent, name hidden, standing on its position."""
    width, height = HITBOX_SIZE
    root = npc_img(Rgba(width, height), {"x": width // 2, "y": height})
    root.find("info").value.append(_int("hideName", 1))
    return root


def _portrait_id(name):
    return name.endswith(".img") and name[:-4].isdigit() and PORTRAIT_MIN <= int(name[:-4]) <= PORTRAIT_MAX


def patch_npc(wz, portraits, hitbox_id):
    """Replaces Npc.wz's 9970000-9970999 images with portraits {npc id: (Rgba, origin, name)} and the hitbox."""
    wz.entries[:] = [e for e in wz.entries if not _portrait_id(e.name)]
    imgs = [(npc_id, npc_img(image, origin)) for npc_id, (image, origin, _) in sorted(portraits.items())]
    for npc_id, root in imgs + [(hitbox_id, hitbox_img())]:
        entry = WzEntry(name=f"{npc_id}.img", entry_type="img", original_name_type=4)
        _set(entry, _serialize(root, wz.key_stream))
        wz.entries.append(entry)


def patch_string(wz, portraits, hitbox_id):
    """Names each portrait in String.wz Npc.img (and the hitbox 'Quest')."""
    entry = wz.index()["Npc.img"]
    root = wz.parse_img(entry)
    root.value = [c for c in root.value if not (c.name.isdigit() and PORTRAIT_MIN <= int(c.name) <= PORTRAIT_MAX)]
    names = [(npc_id, name) for npc_id, (_, _, name) in sorted(portraits.items())]
    for npc_id, name in names + [(hitbox_id, "Quest")]:
        root.value.append(_prop(str(npc_id), [_str("name", name)]))
    _set(entry, _serialize(root, wz.key_stream))


def quest_info_node(entry, order):
    return _prop(str(entry["logId"]), [
        _str("name", entry["name"]),
        _str("parent", entry["parent"]),
        _str("0", entry["available"]),
        _str("1", entry["inProgress"]),
        _str("2", entry["completed"]),
        _int("area", entry.get("area", 20)),
        _int("order", order),
    ])


def check_node(entry):
    start = [_int("lvmin", entry.get("lvmin") or 1), _str("end", HIDE_FROM_AVAILABLE_END)]
    if entry.get("npc"):
        start.append(_int("npc", entry["npc"]))
    return _prop(str(entry["logId"]), [
        _prop("0", start),
        _prop("1", _targets(entry, "items", "item") + _targets(entry, "mobs", "mob")),
    ])


def _targets(entry, key, node):
    """Check.img/1 item or mob list (id, count): what the Quest Helper and the detail pane count."""
    targets = entry.get(key) or []
    if not targets:
        return []
    return [_prop(node, [_prop(str(i), [_int("id", t["id"]), _int("count", t["count"])]) for i, t in enumerate(targets)])]


def act_node(entry):
    return _prop(str(entry["logId"]), [_prop("0"), _prop("1")])


def _replace_child(root, node):
    for i, child in enumerate(root.value):
        if isinstance(child, PropNode) and child.name == node.name:
            root.value[i] = node
            return
    root.value.append(node)


def patch_quests(wz, entries, stale=()):
    """Writes every exported quest into QuestInfo/Check/Act and drops other ids in 30000-32767 (and `stale`).

    No stock v83 quest uses 30000-32767, so whatever else sits there is an earlier export's.
    Returns the img names written."""
    builders = {"QuestInfo.img": lambda e, i: quest_info_node(e, i), "Check.img": lambda e, i: check_node(e),
                "Act.img": lambda e, i: act_node(e)}
    keep = {str(e["logId"]) for e in entries}
    drop = {str(s) for s in stale}
    index, touched = wz.index(), []
    for img in QUEST_IMGS:
        entry = index.get(img)
        if entry is None:
            continue
        root = wz.parse_img(entry)
        if not isinstance(root.value, list):
            root.value = []
        root.value = [c for c in root.value
                      if not (c.name in drop or (c.name.isdigit() and QUEST_MIN <= int(c.name) <= QUEST_MAX
                                                  and c.name not in keep))]
        for order, quest in enumerate(entries, start=1):
            _replace_child(root, builders[img](quest, order))
        _set(entry, _serialize(root, wz.key_stream))
        touched.append(img)
    return touched


def patch_category(wz, areas):
    """Names each living-quest area in Etc.wz QuestCategory.img; returns the img names written."""
    entry = wz.index().get("QuestCategory.img")
    if entry is None:
        return []
    root = wz.parse_img(entry)
    if not isinstance(root.value, list):
        root.value = []
    for area in sorted(areas):
        _replace_child(root, _str(str(area), CATEGORY_LABEL))
    _set(entry, _serialize(root, wz.key_stream))
    return ["QuestCategory.img"]


def render_portraits(client_dir, doc, only=None):
    """{npc id: (Rgba, origin, name)} for the export's portraits, rendered from the client's Character.wz."""
    wardrobe = Wardrobe(client_dir)
    out = {}
    try:
        for p in doc["portraits"]:
            if only and p["persona"] not in only:
                continue
            look = {"skin": p["skin"], "face": p["face"], "hair": p["hair"], "items": worn_items(p["equipped"])}
            image, origin = render(wardrobe, look)
            out[p["npcId"]] = (image, origin, p["name"])
    finally:
        wardrobe.close()
    return out
