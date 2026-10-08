package server.living.resident;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content;
import server.living.config.Content.Loadout;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.roster.GmLevelHolds;
import server.living.store.LivingStore;
import server.living.store.LivingStore.PersonaState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A persona is shaped (level, job, AP, skills, loadout) when it first materializes as a Resident in its home town. One
 * granted (!grantcompanion) or called before it ever appeared there has a character made at its first call: a level-1
 * beginner holding the creator's sword. Found live 2026-10-07: Flint (GUNSLINGER, nautilus-gun-l33) and Isolde (CLERIC)
 * were granted to Bossfriend and never shaped (shaped_version 0); Flint fought Easy Balrog with the beginner sword. When
 * the roster or bench loads a companion, or a GM level set stops holding one, a never-shaped persona is shaped now.
 */
class ResidentCompanionShapingTest {
    private static final int FLINT_CHARACTER = 1010;
    private static final int BOSSFRIEND = 1008;
    private static final String GUN_L33 = "nautilus-gun-l33";
    private static final Loadout GUN_LOADOUT = new Loadout(GUN_L33, List.of(1492004, 1002019),
            List.of(new Content.ItemStack(2330000, 1000)), Map.of("dex", 4, "str", 1), Map.of());
    private static final Persona FLINT = persona("flint", "Flint", "GUNSLINGER", GUN_L33);
    private static final Persona HIRE = persona("hire:391", "Anvith", "MAGICIAN", null);

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final LivingContent content = mock(LivingContent.class);
    private final GmLevelHolds holds = mock(GmLevelHolds.class);
    private final Character flint = mock(Character.class);
    private final List<String> calls = new ArrayList<>();
    private ResidentRuntime runtime;

    private MockedConstruction<ResidentLooks> looks;
    private MockedStatic<ResidentShaper> shaper;
    private MockedStatic<ResidentKit> kit;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentMailboxRuntime> mailbox;

    @BeforeEach
    void wire() {
        when(world.store()).thenReturn(store);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.content()).thenReturn(content);
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.gmLevelHolds()).thenReturn(holds);
        when(content.loadout(GUN_L33)).thenReturn(GUN_LOADOUT);
        when(flint.getId()).thenReturn(FLINT_CHARACTER);
        when(flint.getClient()).thenReturn(mock(Client.class));
        looks = mockConstruction(ResidentLooks.class); // ResidentLooks reads Character.wz
        runtime = new ResidentRuntime(world);
        shaper = mockStatic(ResidentShaper.class);
        shaper.when(() -> ResidentShaper.shape(any(), any(), any(), anyInt())).thenAnswer(inv -> calls.add("shape"));
        kit = mockStatic(ResidentKit.class);
        kit.when(() -> ResidentKit.apply(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> calls.add("kit"));
        registry = mockStatic(AgentRuntimeRegistry.class);
        mailbox = mockStatic(AgentMailboxRuntime.class);
    }

    @AfterEach
    void close() {
        mailbox.close();
        registry.close();
        kit.close();
        shaper.close();
        looks.close();
    }

    private void stored(int characterId, int shapedVersion) {
        when(store.personaState("flint")).thenReturn(new PersonaState("flint", characterId, null, shapedVersion, BOSSFRIEND));
    }

    private void verifyNeverShaped() {
        shaper.verify(() -> ResidentShaper.shape(any(), any(), any(), anyInt()), never());
        verify(store, never()).savePersonaState(any());
    }

    @Test
    void aGrantedPersonaNeverShapedIsShapedToItsLoadoutWhenTheRosterLoadsIt() {
        stored(FLINT_CHARACTER, 0);

        runtime.prepareCompanion(flint, FLINT, true); // loaded, not registered yet: nothing ticks it

        shaper.verify(() -> ResidentShaper.shape(flint, FLINT, GUN_LOADOUT, "flint".hashCode()));
        ArgumentCaptor<PersonaState> saved = ArgumentCaptor.forClass(PersonaState.class);
        verify(store).savePersonaState(saved.capture());
        assertEquals(new PersonaState("flint", FLINT_CHARACTER, null,
                ResidentShaper.RECIPE_VERSION * 1000 + Math.abs(GUN_L33.hashCode() % 1000), BOSSFRIEND), saved.getValue(),
                "shaped to its recipe and loadout, its employer kept");
        verify(store).event(eq(0), eq("flint"), eq("persona.shaped"), contains("loadout " + GUN_L33));
        assertEquals(List.of("shape", "kit"), calls, "shaped first, then its quest gear for the level it now has");
    }

    @Test
    void aCompanionShapedAsAResidentIsNotReshapedWhenLoaded() {
        stored(FLINT_CHARACTER, 5231); // shaped in town before it was recruited (any version: a companion is not reshaped)

        runtime.prepareCompanion(flint, FLINT, true);

        verifyNeverShaped();
        assertEquals(List.of("kit"), calls);
    }

    @Test
    void aNeverShapedCompanionHeldAtAGmLevelIsNotShaped() {
        stored(FLINT_CHARACTER, 0);
        when(holds.held(FLINT_CHARACTER, "flint")).thenReturn(true); // shaping would level it to its persona's level

        runtime.prepareCompanion(flint, FLINT, true);

        verifyNeverShaped();
    }

    @Test
    void aHiredCitizenIsNeverShaped() {
        when(store.personaState(HIRE.id())).thenReturn(new PersonaState(HIRE.id(), FLINT_CHARACTER, null, 0, BOSSFRIEND));

        runtime.prepareCompanion(flint, HIRE, true);

        verifyNeverShaped();
    }

    @Test
    void aPersonaStateThatCannotBeReadNeverShapes() {
        stored(0, 0); // LivingStore.personaState answers a failed read with an empty state
        runtime.prepareCompanion(flint, FLINT, true);
        verifyNeverShaped(); // a save from it would clear its employer

        stored(FLINT_CHARACTER + 1, 0); // names another character
        runtime.prepareCompanion(flint, FLINT, true);
        verifyNeverShaped();
    }

    @Test
    void aCompanionNotLiveIsShapedWhenItNextLoads() {
        stored(FLINT_CHARACTER, 0);

        runtime.prepareCompanion(flint, FLINT, false); // a GM hold released while it is benched

        verifyNeverShaped();
    }

    /** {@code loading}: the roster's call (registered at its spawn) or a GM level set's release (live companion). */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRegisteredCompanionIsShapedOnItsOwnMailboxBeforeItsQuestGear(boolean loading) {
        stored(FLINT_CHARACTER, 0);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.bot()).thenReturn(flint);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(FLINT_CHARACTER)).thenReturn(entry);
        List<AgentMailboxAction<?>> queued = new ArrayList<>();
        mailbox.when(() -> AgentMailboxRuntime.dispatch(eq(entry), any())).thenAnswer(inv -> {
            queued.add(inv.getArgument(1));
            return new CompletableFuture<>();
        });

        runtime.prepareCompanion(flint, FLINT, loading);
        verifyNeverShaped(); // nothing on the calling thread

        for (AgentMailboxAction<?> action : queued) { // the Agent's next tick drains its mailbox in order
            action.execute(entry);
        }
        shaper.verify(() -> ResidentShaper.shape(flint, FLINT, GUN_LOADOUT, "flint".hashCode()));
        verify(store).event(eq(0), eq("flint"), eq("persona.shaped"), anyString());
        assertEquals(List.of("shape", "kit"), calls);
    }

    private static Persona persona(String id, String name, String job, String loadout) {
        return new Persona(id, name, "", job, new Content.LevelBand(33, 36), Content.Strength.SEASONED, loadout,
                "relaxed-v1", null, "nautilus", List.of(), true, null, List.of(), null, List.of(), List.of(), Map.of(), -1,
                -1, -1, List.of(), 0, Look.NONE, "test");
    }
}
