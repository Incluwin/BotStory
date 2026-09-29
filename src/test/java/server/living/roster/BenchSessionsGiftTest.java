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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coin given to a companion on a live bench slice (docs/BOT_MANAGEMENT_PLAN.md P3b, CompanionManager.giveCoin): the
 * slice's harvest takes what it farmed into the haul, measured from its purse at the last look, so a gift raises that
 * purse too and stays the companion's own, never taken for farmed meso or counted in what the bench earned.
 */
class BenchSessionsGiftTest {
    private static final int ALICE = 10;
    private static final int OLWEN_CHARACTER = 900;
    private static final Persona OLWEN = new Persona("olwen", "Olwen", "", "BEGINNER", new Content.LevelBand(1, 1),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Look.NONE, "test");

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Character olwenBot = mock(Character.class);
    private final AtomicInteger purse = new AtomicInteger(1_000);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private boolean loaded;

    BenchSessionsGiftTest() {
        CompanionExecutor executor = mock(CompanionExecutor.class);
        when(executor.onExecutorThread()).thenReturn(true); // everything below runs on companion-ops
        when(world.executor()).thenReturn(executor);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.leases()).thenReturn(new CompanionLeases());
        BenchLedger bench = mock(BenchLedger.class);
        when(bench.withinDailyCap(any(), anyDouble())).thenReturn(true);
        when(world.bench()).thenReturn(bench);
        when(world.roster()).thenReturn(mock(RosterService.class));
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), anyInt(), anyLong())).thenReturn(CompanionBudget.Admission.ALLOW);
        when(world.budget()).thenReturn(budget);
        LivingContent content = mock(LivingContent.class);
        when(content.persona("olwen")).thenReturn(OLWEN);
        when(world.content()).thenReturn(content);
        // Olwen, benched to farm meso at a field camp well below Alice: due a live slice
        RosterEntry row = new RosterEntry(ALICE, "olwen", RosterState.BENCHED, Assignment.FARM_MESO, 0, 0, 104010001, 0L,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null);
        when(store.allBenched()).thenReturn(List.of(row));
        when(store.rosterEntry(ALICE, "olwen")).thenReturn(row);
        when(store.characterLevel(ALICE)).thenReturn(30);
        when(store.characterLevel(OLWEN_CHARACTER)).thenReturn(10);
        when(residents.characterIdOf("olwen")).thenReturn(OLWEN_CHARACTER);
        MapleMap camp = mock(MapleMap.class);
        when(camp.getAllPlayers()).thenReturn(List.of());
        when(residents.map(104010001)).thenReturn(camp);
        when(olwenBot.getId()).thenReturn(OLWEN_CHARACTER);
        when(olwenBot.getMap()).thenReturn(camp);
        when(olwenBot.getMeso()).thenAnswer(inv -> purse.get());
        doAnswer(inv -> purse.addAndGet(inv.getArgument(0))).when(olwenBot).gainMeso(anyInt(), anyBoolean());
        Inventory empty = mock(Inventory.class);
        when(empty.list()).thenReturn(List.of());
        when(olwenBot.getInventory(any())).thenReturn(empty);

        statics.add(mockStatic(ResidentSpots.class));
        statics.add(mockStatic(AgentInteractionRuntime.class));
        MockedStatic<CosmicAgentOfflineLoader> loader = mockStatic(CosmicAgentOfflineLoader.class);
        loader.when(() -> CosmicAgentOfflineLoader.loadOfflineAgent(eq(OLWEN_CHARACTER), anyInt(), anyInt(), eq(camp), any()))
                .thenAnswer(inv -> {
                    loaded = true;
                    return olwenBot;
                });
        statics.add(loader);
        MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(OLWEN_CHARACTER))
                .thenAnswer(inv -> loaded ? new AgentRuntimeEntry(olwenBot, null, null) : null);
        statics.add(registry);
        statics.add(mockStatic(AgentRuntimeCleanupService.class));
    }

    @AfterEach
    void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    @Test
    void aGiftStaysInTheSlicesPurseWhileWhatItFarmedGoesToTheHaul() {
        BenchSessions bench = new BenchSessions(world);
        bench.tick(); // the slice loads, its purse (1,000) noted
        assertTrue(bench.live("olwen"));

        purse.addAndGet(300); // farmed
        olwenBot.gainMeso(500, false); // Alice's coin for supplies (CompanionManager.giveCoin, on companion-ops)
        assertTrue(bench.gift("olwen", 500));
        bench.tick();

        verify(store).saveRosterProgress(argThat(entry -> entry.haulMeso() == 300L)); // only what it farmed
        assertEquals(1_500, purse.get(), "the coin is still in its purse for potions");
        verify(store).event(ALICE, "olwen", "bench.harvest", "+300 meso, 0 stacks");
        assertFalse(bench.gift("nobody", 500), "no live slice: nothing to raise");
    }
}
