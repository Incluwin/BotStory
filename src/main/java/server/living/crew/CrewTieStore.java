package server.living.crew;

import server.living.crew.CrewTies.SharedClear;
import server.living.population.records.Clear;
import server.living.population.records.ClearStore;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only reads for crew ties and rival banter (docs/BOT_CONCEPT_PLAN.md F6.4): an owner's shared clears (through
 * ClearStore, living_clear's own store) and two companions' newest companion_performance rows (a failed read throws,
 * so the caller tells "no data" from "could not read"). Nothing is written.
 */
public class CrewTieStore {
    private final ClearStore clears = new ClearStore();

    /** One companion_performance row: one companion's PQ or boss run (damage share null when not measured). */
    public record RunRow(int companionId, String activity, String outcome, int durationS, int deaths, Double damageShare,
                         long recordedAtMs) { }

    /**
     * Every living_clear the owner was in as a player, with the companions (P) in it, oldest first: read by the table's
     * own store (ClearStore, which alone talks to living_clear), empty when the read failed (it logs).
     */
    public List<SharedClear> clearsWith(int ownerId) {
        List<SharedClear> out = new ArrayList<>();
        for (Clear clear : clears.companionClearsOf(ownerId)) {
            List<Integer> companions = new ArrayList<>();
            clear.members().forEach(member -> companions.add(member.id()));
            out.add(new SharedClear(clear.id(), ownerId, clear.quest(), clear.durationMs(), companions));
        }
        return out;
    }

    /** The newest {@code limit} companion_performance rows of the two companions under this owner, newest first. */
    public List<RunRow> runs(int ownerId, int first, int second, int limit) {
        String sql = "SELECT companion_character_id, activity, outcome, duration_s, deaths, damage_share, recorded_at"
                + " FROM companion_performance WHERE owner_character_id = ? AND companion_character_id IN (?, ?)"
                + " ORDER BY id DESC LIMIT ?";
        List<RunRow> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, ownerId);
            ps.setInt(2, first);
            ps.setInt(3, second);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.math.BigDecimal share = rs.getBigDecimal(6);
                    Timestamp at = rs.getTimestamp(7);
                    out.add(new RunRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                            share == null ? null : share.doubleValue(), at == null ? 0L : at.getTime()));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("companion_performance read for " + ownerId + " failed", failure);
        }
        return out;
    }
}
