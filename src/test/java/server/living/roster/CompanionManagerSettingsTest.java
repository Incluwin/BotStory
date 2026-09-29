package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatBuffStateRuntime;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.capabilities.supplies.AgentPotionThresholds;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * CompanionManager's settings and coin (docs/BOT_MANAGEMENT_PLAN.md P3b, the Fighting style and Supplies &amp; loot
 * screens' calls): only the owner's own companions, each setting kept in settings_json and answered at once by the
 * Agent seams, a support toggle said to a companion out with the owner in the Agent's own phrase, a formation of its
 * own taken at once (and the crew-wide formation kept as each one's own), and a little coin moved from the owner's
 * purse into the companion's, loaded or not, never more than the owner has or its purse holds.
 */
class CompanionManagerSettingsTest {
    private static final int BEN = 20;

    private LifecycleHarness h;
    private Persona hadrik;
    private Persona mira;
    private Persona brannock;
    private CompanionManager manager;
    private MockedStatic<CosmicAgentWhisperCommandBridge> whispers;
    private MockedStatic<AgentChatRouteCoordinator> chat;
    private MockedStatic<AgentMailboxRuntime> mailboxes;
    private MockedStatic<AgentDialogueTransportRuntime> replies;
    private final List<AgentRuntimeEntry> dispatched = new ArrayList<>();

    @BeforeEach
    void wire() {
        h = new LifecycleHarness();
        hadrik = h.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        mira = h.companion("mira", "Mira", 903, RosterState.BENCHED, HENESYS);
        brannock = h.companionOf(BEN, "brannock", "Brannock", 904, RosterState.ACTIVE, LITH); // Ben's
        h.roster();
        when(h.world.directory().mayControl(any(), anyInt())).thenReturn(true);
        manager = new CompanionManager(h.world);
        whispers = org.mockito.Mockito.mockStatic(CosmicAgentWhisperCommandBridge.class);
        chat = org.mockito.Mockito.mockStatic(AgentChatRouteCoordinator.class);
        replies = org.mockito.Mockito.mockStatic(AgentDialogueTransportRuntime.class);
        mailboxes = org.mockito.Mockito.mockStatic(AgentMailboxRuntime.class);
        mailboxes.when(() -> AgentMailboxRuntime.dispatch(any(AgentRuntimeEntry.class), any(AgentMailboxAction.class)))
                .thenAnswer(inv -> {
                    AgentRuntimeEntry runtime = inv.getArgument(0);
                    dispatched.add(runtime);
                    return CompletableFuture.completedFuture(((AgentMailboxAction<?>) inv.getArgument(1)).execute(runtime));
                });
    }

    @AfterEach
    void close() {
        // the crew-wide formation command ran for real: the leader's formation is global Agent state
        server.agents.capabilities.movement.AgentFormationService.formationsByLeaderId().remove(ANN);
        mailboxes.close();
        replies.close();
        chat.close();
        whispers.close();
        h.close();
    }

    private String json(Persona persona) {
        return h.store.settings.get(FakeLivingStore.key(ANN, persona.id()));
    }

    // ---- ownership -----------------------------------------------------------------------------------

    @Test
    void aFriendsCompanionIsNotOwnedAndNothingIsWrittenOrMoved() {
        h.outWithAnn(brannock); // standing right here in the same party
        when(h.ann.getMeso()).thenReturn(1_000_000);

        assertNull(manager.settings(h.ann, brannock));
        for (RosterResult result : List.of(manager.setPotionThresholds(h.ann, brannock, 40, 30),
                manager.setLootPolicy(h.ann, brannock, AgentLootPolicy.Mode.NONE),
                manager.setLeaveBossDrops(h.ann, brannock, false),
                manager.setSupport(h.ann, brannock, CompanionSettings.Support.BUFFS, false),
                manager.setFormation(h.ann, brannock, "left"),
                manager.giveCoin(h.ann, brannock, 500))) {
            assertInstanceOf(RosterResult.NotOwned.class, result);
        }

        assertTrue(h.store.settings.isEmpty());
        assertTrue(h.store.events("companion.settings").isEmpty());
        assertTrue(h.store.events("roster.coin").isEmpty());
        verify(h.ann, never()).gainMeso(anyInt(), anyBoolean());
        verify(h.bot(brannock), never()).gainMeso(anyInt(), anyBoolean());
        whispers.verifyNoInteractions();
        assertTrue(dispatched.isEmpty());
    }

    // ---- settings --------------------------------------------------------------------------------------

    @Test
    void theDefaultsUntilTheOwnerChangesOne() {
        assertEquals(CompanionSettings.defaults(), manager.settings(h.ann, mira));
    }

    @Test
    void potionPercentagesAreKeptAndTheSeamAnswersAtOnce() {
        h.moveAnn(HENESYS, 500, 200);
        h.roster().call(h.ann, mira); // loaded at once from her camp here: bound to her settings

        RosterResult result = manager.setPotionThresholds(h.ann, mira, 45, 35);

        assertEquals("Mira drinks a potion below 45% HP and 35% MP.", ((RosterResult.Ok) result).line());
        assertEquals("{\"hp\":45,\"mp\":35}", json(mira));
        assertEquals(new AgentPotionThresholds.Thresholds(0.45f, 0.35f), h.roster().settings().thresholds(903),
                "no reload needed: the next potion check drinks by them");
        assertEquals(List.of(ANN + " mira companion.settings {\"hp\":45,\"mp\":35}"), h.store.events("companion.settings"));

        for (int[] bad : new int[][]{{5, 30}, {45, 95}, {0, 0}}) {
            assertInstanceOf(RosterResult.Failed.class, manager.setPotionThresholds(h.ann, mira, bad[0], bad[1]));
        }
        assertEquals("{\"hp\":45,\"mp\":35}", json(mira), "a refused value writes nothing");
    }

    @Test
    void theLootPolicyAndTheBossFlagAreKept() {
        assertEquals("Mira leaves all the loot to you.",
                ((RosterResult.Ok) manager.setLootPolicy(h.ann, mira, AgentLootPolicy.Mode.NONE)).line());
        assertEquals("Mira picks up a boss's drops too.",
                ((RosterResult.Ok) manager.setLeaveBossDrops(h.ann, mira, false)).line());

        assertEquals("{\"loot\":\"NONE\",\"leaveBossDrops\":false}", json(mira));
        CompanionSettings settings = manager.settings(h.ann, mira);
        assertEquals(AgentLootPolicy.Mode.NONE, settings.loot());
        assertFalse(settings.leaveBossDrops());
        for (AgentLootPolicy.Mode mode : AgentLootPolicy.Mode.values()) {
            assertTrue(manager.setLootPolicy(h.ann, mira, mode).ok(), mode.name());
            assertEquals(mode, manager.settings(h.ann, mira).loot());
        }
    }

    @Test
    void aSupportToggleIsSaidToACompanionOutWithTheOwnerInTheAgentsOwnPhrase() {
        h.outWithAnn(hadrik);
        Character bot = h.bot(hadrik);

        RosterResult off = manager.setSupport(h.ann, hadrik, CompanionSettings.Support.BUFFS, false);
        manager.setSupport(h.ann, hadrik, CompanionSettings.Support.BUFF_POTIONS, true);

        assertEquals("Hadrik stops buffing the party.", ((RosterResult.Ok) off).line());
        whispers.verify(() -> CosmicAgentWhisperCommandBridge.handleWhisperToAgent(h.ann, bot, "support off"));
        whispers.verify(() -> CosmicAgentWhisperCommandBridge.handleWhisperToAgent(h.ann, bot, "buff pots on"));
        assertEquals("{\"buffs\":false,\"buffPotions\":true}", json(hadrik));
        assertTrue(dispatched.isEmpty(), "the chat path sets it, and the companion answers for itself");
    }

    @Test
    void aSupportToggleIsSetQuietlyOnOneOnItsWayAndKeptForOneOnTheBench() {
        h.roster().call(h.ann, mira); // from her Henesys camp to Ann's field: on her way
        assertEquals(RosterState.TRAVELLING, h.row(mira).state());

        manager.setSupport(h.ann, mira, CompanionSettings.Support.BUFFS, false);

        whispers.verifyNoInteractions();
        assertEquals(1, dispatched.size());
        assertFalse(AgentCombatBuffStateRuntime.skillBuffsEnabled(dispatched.get(0)));

        dispatched.clear();
        manager.setSupport(h.ann, hadrik, CompanionSettings.Support.HEALS, false); // not loaded: kept for its next load
        assertTrue(dispatched.isEmpty());
        assertEquals("{\"heals\":false}", json(hadrik));
    }

    @Test
    void aFormationOfItsOwnIsKeptTakenAtOnceAndAnsweredInItsOwnWords() {
        h.outWithAnn(hadrik);

        RosterResult result = manager.setFormation(h.ann, hadrik, "left");

        assertEquals("You tell Hadrik: keep to my left.", ((RosterResult.Ok) result).line());
        assertEquals("{\"formation\":\"left\"}", json(hadrik));
        replies.verify(() -> AgentDialogueTransportRuntime.queueReply(any(), eq("ok, keeping to your left.")));

        assertEquals("You tell Hadrik: keep with the others.",
                ((RosterResult.Ok) manager.setFormation(h.ann, hadrik, null)).line());
        assertEquals("{}", json(hadrik));

        assertInstanceOf(RosterResult.Failed.class, manager.setFormation(h.ann, hadrik, "zigzag"));
        assertEquals("{}", json(hadrik), "an unknown formation writes nothing");
    }

    @Test
    void theCrewWideFormationIsKeptAsEachOnesOwn() {
        Persona tobi = h.companion("tobi", "Tobi", 902, RosterState.ACTIVE, LITH);
        h.outWithAnn(hadrik);
        h.outWithAnn(tobi);

        assertTrue(manager.setFormation(h.ann, "spread").ok());

        assertEquals("{\"formation\":\"spread\"}", json(hadrik));
        assertEquals("{\"formation\":\"spread\"}", json(tobi));
        assertNull(json(mira), "on the bench: not told");
    }

    // ---- a little coin ---------------------------------------------------------------------------------

    @Test
    void coinForOneOutWithTheOwnerGoesIntoItsPurseAndItSaysThanks() {
        h.outWithAnn(hadrik);
        Character bot = h.bot(hadrik);
        when(h.ann.getMeso()).thenReturn(10_000);
        when(bot.canHoldMeso(2_500)).thenReturn(true);

        RosterResult result = manager.giveCoin(h.ann, hadrik, 2_500);

        assertEquals("You give Hadrik 2,500 meso.", ((RosterResult.Ok) result).line());
        verify(h.ann).gainMeso(-2_500, true);
        verify(bot).gainMeso(2_500, false);
        assertEquals(List.of(ANN + " hadrik roster.coin 2500 meso, carried"), h.store.events("roster.coin"));
        verify(h.residents).reply(hadrik, h.ann, CompanionManager.COIN_THANKS);
    }

    @Test
    void coinForOneOnTheBenchGoesIntoItsStoredPurse() {
        when(h.ann.getMeso()).thenReturn(10_000);
        h.store.purses.put(903, 100);

        RosterResult result = manager.giveCoin(h.ann, mira, 250);

        assertTrue(result.ok());
        verify(h.ann).gainMeso(-250, true);
        assertEquals(Map.of(903, 350), h.store.purses);
        assertEquals(List.of(ANN + " mira roster.coin 250 meso, stored"), h.store.events("roster.coin"));
        verify(h.residents).reply(mira, h.ann, CompanionManager.COIN_THANKS);
    }

    @Test
    void coinIsRefusedForMoreThanTheOwnerHasOrThePurseHolds() {
        when(h.ann.getMeso()).thenReturn(100);
        assertEquals("You don't have 500 meso.", RosterVoice.line(mira, manager.giveCoin(h.ann, mira, 500)));
        for (long bad : new long[]{0L, -5L, (long) Integer.MAX_VALUE + 1L}) {
            assertInstanceOf(RosterResult.Failed.class, manager.giveCoin(h.ann, mira, bad));
        }
        verify(h.ann, never()).gainMeso(anyInt(), anyBoolean());

        when(h.ann.getMeso()).thenReturn(10_000);
        h.store.purses.put(903, Integer.MAX_VALUE - 10);
        assertInstanceOf(RosterResult.Failed.class, manager.giveCoin(h.ann, mira, 100));
        verify(h.ann).gainMeso(-100, true);
        verify(h.ann).gainMeso(100, true); // it never reached the purse: back to the owner
        assertEquals(Integer.MAX_VALUE - 10, (int) h.store.purses.get(903));

        h.outWithAnn(hadrik);
        when(h.bot(hadrik).canHoldMeso(anyInt())).thenReturn(false);
        assertInstanceOf(RosterResult.Failed.class, manager.giveCoin(h.ann, hadrik, 100));
        verify(h.bot(hadrik), never()).gainMeso(anyInt(), anyBoolean());
        assertTrue(h.store.events("roster.coin").isEmpty());
    }
}
