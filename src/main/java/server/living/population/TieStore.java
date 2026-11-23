package server.living.population;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.CitizenTies.Tie;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** living_tie (changeset 65): every citizen tie, read once at start and written in batches (CitizenTies). */
class TieStore {
    private static final Logger log = LoggerFactory.getLogger(TieStore.class);

    /** Every stored tie; null when the read fails (the ties then stay as they are in memory, nothing written over). */
    List<Tie> all() {
        List<Tie> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT a, b, kind, strength, updated_at FROM living_tie");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                CitizenTies.Kind kind = CitizenTies.Kind.parse(rs.getString(3));
                if (kind != null) {
                    out.add(new Tie(rs.getInt(1), rs.getInt(2), kind, rs.getInt(4), rs.getLong(5)));
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("living_tie read failed", failure);
            return null;
        }
    }

    /** Writes {@code changed} and deletes the pairs in {@code gone} (each {a, b}) in one transaction; false on failure. */
    boolean save(List<Tie> changed, List<int[]> gone) {
        if (changed.isEmpty() && gone.isEmpty()) {
            return true;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (PreparedStatement up = con.prepareStatement("INSERT INTO living_tie (a, b, kind, strength, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE kind = VALUES(kind), strength = VALUES(strength),"
                    + " updated_at = VALUES(updated_at)");
                 PreparedStatement down = con.prepareStatement("DELETE FROM living_tie WHERE a = ? AND b = ?")) {
                for (Tie tie : changed) {
                    up.setInt(1, tie.a());
                    up.setInt(2, tie.b());
                    up.setString(3, tie.kind().name());
                    up.setInt(4, tie.strength());
                    up.setLong(5, tie.updatedAtMs());
                    up.addBatch();
                }
                for (int[] pair : gone) {
                    down.setInt(1, pair[0]);
                    down.setInt(2, pair[1]);
                    down.addBatch();
                }
                if (!changed.isEmpty()) {
                    up.executeBatch();
                }
                if (!gone.isEmpty()) {
                    down.executeBatch();
                }
                con.commit();
                return true;
            } catch (SQLException failure) {
                con.rollback();
                throw failure;
            } finally {
                con.setAutoCommit(auto);
            }
        } catch (SQLException failure) {
            log.warn("living_tie write of {} ties failed", changed.size() + gone.size(), failure);
            return false;
        }
    }

    /** A retired citizen's ties go. */
    void forget(int citizenId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM living_tie WHERE a = ? OR b = ?")) {
            ps.setInt(1, citizenId);
            ps.setInt(2, citizenId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_tie forget of {} failed", citizenId, failure);
        }
    }
}
