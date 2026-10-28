package server.living.population;

import config.EconomyConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.economy.ledger.MesoHolder;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Decision D1 (docs/ECONOMY_PLAN.md section 7, user 2026-10-28): citizens had minted 22 B meso, 37x their level's
 * {@link Market#expectedMeso} at Lv 80-99. Once, at boot before any citizen loads, every citizen purse (active and
 * retired; a hired hand's purse is its owner's companion's and stays) is set to a level-shaped draw: lognormal with a
 * median of PURSE_RESET_MEDIAN x expectedMeso and PURSE_RESET_SIGMA, stable per character. Every old purse is kept in
 * economy_purse_reset, whose rows also stop a second run; the change is booked as MesoAccount.RESET.
 */
final class CitizenPurseReset {
    private static final Logger log = LoggerFactory.getLogger(CitizenPurseReset.class);

    /** One citizen purse before and after. */
    record Row(int characterId, int level, int oldMeso, int newMeso, boolean retired) {
    }

    private CitizenPurseReset() {
    }

    /** Runs the reset if economy.CITIZEN_PURSE_RESET is on and it never ran; the number of purses it set. */
    static int runOnce() {
        EconomyConfig config = YamlConfig.config.economy;
        if (config == null || !config.CITIZEN_PURSE_RESET) {
            return 0;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            if (alreadyRan(con)) {
                return 0;
            }
            List<Row> rows = plan(con, config.PURSE_RESET_MEDIAN, config.PURSE_RESET_SIGMA);
            con.setAutoCommit(false);
            try {
                write(con, rows);
                con.commit();
            } catch (SQLException failure) {
                con.rollback();
                throw failure;
            } finally {
                con.setAutoCommit(true);
            }
            long before = 0L;
            long after = 0L;
            for (Row row : rows) {
                before += row.oldMeso();
                after += row.newMeso();
                MesoFlows.record(MesoAccount.RESET, row.retired() ? MesoHolder.RETIRED : MesoHolder.CITIZEN,
                        (long) row.newMeso() - row.oldMeso());
            }
            log.info("citizen purse reset (D1): {} purses, {} -> {} meso; old purses in economy_purse_reset",
                    rows.size(), before, after);
            return rows.size();
        } catch (SQLException failure) {
            log.error("citizen purse reset failed; purses are unchanged", failure);
            return 0;
        }
    }

    /** The purse a citizen of {@code level} gets: stable per character, never negative. */
    static int purse(int characterId, int level, double median, double sigma) {
        double z = new Random(characterId * 0x9E3779B97F4A7C15L).nextGaussian();
        double meso = median * Market.expectedMeso(level) * Math.exp(sigma * z);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE / 2, Math.round(meso)));
    }

    private static boolean alreadyRan(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM economy_purse_reset LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    private static List<Row> plan(Connection con, double median, double sigma) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT c.id, c.level, c.meso, lc.state FROM characters c"
                + " JOIN living_citizen lc ON lc.character_id = c.id"
                + " WHERE FIND_IN_SET('hired', lc.flags) = 0" // a hired hand's purse is its owner's companion's
                + " AND NOT EXISTS (SELECT 1 FROM living_persona_state p WHERE p.character_id = c.id)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt(1);
                int level = rs.getInt(2);
                rows.add(new Row(id, level, rs.getInt(3), purse(id, level, median, sigma),
                        "RETIRED".equals(rs.getString(4))));
            }
        }
        return rows;
    }

    private static void write(Connection con, List<Row> rows) throws SQLException {
        try (PreparedStatement backup = con.prepareStatement(
                "INSERT INTO economy_purse_reset (character_id, level, old_meso, new_meso) VALUES (?, ?, ?, ?)");
             PreparedStatement purse = con.prepareStatement("UPDATE characters SET meso = ? WHERE id = ?")) {
            for (Row row : rows) {
                backup.setInt(1, row.characterId());
                backup.setInt(2, row.level());
                backup.setInt(3, row.oldMeso());
                backup.setInt(4, row.newMeso());
                backup.addBatch();
                purse.setInt(1, row.newMeso());
                purse.setInt(2, row.characterId());
                purse.addBatch();
            }
            backup.executeBatch();
            purse.executeBatch();
        }
    }
}
