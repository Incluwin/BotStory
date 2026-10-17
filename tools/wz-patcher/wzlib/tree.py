"""Compare .img property trees and apply node edits to them.

An edit list turns one tree into another by node path ("skill/1001005/level/1/damage"):
  {"op": "set", "path": p, "after": sibling, "leaf": {...}}   set or add a value node
  {"op": "set", "path": p, "after": sibling, "blob": file}    set or add a whole subtree
  {"op": "ensure", ...}                                        like set, only when p is absent
  {"op": "del", "path": p}                                     remove a node
"after" names the sibling the node follows in the target tree (None = first), so added
nodes land where the target has them. Leaves are JSON; subtrees (canvases, sounds,
containers) are stored as small .img blobs holding one child.
"""
import copy

from .reader import WzImgParser
from .types import (
    AStr, PropNode,
    PROP_NULL, PROP_SHORT, PROP_SHORT_ALT, PROP_INT, PROP_FLOAT,
    PROP_DOUBLE, PROP_STRING, PROP_EXTENDED, PROP_LONG,
)
from .writer import write_img

_LEAF_TYPES = {PROP_NULL: "null", PROP_SHORT: "short", PROP_SHORT_ALT: "short11", PROP_INT: "int",
               PROP_FLOAT: "float", PROP_DOUBLE: "double", PROP_STRING: "str", PROP_LONG: "long"}
_LEAF_BYTES = {v: k for k, v in _LEAF_TYPES.items()}
_CONTAINERS = ("Property", "Canvas", "Shape2D#Convex2D")


def is_container(node):
    return node.type_byte == PROP_EXTENDED and node.extended_type in _CONTAINERS


def equal(a, b):
    """Deep equality. One-byte and two-byte strings with the same text count as equal."""
    if a.name != b.name or a.type_byte != b.type_byte or a.extended_type != b.extended_type:
        return False
    if a.raw_bytes != b.raw_bytes:
        return False
    if a.extended_type == "Canvas" and (
            getattr(a, '_canvas_unk', 0) != getattr(b, '_canvas_unk', 0) or
            getattr(a, '_canvas_has_children', 0) != getattr(b, '_canvas_has_children', 0)):
        return False
    if a.extended_type == "UOL" and getattr(a, '_uol_reserved', 0) != getattr(b, '_uol_reserved', 0):
        return False
    if isinstance(a.value, list) or isinstance(b.value, list):
        ca, cb = a.children(), b.children()
        return len(ca) == len(cb) and all(equal(x, y) for x, y in zip(ca, cb))
    return a.value == b.value


def _shell_equal(a, b):
    """Same container kind and, for canvases, the same pixels and flags (children aside)."""
    if a.type_byte != b.type_byte or a.extended_type != b.extended_type or not is_container(a):
        return False
    if a.extended_type == "Canvas":
        return (a.raw_bytes == b.raw_bytes and
                getattr(a, '_canvas_unk', 0) == getattr(b, '_canvas_unk', 0) and
                getattr(a, '_canvas_has_children', 0) == getattr(b, '_canvas_has_children', 0))
    return True


def _unique(nodes):
    names = [n.name for n in nodes]
    return len(names) == len(set(names))


def leaf_json(node):
    if node.type_byte == PROP_EXTENDED:
        if node.extended_type == "Shape2D#Vector2D":
            return {"t": "vector", "x": node.value['x'], "y": node.value['y']}
        if node.extended_type == "UOL":
            out = {"t": "uol", "v": str(node.value)}
            if getattr(node, '_uol_reserved', 0):
                out["r"] = node._uol_reserved
            if isinstance(node.value, AStr):
                out["a"] = 1
            return out
        return None
    out = {"t": _LEAF_TYPES[node.type_byte]}
    if node.type_byte != PROP_NULL:
        out["v"] = str(node.value) if node.type_byte == PROP_STRING else node.value
    if node.type_byte == PROP_STRING and isinstance(node.value, AStr):
        out["a"] = 1
    return out


def leaf_node(name, j):
    t = j["t"]
    if t == "vector":
        return PropNode(name, PROP_EXTENDED, {'x': j["x"], 'y': j["y"]}, extended_type="Shape2D#Vector2D")
    if t == "uol":
        node = PropNode(name, PROP_EXTENDED, AStr(j["v"]) if j.get("a") else j["v"], extended_type="UOL")
        node._uol_reserved = j.get("r", 0)
        return node
    tb = _LEAF_BYTES[t]
    v = j.get("v")
    if tb == PROP_STRING and j.get("a"):
        v = AStr(v)
    return PropNode(name, tb, v)


def subtree_blob(node, key_stream):
    return write_img(PropNode("", PROP_EXTENDED, [node], extended_type="Property"), key_stream)


def blob_subtree(data, key_stream):
    return WzImgParser(data, key_stream).parse().children()[0]


def diff(base, target):
    """Edits that turn base into target: a list of (op, path, after, node) tuples."""
    edits = []
    _diff(base, target, "", edits)
    return edits


def _diff(base, target, path, edits):
    bc, tc = base.children(), target.children()
    if not (_unique(bc) and _unique(tc)):
        edits.append(("set", path, None, target))
        return
    bmap = {n.name: n for n in bc}
    tnames = {n.name for n in tc}
    for n in bc:
        if n.name not in tnames:
            edits.append(("del", f"{path}/{n.name}" if path else n.name, None, None))
    prev = None
    for n in tc:
        p = f"{path}/{n.name}" if path else n.name
        b = bmap.get(n.name)
        if b is None:
            edits.append(("set", p, prev, n))
        elif not equal(b, n):
            if _shell_equal(b, n):
                _diff(b, n, p, edits)
            else:
                edits.append(("set", p, prev, n))
        prev = n.name


def _walk_to(root, parts, create):
    node = root
    for part in parts:
        nxt = node.find(part)
        if nxt is None:
            if not create:
                return None
            if not is_container(node):
                return None
            if not isinstance(node.value, list):
                node.value = []
            nxt = PropNode(part, PROP_EXTENDED, [], extended_type="Property")
            node.value.append(nxt)
        node = nxt
    return node


def apply_edit(root, op, path, after, node):
    """Apply one edit in place. Returns a short note when the base made it unusual."""
    parts = path.split("/") if path else []
    if op == "set" and not parts:  # whole tree
        root.value = copy.deepcopy(node).value
        return None
    parent = _walk_to(root, parts[:-1], create=(op != "del"))
    name = parts[-1]
    if parent is None:
        return None
    if not isinstance(parent.value, list):
        if op == "del":
            return None
        if not is_container(parent):
            return f"{path}: parent is not a container"
        parent.value = []
    kids = parent.value
    idx = next((i for i, c in enumerate(kids) if c.name == name), None)
    if op == "del":
        if idx is not None:
            kids.pop(idx)
        return None
    if op == "ensure" and idx is not None:
        return None
    new = copy.deepcopy(node)
    new.name = name
    if idx is not None:
        kids[idx] = new
        return None
    pos = 0
    if after is not None:
        j = next((i for i, c in enumerate(kids) if c.name == after), None)
        pos = len(kids) if j is None else j + 1
    kids.insert(pos, new)
    return None
