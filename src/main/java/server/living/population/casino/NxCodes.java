package server.living.population.casino;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Random;

/**
 * Cash Shop coupon codes worth NX (SoloMapling's NXCodeManager): a row in {@code nxcode} and one in {@code nxcode_items}
 * of type 4, which CouponCodeHandler credits as NX Credit when the code is entered in the Cash Shop. Ids come from the
 * tables' auto-increment (SoloMapling read MAX(id) + 1, which two sellers at once could collide on). Codes come from a
 * SecureRandom, never the casino's seeded Random: that one shuffles the decks and rolls the dice, and a code is worth
 * real NX to whoever can predict the next one.
 */
final class NxCodes {
    private static final Logger log = LoggerFactory.getLogger(NxCodes.class);
    /** No 0/O, 1/I/L: a code read out in a whisper is typed back without mix-ups. */
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int NX_CREDIT = 4;
    /** Far in the future: the server's expired-coupon sweep never takes it. */
    private static final long NEVER_EXPIRES = 8_901_234_567_890L;
    private static final int TRIES = 5;
    private static final SecureRandom SECURE = new SecureRandom();

    private NxCodes() {
    }

    /** "ABCDE-FGH23-JK456": three groups of five; the code itself is the fifteen characters without dashes. */
    static String generate(Random random) {
        StringBuilder code = new StringBuilder();
        for (int group = 0; group < 3; group++) {
            if (group > 0) {
                code.append('-');
            }
            for (int i = 0; i < 5; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        return code.toString();
    }

    /** A new code worth {@code nx}, stored; null when the database refused every try. */
    static String createUnique(int nx) {
        for (int i = 0; i < TRIES; i++) {
            String code = generate(SECURE);
            try (Connection con = DatabaseConnection.getConnection()) {
                if (create(con, code, nx)) {
                    return code;
                }
            } catch (SQLIntegrityConstraintViolationException taken) {
                log.debug("NX code {} already exists, drawing another", code);
            } catch (SQLException failure) {
                log.warn("NX code not stored", failure);
                return null;
            }
        }
        return null;
    }

    /** Stores {@code code} (dashes dropped) worth {@code nx}: both rows or neither. */
    static boolean create(Connection con, String code, int nx) throws SQLException {
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            int codeId;
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO nxcode (code, retriever, expiration) VALUES (?, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, code.replace("-", ""));
                ps.setLong(2, NEVER_EXPIRES);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        con.rollback();
                        return false;
                    }
                    codeId = keys.getInt(1);
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO nxcode_items (codeid, type, item, quantity) VALUES (?, ?, 0, ?)")) {
                ps.setInt(1, codeId);
                ps.setInt(2, NX_CREDIT);
                ps.setInt(3, nx);
                ps.executeUpdate();
            }
            con.commit();
            return true;
        } catch (SQLException failure) {
            con.rollback();
            throw failure;
        } finally {
            con.setAutoCommit(autoCommit);
        }
    }
}
