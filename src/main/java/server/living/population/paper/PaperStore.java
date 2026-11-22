package server.living.population.paper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.store.SqlTime;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * JDBC for the paper (changeset 64): living_paper, the paper.item and letters.paper rows of living_event_log, and the
 * existing tables the issue reads (pvp_tournament and its seats, pvp_rating's weekly climb, living_clear,
 * economy_cpi_daily, rival.news rows). Every read degrades to empty and every write logs on failure.
 */
final class PaperStore implements PaperDesk.Store, PaperCompose.Sources {
    private static final Logger log = LoggerFactory.getLogger(PaperStore.class);
    private static final String RIVAL_NEWS = "rival.news";
    private static final String DUEL_NEWS = "duel";

    private final UnaryOperator<String> questLabels;
    private final IntFunction<String> itemNames;

    /** {@code questLabels}: the records page's name of a quest script (null for no record); {@code itemNames}: an item's. */
    PaperStore(UnaryOperator<String> questLabels, IntFunction<String> itemNames) {
        this.questLabels = questLabels;
        this.itemNames = itemNames;
    }

    // ---- the desk ----------------------------------------------------------------------------------------------------

    @Override
    public boolean file(String detail) {
        return event(null, PaperDesk.ITEM_EVENT, detail);
    }

    private boolean event(Integer characterId, String kind, String detail) {
        if (!DatabaseConnection.isInitialized()) {
            return false;
        }
        String sql = "INSERT INTO living_event_log (character_id, persona_id, kind, detail) VALUES (?, NULL, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            if (characterId == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setInt(1, characterId);
            }
            ps.setString(2, kind);
            ps.setString(3, detail);
            ps.executeUpdate();
            return true;
        } catch (SQLException failure) {
            log.warn("living_event_log {} write failed", kind, failure);
            return false;
        }
    }

    @Override
    public Optional<PaperDesk.Stored> issue(int weekOf) {
        return one(SqlTime.utc("SELECT week_of, issued_at, body FROM living_paper WHERE week_of = ?"), weekOf, null);
    }

    @Override
    public Optional<PaperDesk.Stored> latestAtOrBefore(int weekOf) {
        Optional<PaperDesk.Stored> row = one(SqlTime.utc("SELECT week_of, issued_at, body FROM living_paper"
                + " WHERE week_of <= ? ORDER BY week_of DESC LIMIT 1"), weekOf, null);
        return row == null ? Optional.empty() : row;
    }

    private Optional<PaperDesk.Stored> one(String sql, int weekOf, Optional<PaperDesk.Stored> onFailure) {
        if (!DatabaseConnection.isInitialized()) {
            return onFailure;
        }
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, weekOf);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new PaperDesk.Stored(rs.getInt(1), SqlTime.get(rs, 2), rs.getString(3)))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            log.warn("living_paper read failed for week {}", weekOf, failure);
            return onFailure;
        }
    }

    @Override
    public boolean insert(int weekOf, long issuedAtMs, String body) {
        if (!DatabaseConnection.isInitialized()) {
            return false;
        }
        String sql = SqlTime.utc("INSERT IGNORE INTO living_paper (week_of, issued_at, body) VALUES (?, ?, ?)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, weekOf);
            SqlTime.set(ps, 2, issuedAtMs);
            ps.setString(3, body);
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) {
            log.warn("living_paper write failed for week {}", weekOf, failure);
            return false;
        }
    }

    @Override
    public Integer lastSeen(int characterId) {
        if (!DatabaseConnection.isInitialized()) {
            return null;
        }
        String sql = "SELECT detail FROM living_event_log WHERE character_id = ? AND kind = ? ORDER BY id DESC LIMIT 1";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, PaperDesk.READ_EVENT);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                try {
                    return Integer.parseInt(rs.getString(1).trim());
                } catch (RuntimeException unreadable) {
                    return -1;
                }
            }
        } catch (SQLException failure) {
            log.warn("living_event_log {} read failed for {}", PaperDesk.READ_EVENT, characterId, failure);
            return null;
        }
    }

    @Override
    public void seen(int characterId, int weekOf) {
        event(characterId, PaperDesk.READ_EVENT, String.valueOf(weekOf));
    }

    @Override
    public String name(int characterId) {
        if (!DatabaseConnection.isInitialized()) {
            return null;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT name FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException failure) {
            log.warn("characters name read failed for {}", characterId, failure);
            return null;
        }
    }

    // ---- what an issue reads -----------------------------------------------------------------------------------------

    @Override
    public List<PaperItem> items(long fromMs, long toMs) {
        List<PaperItem> out = new ArrayList<>();
        for (String[] row : events(PaperDesk.ITEM_EVENT, fromMs, toMs, true)) {
            PaperItem item = PaperItem.parse(Long.parseLong(row[0]), row[1]);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    @Override
    public List<PaperCompose.RivalBeat> rivalBeats(long fromMs, long toMs) {
        List<PaperCompose.RivalBeat> out = new ArrayList<>();
        for (String[] row : events(RIVAL_NEWS, fromMs, toMs, false)) {
            String[] parts = row[1] == null ? new String[0] : row[1].split("\\|");
            if (parts.length < 3 || DUEL_NEWS.equals(parts[1])) {
                continue; // a duel's result, not a beaten time
            }
            try {
                out.add(new PaperCompose.RivalBeat(parts[0], parts[1], Long.parseLong(parts[2])));
            } catch (NumberFormatException unreadable) {
                // an older row: skipped
            }
        }
        return out;
    }

    /** Rows of {@code kind} in the window, oldest first: {at ms, detail}. */
    private List<String[]> events(String kind, long fromMs, long toMs, boolean noCharacter) {
        List<String[]> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = SqlTime.utc("SELECT at, detail FROM living_event_log WHERE "
                + (noCharacter ? "character_id IS NULL AND " : "") + "kind = ? AND at >= ? AND at < ? ORDER BY id");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, kind);
            SqlTime.set(ps, 2, fromMs);
            SqlTime.set(ps, 3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {String.valueOf(SqlTime.get(rs, 1)), rs.getString(2)});
                }
            }
        } catch (SQLException failure) {
            log.warn("living_event_log {} window read failed", kind, failure);
        }
        return out;
    }

    @Override
    public List<PaperCompose.Champion> champions(int weekOf) {
        List<PaperCompose.Champion> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = "SELECT t.bracket, c.name, r.name FROM pvp_tournament t"
                + " LEFT JOIN pvp_tournament_seat c ON c.tournament_id = t.id AND c.character_id = t.champion_id"
                + " LEFT JOIN pvp_tournament_seat r ON r.tournament_id = t.id AND r.character_id = t.runner_up_id"
                + " WHERE t.week_of = ? AND t.state = 'DONE' ORDER BY t.id";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, weekOf);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PaperCompose.Champion(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_tournament champions read failed for week {}", weekOf, failure);
        }
        return out;
    }

    /**
     * The 1v1 ladder's biggest climbs in the paper week's ladder week (LadderBook.week: weeks from Monday, UTC), in
     * the newest season. A row whose player has fought since that week holds a later week and is not read.
     */
    @Override
    public List<PaperCompose.Climb> climbs(int weekOf) {
        List<PaperCompose.Climb> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = "SELECT r.bracket, c.name, r.rating - r.week_rating FROM pvp_rating r"
                + " JOIN characters c ON c.id = r.character_id"
                + " WHERE r.week_of = ? AND r.rating > r.week_rating AND c.gm = 0"
                + " AND r.bracket IN ('ROOKIE', 'VETERAN', 'CHAMPION')"
                + " AND r.season = (SELECT MAX(season) FROM pvp_rating)"
                + " ORDER BY r.rating - r.week_rating DESC LIMIT 3";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, ladderWeek(weekOf));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PaperCompose.Climb(rs.getString(2), rs.getString(1), (int) Math.round(rs.getDouble(3))));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_rating climbs read failed for week {}", weekOf, failure);
        }
        return out;
    }

    /** The ladder's week number (floor((epoch day + 3) / 7)) of the week whose Monday is epoch day {@code weekOf}. */
    static int ladderWeek(int weekOf) {
        return (int) Math.floorDiv(weekOf + 3L, 7L);
    }

    @Override
    public List<PaperCompose.ClearRow> clears(long fromMs, long toMs) {
        List<PaperCompose.ClearRow> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = SqlTime.utc("SELECT quest, duration_ms, cleared_at, members FROM living_clear"
                + " WHERE cleared_at >= ? AND cleared_at < ? ORDER BY id");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            SqlTime.set(ps, 1, fromMs);
            SqlTime.set(ps, 2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PaperCompose.ClearRow(rs.getString(1), rs.getLong(2), SqlTime.get(rs, 3),
                            memberNames(rs.getString(4))));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_clear window read failed", failure);
        }
        return out;
    }

    /** The names of a living_clear members string ("id:K:name,..."). */
    static List<String> memberNames(String members) {
        List<String> out = new ArrayList<>();
        if (members == null || members.isBlank()) {
            return out;
        }
        for (String one : members.split(",")) {
            String[] parts = one.split(":", 3);
            if (parts.length == 3 && !parts[2].isBlank()) {
                out.add(parts[2]);
            }
        }
        return out;
    }

    @Override
    public Map<String, Long> fastestBefore(long beforeMs) {
        Map<String, Long> out = new HashMap<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = SqlTime.utc("SELECT quest, MIN(duration_ms) FROM living_clear WHERE duration_ms > 0"
                + " AND cleared_at < ? GROUP BY quest");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            SqlTime.set(ps, 1, beforeMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_clear bests read failed", failure);
        }
        return out;
    }

    @Override
    public Set<String> clearedBefore(long beforeMs) {
        Set<String> out = new HashSet<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = SqlTime.utc("SELECT DISTINCT quest FROM living_clear WHERE cleared_at < ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            SqlTime.set(ps, 1, beforeMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_clear quests read failed", failure);
        }
        return out;
    }

    @Override
    public List<PaperCompose.Price> prices(LocalDate from, LocalDate to) {
        List<PaperCompose.Price> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = "SELECT day, item_id, price FROM economy_cpi_daily WHERE day BETWEEN ? AND ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PaperCompose.Price(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getLong(3)));
                }
            }
        } catch (SQLException failure) {
            log.warn("economy_cpi_daily read failed", failure);
        }
        return out;
    }

    @Override
    public String questLabel(String script) {
        return questLabels.apply(script);
    }

    @Override
    public String itemName(int itemId) {
        return itemNames.apply(itemId);
    }
}
