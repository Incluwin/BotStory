package server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PasswordHasher;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Builds signed SSO attestations for MapleEdit.
 *
 * <p>Flow: the game client (or any trusted caller on the admin control port)
 * presents {@code account + password} and the character they want to claim.
 * We re-verify credentials against {@code accounts}, confirm the character
 * belongs to the account, then sign a canonical JSON payload with the
 * {@link AdminSsoKey} ed25519 key. The envelope MapleEdit receives is:
 *
 * <pre>
 * {
 *   "ok": true,
 *   "payload_json": "{...canonical json...}",
 *   "signature": "base64 ed25519 sig",
 *   "pubkey": "base64 32-byte ed25519 pub",
 *   "addr": "hex sha256 of pubkey"
 * }
 * </pre>
 *
 * <p>MapleEdit pins {@code pubkey} on first contact and thereafter accepts
 * only attestations signed by that key. The 5-minute TTL limits replay.
 */
final class AdminSsoAuth {
    private static final Logger log = LoggerFactory.getLogger(AdminSsoAuth.class);
    private static final long TTL_SECONDS = 300;  // 5 min
    private static final SecureRandom RNG = new SecureRandom();

    private AdminSsoAuth() {}

    /**
     * Verify credentials, confirm the character belongs to the account, and
     * return a signed attestation envelope. All error paths return the same
     * shape as the existing {@code /actions/cosmic-login} endpoint so the
     * MapleEdit caller can share error handling.
     */
    static String attest(String account, String password, String character) {
        if (account == null || account.isEmpty() || password == null || character == null
                || character.isEmpty()) {
            return "{\"ok\":false,\"error\":\"missing_fields\"}";
        }

        int accountId;
        int characterId = -1;
        int maxLevel = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, password, banned FROM accounts WHERE name = ?")) {
                ps.setString(1, account);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return "{\"ok\":false,\"error\":\"invalid_credentials\"}";
                    }
                    accountId = rs.getInt("id");
                    boolean banned = rs.getByte("banned") == 1;
                    String storedHash = rs.getString("password");
                    if (!verifyPassword(password, storedHash)) {
                        return "{\"ok\":false,\"error\":\"invalid_credentials\"}";
                    }
                    if (banned) {
                        return "{\"ok\":false,\"error\":\"banned\"}";
                    }
                }
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, level FROM characters WHERE accountid = ? AND name = ?")) {
                ps.setInt(1, accountId);
                ps.setString(2, character);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return "{\"ok\":false,\"error\":\"character_not_on_account\"}";
                    }
                    characterId = rs.getInt("id");
                }
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT COALESCE(MAX(level), 0) AS m FROM characters WHERE accountid = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) maxLevel = rs.getInt("m");
                }
            }
        } catch (SQLException e) {
            log.warn("cosmic-sso-attest SQL failure for account={}", account, e);
            return "{\"ok\":false,\"error\":\"sql\"}";
        }

        long now = System.currentTimeMillis() / 1000L;
        String nonce = newNonceHex(16);

        // Canonical JSON: keys in alphabetical order, no whitespace. Must match
        // how MapleEdit re-serializes the payload when verifying the signature.
        String payloadJson = "{"
                + "\"account\":" + AdminJson.str(account) + ","
                + "\"account_id\":" + accountId + ","
                + "\"character\":" + AdminJson.str(character) + ","
                + "\"character_id\":" + characterId + ","
                + "\"expires_at\":" + (now + TTL_SECONDS) + ","
                + "\"issued_at\":" + now + ","
                + "\"issuer\":\"cosmic\","
                + "\"max_level\":" + maxLevel + ","
                + "\"nonce\":" + AdminJson.str(nonce) + ","
                + "\"v\":1"
                + "}";

        try {
            String sig = AdminSsoKey.sign(payloadJson);
            String pubB64 = AdminSsoKey.pubkeyBase64();
            String addrHex = AdminSsoKey.addressHex();
            return "{"
                    + "\"ok\":true,"
                    + "\"payload_json\":" + AdminJson.str(payloadJson) + ","
                    + "\"signature\":" + AdminJson.str(sig) + ","
                    + "\"pubkey\":" + AdminJson.str(pubB64) + ","
                    + "\"addr\":" + AdminJson.str(addrHex)
                    + "}";
        } catch (Exception e) {
            log.error("cosmic-sso-attest signing failed", e);
            return "{\"ok\":false,\"error\":\"sign_failed\"}";
        }
    }

    /** Public-only endpoint: the key material MapleEdit pins as its trust anchor. */
    static String pubkeyBlob() {
        try {
            return "{"
                    + "\"ok\":true,"
                    + "\"pubkey\":" + AdminJson.str(AdminSsoKey.pubkeyBase64()) + ","
                    + "\"addr\":" + AdminJson.str(AdminSsoKey.addressHex()) + ","
                    + "\"alg\":\"ed25519\""
                    + "}";
        } catch (Exception e) {
            log.error("cosmic-sso pubkey read failed", e);
            return "{\"ok\":false,\"error\":\"key_unavailable\"}";
        }
    }

    private static boolean verifyPassword(String plaintext, String stored) {
        if (stored == null) return false;
        if (PasswordHasher.isBcryptHash(stored) && PasswordHasher.checkBcrypt(plaintext, stored)) {
            return true;
        }
        if (plaintext.equals(stored)) return true;  // legacy plaintext
        if (PasswordHasher.matchesLegacyHash(stored, "SHA-1", plaintext)) return true;
        if (PasswordHasher.matchesLegacyHash(stored, "SHA-512", plaintext)) return true;
        return false;
    }

    private static String newNonceHex(int nBytes) {
        byte[] buf = new byte[nBytes];
        RNG.nextBytes(buf);
        StringBuilder sb = new StringBuilder(nBytes * 2);
        for (byte b : buf) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
