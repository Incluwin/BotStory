"""Read v83 .wz files: the directory and .img property trees.

Adapted from MapleEdit (Incluwin/MapleEdit, AGPL-3.0), wz/reader.py. Changes: one-byte
strings decode as Latin-1 (AStr) so they survive a round trip, Shape2D#Convex2D is parsed
(its inner type names can be offset references into the .img, so raw copies break).
"""
import os
import struct

from .crypto import GMS_IV, ZERO_IV, generate_key_stream, compute_version_hash
from .types import (
    AStr, WzEntry, PropNode,
    PROP_NULL, PROP_SHORT, PROP_SHORT_ALT, PROP_INT, PROP_FLOAT,
    PROP_DOUBLE, PROP_STRING, PROP_EXTENDED, PROP_LONG,
)


class _Reader:
    def __init__(self, data, key_stream, pos=0):
        self.data = data
        self.pos = pos
        self.ks = key_stream

    def read_byte(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def _unpack(self, fmt, size):
        v = struct.unpack_from(fmt, self.data, self.pos)[0]
        self.pos += size
        return v

    def read_int8(self):
        return self._unpack('<b', 1)

    def read_int16(self):
        return self._unpack('<h', 2)

    def read_int32(self):
        return self._unpack('<i', 4)

    def read_uint32(self):
        return self._unpack('<I', 4)

    def read_int64(self):
        return self._unpack('<q', 8)

    def read_float32(self):
        return self._unpack('<f', 4)

    def read_float64(self):
        return self._unpack('<d', 8)

    def read_compressed_int(self):
        v = self.read_int8()
        return self.read_int32() if v == -128 else v

    def read_compressed_long(self):
        v = self.read_int8()
        return self.read_int64() if v == -128 else v

    def read_wz_string(self):
        flag = self.read_int8()
        if flag == 0:
            return AStr("")
        ks = self.ks
        if flag > 0:
            length = flag if flag != 127 else self.read_int32()
            if length <= 0 or self.pos + length * 2 > len(self.data):
                raise ValueError(f"bad string length {length} at {self.pos}")
            raw = self.data[self.pos:self.pos + length * 2]
            self.pos += length * 2
            mask = 0xAAAA
            chars = []
            for i in range(length):
                c = raw[2 * i] | (raw[2 * i + 1] << 8)
                c ^= mask
                if i * 2 + 1 < len(ks):
                    c ^= ks[i * 2] | (ks[i * 2 + 1] << 8)
                chars.append(chr(c & 0xFFFF))
                mask = (mask + 1) & 0xFFFF
            return ''.join(chars)
        length = -flag if flag != -128 else self.read_int32()
        if length <= 0 or self.pos + length > len(self.data):
            raise ValueError(f"bad string length {length} at {self.pos}")
        raw = bytearray(self.data[self.pos:self.pos + length])
        self.pos += length
        mask = 0xAA
        for i in range(length):
            raw[i] ^= mask ^ (ks[i] if i < len(ks) else 0)
            mask = (mask + 1) & 0xFF
        return AStr(raw.decode('latin-1'))


class WzFile:
    """The header and directory of a .wz file. Image bytes are read on demand."""

    def __init__(self, filepath):
        self.filepath = str(filepath)
        self.fd = open(self.filepath, 'rb')
        self.filesize = os.fstat(self.fd.fileno()).st_size
        self.fstart = 0
        self.version = 0
        self.version_hash = 0
        self.key_stream = None
        self.iv = None
        self.entries = []
        self.header_bytes = b''
        self._ver_header_size = 2
        self._parse()

    def read_at(self, offset, length):
        self.fd.seek(offset)
        return self.fd.read(max(0, min(length, self.filesize - offset)))

    def _parse(self):
        header = self.read_at(0, 64)
        if header[0:4] != b'PKG1':
            raise ValueError(f"{self.filepath}: not a WZ file")
        self.fstart = struct.unpack_from('<I', header, 12)[0]
        self.header_bytes = self.read_at(0, self.fstart)
        self.version_header = self.read_at(self.fstart, 2)
        ver_header = struct.unpack_from('<H', self.version_header, 0)[0]
        span = min(16 * 1024 * 1024, self.filesize - self.fstart)
        dir_buf = self.read_at(self.fstart + 2, span)
        for ver in [83] + [v for v in range(1, 1000) if v != 83]:
            h, vh = compute_version_hash(ver)
            if vh != (ver_header & 0xFF):
                continue
            for iv in (GMS_IV, ZERO_IV):
                if self._try(ver, iv, h, dir_buf):
                    return
        raise ValueError(f"{self.filepath}: could not detect the WZ version and key")

    def _try(self, ver, iv, version_hash, dir_buf):
        ks = generate_key_stream(iv)
        r = _Reader(dir_buf, ks)
        try:
            count = r.read_compressed_int()
            if not 0 < count < 100000:
                return False
            t = r.read_byte()
            if t not in (2, 3, 4):
                return False
            if t in (3, 4):
                name = r.read_wz_string()
                if not name or not all(32 <= ord(c) < 127 for c in name[:32]):
                    return False
            self.version, self.version_hash, self.iv, self.key_stream = ver, version_hash, iv, ks
            r.pos = 0
            self.entries = self._parse_level(r)
            start = self.fstart + 2 + r.pos
            for e in self.iter_imgs():
                e.offset = e.original_offset = start
                start += e.block_size
            return True
        except (ValueError, IndexError, struct.error):
            return False

    def _parse_level(self, r):
        count = r.read_compressed_int()
        entries = []
        for _ in range(count):
            et = r.read_byte()
            name_str_offset = None
            if et == 2:
                name_str_offset = r.read_int32()
                sr = _Reader(self.read_at(self.fstart + name_str_offset, 1024), self.key_stream)
                is_dir = sr.read_byte() == 3
                name = sr.read_wz_string()
            elif et in (3, 4):
                name = r.read_wz_string()
                is_dir = et == 3
            elif et == 1:
                raise ValueError("directory entry type 1 is not supported")
            else:
                raise ValueError(f"unknown directory entry type {et}")
            block_size = r.read_compressed_int()
            checksum = r.read_compressed_int()
            r.read_uint32()  # encrypted offset; images are laid out in directory order
            entries.append(WzEntry(name=str(name), entry_type='dir' if is_dir else 'img',
                                   block_size=block_size, checksum=checksum,
                                   original_name_type=et, name_str_offset=name_str_offset))
        for e in entries:
            if e.entry_type == 'dir':
                e.children = self._parse_level(r)
        return entries

    def iter_imgs(self, entries=None):
        """Every .img entry in file order (pre-order, directory listing order)."""
        for e in self.entries if entries is None else entries:
            if e.entry_type == 'img':
                yield e
            else:
                yield from self.iter_imgs(e.children)

    def index(self):
        """{'Dir/Sub/name.img': entry} for every image."""
        out = {}

        def walk(entries, prefix):
            for e in entries:
                if e.entry_type == 'dir':
                    walk(e.children, prefix + e.name + '/')
                else:
                    out[prefix + e.name] = e
        walk(self.entries, '')
        return out

    def read_img_data(self, entry):
        if entry.data is not None:
            return entry.data
        return self.read_at(entry.original_offset, entry.block_size)

    def parse_img(self, entry):
        return WzImgParser(self.read_img_data(entry), self.key_stream).parse()

    def close(self):
        self.fd.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class WzImgParser(_Reader):
    """Parses .img bytes into a PropNode tree."""

    def __init__(self, data, key_stream):
        super().__init__(data, key_stream)

    def read_string_block(self):
        t = self.read_byte()
        if t in (0x00, 0x73):
            return self.read_wz_string()
        if t in (0x01, 0x1B):
            offset = self.read_int32()
            back = self.pos
            self.pos = offset
            s = self.read_wz_string()
            self.pos = back
            return s
        raise ValueError(f"bad string block type 0x{t:02x} at {self.pos - 1}")

    def parse(self):
        self.pos = 0
        if self.read_string_block() != "Property":
            raise ValueError("an .img must start with a Property")
        self.pos += 2
        return PropNode(name="", type_byte=PROP_EXTENDED, value=self._parse_list(),
                        extended_type="Property")

    def _parse_list(self):
        count = self.read_compressed_int()
        nodes = []
        for _ in range(count):
            name = str(self.read_string_block())
            t = self.read_byte()
            if t == PROP_NULL:
                nodes.append(PropNode(name, PROP_NULL))
            elif t in (PROP_SHORT, PROP_SHORT_ALT):
                nodes.append(PropNode(name, t, self.read_int16()))
            elif t == PROP_INT:
                nodes.append(PropNode(name, PROP_INT, self.read_compressed_int()))
            elif t == PROP_FLOAT:
                nodes.append(PropNode(name, PROP_FLOAT,
                                      self.read_float32() if self.read_byte() == 0x80 else 0.0))
            elif t == PROP_DOUBLE:
                nodes.append(PropNode(name, PROP_DOUBLE, self.read_float64()))
            elif t == PROP_STRING:
                nodes.append(PropNode(name, PROP_STRING, self.read_string_block()))
            elif t == PROP_EXTENDED:
                size = self.read_int32()
                end = self.pos + size
                nodes.append(self._parse_extended(name, end))
                self.pos = end
            elif t == PROP_LONG:
                nodes.append(PropNode(name, PROP_LONG, self.read_compressed_long()))
            else:
                raise ValueError(f"unknown property type 0x{t:02x} at {self.pos - 1}")
        return nodes

    def _parse_extended(self, name, end):
        type_name = str(self.read_string_block())
        if type_name == "Property":
            self.pos += 2
            return PropNode(name, PROP_EXTENDED, self._parse_list(), extended_type="Property")
        if type_name == "Shape2D#Vector2D":
            x = self.read_compressed_int()
            y = self.read_compressed_int()
            return PropNode(name, PROP_EXTENDED, {'x': x, 'y': y}, extended_type=type_name)
        if type_name == "Shape2D#Convex2D":
            count = self.read_compressed_int()
            points = []
            for i in range(count):
                inner = str(self.read_string_block())
                if inner != "Shape2D#Vector2D":
                    raise ValueError(f"Convex2D holds {inner!r}")
                x = self.read_compressed_int()
                y = self.read_compressed_int()
                points.append(PropNode(str(i), PROP_EXTENDED, {'x': x, 'y': y},
                                       extended_type="Shape2D#Vector2D"))
            return PropNode(name, PROP_EXTENDED, points, extended_type=type_name)
        if type_name == "Canvas":
            unk = self.read_byte()
            has_children = self.read_byte()
            children = None
            if has_children == 1:
                self.pos += 2
                children = self._parse_list()
            node = PropNode(name, PROP_EXTENDED, children, raw_bytes=bytes(self.data[self.pos:end]),
                            extended_type="Canvas")
            node._canvas_unk = unk
            node._canvas_has_children = has_children
            return node
        if type_name == "UOL":
            reserved = self.read_byte()
            node = PropNode(name, PROP_EXTENDED, self.read_string_block(), extended_type="UOL")
            node._uol_reserved = reserved
            return node
        # Sound_DX8 and anything else: opaque payload after the type name.
        return PropNode(name, PROP_EXTENDED, raw_bytes=bytes(self.data[self.pos:end]),
                        extended_type=type_name)


def canvas_size(node):
    """(width, height) of a Canvas node from its pixel tail."""
    r = _Reader(node.raw_bytes or b'\x00\x00', b'')
    return r.read_compressed_int(), r.read_compressed_int()
