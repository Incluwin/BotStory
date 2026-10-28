package server.living.crew;

import server.living.crew.CrewTies.SharedClear;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only JDBC for crew ties and rival banter (docs/BOT_CONCEPT_PLAN.md F6.4): an owner's shared clears from
 * living_clear / living_clear_member, and two companions' newest companion_performance rows. Nothing is written. A
 * failed read throws, so the caller tells "no data" from "could not read".
 */
public class CrewTieStore {
    /** One companion_performance row: one companion's PQ or boss run (damage share null when not measured). */
    public record RunRow(int companionId, String activity, String outcome, int durationS, int deaths, Double damageShare,
                         long recordedAtMs) { }

    /** Every living_clear the owner was in as a player, with the companions (P) in it, oldest first. */
    public List<SharedClear> clearsWith(int ownerId) {
        String sql = "SELECT c.id, c.quest, c.duration_ms, m.character_id FROM living_clear_member o"
                + " JOIN living_clear c ON c.id = o.clear_id"
                + " JOIN living_clear_member m ON m.clear_id = o.clear_id AND m.kind = 'P'"
                + " WHERE o.character_id = ? AND o.kind = 'H' ORDER BY c.id";
        Map<Integer, Object[]> clears = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    Object[] clear = clears.computeIfAbsent(id, key -> new Object[3]);
                    if (clear[2] == null) {
                        clear[0] = rs.getString(2);
                        clear[1] = rs.getLong(3);
                        clear[2] = new ArrayList<Integer>();
                    }
                    @SuppressWarnings("unchecked")
                    List<Integer> members = (List<Integer>) clear[2];
                    members.add(rs.getInt(4));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("living_clear read for " + ownerId + " failed", failure);
        }
        List<SharedClear> out = new ArrayList<>();
        for (Map.Entry<Integer, Object[]> clear : clears.entrySet()) {
            @SuppressWarnings("unchecked")
            List<Integer> members = (List<Integer>) clear.getValue()[2];
            out.add(new SharedClear(clear.getKey(), ownerId, (String) clear.getValue()[0], (Long) clear.getValue()[1],
                    members));
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
