package server.living.population.coliseum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.store.SqlTime;
import server.pvp.Glicko2;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC for {@code pvp_rating} (changeset 60) and the ladder's rows in {@code pvp_match} (kind LADDER, changeset 59),
 * docs/PVP_PLAN.md V4. Every read degrades to empty and every write logs on failure.
 */
class LadderStore implements LadderBook.Store {
    private static final Logger log = LoggerFactory.getLogger(LadderStore.class);
    static final String LADDER = "LADDER";
    static final String TOURNAMENT = "TOURNAMENT";
    /** V6: team matches (a team ladder match or a Crew War) in pvp_match. */
    static final String TEAM = "TEAM";
    /** V6: the team ladder's ratings are kept apart from 1v1 under this prefix to the bracket (TEAM_VETERAN). */
    static final String TEAM_PREFIX = "TEAM_";

    private final String prefix;

    /** The 1v1 ladder's store. */
    LadderStore() {
        this("");
    }

    /** A store whose brackets are kept under {@code prefix} ({@link #TEAM_PREFIX} for the team ladder). */
    LadderStore(String prefix) {
        this.prefix = prefix;
    }

    private String key(LadderRules.Bracket bracket) {
        return prefix + bracket.name();
    }

    @Override
    public Optional<LadderBook.Row> load(int characterId, LadderRules.Bracket bracket, int season) {
        if (!DatabaseConnection.isInitialized()) {
            return Optional.empty();
        }
        String sql = SqlTime.utc("SELECT rating, deviation, volatility, wins, losses, updated_at, week_of, week_rating"
                + " FROM pvp_rating WHERE character_id = ? AND bracket = ? AND season = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, key(bracket));
            ps.setInt(3, season);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(row(rs, characterId, bracket));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_rating read failed for {} in {}", characterId, bracket, failure);
        }
        return Optional.empty();
    }

    private static LadderBook.Row row(ResultSet rs, int characterId, LadderRules.Bracket bracket) throws SQLException {
        return new LadderBook.Row(characterId, bracket,
                new Glicko2.Rating(rs.getDouble("rating"), rs.getDouble("deviation"), rs.getDouble("volatility")),
                rs.getInt("wins"), rs.getInt("losses"), SqlTime.get(rs, "updated_at"), rs.getInt("week_of"),
                rs.getDouble("week_rating"));
    }

    @Override
    public void save(LadderBook.Row row, int season) {
        if (!DatabaseConnection.isInitialized()) {
            return;
        }
        String sql = SqlTime.utc("INSERT INTO pvp_rating (character_id, bracket, season, rating, deviation, volatility,"
                + " wins, losses, updated_at, week_of, week_rating) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE rating = VALUES(rating), deviation = VALUES(deviation),"
                + " volatility = VALUES(volatility), wins = VALUES(wins), losses = VALUES(losses),"
                + " updated_at = VALUES(updated_at), week_of = VALUES(week_of), week_rating = VALUES(week_rating)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, row.characterId());
            ps.setString(2, key(row.bracket()));
            ps.setInt(3, season);
            ps.setDouble(4, row.rating().rating());
            ps.setDouble(5, row.rating().deviation());
            ps.setDouble(6, row.rating().volatility());
            ps.setInt(7, row.wins());
            ps.setInt(8, row.losses());
            SqlTime.set(ps, 9, row.updatedAtMs() > 0 ? row.updatedAtMs() : System.currentTimeMillis());
            ps.setInt(10, row.weekOf());
            ps.setDouble(11, row.weekRating());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("pvp_rating write failed for {} in {}", row.characterId(), row.bracket(), failure);
        }
    }

    @Override
    public List<LadderBook.Ranked> top(LadderRules.Bracket bracket, int season, int minPlayed, int limit) {
        if (!DatabaseConnection.isInitialized()) {
            return List.of();
        }
        return ranked(" AND r.wins + r.losses >= ? ORDER BY r.rating DESC LIMIT ?", bracket, season, minPlayed, limit,
                "top");
    }

    @Override
    public List<LadderBook.Ranked> climbers(LadderRules.Bracket bracket, int season, int week, int limit) {
        return ranked(" AND r.week_of = ? AND r.rating > r.week_rating ORDER BY r.rating - r.week_rating DESC LIMIT ?",
                bracket, season, week, limit, "climbers");
    }

    private List<LadderBook.Ranked> ranked(String where, LadderRules.Bracket bracket, int season, int third, int limit,
                                           String what) {
        if (!DatabaseConnection.isInitialized()) {
            return List.of();
        }
        String sql = SqlTime.utc("SELECT r.character_id, r.rating, r.deviation, r.volatility, r.wins, r.losses,"
                + " r.updated_at, r.week_of, r.week_rating, c.name, c.level FROM pvp_rating r"
                + " JOIN characters c ON c.id = r.character_id WHERE r.bracket = ? AND r.season = ? AND c.gm = 0"
                + where);
        List<LadderBook.Ranked> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, key(bracket));
            ps.setInt(2, season);
            ps.setInt(3, third);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LadderBook.Ranked(row(rs, rs.getInt("character_id"), bracket), rs.getString("name"),
                            rs.getInt("level")));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_rating {} read failed for {}", what, bracket, failure);
        }
        return out;
    }

    @Override
    public int[] standing(LadderRules.Bracket bracket, int season, int minPlayed, double rating) {
        if (!DatabaseConnection.isInitialized()) {
            return new int[] {0, 0};
        }
        String sql = "SELECT COALESCE(SUM(r.rating > ?), 0), COUNT(*) FROM pvp_rating r"
                + " JOIN characters c ON c.id = r.character_id"
                + " WHERE r.bracket = ? AND r.season = ? AND r.wins + r.losses >= ? AND c.gm = 0";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setDouble(1, rating);
            ps.setString(2, key(bracket));
            ps.setInt(3, season);
            ps.setInt(4, minPlayed);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new int[] {rs.getInt(1), rs.getInt(2)};
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_rating standing read failed for {}", bracket, failure);
        }
        return new int[] {0, 0};
    }

    /** A finished ladder match a human fought: one pvp_match row (reason holds the round score) and its two fighters. */
    record Fighter(int characterId, int side, boolean human, boolean ko, long dealt, long taken) {
    }

    long insertMatch(long endedAtMs, int mapId, long seconds, int winnerSide, String reason, double odds,
                     List<Fighter> fighters) {
        return insertMatch(LADDER, endedAtMs, mapId, seconds, winnerSide, reason, odds, fighters);
    }

    /** {@link #insertMatch} with its kind: LADDER, or TOURNAMENT for a weekly tournament's bout (V5). */
    long insertMatch(String kind, long endedAtMs, int mapId, long seconds, int winnerSide, String reason, double odds,
                     List<Fighter> fighters) {
        if (!DatabaseConnection.isInitialized()) {
            return 0L;
        }
        String sql = SqlTime.utc("INSERT INTO pvp_match (kind, ended_at, map_id, seconds, winner_side, reason, crew_id,"
                + " odds) VALUES (?, ?, ?, ?, ?, ?, 0, ?)");
        String fighterSql = "INSERT INTO pvp_match_fighter (match_id, character_id, side, human, ko, dealt, taken)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement each = con.prepareStatement(fighterSql)) {
                ps.setString(1, kind);
                SqlTime.set(ps, 2, endedAtMs);
                ps.setInt(3, mapId);
                ps.setInt(4, (int) Math.min(Integer.MAX_VALUE, seconds));
                ps.setInt(5, winnerSide);
                ps.setString(6, reason.length() <= 48 ? reason : reason.substring(0, 48));
                ps.setDouble(7, odds);
                ps.executeUpdate();
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 0L;
                }
                for (Fighter fighter : fighters) {
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
            log.warn("pvp_match {} insert failed", kind, failure);
            return 0L;
        }
    }
}
