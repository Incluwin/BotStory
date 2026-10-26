"""Cryssea's look: a Custom.wz image for the iTCG card-shop girl (docs/ITCG_MASTERIA_PLAN.md section 5).

Cryssea (NPC 9010018) has a name in every base's String.wz and no Npc.wz image in any of them, and a client that is
sent an NPC without an image closes. kaentake mounts Custom.wz as an overlay that answers for a whole image the base
WZ lacks (resman.cpp, raw__OnGetLocalObject_hook; UI/UIWindowEx.img is served the same way), so this writes

    Custom.wz  Npc/9010018.img   info/link = another NPC's id (7 digits, as in Npc.wz)

and the client draws that NPC's image for her, the same `link` 35 stock Npc.wz images use. The server places her only
while server mod `cryssea` is on (server.mods.GatedNpcs); turn it on after every client has this.
"""
from wzlib.types import PropNode, WzEntry, PROP_EXTENDED, PROP_STRING
from wzlib.writer import ImgWriter, compute_checksum

CRYSSEA = 9010018
DEFAULT_LOOK = 1052122  # Tina, the Kerning Square store clerk: a shop look for the card-shop girl


def link_img(look_id):
    """An Npc.wz image that is only a link to {look_id}'s image."""
    link = PropNode(name="link", type_byte=PROP_STRING, value=f"{int(look_id):07d}")
    info = PropNode(name="info", type_byte=PROP_EXTENDED, extended_type="Property", value=[link])
    return PropNode(name="", type_byte=PROP_EXTENDED, extended_type="Property", value=[info])


def patch_custom(wz, npc_id=CRYSSEA, look_id=DEFAULT_LOOK):
    """Adds Npc/<npc_id>.img to an open Custom.wz, or replaces it: an info/link to {look_id}."""
    npc_dir = next((e for e in wz.entries if e.entry_type == "dir" and e.name == "Npc"), None)
    if npc_dir is None:
        npc_dir = WzEntry(name="Npc", entry_type="dir", original_name_type=3)
        wz.entries.append(npc_dir)
    name = f"{int(npc_id):07d}.img"
    npc_dir.children[:] = [e for e in npc_dir.children if e.name != name]
    writer = ImgWriter(wz.key_stream)
    writer.write_property_tree(link_img(look_id))
    data = writer.to_bytes()
    entry = WzEntry(name=name, entry_type="img", original_name_type=4)
    entry.data, entry.block_size, entry.checksum = data, len(data), compute_checksum(data)
    npc_dir.children.append(entry)
    return f"Npc/{name}"


def linked(wz, npc_id=CRYSSEA):
    """The NPC id a written Custom.wz links {npc_id} to, or None."""
    entry = wz.index().get(f"Npc/{int(npc_id):07d}.img")
    if entry is None:
        return None
    node = wz.parse_img(entry).find_path("info/link")
    return int(node.value) if node is not None else None
