package net.server.coordinator.session;

import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Loopback-only GM policy: characters with gm > 0 may only be selected from
 * the loopback interface (127.0.0.0/8). Anyone reaching the server over a
 * network — including Tailscale share recipients on 100.x.x.x — cannot log
 * into a GM character even if they obtain the account password.
 */
public final class GmLoginSecurity {
    private static final Logger log = LoggerFactory.getLogger(GmLoginSecurity.class);

    private GmLoginSecurity() {
    }

    public static boolean blockIfGmFromRemote(Client c, int charId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT gm FROM characters WHERE id = ? AND accountid = ?")) {
            ps.setInt(1, charId);
            ps.setInt(2, c.getAccID());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt("gm") > 0
                        && !IpAddresses.isLocalAddress(c.getRemoteAddress())) {
                    log.warn("Blocked remote GM char-select: accId={} charId={} ip={}",
                            c.getAccID(), charId, c.getRemoteAddress());
                    c.sendPacket(PacketCreator.getAfterLoginError(10));
                    return true;
                }
            }
        } catch (SQLException e) {
            log.error("GM loopback check failed for charId {}: {}", charId, e.getMessage());
            c.sendPacket(PacketCreator.getAfterLoginError(10));
            return true;
        }
        return false;
    }
}
