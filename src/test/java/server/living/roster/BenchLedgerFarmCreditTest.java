package server.living.roster;

import config.LivingWorldConfig;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.knowledge.KnowledgeTestKit;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.store.LivingStore;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bench ledger's FARM_ITEM and GATHER credit (docs/SERVER_KNOWLEDGE_PLAN.md 8.3): with server knowledge only a
 * monster the companion could farm counts, with no DB query; without it the top drop_data row, as before. A bench ally
 * out with a boss run (P7 Part B) is credited nothing while its trip lasts: only the clock moves.
 */
class BenchLedgerFarmCreditTest {
    private static final int FAIRY_WING = 4003003;
    private static final int ZAKUM_HELMET = 1002357;
    private static final int SNAIL_SHELL = 4000019;
    private static final int SNAIL_FIELD = 104000100; // Right Around Lith Harbor
    private static final int ELLINIA_FIELD = 101010000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final BenchLedger ledger = new BenchLedger(world);

    BenchLedgerFarmCreditTest() {
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.store()).thenReturn(store);
        // what today's ledger reads: the top drop_data row, whoever drops it
        when(store.mobsDropping(FAIRY_WING)).thenReturn(List.of(new int[] {9500317, 40_000}));
        when(store.mobsDropping(SNAIL_SHELL)).thenReturn(List.of(new int[] {100100, 600_000}));
    }

    @Test
    void onlyWhatItCouldFarmCountsAndTheDbIsNotAsked() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertEquals(0.0d, ledger.chancePerKill(FAIRY_WING, 70, ELLINIA_FIELD, false), "script-only snowmen");
            assertEquals(0.0d, ledger.chancePerKill(FAIRY_WING, 70, 209080000, true), "not even at Happy's field");
            assertEquals(0.0d, ledger.chancePerKill(ZAKUM_HELMET, 30, ELLINIA_FIELD, false), "Zakum is no farm");
            assertEquals(0.6d, ledger.chancePerKill(SNAIL_SHELL, 10, SNAIL_FIELD, false), 1e-9, "Snails: 600000 at drop rate 1");
        }
        verify(store, never()).mobsDropping(anyInt());
    }

    @Test
    void farmItemCreditsOnlyItsCampsDroppersGatherAnyItCouldFarm() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            // a FARM_ITEM camp moved off the snails (setCamp, a too-rough move): it grinds what is there
            assertEquals(0.0d, ledger.chancePerKill(SNAIL_SHELL, 10, ELLINIA_FIELD, false), "no snails at its camp");
            assertTrue(ledger.chancePerKill(SNAIL_SHELL, 10, ELLINIA_FIELD, true) > 0, "GATHER's visit goes to the snails");
        }
    }

    @Test
    void anAllyOutWithAnExpeditionMovesOnlyTheClock() {
        // P7 Part B: a benched farmer called down to a boss run; the fight credits its EXP, the ledger nothing
        LivingStore.RosterEntry row = new LivingStore.RosterEntry(10, "orrin", LivingStore.RosterState.BENCHED,
                LivingStore.Assignment.FARM_MESO, 0, 0, SNAIL_FIELD, 1_500L, 700L, 0L, 1_000L, 1_000L,
                System.currentTimeMillis() - 3_600_000L, 120, "2026-10-07");
        Persona orrin = mock(Persona.class);
        when(orrin.id()).thenReturn("orrin");
        LivingContent content = mock(LivingContent.class);
        when(content.persona("orrin")).thenReturn(orrin);
        when(world.content()).thenReturn(content);
        when(world.routineDate()).thenReturn(LocalDate.of(2026, 10, 7));
        when(world.benchDuty()).thenReturn(mock(BenchDutyDispatcher.class)); // no live slice
        CompanionBossRuns bossRuns = mock(CompanionBossRuns.class);
        when(bossRuns.allyOnTrip("orrin")).thenReturn(true);
        when(world.bossRuns()).thenReturn(bossRuns);
        long before = System.currentTimeMillis();

        ledger.accrueNow(row);

        ArgumentCaptor<LivingStore.RosterEntry> saved = ArgumentCaptor.forClass(LivingStore.RosterEntry.class);
        verify(store).saveRosterProgress(saved.capture());
        LivingStore.RosterEntry after = saved.getValue();
        assertTrue(after.lastLedgerAtMs() >= before, "the clock moved");
        assertEquals(1_500L, after.accruedExp(), "no EXP credit");
        assertEquals(700L, after.haulMeso(), "no meso credit");
        assertEquals(120, after.farmSecondsToday(), "and no farm time toward the daily cap");
        assertEquals(LivingStore.RosterState.BENCHED, after.state());
    }

    @Test
    void withoutKnowledgeItReadsTheTopDropRow() throws Exception {
        assertEquals(0.04d, ledger.chancePerKill(FAIRY_WING, 70, ELLINIA_FIELD, false), 1e-9, "nothing published");
        boolean decisions = YamlConfig.config.knowledge.DECISIONS;
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            YamlConfig.config.knowledge.DECISIONS = false;
            assertEquals(0.04d, ledger.chancePerKill(FAIRY_WING, 70, ELLINIA_FIELD, false), 1e-9, "DECISIONS off");
            assertTrue(ledger.chancePerKill(SNAIL_SHELL, 10, SNAIL_FIELD, false) > 0);
        } finally {
            YamlConfig.config.knowledge.DECISIONS = decisions;
        }
    }
}
