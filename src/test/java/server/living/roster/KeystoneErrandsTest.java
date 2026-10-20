package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.config.Content.Persona;
import server.living.roster.KeystoneErrands.Progress;
import server.living.roster.KeystoneErrands.Step;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.KeystoneErrands.CAVERN_OF_FORTITUDE_MAP_ID;
import static server.living.roster.KeystoneErrands.CAVERN_OF_PAIN_MAP_ID;
import static server.living.roster.KeystoneErrands.FIREBRAND_MOB_ID;
import static server.living.roster.KeystoneErrands.JACK_NPC_ID;
import static server.living.roster.KeystoneErrands.LUKAN_NPC_ID;
import static server.living.roster.KeystoneErrands.NIGHTSHADOW_MOB_ID;
import static server.living.roster.KeystoneErrands.PHANTOM_ROAD_MAP_ID;
import static server.living.roster.KeystoneErrands.RISEN_FIREBRAND_MOB_ID;
import static server.living.roster.KeystoneErrands.STORMBREAKER_BADGE_ITEM_ID;
import static server.living.roster.KeystoneErrands.STORMBREAKER_MOB_ID;
import static server.living.roster.KeystoneErrands.STORMHALL_MAP_ID;
import static server.living.roster.KeystoneErrands.WINDRAIDER_MOB_ID;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Companions earn their own Crimsonwood Keystone (2026-10-19, docs/CWKPQ_COMPANION_PLAN.md A3): asked by its owner, a
 * companion does Lukan's and Jack's quests and their hunts by itself, live when it is loaded and on the ledger at its
 * own measured rates in between, and comes back with the keystone.
 */
class KeystoneErrandsTest {
    private static final int THANE = 905;
    private static final Progress FRESH = progress(0, 0, 0, 0, 0, 0, 0, false);

    private final LifecycleHarness game = new LifecycleHarness();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicReference<Progress> log = new AtomicReference<>(FRESH);
    private final List<Step> acts = new ArrayList<>();
    private final List<Map<Integer, Integer>> credits = new ArrayList<>();
    private final List<Set<Integer>> hunts = new ArrayList<>();
    private KeystoneErrands errands;
    private Persona thane;

    @BeforeEach
    void thaneOutWithAnn() {
        thane = game.companionAs("CRUSADER", "thane", "Thane", THANE, RosterState.ACTIVE, LITH);
        game.outWithAnn(thane);
        game.roster();
        game.store.levels.put(THANE, 90);
        Character bot = game.bot(thane);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getCurrentMaxHp()).thenReturn(5000);
        errands = new KeystoneErrands(game.world, clock::get);
        errands.goesAlong = (runtime, ownerId) -> true; // following Ann
        errands.progress = character -> log.get();
        errands.hands = new KeystoneErrands.Hands() {
            @Override
            public boolean act(AgentRuntimeEntry runtime, Step step) {
                acts.add(step);
                log.set(after(log.get(), step));
                return true;
            }

            @Override
            public void credit(AgentRuntimeEntry runtime, Map<Integer, Integer> whole) {
                credits.add(whole);
                Progress p = log.get();
                Map<Integer, Integer> kills = new HashMap<>(p.kills());
                whole.forEach((mark, count) -> {
                    if (mark != STORMBREAKER_BADGE_ITEM_ID) {
                        kills.merge(mark, count, Integer::sum);
                    }
                });
                log.set(new Progress(p.brewingStorm(), p.stemmingTheTide(), p.stormingTheCastle(),
                        p.badges() + whole.getOrDefault(STORMBREAKER_BADGE_ITEM_ID, 0), kills, p.keystone()));
            }

            @Override
            public boolean hunt(AgentRuntimeEntry runtime, Character agent, String requestId, int mapId,
                                Set<Integer> targets, long exitAtMs) {
                hunts.add(targets);
                return true;
            }
        };
        game.interaction.when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> new AgentRuntimeEntry(inv.getArgument(0), inv.getArgument(0), null));
    }

    @AfterEach
    void close() {
        game.close();
    }

    // ---- the chain --------------------------------------------------------------------------------------

    @Test
    void theStepIsReadFromTheQuestLogAndTheBags() {
        assertEquals(Step.START_BREWING_STORM, KeystoneErrands.next(FRESH));
        assertEquals(Step.HUNT_BADGES, KeystoneErrands.next(progress(1, 0, 0, 9, 0, 0, 0, false)));
        assertEquals(Step.FINISH_BREWING_STORM, KeystoneErrands.next(progress(1, 0, 0, 10, 0, 0, 0, false)));
        assertEquals(Step.START_STEMMING_THE_TIDE, KeystoneErrands.next(progress(2, 0, 0, 0, 0, 0, 0, false)));
        assertEquals(Step.START_STORMING_THE_CASTLE, KeystoneErrands.next(progress(2, 1, 0, 0, 0, 0, 0, false)));
        assertEquals(Step.HUNT_CASTLE, KeystoneErrands.next(progress(2, 1, 1, 0, 75, 74, 75, false)));
        assertEquals(Step.FINISH_STORMING_THE_CASTLE, KeystoneErrands.next(progress(2, 1, 1, 0, 75, 75, 75, false)));
        assertEquals(Step.DONE, KeystoneErrands.next(progress(2, 1, 2, 0, 0, 0, 0, true)));
        assertEquals(Step.REISSUE_KEYSTONE, KeystoneErrands.next(progress(2, 1, 2, 0, 0, 0, 0, false)), "lost it");
        assertEquals(Step.LOCKED_OUT, KeystoneErrands.next(progress(2, 2, 0, 0, 0, 0, 0, false)),
                "Stemming the Tide finished first: Storming the Castle can never start");
        assertEquals(Step.DONE, KeystoneErrands.next(progress(0, 0, 0, 0, 0, 0, 0, true)), "a keystone is a keystone");
    }

    @Test
    void aHuntFightsFirebrandsInTheirFirstFormForTheRisenOnesAndOnlyWhatItStillNeeds() {
        Progress windraidersDone = progress(2, 1, 1, 0, 75, 10, 3, false);

        assertEquals(Set.of(FIREBRAND_MOB_ID, RISEN_FIREBRAND_MOB_ID, NIGHTSHADOW_MOB_ID),
                KeystoneErrands.targets(Step.HUNT_CASTLE, windraidersDone));
        assertEquals(Map.of(RISEN_FIREBRAND_MOB_ID, 65, NIGHTSHADOW_MOB_ID, 72),
                KeystoneErrands.wanted(Step.HUNT_CASTLE, windraidersDone));
        assertEquals(Set.of(STORMBREAKER_MOB_ID), KeystoneErrands.targets(Step.HUNT_BADGES, progress(1, 0, 0, 3, 0, 0, 0, false)));
    }

    // ---- the owner's ask ----------------------------------------------------------------------------------

    @Test
    void askedItSaysSoWalksOutKeepingItsSeatAndHeadsForLukan() {
        String said = errands.ask(game.ann, thane);

        assertEquals("Thane leaves to earn a Crimsonwood Keystone, keeping the party slot.", said);
        verify(game.residents).reply(thane, game.ann, KeystoneErrandLines.GO.get(ANN % KeystoneErrandLines.GO.size()));
        assertEquals(RosterState.AWAY, game.row(thane).state());
        assertEquals(CompanionSlots.Kind.AWAY, game.slots.reservation("thane").kind(), "its seat is kept");
        assertTrue(game.store.flags.containsKey("companion.keystone-errand.10.thane"));
        assertTrue(errands.underway("thane"));
        assertEquals("gone to see Lukan on Phantom Road", errands.doing("thane"));

        RosterEntry away = game.row(thane);
        assertSame(errands.talk, errands.formFor(away));
        assertEquals(0, errands.talk.mapFor(away, thane, THANE), "on its way");
        clock.addAndGet(roadTo(PHANTOM_ROAD_MAP_ID));
        assertEquals(PHANTOM_ROAD_MAP_ID, errands.talk.mapFor(away, thane, THANE), "at Lukan's");
        assertEquals(0, errands.hunt.mapFor(away, thane, THANE), "a talk step, not a hunt");
    }

    @Test
    void belowItsLevelHoldingOneOrLockedOutItSaysSoAndStays() {
        game.store.levels.put(THANE, 80);
        errands.ask(game.ann, thane);
        verify(game.residents).reply(thane, game.ann, KeystoneErrandLines.LOW.get(ANN % KeystoneErrandLines.LOW.size()));

        game.store.levels.put(THANE, 90);
        log.set(progress(2, 1, 2, 0, 0, 0, 0, true));
        assertEquals("Thane already holds a Crimsonwood Keystone.", errands.ask(game.ann, thane));

        log.set(progress(2, 2, 0, 0, 0, 0, 0, false));
        errands.ask(game.ann, thane);
        verify(game.residents).reply(thane, game.ann, KeystoneErrandLines.LOCKED.get(0));

        assertEquals(RosterState.ACTIVE, game.row(thane).state());
        assertNull(errands.errand("thane"));
        assertFalse(game.store.flags.containsKey("companion.keystone-errand.10.thane"));
    }

    @Test
    void withTheKillSwitchOffNobodyGoesAndOneOutIsCalledBack() {
        errands.ask(game.ann, thane);
        game.runTimers();
        game.config.COMPANION_KEYSTONE_ERRANDS_ENABLED = false;

        assertNull(errands.formFor(game.row(thane)));
        errands.sweep();
        game.runTimers();

        assertEquals(RosterState.ACTIVE, game.row(thane).state());
        assertFalse(game.store.flags.containsKey("companion.keystone-errand.10.thane"));
        assertTrue(errands.ask(game.ann, thane).contains("can't go"));
    }

    // ---- talk steps ----------------------------------------------------------------------------------------

    @Test
    void atLukanItStartsTheBrewingStormAsAPlayerDoesAndSetsOffForStormhall() {
        BenchDutyDispatcher.Slice slice = atLukan();
        game.runDueTimers();
        assertTrue(acts.isEmpty(), "it stands with him a moment first");
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();

        assertEquals(List.of(Step.START_BREWING_STORM), acts);
        KeystoneErrands.Errand errand = errands.errand("thane");
        assertEquals(Step.HUNT_BADGES, errand.step);
        assertEquals("on to its next step", errands.talk.check(slice, game.bot(thane), game.row(thane), clock.get()));
        RosterEntry away = game.row(thane);
        assertSame(errands.hunt, errands.formFor(away));
        assertEquals(0, errands.hunt.mapFor(away, thane, THANE));
        clock.addAndGet(RecallTravel.etaMs(PHANTOM_ROAD_MAP_ID, STORMHALL_MAP_ID, game.config.RECALL_MAX_ETA_MS));
        assertEquals(STORMHALL_MAP_ID, errands.hunt.mapFor(away, thane, THANE));
        assertEquals("hunting Stormbreakers for Lukan", errands.doing("thane"));
    }

    @Test
    void twoWordsWithLukanInARowAreSaidWithoutLeavingHim() {
        log.set(progress(1, 1, 0, 10, 0, 0, 0, false)); // its badges, and Jack's quest already started
        atLukan();
        game.runDueTimers(); // it walks up to him first
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();
        assertEquals(List.of(Step.FINISH_BREWING_STORM), acts);
        assertEquals(Step.START_STORMING_THE_CASTLE, errands.errand("thane").step);
        game.runDueTimers(); // it walks up to him first
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();

        assertEquals(List.of(Step.FINISH_BREWING_STORM, Step.START_STORMING_THE_CASTLE), acts);
        assertEquals(Step.HUNT_CASTLE, errands.errand("thane").step);
    }

    @Test
    void aStepItFindsDoneAlreadyMovesTheErrandOnInsteadOfStartingTheSlice() {
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(PHANTOM_ROAD_MAP_ID));
        log.set(progress(2, 1, 1, 0, 20, 0, 0, false)); // its owner's quest mirror, meanwhile

        assertFalse(begin(errands.talk, PHANTOM_ROAD_MAP_ID, LUKAN_NPC_ID));

        assertEquals(Step.HUNT_CASTLE, errands.errand("thane").step);
        assertTrue(acts.isEmpty());
        assertEquals(clock.get() + RecallTravel.etaMs(PHANTOM_ROAD_MAP_ID, CAVERN_OF_FORTITUDE_MAP_ID,
                game.config.RECALL_MAX_ETA_MS), errands.errand("thane").arrivesAtMs);
    }

    @Test
    void jackIsSeenInTheCavernOfPain() {
        log.set(progress(2, 0, 0, 0, 0, 0, 0, false));
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(CAVERN_OF_PAIN_MAP_ID));
        assertEquals(CAVERN_OF_PAIN_MAP_ID, errands.talk.mapFor(game.row(thane), thane, THANE));
        assertEquals("gone to see Jack in the Cavern of Pain", errands.doing("thane"));

        assertTrue(begin(errands.talk, CAVERN_OF_PAIN_MAP_ID, JACK_NPC_ID));
        game.runDueTimers(); // it walks up to him first
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();

        assertEquals(List.of(Step.START_STEMMING_THE_TIDE), acts);
        assertEquals(Step.START_STORMING_THE_CASTLE, errands.errand("thane").step);
    }

    @Test
    void aRefusedQuestIsTriedAgainAndTheThirdRefusalEndsTheErrand() {
        errands.hands = new RefusingHands();
        for (int attempt = 1; attempt <= KeystoneErrands.TRIES; attempt++) {
            if (attempt == 1) {
                errands.ask(game.ann, thane);
                game.runTimers();
                clock.addAndGet(roadTo(PHANTOM_ROAD_MAP_ID));
            }
            assertTrue(begin(errands.talk, PHANTOM_ROAD_MAP_ID, LUKAN_NPC_ID), "attempt " + attempt);
            game.runDueTimers(); // it walks up to him first
            clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
            game.runDueTimers();
        }

        assertFalse(errands.underway("thane"));
        assertEquals(1, game.store.events("keystone.errand.end").size());
        errands.sweep();
        game.runTimers();
        assertEquals(RosterState.ACTIVE, game.row(thane).state(), "the sweep calls it back");
        verify(game.residents, never()).reply(thane, game.ann, KeystoneErrandLines.BACK.get(ANN % KeystoneErrandLines.BACK.size()));
    }

    // ---- hunts and the ledger ------------------------------------------------------------------------------

    @Test
    void aLiveHuntFightsOnlyItsMarksAndMeasuresHowManyAnHourItGets() {
        BenchDutyDispatcher.Slice slice = atStormhall();
        assertEquals(List.of(Set.of(STORMBREAKER_MOB_ID)), hunts);
        assertTrue(errands.errand("thane").live);

        clock.addAndGet(30 * 60_000L);
        log.set(progress(1, 0, 0, 4, 0, 0, 0, false));
        assertNull(errands.hunt.check(slice, game.bot(thane), game.row(thane), clock.get()), "six more to go");
        errands.hunt.finish(slice, game.bot(thane), "slice length");

        KeystoneErrands.Errand errand = errands.errand("thane");
        assertFalse(errand.live);
        assertEquals(8.0d, errand.perHour.get(STORMBREAKER_BADGE_ITEM_ID), 1e-9, "4 badges in half an hour");
        assertEquals(Step.HUNT_BADGES, errand.step);
        assertEquals(STORMHALL_MAP_ID, errands.hunt.mapFor(game.row(thane), thane, THANE), "its next field slice, here");
    }

    @Test
    void betweenLiveSlicesTheLedgerCarriesTheHuntOnAtItsOwnRateIntoTheQuestLog() {
        BenchDutyDispatcher.Slice slice = atStormhall();
        clock.addAndGet(30 * 60_000L);
        log.set(progress(1, 0, 0, 4, 0, 0, 0, false));
        errands.hunt.finish(slice, game.bot(thane), "slice length");

        clock.addAndGet(30 * 60_000L);
        errands.sweep();
        KeystoneErrands.Errand errand = errands.errand("thane");
        assertEquals(4.0d, errand.credit.get(STORMBREAKER_BADGE_ITEM_ID), 1e-9, "8 an hour, half an hour");
        assertEquals(Step.HUNT_BADGES, errand.step);
        clock.addAndGet(60 * 60_000L);
        errands.sweep();
        assertEquals(6.0d, errand.credit.get(STORMBREAKER_BADGE_ITEM_ID), 1e-9, "never past what it needs");
        assertEquals(Step.FINISH_BREWING_STORM, errand.step, "covered: back to Lukan");

        clock.addAndGet(RecallTravel.etaMs(STORMHALL_MAP_ID, PHANTOM_ROAD_MAP_ID, game.config.RECALL_MAX_ETA_MS));
        assertTrue(begin(errands.talk, PHANTOM_ROAD_MAP_ID, LUKAN_NPC_ID));
        assertEquals(List.of(Map.of(STORMBREAKER_BADGE_ITEM_ID, 6)), credits, "into its bag as it loads");
        assertTrue(errand.credit.isEmpty());
        game.runDueTimers(); // it walks up to him first
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();
        assertEquals(List.of(Step.FINISH_BREWING_STORM), acts);
        assertEquals(Step.START_STEMMING_THE_TIDE, errand.step);
    }

    @Test
    void aHuntItNeverMeasuredLiveGainsNothingOnTheLedger() {
        log.set(progress(1, 0, 0, 0, 0, 0, 0, false));
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(STORMHALL_MAP_ID) + 5 * 3_600_000L);

        errands.sweep();

        KeystoneErrands.Errand errand = errands.errand("thane");
        assertTrue(errand.credit.isEmpty(), "no live rate, no credit: never quicker than a live hunt");
        assertEquals(Step.HUNT_BADGES, errand.step);
    }

    @Test
    void aShortLiveSliceMeasuresNothing() {
        BenchDutyDispatcher.Slice slice = atStormhall();
        clock.addAndGet(20_000L);
        log.set(progress(1, 0, 0, 1, 0, 0, 0, false));
        errands.hunt.finish(slice, game.bot(thane), "unloaded");

        assertTrue(errands.errand("thane").perHour.isEmpty());
    }

    @Test
    void theCastleHuntCountsEachMarkAndEndsWhenAllThreeAreDone() {
        log.set(progress(2, 1, 1, 0, 70, 70, 70, false));
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(CAVERN_OF_FORTITUDE_MAP_ID));
        assertTrue(begin(errands.hunt, CAVERN_OF_FORTITUDE_MAP_ID, 0));
        BenchDutyDispatcher.Slice slice = new BenchDutyDispatcher.Slice(game.row(thane), THANE, errands.hunt,
                CAVERN_OF_FORTITUDE_MAP_ID, clock.get());
        assertEquals(List.of(Set.of(WINDRAIDER_MOB_ID, FIREBRAND_MOB_ID, RISEN_FIREBRAND_MOB_ID, NIGHTSHADOW_MOB_ID)), hunts);

        clock.addAndGet(10 * 60_000L);
        log.set(progress(2, 1, 1, 0, 75, 75, 75, false));
        assertEquals("hunted what it needs", errands.hunt.check(slice, game.bot(thane), game.row(thane), clock.get()));
        errands.hunt.finish(slice, game.bot(thane), "hunted what it needs");

        KeystoneErrands.Errand errand = errands.errand("thane");
        assertEquals(Step.FINISH_STORMING_THE_CASTLE, errand.step);
        assertEquals(30.0d, errand.perHour.get(NIGHTSHADOW_MOB_ID), 1e-9, "5 in ten minutes");
    }

    // ---- home again ----------------------------------------------------------------------------------------

    @Test
    void withTheKeystoneInItsBagItComesBackToItsOwnerAndSaysSo() {
        log.set(progress(2, 1, 1, 0, 75, 75, 75, false));
        atLukan();
        game.runDueTimers(); // it walks up to him first
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();
        assertEquals(List.of(Step.FINISH_STORMING_THE_CASTLE), acts);
        game.runTimers(); // the trip back

        assertEquals(RosterState.ACTIVE, game.row(thane).state());
        verify(game.residents).reply(thane, game.ann, KeystoneErrandLines.BACK.get(ANN % KeystoneErrandLines.BACK.size()));
        assertNull(errands.errand("thane"));
        assertFalse(game.store.flags.containsKey("companion.keystone-errand.10.thane"));
        assertEquals(1, game.store.events("keystone.earned").size());
    }

    @Test
    void calledBackByItsOwnerItsErrandIsOverAndTheQuestLogKeepsItsProgress() {
        errands.ask(game.ann, thane);
        game.runTimers();
        game.roster().call(game.ann, thane);
        game.runTimers();
        assertEquals(RosterState.ACTIVE, game.row(thane).state());

        errands.sweep();

        assertNull(errands.errand("thane"));
        assertFalse(game.store.flags.containsKey("companion.keystone-errand.10.thane"));
    }

    @Test
    void aBenchedCompanionGoesBetweenItsDutiesAndBackToThem() {
        Persona orrin = game.companionAs("PAGE", "orrin", "Orrin", 906, RosterState.BENCHED, LITH);
        game.store.levels.put(906, 95);

        assertEquals("Orrin will go for a Crimsonwood Keystone between bench duties.", errands.ask(game.ann, orrin));

        KeystoneErrands.Errand errand = errands.errand("orrin");
        assertFalse(errand.returns);
        assertSame(errands.talk, errands.formFor(game.row(orrin)));
        assertEquals(RosterState.BENCHED, game.row(orrin).state(), "it stays on the bench");
        assertTrue(game.store.flags.containsKey("companion.keystone-errand.10.orrin"));
    }

    @Test
    void aRestartCarriesTheErrandsOnFromLukansDoor() {
        game.store.flags.put("companion.keystone-errand.10.thane", ANN);
        game.store.flags.put("companion.keystone-errand.10.gone", ANN);
        game.store.seed(game.row(thane).with(RosterState.AWAY, 1_000L));

        errands.restore();

        assertFalse(game.store.flags.containsKey("companion.keystone-errand.10.gone"), "a row that is not away has none");
        KeystoneErrands.Errand errand = errands.errand("thane");
        assertTrue(errand.returns);
        assertEquals(PHANTOM_ROAD_MAP_ID, errands.talk.mapFor(game.row(thane), thane, THANE));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** Thane left Ann for Lukan, travelled there and walks up to him now: the slice. */
    private BenchDutyDispatcher.Slice atLukan() {
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(PHANTOM_ROAD_MAP_ID));
        assertTrue(begin(errands.talk, PHANTOM_ROAD_MAP_ID, LUKAN_NPC_ID));
        return new BenchDutyDispatcher.Slice(game.row(thane), THANE, errands.talk, PHANTOM_ROAD_MAP_ID, clock.get());
    }

    /** Thane started The Brewing Storm and is loaded in Stormhall now, hunting: the slice. */
    private BenchDutyDispatcher.Slice atStormhall() {
        log.set(progress(1, 0, 0, 0, 0, 0, 0, false));
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(STORMHALL_MAP_ID));
        assertTrue(begin(errands.hunt, STORMHALL_MAP_ID, 0));
        return new BenchDutyDispatcher.Slice(game.row(thane), THANE, errands.hunt, STORMHALL_MAP_ID, clock.get());
    }

    /** A bench slice of {@code form} begun on {@code mapId}, with the NPC (if any) at (400, 150). */
    private boolean begin(BenchDutyDispatcher.Form form, int mapId, int npcId) {
        MapleMap map = game.map(mapId);
        if (npcId != 0) {
            NPC npc = mock(NPC.class);
            when(npc.getPosition()).thenReturn(new Point(400, 150));
            when(map.getNPCById(npcId)).thenReturn(npc);
        }
        Character bot = game.bot(thane);
        bot.changeMap(map, new Point(LifecycleHarness.portalX(mapId), 200));
        BenchDutyDispatcher.Slice slice = new BenchDutyDispatcher.Slice(game.row(thane), THANE, form, mapId, clock.get());
        return form.begin(slice, bot, clock.get());
    }

    private long roadTo(int mapId) {
        return RecallTravel.etaMs(LifecycleHarness.HENESYS_FIELD, mapId, game.config.RECALL_MAX_ETA_MS);
    }

    private static Progress progress(int brewing, int stemming, int storming, int badges, int windraiders, int firebrands,
                                     int nightshadows, boolean keystone) {
        return new Progress(brewing, stemming, storming, badges, Map.of(WINDRAIDER_MOB_ID, windraiders,
                RISEN_FIREBRAND_MOB_ID, firebrands, NIGHTSHADOW_MOB_ID, nightshadows), keystone);
    }

    /** The quest log after a player's {@code step}, as the stock quests do it. */
    private static Progress after(Progress p, Step step) {
        return switch (step) {
            case START_BREWING_STORM -> new Progress(1, p.stemmingTheTide(), p.stormingTheCastle(), p.badges(), p.kills(), p.keystone());
            case FINISH_BREWING_STORM -> new Progress(2, p.stemmingTheTide(), p.stormingTheCastle(), p.badges() - 10, p.kills(), p.keystone());
            case START_STEMMING_THE_TIDE -> new Progress(p.brewingStorm(), 1, p.stormingTheCastle(), p.badges(), p.kills(), p.keystone());
            case START_STORMING_THE_CASTLE -> new Progress(p.brewingStorm(), p.stemmingTheTide(), 1, p.badges(), p.kills(), p.keystone());
            case FINISH_STORMING_THE_CASTLE, REISSUE_KEYSTONE -> new Progress(p.brewingStorm(), p.stemmingTheTide(), 2, p.badges(), p.kills(), true);
            default -> p;
        };
    }

    private final class RefusingHands implements KeystoneErrands.Hands {
        @Override
        public boolean act(AgentRuntimeEntry runtime, Step step) {
            acts.add(step);
            return false;
        }

        @Override
        public void credit(AgentRuntimeEntry runtime, Map<Integer, Integer> whole) {
        }

        @Override
        public boolean hunt(AgentRuntimeEntry runtime, Character agent, String requestId, int mapId, Set<Integer> targets,
                            long exitAtMs) {
            return false;
        }
    }
}
