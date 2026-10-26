package server.living.population;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.Citizen.State;
import server.living.store.SqlTime;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC access to the population tables ({@code living_citizen}, circles, guild meta, price index, acquaintances,
 * alliance marks).
 */
public final class CitizenStore {
    private static final Logger log = LoggerFactory.getLogger(CitizenStore.class);
    private static final String COLUMNS = "character_id, name, cohort, circle_id, guild_id, home_region, career, personality,"
            + " voice, pace, offset_minutes, channel, flags, state, joined_at, retires_at, last_online_at, last_ledger_at,"
            + " accrued_exp, scheduled_minutes, life_summary";

    public record Circle(int id, String name, String cohort) { }

    public record GuildMeta(int guildId, String stance, int levelMin, int levelMax, String motto, String cohort) { }

    public record Acquaintance(int characterId, int playerId, int partyMinutes, int mapMinutes, boolean buddyAsked, boolean guildAsked) { }

    /** A character a player has met (shared party or map minutes), with its level and job as its characters row holds them. */
    public record Met(int characterId, int level, int job, int partyMinutes, int mapMinutes) { }

    // ---- citizens ------------------------------------------------------------------------

    public List<Citizen> all(State state) {
        List<Citizen> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT " + COLUMNS + " FROM living_citizen" + (state == null ? "" : " WHERE state = ?") + " ORDER BY character_id");
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
        String sql = SqlTime.utc("SELECT " + COLUMNS + " FROM living_citizen WHERE character_id = ?");
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
        String sql = SqlTime.utc("INSERT INTO living_citizen (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE cohort = VALUES(cohort), circle_id = VALUES(circle_id), guild_id = VALUES(guild_id),"
                + " home_region = VALUES(home_region), career = VALUES(career), personality = VALUES(personality), voice = VALUES(voice),"
                + " pace = VALUES(pace), offset_minutes = VALUES(offset_minutes), channel = VALUES(channel), flags = VALUES(flags),"
                + " state = VALUES(state), retires_at = VALUES(retires_at), last_online_at = VALUES(last_online_at),"
                + " last_ledger_at = VALUES(last_ledger_at), accrued_exp = VALUES(accrued_exp), scheduled_minutes = VALUES(scheduled_minutes),"
                + " life_summary = VALUES(life_summary)");
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
            SqlTime.set(ps, 15, citizen.joinedAtMs());
            SqlTime.setNullable(ps, 16, citizen.retiresAtMs());
            SqlTime.setNullable(ps, 17, citizen.lastOnlineAtMs());
            SqlTime.setNullable(ps, 18, citizen.lastLedgerAtMs());
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

    /** Every active citizen's stored {level, job id}, by character id, in one query (ProgressionLedger.markJobBacklog). */
    public Map<Integer, int[]> levelsAndJobs() {
        Map<Integer, int[]> out = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT c.id, c.level, c.job FROM characters c"
                     + " JOIN living_citizen l ON l.character_id = c.id WHERE l.state = 'ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getInt(1), new int[]{rs.getInt(2), rs.getInt(3)});
            }
        } catch (SQLException failure) {
            log.warn("citizen levels and jobs lookup failed", failure);
        }
        return out;
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

    /** Everyone the player has spent party or map minutes with (the hire list, P4), in one read. */
    public List<Met> metBy(int playerId) {
        List<Met> out = new ArrayList<>();
        String sql = "SELECT a.character_id, c.level, c.job, a.party_minutes, a.map_minutes FROM living_acquaintance a"
                + " JOIN characters c ON c.id = a.character_id WHERE a.player_id = ? AND (a.party_minutes > 0 OR a.map_minutes > 0)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Met(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_acquaintance scan failed for {}", playerId, failure);
        }
        return out;
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

    // ---- alliances -----------------------------------------------------------------------

    /**
     * Shared minutes (map minutes plus party minutes twice, as the guild invite counts them) between the citizens of
     * {@code citizenGuildIds} and the players of every other guild, by that guild's id, in one read
     * (docs/BOT_GUILD_ALLIANCES_PLAN.md). Acquaintances are only kept with players, so every row is a player's.
     */
    public Map<Integer, Integer> playerGuildMinutes(java.util.Collection<Integer> citizenGuildIds) {
        Map<Integer, Integer> out = new HashMap<>();
        if (citizenGuildIds.isEmpty()) {
            return out;
        }
        String in = String.join(", ", java.util.Collections.nCopies(citizenGuildIds.size(), "?"));
        String sql = "SELECT p.guildid, SUM(a.map_minutes + 2 * a.party_minutes) FROM living_acquaintance a"
                + " JOIN characters c ON c.id = a.character_id JOIN characters p ON p.id = a.player_id"
                + " WHERE c.guildid IN (" + in + ") AND p.guildid > 0 AND p.guildid NOT IN (" + in + ") GROUP BY p.guildid";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            int index = 1;
            for (int round = 0; round < 2; round++) {
                for (int guildId : citizenGuildIds) {
                    ps.setInt(index++, guildId);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), rs.getInt(2));
                }
            }
        } catch (SQLException failure) {
            log.warn("alliance player-guild minutes scan failed", failure);
        }
        return out;
    }

    /** Takes {@code amount} meso from an offline character that has it; false (nothing taken) when it has less. */
    public boolean chargeMeso(int characterId, long amount) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE characters SET meso = meso - ? WHERE id = ? AND meso >= ?")) {
            ps.setLong(1, amount);
            ps.setInt(2, characterId);
            ps.setLong(3, amount);
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) {
            log.warn("alliance fee charge failed for {}", characterId, failure);
            return false;
        }
    }

    /** Sets an offline character's alliance rank in the characters table (a loaded one saves its own). */
    public void setAllianceRank(int characterId, int rank) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE characters SET allianceRank = ? WHERE id = ?")) {
            ps.setInt(1, rank);
            ps.setInt(2, characterId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("alliance rank write failed for {}", characterId, failure);
        }
    }

    /** When the alliance mark {@code kind} was set for this guild (epoch ms), or 0 when it is not set. */
    public long allianceMark(int guildId, String kind) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT at_ms FROM living_alliance_mark WHERE guild_id = ? AND kind = ?")) {
            ps.setInt(1, guildId);
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            log.warn("living_alliance_mark read failed for {}", guildId, failure);
            return 0L;
        }
    }

    /** Sets the alliance mark {@code kind} for this guild at {@code atMs}; {@code atMs} 0 clears it. */
    public void setAllianceMark(int guildId, String kind, long atMs) {
        String sql = atMs <= 0 ? "DELETE FROM living_alliance_mark WHERE guild_id = ? AND kind = ?"
                : "INSERT INTO living_alliance_mark (guild_id, kind, at_ms) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE at_ms = VALUES(at_ms)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, guildId);
            ps.setString(2, kind);
            if (atMs > 0) {
                ps.setLong(3, atMs);
            }
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_alliance_mark write failed for {}", guildId, failure);
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
                State.valueOf(rs.getString("state")), SqlTime.get(rs, "joined_at"), SqlTime.get(rs, "retires_at"),
                SqlTime.get(rs, "last_online_at"), SqlTime.get(rs, "last_ledger_at"), rs.getLong("accrued_exp"),
                rs.getInt("scheduled_minutes"), rs.getString("life_summary"));
    }

    private static void setNullableInt(PreparedStatement ps, int index, int value) throws SQLException {
        if (value <= 0) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
