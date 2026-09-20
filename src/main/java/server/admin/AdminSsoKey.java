package server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * ed25519 keypair used to sign MapleEdit SSO attestations.
 *
 * <p>Key material lives at {@code <cosmic-root>/sso-key.json} ({@code priv_pkcs8}
 * base64, {@code pub_raw} 32-byte base64). Generated on first call and re-used
 * thereafter. The public key is also exposed via {@code /runtime/sso-pubkey}
 * so MapleEdit can bootstrap its trust anchor on first connect.
 *
 * <p>The address is {@code hex(sha256(pub_raw))} — a deterministic 32-byte
 * identifier in the same shape as IOTA's blake2b-derived addresses. We use
 * SHA-256 here to stay zero-dependency; if the project later picks up an IOTA
 * SDK, re-derive with blake2b and bump the {@code v} field of attestations.
 */
final class AdminSsoKey {
    private static final Logger log = LoggerFactory.getLogger(AdminSsoKey.class);
    private static final Object LOCK = new Object();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private static KeyPair cached;
    private static String cachedPubB64;
    private static String cachedAddrHex;

    private AdminSsoKey() {}

    static void ensure() throws IOException, NoSuchAlgorithmException {
        loadOrCreate();
    }

    static String pubkeyBase64() throws IOException, NoSuchAlgorithmException {
        loadOrCreate();
        return cachedPubB64;
    }

    static String addressHex() throws IOException, NoSuchAlgorithmException {
        loadOrCreate();
        return cachedAddrHex;
    }

    /**
     * Sign {@code payloadJson} (the canonical UTF-8 bytes of a JSON object)
     * and return the base64 signature, no padding.
     */
    static String sign(String payloadJson) throws Exception {
        loadOrCreate();
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(cached.getPrivate());
        sig.update(payloadJson.getBytes(StandardCharsets.UTF_8));
        return B64.encodeToString(sig.sign());
    }

    private static void loadOrCreate() throws IOException, NoSuchAlgorithmException {
        synchronized (LOCK) {
            if (cached != null) return;
            Path path = Path.of("sso-key.json");
            if (Files.exists(path)) {
                try {
                    cached = loadFromDisk(path);
                } catch (Exception e) {
                    log.warn("sso-key.json present but unreadable; regenerating", e);
                    cached = null;
                }
            }
            if (cached == null) {
                cached = generateAndPersist(path);
            }
            byte[] raw = rawPubkey(cached.getPublic());
            cachedPubB64 = B64.encodeToString(raw);
            cachedAddrHex = hexSha256(raw);
        }
    }

    private static KeyPair generateAndPersist(Path path) throws NoSuchAlgorithmException, IOException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = gen.generateKeyPair();
        byte[] privPkcs8 = kp.getPrivate().getEncoded();
        byte[] pubRaw = rawPubkey(kp.getPublic());
        String json = "{"
                + "\"v\":1,"
                + "\"priv_pkcs8\":\"" + B64.encodeToString(privPkcs8) + "\","
                + "\"pub_raw\":\"" + B64.encodeToString(pubRaw) + "\""
                + "}";
        Files.writeString(path, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows — NTFS ACLs inherit from the parent directory.
        }
        log.info("AdminSsoKey: generated new ed25519 keypair at {}", path.toAbsolutePath());
        return kp;
    }

    private static KeyPair loadFromDisk(Path path) throws Exception {
        String body = Files.readString(path, StandardCharsets.UTF_8);
        java.util.Map<String, String> flat = AdminJson.parseFlat(body);
        String privB64 = flat.get("priv_pkcs8");
        String pubB64 = flat.get("pub_raw");
        if (privB64 == null || pubB64 == null) {
            throw new IOException("sso-key.json missing priv_pkcs8 or pub_raw");
        }
        byte[] privPkcs8 = B64D.decode(privB64);
        byte[] pubRaw = B64D.decode(pubB64);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privPkcs8));
        PublicKey pub = decodeRawPublic(pubRaw);
        return new KeyPair(pub, priv);
    }

    /**
     * Extract the 32-byte compressed point from an Ed25519 public key. Java's
     * {@code EdECPublicKey.getPoint()} gives us the decoded point; we rebuild
     * the 32-byte wire form (little-endian y, high bit = x-parity) for wire
     * interop with non-Java verifiers like PyNaCl.
     */
    private static byte[] rawPubkey(PublicKey pub) {
        if (!(pub instanceof EdECPublicKey ed)) {
            throw new IllegalStateException("not an Ed25519 public key: " + pub.getClass());
        }
        EdECPoint point = ed.getPoint();
        byte[] y = point.getY().toByteArray();  // big-endian
        byte[] le = new byte[32];
        for (int i = 0; i < y.length && i < 32; i++) {
            le[i] = y[y.length - 1 - i];
        }
        if (point.isXOdd()) {
            le[31] |= (byte) 0x80;
        } else {
            le[31] &= (byte) 0x7F;
        }
        return le;
    }

    private static PublicKey decodeRawPublic(byte[] raw) throws Exception {
        if (raw.length != 32) throw new IOException("raw pubkey must be 32 bytes, got " + raw.length);
        boolean xOdd = (raw[31] & 0x80) != 0;
        byte[] yLe = raw.clone();
        yLe[31] &= 0x7F;
        byte[] yBe = new byte[32];
        for (int i = 0; i < 32; i++) yBe[i] = yLe[31 - i];
        java.math.BigInteger y = new java.math.BigInteger(1, yBe);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519,
                new EdECPoint(xOdd, y)));
    }

    private static String hexSha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
