package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.roster.CompanionBudget.Admission;
import server.living.roster.CompanionBudget.Kind;
import server.living.roster.CompanionBudget.Usage;
import server.living.store.LivingStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CompanionBudget v1 (docs/BOT_MANAGEMENT_P0_RESULTS.md): the pure pool rule, then admit() over live counts. */
class BudgetTest {
    private static final int ALICE = 10;

    private final LivingWorldConfig config = new LivingWorldConfig();

    // ---- pure rule -----------------------------------------------------------------------

    @Test
    void defaultsAreBudgetV1() {
        assertEquals(5, config.COMPANION_PER_HUMAN);
        assertEquals(2, config.BENCH_LIVE_PER_OWNER);
        assertEquals(8, config.BENCH_LIVE_GLOBAL);
        assertEquals(12, config.EXPEDITION_BURST_MAX);
        assertEquals(300, config.COMPANION_BUDGET_CEILING);
        assertEquals(2000, config.COMPANION_SPAWN_INTERVAL_MS);
        assertEquals(4, config.BENCH_LIVE_MAX); // the bench rotation size is unchanged
    }

    @Test
    void companionsStopAtFivePerHuman() {
        assertTrue(CompanionBudget.decide(Kind.COMPANION, usage(100, 4, 0, 0, 0), config).allowed());
        assertFalse(CompanionBudget.decide(Kind.COMPANION, usage(100, 5, 0, 0, 0), config).allowed());
    }

    @Test
    void benchLiveStopsAtTheServerPoolAndThePerOwnerShare() {
        assertTrue(CompanionBudget.decide(Kind.BENCH_LIVE, usage(100, 0, 7, 1, 0), config).allowed());
        assertFalse(CompanionBudget.decide(Kind.BENCH_LIVE, usage(100, 0, 8, 0, 0), config).allowed());
        assertFalse(CompanionBudget.decide(Kind.BENCH_LIVE, usage(100, 0, 3, 2, 0), config).allowed());
    }

    @Test
    void expeditionStopsAtItsBurst() {
        assertTrue(CompanionBudget.decide(Kind.EXPEDITION, usage(100, 5, 0, 0, 11), config).allowed());
        assertFalse(CompanionBudget.decide(Kind.EXPEDITION, usage(100, 0, 0, 0, 12), config).allowed());
    }

    @Test
    void theAgentCeilingStopsEveryKind() {
        for (Kind kind : Kind.values()) {
            assertTrue(CompanionBudget.decide(kind, usage(299, 0, 0, 0, 0), config).allowed(), kind.name());
            Admission full = CompanionBudget.decide(kind, usage(300, 0, 0, 0, 0), config);
            assertFalse(full.allowed(), kind.name());
            assertEquals("Agent ceiling 300 reached", full.reason());
        }
    }

    @Test
    void loadsAreSpacedByTheInterval() {
        assertEquals(0L, CompanionBudget.waitMs(null, 5_000L, 2_000L));
        assertEquals(500L, CompanionBudget.waitMs(1_000L, 2_500L, 2_000L));
        assertEquals(0L, CompanionBudget.waitMs(1_000L, 3_000L, 2_000L));
    }

    // ---- admit over live counts ----------------------------------------------------------

    private LivingWorld world;
    private LivingStore store;
    private BenchSessions bench;
    private CompanionLeases leases;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private final Character alice = mock(Character.class);

    @BeforeEach
    void wireWorld() {
        world = mock(LivingWorld.class);
        store = mock(LivingStore.class);
        bench = mock(BenchSessions.class);
        leases = new CompanionLeases();
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store);
        when(world.benchSessions()).thenReturn(bench);
        when(world.leases()).thenReturn(leases);
        when(alice.getId()).thenReturn(ALICE);
        registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(AgentRuntimeRegistry::activeAgentCount).thenReturn(250);
    }

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void oneOwnerGetsOneCompanionLoadEveryTwoSeconds() {
        CompanionBudget budget = new CompanionBudget(world);

        assertTrue(budget.admit(Kind.COMPANION, alice, 10_000L).allowed());
        Admission tooSoon = budget.admit(Kind.COMPANION, alice, 11_000L);
        assertFalse(tooSoon.allowed());
        assertEquals("one load every 2000 ms", tooSoon.reason());
        assertEquals(1_000L, budget.loadDelayMs(alice, 11_000L));
        assertTrue(budget.admit(Kind.BENCH_LIVE, alice, 12_000L).allowed());
        // an expedition burst is not spaced
        assertTrue(budget.admit(Kind.EXPEDITION, alice, 12_001L).allowed());
    }

    @Test
    void aFullPoolIsLoggedAndDoesNotSpendTheLoadSlot() {
        CompanionBudget budget = new CompanionBudget(world);
        registry.when(() -> AgentRuntimeRegistry.activeAgentCountForLeader(ALICE)).thenReturn(5);

        Admission denied = budget.admit(Kind.COMPANION, alice, 10_000L);

        assertFalse(denied.allowed());
        assertEquals("5 companions already out", denied.reason());
        verify(store).event(eq(ALICE), isNull(), eq("companion.budget_denied"), eq("COMPANION: 5 companions already out"));
        assertEquals(0L, budget.loadDelayMs(alice, 10_000L));
    }

    @Test
    void benchAndExpeditionCountsComeFromTheBenchSessionsAndTheLeases() {
        CompanionBudget budget = new CompanionBudget(world);
        when(bench.liveCount()).thenReturn(3);
        when(bench.liveCountFor(ALICE)).thenReturn(2);
        for (int id = 1; id <= 12; id++) {
            leases.tryAcquire(id, CompanionLeases.Kind.EXPEDITION, ALICE);
        }

        assertEquals(new Usage(250, 0, 3, 2, 12), budget.usage(ALICE));
        assertFalse(budget.admit(Kind.BENCH_LIVE, alice, 10_000L).allowed());
        assertFalse(budget.admit(Kind.EXPEDITION, alice, 10_000L).allowed());
        assertTrue(budget.admit(Kind.COMPANION, alice, 10_000L).allowed());
    }

    @Test
    void noOwnerIsNeverAdmitted() {
        CompanionBudget budget = new CompanionBudget(world);

        assertFalse(budget.admit(Kind.COMPANION, null, 10_000L).allowed());
        verify(store, never()).event(eq(ALICE), isNull(), anyString(), anyString());
    }

    private static Usage usage(int agents, int ownerCompanions, int benchLive, int ownerBenchLive, int expedition) {
        return new Usage(agents, ownerCompanions, benchLive, ownerBenchLive, expedition);
    }
}
