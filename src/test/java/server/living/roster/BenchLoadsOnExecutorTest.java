package server.living.roster;

import client.Character;
import client.inventory.Inventory;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Bench loads on companion-ops (docs/BOT_MANAGEMENT_PLAN.md P2b, "Threading and party-event model"): every live bench
 * slice load and unload, and every roster load, runs on the companion-ops thread; a timer (or a shutdown) only hands
 * the work over. Called from any other thread, BenchSessions queues the work instead of doing it; the roster's timers
 * submit their task; LivingWorld's timers that touch companions only submit.
 */
class BenchLoadsOnExecutorTest {
    private static final int ALICE = 10;
    private static final int OLWEN_CHARACTER = 900;
    private static final Persona OLWEN = new Persona("olwen", "Olwen", "", "BEGINNER", new Content.LevelBand(1, 1),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Look.NONE, "test");
    private static final Path MAIN = Path.of("src/main/java");

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionLeases leases = new CompanionLeases();
    private final Character olwenBot = mock(Character.class);
    private final List<Runnable> ops = new ArrayList<>();
    private final AtomicBoolean onOps = new AtomicBoolean();
    private final List<String> calls = new ArrayList<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    BenchLoadsOnExecutorTest() {
        CompanionExecutor executor = mock(CompanionExecutor.class);
        when(executor.onExecutorThread()).thenAnswer(inv -> onOps.get());
        when(executor.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            ops.add(inv.getArgument(1));
            return CompletableFuture.completedFuture(null);
        });
        when(world.executor()).thenReturn(executor);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.leases()).thenReturn(leases);
        when(world.bench()).thenReturn(mock(BenchLedger.class));
        when(world.roster()).thenReturn(mock(RosterService.class));
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), anyInt(), anyLong())).thenReturn(CompanionBudget.Admission.ALLOW);
        when(world.budget()).thenReturn(budget);
        LivingContent content = mock(LivingContent.class);
        when(content.persona("olwen")).thenReturn(OLWEN);
        when(world.content()).thenReturn(content);
        // Olwen, benched to train at a field camp well below Alice: due a live slice
        RosterEntry row = new RosterEntry(ALICE, "olwen", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 104010001, 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, 0, null);
        when(store.allBenched()).thenReturn(List.of(row));
        when(store.rosterEntry(ALICE, "olwen")).thenReturn(row);
        when(store.characterLevel(ALICE)).thenReturn(30);
        when(store.characterLevel(OLWEN_CHARACTER)).thenReturn(10);
        when(residents.characterIdOf("olwen")).thenReturn(OLWEN_CHARACTER);
        MapleMap camp = mock(MapleMap.class);
        when(residents.map(104010001)).thenReturn(camp);
        when(olwenBot.getId()).thenReturn(OLWEN_CHARACTER);
        when(olwenBot.getMap()).thenReturn(camp);
        Inventory empty = mock(Inventory.class);
        when(empty.list()).thenReturn(List.of());
        when(olwenBot.getInventory(any())).thenReturn(empty);
    }

    /** BenchSessions over this world, with the Agent runtime's statics faked (loads and unloads are recorded). */
    private BenchSessions bench() {
        MapleMap camp = residents.map(104010001);
        statics.add(mockStatic(ResidentSpots.class));
        statics.add(mockStatic(AgentInteractionRuntime.class));
        MockedStatic<CosmicAgentOfflineLoader> loader = mockStatic(CosmicAgentOfflineLoader.class);
        loader.when(() -> CosmicAgentOfflineLoader.loadOfflineAgent(eq(OLWEN_CHARACTER), anyInt(), anyInt(), eq(camp), any()))
                .thenAnswer(inv -> {
                    calls.add("load Olwen " + (onOps.get() ? "on companion-ops" : "elsewhere"));
                    return olwenBot;
                });
        statics.add(loader);
        MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(OLWEN_CHARACTER))
                .thenAnswer(inv -> calls.stream().anyMatch(call -> call.startsWith("load")) && !calls.stream().anyMatch(call -> call.startsWith("unload"))
                        ? new AgentRuntimeEntry(olwenBot, null, null) : null);
        statics.add(registry);
        MockedStatic<AgentRuntimeCleanupService> cleanup = mockStatic(AgentRuntimeCleanupService.class);
        cleanup.when(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(OLWEN_CHARACTER)).thenAnswer(inv ->
                calls.add("unload Olwen " + (onOps.get() ? "on companion-ops" : "elsewhere")));
        statics.add(cleanup);
        return new BenchSessions(world);
    }

    @AfterEach
    void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    @Test
    void aTickFromTheTimerOnlyHandsTheSliceLoadOver() {
        BenchSessions bench = bench();

        bench.tick(); // the LOW_PRIORITY timer's thread

        assertEquals(List.of(), calls, "nothing loaded off companion-ops");
        assertNull(leases.holder(OLWEN_CHARACTER));
        assertEquals(1, ops.size());
        runOps();
        assertEquals(List.of("load Olwen on companion-ops"), calls);
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.BENCH_LIVE, ALICE), leases.holder(OLWEN_CHARACTER));
        assertTrue(bench.live("olwen"));
    }

    @Test
    void anUnloadAskedForFromAnotherThreadRunsOnCompanionOps() {
        BenchSessions bench = bench();
        bench.tick();
        runOps();

        bench.endFor("olwen", "called by owner");

        assertEquals(List.of("load Olwen on companion-ops"), calls, "not unloaded here");
        assertTrue(bench.live("olwen"));
        runOps();
        assertEquals(List.of("load Olwen on companion-ops", "unload Olwen on companion-ops"), calls);
        assertNull(leases.holder(OLWEN_CHARACTER));
    }

    @Test
    void theShutdownsEndAllRunsOnCompanionOpsToo() {
        BenchSessions bench = bench();
        bench.tick();
        runOps();

        bench.endAll("server shutdown");

        assertTrue(bench.live("olwen"));
        runOps();
        assertEquals("unload Olwen on companion-ops", calls.get(calls.size() - 1));
    }

    @Test
    void onCompanionOpsItRunsAtOnce() {
        BenchSessions bench = bench();
        onOps.set(true);

        bench.tick();
        bench.endFor("olwen", "called by owner");

        assertEquals(List.of("load Olwen on companion-ops", "unload Olwen on companion-ops"), calls);
        assertEquals(List.of(), ops);
    }

    @Test
    void livingWorldTimersThatTouchCompanionsOnlySubmitToCompanionOps() throws IOException {
        String source = Files.readString(MAIN.resolve("server/living/LivingWorld.java"));
        Matcher timer = Pattern.compile("timers\\.register\\(TimerManager\\.SchedulerLane\\.LOW_PRIORITY,\\s*([^,]+(?:,[^,]*?\\))?)")
                .matcher(source);
        int companionTimers = 0;
        while (timer.find()) {
            String task = timer.group(1);
            if (task.matches("(?s).*\\b(bench|benchSessions|contracts|auditor|partySync|roster)\\b.*")) {
                companionTimers++;
                assertTrue(task.startsWith("() -> world.executor.submit("), "a timer only hands companion work over: " + task);
            }
        }
        assertEquals(5, companionTimers, "bench ledger, contracts, bench slices, resume, audit");
        // the shutdown's bench unloads go to companion-ops as well, and it waits for them
        assertTrue(source.contains("world.executor.submit(\"bench_sessions.end_all\", () -> world.benchSessions.endAll("));
        assertEquals(1, source.split("benchSessions\\.endAll\\(", -1).length - 1);
    }

    @Test
    void theRostersOnlyTimerHandsItsTaskToCompanionOps() throws IOException {
        String roster = Files.readString(MAIN.resolve("server/living/roster/RosterService.java"));
        assertEquals(1, roster.split("TimerManager\\.getInstance\\(\\)\\.schedule\\(", -1).length - 1);
        assertTrue(roster.contains("() -> world.executor().submit(name, task), delayMs);"));
        String bench = Files.readString(MAIN.resolve("server/living/roster/BenchSessions.java"));
        assertTrue(!bench.contains("TimerManager"), "BenchSessions schedules nothing of its own");
    }

    @Test
    void aTravellersArrivalTimerOnlyQueuesItsArrival() {
        try (LifecycleHarness game = new LifecycleHarness()) {
            Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LifecycleHarness.LITH);
            game.roster().call(game.ann, olwen);
            List<Runnable> queued = DirectExecutor.queue();
            CompanionExecutor companionOps = DirectExecutor.queued(queued);
            when(game.world.executor()).thenReturn(companionOps);

            game.runDueTimers(); // the LOW_PRIORITY timer fires

            assertEquals(RosterState.TRAVELLING, game.row(olwen).state(), "nothing ran on the timer's thread");
            assertEquals(1, queued.size());
            queued.get(0).run(); // companion-ops
            assertEquals(RosterState.ACTIVE, game.row(olwen).state());
            assertEquals("follow Olwen", game.calls.get(game.calls.size() - 1));
        }
    }

    private void runOps() {
        onOps.set(true);
        try {
            while (!ops.isEmpty()) {
                ops.remove(0).run();
            }
        } finally {
            onOps.set(false);
        }
    }
}
