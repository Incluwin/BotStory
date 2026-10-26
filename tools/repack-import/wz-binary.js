// Read-only v83 GMS .wz reader: the directory tree and .img property trees, with node order, value types and
// canvas sizes kept, so an image can be written back out as the server's XML dump (wz-xml.js). Ported from
// tools/wz-patcher/wzlib/reader.py by way of the 2026-10-25 town audit's ta-work/wzread.js. Never writes.
'use strict';
const fs = require('fs');
const crypto = require('crypto');

const GMS_IV = Buffer.from([0x4D, 0x23, 0xC7, 0x2B]);
const ZERO_IV = Buffer.alloc(4);
const USER_KEY = Buffer.from([
  0x13, 0, 0, 0, 0x08, 0, 0, 0, 0x06, 0, 0, 0, 0xB4, 0, 0, 0,
  0x1B, 0, 0, 0, 0x0F, 0, 0, 0, 0x33, 0, 0, 0, 0x52, 0, 0, 0]);

function keyStream(iv, length = 0x40000) {
  if (!iv.some(b => b)) return Buffer.alloc(length);
  const out = Buffer.alloc(length);
  let block = Buffer.alloc(16);
  for (let i = 0; i < 16; i++) block[i] = iv[i % 4];
  for (let pos = 0; pos < length; pos += 16) {
    const c = crypto.createCipheriv('aes-256-ecb', USER_KEY, null);
    c.setAutoPadding(false);
    block = Buffer.concat([c.update(block), c.final()]);
    block.copy(out, pos, 0, Math.min(16, length - pos));
  }
  return out;
}

function versionHash(ver) {
  let h = 0;
  for (const ch of String(ver)) h = ((h * 32) + ch.charCodeAt(0) + 1) >>> 0;
  return (~(((h >>> 24) & 0xFF) ^ ((h >>> 16) & 0xFF) ^ ((h >>> 8) & 0xFF) ^ (h & 0xFF))) & 0xFF;
}

class Reader {
  constructor(buf, ks, pos = 0) { this.b = buf; this.ks = ks; this.pos = pos; }
  u8() { return this.b[this.pos++]; }
  i8() { const v = this.b.readInt8(this.pos); this.pos += 1; return v; }
  i16() { const v = this.b.readInt16LE(this.pos); this.pos += 2; return v; }
  i32() { const v = this.b.readInt32LE(this.pos); this.pos += 4; return v; }
  u32() { const v = this.b.readUInt32LE(this.pos); this.pos += 4; return v; }
  i64() { const v = this.b.readBigInt64LE(this.pos); this.pos += 8; return v; }
  f32() { const v = this.b.readFloatLE(this.pos); this.pos += 4; return v; }
  f64() { const v = this.b.readDoubleLE(this.pos); this.pos += 8; return v; }
  cint() { const v = this.i8(); return v === -128 ? this.i32() : v; }
  clong() { const v = this.i8(); return v === -128 ? this.i64() : BigInt(v); }
  str() {
    const flag = this.i8();
    if (flag === 0) return '';
    const ks = this.ks;
    if (flag > 0) {
      const len = flag !== 127 ? flag : this.i32();
      if (len <= 0 || this.pos + len * 2 > this.b.length) throw new Error('bad unicode string length ' + len);
      let mask = 0xAAAA;
      let s = '';
      for (let i = 0; i < len; i++) {
        let c = this.b[this.pos + 2 * i] | (this.b[this.pos + 2 * i + 1] << 8);
        c ^= mask;
        if (i * 2 + 1 < ks.length) c ^= ks[i * 2] | (ks[i * 2 + 1] << 8);
        s += String.fromCharCode(c & 0xFFFF);
        mask = (mask + 1) & 0xFFFF;
      }
      this.pos += len * 2;
      return s;
    }
    const len = flag !== -128 ? -flag : this.i32();
    if (len <= 0 || this.pos + len > this.b.length) throw new Error('bad ascii string length ' + len);
    let mask = 0xAA;
    let s = '';
    for (let i = 0; i < len; i++) {
      s += String.fromCharCode(this.b[this.pos + i] ^ mask ^ (i < ks.length ? ks[i] : 0));
      mask = (mask + 1) & 0xFF;
    }
    this.pos += len;
    return s;
  }
}

class WzFile {
  constructor(path) {
    this.path = path;
    this.fd = fs.openSync(path, 'r');
    this.size = fs.fstatSync(this.fd).size;
    this.parseHeader();
  }

  readAt(off, len) {
    len = Math.max(0, Math.min(len, this.size - off));
    const b = Buffer.alloc(len);
    fs.readSync(this.fd, b, 0, len, off);
    return b;
  }

  parseHeader() {
    const h = this.readAt(0, 64);
    if (h.toString('latin1', 0, 4) !== 'PKG1') throw new Error('not a wz file: ' + this.path);
    this.fstart = h.readUInt32LE(12);
    const verHdr = this.readAt(this.fstart, 2).readUInt16LE(0);
    const dirBuf = this.readAt(this.fstart + 2, Math.min(16 * 1024 * 1024, this.size - this.fstart));
    const versions = [83];
    for (let v = 1; v < 1000; v++) if (v !== 83) versions.push(v);
    for (const ver of versions) {
      if (versionHash(ver) !== (verHdr & 0xFF)) continue;
      for (const iv of [GMS_IV, ZERO_IV]) if (this.tryDirectory(ver, iv, dirBuf)) return;
    }
    throw new Error('could not detect the wz version of ' + this.path);
  }

  tryDirectory(ver, iv, dirBuf) {
    const ks = keyStream(iv);
    const r = new Reader(dirBuf, ks);
    try {
      const count = r.cint();
      if (!(count > 0 && count < 100000)) return false;
      const t = r.u8();
      if (![2, 3, 4].includes(t)) return false;
      if (t === 3 || t === 4) {
        const name = r.str();
        if (!name || ![...name.slice(0, 32)].every(c => c.charCodeAt(0) >= 32 && c.charCodeAt(0) < 127)) return false;
      }
      this.version = ver;
      this.ks = ks;
      r.pos = 0;
      this.entries = this.directory(r);
      let start = this.fstart + 2 + r.pos;
      for (const e of this.images()) { e.offset = start; start += e.size; }
      return true;
    } catch (e) {
      return false;
    }
  }

  directory(r) {
    const count = r.cint();
    const entries = [];
    for (let i = 0; i < count; i++) {
      const et = r.u8();
      let name;
      let dir;
      if (et === 2) {
        const so = r.i32();
        const sr = new Reader(this.readAt(this.fstart + so, 1024), this.ks);
        dir = sr.u8() === 3;
        name = sr.str();
      } else if (et === 3 || et === 4) {
        name = r.str();
        dir = et === 3;
      } else {
        throw new Error('directory entry type ' + et);
      }
      const size = r.cint();
      r.cint(); // checksum
      r.u32(); // offset hash
      entries.push({ name, dir, size });
    }
    for (const e of entries) if (e.dir) e.children = this.directory(r);
    return entries;
  }

  *images(entries) {
    for (const e of entries || this.entries) {
      if (!e.dir) yield e; else yield* this.images(e.children);
    }
  }

  /** path ("Map/Map2/219000000.img") -> directory entry. */
  index() {
    if (!this._index) {
      const out = new Map();
      const walk = (es, pre) => {
        for (const e of es) {
          if (e.dir) walk(e.children, pre + e.name + '/'); else out.set(pre + e.name, e);
        }
      };
      walk(this.entries, '');
      this._index = out;
    }
    return this._index;
  }

  /** The image at path as a node tree, or null when the file has no such image. */
  image(path) {
    const e = this.index().get(path);
    if (!e) return null;
    const root = new ImageParser(this.readAt(e.offset, e.size), this.ks).parse();
    return { name: e.name, type: 'imgdir', children: root };
  }

  close() { fs.closeSync(this.fd); }
}

// A node: { name, type, value?, x?, y?, width?, height?, children? } where type is one of the server XML tags:
// imgdir, canvas, convex, sound, uol, null, short, int, long, float, double, string, vector.
class ImageParser extends Reader {
  stringBlock() {
    const t = this.u8();
    if (t === 0x00 || t === 0x73) return this.str();
    if (t === 0x01 || t === 0x1B) {
      const off = this.i32();
      const back = this.pos;
      this.pos = off;
      const s = this.str();
      this.pos = back;
      return s;
    }
    throw new Error('bad string block 0x' + t.toString(16) + ' at ' + (this.pos - 1));
  }

  parse() {
    this.pos = 0;
    if (this.stringBlock() !== 'Property') throw new Error('an image must start with Property');
    this.pos += 2;
    return this.list();
  }

  list() {
    const count = this.cint();
    const out = [];
    for (let i = 0; i < count; i++) {
      const name = this.stringBlock();
      const t = this.u8();
      if (t === 0x00) out.push({ name, type: 'null' });
      else if (t === 0x02 || t === 0x0B) out.push({ name, type: 'short', value: this.i16() });
      else if (t === 0x03 || t === 0x13) out.push({ name, type: 'int', value: this.cint() });
      else if (t === 0x04) out.push({ name, type: 'float', value: this.u8() === 0x80 ? this.f32() : 0 });
      else if (t === 0x05) out.push({ name, type: 'double', value: this.f64() });
      else if (t === 0x08) out.push({ name, type: 'string', value: this.stringBlock() });
      else if (t === 0x09) {
        const size = this.u32();
        const end = this.pos + size;
        out.push(this.extended(name, end));
        this.pos = end;
      } else if (t === 0x14) out.push({ name, type: 'long', value: this.clong() });
      else throw new Error('unknown property type 0x' + t.toString(16) + ' at ' + (this.pos - 1));
    }
    return out;
  }

  extended(name, end) {
    const tn = this.stringBlock();
    if (tn === 'Property') {
      this.pos += 2;
      return { name, type: 'imgdir', children: this.list() };
    }
    if (tn === 'Shape2D#Vector2D') return { name, type: 'vector', x: this.cint(), y: this.cint() };
    if (tn === 'Shape2D#Convex2D') {
      const count = this.cint();
      const children = [];
      for (let i = 0; i < count; i++) {
        const inner = this.stringBlock();
        if (inner !== 'Shape2D#Vector2D') throw new Error('Convex2D holds ' + inner);
        children.push({ name: String(i), type: 'vector', x: this.cint(), y: this.cint() });
      }
      return { name, type: 'convex', children };
    }
    if (tn === 'Canvas') {
      this.u8();
      const hasChildren = this.u8();
      let children = [];
      if (hasChildren === 1) {
        this.pos += 2;
        children = this.list();
      }
      const width = this.cint();
      const height = this.cint();
      return { name, type: 'canvas', width, height, children };
    }
    if (tn === 'UOL') {
      this.u8();
      return { name, type: 'uol', value: this.stringBlock() };
    }
    if (tn === 'Sound_DX8') return { name, type: 'sound' };
    throw new Error('unknown extended type ' + tn + ' for ' + name);
  }
}

module.exports = { WzFile };
