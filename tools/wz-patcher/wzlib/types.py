"""Directory entries and property nodes of a WZ file.

Adapted from MapleEdit (Incluwin/MapleEdit, AGPL-3.0), wz/types.py.
"""
from dataclasses import dataclass, field
from typing import Any, Optional


@dataclass
class WzEntry:
    """A directory entry of a .wz file: a directory or an .img."""
    name: str
    entry_type: str              # 'dir' or 'img'
    block_size: int = 0
    checksum: int = 0
    offset: int = 0              # computed file offset
    original_offset: int = 0     # where the unchanged bytes sit in the source file
    data: Optional[bytes] = None  # new .img bytes; None = copy from the source file
    children: list = field(default_factory=list)
    # 3/4 = name stored inline (3 dir, 4 img). 2 = name stored once elsewhere in the
    # directory; Nexon's writer uses it for repeated names. Kept for byte-identical saves.
    original_name_type: Optional[int] = None
    name_str_offset: Optional[int] = None


PROP_NULL = 0x00
PROP_SHORT = 0x02
PROP_SHORT_ALT = 0x0B
PROP_INT = 0x03
PROP_FLOAT = 0x04
PROP_DOUBLE = 0x05
PROP_STRING = 0x08
PROP_EXTENDED = 0x09
PROP_LONG = 0x14


class AStr(str):
    """A string that was stored as one byte per character (the WZ 'ASCII' form).

    Such strings may hold bytes above 0x7F (code page text); they are decoded as
    Latin-1 and written back the same way so a parse/write round trip keeps them.
    """
    __slots__ = ()


@dataclass
class PropNode:
    """A node of an .img property tree with the type information needed to write it back."""
    name: str
    type_byte: int
    value: Any = None            # number, str, list[PropNode] (Property/Canvas/Convex), dict (vector)
    raw_bytes: Optional[bytes] = None  # Canvas pixel tail, Sound payload, unknown types
    extended_type: Optional[str] = None  # 'Property', 'Canvas', 'Shape2D#Vector2D', 'UOL', ...

    def find(self, name):
        if isinstance(self.value, list):
            for child in self.value:
                if isinstance(child, PropNode) and child.name == name:
                    return child
        return None

    def find_path(self, path):
        node = self
        for part in path.split('/'):
            node = node.find(part)
            if node is None:
                return None
        return node

    def children(self):
        return self.value if isinstance(self.value, list) else []
