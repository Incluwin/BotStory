package server.living.population;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.store.SqlTime;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC for {@code living_guild_goal} (changeset 67, docs/WORLD_SYSTEMS_PLAN.md N5.2) and the coliseum wins a goal
 * counts from {@code pvp_match}. Every read degrades to empty and every write logs on failure.
 */
class GuildGoalStore implements GuildGoals.Store {
    private static final Logger log = LoggerFactory.getLogger(GuildGoalStore.class);

    @Override
    public GuildGoals.Goal load(int guildId, int weekOf) {
        if (!DatabaseConnection.isInitialized()) {
            return null;
        }
        String sql = "SELECT goal, detail, target, progress, done FROM living_guild_goal WHERE guild_id = ? AND week_of = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, guildId);
            ps.setInt(2, weekOf);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    GuildGoals.Kind kind = GuildGoals.Kind.parse(rs.getString("goal"));
                    return kind == null ? null : new GuildGoals.Goal(guildId, weekOf, kind, rs.getString("detail"),
                            rs.getInt("target"), rs.getInt("progress"), rs.getBoolean("done"));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_guild_goal read failed for guild {} week {}", guildId, weekOf, failure);
        }
        return null;
    }

    @Override
    public void insertIfAbsent(GuildGoals.Goal goal, long nowMs) {
        if (!DatabaseConnection.isInitialized()) {
            return;
        }
        String sql = SqlTime.utc("INSERT IGNORE INTO living_guild_goal (guild_id, week_of, goal, detail, target, progress,"
                + " done, set_at) VALUES (?, ?, ?, ?, ?, 0, 0, ?)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, goal.guildId());
            ps.setInt(2, goal.weekOf());
            ps.setString(3, goal.kind().name());
            ps.setString(4, goal.detail());
            ps.setInt(5, goal.target());
            SqlTime.set(ps, 6, nowMs);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_guild_goal insert failed for guild {}", goal.guildId(), failure);
        }
    }

    @Override
    public void progress(int guildId, int weekOf, int progress) {
        if (!DatabaseConnection.isInitialized()) {
            return;
        }
        String sql = "UPDATE living_guild_goal SET progress = GREATEST(progress, ?) WHERE guild_id = ? AND week_of = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, progress);
            ps.setInt(2, guildId);
            ps.setInt(3, weekOf);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_guild_goal progress write failed for guild {}", guildId, failure);
        }
    }

    @Override
    public boolean markDone(int guildId, int weekOf, long nowMs) {
        if (!DatabaseConnection.isInitialized()) {
            return false;
        }
        String sql = SqlTime.utc("UPDATE living_guild_goal SET done = 1, done_at = ? WHERE guild_id = ? AND week_of = ?"
                + " AND done = 0");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            SqlTime.set(ps, 1, nowMs);
            ps.setInt(2, guildId);
            ps.setInt(3, weekOf);
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) {
            log.warn("living_guild_goal done write failed for guild {}", guildId, failure);
            return false;
        }
    }

    /** Match id -> the fighters on its winning side, for the coliseum matches that ended in {@code [fromMs, toMs)}. */
    static Map<Long, Set<Integer>> coliseumWins(long fromMs, long toMs) {
        Map<Long, Set<Integer>> out = new HashMap<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = SqlTime.utc("SELECT m.id, f.character_id FROM pvp_match m JOIN pvp_match_fighter f ON f.match_id = m.id"
                + " AND f.side = m.winner_side WHERE m.ended_at >= ? AND m.ended_at < ? AND m.winner_side >= 0");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            SqlTime.set(ps, 1, fromMs);
            SqlTime.set(ps, 2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getLong(1), id -> new HashSet<>()).add(rs.getInt(2));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_match wins read failed", failure);
        }
        return out;
    }

    /** The title a week's goal notice starts with, so the master's last one is taken down when it posts the next. */
    static final String NOTICE_PREFIX = "Goal: ";

    /**
     * The guild master's thread of the week's goal on the stock guild BBS (N5.5), as BBSOperationHandler.newBBSThread
     * writes one (the next local id, server time, icon 0, the client's 25 / 600 character limits); its notice of an
     * earlier week is deleted first, as a master tidies the board. The client shows the poster's name from its guild
     * list, so a citizen master's thread reads like any member's.
     */
    static void postNotice(int guildId, int posterId, String title, String text, long serverTimeMs) {
        if (!DatabaseConnection.isInitialized()) {
            return;
        }
        String shortTitle = title.length() > 25 ? title.substring(0, 25) : title;
        String shortText = text.length() > 600 ? text.substring(0, 600) : text;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM bbs_threads WHERE guildid = ? AND postercid = ?"
                    + " AND localthreadid > 0 AND name LIKE ?")) {
                ps.setInt(1, guildId);
                ps.setInt(2, posterId);
                ps.setString(3, NOTICE_PREFIX + "%");
                ps.executeUpdate();
            }
            int next;
            try (PreparedStatement ps = con.prepareStatement("SELECT COALESCE(MAX(localthreadid), 0) + 1 FROM bbs_threads"
                    + " WHERE guildid = ?")) {
                ps.setInt(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    next = rs.next() ? rs.getInt(1) : 1;
                }
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO bbs_threads (postercid, name, timestamp, icon,"
                    + " startpost, guildid, localthreadid) VALUES (?, ?, ?, 0, ?, ?, ?)")) {
                ps.setInt(1, posterId);
                ps.setString(2, shortTitle);
                ps.setLong(3, serverTimeMs);
                ps.setString(4, shortText);
                ps.setInt(5, guildId);
                ps.setInt(6, next);
                ps.executeUpdate();
            }
        } catch (SQLException failure) {
            log.warn("bbs_threads goal notice failed for guild {}", guildId, failure);
        }
    }

    /** The goals of {@code weekOf}, for the status line (!citizens status). */
    List<GuildGoals.Goal> week(int weekOf) {
        List<GuildGoals.Goal> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = "SELECT guild_id, goal, detail, target, progress, done FROM living_guild_goal WHERE week_of = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, weekOf);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GuildGoals.Kind kind = GuildGoals.Kind.parse(rs.getString("goal"));
                    if (kind != null) {
                        out.add(new GuildGoals.Goal(rs.getInt("guild_id"), weekOf, kind, rs.getString("detail"),
                                rs.getInt("target"), rs.getInt("progress"), rs.getBoolean("done")));
                    }
                }
            }
        } catch (SQLException failure) {
            log.warn("living_guild_goal week read failed", failure);
        }
        return out;
    }
}
