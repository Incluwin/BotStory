package server.living.population.rivals;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC for {@code pvp_match} and {@code pvp_match_fighter} (changeset 59, docs/PVP_PLAN.md V3.4). Every read degrades
 * to empty and every write logs on failure. Not final: tests keep it in memory.
 */
class RivalMatchStore {
    private static final Logger log = LoggerFactory.getLogger(RivalMatchStore.class);
    static final String RIVAL = "RIVAL";

    /** Books a finished rival duel and its fighters; the match id, or 0 when the write failed. */
    long insert(RivalRecord.Match match, int mapId, long seconds, String reason, int crewId, double odds) {
        if (!DatabaseConnection.isInitialized()) {
            return 0L; // tests and tools without the pool
        }
        String sql = SqlTime.utc("INSERT INTO pvp_match (kind, ended_at, map_id, seconds, winner_side, reason, crew_id,"
                + " odds) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        String fighterSql = "INSERT INTO pvp_match_fighter (match_id, character_id, side, human, ko, dealt, taken)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement each = con.prepareStatement(fighterSql)) {
                ps.setString(1, RIVAL);
                SqlTime.set(ps, 2, match.endedAtMs());
                ps.setInt(3, mapId);
                ps.setInt(4, (int) Math.min(Integer.MAX_VALUE, seconds));
                ps.setInt(5, match.winnerSide());
                ps.setString(6, reason.length() <= 48 ? reason : reason.substring(0, 48));
                ps.setInt(7, crewId);
                ps.setDouble(8, odds);
                ps.executeUpdate();
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 0L;
                }
                for (RivalRecord.Fighter fighter : match.fighters()) {
                    each.setLong(1, id);
                    each.setInt(2, fighter.characterId());
                    each.setInt(3, fighter.side());
                    each.setBoolean(4, fighter.human());
                    each.setBoolean(5, fighter.ko());
                    each.setLong(6, fighter.dealt());
                    each.setLong(7, fighter.taken());
                    each.addBatch();
                }
                each.executeBatch();
                con.commit();
                return id;
            } catch (SQLException failure) {
                con.rollback();
                throw failure;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            log.warn("pvp_match insert failed for crew {}", crewId, failure);
            return 0L;
        }
    }

    /** The rival matches against crew {@code crewId} that {@code humanId} fought on the player side, oldest first. */
    List<RivalRecord.Match> rivalMatches(int crewId, int humanId) {
        if (!DatabaseConnection.isInitialized()) {
            return List.of();
        }
        String sql = SqlTime.utc("SELECT m.id, m.ended_at, m.winner_side, f.character_id, f.side, f.human, f.ko, f.dealt,"
                + " f.taken FROM pvp_match m JOIN pvp_match_fighter me ON me.match_id = m.id AND me.character_id = ?"
                + " AND me.side = 0 JOIN pvp_match_fighter f ON f.match_id = m.id WHERE m.kind = ? AND m.crew_id = ?"
                + " ORDER BY m.id");
        Map<Long, long[]> heads = new LinkedHashMap<>();
        Map<Long, List<RivalRecord.Fighter>> fighters = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, humanId);
            ps.setString(2, RIVAL);
            ps.setInt(3, crewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    heads.putIfAbsent(id, new long[] {SqlTime.get(rs, "ended_at"), rs.getInt("winner_side")});
                    fighters.computeIfAbsent(id, key -> new ArrayList<>()).add(new RivalRecord.Fighter(
                            rs.getInt("character_id"), rs.getInt("side"), rs.getBoolean("human"), rs.getBoolean("ko"),
                            rs.getLong("dealt"), rs.getLong("taken")));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_match read failed for crew {} and {}", crewId, humanId, failure);
            return List.of();
        }
        List<RivalRecord.Match> out = new ArrayList<>();
        heads.forEach((id, head) -> out.add(new RivalRecord.Match(head[0], (int) head[1], fighters.get(id))));
        return out;
    }
}
