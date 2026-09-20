package tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared password hashing helpers. Previously duplicated across
 * {@code client.Client#checkHash} and
 * {@code net.server.handlers.login.LoginPasswordHandler#hashpwSHA512}.
 */
public final class PasswordHasher {

    private static final int BCRYPT_WORK_FACTOR = 12;

    private PasswordHasher() {
    }

    public static String hashWithBcrypt(String plaintext) {
        return BCrypt.hashpw(plaintext, BCrypt.gensalt(BCRYPT_WORK_FACTOR));
    }

    public static boolean isBcryptHash(String hash) {
        return hash != null && hash.length() >= 2 && hash.charAt(0) == '$' && hash.charAt(1) == '2';
    }

    public static boolean checkBcrypt(String plaintext, String storedHash) {
        return BCrypt.checkpw(plaintext, storedHash);
    }

    public static String hashSha512Hex(String plaintext) {
        return digestHex("SHA-512", plaintext);
    }

    /**
     * Constant-time-ish compare of {@code plaintext} hashed with {@code algorithm}
     * against a stored hex hash. Used to recognise legacy SHA-1 / SHA-512 accounts
     * during the bcrypt migration.
     */
    public static boolean matchesLegacyHash(String storedHash, String algorithm, String plaintext) {
        return digestHex(algorithm, plaintext).equals(storedHash);
    }

    private static String digestHex(String algorithm, String plaintext) {
        try {
            MessageDigest digester = MessageDigest.getInstance(algorithm);
            byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
            digester.update(bytes, 0, bytes.length);
            return HexTool.toHexString(digester.digest()).replace(" ", "").toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing required hash algorithm: " + algorithm, e);
        }
    }
}
