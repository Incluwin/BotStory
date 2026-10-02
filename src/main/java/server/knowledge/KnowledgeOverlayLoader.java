package server.knowledge;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads the live overlay with four read-only {@code SELECT}s: the same tables {@code MonsterInformationProvider},
 * {@code ShopFactory} and {@code MapFactory} read, so answers match what the game does after changesets and
 * database-console edits. Shop pitch is kept (Perfect Pitch prices); global drops keep their continent.
 */
public final class KnowledgeOverlayLoader {
    private KnowledgeOverlayLoader() {
    }

    public static KnowledgeOverlay load() throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            return load(con);
        }
    }

    public static KnowledgeOverlay load(Connection con) throws SQLException {
        KnowledgeOverlay.Builder overlay = KnowledgeOverlay.builder();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT dropperid, itemid, chance, minimum_quantity, maximum_quantity, questid FROM drop_data");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                overlay.drop(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6));
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT continent, itemid, chance, minimum_quantity, maximum_quantity, questid FROM drop_data_global WHERE chance > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                overlay.globalDrop(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6));
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT s.shopid, s.npcid, i.itemid, i.price, i.pitch FROM shops s JOIN shopitems i ON i.shopid = s.shopid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                overlay.shopItem(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5));
            }
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT type, life, map FROM plife");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                overlay.life(rs.getString(1), rs.getInt(2), rs.getInt(3));
            }
        }
        return overlay.build();
    }
}
