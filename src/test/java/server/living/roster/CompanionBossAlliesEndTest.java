package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.MemberResult;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Refusal;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.RunEnded;
import server.agents.capabilities.expedition.boss.AgentBossRunMember.Phase;
import server.life.NPC;
import server.living.roster.CompanionBossAllies.State;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.KEY;
import static server.living.roster.BossAllyHarness.ROOM;
import static server.living.roster.BossAllyHarness.SID;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.BossAllyHarness.isLine;
import static server.living.roster.BossAllyHarness.seat;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * How a bench ally's trip ends (P7 Part B step 8, CompanionBossAllies through the host rule CompanionBossRuns): each
 * way out of a boss run, from a clear to a list that never started, a lost run, a gone runtime and every refusal, says
 * one line for how it went, walks the ally out of its party and to the room's way out while a human watches, unloads
 * it, and only then lets its EXPEDITION lease, its commitment and its trip go; an unload that leaves it online is tried
 * again by the audit. An ally down in the fight waits beside Mu Young, still tied to the run, until its owner comes out
 * to her room or the run ends. As the server shuts down (step 12), every ally goes at once, saying nothing.
 */
class CompanionBossAlliesEndTest {
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int GUNNAR = 913;
    private static final int BRYN = 914;
    private static final int FENNA = 915;
    private static final int MU_YOUNG = 1061014;

    private final BossAllyHarness h = new BossAllyHarness();

    @BeforeEach
    void orrinAndMagnaCameDownToMuYoung() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.game.roster();
        h.arrived("orrin", "magna");
        assertEquals(State.HERE, h.allies.trip("orrin").state);
        assertEquals(State.HERE, h.allies.trip("magna").state);
        NPC muYoung = mock(NPC.class);
        when(muYoung.getPosition()).thenReturn(new Point(617, 246)); // WZ 105100100 life/0
        when(h.room.getNPCById(MU_YOUNG)).thenReturn(muYoung);
        h.mapChat.clear();
    }

    @AfterEach
    void close() {
        h.close();
    }

    // ---- each end lets everything go ------------------------------------------------------------

    @Test
    void aClearedRunSendsEachHomeAsItComesOutWithAGoodFight() {
        signUp(ORRIN, MAGNA);
        inside(ORRIN, MAGNA);

        h.bossRuns.memberOut(seat(ORRIN), Out.EXITED);
        h.bossRuns.memberOut(seat(MAGNA), Out.EXITED);
        h.bossRuns.runEnded(ended(true, true, "members-out", result(ORRIN, true, Out.EXITED),
                result(MAGNA, true, Out.EXITED)));

        assertAllHome(ORRIN, MAGNA);
        assertLines(BossRunLines.ALLY_GOOD_FIGHT, "Orrin", "Magna");
    }

    @Test
    void anEventDisposedRunSendsThemHome() {
        signUp(ORRIN, MAGNA);
        inside(ORRIN, MAGNA);

        h.bossRuns.memberOut(seat(ORRIN), Out.DIED);
        h.bossRuns.memberOut(seat(MAGNA), Out.EXITED);
        h.bossRuns.runEnded(ended(true, false, "event-disposed", result(ORRIN, false, Out.DIED),
                result(MAGNA, false, Out.EXITED)));

        assertAllHome(ORRIN, MAGNA);
    }

    @Test
    void aDisbandedListSendsThemHomeWithTheFightCalledOff() {
        signUp(ORRIN); // Magna still walks up

        h.bossRuns.runEnded(ended(false, false, "disbanded", listed(ORRIN), listed(MAGNA)));

        assertAllHome(ORRIN, MAGNA);
        assertLines(BossRunLines.ALLY_CALLED_OFF, "Orrin", "Magna");
    }

    @Test
    void aRunNobodyWentIntoSendsThemHomeWithAMissedStart() {
        signUp(ORRIN, MAGNA);

        h.bossRuns.memberOut(seat(ORRIN), Out.MISSED_START);
        h.bossRuns.memberOut(seat(MAGNA), Out.MISSED_START);
        h.bossRuns.runEnded(ended(true, false, "nobody-inside", result(ORRIN, false, Out.MISSED_START),
                result(MAGNA, false, Out.MISSED_START)));

        assertAllHome(ORRIN, MAGNA);
        assertLines(BossRunLines.ALLY_MISSED, "Orrin", "Magna");
    }

    @Test
    void aRunEndedOnAnErrorSendsTheStrandedHome() {
        signUp(ORRIN, MAGNA);
        inside(ORRIN, MAGNA);

        h.bossRuns.memberOut(seat(ORRIN), Out.EXITED); // handed back from where it stood
        h.bossRuns.memberOut(seat(MAGNA), Out.DIED);
        h.bossRuns.runEnded(ended(true, false, "error", result(ORRIN, false, Out.EXITED),
                result(MAGNA, false, Out.DIED)));

        assertAllHome(ORRIN, MAGNA);
    }

    @Test
    void aRunLostWithoutAnEndIsSweptAfterTheLostRunWait() {
        signUp(ORRIN, MAGNA);
        h.open.gone = true; // the Agent layer holds no run of that id, and no end came

        h.bossRuns.sweepLostRuns(h.now);
        h.bossRuns.sweepLostRuns(h.now + CompanionPartyQuestService.LOST_RUN_AFTER_MS - 1L);
        assertEquals(State.HERE, h.allies.trip("orrin").state, "not yet");
        assertEquals(KEY, h.game.commitments.keyOf(ORRIN));

        h.bossRuns.sweepLostRuns(h.now + CompanionPartyQuestService.LOST_RUN_AFTER_MS);

        assertAllHome(ORRIN, MAGNA);
        assertSaid(BossRunLines.ALLY_GOODBYE, "Orrin", "Magna"); // one wording only
    }

    @Test
    void aGoneRuntimeLetsItsTripGoAndLeavesTheOtherAlone() {
        signUp(ORRIN, MAGNA);
        inside(ORRIN, MAGNA);
        h.game.loaded.remove(ORRIN); // unloaded under the run

        h.bossRuns.memberOut(seat(ORRIN), Out.GONE);

        assertHome(ORRIN, "orrin");
        assertEquals(List.of(), h.mapChat, "it is gone: it says nothing");
        assertEquals(State.HERE, h.allies.trip("magna").state);
        assertEquals(KEY, h.game.commitments.keyOf(MAGNA));
        assertEquals(1, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
    }

    @ParameterizedTest
    @EnumSource(Refusal.class)
    void eachRefusalSendsALoadedAllyHomeWithItsLine(Refusal refusal) {
        signUp(ORRIN);

        h.bossRuns.refused(seat(ORRIN), refusal);

        assertHome(ORRIN, "orrin");
        String intent = refusal == Refusal.WITHDRAWN ? BossRunLines.ALLY_CALLED_OFF : BossRunLines.ALLY_NOT_LISTED;
        assertEquals(1, h.mapChat.size(), h.mapChat.toString());
        assertTrue(isLine(intent, null, said("Orrin", h.mapChat.get(0))), refusal + ": " + h.mapChat);
        assertEquals(List.of(), h.game.partyChat, "never a party companion's line in a party of bots");
        assertEquals(State.HERE, h.allies.trip("magna").state, "the other ally stays");
    }

    @Test
    void aMissedStartSendsOnlyThatAllyHome() {
        signUp(ORRIN, MAGNA);

        h.bossRuns.memberOut(seat(ORRIN), Out.MISSED_START);

        assertHome(ORRIN, "orrin");
        assertLines(BossRunLines.ALLY_MISSED, "Orrin");
        assertEquals(KEY, h.game.commitments.keyOf(MAGNA));
    }

    @Test
    void aWithdrawnAllyGoesHomeWithTheFightCalledOff() {
        signUp(ORRIN, MAGNA);
        h.open.phases.put(ORRIN, Phase.REFUSED); // the run took it off her list itself

        h.bossRuns.refused(seat(ORRIN), Refusal.WITHDRAWN);

        assertHome(ORRIN, "orrin");
        assertLines(BossRunLines.ALLY_CALLED_OFF, "Orrin");
    }

    @Test
    void aTravellingAllyRefusedTurnsBackWithAWhisperAndItsArrivalDoesNothing() {
        h.allies.send(h.game.ann, "easy_balrog", List.of("gunnar"), h.now);
        assertEquals(State.TRAVELLING, h.allies.trip("gunnar").state);

        h.bossRuns.refused(seat(GUNNAR), Refusal.LATE); // Start came before it got here
        h.game.runDueTimers();

        assertHome(GUNNAR, "gunnar");
        List<String> said = h.said("Gunnar");
        assertEquals(2, said.size(), h.whispers.toString());
        assertTrue(isLine(BossRunLines.ALLY_TURNED_BACK, null, said.get(1)), said.toString());
        assertEquals(2, h.ports.loads.size(), "never loaded: " + h.ports.loads);
        assertEquals(List.of(), h.ports.unloads);
    }

    // ---- the walk out and the unload -----------------------------------------------------------

    @Test
    void withAHumanWatchingItLeavesItsPartyAndWalksToTheWayOutThenUnloads() {
        h.annIsInTheRoom();
        h.ports.walksArrive = false;
        signUp(ORRIN, MAGNA);

        h.bossRuns.refused(seat(ORRIN), Refusal.BANNED);

        assertTrue(h.ports.party.contains("leave Orrin (companion.boss)"), h.ports.party.toString());
        assertEquals(List.of("walk Orrin to -691,246"), h.ports.walks, "the outPerrion portal");
        assertEquals(State.LEAVING, h.allies.trip("orrin").state);
        assertEquals(List.of(), h.ports.unloads, "it walks first");
        h.game.runDueTimers();
        assertEquals(List.of(), h.ports.unloads, "still on its way");

        h.game.bots.get(ORRIN).changeMap(h.room, new Point(-680, 246));
        h.game.runDueTimers();

        assertEquals(List.of("unload Orrin"), h.ports.unloads);
        assertHome(ORRIN, "orrin");
    }

    @Test
    void aWalkThatNeverGetsThereEndsAtTheWalkOutTime() {
        h.annIsInTheRoom();
        h.ports.walksArrive = false;
        signUp(ORRIN);

        h.bossRuns.refused(seat(ORRIN), Refusal.BANNED);
        h.now += h.game.config.COMPANION_BOSS_ALLY_WALK_OUT_MS - 1L;
        h.game.runDueTimers();
        assertEquals(List.of(), h.ports.unloads);
        h.now += 1L;
        h.game.runDueTimers();

        assertHome(ORRIN, "orrin");
    }

    @Test
    void withNoHumanToSeeItItGoesWhereItStands() {
        signUp(ORRIN);

        h.bossRuns.refused(seat(ORRIN), Refusal.BANNED);

        assertEquals(List.of(), h.ports.walks);
        assertHome(ORRIN, "orrin");
    }

    @Test
    void anUnloadThatLeavesItOnlineKeepsItsLeaseAndTheAuditUnloadsItAgain() {
        h.ports.stayOnline.add(ORRIN);
        signUp(ORRIN);

        h.bossRuns.refused(seat(ORRIN), Refusal.BANNED);
        assertEquals(State.LEAVING, h.allies.trip("orrin").state);
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.EXPEDITION, ANN), h.game.leases.holder(ORRIN),
                "nothing may load a second copy of it");
        h.bossRuns.sweepLostRuns(h.now + 1_000L);
        assertEquals(1, h.ports.unloads.size(), "a walk out may still be on");

        h.ports.stayOnline.clear();
        h.bossRuns.sweepLostRuns(h.now + h.game.config.COMPANION_BOSS_ALLY_WALK_OUT_MS);

        assertEquals(List.of("unload Orrin", "unload Orrin"), h.ports.unloads);
        assertHome(ORRIN, "orrin");
    }

    // ---- down in the fight ---------------------------------------------------------------------

    @Test
    void aDeadAllyWaitsAtItsSpotTiedToTheRunUntilItsOwnerComesOut() {
        signUp(ORRIN, MAGNA);
        inside(ORRIN, MAGNA);
        h.game.annEntersInstance(105100300); // Ann fights on

        h.bossRuns.memberOut(seat(ORRIN), Out.DIED);

        assertEquals(List.of("walk Orrin to 417,246"), h.ports.walks, "its own spot beside Mu Young (ordinal 5)");
        assertEquals(State.HERE, h.allies.trip("orrin").state);
        assertEquals(KEY, h.game.commitments.keyOf(ORRIN), "still in the run");
        assertNotNull(h.game.leases.holder(ORRIN));
        List<String> whispered = h.said("Orrin"); // its ETA at the call, then this
        assertEquals(2, whispered.size(), h.whispers.toString());
        assertTrue(isLine(BossRunLines.DOWN, null, whispered.get(1)), h.whispers.toString());
        assertEquals(List.of(), h.mapChat, "she is in the fight: it tells her in a whisper");

        h.game.companionOnChannel(h.game.personas.get("orrin"), BossAllyHarness.CHANNEL);
        h.game.moveAnn(ROOM, 300, 246); // out through the exit NPC, but into channel 1's copy of the room
        h.bossRuns.ownerArrived(ANN, ROOM);
        assertEquals(State.HERE, h.allies.trip("orrin").state, "not the copy it waits in");

        h.game.annOnChannel(BossAllyHarness.CHANNEL);
        h.bossRuns.ownerArrived(ANN, ROOM);

        assertHome(ORRIN, "orrin");
        assertSaid(BossRunLines.ALLY_GOODBYE, "Orrin");
        assertEquals(State.HERE, h.allies.trip("magna").state, "Magna fights on");
    }

    @Test
    void aDeadAllyGoesHomeWhenTheRunEndsFirst() {
        signUp(ORRIN);
        inside(ORRIN);
        h.game.annEntersInstance(105100300);

        h.bossRuns.memberOut(seat(ORRIN), Out.DIED);
        h.game.moveAnn(ROOM, 300, 246);
        h.bossRuns.runEnded(ended(true, true, "members-out", result(ORRIN, false, Out.DIED)));

        assertHome(ORRIN, "orrin");
        assertLines(BossRunLines.ALLY_GOOD_FIGHT, "Orrin");
    }

    /** Both allies are down outside (or, for a list that never started, still on it) as the run ends. */
    @ParameterizedTest
    @CsvSource({"true, true, boss.ally.goodfight", "true, false, boss.ally.nexttime",
            "false, false, boss.ally.calledoff"})
    void theRunsEndSaysGoodbyeByHowItWentEachAllyInItsOwnWords(boolean started, boolean cleared, String intent) {
        signUp(ORRIN, MAGNA);
        if (started) {
            inside(ORRIN, MAGNA);
            h.game.annEntersInstance(105100300);
            h.bossRuns.memberOut(seat(ORRIN), Out.DIED);
            h.bossRuns.memberOut(seat(MAGNA), Out.DIED);
            h.game.moveAnn(ROOM, 300, 246); // she came out, and her allies waited on (no arrival hook here)
            assertEquals(State.HERE, h.allies.trip("orrin").state);
        }

        h.bossRuns.runEnded(started ? ended(true, cleared, "members-out", result(ORRIN, false, Out.DIED),
                result(MAGNA, false, Out.DIED)) : ended(false, false, "disbanded", listed(ORRIN), listed(MAGNA)));

        assertAllHome(ORRIN, MAGNA);
        assertLines(intent, "Orrin", "Magna");
    }

    @Test
    void theRunsEndLogsHowManyWentHomeAndThePeakOfThePermits() {
        signUp(ORRIN, MAGNA);
        inside(ORRIN, MAGNA);

        try (LivingLogLines log = new LivingLogLines(CompanionBossAllies.class)) {
            h.bossRuns.runEnded(ended(true, true, "members-out", result(ORRIN, true, Out.EXITED),
                    result(MAGNA, true, Out.EXITED)));

            assertEquals(List.of("Boss allies: session=7 boss=easy_balrog reason=members-out sent home=2 peak=2/12"),
                    log.startingWith("Boss allies:"));
        }
    }

    // ---- the server's shutdown -----------------------------------------------------------------

    @Test
    void theShutdownSendsEveryAllyHomeAtOnceSayingNothing() {
        h.annIsInTheRoom(); // a human sees them: still nobody walks
        h.ports.walksArrive = false;
        h.benched("bryn", "Bryn", BRYN, SLEEPYWOOD_CAMP);
        h.benched("fenna", "Fenna", FENNA, SLEEPYWOOD_CAMP);
        h.game.world.views().invalidate(ANN); // new rows: the crew cache reads them again
        h.arrived("bryn");
        signUp(ORRIN, MAGNA, BRYN); // on her list, in their party of bots
        h.bossRuns.refused(seat(BRYN), Refusal.BANNED); // walking out
        h.allies.send(h.game.ann, "easy_balrog", List.of("gunnar"), h.now); // on its way down
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(h.game.world.executor()).thenReturn(queued);
        h.allies.send(h.game.ann, "easy_balrog", List.of("fenna"), h.now); // its call still waits on companion-ops
        assertEquals(State.LEAVING, h.allies.trip("bryn").state);
        assertEquals(State.TRAVELLING, h.allies.trip("gunnar").state);
        assertEquals(State.CALLING, h.allies.trip("fenna").state);
        assertEquals(List.of(ORRIN, MAGNA), h.ports.partyOf(ORRIN));
        h.whispers.clear();
        h.mapChat.clear();
        h.ports.walks.clear();
        h.ports.party.clear();
        h.ports.unloads.clear();

        h.bossRuns.stopAllies();
        h.game.runDueTimers(); // Gunnar's arrival and Bryn's walk out come too late
        for (int i = 0; i < ops.size(); i++) {
            ops.get(i).run(); // and so does Fenna's call
        }

        assertAllHome(ORRIN, MAGNA, BRYN, GUNNAR, FENNA);
        assertEquals(Set.of("unload Orrin", "unload Magna", "unload Bryn"), Set.copyOf(h.ports.unloads));
        assertEquals(3, h.ports.unloads.size(), h.ports.unloads.toString());
        assertEquals(List.of(), h.ports.walks, "nobody walks out");
        assertEquals(Set.of("leave Orrin (companion.boss)", "leave Magna (companion.boss)"), Set.copyOf(h.ports.party));
        assertEquals(List.of(), h.ports.partyOf(ORRIN));
        assertEquals(List.of(), h.ports.partyOf(MAGNA));
        assertTrue(h.open.calls.contains("withdraw " + GUNNAR), h.open.calls.toString());
        assertEquals(3, h.ports.loads.size(), "Gunnar and Fenna never loaded: " + h.ports.loads);
        assertEquals(List.of(), h.mapChat, "the server is going down: nobody says a word");
        assertEquals(List.of(), h.whispers);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Each ally is on Mu Young's list (and its party formed); the line that they are all signed up is not kept. */
    private void signUp(int... ids) {
        for (int id : ids) {
            h.open.phases.put(id, Phase.REGISTERED);
            h.bossRuns.registered(seat(id));
        }
        h.mapChat.clear();
    }

    private void inside(int... ids) {
        for (int id : ids) {
            h.open.phases.put(id, Phase.INSIDE);
        }
    }

    private static MemberResult result(int id, boolean inAtClear, Out out) {
        return new MemberResult(id, ANN, true, inAtClear, out == Out.DIED ? 1 : 0, out);
    }

    /** A member still on the list (or walking up) as a list that never started ended. */
    private static MemberResult listed(int id) {
        return new MemberResult(id, ANN, false, false, 0, null);
    }

    private static RunEnded ended(boolean started, boolean cleared, String reason, MemberResult... members) {
        return new RunEnded(SID, "easy_balrog", started, cleared, reason, started ? 1_000L : 0L, 61_000L, 3,
                List.of(members));
    }

    /** Every ally named is home and nothing of a trip is held by anyone. */
    private void assertAllHome(int... ids) {
        for (int id : ids) {
            assertHome(id, h.game.personaOfBot.get(id));
        }
        assertEquals(0, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
    }

    /** The ally is unloaded and its trip, its EXPEDITION lease and its commitment are let go. */
    private void assertHome(int id, String personaId) {
        assertNull(h.allies.trip(personaId), personaId + " still on a trip");
        assertNull(h.game.leases.holder(id), personaId + "'s lease");
        assertFalse(h.game.commitments.isCommitted(id), personaId + "'s commitment");
        assertFalse(h.game.loaded.contains(id), personaId + " still loaded");
        assertFalse(h.allies.onTrip(personaId));
    }

    /** Each of {@code names} said one {@code intent} line in map chat, no two of them the same words. */
    private void assertLines(String intent, String... names) {
        List<String> words = assertSaid(intent, names);
        assertEquals(words.size(), new HashSet<>(words).size(), "each in its own words: " + words);
    }

    /** Each of {@code names} said one {@code intent} line in map chat; the words each said. */
    private List<String> assertSaid(String intent, String... names) {
        List<String> words = new ArrayList<>();
        for (String name : names) {
            List<String> theirs = h.mapChat.stream().filter(line -> line.startsWith(name + ": ")).toList();
            assertEquals(1, theirs.size(), name + ": " + h.mapChat);
            words.add(said(name, theirs.get(0)));
            assertTrue(isLine(intent, null, words.get(words.size() - 1)), intent + ": " + h.mapChat);
        }
        return words;
    }

    private static String said(String name, String line) {
        return line.substring(name.length() + 2);
    }
}
