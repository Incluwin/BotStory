package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Refusal;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionType;
import server.living.LivingWorld;
import server.living.roster.CompanionBossAllies.State;
import testutil.BossLogDatabase;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.KEY;
import static server.living.roster.BossAllyHarness.SID;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.BossAllyHarness.isLine;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * A bench ally's call on companion-ops (P7 Part B step 7, CompanionBossAllies.call): the budget admits it before
 * anything changes (EXPEDITION_BURST_MAX counts the leases held, never the caller's own), so a denial ends no slice and
 * the ally whispers there is no room; then its live slice ends, its EXPEDITION lease is taken, it is enlisted as a
 * member that leads itself and committed under its owner's run key, and it whispers the recall ETA it arrives at (plus
 * a stagger). Each failure after the admission undoes the steps before it, last first, and it whispers that it cannot
 * come. Two quick picks never take more than the owner's share. Each lease taken raises the server's peak of
 * EXPEDITION leases held at once (step 12, /runtime/budget), which never falls.
 */
class CompanionBossAlliesCallTest {
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int GUNNAR = 913;
    private static final int BRYN = 914;
    private static final int OTHER_OWNER = 30;
    private static final CompanionLeases.Lease ANNS_EXPEDITION =
            new CompanionLeases.Lease(CompanionLeases.Kind.EXPEDITION, ANN);

    private final BossAllyHarness h = new BossAllyHarness();

    @BeforeEach
    void annHasThreeBenchedAlliesUnderSleepywood() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.game.roster();
    }

    @AfterEach
    void close() {
        h.close();
    }

    @Test
    void withElevenExpeditionLeasesHeldTheTwelfthCallIsAdmitted() {
        holdExpeditionLeases(11);

        assertEquals(List.of("Orrin"), send("orrin"));

        assertEquals(ANNS_EXPEDITION, h.game.leases.holder(ORRIN));
        assertEquals(12, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
        assertEquals(State.TRAVELLING, h.allies.trip("orrin").state);
    }

    @Test
    void withTwelveHeldTheCallIsDeniedWithNoRoomAndChangesNothing() {
        holdExpeditionLeases(12);

        send("orrin");

        assertNull(h.allies.trip("orrin"));
        assertNull(h.game.leases.holder(ORRIN));
        assertEquals(List.of(), h.bench, "a denied call never ends its live slice");
        verify(h.game.world.benchDuty(), never()).endFor(anyString(), anyString());
        assertEquals(List.of(), h.open.calls, "never enlisted");
        assertNull(h.game.commitments.keyOf(ORRIN));
        assertEquals(List.of(), h.game.timers, "no arrival");
        assertEquals(1, h.said("Orrin").size(), h.whispers.toString());
        assertTrue(isLine(BossRunLines.ALLY_NO_ROOM, null, h.said("Orrin").get(0)), h.whispers.toString());
        assertTrue(h.game.store.events("companion.budget_denied").get(0).contains("expedition burst 12 reached"));
    }

    @Test
    void theCallEndsTheLiveSliceBeforeItTakesTheLease() {
        send("orrin");

        assertEquals(List.of("end orrin lease=null"), h.bench);
        assertEquals(ANNS_EXPEDITION, h.game.leases.holder(ORRIN));
    }

    @Test
    void itIsEnlistedAsAMemberThatLeadsItselfAndCommittedUnderItsOwnersRunKey() {
        send("orrin");

        assertEquals(List.of("enlistSelfLed " + ANN + " [" + ORRIN + "]"), h.open.calls, "never as a follower");
        assertEquals(KEY, h.game.commitments.keyOf(ORRIN));
        CompanionBossAllies.Trip trip = h.allies.trip("orrin");
        assertEquals(SID, trip.sessionId);
        assertEquals(ORRIN, trip.characterId);
        assertTrue(h.runs.containsKey(KEY), "the owner's part of the run is kept from the call on");
    }

    @Test
    void itWhispersItsRecallEtaAndArrivesThenPlusAStagger() {
        send("orrin");

        assertEquals(1, h.said("Orrin").size(), h.whispers.toString());
        assertTrue(isLine(BossRunLines.ALLY_ON_MY_WAY, "~40 s", h.said("Orrin").get(0)), h.whispers.toString());
        assertEquals(1, h.game.timers.size());
        long delay = h.game.timers.get(0).delayMs();
        assertTrue(delay >= 40_000L + ArrivalPlacement.STAGGER_MIN_MS && delay <= 40_000L
                + ArrivalPlacement.STAGGER_MAX_MS, "arrives at " + delay);
    }

    @Test
    void anOwnerGoneOfflineOrAnOfferGoneDropsTheCallWithNoLine() {
        List<Runnable> ops = queuedOps();
        send("orrin");
        send("magna");
        h.game.annOnline = false;
        ops.get(0).run();
        h.game.annOnline = true;
        when(h.expedition.isRegistering()).thenReturn(false); // the leader pressed Start
        ops.get(1).run();

        assertNull(h.allies.trip("orrin"));
        assertNull(h.allies.trip("magna"));
        assertEquals(List.of(), h.bench);
        assertEquals(0, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
        assertEquals(List.of(), h.whispers);
    }

    @Test
    void aLeaseHeldForItsSliceUndoesTheCallAndItCannotCome() {
        h.game.leases.tryAcquire(ORRIN, CompanionLeases.Kind.BENCH_LIVE, ANN); // its slice did not unload

        send("orrin");

        assertNull(h.allies.trip("orrin"));
        assertEquals(CompanionLeases.Kind.BENCH_LIVE, h.game.leases.holder(ORRIN).kind(), "never taken from its slice");
        assertEquals(List.of(), h.open.calls);
        assertEquals(List.of(), h.game.timers);
        assertCannotCome();
    }

    @Test
    void aRefusedEnlistLetsTheLeaseGoAndItCannotCome() {
        h.open.refuse.add(ORRIN);

        send("orrin");

        assertNull(h.allies.trip("orrin"));
        assertNull(h.game.leases.holder(ORRIN));
        assertNull(h.game.commitments.keyOf(ORRIN));
        assertEquals(List.of("enlistSelfLed " + ANN + " [" + ORRIN + "]"), h.open.calls, "nothing to withdraw");
        assertCannotCome();
    }

    @Test
    void aCommitmentToAnotherRunUndoesTheEnlistThenTheLease() {
        h.open.onEnlist = id -> h.game.commitments.commit("pq:kpq:3:" + ANN, ANN, id); // tied elsewhere meanwhile
        List<String> atWithdraw = new ArrayList<>();
        h.open.onWithdraw = id -> atWithdraw.add(h.game.leases.holder(id) + " " + h.game.commitments.keyOf(id));

        send("orrin");

        assertEquals(List.of("enlistSelfLed " + ANN + " [" + ORRIN + "]", "withdraw " + ORRIN), h.open.calls);
        assertEquals(List.of(ANNS_EXPEDITION + " pq:kpq:3:" + ANN), atWithdraw, "withdrawn while its lease is held");
        assertNull(h.game.leases.holder(ORRIN));
        assertEquals("pq:kpq:3:" + ANN, h.game.commitments.keyOf(ORRIN), "another run's commitment is left alone");
        assertNull(h.allies.trip("orrin"));
        assertCannotCome();
    }

    @Test
    void aFailureAfterItsCommitUndoesEveryStepLastFirst() {
        TimerManager timers = TimerManager.getInstance(); // the harness's mock
        doThrow(new IllegalStateException("timers down")).when(timers)
                .schedule(any(TimerManager.SchedulerLane.class), any(Runnable.class), anyLong());
        List<String> atWithdraw = new ArrayList<>();
        h.open.onWithdraw = id -> atWithdraw.add(h.game.leases.holder(id) + " " + h.game.commitments.keyOf(id));

        send("orrin");

        assertEquals(List.of(ANNS_EXPEDITION + " null"), atWithdraw,
                "its commitment went first, then its place in the run, then its lease");
        assertNull(h.game.leases.holder(ORRIN));
        assertNull(h.game.commitments.keyOf(ORRIN));
        assertNull(h.allies.trip("orrin"));
        assertCannotCome();
    }

    @Test
    void twoQuickPicksNeverPassTheShare() {
        h.game.config.EXPEDITION_BURST_MAX = 2;
        List<Runnable> ops = queuedOps();

        assertEquals(List.of("Orrin", "Magna"), send("orrin", "magna", "gunnar"));
        assertEquals(List.of(), send("gunnar"), "the second pick counts the first one's reservations");
        ops.forEach(Runnable::run);

        assertEquals(2, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
        assertEquals(State.TRAVELLING, h.allies.trip("orrin").state);
        assertEquals(State.TRAVELLING, h.allies.trip("magna").state);
        assertNull(h.allies.trip("gunnar"));
        assertEquals(List.of(), send("gunnar"), "both permits held");
    }

    @Test
    void thePeakOfExpeditionLeasesRisesWithEachLeaseAndNeverFalls() {
        send("orrin");
        assertEquals(1, h.bossRuns.expeditionPeak());
        send("magna");
        assertEquals(2, h.bossRuns.expeditionPeak(), "raised as its lease is taken, before it arrives");
        assertEquals(State.TRAVELLING, h.allies.trip("magna").state);

        h.bossRuns.refused(BossAllyHarness.seat(MAGNA), Refusal.BANNED); // it turns back: its lease goes
        assertEquals(1, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
        assertEquals(2, h.bossRuns.expeditionPeak(), "never falls");
        send("gunnar");
        assertEquals(2, h.bossRuns.expeditionPeak(), "two held again is no new peak");

        holdExpeditionLeases(2); // another owner's allies
        h.benched("bryn", "Bryn", BRYN, SLEEPYWOOD_CAMP);
        h.game.world.views().invalidate(ANN); // a new row: the crew cache reads it again
        send("bryn");
        assertEquals(5, h.game.leases.count(CompanionLeases.Kind.EXPEDITION));
        assertEquals(5, h.bossRuns.expeditionPeak(), "every EXPEDITION lease held counts: the server's own peak");
    }

    // ---- P8 step 11: a bench ally's daily Zakum entry ------------------------------------------------------------

    @Test
    void aCappedAllyWhispersItWentInTodayAndNeverTravels() {
        atAdobis();
        try (BossLogDatabase db = new BossLogDatabase()) {
            db.counts.put(ORRIN, 1); // Orrin went in today

            assertEquals(List.of("Orrin"), sendTo("zakum", "orrin"), "the picker cannot know: he is called");

            assertNull(h.allies.trip("orrin"));
            assertNull(h.game.leases.holder(ORRIN), "no lease taken");
            assertEquals(List.of(), h.open.calls, "never enlisted");
            assertNull(h.game.commitments.keyOf(ORRIN));
            assertEquals(List.of(), h.game.timers, "no arrival");
            assertEquals(List.of("count " + ORRIN + " ZAKUM"), db.statements, "the stock count, on companion-ops");
            List<String> said = h.said("Orrin");
            assertEquals(1, said.size(), h.whispers.toString());
            assertTrue(isLine(BossRunLines.QUOTA, null, said.get(0)), h.whispers.toString());
        }
    }

    @Test
    void anAllyWithAnEntryLeftTravelsAndItsSignUpRunsNoJdbc() {
        atAdobis();
        try (BossLogDatabase db = new BossLogDatabase();
             MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(h.game.world);
            when(h.game.world.bossRuns()).thenReturn(h.bossRuns);

            sendTo("zakum", "orrin");

            assertEquals(State.TRAVELLING, h.allies.trip("orrin").state);
            assertEquals(List.of("enlistSelfLed " + ANN + " [" + ORRIN + "]"), h.open.calls);
            assertEquals(List.of("count " + ORRIN + " ZAKUM"), db.statements);
            db.clear();
            // At Adobis, his sign-up runs inside the NPC script: answered from memory.
            assertTrue(ExpeditionBossLog.attemptBoss(ORRIN, BossAllyHarness.CHANNEL, h.expedition, false));
            assertEquals(0, db.connections());
        }
    }

    @Test
    void easyBalrogAlliesAreNeverCounted() {
        try (BossLogDatabase db = new BossLogDatabase()) {
            send("orrin");

            assertEquals(State.TRAVELLING, h.allies.trip("orrin").state);
            assertEquals(0, db.connections(), "Easy Balrog keeps no entry log");
        }
    }

    /** Ann leads a Zakum list at Adobis instead, both switches listing zakum; the list closes in 30 min. */
    private void atAdobis() {
        h.game.config.COMPANION_BOSS_ENABLED_TYPES = "zakum";
        h.game.config.COMPANION_BOSS_ALLY_TYPES = "zakum";
        when(h.game.ann.getClient().getChannelServer().getExpedition(ExpeditionType.ZAKUM)).thenReturn(h.expedition);
        when(h.expedition.getType()).thenReturn(ExpeditionType.ZAKUM);
        when(h.expedition.getMaxSize()).thenReturn(ExpeditionType.ZAKUM.getMaxSize());
        h.closesIn(1_800_000L);
    }

    private List<String> sendTo(String bossKey, String... personaIds) {
        return h.allies.send(h.game.ann, bossKey, List.of(personaIds), h.now);
    }

    private List<String> send(String... personaIds) {
        return h.allies.send(h.game.ann, "easy_balrog", List.of(personaIds), h.now);
    }

    private List<Runnable> queuedOps() {
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(h.game.world.executor()).thenReturn(queued);
        return ops;
    }

    private void holdExpeditionLeases(int count) {
        for (int i = 0; i < count; i++) {
            h.game.leases.tryAcquire(6000 + i, CompanionLeases.Kind.EXPEDITION, OTHER_OWNER);
        }
    }

    private void assertCannotCome() {
        List<String> said = h.said("Orrin");
        assertEquals(1, said.stream().filter(line -> isLine(BossRunLines.ALLY_CANT_COME, null, line)).count(),
                h.whispers.toString());
        assertEquals(said.get(said.size() - 1), said.stream()
                .filter(line -> isLine(BossRunLines.ALLY_CANT_COME, null, line)).findFirst().orElseThrow());
        assertTrue(said.stream().noneMatch(line -> isLine(BossRunLines.ALLY_ON_MY_WAY, "~40 s", line)),
                "no ETA it would not keep: " + said);
    }
}
