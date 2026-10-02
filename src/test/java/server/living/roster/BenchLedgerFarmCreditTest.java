package server.living.roster;

import config.LivingWorldConfig;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeTestKit;
import server.living.LivingWorld;
import server.living.store.LivingStore;

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
 * monster the companion could farm counts, with no DB query; without it the top drop_data row, as before.
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
