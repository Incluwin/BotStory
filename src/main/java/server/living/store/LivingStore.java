package server.living.store;

import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.Content.BondStage;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * JDBC access to the living_* tables (029-living-world.sql). Plain statements, one method per
 * operation; callers own transactions only where two writes must be atomic.
 */
public final class LivingStore {
    private static final Logger log = LoggerFactory.getLogger(LivingStore.class);

    public record PersonaState(String personaId, int characterId, String arcChapter, int shapedVersion, int employedBy) {
        public PersonaState withCharacter(int id) {
            return new PersonaState(personaId, id, arcChapter, shapedVersion, employedBy);
        }

        public PersonaState withChapter(String chapter) {
            return new PersonaState(personaId, characterId, chapter, shapedVersion, employedBy);
        }

        public PersonaState withShaped(int version) {
            return new PersonaState(personaId, characterId, arcChapter, version, employedBy);
        }

        public PersonaState withEmployer(int owner) {
            return new PersonaState(personaId, characterId, arcChapter, shapedVersion, owner);
        }
    }

    public enum ContractState { NONE, OFFERED, ACTIVE, ENDED }

    public record Bond(int characterId,
                       String personaId,
                       BondStage stage,
                       int points,
                       Set<String> flags,
                       ContractState contractState,
                       long contractStartedAtMs,
                       long offerExpiresAtMs,
                       int proofProgress,
                       long paidMeso,
                       int partySeconds,
                       long leftAtMs,
                       long lastInteractionAtMs) {
        public static Bond fresh(int characterId, String personaId, long nowMs) {
            return new Bond(characterId, personaId, BondStage.MET, 0, new LinkedHashSet<>(), ContractState.NONE,
                    0L, 0L, 0, 0L, 0, 0L, nowMs);
        }

        public boolean has(String flag) {
            return flags.contains(flag);
        }
    }

    public enum QuestStatus { ACTIVE, COMPLETED }

    public record QuestState(int characterId, String questId, int stepIndex, int progress, QuestStatus status,
                             long startedAtMs, long completedAtMs, int repeats) { }

    /**
     * A companion's seat state (docs/BOT_MANAGEMENT_PLAN.md, "Seat states"). TRAVELLING, HOLD and PARKED come with
     * changeset 40 and are entered from P2b on; the legal moves are CompanionSeatState's. Only RosterService writes it.
     */
    public enum RosterState { ACTIVE, BENCHED, AWAY, RELEASED, TRAVELLING, HOLD, PARKED }

    public enum Assignment { TRAIN, FARM_MESO, FARM_ITEM, GATHER, SELL, STALL, REST }

    /**
     * One living_roster row. {@code etaAtMs} is set only while TRAVELLING (when it arrives), {@code holdMapId} only
     * while HOLD (where it waits) and {@code parkedAtMs} only while PARKED (when its owner went offline); 0 otherwise.
     */
    public record RosterEntry(int characterId,
                              String personaId,
                              RosterState state,
                              Assignment assignment,
                              int assignmentItem,
                              int assignmentCount,
                              int campMapId,
                              long accruedExp,
                              long haulMeso,
                              long wagesPaidUntilMs,
                              long recruitedAtMs,
                              long benchedAtMs,
                              long lastLedgerAtMs,
                              int farmSecondsToday,
                              String farmDay,
                              long etaAtMs,
                              int holdMapId,
                              long parkedAtMs) {
        /** A row with no travel, hold or park under way. */
        public RosterEntry(int characterId, String personaId, RosterState state, Assignment assignment, int assignmentItem,
                           int assignmentCount, int campMapId, long accruedExp, long haulMeso, long wagesPaidUntilMs,
                           long recruitedAtMs, long benchedAtMs, long lastLedgerAtMs, int farmSecondsToday, String farmDay) {
            this(characterId, personaId, state, assignment, assignmentItem, assignmentCount, campMapId, accruedExp,
                    haulMeso, wagesPaidUntilMs, recruitedAtMs, benchedAtMs, lastLedgerAtMs, farmSecondsToday, farmDay,
                    0L, 0, 0L);
        }

        /**
         * State change; entering bench-governed work also restarts the ledger clock so no idle time is credited (a
         * PARKED row benched once its grace ends passes the grace deadline as {@code nowMs}, so only the time after it
         * is credited). The travel, hold and park columns are cleared, except the one the new state keeps.
         */
        public RosterEntry with(RosterState newState, long nowMs) {
            boolean benchGoverned = newState == RosterState.BENCHED || newState == RosterState.AWAY;
            return new RosterEntry(characterId, personaId, newState, assignment, assignmentItem, assignmentCount,
                    campMapId, accruedExp, haulMeso, wagesPaidUntilMs, recruitedAtMs,
                    newState == RosterState.BENCHED ? nowMs : benchedAtMs,
                    benchGoverned ? nowMs : lastLedgerAtMs, farmSecondsToday, farmDay,
                    newState == RosterState.TRAVELLING ? etaAtMs : 0L,
                    newState == RosterState.HOLD ? holdMapId : 0,
                    newState == RosterState.PARKED ? parkedAtMs : 0L);
        }

        /** TRAVELLING, arriving at {@code arrivesAtMs}. */
        public RosterEntry withTravel(long arrivesAtMs, long nowMs) {
            return with(RosterState.TRAVELLING, nowMs).withLifecycle(arrivesAtMs, 0, 0L);
        }

        /** HOLD on {@code mapId}. */
        public RosterEntry withHold(int mapId, long nowMs) {
            return with(RosterState.HOLD, nowMs).withLifecycle(0L, mapId, 0L);
        }

        /** PARKED since {@code nowMs}. */
        public RosterEntry withPark(long nowMs) {
            return with(RosterState.PARKED, nowMs).withLifecycle(0L, 0, nowMs);
        }

        private RosterEntry withLifecycle(long eta, int holdMap, long parkedAt) {
            return new RosterEntry(characterId, personaId, state, assignment, assignmentItem, assignmentCount, campMapId,
                    accruedExp, haulMeso, wagesPaidUntilMs, recruitedAtMs, benchedAtMs, lastLedgerAtMs, farmSecondsToday,
                    farmDay, eta, holdMap, parkedAt);
        }
    }

    public record HaulItem(int itemId, int quantity) { }

    public record LearnedPhrase(String normalizedText, String canonical, int hits, String source, boolean approved,
                                long firstSeenMs, long lastSeenMs) { }

    // ---- persona state -------------------------------------------------------------------

    public PersonaState personaState(String personaId) {
        String sql = "SELECT character_id, arc_chapter, shaped_version, employed_by FROM living_persona_state WHERE persona_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PersonaState(personaId, rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_persona_state read failed for {}", personaId, failure);
        }
        return new PersonaState(personaId, 0, null, 0, 0);
    }

    public void savePersonaState(PersonaState state) {
        String sql = "INSERT INTO living_persona_state (persona_id, character_id, arc_chapter, shaped_version, employed_by)"
                + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE character_id = VALUES(character_id),"
                + " arc_chapter = VALUES(arc_chapter), shaped_version = VALUES(shaped_version), employed_by = VALUES(employed_by)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, state.personaId());
            setNullableInt(ps, 2, state.characterId());
            ps.setString(3, state.arcChapter());
            ps.setInt(4, state.shapedVersion());
            setNullableInt(ps, 5, state.employedBy());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_persona_state write failed for {}", state.personaId(), failure);
        }
    }

    public Map<String, PersonaState> allPersonaStates() {
        Map<String, PersonaState> out = new LinkedHashMap<>();
        String sql = "SELECT persona_id, character_id, arc_chapter, shaped_version, employed_by FROM living_persona_state";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), new PersonaState(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
            }
        } catch (SQLException failure) {
            log.warn("living_persona_state scan failed", failure);
        }
        return out;
    }

    // ---- world flags ---------------------------------------------------------------------

    public Set<String> worldFlags() {
        Set<String> out = new LinkedHashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT flag FROM living_world_flag");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException failure) {
            log.warn("living_world_flag scan failed", failure);
        }
        return out;
    }

    public void setWorldFlag(String flag, int setBy) {
        String sql = "INSERT IGNORE INTO living_world_flag (flag, set_by) VALUES (?, ?)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, flag);
            setNullableInt(ps, 2, setBy);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_world_flag write failed for {}", flag, failure);
        }
    }

    public void clearWorldFlag(String flag) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM living_world_flag WHERE flag = ?")) {
            ps.setString(1, flag);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_world_flag delete failed for {}", flag, failure);
        }
    }

    // ---- bonds ---------------------------------------------------------------------------

    private static final String BOND_COLUMNS = "character_id, persona_id, stage, points, flags, contract_state,"
            + " contract_started_at, offer_expires_at, proof_progress, paid_meso, party_seconds, left_at, last_interaction_at";

    public Bond bond(int characterId, String personaId) {
        String sql = SqlTime.utc("SELECT " + BOND_COLUMNS + " FROM living_bond WHERE character_id = ? AND persona_id = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readBond(rs);
                }
            }
        } catch (SQLException failure) {
            log.warn("living_bond read failed for {}/{}", characterId, personaId, failure);
        }
        return null;
    }

    public List<Bond> bondsOf(int characterId) {
        List<Bond> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT " + BOND_COLUMNS + " FROM living_bond WHERE character_id = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readBond(rs));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_bond scan failed for {}", characterId, failure);
        }
        return out;
    }

    /** Character id holding an ACTIVE contract with this persona, or 0. */
    public int activeContractOwner(String personaId) {
        String sql = "SELECT character_id FROM living_bond WHERE persona_id = ? AND contract_state = 'ACTIVE' LIMIT 1";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            log.warn("living_bond owner lookup failed for {}", personaId, failure);
            return 0;
        }
    }

    /** Sum of party_seconds over every bond with this persona. */
    public long partySecondsTotal(String personaId) {
        String sql = "SELECT COALESCE(SUM(party_seconds), 0) FROM living_bond WHERE persona_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            log.warn("living_bond party seconds sum failed for {}", personaId, failure);
            return 0L;
        }
    }

    /** Level of any character (online or not) straight from the characters table; 0 if unknown. */
    public int characterLevel(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT level FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            log.warn("character level lookup failed for {}", characterId, failure);
            return 0;
        }
    }

    /** {level, job id} of any character (online or not) from the characters table; {0, -1} if unknown. */
    public int[] characterLevelAndJob(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT level, job FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[] {rs.getInt(1), rs.getInt(2)} : new int[] {0, -1};
            }
        } catch (SQLException failure) {
            log.warn("character level/job lookup failed for {}", characterId, failure);
            return new int[] {0, -1};
        }
    }

    /**
     * When the character's player was last seen (decision 18): the later of the character's last logout and its
     * account's last login, which any of the account's characters sets; 0 when neither is known or it has no row, and
     * Long.MAX_VALUE when the read fails (so nobody is taken for away on a database hiccup).
     */
    public long lastSeenAtMs(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT c.lastLogoutTime, a.lastlogin FROM characters c"
                     + " LEFT JOIN accounts a ON a.id = c.accountid WHERE c.id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                Timestamp logout = rs.getTimestamp(1);
                Timestamp login = rs.getTimestamp(2);
                return Math.max(logout == null ? 0L : logout.getTime(), login == null ? 0L : login.getTime());
            }
        } catch (SQLException failure) {
            log.warn("last seen lookup failed for {}", characterId, failure);
            return Long.MAX_VALUE;
        }
    }

    /** A character's name, look, level, job and saved map, as its characters row holds them now. */
    public record CharacterRow(String name, int gender, int skin, int face, int hair, int level, int job, int mapId) { }

    /** The characters row of any character (online or not; a loaded one's is as of its last save); null if unknown. */
    public CharacterRow characterRow(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT name, gender, skincolor, face, hair, level, job, map FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new CharacterRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8)) : null;
            }
        } catch (SQLException failure) {
            log.warn("character row lookup failed for {}", characterId, failure);
            return null;
        }
    }

    /** Mobs dropping an item with their per-million chance, from Cosmic's drop_data table. */
    public List<int[]> mobsDropping(int itemId) {
        List<int[]> out = new ArrayList<>();
        String sql = "SELECT dropperid, chance FROM drop_data WHERE itemid = ? ORDER BY chance DESC LIMIT 20";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new int[] {rs.getInt(1), rs.getInt(2)});
                }
            }
        } catch (SQLException failure) {
            log.warn("drop_data lookup failed for item {}", itemId, failure);
        }
        return out;
    }

    public void saveBond(Bond bond) {
        String sql = SqlTime.utc("INSERT INTO living_bond (" + BOND_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE stage = VALUES(stage), points = VALUES(points), flags = VALUES(flags),"
                + " contract_state = VALUES(contract_state), contract_started_at = VALUES(contract_started_at),"
                + " offer_expires_at = VALUES(offer_expires_at), proof_progress = VALUES(proof_progress),"
                + " paid_meso = VALUES(paid_meso), party_seconds = VALUES(party_seconds), left_at = VALUES(left_at),"
                + " last_interaction_at = VALUES(last_interaction_at)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, bond.characterId());
            ps.setString(2, bond.personaId());
            ps.setString(3, bond.stage().name());
            ps.setInt(4, bond.points());
            ps.setString(5, String.join(",", bond.flags()));
            ps.setString(6, bond.contractState().name());
            SqlTime.setNullable(ps, 7, bond.contractStartedAtMs());
            SqlTime.setNullable(ps, 8, bond.offerExpiresAtMs());
            ps.setInt(9, bond.proofProgress());
            ps.setLong(10, bond.paidMeso());
            ps.setInt(11, bond.partySeconds());
            SqlTime.setNullable(ps, 12, bond.leftAtMs());
            SqlTime.setNullable(ps, 13, bond.lastInteractionAtMs());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_bond write failed for {}/{}", bond.characterId(), bond.personaId(), failure);
        }
    }

    private static Bond readBond(ResultSet rs) throws SQLException {
        Set<String> flags = new LinkedHashSet<>();
        String raw = rs.getString("flags");
        if (raw != null && !raw.isBlank()) {
            for (String flag : raw.split(",")) {
                if (!flag.isBlank()) {
                    flags.add(flag.trim());
                }
            }
        }
        return new Bond(rs.getInt("character_id"), rs.getString("persona_id"),
                BondStage.valueOf(rs.getString("stage")), rs.getInt("points"), flags,
                ContractState.valueOf(rs.getString("contract_state")),
                SqlTime.get(rs, "contract_started_at"), SqlTime.get(rs, "offer_expires_at"),
                rs.getInt("proof_progress"), rs.getLong("paid_meso"), rs.getInt("party_seconds"),
                SqlTime.get(rs, "left_at"), SqlTime.get(rs, "last_interaction_at"));
    }

    // ---- quests --------------------------------------------------------------------------

    public QuestState questState(int characterId, String questId) {
        String sql = SqlTime.utc("SELECT step_index, progress, status, started_at, completed_at, repeats FROM living_quest_state"
                + " WHERE character_id = ? AND quest_id = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, questId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new QuestState(characterId, questId, rs.getInt(1), rs.getInt(2),
                            QuestStatus.valueOf(rs.getString(3)), SqlTime.get(rs, 4), SqlTime.get(rs, 5),
                            rs.getInt(6));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_quest_state read failed for {}/{}", characterId, questId, failure);
        }
        return null;
    }

    public List<QuestState> questStates(int characterId, QuestStatus status) {
        List<QuestState> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT quest_id, step_index, progress, status, started_at, completed_at, repeats FROM living_quest_state"
                + " WHERE character_id = ?" + (status == null ? "" : " AND status = ?"));
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            if (status != null) {
                ps.setString(2, status.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new QuestState(characterId, rs.getString(1), rs.getInt(2), rs.getInt(3),
                            QuestStatus.valueOf(rs.getString(4)), SqlTime.get(rs, 5), SqlTime.get(rs, 6),
                            rs.getInt(7)));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_quest_state scan failed for {}", characterId, failure);
        }
        return out;
    }

    public void saveQuestState(QuestState state) {
        String sql = SqlTime.utc("INSERT INTO living_quest_state (character_id, quest_id, step_index, progress, status, started_at,"
                + " completed_at, repeats) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE step_index = VALUES(step_index),"
                + " progress = VALUES(progress), status = VALUES(status), started_at = VALUES(started_at),"
                + " completed_at = VALUES(completed_at), repeats = VALUES(repeats)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, state.characterId());
            ps.setString(2, state.questId());
            ps.setInt(3, state.stepIndex());
            ps.setInt(4, state.progress());
            ps.setString(5, state.status().name());
            SqlTime.setNullable(ps, 6, state.startedAtMs());
            SqlTime.setNullable(ps, 7, state.completedAtMs());
            ps.setInt(8, state.repeats());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_quest_state write failed for {}/{}", state.characterId(), state.questId(), failure);
        }
    }

    public void deleteQuestState(int characterId, String questId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM living_quest_state WHERE character_id = ? AND quest_id = ?")) {
            ps.setInt(1, characterId);
            ps.setString(2, questId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_quest_state delete failed for {}/{}", characterId, questId, failure);
        }
    }

    // ---- roster --------------------------------------------------------------------------

    private static final String ROSTER_COLUMNS = "character_id, persona_id, state, assignment, assignment_item,"
            + " assignment_count, camp_map_id, accrued_exp, haul_meso, wages_paid_until, recruited_at, benched_at,"
            + " last_ledger_at, farm_seconds_today, farm_day, eta_at, hold_map_id, parked_at";
    /** Every column saveRosterProgress writes: all but the keys, state, recruited_at, lineup_slot and eta/hold/park. */
    private static final String ROSTER_PROGRESS_COLUMNS = "assignment = ?, assignment_item = ?, assignment_count = ?,"
            + " camp_map_id = ?, accrued_exp = ?, haul_meso = ?, wages_paid_until = ?, benched_at = ?,"
            + " last_ledger_at = ?, farm_seconds_today = ?, farm_day = ?";

    public RosterEntry rosterEntry(int characterId, String personaId) {
        String sql = SqlTime.utc("SELECT " + ROSTER_COLUMNS + " FROM living_roster WHERE character_id = ? AND persona_id = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readRoster(rs);
                }
            }
        } catch (SQLException failure) {
            log.warn("living_roster read failed for {}/{}", characterId, personaId, failure);
        }
        return null;
    }

    public List<RosterEntry> roster(int characterId) {
        return rosterWhere("character_id = ? AND state <> 'RELEASED'", ps -> ps.setInt(1, characterId));
    }

    public RosterEntry rosterOfPersona(String personaId) {
        List<RosterEntry> entries = rosterWhere("persona_id = ? AND state <> 'RELEASED'", ps -> ps.setString(1, personaId));
        return entries.isEmpty() ? null : entries.get(0);
    }

    public List<RosterEntry> allBenched() {
        return rosterWhere("state IN ('BENCHED','AWAY')", ps -> { });
    }

    public List<RosterEntry> allActive() {
        return rosterWhere("state = 'ACTIVE'", ps -> { });
    }

    /** Rows between the party and the bench: on the way (TRAVELLING), waiting outside a run (HOLD), or PARKED. */
    public List<RosterEntry> allInTransit() {
        return rosterWhere("state IN ('TRAVELLING','HOLD','PARKED')", ps -> { });
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<RosterEntry> rosterWhere(String where, Binder binder) {
        List<RosterEntry> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT " + ROSTER_COLUMNS + " FROM living_roster WHERE " + where);
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readRoster(rs));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_roster scan failed ({})", where, failure);
        }
        return out;
    }

    public void saveRoster(RosterEntry entry) {
        String sql = SqlTime.utc("INSERT INTO living_roster (" + ROSTER_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE state = VALUES(state), assignment = VALUES(assignment),"
                + " assignment_item = VALUES(assignment_item), assignment_count = VALUES(assignment_count),"
                + " camp_map_id = VALUES(camp_map_id), accrued_exp = VALUES(accrued_exp), haul_meso = VALUES(haul_meso),"
                + " wages_paid_until = VALUES(wages_paid_until), benched_at = VALUES(benched_at),"
                + " last_ledger_at = VALUES(last_ledger_at), farm_seconds_today = VALUES(farm_seconds_today), farm_day = VALUES(farm_day),"
                + " eta_at = VALUES(eta_at), hold_map_id = VALUES(hold_map_id), parked_at = VALUES(parked_at)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, entry.characterId());
            ps.setString(2, entry.personaId());
            ps.setString(3, entry.state().name());
            ps.setString(4, entry.assignment().name());
            setNullableInt(ps, 5, entry.assignmentItem());
            setNullableInt(ps, 6, entry.assignmentCount());
            setNullableInt(ps, 7, entry.campMapId());
            ps.setLong(8, entry.accruedExp());
            ps.setLong(9, entry.haulMeso());
            SqlTime.setNullable(ps, 10, entry.wagesPaidUntilMs());
            SqlTime.set(ps, 11, entry.recruitedAtMs() == 0L ? System.currentTimeMillis() : entry.recruitedAtMs());
            SqlTime.setNullable(ps, 12, entry.benchedAtMs());
            SqlTime.setNullable(ps, 13, entry.lastLedgerAtMs());
            ps.setInt(14, entry.farmSecondsToday());
            ps.setString(15, entry.farmDay());
            SqlTime.setNullable(ps, 16, entry.etaAtMs());
            setNullableInt(ps, 17, entry.holdMapId());
            SqlTime.setNullable(ps, 18, entry.parkedAtMs());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_roster write failed for {}/{}", entry.characterId(), entry.personaId(), failure);
        }
    }

    /**
     * Saves a row's bench progress (assignment, camp, banked EXP, haul meso, wages, ledger clocks) but never its
     * state or the travel, hold and park columns that go with it: only RosterService moves a companion between states
     * (plan P2a, "Data model"). A row that is gone stays gone.
     */
    public void saveRosterProgress(RosterEntry entry) {
        String sql = SqlTime.utc("UPDATE living_roster SET " + ROSTER_PROGRESS_COLUMNS + " WHERE character_id = ? AND persona_id = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, entry.assignment().name());
            setNullableInt(ps, 2, entry.assignmentItem());
            setNullableInt(ps, 3, entry.assignmentCount());
            setNullableInt(ps, 4, entry.campMapId());
            ps.setLong(5, entry.accruedExp());
            ps.setLong(6, entry.haulMeso());
            SqlTime.setNullable(ps, 7, entry.wagesPaidUntilMs());
            SqlTime.setNullable(ps, 8, entry.benchedAtMs());
            SqlTime.setNullable(ps, 9, entry.lastLedgerAtMs());
            ps.setInt(10, entry.farmSecondsToday());
            ps.setString(11, entry.farmDay());
            ps.setInt(12, entry.characterId());
            ps.setString(13, entry.personaId());
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_roster progress write failed for {}/{}", entry.characterId(), entry.personaId(), failure);
        }
    }

    /** The owner's usual lineup: persona id -> lineup_slot (1 = first), for the rows that have one. */
    public Map<String, Integer> lineupSlots(int characterId) {
        Map<String, Integer> out = new HashMap<>();
        String sql = "SELECT persona_id, lineup_slot FROM living_roster"
                + " WHERE character_id = ? AND lineup_slot IS NOT NULL AND state <> 'RELEASED'";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("persona_id"), rs.getInt("lineup_slot"));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_roster lineup read failed for {}", characterId, failure);
        }
        return out;
    }

    /** Sets (or with null clears) one companion's place in its owner's usual lineup. */
    public void saveLineupSlot(int characterId, String personaId, Integer lineupSlot) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "UPDATE living_roster SET lineup_slot = ? WHERE character_id = ? AND persona_id = ?")) {
            if (lineupSlot == null) {
                ps.setNull(1, java.sql.Types.TINYINT);
            } else {
                ps.setInt(1, lineupSlot);
            }
            ps.setInt(2, characterId);
            ps.setString(3, personaId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_roster lineup write failed for {}/{}", characterId, personaId, failure);
        }
    }

    /** A read the database could not answer: a failure, never to be taken for "there is none". */
    public static final class ReadFailed extends RuntimeException {
        public ReadFailed(String what, Throwable cause) {
            super(what, cause);
        }
    }

    /**
     * One companion's settings_json (P3b), or null when it has none or there is no such row. Never reads its state.
     *
     * @throws ReadFailed when the read failed (not the same as no settings: the caller must not keep the defaults)
     */
    public String settings(int characterId, String personaId) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT settings_json FROM living_roster WHERE character_id = ? AND persona_id = ?")) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException failure) {
            log.warn("living_roster settings read failed for {}/{}", characterId, personaId, failure);
            throw new ReadFailed("living_roster settings of " + characterId + "/" + personaId, failure);
        }
    }

    /**
     * Saves one companion's settings_json: an UPDATE that never touches its state (a row that is gone stays gone).
     * True when its row took it; false when there is no such row or the write failed.
     */
    public boolean saveSettings(int characterId, String personaId, String json) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "UPDATE living_roster SET settings_json = ? WHERE character_id = ? AND persona_id = ?")) {
            ps.setString(1, json);
            ps.setInt(2, characterId);
            ps.setString(3, personaId);
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) {
            log.warn("living_roster settings write failed for {}/{}", characterId, personaId, failure);
            return false;
        }
    }

    /**
     * Adds {@code meso} to the purse of a character nothing has loaded (a benched companion's), as its next load reads
     * it, on {@code con}: the economy transaction that takes the meso from its giver
     * (EconomyTransactionContext.enlist), so both purses commit together or not at all. A loaded character saves its
     * own purse over this, so it is only for one that is not loaded.
     *
     * @throws SQLException when there is no such character or the purse would go past its limit
     */
    public void addCharacterMeso(Connection con, int characterId, int meso) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE characters SET meso = meso + ? WHERE id = ? AND meso + ? <= ?")) {
            ps.setInt(1, meso);
            ps.setInt(2, characterId);
            ps.setLong(3, meso);
            ps.setLong(4, Integer.MAX_VALUE);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("character " + characterId + " cannot take " + meso + " meso");
            }
        }
    }

    // ---- named lineups (P9) ------------------------------------------------------------------

    /** The places a named lineup keeps (the usual lineup's, living_roster.lineup_slot, are as many). */
    public static final int NAMED_LINEUP_PLACES = 5;

    /**
     * An owner's named lineups (companion_named_lineup, changeset 46): lineup name -> persona ids in place order; a
     * lineup never saved is absent.
     *
     * @throws ReadFailed when the read failed
     */
    public Map<String, List<String>> namedLineups(int ownerId) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT lineup, persona_id FROM companion_named_lineup WHERE owner_character_id = ? ORDER BY lineup, slot")) {
            ps.setInt(1, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), name -> new ArrayList<>()).add(rs.getString(2));
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("companion_named_lineup read failed for {}", ownerId, failure);
            throw new ReadFailed("companion_named_lineup of " + ownerId, failure);
        }
    }

    /**
     * Replaces one of an owner's named lineups with {@code personaIds} (places 1.. in order, the first
     * {@link #NAMED_LINEUP_PLACES}; empty clears it), in one transaction; false when the write failed.
     */
    public boolean saveNamedLineup(int ownerId, String lineup, List<String> personaIds) {
        try (Connection con = DatabaseConnection.getConnection()) {
            boolean autoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (PreparedStatement clear = con.prepareStatement(
                    "DELETE FROM companion_named_lineup WHERE owner_character_id = ? AND lineup = ?");
                 PreparedStatement insert = con.prepareStatement(
                         "INSERT INTO companion_named_lineup (owner_character_id, lineup, slot, persona_id) VALUES (?, ?, ?, ?)")) {
                clear.setInt(1, ownerId);
                clear.setString(2, lineup);
                clear.executeUpdate();
                int slot = 0;
                for (String personaId : personaIds) {
                    if (slot >= NAMED_LINEUP_PLACES) {
                        break;
                    }
                    insert.setInt(1, ownerId);
                    insert.setString(2, lineup);
                    insert.setInt(3, ++slot);
                    insert.setString(4, personaId);
                    insert.addBatch();
                }
                insert.executeBatch();
                con.commit();
                return true;
            } catch (SQLException failure) {
                con.rollback();
                throw failure;
            } finally {
                con.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            log.warn("companion_named_lineup write failed for {}/{}", ownerId, lineup, failure);
            return false;
        }
    }

    // ---- crews (P3b) -------------------------------------------------------------------------

    /**
     * The formation an owner set for their whole crew ("SPREAD 80 0": type, px, snap range), or null when they never
     * set one.
     *
     * @throws ReadFailed when the read failed
     */
    public String crewFormation(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT formation FROM living_crew WHERE character_id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException failure) {
            log.warn("living_crew read failed for {}", characterId, failure);
            throw new ReadFailed("living_crew of " + characterId, failure);
        }
    }

    /** Keeps the formation an owner set for their whole crew; false when the write failed. */
    public boolean saveCrewFormation(int characterId, String formation) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "INSERT INTO living_crew (character_id, formation) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE formation = VALUES(formation)")) {
            ps.setInt(1, characterId);
            ps.setString(2, formation);
            ps.executeUpdate();
            return true;
        } catch (SQLException failure) {
            log.warn("living_crew write failed for {}", characterId, failure);
            return false;
        }
    }

    /**
     * The owners who turned on "Keep my crew fresh" (living_crew.keep_fresh, changeset 43, P5).
     *
     * @throws ReadFailed when the read failed
     */
    public Set<Integer> crewsKeptFresh() {
        Set<Integer> out = new LinkedHashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT character_id FROM living_crew WHERE keep_fresh = 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getInt(1));
            }
            return out;
        } catch (SQLException failure) {
            log.warn("living_crew keep_fresh scan failed", failure);
            throw new ReadFailed("living_crew keep_fresh", failure);
        }
    }

    /** Keeps whether an owner wants their crew kept fresh; false when the write failed. */
    public boolean saveCrewKeepFresh(int characterId, boolean keepFresh) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "INSERT INTO living_crew (character_id, keep_fresh) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE keep_fresh = VALUES(keep_fresh)")) {
            ps.setInt(1, characterId);
            ps.setBoolean(2, keepFresh);
            ps.executeUpdate();
            return true;
        } catch (SQLException failure) {
            log.warn("living_crew keep_fresh write failed for {}", characterId, failure);
            return false;
        }
    }

    // ---- bench yields (P5) --------------------------------------------------------------------

    /**
     * One companion_yield row (changeset 42): what live bench slices of one job branch and level band measured at one
     * camp, per hour. {@code samples} counts full live slices (one cut short counts for the share it ran).
     */
    public record YieldRow(String jobBranch, int levelBand, int campMapId, double samples, double expPerHour,
                           double mesoPerHour, double itemsPerHour, double deathsPerHour) { }

    /**
     * Every companion_yield row (one per job branch, level band and camp: a small table).
     *
     * @throws ReadFailed when the read failed (not the same as nothing measured: the model must not start over)
     */
    public List<YieldRow> companionYields() {
        List<YieldRow> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT job_branch, level_band, camp_map_id, samples, exp_per_hour,"
                     + " meso_per_hour, items_per_hour, deaths_per_hour FROM companion_yield");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new YieldRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getDouble(4), rs.getDouble(5),
                        rs.getDouble(6), rs.getDouble(7), rs.getDouble(8)));
            }
            return out;
        } catch (SQLException failure) {
            log.warn("companion_yield read failed", failure);
            throw new ReadFailed("companion_yield", failure);
        }
    }

    /**
     * The rows on "Your call" (living_roster.your_call, changeset 42, P5): owner character id to the persona ids whose
     * companion picks its own duty and camp before each bench slice.
     *
     * @throws ReadFailed when the read failed
     */
    public Map<Integer, Set<String>> yourCalls() {
        Map<Integer, Set<String>> out = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT character_id, persona_id FROM living_roster WHERE your_call = 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getInt(1), owner -> new LinkedHashSet<>()).add(rs.getString(2));
            }
            return out;
        } catch (SQLException failure) {
            log.warn("living_roster your_call scan failed", failure);
            throw new ReadFailed("living_roster your_call", failure);
        }
    }

    /** Turns "Your call" on or off for one row (it never touches the row's state); false when the write failed. */
    public boolean saveYourCall(int characterId, String personaId, boolean on) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "UPDATE living_roster SET your_call = ? WHERE character_id = ? AND persona_id = ?")) {
            ps.setBoolean(1, on);
            ps.setInt(2, characterId);
            ps.setString(3, personaId);
            ps.executeUpdate();
            return true;
        } catch (SQLException failure) {
            log.warn("living_roster your_call write failed for {}/{}", characterId, personaId, failure);
            return false;
        }
    }

    /**
     * What a live bench stall holds of its haul (living_stall_stock, changeset 42, P5): item id to the units moved from
     * the haul into its companion's bag or shop and not yet sold or handed back. Kept so a crash mid-stall never loses
     * them: the companion's next load moves them back to the haul (BenchStallForm).
     *
     * @throws ReadFailed when the read failed (not the same as nothing held)
     */
    public Map<Integer, Integer> stallStock(int characterId, String personaId) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT item_id, quantity FROM living_stall_stock WHERE character_id = ? AND persona_id = ? AND quantity > 0"
                        + " ORDER BY item_id")) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), rs.getInt(2));
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("living_stall_stock read failed for {}/{}", characterId, personaId, failure);
            throw new ReadFailed("living_stall_stock", failure);
        }
    }

    /** Keeps what a live stall holds of the haul now (replacing what was kept; empty clears it); false when it failed. */
    public boolean saveStallStock(int characterId, String personaId, Map<Integer, Integer> units) {
        try (Connection con = DatabaseConnection.getConnection()) {
            boolean autoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (PreparedStatement clear = con.prepareStatement(
                    "DELETE FROM living_stall_stock WHERE character_id = ? AND persona_id = ?");
                 PreparedStatement insert = con.prepareStatement(
                         "INSERT INTO living_stall_stock (character_id, persona_id, item_id, quantity) VALUES (?, ?, ?, ?)")) {
                clear.setInt(1, characterId);
                clear.setString(2, personaId);
                clear.executeUpdate();
                for (Map.Entry<Integer, Integer> unit : units.entrySet()) {
                    if (unit.getValue() > 0) {
                        insert.setInt(1, characterId);
                        insert.setString(2, personaId);
                        insert.setInt(3, unit.getKey());
                        insert.setInt(4, unit.getValue());
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
                con.commit();
                return true;
            } catch (SQLException failure) {
                con.rollback();
                throw failure;
            } finally {
                con.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            log.warn("living_stall_stock write failed for {}/{}", characterId, personaId, failure);
            return false;
        }
    }

    /**
     * What a rewrite of a saved inventory puts on once its drop is decided ({@link #dropStoredItems}):
     * {@code takenWorn} the worn items it takes, {@code worn} what the character still wears and {@code carried} its
     * Equip tab. It may move a carried item onto an empty worn slot by setting its position (below 0); it never adds or
     * removes one.
     */
    @FunctionalInterface
    public interface Redress {
        void apply(List<Item> takenWorn, List<Item> worn, List<Item> carried);
    }

    /**
     * Takes the items {@code drop} picks out of the saved inventory of a character nothing has loaded (a released
     * companion's boss gear, CompanionGearTool): its items are read and, when {@code drop} picks any,
     * {@code redress} may put carried pieces on the slots they left, and it is written back without them in one
     * transaction, the way the game saves an inventory (ItemFactory). A loaded character saves its own inventory over
     * this, so it is only for one that is not loaded; the caller holds the maintenance lock that offline loads take.
     * Returns how many items were taken (0: nothing was written), or -1 when the read or the write failed (nothing
     * changed).
     */
    public int dropStoredItems(int characterId, Predicate<Item> drop, Redress redress) {
        try {
            List<Pair<Item, InventoryType>> items = ItemFactory.INVENTORY.loadItems(characterId, false);
            List<Pair<Item, InventoryType>> kept = new ArrayList<>();
            List<Item> takenWorn = new ArrayList<>();
            for (Pair<Item, InventoryType> pair : items) {
                if (!drop.test(pair.getLeft())) {
                    kept.add(pair);
                } else if (pair.getRight() == InventoryType.EQUIPPED) {
                    takenWorn.add(pair.getLeft());
                }
            }
            int dropped = items.size() - kept.size();
            if (dropped == 0) {
                return 0;
            }
            redress.apply(takenWorn, tab(kept, InventoryType.EQUIPPED), tab(kept, InventoryType.EQUIP));
            List<Pair<Item, InventoryType>> written = kept.stream().map(pair -> pair.getRight() == InventoryType.EQUIP
                    && pair.getLeft().getPosition() < 0 ? new Pair<>(pair.getLeft(), InventoryType.EQUIPPED) : pair)
                    .toList();
            try (Connection con = DatabaseConnection.getConnection()) {
                boolean autoCommit = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    ItemFactory.INVENTORY.saveItems(written, characterId, con);
                    con.commit();
                } catch (SQLException | RuntimeException failure) {
                    con.rollback();
                    throw failure;
                } finally {
                    con.setAutoCommit(autoCommit);
                }
            }
            return dropped;
        } catch (SQLException | RuntimeException failure) {
            log.warn("inventory rewrite failed for character {}", characterId, failure);
            return -1;
        }
    }

    private static List<Item> tab(List<Pair<Item, InventoryType>> items, InventoryType type) {
        return items.stream().filter(pair -> pair.getRight() == type).map(Pair::getLeft).toList();
    }

    /**
     * What a character can wear by, from its characters row (a released companion's, CompanionGearTool): its job id,
     * level, gender (0 male, 1 female), base STR, DEX, INT and LUK, and fame.
     */
    public record WearStats(int jobId, int level, int gender, int str, int dex, int int_, int luk, int fame) { }

    /** {@link WearStats} of any character (online or not) from the characters table; null if unknown or unread. */
    public WearStats wearStats(int characterId) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT job, level, gender, str, dex, `int`, luk, fame FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new WearStats(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8)) : null;
            }
        } catch (SQLException failure) {
            log.warn("character wear stats lookup failed for {}", characterId, failure);
            return null;
        }
    }

    /**
     * The level of each skill a character holds, by skill id, from its skills rows (what weapon a released companion's
     * build fights with, CompanionGearTool); empty for none, null when the read fails.
     */
    public Map<Integer, Integer> skillLevels(int characterId) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT skillid, skilllevel FROM skills WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            Map<Integer, Integer> levels = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    levels.put(rs.getInt(1), rs.getInt(2));
                }
            }
            return levels;
        } catch (SQLException failure) {
            log.warn("character skill levels lookup failed for {}", characterId, failure);
            return null;
        }
    }

    /**
     * A character's stored level as a released companion's level restore reads it (ReleasedLevelRestore): from its
     * characters row the level, job, EXP, base STR/DEX/INT/LUK, unspent AP, unspent SP of each skill book, the AP it put
     * into HP/MP, its GM level and its Equip tab size; its skill rows; the items it wears (a position below 0) and the
     * ones in its Equip tab.
     */
    public record StoredLevel(int level, int jobId, int exp, int str, int dex, int intel, int luk, int ap, int[] sp,
                              int hpMpUsed, int gmLevel, int equipSlots, List<StoredSkill> skills, List<Item> worn,
                              List<Item> carried) { }

    /** One skills row: the skill, its level, its master level and its expiration (-1: none). */
    public record StoredSkill(int skillId, int level, int masterLevel, long expiration) { }

    /**
     * What a stored level rewrite writes: the characters row's level, job, EXP, base stats, unspent AP and SP, HP/MP and
     * their maximums; every skill row (a row not listed is deleted); and each worn piece that comes off, its worn
     * position to the Equip tab position it goes to.
     */
    public record LevelRewrite(int level, int jobId, int exp, int str, int dex, int intel, int luk, int ap, int[] sp,
                               int hp, int mp, int maxHp, int maxMp, List<StoredSkill> skills,
                               Map<Short, Short> unequip) { }

    /**
     * Rewrites the stored level of a character nothing has loaded (a released companion's GM level hold,
     * ReleasedLevelRestore) in one transaction: its {@link StoredLevel} is read, {@code plan} decides the
     * {@link LevelRewrite} (null: nothing to write) and its characters row, its skill rows and, when pieces come off, its
     * inventory (ItemFactory, as {@link #dropStoredItems}) are written. A loaded character saves its own over this, so it
     * is only for one that is not loaded; the caller holds the maintenance lock that offline loads take. Returns 1 when
     * written, 0 when there was nothing to write (no characters row, or {@code plan} said null), -1 when the read or the
     * write failed (nothing changed).
     */
    public int rewriteStoredLevel(int characterId, java.util.function.Function<StoredLevel, LevelRewrite> plan) {
        try {
            List<Pair<Item, InventoryType>> items = ItemFactory.INVENTORY.loadItems(characterId, false);
            try (Connection con = DatabaseConnection.getConnection()) {
                boolean autoCommit = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    StoredLevel stored = storedLevel(con, characterId, items);
                    LevelRewrite rewrite = stored == null ? null : plan.apply(stored);
                    if (rewrite == null) {
                        con.rollback();
                        return 0;
                    }
                    writeLevel(con, characterId, rewrite);
                    writeSkills(con, characterId, rewrite.skills());
                    if (!rewrite.unequip().isEmpty()) {
                        ItemFactory.INVENTORY.saveItems(unequipped(items, rewrite.unequip()), characterId, con);
                    }
                    con.commit();
                    return 1;
                } catch (SQLException | RuntimeException failure) {
                    con.rollback();
                    throw failure;
                } finally {
                    con.setAutoCommit(autoCommit);
                }
            }
        } catch (SQLException | RuntimeException failure) {
            log.warn("stored level rewrite failed for character {}", characterId, failure);
            return -1;
        }
    }

    private static StoredLevel storedLevel(Connection con, int characterId, List<Pair<Item, InventoryType>> items)
            throws SQLException {
        StoredLevel row;
        try (PreparedStatement ps = con.prepareStatement("SELECT level, job, exp, str, dex, `int`, luk, ap, sp, hpMpUsed,"
                + " gm, equipslots FROM characters WHERE id = ? FOR UPDATE")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                row = new StoredLevel(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                        rs.getInt(7), rs.getInt(8), skillPoints(rs.getString(9)), rs.getInt(10), rs.getInt(11),
                        rs.getInt(12), List.of(), tab(items, InventoryType.EQUIPPED), tab(items, InventoryType.EQUIP));
            }
        }
        List<StoredSkill> skills = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT skillid, skilllevel, masterlevel, expiration FROM skills WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    skills.add(new StoredSkill(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)));
                }
            }
        }
        return new StoredLevel(row.level(), row.jobId(), row.exp(), row.str(), row.dex(), row.intel(), row.luk(),
                row.ap(), row.sp(), row.hpMpUsed(), row.gmLevel(), row.equipSlots(), List.copyOf(skills), row.worn(),
                row.carried());
    }

    /** The sp column ("3,0,0,0,0,0,0,0,0,0", one per skill book) as Character.loadCharSkillPoints reads it. */
    private static int[] skillPoints(String column) {
        String[] parts = column == null || column.isBlank() ? new String[0] : column.split(",");
        int[] points = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            points[i] = Integer.parseInt(parts[i].trim());
        }
        return points;
    }

    private static void writeLevel(Connection con, int characterId, LevelRewrite rewrite) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET level = ?, job = ?, exp = ?, str = ?,"
                + " dex = ?, `int` = ?, luk = ?, ap = ?, sp = ?, hp = ?, mp = ?, maxhp = ?, maxmp = ? WHERE id = ?")) {
            ps.setInt(1, rewrite.level());
            ps.setInt(2, rewrite.jobId());
            ps.setInt(3, rewrite.exp());
            ps.setInt(4, rewrite.str());
            ps.setInt(5, rewrite.dex());
            ps.setInt(6, rewrite.intel());
            ps.setInt(7, rewrite.luk());
            ps.setInt(8, rewrite.ap());
            StringBuilder sp = new StringBuilder();
            for (int points : rewrite.sp()) {
                sp.append(sp.isEmpty() ? "" : ",").append(points);
            }
            ps.setString(9, sp.toString());
            ps.setInt(10, rewrite.hp());
            ps.setInt(11, rewrite.mp());
            ps.setInt(12, rewrite.maxHp());
            ps.setInt(13, rewrite.maxMp());
            ps.setInt(14, characterId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("character " + characterId + " is gone");
            }
        }
    }

    /** Every skill row as listed, the way the game saves skills (Character.saveCharToDB): the old rows go first. */
    private static void writeSkills(Connection con, int characterId, List<StoredSkill> skills) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM skills WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
        }
        if (skills.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO skills (characterid, skillid, skilllevel,"
                + " masterlevel, expiration) VALUES (?, ?, ?, ?, ?)")) {
            for (StoredSkill skill : skills) {
                ps.setInt(1, characterId);
                ps.setInt(2, skill.skillId());
                ps.setInt(3, skill.level());
                ps.setInt(4, skill.masterLevel());
                ps.setLong(5, skill.expiration());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** The inventory with each worn piece {@code unequip} names in the Equip tab at its new position. */
    private static List<Pair<Item, InventoryType>> unequipped(List<Pair<Item, InventoryType>> items,
                                                              Map<Short, Short> unequip) {
        List<Pair<Item, InventoryType>> out = new ArrayList<>();
        for (Pair<Item, InventoryType> pair : items) {
            Short to = pair.getRight() == InventoryType.EQUIPPED ? unequip.get(pair.getLeft().getPosition()) : null;
            if (to == null) {
                out.add(pair);
            } else {
                pair.getLeft().setPosition(to);
                out.add(new Pair<>(pair.getLeft(), InventoryType.EQUIP));
            }
        }
        return out;
    }

    /**
     * The item ids in the USE tab a character nothing has loaded carries (its saved inventory, quantity above 0), for a
     * bench companion's potions (CrewRotation).
     *
     * @throws ReadFailed when the read failed
     */
    public Set<Integer> usableItems(int characterId) {
        Set<Integer> out = new LinkedHashSet<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT DISTINCT itemid FROM inventoryitems WHERE characterid = ? AND type = 1 AND inventorytype = 2"
                        + " AND quantity > 0")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("inventory read failed for character {}", characterId, failure);
            throw new ReadFailed("inventoryitems", failure);
        }
    }

    /**
     * True when a character nothing has loaded holds item {@code itemId} anywhere in its saved inventory (quantity above
     * 0): a bench ally's Crimsonwood Keystone (CompanionKeystoneGate).
     *
     * @throws ReadFailed when the read failed
     */
    public boolean holdsItem(int characterId, int itemId) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM inventoryitems WHERE characterid = ? AND type = 1 AND itemid = ? AND quantity > 0"
                        + " LIMIT 1")) {
            ps.setInt(1, characterId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException failure) {
            log.warn("inventory read failed for character {}", characterId, failure);
            throw new ReadFailed("inventoryitems", failure);
        }
    }

    /** Keeps one companion_yield row (an upsert); false when the write failed. */
    public boolean saveCompanionYield(YieldRow row) {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "INSERT INTO companion_yield (job_branch, level_band, camp_map_id, samples, exp_per_hour, meso_per_hour,"
                        + " items_per_hour, deaths_per_hour) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE"
                        + " samples = VALUES(samples), exp_per_hour = VALUES(exp_per_hour), meso_per_hour = VALUES(meso_per_hour),"
                        + " items_per_hour = VALUES(items_per_hour), deaths_per_hour = VALUES(deaths_per_hour)")) {
            ps.setString(1, row.jobBranch());
            ps.setInt(2, row.levelBand());
            ps.setInt(3, row.campMapId());
            ps.setDouble(4, row.samples());
            ps.setDouble(5, row.expPerHour());
            ps.setDouble(6, row.mesoPerHour());
            ps.setDouble(7, row.itemsPerHour());
            ps.setDouble(8, row.deathsPerHour());
            ps.executeUpdate();
            return true;
        } catch (SQLException failure) {
            log.warn("companion_yield write failed for {}/{}/{}", row.jobBranch(), row.levelBand(), row.campMapId(), failure);
            return false;
        }
    }

    // ---- companion runs (P6) ------------------------------------------------------------------

    /**
     * One companion_performance row (changeset 40): how one companion's party-quest run went. {@code outcome} is
     * cleared, failed or tapped_out; its damage share is not measured (NULL).
     */
    public record CompanionRunRow(int ownerCharacterId, int companionCharacterId, String personaId, String activity,
                                  String outcome, int durationS, int deaths, int partySize) { }

    /** A companion's record in one activity: its runs, and how many of them cleared. */
    public record CompanionRunStats(int runs, int clears) { }

    /** Adds one companion_performance row (recorded_at is the database's now); false when the write failed. */
    public boolean saveCompanionRun(CompanionRunRow row) {
        String sql = SqlTime.utc("INSERT INTO companion_performance (owner_character_id, companion_character_id, persona_id,"
                + " activity, outcome, duration_s, deaths, damage_share, party_size) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, row.ownerCharacterId());
            ps.setInt(2, row.companionCharacterId());
            ps.setString(3, row.personaId());
            ps.setString(4, row.activity());
            ps.setString(5, row.outcome());
            ps.setInt(6, row.durationS());
            ps.setInt(7, row.deaths());
            ps.setInt(8, row.partySize());
            ps.executeUpdate();
            return true;
        } catch (SQLException failure) {
            log.warn("companion_performance write failed for {}", row.companionCharacterId(), failure);
            return false;
        }
    }

    /**
     * Every companion's record in {@code activity} ("kpq"), by companion character id.
     *
     * @throws ReadFailed when the read failed (not the same as no runs yet)
     */
    public Map<Integer, CompanionRunStats> companionRunStats(String activity) {
        Map<Integer, CompanionRunStats> out = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT companion_character_id, COUNT(*), SUM(outcome = 'cleared') FROM companion_performance"
                        + " WHERE activity = ? GROUP BY companion_character_id")) {
            ps.setString(1, activity);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), new CompanionRunStats(rs.getInt(2), rs.getInt(3)));
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("companion_performance read failed for {}", activity, failure);
            throw new ReadFailed("companion_performance " + activity, failure);
        }
    }

    // ---- PQ clear-time records ------------------------------------------------------------------

    /**
     * One pq_run_pace row (changeset 44): how one companion party-quest run went against the entry NPC's estimate at
     * its Yes. {@code outcome} is cleared, failed or rerolled (its leader left stage 1 to run it again); the est_*
     * fields are null when no estimate was made, and {@code actualS} is null unless the run cleared. {@code spans},
     * {@code members} and {@code drawn} are JSON (PqPaceLedger writes and reads them); {@code recordedAtMs} is kept in
     * UTC. {@code id} is 0 until written.
     */
    public record PqRunPaceRow(long id, String pqKey, String sessionId, int leaderId, int partySize, int humans,
                               int epoch, String outcome, Integer estP10S, Integer estP50S, Integer estP90S,
                               Integer estRecords, Integer actualS, long startedAtMs, long clearedAtMs, String spans,
                               String members, String drawn, long recordedAtMs) { }

    private static final String PQ_RUN_PACE_COLUMNS = "pq_key, session_id, leader_character_id, party_size, humans, epoch,"
            + " outcome, est_p10_s, est_p50_s, est_p90_s, est_records, actual_s, started_at_ms, cleared_at_ms, spans,"
            + " members, drawn, recorded_at";

    /** Adds one pq_run_pace row (recorded_at bound in UTC); false when the write failed. */
    public boolean savePqRunPace(PqRunPaceRow row) {
        String sql = SqlTime.utc("INSERT INTO pq_run_pace (" + PQ_RUN_PACE_COLUMNS + ")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, row.pqKey());
            ps.setString(2, row.sessionId());
            ps.setInt(3, row.leaderId());
            ps.setInt(4, row.partySize());
            ps.setInt(5, row.humans());
            ps.setInt(6, row.epoch());
            ps.setString(7, row.outcome());
            setNullableInt(ps, 8, row.estP10S());
            setNullableInt(ps, 9, row.estP50S());
            setNullableInt(ps, 10, row.estP90S());
            setNullableInt(ps, 11, row.estRecords());
            setNullableInt(ps, 12, row.actualS());
            ps.setLong(13, row.startedAtMs());
            ps.setLong(14, row.clearedAtMs());
            ps.setString(15, row.spans());
            ps.setString(16, row.members());
            ps.setString(17, row.drawn());
            SqlTime.set(ps, 18, row.recordedAtMs());
            ps.executeUpdate();
            return true;
        } catch (SQLException failure) {
            log.warn("pq_run_pace write failed for {}", row.sessionId(), failure);
            return false;
        }
    }

    /**
     * The newest {@code limit} pq_run_pace rows of one quest recorded under {@code epoch}, newest first.
     *
     * @throws ReadFailed when the read failed (not the same as no runs yet)
     */
    public List<PqRunPaceRow> pqRunPaces(String pqKey, int epoch, int limit) {
        List<PqRunPaceRow> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT id, " + PQ_RUN_PACE_COLUMNS + " FROM pq_run_pace WHERE pq_key = ? AND epoch = ?"
                + " ORDER BY id DESC LIMIT ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, pqKey);
            ps.setInt(2, epoch);
            ps.setInt(3, Math.max(0, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PqRunPaceRow(rs.getLong("id"), rs.getString("pq_key"), rs.getString("session_id"),
                            rs.getInt("leader_character_id"), rs.getInt("party_size"), rs.getInt("humans"),
                            rs.getInt("epoch"), rs.getString("outcome"), nullableInt(rs, "est_p10_s"),
                            nullableInt(rs, "est_p50_s"), nullableInt(rs, "est_p90_s"), nullableInt(rs, "est_records"),
                            nullableInt(rs, "actual_s"), rs.getLong("started_at_ms"), rs.getLong("cleared_at_ms"),
                            rs.getString("spans"), rs.getString("members"), rs.getString("drawn"),
                            SqlTime.get(rs, "recorded_at")));
                }
            }
            return out;
        } catch (SQLException failure) {
            log.warn("pq_run_pace read failed for {} epoch {}", pqKey, epoch, failure);
            throw new ReadFailed("pq_run_pace " + pqKey, failure);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** The meso in the purse of a character nothing has loaded (a benched companion's); -1 when unknown. */
    public long characterMeso(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT meso FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        } catch (SQLException failure) {
            log.warn("meso lookup failed for character {}", characterId, failure);
            return -1L;
        }
    }

    public void deleteRoster(int characterId, String personaId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM living_roster WHERE character_id = ? AND persona_id = ?")) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_roster delete failed for {}/{}", characterId, personaId, failure);
        }
    }

    private static RosterEntry readRoster(ResultSet rs) throws SQLException {
        return new RosterEntry(rs.getInt("character_id"), rs.getString("persona_id"),
                RosterState.valueOf(rs.getString("state")), Assignment.valueOf(rs.getString("assignment")),
                rs.getInt("assignment_item"), rs.getInt("assignment_count"), rs.getInt("camp_map_id"),
                rs.getLong("accrued_exp"), rs.getLong("haul_meso"), SqlTime.get(rs, "wages_paid_until"),
                SqlTime.get(rs, "recruited_at"), SqlTime.get(rs, "benched_at"),
                SqlTime.get(rs, "last_ledger_at"), rs.getInt("farm_seconds_today"), rs.getString("farm_day"),
                SqlTime.get(rs, "eta_at"), rs.getInt("hold_map_id"), SqlTime.get(rs, "parked_at"));
    }

    // ---- haul ----------------------------------------------------------------------------

    public List<HaulItem> haul(int characterId, String personaId) {
        List<HaulItem> out = new ArrayList<>();
        String sql = "SELECT item_id, quantity FROM living_haul_item WHERE character_id = ? AND persona_id = ? AND quantity > 0 ORDER BY item_id";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HaulItem(rs.getInt(1), rs.getInt(2)));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_haul_item scan failed for {}/{}", characterId, personaId, failure);
        }
        return out;
    }

    public void addHaul(int characterId, String personaId, int itemId, int quantity) {
        String sql = "INSERT INTO living_haul_item (character_id, persona_id, item_id, quantity) VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            ps.setInt(3, itemId);
            ps.setInt(4, quantity);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_haul_item write failed for {}/{}", characterId, personaId, failure);
        }
    }

    public void removeHaul(int characterId, String personaId, int itemId, int quantity) {
        String sql = "UPDATE living_haul_item SET quantity = GREATEST(0, quantity - ?) WHERE character_id = ? AND persona_id = ? AND item_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, quantity);
            ps.setInt(2, characterId);
            ps.setString(3, personaId);
            ps.setInt(4, itemId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_haul_item update failed for {}/{}", characterId, personaId, failure);
        }
    }

    public void clearHaul(int characterId, String personaId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM living_haul_item WHERE character_id = ? AND persona_id = ?")) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_haul_item clear failed for {}/{}", characterId, personaId, failure);
        }
    }

    public void recordTransfer(int characterId, String personaId, int itemId, int quantity) {
        String sql = "INSERT INTO living_haul_transfer (character_id, persona_id, item_id, quantity) VALUES (?, ?, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            ps.setInt(3, itemId);
            ps.setInt(4, quantity);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_haul_transfer write failed for {}/{}", characterId, personaId, failure);
        }
    }

    /** Units of an item this persona handed to the player since {@code sinceMs}. */
    public int transferredSince(int characterId, String personaId, int itemId, long sinceMs) {
        String sql = SqlTime.utc("SELECT COALESCE(SUM(quantity), 0) FROM living_haul_transfer WHERE character_id = ? AND persona_id = ?"
                + " AND item_id = ? AND transferred_at >= ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            ps.setInt(3, itemId);
            SqlTime.set(ps, 4, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            log.warn("living_haul_transfer sum failed for {}/{}", characterId, personaId, failure);
            return 0;
        }
    }

    // ---- learned phrases -----------------------------------------------------------------

    public List<LearnedPhrase> learnedPhrases() {
        List<LearnedPhrase> out = new ArrayList<>();
        String sql = SqlTime.utc("SELECT normalized_text, canonical, hits, source, approved, first_seen, last_seen FROM living_learned_phrase");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new LearnedPhrase(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getBoolean(5), SqlTime.get(rs, 6), SqlTime.get(rs, 7)));
            }
        } catch (SQLException failure) {
            log.warn("living_learned_phrase scan failed", failure);
        }
        return out;
    }

    public void learnPhrase(String normalizedText, String canonical, String source) {
        String sql = "INSERT INTO living_learned_phrase (normalized_text, canonical, hits, source) VALUES (?, ?, 1, ?)"
                + " ON DUPLICATE KEY UPDATE hits = hits + 1, last_seen = CURRENT_TIMESTAMP";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, normalizedText);
            ps.setString(2, canonical);
            ps.setString(3, source);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_learned_phrase write failed for '{}'", normalizedText, failure);
        }
    }

    public void setPhraseApproved(String normalizedText, boolean approved) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE living_learned_phrase SET approved = ? WHERE normalized_text = ?")) {
            ps.setBoolean(1, approved);
            ps.setString(2, normalizedText);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_learned_phrase approve failed for '{}'", normalizedText, failure);
        }
    }

    public void deletePhrase(String normalizedText) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM living_learned_phrase WHERE normalized_text = ?")) {
            ps.setString(1, normalizedText);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_learned_phrase delete failed for '{}'", normalizedText, failure);
        }
    }

    public int expirePhrases(int minHits, int ttlDays) {
        String sql = "DELETE FROM living_learned_phrase WHERE approved = 0 AND hits < ? AND last_seen < (NOW() - INTERVAL ? DAY)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, minHits);
            ps.setInt(2, ttlDays);
            return ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_learned_phrase expiry failed", failure);
            return 0;
        }
    }

    // ---- audit ---------------------------------------------------------------------------

    public void event(int characterId, String personaId, String kind, String detail) {
        eventChecked(characterId, personaId, kind, detail);
    }

    /** As {@link #event}; false when the row could not be written. */
    public boolean eventChecked(int characterId, String personaId, String kind, String detail) {
        String sql = "INSERT INTO living_event_log (character_id, persona_id, kind, detail) VALUES (?, ?, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            setNullableInt(ps, 1, characterId);
            ps.setString(2, personaId);
            ps.setString(3, kind);
            ps.setString(4, detail == null ? null : detail.length() > 512 ? detail.substring(0, 512) : detail);
            ps.executeUpdate();
            return true;
        } catch (SQLException failure) {
            log.warn("living_event_log write failed ({})", kind, failure);
            return false;
        }
    }

    /**
     * One living_event_log row's kind and detail; read as a GM level record ({@link #latestGmLevel}), {@code kept} is
     * true when any release of that record was a keep (a {@code gm.level.release} row with the detail {@code keep}).
     */
    public record EventRow(String kind, String detail, boolean kept) {
        public EventRow(String kind, String detail) {
            this(kind, detail, false);
        }
    }

    /**
     * The character's latest GM level event with this persona ({@code gm.level.hold} or {@code gm.level.release}, the
     * GmLevelHolds record) and whether any release of that record was a keep ({@link EventRow#kept}: a later set that
     * was not applied or was restored leaves it kept): empty when there is none, null when the read fails. The table is
     * indexed by character (idx_living_event_character), so this reads that character's audit rows and keeps the newest
     * match; GmLevelHolds caches the answer, so it runs once per character and persona a boot.
     */
    public java.util.Optional<EventRow> latestGmLevel(int characterId, String personaId) {
        String sql = "SELECT kind, detail, EXISTS (SELECT 1 FROM living_event_log k WHERE k.character_id = ?"
                + " AND k.persona_id = ? AND k.kind = 'gm.level.release' AND k.detail = 'keep')"
                + " FROM living_event_log WHERE character_id = ? AND persona_id = ?"
                + " AND kind IN ('gm.level.hold', 'gm.level.release') ORDER BY id DESC LIMIT 1";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            ps.setInt(3, characterId);
            ps.setString(4, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? java.util.Optional.of(new EventRow(rs.getString(1), rs.getString(2), rs.getBoolean(3)))
                        : java.util.Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            log.warn("living_event_log GM level read failed for {}/{}", characterId, personaId, failure);
            return null;
        }
    }

    /**
     * The personas a release's cleanup may have been cut short for (RosterService.finishReleaseCleanupAtStart): each
     * persona's character (living_persona_state, a Resident's or a hired citizen's; never a human's) that nobody
     * employs (no living_roster row of the persona but a RELEASED one) and that still has a GM level hold with that
     * persona (its latest gm.level row a hold, as {@link #latestGmLevel} reads it) or an item tagged {@code gearTag}
     * worn or in its Equip tab. One query over the persona rows, each looked up by an index (living_roster by persona,
     * inventoryitems and living_event_log by character). Persona id to character id, by persona id, at most
     * {@code limit}; null when the read fails.
     */
    public Map<String, Integer> releaseCleanupCandidates(String gearTag, int limit) {
        String sql = "SELECT p.persona_id, p.character_id FROM living_persona_state p WHERE p.character_id > 0"
                + " AND NOT EXISTS (SELECT 1 FROM living_roster r WHERE r.persona_id = p.persona_id"
                + " AND r.state <> 'RELEASED')"
                + " AND (EXISTS (SELECT 1 FROM inventoryitems i WHERE i.characterid = p.character_id AND i.type = ?"
                + " AND i.inventorytype IN (?, ?) AND i.owner = ?)"
                + " OR (SELECT e.kind FROM living_event_log e WHERE e.character_id = p.character_id"
                + " AND e.persona_id = p.persona_id AND e.kind IN ('gm.level.hold', 'gm.level.release')"
                + " ORDER BY e.id DESC LIMIT 1) = 'gm.level.hold')"
                + " ORDER BY p.persona_id LIMIT ?";
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, ItemFactory.INVENTORY.getValue());
            ps.setInt(2, InventoryType.EQUIPPED.getType());
            ps.setInt(3, InventoryType.EQUIP.getType());
            ps.setString(4, gearTag);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getInt(2));
                }
            }
            return out;
        } catch (SQLException | RuntimeException failure) {
            log.warn("release cleanup candidates read failed", failure);
            return null;
        }
    }

    /**
     * The character's newest event with this persona of one of these {@code kinds}, as its kind and detail (a
     * companion's boss-gear record, BossGearRecord): empty when there is none, null when the read fails. Reads through
     * the character index (idx_living_event_character).
     */
    public java.util.Optional<EventRow> latestEvent(int characterId, String personaId, List<String> kinds) {
        if (kinds.isEmpty()) {
            return java.util.Optional.empty();
        }
        String sql = "SELECT kind, detail FROM living_event_log WHERE character_id = ? AND persona_id = ? AND kind IN ("
                + String.join(",", java.util.Collections.nCopies(kinds.size(), "?")) + ") ORDER BY id DESC LIMIT 1";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            int at = 1;
            ps.setInt(at++, characterId);
            ps.setString(at++, personaId);
            for (String kind : kinds) {
                ps.setString(at++, kind);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? java.util.Optional.of(new EventRow(rs.getString(1), rs.getString(2)))
                        : java.util.Optional.empty();
            }
        } catch (SQLException failure) {
            log.warn("living_event_log latest read failed for {}/{} {}", characterId, personaId, kinds, failure);
            return null;
        }
    }

    /** One living_event_log row: the character it was logged under (0 for none), its kind and its detail. */
    public record LoggedEvent(int characterId, String kind, String detail) {
    }

    /**
     * The persona's newest event of one of these {@code kinds} under any character, with the character it was logged
     * under (a released companion's boss-gear record at start, when who let it go is no longer known:
     * BossGearRecord.readByPersona): empty when there is none, null when the read fails. No index covers the persona,
     * so it reads the log back from the newest row until one matches: only the start's cleanup of a release a stop cut
     * short calls it, for a persona whose boss gear comes off.
     */
    public java.util.Optional<LoggedEvent> latestPersonaEvent(String personaId, List<String> kinds) {
        if (kinds.isEmpty()) {
            return java.util.Optional.empty();
        }
        String sql = "SELECT character_id, kind, detail FROM living_event_log WHERE persona_id = ? AND kind IN ("
                + String.join(",", java.util.Collections.nCopies(kinds.size(), "?")) + ") ORDER BY id DESC LIMIT 1";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            int at = 1;
            ps.setString(at++, personaId);
            for (String kind : kinds) {
                ps.setString(at++, kind);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? java.util.Optional.of(new LoggedEvent(rs.getInt(1), rs.getString(2), rs.getString(3)))
                        : java.util.Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            log.warn("living_event_log latest read failed for persona {} {}", personaId, kinds, failure);
            return null;
        }
    }

    /** The details of every {@code kind} event logged for this persona (with or without a character); null when the read fails. */
    public List<String> personaEventDetails(String personaId, String kind) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT detail FROM living_event_log WHERE persona_id = ? AND kind = ? ORDER BY id";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, personaId);
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_event_log scan failed for {} {}", personaId, kind, failure);
            return null;
        }
        return out;
    }

    /** How many {@code kind} events the character has with this persona since {@code sinceMs}; 0 when the read fails. */
    public int countEvents(int characterId, String personaId, String kind, long sinceMs) {
        String sql = SqlTime.utc("SELECT COUNT(*) FROM living_event_log WHERE character_id = ? AND persona_id = ? AND kind = ? AND at >= ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            ps.setString(3, kind);
            SqlTime.set(ps, 4, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            log.warn("living_event_log count failed for {}/{}", characterId, personaId, failure);
            return 0;
        }
    }

    /**
     * The persona ids of the character's {@code kind} events with this {@code detail} since their last {@code toldKind}
     * event, oldest first: what they have not been told yet (the Residents and hires let go while they were away).
     */
    public List<String> untoldEvents(int characterId, String kind, String detail, String toldKind) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT persona_id FROM living_event_log WHERE character_id = ? AND kind = ? AND detail = ?"
                + " AND id > COALESCE((SELECT MAX(id) FROM living_event_log WHERE character_id = ? AND kind = ?), 0) ORDER BY id";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, kind);
            ps.setString(3, detail);
            ps.setInt(4, characterId);
            ps.setString(5, toldKind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_event_log scan failed for {}", characterId, failure);
        }
        return out;
    }

    /** One living_event_log row of a character's: the persona it names (null for none), its kind and its detail. */
    public record CharacterEvent(String personaId, String kind, String detail) { }

    /**
     * The character's events of these {@code kinds} since their newest event of one of the {@code markers} (all of them
     * when there is none), oldest first, at most {@code limit}: what the letters have not told them yet
     * (CompanionLetters). Null when the read fails.
     */
    public List<CharacterEvent> eventsAfterMarker(int characterId, List<String> kinds, List<String> markers, int limit) {
        String sql = "SELECT persona_id, kind, detail FROM living_event_log WHERE character_id = ? AND kind IN ("
                + String.join(",", java.util.Collections.nCopies(kinds.size(), "?")) + ")"
                + " AND id > COALESCE((SELECT MAX(id) FROM living_event_log WHERE character_id = ? AND kind IN ("
                + String.join(",", java.util.Collections.nCopies(markers.size(), "?")) + ")), 0) ORDER BY id LIMIT ?";
        List<CharacterEvent> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            int at = 1;
            ps.setInt(at++, characterId);
            for (String kind : kinds) {
                ps.setString(at++, kind);
            }
            ps.setInt(at++, characterId);
            for (String marker : markers) {
                ps.setString(at++, marker);
            }
            ps.setInt(at, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CharacterEvent(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_event_log scan failed for {} {}", characterId, kinds, failure);
            return null;
        }
        return out;
    }

    /**
     * The last {@code limit} events of one companion of the character's of these {@code kinds}, oldest first, each as
     * {kind, detail} (its own record at its camps, BenchDutyDispatcher.campRecord). Empty when the read fails.
     */
    public List<String[]> personaEvents(int characterId, String personaId, List<String> kinds, int limit) {
        List<String[]> out = new ArrayList<>();
        if (kinds == null || kinds.isEmpty()) {
            return out;
        }
        String sql = "SELECT kind, detail FROM living_event_log WHERE character_id = ? AND persona_id = ? AND kind IN ("
                + String.join(",", java.util.Collections.nCopies(kinds.size(), "?")) + ") ORDER BY id DESC LIMIT ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            int at = 1;
            ps.setInt(at++, characterId);
            ps.setString(at++, personaId);
            for (String kind : kinds) {
                ps.setString(at++, kind);
            }
            ps.setInt(at, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        } catch (SQLException failure) {
            log.warn("living_event_log scan failed for {}/{}", characterId, personaId, failure);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public List<String> recentEvents(int characterId, int limit) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT at, persona_id, kind, detail FROM living_event_log WHERE character_id = ? ORDER BY id DESC LIMIT ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getTimestamp(1) + " " + rs.getString(2) + " " + rs.getString(3) + " " + rs.getString(4));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_event_log scan failed for {}", characterId, failure);
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------------------------

    private static void setNullableInt(PreparedStatement ps, int index, int value) throws SQLException {
        if (value == 0) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /** {@code ms} rounded to the nearest whole second, as MySQL stores it: the TIMESTAMP columns read it back unchanged. */
    public static long toWholeSecond(long ms) {
        return Math.floorDiv(ms + 500L, 1000L) * 1000L;
    }
}
