"""Write .img property trees and whole .wz files.

Adapted from MapleEdit (Incluwin/MapleEdit, AGPL-3.0), wz/writer.py. Changes: Latin-1 one-byte
strings (AStr) are written back as one-byte strings, Shape2D#Convex2D is written from its
points, and type-2 directory names point at wherever the shared name lands in the new
directory (MapleEdit reused the old offset, which is only right when nothing before it moved).
"""
import os
import struct

from .crypto import rotate_left_32, WZ_OFFSET_CONSTANT
from .types import (
    AStr,
    PROP_NULL, PROP_SHORT, PROP_SHORT_ALT, PROP_INT, PROP_FLOAT,
    PROP_DOUBLE, PROP_STRING, PROP_EXTENDED, PROP_LONG,
)


class _Buffer:
    def __init__(self, key_stream):
        self.buf = bytearray()
        self.ks = key_stream

    @property
    def pos(self):
        return len(self.buf)

    def write_byte(self, v):
        self.buf.append(v & 0xFF)

    def write_int16(self, v):
        self.buf += struct.pack('<h', v)

    def write_uint16(self, v):
        self.buf += struct.pack('<H', v)

    def write_int32(self, v):
        self.buf += struct.pack('<i', v)

    def write_uint32(self, v):
        self.buf += struct.pack('<I', v)

    def write_int64(self, v):
        self.buf += struct.pack('<q', v)

    def write_float32(self, v):
        self.buf += struct.pack('<f', v)

    def write_float64(self, v):
        self.buf += struct.pack('<d', v)

    def write_bytes(self, data):
        self.buf += data

    def write_compressed_int(self, value):
        if value > 127 or value <= -128:
            self.write_byte(0x80)
            self.write_int32(value)
        else:
            self.write_byte(value)

    def write_compressed_long(self, value):
        if -127 <= value <= 127:
            self.write_byte(value)
        else:
            self.write_byte(0x80)
            self.write_int64(value)

    def write_wz_string(self, s):
        if len(s) == 0:
            self.write_byte(0)
            return
        ks = self.ks
        length = len(s)
        one_byte = all(ord(c) <= 0xFF for c in s) if isinstance(s, AStr) else \
            all(ord(c) <= 0x7F for c in s)
        if not one_byte:
            if length >= 127:
                self.write_byte(0x7F)
                self.write_int32(length)
            else:
                self.write_byte(length)
            out = bytearray(length * 2)
            mask = 0xAAAA
            for i, ch in enumerate(s):
                c = (ord(ch) & 0xFFFF) ^ mask
                if i * 2 + 1 < len(ks):
                    c ^= ks[i * 2] | (ks[i * 2 + 1] << 8)
                out[2 * i] = c & 0xFF
                out[2 * i + 1] = (c >> 8) & 0xFF
                mask = (mask + 1) & 0xFFFF
            self.buf += out
        else:
            if length > 127:
                self.write_byte(0x80)
                self.write_int32(length)
            else:
                self.write_byte(-length)
            out = bytearray(length)
            mask = 0xAA
            for i, ch in enumerate(s):
                out[i] = (ord(ch) ^ mask ^ (ks[i] if i < len(ks) else 0)) & 0xFF
                mask = (mask + 1) & 0xFF
            self.buf += out


class ImgWriter(_Buffer):
    """Serializes a PropNode tree into .img bytes."""

    def __init__(self, key_stream):
        super().__init__(key_stream)
        self.string_pool = {}

    def write_string_block(self, s, without_offset=0x73, with_offset=0x1B):
        if len(s) > 4 and s in self.string_pool:
            self.write_byte(with_offset)
            self.write_int32(self.string_pool[s])
        else:
            self.write_byte(without_offset)
            start = self.pos
            self.write_wz_string(s)
            self.string_pool.setdefault(s, start)

    def to_bytes(self):
        return bytes(self.buf)

    def write_property_tree(self, root):
        self.write_string_block("Property", 0x73, 0x1B)
        self._write_list(root.children())
        return self.to_bytes()

    def _write_list(self, nodes):
        self.write_uint16(0)
        self.write_compressed_int(len(nodes))
        for node in nodes:
            self._write_property(node)

    def _write_property(self, node):
        self.write_string_block(node.name, 0x00, 0x01)
        t = node.type_byte
        if t == PROP_NULL:
            self.write_byte(0x00)
        elif t in (PROP_SHORT, PROP_SHORT_ALT):
            self.write_byte(t)
            self.write_int16(int(node.value))
        elif t == PROP_INT:
            self.write_byte(PROP_INT)
            self.write_compressed_int(int(node.value))
        elif t == PROP_FLOAT:
            self.write_byte(PROP_FLOAT)
            if node.value == 0.0:
                self.write_byte(0x00)
            else:
                self.write_byte(0x80)
                self.write_float32(node.value)
        elif t == PROP_DOUBLE:
            self.write_byte(PROP_DOUBLE)
            self.write_float64(node.value)
        elif t == PROP_STRING:
            self.write_byte(PROP_STRING)
            self.write_string_block(node.value, 0x00, 0x01)
        elif t == PROP_EXTENDED:
            self.write_byte(PROP_EXTENDED)
            size_at = self.pos
            self.write_int32(0)
            self._write_extended(node)
            struct.pack_into('<i', self.buf, size_at, self.pos - size_at - 4)
        elif t == PROP_LONG:
            self.write_byte(PROP_LONG)
            self.write_compressed_long(int(node.value))
        else:
            raise ValueError(f"cannot write property type 0x{t:02x} ({node.name!r})")

    def _write_extended(self, node):
        ext = node.extended_type
        if ext == "Property":
            self.write_string_block("Property", 0x73, 0x1B)
            self._write_list(node.children())
        elif ext == "Shape2D#Vector2D":
            self.write_string_block(ext, 0x73, 0x1B)
            self.write_compressed_int(node.value['x'])
            self.write_compressed_int(node.value['y'])
        elif ext == "Shape2D#Convex2D":
            self.write_string_block(ext, 0x73, 0x1B)
            self.write_compressed_int(len(node.children()))
            for p in node.children():
                self.write_string_block("Shape2D#Vector2D", 0x73, 0x1B)
                self.write_compressed_int(p.value['x'])
                self.write_compressed_int(p.value['y'])
        elif ext == "Canvas":
            self.write_string_block("Canvas", 0x73, 0x1B)
            self.write_byte(getattr(node, '_canvas_unk', 0))
            has_children = getattr(node, '_canvas_has_children', 1 if node.children() else 0)
            self.write_byte(has_children)
            if has_children == 1:
                self._write_list(node.children())
            self.write_bytes(node.raw_bytes or b'')
        elif ext == "UOL":
            self.write_string_block("UOL", 0x73, 0x1B)
            self.write_byte(getattr(node, '_uol_reserved', 0))
            self.write_string_block(node.value, 0x00, 0x01)
        elif node.raw_bytes is not None:
            self.write_string_block(ext, 0x73, 0x1B)
            self.write_bytes(node.raw_bytes)
        else:
            raise ValueError(f"cannot write extended type {ext!r} ({node.name!r})")


def write_img(root, key_stream):
    return ImgWriter(key_stream).write_property_tree(root)


def compute_checksum(data):
    total = sum(data) & 0xFFFFFFFF
    return total - 0x100000000 if total >= 0x80000000 else total


class _DirWriter(_Buffer):
    def __init__(self, key_stream, version_hash, fstart):
        super().__init__(key_stream)
        self.hash = version_hash
        self.fstart = fstart

    def write_offset(self, value):
        enc = ((2 + self.pos) ^ 0xFFFFFFFF) & 0xFFFFFFFF
        enc = (enc * self.hash) & 0xFFFFFFFF
        enc = (enc - WZ_OFFSET_CONSTANT) & 0xFFFFFFFF
        enc = rotate_left_32(enc, enc & 0x1F)
        self.write_uint32((enc ^ ((value - self.fstart * 2) & 0xFFFFFFFF)) & 0xFFFFFFFF)


def _cint_len(n):
    return 5 if (n > 127 or n <= -128) else 1


def _str_len(s):
    if not s:
        return 1
    return 1 + 4 + len(s) if len(s) > 127 else 1 + len(s)


def _entry_size(e):
    name = 4 if e.original_name_type == 2 else _str_len(e.name)
    return 1 + name + _cint_len(e.block_size) + _cint_len(e.checksum) + 4


def _level_size(entries):
    return _cint_len(len(entries)) + sum(_entry_size(e) for e in entries)


def _tree_size(entries):
    return _level_size(entries) + sum(_tree_size(e.children) for e in entries if e.entry_type == 'dir')


def _assign_img_offsets(entries, cur):
    for e in entries:
        if e.entry_type == 'img':
            e.offset = cur
            cur += e.block_size
        else:
            cur = _assign_img_offsets(e.children, cur)
    return cur


def _assign_dir_offsets(entries, cur):
    """A dir entry's offset is where its child list starts; lists are laid out depth-first."""
    for e in entries:
        if e.entry_type == 'dir':
            e.offset = cur
            cur = _assign_dir_offsets(e.children, cur + _level_size(e.children))
    return cur


def _write_dir(w, entries, inline_names, type2_slots):
    w.write_compressed_int(len(entries))
    for e in entries:
        if e.original_name_type == 2:
            w.write_byte(2)
            type2_slots.append((w.pos, e))
            w.write_int32(0)
        else:
            inline_names.setdefault((e.name, e.entry_type), []).append(2 + w.pos)
            w.write_byte(3 if e.entry_type == 'dir' else 4)
            w.write_wz_string(e.name)
        w.write_compressed_int(e.block_size)
        w.write_compressed_int(e.checksum)
        w.write_offset(e.offset)
    for e in entries:
        if e.entry_type == 'dir':
            _write_dir(w, e.children, inline_names, type2_slots)


def build_directory(wz_file):
    """Assign every offset and return the directory bytes (between the version header and
    the first image)."""
    entries, fstart = wz_file.entries, wz_file.fstart
    image_start = fstart + 2 + _tree_size(entries)
    _assign_img_offsets(entries, image_start)
    _assign_dir_offsets(entries, fstart + 2 + _level_size(entries))
    while True:
        w = _DirWriter(wz_file.key_stream, wz_file.version_hash, fstart)
        inline_names, type2_slots = {}, []
        _write_dir(w, entries, inline_names, type2_slots)
        missing = [e for _, e in type2_slots if (e.name, e.entry_type) not in inline_names]
        if not missing:
            break
        for e in missing:  # the shared name is gone: store this one inline (sizes change, redo)
            e.original_name_type = 3 if e.entry_type == 'dir' else 4
            e.name_str_offset = None
        _assign_img_offsets(entries, fstart + 2 + _tree_size(entries))
        _assign_dir_offsets(entries, fstart + 2 + _level_size(entries))
    for pos, e in type2_slots:
        targets = inline_names[(e.name, e.entry_type)]
        # Keep the original target when it still holds this name (byte-identical saves).
        if e.name_str_offset not in targets:
            e.name_str_offset = targets[0]
        struct.pack_into('<i', w.buf, pos, e.name_str_offset)
    return bytes(w.buf)


def save_wz_file(wz_file, output_path):
    """Write wz_file (with any changed or added entries) to a new file at output_path."""
    if os.path.abspath(wz_file.filepath) == os.path.abspath(output_path):
        raise ValueError("refusing to overwrite the source file")
    dir_buf = build_directory(wz_file)
    tmp = str(output_path) + '.partial'
    with open(tmp, 'wb') as fd:
        fd.write(wz_file.header_bytes)
        fd.write(wz_file.version_header)
        fd.write(dir_buf)
        for e in wz_file.iter_imgs():
            if e.data is not None:
                fd.write(e.data)
            else:
                remaining, src = e.block_size, e.original_offset
                while remaining > 0:
                    chunk = wz_file.read_at(src, min(1 << 20, remaining))
                    if not chunk:
                        raise IOError(f"{wz_file.filepath}: short read at {src}")
                    fd.write(chunk)
                    src += len(chunk)
                    remaining -= len(chunk)
        end = fd.tell()
        fd.seek(4)
        fd.write(struct.pack('<Q', end - wz_file.fstart))
    os.replace(tmp, output_path)
