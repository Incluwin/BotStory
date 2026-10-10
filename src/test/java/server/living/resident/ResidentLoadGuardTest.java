package server.living.resident;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.roster.CompanionLeases;
import server.living.store.LivingStore;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The Resident load site (docs/BOT_MANAGEMENT_PLAN.md P1, "JoinOutcome and spawn fixes"): a Resident materializes
 * only a character that is free, holds its RESIDENT lease while materialized, and never takes over a character
 * that is leased or loaded for someone else.
 */
class ResidentLoadGuardTest {
    private static final int OLWEN_CHARACTER = 900;
    private static final int GM = 10;
    private static final Persona OLWEN = new Persona("olwen", "Olwen", "", "BEGINNER", new Content.LevelBand(1, 1),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Look.NONE, "test");

    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionLeases leases = new CompanionLeases();
    private final MapleMap town = mock(MapleMap.class);
    private ResidentRuntime runtime;

    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedStatic<CosmicAgentOfflineLoader> loader;
    private MockedStatic<ResidentSpots> spots;
    private MockedConstruction<ResidentLooks> looks;

    @BeforeEach
    void wireWorld() {
        LivingStore store = mock(LivingStore.class);
        when(store.personaState("olwen")).thenReturn(new LivingStore.PersonaState("olwen", OLWEN_CHARACTER, null, 0, 0));
        when(world.store()).thenReturn(store);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.leases()).thenReturn(leases);
        looks = mockConstruction(ResidentLooks.class); // ResidentLooks reads Character.wz; the look is not what is tested
        runtime = new ResidentRuntime(world);
        registry = mockStatic(AgentRuntimeRegistry.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        loader = mockStatic(CosmicAgentOfflineLoader.class);
        spots = mockStatic(ResidentSpots.class);
    }

    @AfterEach
    void closeStatics() {
        spots.close();
        loader.close();
        identity.close();
        cleanup.close();
        registry.close();
        looks.close();
    }

    @Test
    void aCharacterLeasedElsewhereIsNotLoaded() {
        leases.tryAcquire(OLWEN_CHARACTER, CompanionLeases.Kind.COMPANION, GM);

        assertNull(runtime.materialize(OLWEN, town, null, 1_000L));

        loader.verifyNoInteractions();
        cleanup.verifyNoInteractions();
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, GM), leases.holder(OLWEN_CHARACTER));
    }

    @Test
    void aCharacterFollowingSomeoneElseIsNotTakenOver() {
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(OLWEN_CHARACTER)).thenReturn(entry);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(OLWEN_CHARACTER)).thenReturn(GM);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(mock(Character.class));

        assertNull(runtime.materialize(OLWEN, town, null, 1_000L));

        loader.verifyNoInteractions();
        cleanup.verifyNoInteractions();
        assertNull(leases.holder(OLWEN_CHARACTER));
    }

    @Test
    void aFailedLoadFreesTheResidentLease() {
        loader.when(() -> CosmicAgentOfflineLoader.loadOfflineAgent(eq(OLWEN_CHARACTER), anyInt(), anyInt(), eq(town), any()))
                .thenReturn(null);

        assertNull(runtime.materialize(OLWEN, town, null, 1_000L));

        loader.verify(() -> CosmicAgentOfflineLoader.loadOfflineAgent(eq(OLWEN_CHARACTER), anyInt(), anyInt(), eq(town), any()));
        assertNull(leases.holder(OLWEN_CHARACTER));
    }

    /** The shaper and the wardrobe then see only its own gender's starter clothes (ResidentStarterSwapTest). */
    @Test
    void aMaterializedResidentSwapsItsStarterClothesBeforeItIsShapedAndDressed() {
        Character agent = mock(Character.class);
        loader.when(() -> CosmicAgentOfflineLoader.loadOfflineAgent(eq(OLWEN_CHARACTER), anyInt(), anyInt(), eq(town), any()))
                .thenReturn(agent);
        when(world.dialogue()).thenReturn(mock(ResidentDialogue.class));
        when(world.content()).thenReturn(mock(LivingContent.class));
        when(world.arcs()).thenReturn(mock(ArcService.class));
        List<String> calls = new ArrayList<>();
        try (MockedStatic<AgentInteractionRuntime> interaction = mockStatic(AgentInteractionRuntime.class);
             MockedStatic<ResidentShaper> shaper = mockStatic(ResidentShaper.class);
             MockedStatic<ResidentKit> kit = mockStatic(ResidentKit.class);
             MockedStatic<ResidentWardrobe> wardrobe = mockStatic(ResidentWardrobe.class);
             MockedStatic<ResidentStarterSwap> starterSwap = mockStatic(ResidentStarterSwap.class)) {
            interaction.when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(agent))
                    .thenReturn(mock(AgentRuntimeEntry.class));
            shaper.when(() -> ResidentShaper.shape(any(), any(), any(), anyInt())).thenAnswer(inv -> calls.add("shape"));
            kit.when(() -> ResidentKit.apply(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> calls.add("kit"));
            wardrobe.when(() -> ResidentWardrobe.dress(any(), any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> calls.add("wardrobe"));
            starterSwap.when(() -> ResidentStarterSwap.swap(any(), any())).thenAnswer(inv -> calls.add("swap"));

            assertNotNull(runtime.materialize(OLWEN, town, null, 1_000L));
        }

        assertEquals(List.of("swap", "shape", "kit", "wardrobe"), calls);
    }

    @Test
    void standbyFreesTheResidentLease() {
        leases.tryAcquire(OLWEN_CHARACTER, CompanionLeases.Kind.RESIDENT, 0);
        ResidentRuntime.Session session = new ResidentRuntime.Session(OLWEN, OLWEN_CHARACTER);
        session.entry = mock(AgentRuntimeEntry.class);

        runtime.standby(session, "town asleep");

        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(OLWEN_CHARACTER));
        assertNull(leases.holder(OLWEN_CHARACTER));
    }
}
