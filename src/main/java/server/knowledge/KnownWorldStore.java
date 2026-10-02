package server.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code living_known_world} (changeset 45-living-knowledge): one row of encoded lived and heard entries per bot. The
 * timestamp is BIGINT epoch ms, never a TIMESTAMP, so the session time zone cannot shift it. Until the migration is
 * applied the table is missing: that is logged once and every bot keeps its world in memory only.
 */
final class KnownWorldStore implements PersonalKnowledge.Store {
    private static final Logger log = LoggerFactory.getLogger(KnownWorldStore.class);
    /** MySQL ER_NO_SUCH_TABLE. */
    private static final int NO_SUCH_TABLE = 1146;

    record Row(int characterId, String entries, long updatedMs) {
    }

    private volatile boolean missing;

    @Override
    public String load(int characterId) {
        if (missing) {
            return null;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT entries FROM living_known_world WHERE character_id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException failure) {
            failed("read", characterId, failure);
            if (missing) {
                return null;
            }
            throw new IllegalStateException("living_known_world read failed for " + characterId, failure); // not "no row"
        }
    }

    @Override
    public boolean save(List<Row> rows) {
        if (missing) {
            return true; // in memory only until the migration is applied
        }
        String sql = "INSERT INTO living_known_world (character_id, entries, updated_ms) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE entries = VALUES(entries), updated_ms = VALUES(updated_ms)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (Row row : rows) {
                ps.setInt(1, row.characterId());
                ps.setString(2, row.entries());
                ps.setLong(3, row.updatedMs());
                ps.addBatch();
            }
            ps.executeBatch();
            return true;
        } catch (SQLException failure) {
            failed("write", rows.size(), failure);
            return missing;
        }
    }

    @Override
    public void delete(int characterId) {
        if (missing) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM living_known_world WHERE character_id = ?")) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            failed("delete", characterId, failure);
        }
    }

    private void failed(String what, int which, SQLException failure) {
        if (failure.getErrorCode() == NO_SUCH_TABLE) {
            if (!missing) {
                missing = true;
                log.warn("[knowledge] living_known_world is missing (changeset 45-living-knowledge not applied); "
                        + "personal knowledge stays in memory only");
            }
            return;
        }
        log.warn("[knowledge] living_known_world {} failed ({})", what, which, failure);
    }
}
