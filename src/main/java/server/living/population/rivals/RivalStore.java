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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC for the rival crews (living_rival) and what forming them reads of the characters table. Every read degrades to
 * empty and every write logs on failure. Not final: tests keep it in memory.
 */
public class RivalStore {
    private static final Logger log = LoggerFactory.getLogger(RivalStore.class);
    private static final String COLUMNS = "id, account_id, main_id, name, guild_id, circle_id, leader_id, members, formed_at,"
            + " help_asked_at";

    public List<Crew> all() {
        List<Crew> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT " + COLUMNS + " FROM living_rival ORDER BY id");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Crew(rs.getInt("id"), rs.getInt("account_id"), rs.getInt("main_id"), rs.getString("name"),
                        rs.getInt("guild_id"), rs.getInt("circle_id"), rs.getInt("leader_id"),
                        Crew.decodeMembers(rs.getString("members")), SqlTime.get(rs, "formed_at"),
                        SqlTime.get(rs, "help_asked_at")));
            }
        } catch (SQLException failure) {
            log.warn("living_rival read failed", failure);
        }
        return out;
    }

    /** Stores a new crew; its id, or 0 when the write failed. */
    public int insert(Crew crew) {
        String sql = SqlTime.utc("INSERT INTO living_rival (account_id, main_id, name, guild_id, circle_id, leader_id,"
                + " members, formed_at, help_asked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, crew.accountId());
            ps.setInt(2, crew.mainId());
            bind(ps, crew, 3);
            SqlTime.set(ps, 8, crew.formedAtMs());
            SqlTime.setNullable(ps, 9, crew.helpAskedAtMs());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            log.warn("living_rival insert failed for account {}", crew.accountId(), failure);
            return 0;
        }
    }

    public void update(Crew crew) {
        String sql = SqlTime.utc("UPDATE living_rival SET name = ?, guild_id = ?, circle_id = ?, leader_id = ?, members = ?,"
                + " main_id = ?, help_asked_at = ? WHERE id = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, crew, 1);
            ps.setInt(6, crew.mainId());
            SqlTime.setNullable(ps, 7, crew.helpAskedAtMs());
            ps.setInt(8, crew.id());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_rival update failed for {}", crew.id(), failure);
        }
    }

    /** name, guild_id, circle_id, leader_id, members from {@code first}. */
    private static void bind(PreparedStatement ps, Crew crew, int first) throws SQLException {
        ps.setString(first, crew.name());
        ps.setInt(first + 1, crew.guildId());
        ps.setInt(first + 2, crew.circleId());
        ps.setInt(first + 3, crew.leaderId());
        ps.setString(first + 4, crew.encodeMembers());
    }

    /** The account's main: its highest-level character {id, level} (most EXP, then the oldest, on a tie); null for none. */
    public int[] mainOf(int accountId) {
        String sql = "SELECT id, level FROM characters WHERE accountid = ? ORDER BY level DESC, exp DESC, id LIMIT 1";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2)} : null;
            }
        } catch (SQLException failure) {
            log.warn("main character lookup failed for account {}", accountId, failure);
            return null;
        }
    }

    /** Every active citizen's stored {level, guild id, guild rank}, by character id, in one query. */
    public Map<Integer, int[]> citizenProgress() {
        Map<Integer, int[]> out = new HashMap<>();
        String sql = "SELECT c.id, c.level, c.guildid, c.guildrank FROM characters c"
                + " JOIN living_citizen l ON l.character_id = c.id WHERE l.state = 'ACTIVE'";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getInt(1), new int[]{rs.getInt(2), rs.getInt(3), rs.getInt(4)});
            }
        } catch (SQLException failure) {
            log.warn("citizen progress read failed", failure);
        }
        return out;
    }
}
