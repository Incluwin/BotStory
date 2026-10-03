package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentBuffStateRuntime;
import server.agents.capabilities.combat.AgentCombatBuffStateRuntime;
import server.agents.capabilities.dialogue.AgentChatToggleHook;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.agents.capabilities.movement.AgentFormationHook;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.capabilities.movement.AgentFormationStateRuntime;
import server.agents.capabilities.supplies.AgentPotionService;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * CompanionSettingsApplier (docs/BOT_MANAGEMENT_PLAN.md P3b): every path that loads or seats a companion (a call and
 * its arrival, a swap, a login restore and the boot's, channel follow and its fallback, a HOLD rejoin, the auditor's
 * rejoin) binds it to its owner's settings, so the potion and loot seams answer for it, its support toggles are set on
 * its runtime and its potion alerts are rebuilt; the crew's formation is put back when the Agent layer lost it, and
 * companions with a formation of their own take their places. The settings survive bench then call, logout then login,
 * and a restart (the store round trip), and a release forgets them. A live bench slice drinks by its own percentages but
 * loots as configured; one with the defaults gets no answer. What the owner orders in chat (a support toggle, the
 * crew's formation) is kept the same way. QUEST_ONLY counts both bags and reads the quests at most once a second.
 */
class CompanionSettingsApplierTest {
    private static final int HADRIK = 901;
    private static final String CUSTOM = "{\"hp\":40,\"mp\":30,\"loot\":\"NONE\",\"buffs\":false,\"buffPotions\":false}";

    private LifecycleHarness game;
    private Persona hadrik;
    private MockedStatic<AgentMailboxRuntime> mailboxes;
    private MockedStatic<AgentPotionService> potions;
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
        potions = mockStatic(AgentPotionService.class); // the potion alerts a bound companion's mailbox rebuilds
    }

    @AfterEach
    void close() {
        CompanionSettingsApplier.uninstall();
        AgentFormationService.formationsByLeaderId().remove(ANN); // global Agent state
        potions.close();
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

    /** The last support toggles set on the companion's runtime: buffs off, heals as default, buff potions off. */
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
        assertFalse(AgentBuffStateRuntime.enabled(runtime));
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
    void aCompanionOutWithItsOwnerKeepsNoJourneyReserveAndOneOnTheBenchDoes() {
        // seen live: companions carrying 1-2k meso sat below the 500 + 50/level reserve, so every shop visit bought nothing
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, null);
        assertFalse(applier().exempt(HADRIK), "not loaded: nothing to answer for");

        assertTrue(game.roster().call(game.ann, hadrik).ok());
        applier().install();

        assertTrue(applier().exempt(HADRIK), "out with its owner: its recall and follow cost it nothing");
        assertTrue(server.agents.capabilities.supplies.AgentWalletReserve.exempt(HADRIK), "installed as the seam's rule");
        game.selfLed.add(HADRIK); // a live bench slice leads itself
        assertFalse(applier().exempt(HADRIK), "self-led on the bench: the reserve applies like any Agent");
    }

    @Test
    void aCompanionHeldOutsideARunStaysExemptAfterItLeftTheParty() {
        // its readiness restock while its owner is inside spends its whole purse too: its runtime is still the owner's
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, null);
        assertTrue(game.roster().call(game.ann, hadrik).ok());
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertTrue(applier().exempt(HADRIK));

        assertTrue(game.roster().hold(game.ann, hadrik, HENESYS_FIELD, new Point(1450, 120)).ok());

        assertEquals(RosterState.HOLD, game.row(hadrik).state());
        assertTrue(game.calls.contains("leave Hadrik"), game.calls.toString());
        assertTrue(applier().exempt(HADRIK), "held for its owner: no journey reserve");
    }

    @Test
    void aTravellerRunsOnItsSettingsFromItsLoadAndTheyAreReappliedWhenItArrives() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, LITH, CUSTOM);

        game.roster().call(game.ann, hadrik);

        assertEquals(RosterState.TRAVELLING, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertEquals(2, actions.size(), "its support toggles and its potion alerts");

        game.runTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertEquals(4, actions.size(), "her settings are re-applied at the arrival (flow 1, step 5)");
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
        assertNull(applier().policy(HADRIK)); // logged off with its owner: nothing loots on it

        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertAnswersWithItsOwn(HADRIK);
        assertEquals(4, actions.size(), "set on the fresh runtime the login loaded (toggles, potion alerts)");
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
        assertNull(applier().policy(HADRIK)); // not out with her

        game.roster().call(game.ann, hadrik);

        assertAnswersWithItsOwn(HADRIK);
    }

    @Test
    void theySurviveARestartThroughTheStore() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, null);
        when(game.bot(hadrik).getJob()).thenReturn(client.Job.FIGHTER); // Rage: party buffs to turn off
        game.roster().call(game.ann, hadrik);
        CompanionManager manager = new CompanionManager(game.world);
        manager.setPotionThresholds(game.ann, hadrik, 40, 30);
        manager.setLootPolicy(game.ann, hadrik, AgentLootPolicy.Mode.NONE);
        manager.setSupport(game.ann, hadrik, CompanionSettings.Support.BUFFS, false);
        manager.setSupport(game.ann, hadrik, CompanionSettings.Support.BUFF_POTIONS, false);
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
    void aLiveBenchSliceDrinksByItsOwnPercentagesButLootsAsConfiguredAndTheDefaultsGetNoAnswer() {
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
        assertEquals(new AgentPotionThresholds.Thresholds(0.4f, 0.3f), applier().thresholds(HADRIK), "its own %");
        assertNull(applier().policy(HADRIK), "it farms for the haul: the loot rules decide");
        game.selfLed.remove(HADRIK);
        assertAnswersWithItsOwn(HADRIK);
    }

    @Test
    void aBenchSliceLoadIsBoundToItsOwnersSettingsAndItsPotionAlertsRebuilt() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, CUSTOM);
        game.roster();
        game.loaded.add(HADRIK); // BenchSessions.start loaded it at its camp, leading itself
        game.selfLed.add(HADRIK);

        applier().bind(game.bot(hadrik), ANN, "hadrik");

        assertEquals(new AgentPotionThresholds.Thresholds(0.4f, 0.3f), applier().thresholds(HADRIK));
        assertNull(applier().policy(HADRIK));
        potions.verify(() -> AgentPotionService.setupAutopotForBot(game.bot(hadrik)));
        assertEquals(1, actions.size(), "only its potion alerts: its support stays the Agent's on a slice");
    }

    @Test
    void everyLoadRebuildsItsPotionAlertsFromItsOwnPercentagesAndAChangeAtOnce() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, CUSTOM);
        Persona tobi = companion("tobi", "Tobi", 902, RosterState.BENCHED, HENESYS_FIELD, null);

        game.roster().call(game.ann, hadrik);
        game.roster().call(game.ann, tobi);

        // its mode start may have set the configured alerts before it was bound: rebuilt now, not 45 s later
        potions.verify(() -> AgentPotionService.setupAutopotForBot(game.bot(hadrik)));
        potions.verify(() -> AgentPotionService.setupAutopotForBot(game.bot(tobi)), never()); // never chosen: as is

        new CompanionManager(game.world).setPotionThresholds(game.ann, tobi, 55, 45);

        potions.verify(() -> AgentPotionService.setupAutopotForBot(game.bot(tobi)));
        assertEquals(new AgentPotionThresholds.Thresholds(0.55f, 0.45f), applier().thresholds(902));
    }

    // ---- a release forgets them --------------------------------------------------------------------

    @Test
    void aReleaseForgetsTheSettingsSoARecruitAgainStartsFromTheDefaults() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, null);
        game.roster().call(game.ann, hadrik);
        CompanionManager manager = new CompanionManager(game.world);
        manager.setPotionThresholds(game.ann, hadrik, 30, 30);
        manager.setLootPolicy(game.ann, hadrik, AgentLootPolicy.Mode.NONE);
        assertEquals(AgentLootPolicy.Mode.NONE, applier().policy(HADRIK).mode());

        when(game.world.contracts()).thenReturn(mock(server.living.bond.ContractService.class));
        game.roster().release(ANN, hadrik, "test: parted ways");
        game.runTimers();

        assertNull(game.row(hadrik), "the row and its settings_json are gone");
        assertNoAnswer(HADRIK);
        // recruited again after the contract's cooldown: a fresh row, settings_json NULL
        game.store.seed(new server.living.store.LivingStore.RosterEntry(ANN, "hadrik", RosterState.BENCHED,
                server.living.store.LivingStore.Assignment.TRAIN, 0, 0, HENESYS_FIELD, 0L, 0L, 0L, 1_000L, 1_000L, 1_000L,
                0, null));
        assertEquals(CompanionSettings.defaults(), applier().settings(ANN, "hadrik"), "not the old settings from memory");
        game.roster().call(game.ann, hadrik);
        assertNull(applier().thresholds(HADRIK));
        assertEquals(AgentLootPolicy.Mode.ALL, applier().policy(HADRIK).mode(), "the defaults (boss drops left)");
    }

    // ---- a store that fails --------------------------------------------------------------------------

    @Test
    void settingsThatCannotBeReadAreNeverKeptAsTheDefaults() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, CUSTOM);
        game.roster();
        String key = FakeLivingStore.key(ANN, "hadrik");
        game.store.unreadable.add(key);

        assertEquals(CompanionSettings.defaults(), applier().settings(ANN, "hadrik"), "shown as the defaults for now");
        assertFalse(applier().change(ANN, "hadrik", own -> own.withLoot(AgentLootPolicy.Mode.ALL)),
                "no change computed from defaults over what is stored");
        assertEquals(CUSTOM, game.store.settings.get(key), "nothing written over the stored settings");

        game.store.unreadable.remove(key);
        assertEquals(AgentLootPolicy.Mode.NONE, applier().settings(ANN, "hadrik").loot(), "read again: its own");

        game.store.unwritable.add(key);
        assertFalse(applier().change(ANN, "hadrik", own -> own.withLoot(AgentLootPolicy.Mode.ALL)));
        assertEquals(AgentLootPolicy.Mode.NONE, applier().settings(ANN, "hadrik").loot(), "memory kept as stored");
    }

    // ---- what the owner orders in chat --------------------------------------------------------------

    @Test
    void aSupportToggleSaidInChatIsKeptAsItsSettingOnlyFromItsOwner() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, null);
        game.roster().call(game.ann, hadrik);
        applier().install();

        AgentChatToggleHook.toggled(HADRIK, AgentChatToggleHook.Toggle.SUPPORT_HEALS, false); // "heals off"

        assertFalse(applier().settings(ANN, "hadrik").heals(), "the card now shows what it does");
        assertEquals("{\"heals\":false}", game.store.settings.get(FakeLivingStore.key(ANN, "hadrik")));

        game.selfLed.add(HADRIK); // led by someone else now (a slice, a friend's run)
        AgentChatToggleHook.toggled(HADRIK, AgentChatToggleHook.Toggle.SKILL_BUFFS, false);
        assertTrue(applier().settings(ANN, "hadrik").buffs(), "not its owner's order");
        AgentChatToggleHook.toggled(999, AgentChatToggleHook.Toggle.SKILL_BUFFS, false); // an Agent nobody owns
        assertEquals(1, game.store.events("companion.settings").size());
    }

    @Test
    void aFormationOrderToTheCrewIsKeptAndEveryoneItPlacedKeepsToIt() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, "{\"formation\":\"left\"}");
        Persona mira = companion("mira", "Mira", 903, RosterState.BENCHED, LITH, "{\"formation\":\"right\"}");
        List<AgentRuntimeEntry> crew = List.of(new AgentRuntimeEntry(game.bot(hadrik), game.ann, null));
        game.registry.when(() -> AgentRuntimeRegistry.entriesForLeader(ANN)).thenReturn(crew);
        game.roster().call(game.ann, hadrik);
        applier().settings(ANN, "mira"); // on the bench: not placed by the order
        applier().install();
        AgentFormationService.FormationState spread =
                new AgentFormationService.FormationState(AgentFormationService.FormationType.SPREAD, 80, 0);

        AgentFormationHook.formationSet(game.ann, spread); // "formation spread 80" in party chat

        assertEquals("SPREAD 80 0", game.store.crews.get(ANN), "kept for the crew (living_crew)");
        assertEquals(spread, applier().crewFormation(ANN));
        assertNull(applier().settings(ANN, "hadrik").formation(), "placed by it: with the others now");
        assertEquals("right", applier().settings(ANN, "mira").formation(), "not out with her: kept");

        new CompanionManager(game.world).setFormation(game.ann, hadrik, "left");
        AgentFormationHook.formationSet(game.ann, new AgentFormationService.FormationState(
                AgentFormationService.FormationType.SPREAD, 80, 150)); // "formation snap 150": nobody moved
        assertEquals("SPREAD 80 150", game.store.crews.get(ANN));
        assertEquals("left", applier().settings(ANN, "hadrik").formation(), "a snap range places nobody");

        AgentFormationHook.formationSet(mock(client.Character.class), spread); // a leader with no companions
        assertEquals(1, game.store.crews.size());
    }

    @Test
    void theCrewsFormationIsPutBackWhenTheAgentLayerLostIt() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, null);
        Persona tobi = companion("tobi", "Tobi", 902, RosterState.BENCHED, HENESYS_FIELD, "{\"formation\":\"right\"}");
        List<AgentRuntimeEntry> crew = List.of(new AgentRuntimeEntry(game.bot(hadrik), game.ann, null),
                new AgentRuntimeEntry(game.bot(tobi), game.ann, null));
        game.registry.when(() -> AgentRuntimeRegistry.entriesForLeader(ANN)).thenReturn(crew);
        game.store.crews.put(ANN, "LEFT 50 0"); // set before a relog or a restart
        AgentFormationService.formationsByLeaderId().remove(ANN); // the Agent layer forgot it with the cohort

        game.roster().call(game.ann, hadrik);
        game.roster().call(game.ann, tobi);

        assertEquals(new AgentFormationService.FormationState(AgentFormationService.FormationType.LEFT, 50, 0),
                AgentFormationService.formationsByLeaderId().get(ANN));
        assertEquals(-50, AgentFormationStateRuntime.followOffsetX(crew.get(0)), "with the others: the crew's place");
        assertTrue(AgentFormationStateRuntime.followOffsetX(crew.get(1)) > 0, "its own formation (right) on top");
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
        when(quest.getCompleteItemRequirements()).thenReturn(Map.of(4000019, 20, 4000020, 0));
        when(game.ann.getStartedQuests()).thenReturn(List.of(started));
        when(game.ann.getItemQuantity(4000019, false)).thenReturn(12);
        client.Character bot = game.bot(hadrik);

        AgentLootPolicy.Policy policy = applier().policy(HADRIK);

        assertEquals(AgentLootPolicy.Mode.QUEST_ONLY, policy.mode());
        assertTrue(policy.questNeeds().test(bot, 4000019), "8 more to go");
        assertFalse(policy.questNeeds().test(bot, 4000000), "no quest of hers needs it");
        assertFalse(policy.questNeeds().test(bot, 4000020), "one that must not be carried is no need");
        when(game.ann.getItemQuantity(4000019, false)).thenReturn(20);
        assertFalse(policy.questNeeds().test(bot, 4000019), "she has them all");
        game.annOnline = false;
        when(game.ann.getItemQuantity(4000019, false)).thenReturn(0);
        assertFalse(policy.questNeeds().test(bot, 4000019), "an owner offline needs nothing picked up");
    }

    @Test
    void questOnlyCountsWhatTheCompanionAlreadyCarriesToo() {
        hadrik = companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, HENESYS_FIELD, "{\"loot\":\"QUEST_ONLY\"}");
        game.roster().call(game.ann, hadrik);
        client.QuestStatus started = mock(client.QuestStatus.class);
        server.quest.Quest quest = mock(server.quest.Quest.class);
        when(started.getQuest()).thenReturn(quest);
        when(quest.getCompleteItemRequirements()).thenReturn(Map.of(4000019, 50)); // 50 Tree Branches
        when(game.ann.getStartedQuests()).thenReturn(List.of(started));
        when(game.ann.getItemQuantity(4000019, false)).thenReturn(10);
        client.Character bot = game.bot(hadrik);
        when(bot.getItemQuantity(4000019, false)).thenReturn(39);
        AgentLootPolicy.Policy policy = applier().policy(HADRIK);

        assertTrue(policy.questNeeds().test(bot, 4000019), "49 between them: one more");
        when(bot.getItemQuantity(4000019, false)).thenReturn(40);
        assertFalse(policy.questNeeds().test(bot, 4000019), "50 between them: the quest has what it needs");
        when(bot.getItemQuantity(4000019, false)).thenReturn(50);
        when(game.ann.getItemQuantity(4000019, false)).thenReturn(0);
        assertFalse(policy.questNeeds().test(bot, 4000019), "all in the companion's bag counts as well");

        for (int drop = 0; drop < 20; drop++) {
            policy.questNeeds().test(bot, 4000019); // every drop on the map, every tick
        }
        verify(game.ann, times(1)).getStartedQuests(); // read once, then kept for a second
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
