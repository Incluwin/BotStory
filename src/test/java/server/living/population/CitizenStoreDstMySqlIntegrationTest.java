package server.living.population;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.living.population.Citizen.State;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Living timestamps survive MySQL as the same instant when the JVM runs in GMT (server.TIMEZONE) and the session in
 * SYSTEM (America/New_York on the live host). On 2026-09-30 citizen 366's retires_at, 2027-03-14T02:28:19Z, went out as
 * the GMT wall clock '2027-03-14 02:28:19', which New York skips, and every save of the row failed with "Incorrect
 * datetime value". The fall-back instants are the hour New York has twice. Opt in with -Dcosmic.test.mysql=true. The
 * fixture rows are RETIRED/RELEASED, so a running server never loads them, and are deleted afterwards.
 */
class CitizenStoreDstMySqlIntegrationTest {
    private static final long SPRING_FORWARD_GAP_AS_GMT = Instant.parse("2027-03-14T02:28:19Z").toEpochMilli();
    private static final long FALL_BACK_EDT = Instant.parse("2026-11-01T05:30:00Z").toEpochMilli();
    private static final long FALL_BACK_EST = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli();
    /** GMT renders it '2026-11-01 01:30:00', a wall clock New York has twice. */
    private static final long FALL_BACK_OVERLAP_AS_GMT = Instant.parse("2026-11-01T01:30:00Z").toEpochMilli();
    private static final String PERSONA = "tz-probe";

    private static TimeZone originalZone;
    private static int characterId;

    @BeforeAll
    static void connect() {
        assumeTrue(Boolean.getBoolean("cosmic.test.mysql"), "Enable explicitly with -Dcosmic.test.mysql=true");
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("GMT")); // as Server sets it; pooled connections opened now keep it
        assertTrue(DatabaseConnection.initializeConnectionPool());
        characterId = 1_900_000_000 + ThreadLocalRandom.current().nextInt(10_000_000);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (characterId != 0) {
            try (Connection con = DatabaseConnection.getConnection()) {
                for (String table : new String[] {"living_citizen", "living_roster", "living_quest_state"}) {
                    try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + table + " WHERE character_id = ?")) {
                        ps.setInt(1, characterId);
                        ps.executeUpdate();
                    }
                }
            }
        }
        if (originalZone != null) {
            TimeZone.setDefault(originalZone);
        }
    }

    @Test
    void citizenRetiringInTheGapOrTheRepeatedHourSavesAndReadsBack() throws Exception {
        CitizenStore store = new CitizenStore();
        long joined = SPRING_FORWARD_GAP_AS_GMT - 182 * 86_400_000L; // citizen 366's; a save never rewrites it
        for (long retires : new long[] {SPRING_FORWARD_GAP_AS_GMT, FALL_BACK_EDT, FALL_BACK_EST, FALL_BACK_OVERLAP_AS_GMT}) {
            Citizen citizen = new Citizen(characterId, "Tz" + characterId % 100_000_000, "tz-probe", 0, 0, "victoria",
                    "tester", "calm", "plain", 1.0d, 0, 1, Set.of(), State.RETIRED, joined, retires,
                    FALL_BACK_EDT, FALL_BACK_EST, 0L, 0, "");

            store.save(citizen); // logs and swallows a failed write; the read below catches it

            Citizen loaded = store.citizen(characterId);
            assertNotNull(loaded, "row written for retires_at " + Instant.ofEpochMilli(retires));
            assertEquals(citizen, loaded, "retires_at " + Instant.ofEpochMilli(retires));
            assertEquals(retires / 1000L, storedEpochSeconds("living_citizen", "retires_at"));
            assertEquals(FALL_BACK_EDT / 1000L, storedEpochSeconds("living_citizen", "last_online_at"));
            assertEquals(FALL_BACK_EST / 1000L, storedEpochSeconds("living_citizen", "last_ledger_at"));
        }
    }

    @Test
    void rosterClocksAcrossTheGapAndTheRepeatedHourReadBack() throws Exception {
        LivingStore store = new LivingStore();
        RosterEntry travelling = new RosterEntry(characterId, PERSONA, RosterState.RELEASED, Assignment.REST, 0, 0, 0, 0L,
                0L, FALL_BACK_OVERLAP_AS_GMT, FALL_BACK_EDT - 86_400_000L, FALL_BACK_EDT, FALL_BACK_EST, 0, "2026-11-01",
                SPRING_FORWARD_GAP_AS_GMT, 0, FALL_BACK_EST);
        store.saveRoster(travelling);
        assertEquals(travelling, store.rosterEntry(characterId, PERSONA));
        assertEquals(SPRING_FORWARD_GAP_AS_GMT / 1000L, storedEpochSeconds("living_roster", "eta_at"));
        assertEquals(FALL_BACK_EST / 1000L, storedEpochSeconds("living_roster", "parked_at"));

        RosterEntry progressed = new RosterEntry(characterId, PERSONA, RosterState.RELEASED, Assignment.REST, 0, 0, 0, 0L,
                0L, SPRING_FORWARD_GAP_AS_GMT, FALL_BACK_EDT - 86_400_000L, FALL_BACK_EST, FALL_BACK_OVERLAP_AS_GMT, 0,
                "2026-11-01", SPRING_FORWARD_GAP_AS_GMT, 0, FALL_BACK_EST);
        store.saveRosterProgress(progressed);
        assertEquals(progressed, store.rosterEntry(characterId, PERSONA));
        assertEquals(FALL_BACK_OVERLAP_AS_GMT / 1000L, storedEpochSeconds("living_roster", "last_ledger_at"));
    }

    @Test
    void questClocksInTheRepeatedHourReadBack() throws Exception {
        LivingStore store = new LivingStore();
        QuestState state = new QuestState(characterId, PERSONA, 1, 2, QuestStatus.COMPLETED, FALL_BACK_EDT, FALL_BACK_EST, 1);
        store.saveQuestState(state);
        assertEquals(state, store.questState(characterId, PERSONA));
        assertEquals(FALL_BACK_EDT / 1000L, storedEpochSeconds("living_quest_state", "started_at"));
        assertEquals(FALL_BACK_EST / 1000L, storedEpochSeconds("living_quest_state", "completed_at"));
    }

    /** The column's stored instant, read without any session time zone conversion. */
    private static long storedEpochSeconds(String table, String column) throws Exception {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT UNIX_TIMESTAMP(" + column + ") FROM " + table
                     + " WHERE character_id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), table + " row");
                return rs.getLong(1);
            }
        }
    }
}
