package server.living.resident;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.roster.GmLevelHolds;
import server.living.store.LivingStore;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * While a GM level set holds a companion (GmLevelHolds), its Resident quest gear (ResidentKit) is not applied: not
 * when it is dressed (materialized, content reloaded) and not when the roster or bench loads it or its level changes.
 */
class ResidentKitGateTest {
    private static final int YARA_CHARACTER = 901;
    private static final Persona YARA = new Persona("yara", "Yara", "", "WARRIOR", new Content.LevelBand(30, 30),
            Content.Strength.ROOKIE, null, "relaxed-v1", null, "perion", List.of(), true, null, List.of(), null,
            List.of(), List.of(), Map.of(), -1, -1, -1, List.of(), 0, Look.NONE, "test");

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final GmLevelHolds holds = mock(GmLevelHolds.class);
    private final Character yara = mock(Character.class);
    private ResidentRuntime runtime;

    private MockedConstruction<ResidentLooks> looks;
    private MockedStatic<ResidentKit> kit;
    private MockedStatic<ResidentWardrobe> wardrobe;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentMailboxRuntime> mailbox;

    @BeforeEach
    void wire() {
        when(world.store()).thenReturn(store);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.content()).thenReturn(mock(LivingContent.class));
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.gmLevelHolds()).thenReturn(holds);
        when(yara.getId()).thenReturn(YARA_CHARACTER);
        when(yara.getClient()).thenReturn(mock(Client.class));
        looks = mockConstruction(ResidentLooks.class); // ResidentLooks reads Character.wz
        runtime = new ResidentRuntime(world);
        kit = mockStatic(ResidentKit.class);
        wardrobe = mockStatic(ResidentWardrobe.class);
        registry = mockStatic(AgentRuntimeRegistry.class);
        mailbox = mockStatic(AgentMailboxRuntime.class);
    }

    @AfterEach
    void close() {
        mailbox.close();
        registry.close();
        wardrobe.close();
        kit.close();
        looks.close();
    }

    private void held(boolean held) {
        when(holds.held(YARA_CHARACTER, "yara")).thenReturn(held);
    }

    private void verifyKitApplied(int times) {
        kit.verify(() -> ResidentKit.apply(eq(yara), eq(YARA), any(), any(), any(), any()), times(times));
    }

    @Test
    void aHeldCompanionLoadedByTheRosterGetsNoQuestGear() {
        held(true);
        runtime.refreshKit(yara, YARA, true);
        verifyKitApplied(0);

        held(false);
        runtime.refreshKit(yara, YARA, true);
        verifyKitApplied(1);
    }

    @Test
    void aHeldCompanionGetsNoQuestGearOnItsMailbox() {
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.bot()).thenReturn(yara);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(YARA_CHARACTER)).thenReturn(entry);
        mailbox.when(() -> AgentMailboxRuntime.dispatch(eq(entry), any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(((AgentMailboxAction<?>) invocation.getArgument(1)).execute(entry)));

        held(true);
        runtime.refreshKit(yara, YARA);
        verifyKitApplied(0);

        held(false);
        runtime.refreshKit(yara, YARA);
        verifyKitApplied(1);
    }

    @Test
    void aHeldCompanionIsNotShapedAgainAsAResident() {
        // shaped to an older recipe: shaping would level it to its persona's level
        when(store.personaState("yara")).thenReturn(new LivingStore.PersonaState("yara", YARA_CHARACTER, null, 1, 0));
        ResidentRuntime.Session session = new ResidentRuntime.Session(YARA, YARA_CHARACTER);
        try (MockedStatic<ResidentShaper> shaper = mockStatic(ResidentShaper.class)) {
            held(true);
            runtime.shapeIfNeeded(session, yara);
            shaper.verify(() -> ResidentShaper.shape(any(), any(), any(), anyInt()), never());

            held(false);
            runtime.shapeIfNeeded(session, yara);
            shaper.verify(() -> ResidentShaper.shape(eq(yara), eq(YARA), any(), anyInt()), times(1));
        }
    }

    @Test
    void aCompanionAGmKeptAtItsLevelIsShapedAtThatLevelAsAResident() {
        // '!setlevel Yara 25' then 'keep', then back in town: still level 25, not its persona's 30
        when(store.personaState("yara")).thenReturn(new LivingStore.PersonaState("yara", YARA_CHARACTER, null, 0, 0));
        when(holds.kept(YARA_CHARACTER, "yara")).thenReturn(true);
        ResidentRuntime.Session session = new ResidentRuntime.Session(YARA, YARA_CHARACTER);
        try (MockedStatic<ResidentShaper> shaper = mockStatic(ResidentShaper.class)) {
            runtime.shapeIfNeeded(session, yara);
            shaper.verify(() -> ResidentShaper.shape(any(), any(), any(), anyInt()), never());
            shaper.verify(() -> ResidentShaper.shapeKeepingLevel(eq(yara), eq(YARA), any(), any()));
        }
    }

    @Test
    void aHeldCompanionIsDressedWithoutItsQuestGear() throws Exception {
        Method dress = ResidentRuntime.class.getDeclaredMethod("dress", Persona.class, Character.class);
        dress.setAccessible(true);

        held(true);
        dress.invoke(runtime, YARA, yara);
        verifyKitApplied(0);
        kit.verify(() -> ResidentKit.apply(any(), any(), any(), any(), any(), any()), never());

        held(false);
        dress.invoke(runtime, YARA, yara);
        verifyKitApplied(1);
    }
}
