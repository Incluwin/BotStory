package server.living.population.needs;

import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.store.SqlTime;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JDBC for the needs board: the living_need rows, and the items a board hand-in left for a citizen who was away
 * (inventoryitems type 14, {@link ItemFactory#NEED_ESCROW}, keyed by its character id). Every read degrades to empty and
 * every write logs on failure, so the citizens' day never stops on the database. Not final: tests keep it in memory.
 */
public class NeedStore {
    private static final Logger log = LoggerFactory.getLogger(NeedStore.class);
    private static final String COLUMNS = "id, citizen_id, kind, item_id, quantity, delivered, offer, detail, map_id, state,"
            + " claimed_by, claimed_until, fulfilled_by, thanked, created_at, deadline_at, closed_at, poster_character_id";
    /** A board hand-in's thanks are owed this long at most; older ones are let go at start. */
    private static final long THANKS_KEPT_MS = 7L * 86_400_000L;

    /**
     * The requests the ledger keeps in memory: open ones, ones in escrow, and met ones still owing thanks; PARTY rows
     * left open by a restart end first (a forming party lives in memory only).
     */
    public List<Need> live(long nowMs) {
        List<Need> out = new ArrayList<>();
        String close = SqlTime.utc("UPDATE living_need SET state = 'GONE', closed_at = ? WHERE kind = 'PARTY' AND state = 'OPEN'");
        String sql = SqlTime.utc("SELECT " + COLUMNS + " FROM living_need WHERE poster_character_id IS NULL"
                + " AND (state IN ('OPEN', 'ESCROW') OR (state = 'MET' AND thanked = 0 AND closed_at > ?))");
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(close)) {
                SqlTime.set(ps, 1, nowMs);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                SqlTime.set(ps, 1, nowMs - THANKS_KEPT_MS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Need need = read(rs);
                        if (need != null) {
                            out.add(need);
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            log.warn("living_need scan failed", failure);
        }
        return out;
    }

    /** Requests that ended since {@code sinceMs}, for the repost pause. */
    public List<Need> endedSince(long sinceMs) {
        List<Need> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT " + COLUMNS + " FROM living_need WHERE closed_at > ? AND state NOT IN ('OPEN', 'ESCROW')"
                + " AND poster_character_id IS NULL");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            SqlTime.set(ps, 1, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Need need = read(rs);
                    if (need != null) {
                        out.add(need);
                    }
                }
            }
        } catch (SQLException failure) {
            log.warn("living_need history scan failed", failure);
        }
        return out;
    }

    /** Every gear item this citizen ever asked for (a want is asked once; after it, the item is handed as before). */
    public Set<Integer> gearAsked(int citizenId) {
        Set<Integer> out = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT item_id FROM living_need WHERE citizen_id = ? AND kind = 'GEAR'")) {
            ps.setInt(1, citizenId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_need gear read failed for {}", citizenId, failure);
            return null;
        }
        return out;
    }

    /** Stores a new request; its id, or 0 when the write failed. */
    public int insert(Need need) {
        String sql = SqlTime.utc("INSERT INTO living_need (citizen_id, kind, item_id, quantity, delivered, offer, detail,"
                + " map_id, state, claimed_by, claimed_until, fulfilled_by, thanked, created_at, deadline_at, closed_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, need.citizenId());
            ps.setString(2, need.kind().name());
            ps.setInt(3, need.itemId());
            ps.setInt(4, need.quantity());
            bindMutable(ps, need, 5);
            SqlTime.set(ps, 14, need.createdAtMs());
            SqlTime.setNullable(ps, 15, need.deadlineMs());
            SqlTime.setNullable(ps, 16, need.closedAtMs());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            log.warn("living_need insert failed for citizen {}", need.citizenId(), failure);
            return 0;
        }
    }

    /** Writes what changes on a request (count, price, state, hold, helper, thanks, deadline, end). */
    public void update(Need need) {
        try (Connection con = DatabaseConnection.getConnection()) {
            update(con, need);
        } catch (SQLException failure) {
            log.warn("living_need update failed for {}", need.id(), failure);
        }
    }

    /** {@link #update} on the caller's connection, inside its transaction (a hand-in's economy commit). */
    public void update(Connection con, Need need) throws SQLException {
        String sql = SqlTime.utc("UPDATE living_need SET quantity = ?, delivered = ?, offer = ?, detail = ?, map_id = ?,"
                + " state = ?, claimed_by = ?, claimed_until = ?, fulfilled_by = ?, thanked = ?, deadline_at = ?,"
                + " closed_at = ? WHERE id = ?");
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, need.quantity());
            bindMutable(ps, need, 2);
            SqlTime.setNullable(ps, 11, need.deadlineMs());
            SqlTime.setNullable(ps, 12, need.closedAtMs());
            ps.setInt(13, need.id());
            ps.executeUpdate();
        }
        if (need.state() != Need.State.OPEN && need.state() != Need.State.ESCROW) {
            NeedDerivation.closed(need, need.closedAtMs(), autoCommit(con)); // the learned board offers hear it (I2.2)
        }
    }

    /** Whether {@code con} commits each statement (an end written on it holds); false when unsure. */
    private static boolean autoCommit(Connection con) {
        try {
            return con.getAutoCommit();
        } catch (SQLException | RuntimeException failure) {
            return false;
        }
    }

    /** delivered, offer, detail, map, state, claimed_by, claimed_until, fulfilled_by, thanked from {@code first}. */
    private static void bindMutable(PreparedStatement ps, Need need, int first) throws SQLException {
        ps.setInt(first, need.delivered());
        ps.setLong(first + 1, need.offer());
        ps.setString(first + 2, need.detail());
        ps.setInt(first + 3, need.mapId());
        ps.setString(first + 4, need.state().name());
        if (need.claimedBy() > 0) {
            ps.setInt(first + 5, need.claimedBy());
        } else {
            ps.setNull(first + 5, java.sql.Types.INTEGER);
        }
        SqlTime.setNullable(ps, first + 6, need.claimedUntilMs());
        if (need.fulfilledBy() > 0) {
            ps.setInt(first + 7, need.fulfilledBy());
        } else {
            ps.setNull(first + 7, java.sql.Types.INTEGER);
        }
        ps.setInt(first + 8, need.thanked() ? 1 : 0);
    }

    // ---- players' buy orders (N8.3) --------------------------------------------------------

    /** Every open order a player posted; null when the read failed. */
    public List<Need> posted() {
        List<Need> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT " + COLUMNS + " FROM living_need WHERE state = 'OPEN' AND poster_character_id IS NOT NULL");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Need need = read(rs);
                if (need != null) {
                    out.add(need);
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("buy order scan failed", failure);
            return null;
        }
    }

    /** Stores a player's new order on the caller's connection (the commit that takes its meso); its id. */
    public int insertPosted(Connection con, Need order) throws SQLException {
        String sql = SqlTime.utc("INSERT INTO living_need (citizen_id, kind, item_id, quantity, offer, map_id, state,"
                + " created_at, deadline_at, poster_character_id) VALUES (0, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)");
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, order.kind().name());
            ps.setInt(2, order.itemId());
            ps.setInt(3, order.quantity());
            ps.setLong(4, order.offer());
            ps.setInt(5, order.mapId());
            SqlTime.set(ps, 6, order.createdAtMs());
            SqlTime.set(ps, 7, order.deadlineMs());
            ps.setInt(8, order.posterId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("no id for a buy order of " + order.posterId());
                }
                return keys.getInt(1);
            }
        }
    }

    /**
     * A player's order filled in part, filled, or ended, on the caller's connection. Only one still open in the table,
     * with as much delivered as {@code before}, changes: else it fails, so nothing is paid or given back twice.
     */
    public void updatePosted(Connection con, Need before, Need after) throws SQLException {
        String sql = SqlTime.utc("UPDATE living_need SET delivered = ?, state = ?, fulfilled_by = ?, closed_at = ?"
                + " WHERE id = ? AND state = 'OPEN' AND delivered = ?");
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, after.delivered());
            ps.setString(2, after.state().name());
            if (after.fulfilledBy() > 0) {
                ps.setInt(3, after.fulfilledBy());
            } else {
                ps.setNull(3, java.sql.Types.INTEGER);
            }
            SqlTime.setNullable(ps, 4, after.closedAtMs());
            ps.setInt(5, before.id());
            ps.setInt(6, before.delivered());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("buy order " + before.id() + " changed meanwhile");
            }
        }
    }

    /** A Duey package on the caller's connection. */
    public void mail(Connection con, Item item, int meso, String message, String sender, int receiverId) throws SQLException {
        client.processor.npc.DueyProcessor.dueyCreatePackage(con, item, meso, message, sender, receiverId);
    }

    /** Runs {@code work} as one transaction (an order's end while its player is away); false: rolled back. */
    public boolean inTransaction(server.economy.EconomyAtomicPersistence work) {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                work.persist(con);
                con.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                con.rollback();
                log.warn("buy order transaction rolled back", failure);
                return false;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            log.warn("buy order transaction failed", failure);
            return false;
        }
    }

    /** The meso players' open buy orders hold, for the money supply (MesoSupply). */
    public static long heldMeso(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COALESCE(SUM(offer * (quantity - delivered)), 0)"
                + " FROM living_need WHERE state = 'OPEN' AND poster_character_id IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ---- escrow ---------------------------------------------------------------------------

    /** The citizens with board hand-ins waiting for their next load. */
    public Set<Integer> escrowHolders() {
        Set<Integer> out = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT DISTINCT characterid FROM inventoryitems WHERE type = ?")) {
            ps.setInt(1, ItemFactory.NEED_ESCROW.getValue());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        } catch (SQLException failure) {
            log.warn("need escrow scan failed", failure);
        }
        return out;
    }

    /** The items waiting for this citizen; null when the read failed (nothing is taken out then). */
    public List<Pair<Item, InventoryType>> escrow(int citizenId) {
        try {
            return ItemFactory.NEED_ESCROW.loadItems(citizenId, false);
        } catch (SQLException failure) {
            log.warn("need escrow read failed for {}", citizenId, failure);
            return null;
        }
    }

    /** Replaces what waits for this citizen with {@code items}, on the caller's connection (its economy commit). */
    public void saveEscrow(Connection con, int citizenId, List<Pair<Item, InventoryType>> items) throws SQLException {
        ItemFactory.NEED_ESCROW.saveItems(items, citizenId, con);
    }

    /** Takes {@code meso} from an unloaded character's purse, on the caller's connection; fails when it holds less. */
    public void debit(Connection con, int characterId, long meso) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET meso = meso - ? WHERE id = ? AND meso >= ?")) {
            ps.setLong(1, meso);
            ps.setInt(2, characterId);
            ps.setLong(3, meso);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("character " + characterId + " cannot pay " + meso + " meso");
            }
        }
    }

    private static Need read(ResultSet rs) throws SQLException {
        NeedKind kind = NeedKind.parse(rs.getString("kind"));
        if (kind == null) {
            return null;
        }
        return new Need(rs.getInt("id"), rs.getInt("citizen_id"), kind, rs.getInt("item_id"), rs.getInt("quantity"),
                rs.getInt("delivered"), rs.getLong("offer"), rs.getString("detail"), rs.getInt("map_id"),
                Need.State.parse(rs.getString("state")), rs.getInt("claimed_by"), SqlTime.get(rs, "claimed_until"),
                rs.getInt("fulfilled_by"), rs.getInt("thanked") != 0, SqlTime.get(rs, "created_at"),
                SqlTime.get(rs, "deadline_at"), SqlTime.get(rs, "closed_at"), rs.getInt("poster_character_id"));
    }
}
