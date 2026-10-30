package server.living.population.records;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.store.SqlTime;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC for the clear records (living_clear, living_clear_member). Every read degrades to empty and every write logs on
 * failure, so a clear never stops on the database. Not final: tests keep it in memory.
 */
public class ClearStore {
    private static final Logger log = LoggerFactory.getLogger(ClearStore.class);
    private static final String COLUMNS = "id, quest, instance, channel, map_id, started_at, cleared_at, duration_ms, kind,"
            + " crew_id, members";

    /** Stores a clear and who was in it in one commit; its id, or 0 when the write failed. */
    public int insert(Clear clear) {
        String sql = SqlTime.utc("INSERT INTO living_clear (quest, instance, channel, map_id, started_at, cleared_at,"
                + " duration_ms, kind, crew_id, members) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, clear.quest());
                    ps.setString(2, clear.instance().length() > 64 ? clear.instance().substring(0, 64) : clear.instance());
                    ps.setInt(3, clear.channel());
                    ps.setInt(4, clear.mapId());
                    SqlTime.setNullable(ps, 5, clear.startedAtMs());
                    SqlTime.set(ps, 6, clear.clearedAtMs());
                    ps.setLong(7, clear.durationMs());
                    ps.setString(8, clear.kind().name());
                    if (clear.crewId() > 0) {
                        ps.setInt(9, clear.crewId());
                    } else {
                        ps.setNull(9, java.sql.Types.INTEGER);
                    }
                    ps.setString(10, clear.encodeMembers());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        id = keys.next() ? keys.getInt(1) : 0;
                    }
                }
                if (id > 0 && !clear.members().isEmpty()) {
                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT IGNORE INTO living_clear_member (clear_id, character_id, kind) VALUES (?, ?, ?)")) {
                        for (Clear.Member member : clear.members()) {
                            ps.setInt(1, id);
                            ps.setInt(2, member.id());
                            ps.setString(3, String.valueOf(member.kind()));
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                con.commit();
                return id;
            } catch (SQLException failure) {
                con.rollback();
                throw failure;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            log.warn("living_clear insert failed for {}", clear.quest(), failure);
            return 0;
        }
    }

    /** Each quest's first clear (the lowest id) and its fastest timed one (ties: the earlier), at most two rows a quest. */
    public List<Clear> records() {
        List<Clear> out = new ArrayList<>();
        String quests = "SELECT quest, MIN(id) AS first_id FROM living_clear GROUP BY quest";
        String fastest = SqlTime.utc("SELECT " + COLUMNS + " FROM living_clear WHERE quest = ? AND duration_ms > 0"
                + " ORDER BY duration_ms, id LIMIT 1");
        String byId = SqlTime.utc("SELECT " + COLUMNS + " FROM living_clear WHERE id = ?");
        try (Connection con = DatabaseConnection.getConnection()) {
            Map<String, Integer> firsts = new HashMap<>();
            try (PreparedStatement ps = con.prepareStatement(quests); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    firsts.put(rs.getString(1), rs.getInt(2));
                }
            }
            for (Map.Entry<String, Integer> quest : firsts.entrySet()) {
                try (PreparedStatement ps = con.prepareStatement(byId)) {
                    ps.setInt(1, quest.getValue());
                    readAll(ps, out);
                }
                try (PreparedStatement ps = con.prepareStatement(fastest)) {
                    ps.setString(1, quest.getKey());
                    readAll(ps, out);
                }
            }
        } catch (SQLException failure) {
            log.warn("living_clear records read failed", failure);
        }
        return out;
    }

    /** Character id -> quest -> its fastest timed clear, for players only. */
    public Map<Integer, Map<String, Long>> playerBests() {
        Map<Integer, Map<String, Long>> out = new HashMap<>();
        String sql = "SELECT m.character_id, c.quest, MIN(c.duration_ms) FROM living_clear c"
                + " JOIN living_clear_member m ON m.clear_id = c.id"
                + " WHERE m.kind = 'H' AND c.duration_ms > 0 GROUP BY m.character_id, c.quest";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getInt(1), id -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
            }
        } catch (SQLException failure) {
            log.warn("living_clear player bests read failed", failure);
        }
        return out;
    }

    /** Crew id -> quest -> its fastest timed clear. */
    public Map<Integer, Map<String, Long>> crewBests() {
        Map<Integer, Map<String, Long>> out = new HashMap<>();
        String sql = "SELECT crew_id, quest, MIN(duration_ms) FROM living_clear WHERE crew_id IS NOT NULL AND duration_ms > 0"
                + " GROUP BY crew_id, quest";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getInt(1), id -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
            }
        } catch (SQLException failure) {
            log.warn("living_clear crew bests read failed", failure);
        }
        return out;
    }

    /**
     * Every clear {@code playerId} was in as a player, oldest first, each with only its companions (P) as members, names
     * left out: what the owner's crew ties count (F6.4 CrewTies). Empty when none, or when the read failed (logged).
     */
    public List<Clear> companionClearsOf(int playerId) {
        String sql = "SELECT c.id, c.quest, c.duration_ms, m.character_id FROM living_clear_member o"
                + " JOIN living_clear c ON c.id = o.clear_id"
                + " JOIN living_clear_member m ON m.clear_id = o.clear_id AND m.kind = 'P'"
                + " WHERE o.character_id = ? AND o.kind = 'H' ORDER BY c.id";
        Map<Integer, Clear> clears = new java.util.LinkedHashMap<>();
        Map<Integer, List<Clear.Member>> members = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    if (!clears.containsKey(id)) {
                        clears.put(id, new Clear(id, rs.getString(2), "", 0, 0, 0L, 0L, rs.getLong(3), Clear.Kind.HUMAN,
                                0, List.of()));
                    }
                    members.computeIfAbsent(id, key -> new ArrayList<>())
                            .add(new Clear.Member(rs.getInt(4), Clear.AS_COMPANION, ""));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_clear companion clears of {} could not be read", playerId, failure);
            return List.of();
        }
        List<Clear> out = new ArrayList<>();
        for (Clear clear : clears.values()) {
            out.add(new Clear(clear.id(), clear.quest(), clear.instance(), clear.channel(), clear.mapId(),
                    clear.startedAtMs(), clear.clearedAtMs(), clear.durationMs(), clear.kind(), clear.crewId(),
                    members.getOrDefault(clear.id(), List.of())));
        }
        return out;
    }

    private static void readAll(PreparedStatement ps, List<Clear> out) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Clear(rs.getInt("id"), rs.getString("quest"), rs.getString("instance"), rs.getInt("channel"),
                        rs.getInt("map_id"), SqlTime.get(rs, "started_at"), SqlTime.get(rs, "cleared_at"),
                        rs.getLong("duration_ms"), Clear.Kind.parse(rs.getString("kind")), rs.getInt("crew_id"),
                        Clear.decodeMembers(rs.getString("members"))));
            }
        }
    }
}
