package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentBuffStateRuntime;
import server.agents.capabilities.combat.AgentCombatBuffStateRuntime;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.capabilities.movement.AgentFormationStateRuntime;
import server.agents.capabilities.supplies.AgentPotionThresholds;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * CompanionSettingsApplier (docs/BOT_MANAGEMENT_PLAN.md P3b): every path that loads or seats a companion (a call and
 * its arrival, a swap, a login restore and the boot's, channel follow and its fallback, a HOLD rejoin, the auditor's
 * rejoin) binds it to its owner's settings, so the potion and loot seams answer for it and its support toggles are set
 * on its runtime; companions with a formation of their own take their places. The settings survive bench then call,
 * logout then login, and a restart (the store round trip). A live bench slice, an unloaded companion and one with the
 * defaults get no answer: they drink and loot as configured.
 */
class CompanionSettingsApplierTest {
    private static final int HADRIK = 901;
    private static final String CUSTOM = "{\"hp\":40,\"mp\":30,\"loot\":\"NONE\",\"buffs\":false,\"buffPotions\":true}";

    private LifecycleHarness game;
    private Persona hadrik;
    private MockedStatic<AgentMailboxRuntime> mailboxes;
    /** Every mailbox action dispatched, with the runtime it was dispatched to. */
    private final List<AgentRuntimeEntry> runtimes = new ArrayList<>();
    private final List<AgentMailboxAction<?>> actions = new ArrayList<>();

    @BeforeEach
    void wire() {
        game = new LifecycleHarness();
        mailboxes = mockStatic(AgentMailboxRuntime.class);
        mailboxes.when(() -> AgentMailboxRuntime.dispatch(any(AgentRuntimeEntry.class), any(AgentMailboxAction.class)))
                .thenAnswer(inv -> {
                    AgentRuntimeEntry runtime = inv.getArgument(0);
                    AgentMailboxAction<?> action = inv.getArgument(1);
                    runtimes.add(runtime);
                    actions.add(action);
                    return CompletableFuture.completedFuture(action.execute(runtime));
                });
    }

    @AfterEach
    void close() {
        AgentPotionThresholds.install(null);
        AgentLootPolicy.install(null);
        mailboxes.close();
        game.close();
    }

    private CompanionSettingsApplier applier() {
        return game.roster().settings();
    }

    private Persona companion(String id, String name, int characterId, RosterState state, int camp, String json) {
        Persona persona = game.companion(id, name, characterId, state, camp);
        if (json != null) {
            game.store.settings.put(FakeLivingStore.key(ANN, id), json);
        }
        return persona;
    }

    /** The seams answer for the companion with its own settings (40% / 30%, loot nothing, boss drops left). */
    private void assertAnswersWithItsOwn(int characterId) {
        assertEquals(new AgentPotionThresholds.Thresholds(0.4f, 0.3f), applier().thresholds(characterId));
        AgentLootPolicy.Policy policy = applier().policy(characterId);
        assertEquals(AgentLootPolicy.Mode.NONE, policy.mode());
        assertTrue(policy.leaveBossDrops());
    }

    private void assertNoAnswer(int characterId) {
        assertNull(applier().thresholds(characterId));
        assertNull(applier().policy(characterId));
    }

    /** The last support toggles set on the companion's runtime: buffs off, heals as default, buff potions on. */
    private void assertTogglesSetOn(int characterId) {
        AgentRuntimeEntry runtime = null;
        for (AgentRuntimeEntry candidate : runtimes) {
            if (candidate.bot().getId() == characterId) {
                runtime = candidate;
            }
        }
        assertTrue(runtime != null, "a mailbox action for " + characterId);
        assertFalse(AgentCombatBuffStateRuntime.skillBuffsEnabled(runtime));
        assertTrue(AgentCombatBuffStateRuntime.supportHealsEnabled(runtime));
        assertTrue(AgentBuffStateRuntime.enabled(runtime));
    }

    // ---- every load and seat path ------------------------------------------------------------------

    @Test
    void aCallThatSeatsItAtOnceAppliesItsSettings() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, CUSTOM);
        assertNoAnswer(HADRIK);

        assertTrue(game.roster().call(game.ann, hadrik).ok());

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertTogglesSetOn(HADRIK);
    }

    @Test
    void aTravellerRunsOnItsSettingsFromItsLoadAndTheyAreReappliedWhenItArrives() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, LITH, CUSTOM);

        game.roster().call(game.ann, hadrik);

        assertEquals(RosterState.TRAVELLING, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertEquals(1, actions.size());

        game.runTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertEquals(2, actions.size(), "her settings are re-applied at the arrival (flow 1, step 5)");
        assertTogglesSetOn(HADRIK);
    }

    @Test
    void theIncomingOneOfASwapRunsOnItsSettings() {
        Persona tobi = companion("tobi", "Tobi", 902, RosterState.ACTIVE, LITH, null);
        game.outWithAnn(tobi);
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, CUSTOM);

        assertTrue(game.roster().swap(game.ann, tobi, hadrik).ok());

        assertAnswersWithItsOwn(HADRIK);
        assertTogglesSetOn(HADRIK);
        assertNoAnswer(902); // the defaults: as configured, and benched now
    }

    @Test
    void aLoginRestoreAppliesThemAgainAfterTheLogout() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, CUSTOM);
        game.roster().call(game.ann, hadrik);
        assertAnswersWithItsOwn(HADRIK);

        game.roster().onOwnerLogout(game.ann, null);
        game.offline.add(HADRIK);

        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertNoAnswer(HADRIK); // logged off with its owner: nothing runs on it

        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertEquals(2, actions.size(), "set on the fresh runtime the login loaded");
    }

    @Test
    void channelFollowAppliesThemOnTheNewChannel() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.ACTIVE, LITH, CUSTOM);
        game.outWithAnn(hadrik);
        game.companionOnChannel(hadrik, 1);
        game.roster();
        assertNoAnswer(HADRIK); // loaded before this session knew it (no load path ran)

        changeChannel(2);
        game.timers.stream().filter(timer -> timer.delayMs() < CompanionChannelFollow.FALLBACK_MS).toList()
                .forEach(timer -> timer.task().run());

        assertEquals(2, game.companionChannel(hadrik));
        assertAnswersWithItsOwn(HADRIK);
        assertTogglesSetOn(HADRIK);
    }

    @Test
    void channelFollowsFallbackAppliesThem() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.ACTIVE, LITH, CUSTOM);
        game.outWithAnn(hadrik);
        game.companionOnChannel(hadrik, 1);
        game.roster();
        when(game.budget.admitReplacing(any(), any(client.Character.class)))
                .thenReturn(CompanionBudget.Admission.deny("test: no load now"));
        changeChannel(2);
        game.timers.stream().filter(timer -> timer.delayMs() < CompanionChannelFollow.FALLBACK_MS).toList()
                .forEach(timer -> timer.task().run());
        assertNoAnswer(HADRIK);

        new ArrayList<>(game.timers).stream().filter(timer -> timer.delayMs() == CompanionChannelFollow.FALLBACK_MS)
                .forEach(timer -> timer.task().run());

        assertEquals(2, game.companionChannel(hadrik));
        assertAnswersWithItsOwn(HADRIK);
        assertTogglesSetOn(HADRIK);
    }

    @Test
    void aHoldRejoinAppliesThem() {
        game.moveAnn(KERNING, 1500, 120);
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.ACTIVE, LITH, CUSTOM);
        game.outWithAnn(hadrik);
        game.roster().hold(game.ann, hadrik, KERNING, new Point(1450, 120));
        assertNoAnswer(HADRIK);

        game.moveAnn(KERNING, 1200, 120); // out of the run
        game.roster().ownerEnteredMap(game.ann, game.map(KERNING));

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertTogglesSetOn(HADRIK);
    }

    @Test
    void theAuditorsRejoinWhereItStandsAppliesThem() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.ACTIVE, LITH, CUSTOM);
        game.outWithAnn(hadrik);
        game.companionOnChannel(hadrik, 1);
        game.members.remove((Integer) HADRIK); // following Ann on her map, out of her party only
        game.roster();

        game.roster().reseat(game.ann, hadrik);

        assertTrue(game.members.contains(HADRIK));
        assertAnswersWithItsOwn(HADRIK);
        assertTogglesSetOn(HADRIK);
    }

    @Test
    void theBootsRestoreAtTheLoginAppliesThemFromTheStore() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.ACTIVE, LITH, CUSTOM);
        game.roster().reconcileAtBoot();
        assertEquals(RosterState.PARKED, game.row(hadrik).state());

        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertTogglesSetOn(HADRIK);
    }

    // ---- the settings survive ----------------------------------------------------------------------

    @Test
    void theySurviveBenchThenCall() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, null);
        game.roster().call(game.ann, hadrik);
        new CompanionManager(game.world).setPotionThresholds(game.ann, hadrik, 40, 30);
        new CompanionManager(game.world).setLootPolicy(game.ann, hadrik, AgentLootPolicy.Mode.NONE);
        assertAnswersWithItsOwn(HADRIK);

        game.roster().bench(game.ann, hadrik);
        game.runTimers(); // it walks out and goes

        assertEquals(RosterState.BENCHED, game.row(hadrik).state());
        assertNoAnswer(HADRIK);

        game.roster().call(game.ann, hadrik);

        assertAnswersWithItsOwn(HADRIK);
    }

    @Test
    void theySurviveARestartThroughTheStore() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, null);
        game.roster().call(game.ann, hadrik);
        CompanionManager manager = new CompanionManager(game.world);
        manager.setPotionThresholds(game.ann, hadrik, 40, 30);
        manager.setLootPolicy(game.ann, hadrik, AgentLootPolicy.Mode.NONE);
        manager.setSupport(game.ann, hadrik, CompanionSettings.Support.BUFFS, false);
        manager.setSupport(game.ann, hadrik, CompanionSettings.Support.BUFF_POTIONS, true);
        assertEquals(CUSTOM, game.store.settings.get(FakeLivingStore.key(ANN, "hadrik")), "settings_json");

        // the restart: nothing is loaded, no party survived, and a new roster (with an empty cache) boots
        game.loaded.clear();
        game.members.retainAll(List.of(ANN));
        runtimes.clear();
        actions.clear();
        RosterService rebooted = new RosterService(game.world);
        when(game.world.roster()).thenReturn(rebooted);
        rebooted.reconcileAtBoot();
        assertNull(rebooted.settings().thresholds(HADRIK));

        rebooted.onOwnerLogin(game.ann);
        game.runDueTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertTogglesSetOn(HADRIK);
    }

    // ---- who gets no answer ------------------------------------------------------------------------

    @Test
    void aLiveBenchSliceAndACompanionWithTheDefaultsGetNoAnswer() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, CUSTOM);
        Persona tobi = companion("tobi", "Tobi", 902, RosterState.BENCHED, HENESYS_FIELD, "{\"leaveBossDrops\":false}");
        Persona jinx = companion("jinx", "Jinx", 903, RosterState.BENCHED, HENESYS_FIELD, null);
        game.roster().call(game.ann, hadrik);
        game.roster().call(game.ann, tobi);
        game.roster().call(game.ann, jinx);

        assertNoAnswer(902); // loot everything, boss drops too, potions as configured: exactly today's behaviour
        assertNull(applier().thresholds(903));
        AgentLootPolicy.Policy jinxs = applier().policy(903);
        assertEquals(AgentLootPolicy.Mode.ALL, jinxs.mode(), "the defaults loot everything...");
        assertTrue(jinxs.leaveBossDrops(), "...but a boss's drops (NO_BOSS)");
        assertEquals(List.of(HADRIK), runtimes.stream().map(runtime -> runtime.bot().getId()).distinct().toList(),
                "default toggles are left to the Agent");

        game.selfLed.add(HADRIK); // a live bench slice leads itself
        assertNoAnswer(HADRIK);
        game.selfLed.remove(HADRIK);
        assertAnswersWithItsOwn(HADRIK);
    }

    @Test
    void theInstalledSeamsAnswerFromTheSameMemory() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, CUSTOM);
        game.roster().call(game.ann, hadrik);

        applier().install();

        assertEquals(0.4f, AgentPotionThresholds.hp(HADRIK, 0.7f));
        assertEquals(0.3f, AgentPotionThresholds.mp(HADRIK, 0.5f));
        assertEquals(0.7f, AgentPotionThresholds.hp(999, 0.7f), "any other Agent: as configured");
        client.Character bot = game.bot(hadrik);
        assertFalse(AgentLootPolicy.permits(bot, mock(server.maps.MapItem.class)));

        CompanionSettingsApplier.uninstall();
        assertEquals(0.7f, AgentPotionThresholds.hp(HADRIK, 0.7f));
        assertTrue(AgentLootPolicy.permits(bot, mock(server.maps.MapItem.class)));
    }

    @Test
    void questOnlyAsksTheOwnersStartedQuestsAndBag() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, "{\"loot\":\"QUEST_ONLY\"}");
        game.roster().call(game.ann, hadrik);
        client.QuestStatus started = mock(client.QuestStatus.class);
        server.quest.Quest quest = mock(server.quest.Quest.class);
        when(started.getQuest()).thenReturn(quest);
        when(quest.getCompleteItemAmountNeeded(anyInt())).thenReturn(Integer.MAX_VALUE);
        when(quest.getCompleteItemAmountNeeded(4000019)).thenReturn(20);
        when(game.ann.getStartedQuests()).thenReturn(List.of(started));
        when(game.ann.getItemQuantity(4000019, false)).thenReturn(12);

        AgentLootPolicy.Policy policy = applier().policy(HADRIK);

        assertEquals(AgentLootPolicy.Mode.QUEST_ONLY, policy.mode());
        assertTrue(policy.questNeeds().test(4000019), "8 more to go");
        assertFalse(policy.questNeeds().test(4000000), "no quest of hers needs it");
        when(game.ann.getItemQuantity(4000019, false)).thenReturn(20);
        assertFalse(policy.questNeeds().test(4000019), "she has them all");
        game.annOnline = false;
        when(game.ann.getItemQuantity(4000019, false)).thenReturn(0);
        assertFalse(policy.questNeeds().test(4000019), "an owner offline needs nothing picked up");
    }

    // ---- formation ---------------------------------------------------------------------------------

    @Test
    void companionsWithAFormationOfTheirOwnTakeTheirPlacesAfterEveryLoad() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, "{\"formation\":\"left\"}");
        Persona tobi = companion("tobi", "Tobi", 902, RosterState.BENCHED, HENESYS_FIELD, "{\"formation\":\"left\"}");
        Persona jinx = companion("jinx", "Jinx", 903, RosterState.BENCHED, HENESYS_FIELD, null);
        List<AgentRuntimeEntry> crew = new ArrayList<>();
        for (Persona persona : List.of(hadrik, tobi, jinx)) {
            crew.add(new AgentRuntimeEntry(game.bot(persona), game.ann, null));
        }
        game.registry.when(() -> AgentRuntimeRegistry.entriesForLeader(ANN)).thenReturn(crew);
        AgentFormationStateRuntime.setFollowOffsetX(crew.get(2), 12345); // the crew's place for Jinx

        game.roster().call(game.ann, hadrik);
        game.roster().call(game.ann, tobi);
        game.roster().call(game.ann, jinx);

        int px = AgentRuntimeConfig.cfg.FOLLOW_STAGGER;
        assertEquals(-px, AgentFormationStateRuntime.followOffsetX(crew.get(0)), "first on the left");
        assertEquals(-2 * px, AgentFormationStateRuntime.followOffsetX(crew.get(1)), "second on the left");
        assertEquals(12345, AgentFormationStateRuntime.followOffsetX(crew.get(2)), "no formation of its own: untouched");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Client.changeChannel's hook, the old session's logout, then the login on the new channel. */
    private void changeChannel(int channel) {
        game.roster().ownerChangingChannel(game.ann, channel);
        game.roster().onOwnerLogout(game.ann, null);
        game.annOnChannel(channel);
        game.roster().onOwnerLogin(game.ann);
    }
}
