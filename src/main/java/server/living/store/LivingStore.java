package server.living.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.Content.BondStage;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        String sql = "SELECT " + BOND_COLUMNS + " FROM living_bond WHERE character_id = ? AND persona_id = ?";
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
        String sql = "SELECT " + BOND_COLUMNS + " FROM living_bond WHERE character_id = ?";
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
        String sql = "INSERT INTO living_bond (" + BOND_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE stage = VALUES(stage), points = VALUES(points), flags = VALUES(flags),"
                + " contract_state = VALUES(contract_state), contract_started_at = VALUES(contract_started_at),"
                + " offer_expires_at = VALUES(offer_expires_at), proof_progress = VALUES(proof_progress),"
                + " paid_meso = VALUES(paid_meso), party_seconds = VALUES(party_seconds), left_at = VALUES(left_at),"
                + " last_interaction_at = VALUES(last_interaction_at)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, bond.characterId());
            ps.setString(2, bond.personaId());
            ps.setString(3, bond.stage().name());
            ps.setInt(4, bond.points());
            ps.setString(5, String.join(",", bond.flags()));
            ps.setString(6, bond.contractState().name());
            setNullableTime(ps, 7, bond.contractStartedAtMs());
            setNullableTime(ps, 8, bond.offerExpiresAtMs());
            ps.setInt(9, bond.proofProgress());
            ps.setLong(10, bond.paidMeso());
            ps.setInt(11, bond.partySeconds());
            setNullableTime(ps, 12, bond.leftAtMs());
            setNullableTime(ps, 13, bond.lastInteractionAtMs());
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
                time(rs.getTimestamp("contract_started_at")), time(rs.getTimestamp("offer_expires_at")),
                rs.getInt("proof_progress"), rs.getLong("paid_meso"), rs.getInt("party_seconds"),
                time(rs.getTimestamp("left_at")), time(rs.getTimestamp("last_interaction_at")));
    }

    // ---- quests --------------------------------------------------------------------------

    public QuestState questState(int characterId, String questId) {
        String sql = "SELECT step_index, progress, status, started_at, completed_at, repeats FROM living_quest_state"
                + " WHERE character_id = ? AND quest_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, questId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new QuestState(characterId, questId, rs.getInt(1), rs.getInt(2),
                            QuestStatus.valueOf(rs.getString(3)), time(rs.getTimestamp(4)), time(rs.getTimestamp(5)),
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
        String sql = "SELECT quest_id, step_index, progress, status, started_at, completed_at, repeats FROM living_quest_state"
                + " WHERE character_id = ?" + (status == null ? "" : " AND status = ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            if (status != null) {
                ps.setString(2, status.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new QuestState(characterId, rs.getString(1), rs.getInt(2), rs.getInt(3),
                            QuestStatus.valueOf(rs.getString(4)), time(rs.getTimestamp(5)), time(rs.getTimestamp(6)),
                            rs.getInt(7)));
                }
            }
        } catch (SQLException failure) {
            log.warn("living_quest_state scan failed for {}", characterId, failure);
        }
        return out;
    }

    public void saveQuestState(QuestState state) {
        String sql = "INSERT INTO living_quest_state (character_id, quest_id, step_index, progress, status, started_at,"
                + " completed_at, repeats) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE step_index = VALUES(step_index),"
                + " progress = VALUES(progress), status = VALUES(status), started_at = VALUES(started_at),"
                + " completed_at = VALUES(completed_at), repeats = VALUES(repeats)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, state.characterId());
            ps.setString(2, state.questId());
            ps.setInt(3, state.stepIndex());
            ps.setInt(4, state.progress());
            ps.setString(5, state.status().name());
            setNullableTime(ps, 6, state.startedAtMs());
            setNullableTime(ps, 7, state.completedAtMs());
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
        String sql = "SELECT " + ROSTER_COLUMNS + " FROM living_roster WHERE character_id = ? AND persona_id = ?";
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
        String sql = "SELECT " + ROSTER_COLUMNS + " FROM living_roster WHERE " + where;
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
        String sql = "INSERT INTO living_roster (" + ROSTER_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE state = VALUES(state), assignment = VALUES(assignment),"
                + " assignment_item = VALUES(assignment_item), assignment_count = VALUES(assignment_count),"
                + " camp_map_id = VALUES(camp_map_id), accrued_exp = VALUES(accrued_exp), haul_meso = VALUES(haul_meso),"
                + " wages_paid_until = VALUES(wages_paid_until), benched_at = VALUES(benched_at),"
                + " last_ledger_at = VALUES(last_ledger_at), farm_seconds_today = VALUES(farm_seconds_today), farm_day = VALUES(farm_day),"
                + " eta_at = VALUES(eta_at), hold_map_id = VALUES(hold_map_id), parked_at = VALUES(parked_at)";
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
            setNullableTime(ps, 10, entry.wagesPaidUntilMs());
            ps.setTimestamp(11, new Timestamp(entry.recruitedAtMs() == 0L ? System.currentTimeMillis() : entry.recruitedAtMs()));
            setNullableTime(ps, 12, entry.benchedAtMs());
            setNullableTime(ps, 13, entry.lastLedgerAtMs());
            ps.setInt(14, entry.farmSecondsToday());
            ps.setString(15, entry.farmDay());
            setNullableTime(ps, 16, entry.etaAtMs());
            setNullableInt(ps, 17, entry.holdMapId());
            setNullableTime(ps, 18, entry.parkedAtMs());
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
        String sql = "UPDATE living_roster SET " + ROSTER_PROGRESS_COLUMNS + " WHERE character_id = ? AND persona_id = ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, entry.assignment().name());
            setNullableInt(ps, 2, entry.assignmentItem());
            setNullableInt(ps, 3, entry.assignmentCount());
            setNullableInt(ps, 4, entry.campMapId());
            ps.setLong(5, entry.accruedExp());
            ps.setLong(6, entry.haulMeso());
            setNullableTime(ps, 7, entry.wagesPaidUntilMs());
            setNullableTime(ps, 8, entry.benchedAtMs());
            setNullableTime(ps, 9, entry.lastLedgerAtMs());
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
                rs.getLong("accrued_exp"), rs.getLong("haul_meso"), time(rs.getTimestamp("wages_paid_until")),
                time(rs.getTimestamp("recruited_at")), time(rs.getTimestamp("benched_at")),
                time(rs.getTimestamp("last_ledger_at")), rs.getInt("farm_seconds_today"), rs.getString("farm_day"),
                time(rs.getTimestamp("eta_at")), rs.getInt("hold_map_id"), time(rs.getTimestamp("parked_at")));
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
        String sql = "SELECT COALESCE(SUM(quantity), 0) FROM living_haul_transfer WHERE character_id = ? AND persona_id = ?"
                + " AND item_id = ? AND transferred_at >= ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, personaId);
            ps.setInt(3, itemId);
            ps.setTimestamp(4, new Timestamp(sinceMs));
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
        String sql = "SELECT normalized_text, canonical, hits, source, approved, first_seen, last_seen FROM living_learned_phrase";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new LearnedPhrase(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getBoolean(5), time(rs.getTimestamp(6)), time(rs.getTimestamp(7))));
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
        String sql = "INSERT INTO living_event_log (character_id, persona_id, kind, detail) VALUES (?, ?, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            setNullableInt(ps, 1, characterId);
            ps.setString(2, personaId);
            ps.setString(3, kind);
            ps.setString(4, detail == null ? null : detail.length() > 512 ? detail.substring(0, 512) : detail);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("living_event_log write failed ({})", kind, failure);
        }
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

    private static void setNullableTime(PreparedStatement ps, int index, long ms) throws SQLException {
        if (ms <= 0L) {
            ps.setNull(index, java.sql.Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, new Timestamp(ms));
        }
    }

    private static long time(Timestamp value) {
        return value == null ? 0L : value.getTime();
    }

    /** {@code ms} rounded to the nearest whole second, as MySQL stores it: the TIMESTAMP columns read it back unchanged. */
    public static long toWholeSecond(long ms) {
        return Math.floorDiv(ms + 500L, 1000L) * 1000L;
    }
}
