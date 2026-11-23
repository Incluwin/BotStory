package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.population.paper.EventLogSweep;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A companion's crew camp report (docs/WORLD_SYSTEMS_PLAN.md N7.3): bench.report rows are written whether or not the
 * letters are on; talking to it at camp tells what its rows hold since it last told you, in the call-back's words, read
 * from the rows so a restart in between loses nothing; told once (a camp.told marker after it, kept by the retention
 * sweep), and a call-back that spoke its report counts as telling it. The notice's away report reads the same rows
 * since the owner's last logout and marks nothing.
 */
class CampReportTest {
    private static final int ANN = 10;
    private static final int FIELD = 105040300;
    private static final int STOLEN_FENCE = 4000021;

    private final FakeLivingStore store = new FakeLivingStore();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();

    @BeforeEach
    void setUp() {
        config.LETTERS_ENABLED = false;
        config.CREW_CAMP_ENABLED = true;
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store.store);
    }

    /** A ledger as a boot makes one: nothing in memory. */
    private BenchLedger boot() {
        return new BenchLedger(world, id -> id == FIELD ? "Ant Tunnel I" : "camp", Map.of(STOLEN_FENCE, "Stolen Fence")::get);
    }

    @Test
    void rowsAreWrittenWithTheLettersOffAndTheCampTellsThemOnceAcrossARestart() {
        BenchLedger before = boot();
        before.reportHaul(ANN, "olwen", FIELD, 12_000L, STOLEN_FENCE, 143);
        before.flushReports(0);
        assertEquals(1, store.events(BenchLedger.REPORT_EVENT).size(), "a row though the letters are off");

        BenchLedger after = boot(); // the server restarted: the tally in memory is gone, the row is not
        assertEquals("farmed 143 stolen fences and 12k meso at Ant Tunnel I.", after.tellAtCamp(ANN, "olwen", 0));
        assertNull(after.tellAtCamp(ANN, "olwen", 0), "told once");
        assertEquals(2, store.events(BenchLedger.CAMP_TOLD_EVENT).size(), "each telling writes its marker");

        after.reportHaul(ANN, "olwen", FIELD, 0L, STOLEN_FENCE, 7);
        assertEquals("farmed 7 stolen fences at Ant Tunnel I.", after.tellAtCamp(ANN, "olwen", 0),
                "only what it did since it last told you (its pending tally written first)");
        assertNull(boot().tellAtCamp(ANN, "olwen", 0), "and a restart after the telling tells nothing again");
    }

    @Test
    void eachCompanionHasItsOwnMarkerAndTheCampTellingSpendsTheCallBackTally() {
        BenchLedger ledger = boot();
        ledger.reportHaul(ANN, "olwen", FIELD, 0L, STOLEN_FENCE, 3);
        ledger.reportHaul(ANN, "mira", FIELD, 5_000L, 0, 0);

        assertEquals("farmed 3 stolen fences at Ant Tunnel I.", ledger.tellAtCamp(ANN, "olwen", 0));
        assertNull(ledger.takeReport(ANN, "olwen", 0), "called back after: nothing told twice");
        assertEquals("farmed 5.0k meso at Ant Tunnel I.", ledger.tellAtCamp(ANN, "mira", 0), "Mira's untouched");
    }

    @Test
    void aCallBackThatSpokeItsReportCountsAsTellingItAtCamp() {
        BenchLedger ledger = boot();
        ledger.reportHaul(ANN, "olwen", FIELD, 0L, STOLEN_FENCE, 3);
        ledger.flushReports(0);

        assertEquals("farmed 3 stolen fences at Ant Tunnel I.", ledger.takeReport(ANN, "olwen", 0));
        assertNull(boot().tellAtCamp(ANN, "olwen", 0), "the whisper on the way back told it");
    }

    @Test
    void theNoticeReadsTheRowsSinceTheLastLogoutAndMarksNothing() {
        BenchLedger ledger = boot();
        ledger.reportHaul(ANN, "olwen", FIELD, 0L, STOLEN_FENCE, 3);
        ledger.flushReports(0);
        store.store.event(ANN, null, CompanionLetters.AWAY_EVENT, "logout");
        ledger.reportHaul(ANN, "olwen", FIELD, 0L, STOLEN_FENCE, 4);

        List<server.living.store.LivingStore.CharacterEvent> rows = ledger.rowsSinceLogout(ANN);

        assertEquals("farmed 4 stolen fences at Ant Tunnel I.", ledger.reportOfRows(rows, "olwen", 0));
        assertNull(ledger.reportOfRows(rows, "mira", 0));
        assertTrue(store.events(BenchLedger.CAMP_TOLD_EVENT).isEmpty(), "reading the notice tells no companion");
        assertEquals("farmed 7 stolen fences at Ant Tunnel I.", ledger.tellAtCamp(ANN, "olwen", 0),
                "the companion itself still tells all it did since it last told you");
    }

    @Test
    void theRetentionSweepKeepsTheCampMarkers() {
        assertTrue(EventLogSweep.KEPT.stream().anyMatch(kept -> kept.endsWith(".")
                ? BenchLedger.CAMP_TOLD_EVENT.startsWith(kept) : kept.equals(BenchLedger.CAMP_TOLD_EVENT)));
    }
}
