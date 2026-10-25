package server.living.bond;

import client.Character;
import client.Ring;
import client.inventory.manipulator.CashIdGenerator;
import net.server.Server;
import net.server.world.World;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** {@link LoreCouples.Ledger} on the stock rows: World's marriages (and its cache), the rings table, PlayerStorage. */
final class LoreCoupleLedger implements LoreCouples.Ledger {
    private final int worldId;

    LoreCoupleLedger(int worldId) {
        this.worldId = worldId;
    }

    private World world() {
        return Server.getInstance().getWorld(worldId);
    }

    @Override
    public int marriageOf(int characterId) {
        return world().getRelationshipId(characterId);
    }

    @Override
    public int marry(int husbandId, int wifeId) {
        return world().createRelationship(husbandId, wifeId);
    }

    @Override
    public void unmarry(int characterId, int partnerId) {
        if (world().getRelationshipId(characterId) > 0) { // also caches the row deleteRelationship looks up
            world().deleteRelationship(characterId, partnerId);
        }
    }

    @Override
    public Ring ringOf(int characterId, int partnerId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT r.id, r.partnerRingId, r.partnerChrId, r.itemid, "
                     + "r.partnername FROM rings r JOIN rings p ON p.id = r.partnerRingId "
                     + "WHERE r.partnerChrId = ? AND p.partnerChrId = ? ORDER BY r.id DESC")) {
            ps.setInt(1, partnerId);
            ps.setInt(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("itemid");
                    if (constants.id.ItemId.isWeddingRing(itemId)) {
                        return new Ring(rs.getInt("id"), rs.getInt("partnerRingId"), rs.getInt("partnerChrId"), itemId,
                                rs.getString("partnername"));
                    }
                }
            }
        } catch (SQLException failure) {
            monitoring.RuntimeFailureLogger.log(failure);
        }
        return null;
    }

    /** Ring.createRing's two rows, for partners that need not be loaded. */
    @Override
    public Ring makeRings(int itemId, int firstId, String firstName, int secondId, String secondName) {
        int first = CashIdGenerator.generateCashId();
        int second = CashIdGenerator.generateCashId();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO rings (id, itemid, partnerRingId, partnerChrId, partnername) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, first);
            ps.setInt(2, itemId);
            ps.setInt(3, second);
            ps.setInt(4, secondId);
            ps.setString(5, secondName);
            ps.addBatch();
            ps.setInt(1, second);
            ps.setInt(2, itemId);
            ps.setInt(3, first);
            ps.setInt(4, firstId);
            ps.setString(5, firstName);
            ps.addBatch();
            ps.executeBatch();
            return new Ring(first, second, secondId, itemId, secondName);
        } catch (SQLException failure) {
            monitoring.RuntimeFailureLogger.log(failure);
            CashIdGenerator.freeCashId(first);
            CashIdGenerator.freeCashId(second);
            return null;
        }
    }

    @Override
    public void removeRings(Ring ring) {
        Ring.removeRing(ring);
    }

    /** True unless the character row is gone; a failed read counts as there (never a reason to break a marriage). */
    @Override
    public boolean exists(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1 FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException failure) {
            monitoring.RuntimeFailureLogger.log(failure);
            return true;
        }
    }

    @Override
    public String nameOf(int characterId) {
        String name = Character.getNameById(characterId);
        return name == null ? "" : name;
    }

    @Override
    public boolean loaded(int characterId) {
        return world().getPlayerStorage().getCharacterById(characterId) != null;
    }
}
