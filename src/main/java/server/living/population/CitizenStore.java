package server.living.population;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.Citizen.State;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** JDBC access to the population tables ({@code living_citizen}, circles, guild meta, price index, acquaintances). */
public final class CitizenStore {
    private static final Logger log = LoggerFactory.getLogger(CitizenStore.class);
    private static final String COLUMNS = "character_id, name, cohort, circle_id, guild_id, home_region, career, personality,"
            + " voice, pace, offset_minutes, channel, flags, state, joined_at, retires_at, last_online_at, last_ledger_at,"
            + " accrued_exp, scheduled_minutes, life_summary";

    public record Circle(int id, String name, String cohort) { }

    public record GuildMeta(int guildId, String stance, int levelMin, int levelMax, String motto, String cohort) { }

    public record Acquaintance(int characterId, int playerId, int partyMinutes, int mapMinutes, boolean buddyAsked, boolean guildAsked) { }

    // ---- citizens ------------------------------------------------------------------------

    public List<Citizen> all(State state) {
        List<Citizen> out = new ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM living_citizen" + (state == null ? "" : " WHERE state = ?") + " ORDER BY character_id";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            if (state != null) {
                ps.setString(1, state.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_citizen scan failed", failure);
        }
        return out;
    }

    public Citizen citizen(int characterId) {
        String sql = "SELECT " + COLUMNS + " FROM living_citizen WHERE character_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException failure) {
            log.warn("living_citizen read failed for {}", characterId, failure);
            return null;
        }
    }

    public void save(Citizen citizen) {
        String sql = "INSERT INTO living_citizen (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE cohort = VALUES(cohort), circle_id = VALUES(circle_id), guild_id = VALUES(guild_id),"
                + " home_region = VALUES(home_region), career = VALUES(career), personality = VALUES(personality), voice = VALUES(voice),"
                + " pace = VALUES(pace), offset_minutes = VALUES(offset_minutes), channel = VALUES(channel), flags = VALUES(flags),"
                + " state = VALUES(state), retires_at = VALUES(retires_at), last_online_at = VALUES(last_online_at),"
                + " last_ledger_at = VALUES(last_ledger_at), accrued_exp = VALUES(accrued_exp), scheduled_minutes = VALUES(scheduled_minutes),"
                + " life_summary = VALUES(life_summary)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, citizen.characterId());
            ps.setString(2, citizen.name());
            ps.setString(3, citizen.cohort());
            setNullableInt(ps, 4, citizen.circleId());
            setNullableInt(ps, 5, citizen.guildId());
            ps.setString(6, citizen.homeRegion());
            ps.setString(7, citizen.career());
            ps.setString(8, citizen.personality());
            ps.setString(9, citizen.voice());
            ps.setDouble(10, citizen.pace());
            ps.setInt(11, citizen.offsetMinutes());
            ps.setInt(12, citizen.channel());
            ps.setString(13, String.join(",", citizen.flags()));
            ps.setString(14, citizen.state().name());
            ps.setTimestamp(15, new Timestamp(citizen.joinedAtMs()));
            setNullableTime(ps, 16, citizen.retiresAtMs());
            setNullableTime(ps, 17, citizen.lastOnlineAtMs());
            setNullableTime(ps, 18, citizen.lastLedgerAtMs());
            ps.setLong(19, citizen.accruedExp());
            ps.setInt(20, citizen.scheduledMinutes());
            ps.setString(21, citizen.lifeSummary() == null ? "" : citizen.lifeSummary());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_citizen write failed for {}", citizen.characterId(), failure);
        }
    }

    public boolean nameTaken(String name) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1 FROM characters WHERE name = ? UNION SELECT 1 FROM living_citizen WHERE name = ?")) {
            ps.setString(1, name);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException failure) {
            log.warn("name check failed for {}", name, failure);
            return true;
        }
    }

    /** Level, exp, job and meso of a character from the characters table (offline-safe). */
    public int[] characterProgress(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT level, exp, job, meso, fame FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)} : new int[]{0, 0, 0, 0, 0};
            }
        } catch (SQLException failure) {
            log.warn("character progress lookup failed for {}", characterId, failure);
            return new int[]{0, 0, 0, 0, 0};
        }
    }

    /** Sets guild id and rank straight in the characters table for an offline citizen. */
    public void setGuild(int characterId, int guildId, int rank) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE characters SET guildid = ?, guildrank = ? WHERE id = ?")) {
            ps.setInt(1, guildId);
            ps.setInt(2, rank);
            ps.setInt(3, characterId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("guild assignment failed for {}", characterId, failure);
        }
    }

    /** Adds a mutual buddy pair in the buddies table (ignored if already present). */
    public void addBuddyPair(int a, int b) {
        String sql = "INSERT IGNORE INTO buddies (characterid, buddyid, pending, `group`) VALUES (?, ?, 0, 'Default Group')";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, a);
            ps.setInt(2, b);
            ps.executeUpdate();
            ps.setInt(1, b);
            ps.setInt(2, a);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("buddy pair write failed for {}/{}", a, b, failure);
        }
    }

    public int buddyCount(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM buddies WHERE characterid = ? AND pending = 0")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            return 0;
        }
    }

    // ---- circles -------------------------------------------------------------------------

    public List<Circle> circles() {
        List<Circle> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, name, cohort FROM living_circle ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Circle(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException failure) {
            log.warn("living_circle scan failed", failure);
        }
        return out;
    }

    public int createCircle(String name, String cohort) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO living_circle (name, cohort) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, cohort);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            log.warn("living_circle insert failed", failure);
            return 0;
        }
    }

    // ---- guild meta ----------------------------------------------------------------------

    public List<GuildMeta> guilds() {
        List<GuildMeta> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT guild_id, stance, level_min, level_max, motto, cohort FROM living_guild ORDER BY guild_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new GuildMeta(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6)));
            }
        } catch (SQLException failure) {
            log.warn("living_guild scan failed", failure);
        }
        return out;
    }

    public void saveGuild(GuildMeta meta) {
        String sql = "INSERT INTO living_guild (guild_id, stance, level_min, level_max, motto, cohort) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE stance = VALUES(stance), level_min = VALUES(level_min), level_max = VALUES(level_max), motto = VALUES(motto)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, meta.guildId());
            ps.setString(2, meta.stance());
            ps.setInt(3, meta.levelMin());
            ps.setInt(4, meta.levelMax());
            ps.setString(5, meta.motto());
            ps.setString(6, meta.cohort());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_guild write failed for {}", meta.guildId(), failure);
        }
    }

    // ---- price index ---------------------------------------------------------------------

    public long price(int itemId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT price FROM living_price_index WHERE item_id = ?")) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            return 0L;
        }
    }

    public void savePrice(int itemId, long price, int samples) {
        String sql = "INSERT INTO living_price_index (item_id, price, samples) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE price = VALUES(price), samples = VALUES(samples)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            ps.setLong(2, price);
            ps.setInt(3, samples);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_price_index write failed for {}", itemId, failure);
        }
    }

    public List<int[]> prices() {
        List<int[]> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT item_id, price, samples FROM living_price_index");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new int[]{rs.getInt(1), (int) Math.min(Integer.MAX_VALUE, rs.getLong(2)), rs.getInt(3)});
            }
        } catch (SQLException failure) {
            log.warn("living_price_index scan failed", failure);
        }
        return out;
    }

    // ---- acquaintances -------------------------------------------------------------------

    public Acquaintance acquaintance(int characterId, int playerId) {
        String sql = "SELECT party_minutes, map_minutes, buddy_asked, guild_asked FROM living_acquaintance WHERE character_id = ? AND player_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setInt(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Acquaintance(characterId, playerId, rs.getInt(1), rs.getInt(2), rs.getBoolean(3), rs.getBoolean(4));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_acquaintance read failed", failure);
        }
        return new Acquaintance(characterId, playerId, 0, 0, false, false);
    }

    public void saveAcquaintance(Acquaintance a) {
        String sql = "INSERT INTO living_acquaintance (character_id, player_id, party_minutes, map_minutes, buddy_asked, guild_asked)"
                + " VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE party_minutes = VALUES(party_minutes), map_minutes = VALUES(map_minutes),"
                + " buddy_asked = VALUES(buddy_asked), guild_asked = VALUES(guild_asked)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, a.characterId());
            ps.setInt(2, a.playerId());
            ps.setInt(3, a.partyMinutes());
            ps.setInt(4, a.mapMinutes());
            ps.setBoolean(5, a.buddyAsked());
            ps.setBoolean(6, a.guildAsked());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_acquaintance write failed", failure);
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private static Citizen read(ResultSet rs) throws SQLException {
        Set<String> flags = new LinkedHashSet<>();
        String raw = rs.getString("flags");
        if (raw != null && !raw.isBlank()) {
            for (String flag : raw.split(",")) {
                if (!flag.isBlank()) {
                    flags.add(flag.trim());
                }
            }
        }
        return new Citizen(rs.getInt("character_id"), rs.getString("name"), rs.getString("cohort"),
                rs.getObject("circle_id") == null ? 0 : rs.getInt("circle_id"),
                rs.getObject("guild_id") == null ? 0 : rs.getInt("guild_id"),
                rs.getString("home_region"), rs.getString("career"), rs.getString("personality"), rs.getString("voice"),
                rs.getDouble("pace"), rs.getInt("offset_minutes"), rs.getInt("channel"), flags,
                State.valueOf(rs.getString("state")), time(rs.getTimestamp("joined_at")), time(rs.getTimestamp("retires_at")),
                time(rs.getTimestamp("last_online_at")), time(rs.getTimestamp("last_ledger_at")), rs.getLong("accrued_exp"),
                rs.getInt("scheduled_minutes"), rs.getString("life_summary"));
    }

    private static long time(Timestamp value) {
        return value == null ? 0L : value.getTime();
    }

    private static void setNullableTime(PreparedStatement ps, int index, long ms) throws SQLException {
        if (ms <= 0L) {
            ps.setNull(index, java.sql.Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, new Timestamp(ms));
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, int value) throws SQLException {
        if (value <= 0) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
