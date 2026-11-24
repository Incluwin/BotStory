package server.living.population.craft;

import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.processor.npc.DueyProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.economy.EconomyAtomicPersistence;
import server.living.store.SqlTime;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC for commissions: the living_commission rows and what they hold (inventoryitems type 15,
 * {@link ItemFactory#COMMISSION}, keyed by {@link #KEY_BASE} + the order's id: clear of character ids, whose deletion
 * takes every row of theirs). The needs board's own escrow (type 14) does not fit: it is one set per citizen, replaced
 * whole and handed to the citizen at its next load. Not final: tests keep it in memory.
 */
public class CommissionStore {
    private static final Logger log = LoggerFactory.getLogger(CommissionStore.class);
    static final int KEY_BASE = 1_500_000_000;
    private static final String COLUMNS = "id, customer_id, crafter_id, recipe, quantity, fee, state, map_id, work_ms,"
            + " worked_ms, ordered_at, due_at, closed_at";

    /** The open orders; null when the read failed. */
    public List<Commission> open() {
        List<Commission> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT " + COLUMNS + " FROM living_commission WHERE state IN ('HELD', 'WORKING')");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Commission(rs.getInt("id"), rs.getInt("customer_id"), rs.getInt("crafter_id"),
                        rs.getString("recipe"), rs.getInt("quantity"), rs.getLong("fee"),
                        Commission.State.parse(rs.getString("state")), rs.getInt("map_id"), rs.getLong("work_ms"),
                        rs.getLong("worked_ms"), SqlTime.get(rs, "ordered_at"), SqlTime.get(rs, "due_at"),
                        SqlTime.get(rs, "closed_at")));
            }
            return out;
        } catch (SQLException failure) {
            log.warn("living_commission scan failed", failure);
            return null;
        }
    }

    /** Stores a new order on the caller's connection; its id. */
    public int insert(Connection con, Commission order) throws SQLException {
        String sql = SqlTime.utc("INSERT INTO living_commission (customer_id, crafter_id, recipe, quantity, fee, state,"
                + " map_id, work_ms, worked_ms, ordered_at, due_at, closed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, order.customerId());
            ps.setInt(2, order.crafterId());
            ps.setString(3, order.recipe());
            ps.setInt(4, order.quantity());
            ps.setLong(5, order.fee());
            ps.setString(6, order.state().name());
            ps.setInt(7, order.mapId());
            ps.setLong(8, order.workMs());
            ps.setLong(9, order.workedMs());
            SqlTime.set(ps, 10, order.orderedAtMs());
            SqlTime.setNullable(ps, 11, order.dueAtMs());
            SqlTime.setNullable(ps, 12, order.closedAtMs());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("no id for a commission of " + order.customerId());
                }
                return keys.getInt(1);
            }
        }
    }

    /** Writes the work done and the state of an open order; false when the write failed. */
    public boolean update(Commission order) {
        try (Connection con = DatabaseConnection.getConnection()) {
            update(con, order);
            return true;
        } catch (SQLException failure) {
            log.warn("living_commission update failed for {}", order.id(), failure);
            return false;
        }
    }

    /**
     * {@link #update} on the caller's connection, inside its transaction. Only an order still open in the table changes:
     * one already ended fails, so nothing is paid out or given back twice.
     */
    public void update(Connection con, Commission order) throws SQLException {
        String sql = SqlTime.utc("UPDATE living_commission SET state = ?, worked_ms = ?, due_at = ?, closed_at = ?"
                + " WHERE id = ? AND state IN ('HELD', 'WORKING')");
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, order.state().name());
            ps.setLong(2, order.workedMs());
            SqlTime.setNullable(ps, 3, order.dueAtMs());
            SqlTime.setNullable(ps, 4, order.closedAtMs());
            ps.setInt(5, order.id());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("commission " + order.id() + " is not open any more");
            }
        }
    }

    /** What the order holds; null when the read failed (nothing moves then). */
    public List<Pair<Item, InventoryType>> items(int commissionId) {
        try {
            return ItemFactory.COMMISSION.loadItems(KEY_BASE + commissionId, false);
        } catch (SQLException failure) {
            log.warn("commission {} items read failed", commissionId, failure);
            return null;
        }
    }

    /** Replaces what the order holds, on the caller's connection. */
    public void saveItems(Connection con, int commissionId, List<Pair<Item, InventoryType>> items) throws SQLException {
        ItemFactory.COMMISSION.saveItems(items, KEY_BASE + commissionId, con);
    }

    /** A Duey package on the caller's connection (an item or meso, as the stock window holds). */
    public void mail(Connection con, Item item, int meso, String message, String sender, int receiverId) throws SQLException {
        DueyProcessor.dueyCreatePackage(con, item, meso, message, sender, receiverId);
    }

    /** Runs {@code work} as one transaction of its own (a refund by Duey moves no loaded character); false: rolled back. */
    public boolean inTransaction(EconomyAtomicPersistence work) {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                work.persist(con);
                con.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                con.rollback();
                log.warn("commission transaction rolled back", failure);
                return false;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            log.warn("commission transaction failed", failure);
            return false;
        }
    }

    /** The fees held by open orders, for the money supply (MesoSupply). */
    public static long heldMeso(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(fee), 0) FROM living_commission WHERE state IN ('HELD', 'WORKING')");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
