package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Companions go for their next job by themselves (2026-10-05). Found that day: Casse (persona I/L Wizard) reached Lv50
 * a Magician, and Rurik, Yara and HappyWolf Lv45+ Warriors, because only an owner typing a branch name in chat advanced a
 * companion. Now one whose path names its next job tells its owner, walks out keeping its seat, travels to its
 * instructor, walks up to them, advances there and is called back to its owner; a benched one goes between its duties.
 */
class JobErrandsTest {
    private static final int CASSE = 333;
    private static final int RURIK = 831;
    private static final int MAGIC_LIBRARY = 101000003;
    private static final int GRENDEL = 1032001;
    private static final int WARRIORS_SANCTUARY = 102000003;
    private static final int DANCES_WITH_BALROG = 1022000;

    private final LifecycleHarness game = new LifecycleHarness();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> advanced = new ArrayList<>();
    private JobErrands errands;
    private Persona casse;

    @BeforeEach
    void casseOutWithAnn() {
        casse = game.companionAs("IL_WIZARD", "casse", "Casse", CASSE, RosterState.ACTIVE, LITH);
        game.outWithAnn(casse);
        game.roster();
        stats(game.bot(casse), Job.MAGICIAN, 50);
        game.roster().settings().bind(game.bot(casse), ANN, "casse");
        errands = new JobErrands(game.world, clock::get);
        errands.goesAlong = (runtime, ownerId) -> true; // following Ann
        errands.advance = (runtime, job) -> {
            advanced.add(runtime.bot().getName() + " " + job);
            when(runtime.bot().getJob()).thenReturn(job);
            return true;
        };
        game.interaction.when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> new AgentRuntimeEntry(inv.getArgument(0), inv.getArgument(0), null));
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aPathIsThePersonaJobAtFirstJobAndItsOwnLineToFourthJobFromSecondJobOn() {
        assertEquals(Job.IL_WIZARD, JobErrands.pathFor(Job.MAGICIAN, Job.IL_WIZARD));
        assertEquals(Job.FIGHTER, JobErrands.pathFor(Job.BEGINNER, Job.FIGHTER));
        assertEquals(Job.IL_ARCHMAGE, JobErrands.pathFor(Job.IL_WIZARD, Job.IL_WIZARD), "3rd and 4th job: no choice left");
        assertEquals(Job.HERO, JobErrands.pathFor(Job.FIGHTER, null), "a hire too, from second job on");
        assertEquals(Job.FP_ARCHMAGE, JobErrands.pathFor(Job.FP_WIZARD, Job.IL_WIZARD), "its owner chose F/P: its own line now");
        assertEquals(Job.SHADOWER, JobErrands.pathFor(Job.CHIEFBANDIT, Job.BANDIT));

        assertNull(JobErrands.pathFor(Job.WARRIOR, null), "a hire at first job: its owner picks");
        assertNull(JobErrands.pathFor(Job.WARRIOR, Job.WARRIOR), "a persona naming only its first job: its owner picks");
        assertNull(JobErrands.pathFor(Job.BEGINNER, Job.BEGINNER), "a Beginner persona");
        assertNull(JobErrands.pathFor(Job.WARRIOR, Job.IL_WIZARD), "a persona of another class");
    }

    @Test
    void aCygnusKnightOrAranHasNoChoiceLeftFromFirstJobOn() {
        assertEquals(Job.WINDARCHER2, JobErrands.pathFor(Job.NOBLESSE, Job.WINDARCHER2), "a Noblesse persona's class");
        assertEquals(Job.ARAN4, JobErrands.pathFor(Job.LEGEND, Job.ARAN2), "a Legend has one way on");
        assertEquals(Job.ARAN4, JobErrands.pathFor(Job.LEGEND, null), "a Legend hire too");
        assertEquals(Job.DAWNWARRIOR3, JobErrands.pathFor(Job.DAWNWARRIOR1, null), "a hire too: one way on");
        assertEquals(Job.NIGHTWALKER3, JobErrands.pathFor(Job.NIGHTWALKER2, Job.NIGHTWALKER2), "to third job: no 4th in v83");
        assertEquals(Job.ARAN4, JobErrands.pathFor(Job.ARAN1, null));
        assertEquals(Job.THUNDERBREAKER3, JobErrands.pathFor(Job.THUNDERBREAKER3, null), "at its end");

        assertNull(JobErrands.pathFor(Job.NOBLESSE, null), "a Noblesse hire: its owner picks its class");
        assertNull(JobErrands.pathFor(Job.NOBLESSE, Job.FIGHTER), "an Explorer persona on a Noblesse");
        assertNull(JobErrands.pathFor(Job.BEGINNER, Job.DAWNWARRIOR2), "a Cygnus persona on a Beginner");
    }

    @Test
    void theRuleNamesAnEmployedCompanionsPathAndNothingWithTheKillSwitchOff() {
        when(game.residents.isPersonaCharacter(CASSE)).thenReturn(true);

        assertEquals(Job.IL_WIZARD, errands.path(CASSE, Job.MAGICIAN));
        assertNull(errands.path(999, Job.MAGICIAN), "not a persona's character");
        game.config.COMPANION_JOB_ERRANDS_ENABLED = false;
        assertNull(errands.path(CASSE, Job.MAGICIAN));
    }

    @Test
    void aCompanionPastItsLevelGoesToItsInstructorAdvancesThereAndIsCalledBack() {
        errands.due(CASSE, Job.IL_WIZARD);

        verify(game.residents).reply(casse, game.ann, "gotta go see grendel about my 2nd job. brb");
        assertEquals(RosterState.AWAY, game.row(casse).state());
        assertEquals(CompanionSlots.Kind.AWAY, game.slots.reservation("casse").kind(), "its seat is kept");
        assertTrue(game.store.flags.containsKey("companion.job-errand.10.casse"));
        assertEquals(1, game.store.events("job.errand").size());
        assertTrue(game.calls.contains("leave Casse"));
        game.runTimers();
        assertTrue(game.calls.contains("remove Casse"), "gone at the portal");

        RosterEntry away = game.row(casse);
        assertSame(errands.form, errands.formFor(away), "the visit, ahead of its duty");
        assertEquals(0, errands.form.mapFor(away, casse, CASSE), "on its way");
        clock.addAndGet(RecallTravel.etaMs(LifecycleHarness.HENESYS_FIELD, MAGIC_LIBRARY, game.config.RECALL_MAX_ETA_MS));
        assertEquals(MAGIC_LIBRARY, errands.form.mapFor(away, casse, CASSE), "at Grendel's door");

        Character bot = game.bot(casse);
        BenchDutyDispatcher.Slice slice = visit(away, bot, MAGIC_LIBRARY, GRENDEL, new Point(400, 150));
        assertTrue(game.calls.contains("walk Casse to 400,150"), "it walks up to Grendel");
        game.runDueTimers();
        assertTrue(advanced.isEmpty(), "it stands with him a moment first");
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();

        assertEquals(List.of("Casse IL_WIZARD"), advanced);
        assertEquals(1, game.store.events("job.advanced").size());
        assertNotNull(errands.form.check(slice, bot, away, clock.get()), "its visit slice ends (the call ended it already)");
        game.runTimers(); // the trip back
        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        verify(game.residents).reply(casse, game.ann, "made it back. i/l wizard now :)");
        assertFalse(game.store.flags.containsKey("companion.job-errand.10.casse"));
        assertNull(errands.errand("casse"));
    }

    @Test
    void anErrandIsUnderwayAndNamesItsInstructorUntilItHasAdvanced() {
        assertFalse(errands.underway("casse"));
        assertNull(errands.visiting("casse"));

        errands.due(CASSE, Job.IL_WIZARD);

        assertTrue(errands.underway("casse"));
        assertEquals("Grendel in Ellinia", errands.visiting("casse"), "the Crew window's 'gone to see ...'");
        game.runTimers();
        RosterEntry away = game.row(casse);
        assertSame(errands.form, errands.formFor(away));
        clock.addAndGet(RecallTravel.etaMs(LifecycleHarness.HENESYS_FIELD, MAGIC_LIBRARY, game.config.RECALL_MAX_ETA_MS));
        assertEquals(MAGIC_LIBRARY, errands.form.mapFor(away, casse, CASSE));
        visit(away, game.bot(casse), MAGIC_LIBRARY, GRENDEL, new Point(400, 150));
        game.runDueTimers();
        assertEquals("Grendel in Ellinia", errands.visiting("casse"), "with him: still underway");
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();

        assertEquals(List.of("Casse IL_WIZARD"), advanced);
        assertFalse(errands.underway("casse"), "on its way back: its usual trip home");
        assertNull(errands.visiting("casse"));
    }

    @Test
    void aRefusedJobChangeBringsItBackWithoutClaimingItAndItWaitsBeforeGoingAgain() {
        errands.advance = (runtime, job) -> false;
        Character bot = goneToGrendel();
        game.runDueTimers();
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();
        game.runTimers(); // called back: the trip back

        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        verify(game.residents).reply(casse, game.ann, "here.");
        verify(game.residents, never()).reply(casse, game.ann, "made it back. i/l wizard now :)");
        assertEquals(1, game.store.events("job.errand.end").size());
        assertFalse(game.store.flags.containsKey("companion.job-errand.10.casse"));
        game.roster().settings().bind(bot, ANN, "casse");
        errands.due(CASSE, Job.IL_WIZARD);
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "not again right away");
    }

    @Test
    void aVisitThatRunsTooLongEndsAndTheSweepCallsItBack() {
        Character bot = goneToGrendel();
        RosterEntry away = game.row(casse);
        BenchDutyDispatcher.Slice slice = new BenchDutyDispatcher.Slice(away, CASSE, errands.form, MAGIC_LIBRARY, clock.get());
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_WALK_MAX_MS + game.config.COMPANION_JOB_ERRAND_TALK_MS
                + BenchDutyDispatcher.TICK_MS + 1L);

        String reason = errands.form.check(slice, bot, away, clock.get());
        assertEquals("visit took too long", reason);
        errands.form.finish(slice, bot, reason);
        errands.sweep();
        game.runTimers();

        assertTrue(advanced.isEmpty());
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "never left away on a failed errand");
        assertNull(errands.errand("casse"));
    }

    @Test
    void alreadyAdvancedWhenItGetsThereItIsCalledBackByTheSweepNotFromItsStartingSlice() {
        errands.due(CASSE, Job.IL_WIZARD);
        game.runTimers();
        clock.addAndGet(RecallTravel.etaMs(LifecycleHarness.HENESYS_FIELD, MAGIC_LIBRARY, game.config.RECALL_MAX_ETA_MS));
        Character bot = game.bot(casse);
        when(bot.getJob()).thenReturn(Job.IL_WIZARD); // a GM set meanwhile
        bot.changeMap(game.map(MAGIC_LIBRARY), new Point(LifecycleHarness.portalX(MAGIC_LIBRARY), 200));
        BenchDutyDispatcher.Slice slice = new BenchDutyDispatcher.Slice(game.row(casse), CASSE, errands.form, MAGIC_LIBRARY,
                clock.get());

        assertFalse(errands.form.begin(slice, bot, clock.get()));
        assertEquals(RosterState.AWAY, game.row(casse).state(), "nothing called while its slice starts");
        assertFalse(errands.underway("casse"), "it holds its job: it waits to be called back");
        assertNull(errands.visiting("casse"), "the Crew window no longer says it went to see Grendel");
        errands.sweep();
        game.runTimers();

        assertTrue(advanced.isEmpty());
        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        verify(game.residents).reply(casse, game.ann, "made it back. i/l wizard now :)");
    }

    @Test
    void theKillSwitchTurnedOffMidErrandCallsItBack() {
        errands.due(CASSE, Job.IL_WIZARD);
        game.runTimers();
        game.config.COMPANION_JOB_ERRANDS_ENABLED = false;

        errands.sweep();
        game.runTimers();

        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        assertFalse(game.store.flags.containsKey("companion.job-errand.10.casse"));
    }

    @Test
    void calledBackBeforeItAdvancedItWaitsBeforeItSetsOffAgain() {
        errands.due(CASSE, Job.IL_WIZARD);
        game.runTimers();
        game.roster().call(game.ann, casse);
        game.runTimers();
        assertEquals(RosterState.ACTIVE, game.row(casse).state());

        errands.sweep();
        assertNull(errands.errand("casse"));
        assertFalse(game.store.flags.containsKey("companion.job-errand.10.casse"));
        game.roster().settings().bind(game.bot(casse), ANN, "casse");
        errands.due(CASSE, Job.IL_WIZARD);
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "not again right away");

        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_RETRY_MS);
        errands.due(CASSE, Job.IL_WIZARD);
        assertEquals(RosterState.AWAY, game.row(casse).state(), "it goes once that time is up");
    }

    @Test
    void itStaysWhileItsOwnerIsInARunOrHasItWaitOrAGmLevelHoldsIt() {
        game.annEntersInstance(910000000);
        errands.due(CASSE, Job.IL_WIZARD);
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "its owner is in an event");
        game.moveAnn(LifecycleHarness.HENESYS_FIELD, 1000, 200);

        errands.goesAlong = (runtime, ownerId) -> false;
        errands.due(CASSE, Job.IL_WIZARD);
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "told to wait: its owner's order stands");
        errands.goesAlong = (runtime, ownerId) -> true;

        GmLevelHolds holds = mock(GmLevelHolds.class);
        when(holds.held(CASSE, "casse")).thenReturn(true);
        when(game.world.gmLevelHolds()).thenReturn(holds);
        errands.due(CASSE, Job.IL_WIZARD);
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "a GM level set restores its own job");
        verify(game.residents, never()).reply(eq(casse), any(), anyString());
    }

    @Test
    void aBenchedCompanionGoesToItsInstructorBetweenItsDutiesAndBackToThem() {
        Persona rurik = game.companionAs("PAGE", "rurik", "Rurik", RURIK, RosterState.BENCHED, LITH);
        game.store.levels.put(RURIK, 47);
        game.store.jobs.put(RURIK, Job.WARRIOR.getId());
        RosterEntry benched = game.row(rurik);

        assertSame(errands.form, errands.formFor(benched));
        assertEquals(0, errands.form.mapFor(benched, rurik, RURIK), "on its way from its camp");
        clock.addAndGet(RecallTravel.etaMs(LITH, WARRIORS_SANCTUARY, game.config.RECALL_MAX_ETA_MS));
        assertEquals(WARRIORS_SANCTUARY, errands.form.mapFor(benched, rurik, RURIK));

        Character bot = game.bot(rurik);
        stats(bot, Job.WARRIOR, 47);
        visit(benched, bot, WARRIORS_SANCTUARY, DANCES_WITH_BALROG, new Point(-200, 274));
        game.runDueTimers();
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();

        assertEquals(List.of("Rurik PAGE"), advanced);
        assertNull(errands.errand("rurik"), "back to its duties: nobody to be called back to");
        game.store.jobs.put(RURIK, Job.PAGE.getId());
        assertNull(errands.formFor(benched), "its duty's own form again");
        verify(game.residents, never()).reply(eq(rurik), any(), anyString());
        assertTrue(game.store.flags.isEmpty());
    }

    @Test
    void aBenchedHireAtFirstJobOrAPersonaNamingOnlyItsFirstJobIsLeftToItsOwner() {
        Persona yara = game.companionAs("WARRIOR", "yara", "Yara", 15, RosterState.BENCHED, LITH);
        game.store.levels.put(15, 47);
        game.store.jobs.put(15, Job.WARRIOR.getId());
        assertNull(errands.formFor(game.row(yara)));
        Persona wolf = game.companionAs("BEGINNER", "hire:436", "HappyWolf", 436, RosterState.BENCHED, LITH);
        game.store.levels.put(436, 45);
        game.store.jobs.put(436, Job.WARRIOR.getId());
        assertNull(errands.formFor(game.row(wolf)));

        game.store.levels.put(15, 70);
        game.store.jobs.put(15, Job.FIGHTER.getId());
        assertSame(errands.form, errands.formFor(game.row(yara)), "from second job on its own line");
    }

    @Test
    void aRestartCarriesAnOwnersErrandOn() {
        game.store.flags.put("companion.job-errand.10.casse", ANN);
        game.store.seed(game.row(casse).with(RosterState.AWAY, 1_000L));
        game.store.levels.put(CASSE, 50);
        game.store.jobs.put(CASSE, Job.MAGICIAN.getId());

        errands.restore();

        assertEquals(MAGIC_LIBRARY, errands.form.mapFor(game.row(casse), casse, CASSE), "on Grendel's doorstep now");
        assertSame(errands.form, errands.formFor(game.row(casse)));
    }

    @Test
    void aRestartAfterItAdvancedCallsItBackOnceItsOwnerCanTakeIt() {
        game.store.flags.put("companion.job-errand.10.casse", ANN);
        game.store.flags.put("companion.job-errand.10.gone", ANN);
        game.store.seed(game.row(casse).with(RosterState.AWAY, 1_000L));
        game.store.levels.put(CASSE, 50);
        game.store.jobs.put(CASSE, Job.IL_WIZARD.getId());
        game.loaded.remove(CASSE); // an AWAY companion is neither loaded nor in its owner's party
        game.members.remove((Integer) CASSE);

        errands.restore();
        assertFalse(game.store.flags.containsKey("companion.job-errand.10.gone"), "a row that is not away has none");
        assertNull(errands.formFor(game.row(casse)), "advanced: its duty while it waits");
        errands.sweep();
        game.runTimers();

        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        verify(game.residents).reply(casse, game.ann, "made it back. i/l wizard now :)");
        assertFalse(game.store.flags.containsKey("companion.job-errand.10.casse"));
    }

    @Test
    void withTheKillSwitchOffNoCompanionGoes() {
        game.config.COMPANION_JOB_ERRANDS_ENABLED = false;
        Persona rurik = game.companionAs("PAGE", "rurik", "Rurik", RURIK, RosterState.BENCHED, LITH);
        game.store.levels.put(RURIK, 47);
        game.store.jobs.put(RURIK, Job.WARRIOR.getId());

        assertNull(errands.formFor(game.row(rurik)));
    }

    /** Casse left Ann for Grendel, travelled there and walks up to him now; her bot. */
    private Character goneToGrendel() {
        errands.due(CASSE, Job.IL_WIZARD);
        game.runTimers();
        clock.addAndGet(RecallTravel.etaMs(LifecycleHarness.HENESYS_FIELD, MAGIC_LIBRARY, game.config.RECALL_MAX_ETA_MS));
        Character bot = game.bot(casse);
        visit(game.row(casse), bot, MAGIC_LIBRARY, GRENDEL, new Point(400, 150));
        return bot;
    }

    /** A bench slice of the visit: loaded at the map's portal, with its instructor standing at {@code npcAt}. */
    private BenchDutyDispatcher.Slice visit(RosterEntry row, Character bot, int mapId, int npcId, Point npcAt) {
        MapleMap map = game.map(mapId);
        NPC npc = mock(NPC.class);
        when(npc.getPosition()).thenReturn(npcAt);
        when(map.getNPCById(npcId)).thenReturn(npc);
        bot.changeMap(map, new Point(LifecycleHarness.portalX(mapId), 200));
        BenchDutyDispatcher.Slice slice = new BenchDutyDispatcher.Slice(row, bot.getId(), errands.form, mapId, clock.get());
        assertTrue(errands.form.begin(slice, bot, clock.get()));
        return slice;
    }

    private static void stats(Character bot, Job job, int level) {
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getHp()).thenReturn(500);
        when(bot.getCurrentMaxHp()).thenReturn(500);
    }
}
