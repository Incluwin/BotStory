"""WZ key stream, version hash and offset encryption for v83 GMS WZ files.

The key stream is AES-256-ECB applied repeatedly to the IV, so this module carries a small
pure-Python AES (encryption only) to keep the patcher free of third-party packages.
Adapted from MapleEdit (Incluwin/MapleEdit, AGPL-3.0), wz/crypto.py.
"""

GMS_IV = bytes([0x4D, 0x23, 0xC7, 0x2B])
ZERO_IV = bytes(4)

USER_KEY = bytes([
    0x13, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00,
    0x06, 0x00, 0x00, 0x00, 0xB4, 0x00, 0x00, 0x00,
    0x1B, 0x00, 0x00, 0x00, 0x0F, 0x00, 0x00, 0x00,
    0x33, 0x00, 0x00, 0x00, 0x52, 0x00, 0x00, 0x00,
])

WZ_OFFSET_CONSTANT = 0x581C3F6D
KEY_STREAM_LENGTH = 0x100000


def _sbox():
    sbox = [0] * 256
    p = q = 1
    while True:
        # p *= 3 in GF(2^8); q /= 3
        p = p ^ ((p << 1) & 0xFF) ^ (0x1B if p & 0x80 else 0)
        q ^= q << 1
        q ^= q << 2
        q ^= q << 4
        q &= 0xFF
        if q & 0x80:
            q ^= 0x09
        x = q ^ (q << 1 | q >> 7) ^ (q << 2 | q >> 6) ^ (q << 3 | q >> 5) ^ (q << 4 | q >> 4)
        sbox[p] = (x ^ 0x63) & 0xFF
        if p == 1:
            break
    sbox[0] = 0x63
    return sbox


SBOX = _sbox()


def _xtime(b):
    return ((b << 1) ^ 0x1B) & 0xFF if b & 0x80 else b << 1


def _tables():
    t0, t1, t2, t3 = [0] * 256, [0] * 256, [0] * 256, [0] * 256
    for i, s in enumerate(SBOX):
        s2 = _xtime(s)
        s3 = s2 ^ s
        w = (s2 << 24) | (s << 16) | (s << 8) | s3
        t0[i] = w
        t1[i] = ((w >> 8) | (w << 24)) & 0xFFFFFFFF
        t2[i] = ((w >> 16) | (w << 16)) & 0xFFFFFFFF
        t3[i] = ((w >> 24) | (w << 8)) & 0xFFFFFFFF
    return t0, t1, t2, t3


T0, T1, T2, T3 = _tables()


def expand_key(key):
    nk, rounds = 8, 14
    words = [int.from_bytes(key[4 * i:4 * i + 4], "big") for i in range(nk)]
    rcon = 1
    for i in range(nk, 4 * (rounds + 1)):
        t = words[i - 1]
        if i % nk == 0:
            t = ((t << 8) | (t >> 24)) & 0xFFFFFFFF
            t = (SBOX[t >> 24] << 24) | (SBOX[(t >> 16) & 0xFF] << 16) | \
                (SBOX[(t >> 8) & 0xFF] << 8) | SBOX[t & 0xFF]
            t ^= rcon << 24
            rcon = _xtime(rcon)
        elif i % nk == 4:
            t = (SBOX[t >> 24] << 24) | (SBOX[(t >> 16) & 0xFF] << 16) | \
                (SBOX[(t >> 8) & 0xFF] << 8) | SBOX[t & 0xFF]
        words.append(words[i - nk] ^ t)
    return words, rounds


_ROUND_KEYS, _ROUNDS = expand_key(USER_KEY)


def aes256_encrypt_block(block, round_keys=None):
    """AES-256 encryption of one 16-byte block (under USER_KEY unless round_keys are given)."""
    rk = round_keys or _ROUND_KEYS
    s0 = int.from_bytes(block[0:4], "big") ^ rk[0]
    s1 = int.from_bytes(block[4:8], "big") ^ rk[1]
    s2 = int.from_bytes(block[8:12], "big") ^ rk[2]
    s3 = int.from_bytes(block[12:16], "big") ^ rk[3]
    k = 4
    for _ in range(_ROUNDS - 1):
        n0 = T0[s0 >> 24] ^ T1[(s1 >> 16) & 0xFF] ^ T2[(s2 >> 8) & 0xFF] ^ T3[s3 & 0xFF] ^ rk[k]
        n1 = T0[s1 >> 24] ^ T1[(s2 >> 16) & 0xFF] ^ T2[(s3 >> 8) & 0xFF] ^ T3[s0 & 0xFF] ^ rk[k + 1]
        n2 = T0[s2 >> 24] ^ T1[(s3 >> 16) & 0xFF] ^ T2[(s0 >> 8) & 0xFF] ^ T3[s1 & 0xFF] ^ rk[k + 2]
        n3 = T0[s3 >> 24] ^ T1[(s0 >> 16) & 0xFF] ^ T2[(s1 >> 8) & 0xFF] ^ T3[s2 & 0xFF] ^ rk[k + 3]
        s0, s1, s2, s3 = n0, n1, n2, n3
        k += 4
    out = bytearray(16)
    for i, (a, b, c, d) in enumerate(((s0, s1, s2, s3), (s1, s2, s3, s0),
                                     (s2, s3, s0, s1), (s3, s0, s1, s2))):
        w = ((SBOX[a >> 24] << 24) | (SBOX[(b >> 16) & 0xFF] << 16) |
             (SBOX[(c >> 8) & 0xFF] << 8) | SBOX[d & 0xFF]) ^ rk[k + i]
        out[4 * i:4 * i + 4] = w.to_bytes(4, "big")
    return bytes(out)


_KEY_CACHE = {}


def generate_key_stream(iv, length=KEY_STREAM_LENGTH):
    """The WZ key stream for a 4-byte IV: AES-256-ECB applied repeatedly to the IV block."""
    if length == 0 or not any(iv):
        return bytes(length)
    cached = _KEY_CACHE.get(iv)
    if cached is not None and len(cached) >= length:
        return cached[:length]
    result = bytearray(length)
    block = bytes(iv[i % 4] for i in range(16))
    for pos in range(0, length, 16):
        block = aes256_encrypt_block(block)
        result[pos:pos + 16] = block[:min(16, length - pos)]
    stream = bytes(result)
    _KEY_CACHE[iv] = stream
    return stream


def compute_version_hash(version):
    """Version hash and the one-byte version header of a WZ version number."""
    h = 0
    for ch in str(version):
        h = (h * 32 + ord(ch) + 1) & 0xFFFFFFFF
    header = (~(((h >> 24) & 0xFF) ^ ((h >> 16) & 0xFF) ^ ((h >> 8) & 0xFF) ^ (h & 0xFF))) & 0xFF
    return h, header


def rotate_left_32(val, shift):
    shift &= 0x1F
    return ((val << shift) | (val >> (32 - shift))) & 0xFFFFFFFF
