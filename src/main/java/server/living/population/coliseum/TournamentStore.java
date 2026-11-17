package server.living.population.coliseum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.store.SqlTime;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC for {@code pvp_tournament} and {@code pvp_tournament_seat} (changeset 62, docs/PVP_PLAN.md V5). Every read
 * degrades to empty and every write logs on failure.
 */
class TournamentStore implements Tournament.Store {
    private static final Logger log = LoggerFactory.getLogger(TournamentStore.class);

    /** A week's tournament in one bracket. */
    static final String OPEN = "OPEN";
    static final String RUNNING = "RUNNING";
    static final String DONE = "DONE";
    static final String CANCELLED = "CANCELLED";

    record Week(long id, String bracket, int weekOf, long startsAtMs, String state, long pool, int championId,
                int runnerUpId, long finishedAtMs) {
    }

    /** Winnings waiting for a player: the tournament, its bracket and week, how far they got, and what they won. */
    record Owed(long tournamentId, String bracket, int weekOf, int reached, int trophies, long mesos) {
    }

    /** A seat: seed 0 is a player signed up before the start; reached is NONE while still in. */
    record Seat(int characterId, String name, boolean human, int seed, long fee, int reached, int trophies, long mesos) {
    }

    @Override
    public Optional<Week> ensure(String bracket, int weekOf, long startsAtMs) {
        if (!DatabaseConnection.isInitialized()) {
            return Optional.empty();
        }
        String sql = SqlTime.utc("INSERT IGNORE INTO pvp_tournament (bracket, week_of, starts_at, state)"
                + " VALUES (?, ?, ?, ?)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, bracket);
            ps.setInt(2, weekOf);
            SqlTime.set(ps, 3, startsAtMs);
            ps.setString(4, OPEN);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("pvp_tournament insert failed for {} week {}", bracket, weekOf, failure);
            return Optional.empty();
        }
        return week(bracket, weekOf);
    }

    @Override
    public Optional<Week> week(String bracket, int weekOf) {
        return one("WHERE bracket = ? AND week_of = ?", bracket, weekOf);
    }

    @Override
    public Optional<Week> lastDone(String bracket) {
        return one("WHERE bracket = ? AND state = '" + DONE + "' ORDER BY week_of DESC LIMIT 1", bracket, null);
    }

    private Optional<Week> one(String where, String bracket, Integer weekOf) {
        if (!DatabaseConnection.isInitialized()) {
            return Optional.empty();
        }
        String sql = SqlTime.utc("SELECT id, bracket, week_of, starts_at, state, pool, champion_id, runner_up_id,"
                + " finished_at FROM pvp_tournament " + where);
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, bracket);
            if (weekOf != null) {
                ps.setInt(2, weekOf);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Week(rs.getLong("id"), rs.getString("bracket"), rs.getInt("week_of"),
                            SqlTime.get(rs, "starts_at"), rs.getString("state"), rs.getLong("pool"),
                            rs.getInt("champion_id"), rs.getInt("runner_up_id"), SqlTime.get(rs, "finished_at")));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_tournament read failed for {}", bracket, failure);
        }
        return Optional.empty();
    }

    @Override
    public List<Seat> seats(long tournamentId) {
        List<Seat> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = "SELECT character_id, name, human, seed, fee, reached, trophies, mesos FROM pvp_tournament_seat"
                + " WHERE tournament_id = ? ORDER BY seed = 0, seed, character_id";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, tournamentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Seat(rs.getInt("character_id"), rs.getString("name"), rs.getBoolean("human"),
                            rs.getInt("seed"), rs.getLong("fee"), rs.getInt("reached"), rs.getInt("trophies"),
                            rs.getLong("mesos")));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_tournament_seat read failed for {}", tournamentId, failure);
        }
        return out;
    }

    @Override
    public boolean signUp(long tournamentId, Seat seat) {
        if (!DatabaseConnection.isInitialized()) {
            return false;
        }
        String sql = "INSERT IGNORE INTO pvp_tournament_seat (tournament_id, character_id, name, human, seed, fee)"
                + " VALUES (?, ?, ?, ?, 0, ?)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, tournamentId);
            ps.setInt(2, seat.characterId());
            ps.setString(3, seat.name());
            ps.setBoolean(4, seat.human());
            ps.setLong(5, seat.fee());
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) {
            log.warn("pvp_tournament_seat sign-up failed for {}", seat.characterId(), failure);
            return false;
        }
    }

    @Override
    public boolean withdraw(long tournamentId, int characterId) {
        if (!DatabaseConnection.isInitialized()) {
            return false;
        }
        String sql = "DELETE FROM pvp_tournament_seat WHERE tournament_id = ? AND character_id = ? AND seed = 0";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, tournamentId);
            ps.setInt(2, characterId);
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) {
            log.warn("pvp_tournament_seat withdraw failed for {}", characterId, failure);
            return false;
        }
    }

    @Override
    public void seat(long tournamentId, Seat seat) {
        if (!DatabaseConnection.isInitialized()) {
            return;
        }
        String sql = "INSERT INTO pvp_tournament_seat (tournament_id, character_id, name, human, seed, fee)"
                + " VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE seed = VALUES(seed), fee = VALUES(fee)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, tournamentId);
            ps.setInt(2, seat.characterId());
            ps.setString(3, seat.name());
            ps.setBoolean(4, seat.human());
            ps.setInt(5, seat.seed());
            ps.setLong(6, seat.fee());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("pvp_tournament_seat write failed for {}", seat.characterId(), failure);
        }
    }

    @Override
    public void result(long tournamentId, int characterId, int reached, int trophies, long mesos, boolean paid) {
        if (!DatabaseConnection.isInitialized()) {
            return;
        }
        String sql = "UPDATE pvp_tournament_seat SET reached = ?, trophies = ?, mesos = ?, paid = ?"
                + " WHERE tournament_id = ? AND character_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, reached);
            ps.setInt(2, trophies);
            ps.setLong(3, mesos);
            ps.setBoolean(4, paid);
            ps.setLong(5, tournamentId);
            ps.setInt(6, characterId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("pvp_tournament_seat result failed for {}", characterId, failure);
        }
    }

    @Override
    public List<Owed> owed(int characterId) {
        List<Owed> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = "SELECT s.tournament_id, t.bracket, t.week_of, s.reached, s.trophies, s.mesos"
                + " FROM pvp_tournament_seat s JOIN pvp_tournament t ON t.id = s.tournament_id"
                + " WHERE s.character_id = ? AND s.paid = 0 ORDER BY s.tournament_id";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Owed(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                            rs.getLong(6)));
                }
            }
        } catch (SQLException failure) {
            log.warn("pvp_tournament_seat owed read failed for {}", characterId, failure);
        }
        return out;
    }

    @Override
    public void paid(long tournamentId, int characterId) {
        if (!DatabaseConnection.isInitialized()) {
            return;
        }
        String sql = "UPDATE pvp_tournament_seat SET paid = 1 WHERE tournament_id = ? AND character_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, tournamentId);
            ps.setInt(2, characterId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("pvp_tournament_seat paid write failed for {}", characterId, failure);
        }
    }

    @Override
    public List<Week> running() {
        List<Week> out = new ArrayList<>();
        if (!DatabaseConnection.isInitialized()) {
            return out;
        }
        String sql = SqlTime.utc("SELECT id, bracket, week_of, starts_at, state, pool, champion_id, runner_up_id,"
                + " finished_at FROM pvp_tournament WHERE state = '" + RUNNING + "'");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Week(rs.getLong("id"), rs.getString("bracket"), rs.getInt("week_of"),
                        SqlTime.get(rs, "starts_at"), rs.getString("state"), rs.getLong("pool"),
                        rs.getInt("champion_id"), rs.getInt("runner_up_id"), SqlTime.get(rs, "finished_at")));
            }
        } catch (SQLException failure) {
            log.warn("pvp_tournament running read failed", failure);
        }
        return out;
    }

    @Override
    public void state(long tournamentId, String state, long pool, int championId, int runnerUpId, long finishedAtMs) {
        if (!DatabaseConnection.isInitialized()) {
            return;
        }
        String sql = SqlTime.utc("UPDATE pvp_tournament SET state = ?, pool = ?, champion_id = ?, runner_up_id = ?,"
                + " finished_at = ? WHERE id = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setLong(2, pool);
            ps.setInt(3, championId);
            ps.setInt(4, runnerUpId);
            SqlTime.setNullable(ps, 5, finishedAtMs);
            ps.setLong(6, tournamentId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("pvp_tournament state write failed for {}", tournamentId, failure);
        }
    }
}
