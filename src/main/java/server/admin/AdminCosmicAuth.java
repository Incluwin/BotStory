package server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Cosmic-account credential verification and character lookup for the
 * MapleEdit "claim-your-character" login flow.
 *
 * <p>Password matching replicates {@link client.Client#login} — bcrypt first,
 * then SHA-1 / SHA-512 / plain-equality legacy paths so accounts predating
 * the BCRYPT_MIGRATION toggle still authenticate. Banned accounts are always
 * refused.</p>
 *
 * <p>Every method returns a JSON string (never throws); the dashboard treats
 * {@code ok=false} as a credential failure without surfacing the reason to
 * the end user.</p>
 */
final class AdminCosmicAuth {
    private static final Logger log = LoggerFactory.getLogger(AdminCosmicAuth.class);

    private AdminCosmicAuth() {}

    /**
     * Verify cosmic-account credentials and (on success) list the account's
     * characters so MapleEdit can ask the user which one to claim.
     *
     * Returns:
     *   ok=true   → {ok:true, account_id, banned:false, characters:[...]}
     *   ok=false  → {ok:false, error:"invalid_credentials"|"banned"|"sql"}
     */
    static String login(String account, String password) {
        if (account == null || account.isEmpty() || password == null) {
            return "{\"ok\":false,\"error\":\"missing_fields\"}";
        }

        int accountId;
        boolean banned;
        boolean passwordOk;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, password, banned FROM accounts WHERE name = ?")) {
                ps.setString(1, account);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return "{\"ok\":false,\"error\":\"invalid_credentials\"}";
                    }
                    accountId = rs.getInt("id");
                    banned = rs.getByte("banned") == 1;
                    String storedHash = rs.getString("password");
                    passwordOk = verifyPassword(password, storedHash);
                }
            }

            if (!passwordOk) {
                return "{\"ok\":false,\"error\":\"invalid_credentials\"}";
            }
            if (banned) {
                return "{\"ok\":false,\"error\":\"banned\"}";
            }

            AdminJson.Arr chars = new AdminJson.Arr();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, name, level, job FROM characters "
                            + "WHERE accountid = ? ORDER BY level DESC, name ASC")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        chars.obj()
                                .num("id", rs.getInt("id"))
                                .str("name", rs.getString("name"))
                                .num("level", rs.getInt("level"))
                                .num("job", rs.getInt("job"));
                    }
                }
            }

            return "{\"ok\":true,\"account_id\":" + accountId
                    + ",\"characters\":" + chars + "}";
        } catch (SQLException e) {
            log.warn("cosmic-login SQL failure for account={}", account, e);
            return "{\"ok\":false,\"error\":\"sql\"}";
        }
    }

    /**
     * Max level across every character on the given account. Used by the
     * MapleEdit auto-tier sweep (level ≥ 30 → tier 1).
     *
     * Returns {@code {ok:true, account_id, max_level, character_count}} or
     * {@code {ok:false, error:"sql"|"bad_account"}}.
     */
    static String maxLevel(int accountId) {
        if (accountId <= 0) return "{\"ok\":false,\"error\":\"bad_account\"}";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT COALESCE(MAX(level), 0) AS max_level, COUNT(*) AS n "
                             + "FROM characters WHERE accountid = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "{\"ok\":true,\"account_id\":" + accountId
                            + ",\"max_level\":0,\"character_count\":0}";
                }
                int max = rs.getInt("max_level");
                int count = rs.getInt("n");
                return "{\"ok\":true,\"account_id\":" + accountId
                        + ",\"max_level\":" + max
                        + ",\"character_count\":" + count + "}";
            }
        } catch (SQLException e) {
            log.warn("cosmic max-level SQL failure for accountId={}", accountId, e);
            return "{\"ok\":false,\"error\":\"sql\"}";
        }
    }

    /**
     * Replicates {@link client.Client#login}'s password matching. bcrypt is
     * preferred; the SHA-1 / SHA-512 / plain-equality fallbacks exist so
     * accounts created before the bcrypt migration still authenticate.
     */
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
}
