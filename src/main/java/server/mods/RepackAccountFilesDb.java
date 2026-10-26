package server.mods;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Table account_client_files (changeset 48-account-client-files): one row per account that said, or showed, which
 * game files its client has. No row = not known yet. A failed read counts as not known, so the gate refuses rather
 * than risk a closing client.
 */
final class RepackAccountFilesDb implements RepackMaps.Store {
    private static final Logger log = LoggerFactory.getLogger(RepackAccountFilesDb.class);

    @Override
    public Optional<RepackMaps.Answer> load(int accountId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT files, source FROM account_client_files WHERE accountid = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                RepackMaps.Files files = RepackMaps.Files.of(rs.getString("files"));
                RepackMaps.Source source = RepackMaps.Source.of(rs.getString("source"));
                return files == null ? Optional.empty()
                        : Optional.of(new RepackMaps.Answer(files, source == null ? RepackMaps.Source.PLAYER : source));
            }
        } catch (SQLException e) {
            log.warn("Could not read the game files of account {}", accountId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(int accountId, RepackMaps.Answer answer) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO account_client_files (accountid, files, source) VALUES (?, ?, ?)"
                     + " ON DUPLICATE KEY UPDATE files = VALUES(files), source = VALUES(source), updated = CURRENT_TIMESTAMP")) {
            ps.setInt(1, accountId);
            ps.setString(2, answer.files().word());
            ps.setString(3, answer.source().word());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not save the game files of account {}", accountId, e);
        }
    }

    @Override
    public void forget(int accountId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM account_client_files WHERE accountid = ?")) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not forget the game files of account {}", accountId, e);
        }
    }
}
