import sys, hashlib, zlib, struct
sys.path.insert(0, sys.argv[1])
from wzlib.reader import WzFile, _Reader
IDS = ['6108000','6108001','6108005','6109016','6109017','6109018','6109019','6109020','6109000','6109001','6109002','6109003','6109004']
def pixels(raw):
    r = _Reader(raw, b'')
    w = r.read_compressed_int(); h = r.read_compressed_int()
    fmt = r.read_compressed_int(); fmt2 = r.read_byte()
    r.pos += 4
    length = r.read_int32()
    r.pos += 1
    body = raw[r.pos:r.pos + length - 1]
    try:
        data = zlib.decompress(body)
    except Exception:
        data = body  # encrypted listWz blocks; compare raw
    return (w, h, fmt, fmt2), hashlib.sha1(data).hexdigest()[:12]
def walk(node, path, out):
    for c in node.children():
        p = path + '/' + c.name
        if c.extended_type == 'Canvas':
            out[p] = pixels(c.raw_bytes)
            walk(c, p, out)
        elif c.extended_type == 'UOL':
            out[p] = ('UOL', str(c.value))
        elif isinstance(c.value, list):
            walk(c, p, out)
with WzFile(sys.argv[2]) as wz:
    idx = wz.index()
    res = {}
    for i in IDS:
        e = idx.get(i + '.img')
        if e is None:
            print(i, 'missing'); continue
        root = wz.parse_img(e)
        out = {}
        from wzlib.types import PropNode
        walk(PropNode('root', 9, root if isinstance(root, list) else root.children()), '', out)
        res[i] = out
    for i, out in res.items():
        print('==', i, len(out), 'canvases/uols')
        for p, v in out.items():
            print('  ', p, v)
