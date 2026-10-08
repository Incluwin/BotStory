package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.roster.CompanionBossAllies.NotOffered;
import server.living.roster.CompanionBossAllies.Picker;
import server.living.roster.CompanionBossAllies.Row;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.ROOM;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Who Mu Young's picker offers an owner on her Easy Balrog list (P7 Part B step 7, CompanionBossAllies.picker): only
 * their BENCHED rows; one below the expedition's levels (Lv50) is not ready; each one's recall ETA runs from the map
 * its live bench slice stands on, else from its camp; one whose ETA plus the widest arrival stagger (8 s) ends past
 * the run's sign-up deadline cannot reach her; one with a trip is already on the way; one tied to a run, in a boss run
 * or loaded for anything but its own slice is busy. An owner brings ROSTER_MAX - 5 allies to one run at most, and how
 * many more she takes is the least of the list's seat share, the server's permit share and that cap.
 */
class CompanionBossAlliesRowsTest {
    private static final int ORRIN = 911;
    private static final int MAGNA = 912;
    private static final int GUNNAR = 913;
    private static final int BRANN = 914;
    private static final int FENNA = 915;
    private static final int ILSE = 916;

    private final BossAllyHarness h = new BossAllyHarness();

    @AfterEach
    void close() {
        h.close();
    }

    @Test
    void onlyTheOwnersBenchedRowsAreShown() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.companion("thane", "Thane", 902, RosterState.ACTIVE, ROOM);
        h.game.companion("ingrid", "Ingrid", 904, RosterState.TRAVELLING, ROOM);
        h.game.companion("flint", "Flint", 905, RosterState.AWAY, ROOM);
        h.game.companionOf(20, "brannock", "Brannock", 920, RosterState.BENCHED, SLEEPYWOOD_CAMP); // a friend's
        h.game.roster();

        Picker picker = picker();

        assertEquals(List.of("orrin"), picker.rows().stream().map(Row::personaId).toList());
        Row orrin = picker.row("orrin");
        assertTrue(orrin.offered(), orrin.toString());
        assertEquals("Orrin", orrin.name());
        assertEquals(70, orrin.level());
        assertEquals("White Knight", orrin.job());
    }

    @Test
    void anAllyBelowTheExpeditionsLevelsIsNotReady() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.game.store.levels.put(ORRIN, 50);
        h.game.store.levels.put(MAGNA, 49);
        h.game.roster();

        Picker picker = picker();

        assertTrue(picker.row("orrin").offered(), "Lv50 is Easy Balrog's lowest");
        assertEquals(NotOffered.NOT_READY, picker.row("magna").why());
    }

    @Test
    void theEtaRunsFromTheMapItsLiveSliceStandsOnElseFromItsCamp() {
        h.benched("orrin", "Orrin", ORRIN, LITH); // Lith Harbor: two roads off, the 60 s cap
        h.benched("magna", "Magna", MAGNA, LITH);
        BenchDutyDispatcher.Slice slice = mock(BenchDutyDispatcher.Slice.class);
        when(slice.mapId()).thenReturn(SLEEPYWOOD_CAMP);
        when(h.game.world.benchDuty().slice("orrin")).thenReturn(slice);
        h.game.roster();

        Picker picker = picker();

        assertEquals(40_000L, picker.row("orrin").etaMs(), "from the field its slice hunts on, under Sleepywood");
        assertEquals(60_000L, picker.row("magna").etaMs(), "from its camp, capped at RECALL_MAX_ETA_MS");
    }

    @Test
    void anAllyWhoseEtaPlusTheWidestStaggerEndsPastTheDeadlineCannotReachHer() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP); // 40 s
        h.game.roster();
        long margin = h.expedition.getStartTime() - h.deadline(); // REGISTRATION_MARGIN_MS

        h.closesIn(margin + 40_000L + 8_000L);
        assertTrue(picker().row("orrin").offered(), "40 s and the 8 s stagger end right at the deadline");

        h.closesIn(margin + 40_000L + 8_000L - 1L);
        assertEquals(NotOffered.TOO_FAR, picker().row("orrin").why());
    }

    @Test
    void anAllyWithATripIsAlreadyOnTheWay() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.game.roster();
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(h.game.world.executor()).thenReturn(queued);

        assertEquals(List.of("Orrin"), h.allies.send(h.game.ann, "easy_balrog", List.of("orrin"), h.now));
        assertEquals(NotOffered.ON_THE_WAY, picker().row("orrin").why(), "its call still waits on companion-ops");
        assertTrue(picker().row("magna").offered());

        ops.forEach(Runnable::run);

        assertEquals(CompanionBossAllies.State.TRAVELLING, h.allies.trip("orrin").state);
        assertEquals(NotOffered.ON_THE_WAY, picker().row("orrin").why(), "on its way down (committed, enlisted)");
    }

    @Test
    void anAllyTiedToARunInABossRunOrLoadedForAnythingButItsOwnSliceIsBusy() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.benched("brann", "Brann", BRANN, SLEEPYWOOD_CAMP);
        h.game.roster();
        h.game.commitments.commit("pq:kpq:3:" + ANN, ANN, ORRIN);
        h.open.inRun.add(MAGNA);
        loaded(GUNNAR); // a Resident life, say
        loaded(BRANN);
        when(h.game.world.benchDuty().live("brann")).thenReturn(true); // its own live slice: the call ends it

        Picker picker = picker();

        assertEquals(NotOffered.BUSY, picker.row("orrin").why(), "tied to a party quest");
        assertEquals(NotOffered.BUSY, picker.row("magna").why(), "in a boss run");
        assertEquals(NotOffered.BUSY, picker.row("gunnar").why(), "loaded for something else");
        assertNull(picker.row("brann").why(), "loaded for its live bench slice");
    }

    @Test
    void anOwnerBringsAtMostRosterMaxLessFiveAlliesToOneRun() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.benched("magna", "Magna", MAGNA, SLEEPYWOOD_CAMP);
        h.benched("gunnar", "Gunnar", GUNNAR, SLEEPYWOOD_CAMP);
        h.benched("brann", "Brann", BRANN, SLEEPYWOOD_CAMP);
        h.benched("fenna", "Fenna", FENNA, SLEEPYWOOD_CAMP);
        h.benched("ilse", "Ilse", ILSE, SLEEPYWOOD_CAMP);
        h.game.roster();
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(h.game.world.executor()).thenReturn(queued);
        assertEquals(5, picker().left(), "ROSTER_MAX 10 - a full party of 5");

        List<String> sent = h.allies.send(h.game.ann, "easy_balrog",
                List.of("orrin", "magna", "gunnar", "brann", "fenna", "ilse"), h.now);

        assertEquals(List.of("Orrin", "Magna", "Gunnar", "Brann", "Fenna"), sent);
        assertEquals(0, picker().left());
        assertTrue(picker().row("ilse").offered(), "Ilse herself could come; the owner's cap is reached");
        assertEquals(List.of(), h.allies.send(h.game.ann, "easy_balrog", List.of("ilse"), h.now));
        ops.forEach(Runnable::run);
        assertEquals(0, picker().left(), "five on their way down");
        assertNull(h.allies.trip("ilse"));

        h.game.config.ROSTER_MAX = 12;
        assertEquals(2, picker().left(), "ROSTER_MAX 12 - 5 = 7");
    }

    @Test
    void howManyMoreSheTakesIsTheLeastOfTheSeatsThePermitsAndTheAlliesLeft() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.roster();
        assertEquals(5, picker().left(), "27 seats, 5 permits of 12, 5 allies");

        h.open.listed = new CompanionBossRuns.Seats(26, 0, 1, 0);
        assertEquals(2, picker().left(), "26 players listed: 30 - 2 kept for players - 26 = 2 seats");

        h.open.listed = new CompanionBossRuns.Seats(1, 0, 1, 0);
        h.game.config.EXPEDITION_BURST_MAX = 3;
        assertEquals(3, picker().left(), "the server holds 3 allies at once");

        h.game.config.EXPEDITION_BURST_MAX = 12;
        h.open.listed = new CompanionBossRuns.Seats(25, 0, 1, 0); // 3 seats
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(ops);
        when(h.game.world.executor()).thenReturn(queued);
        assertEquals(List.of("Orrin"), h.allies.send(h.game.ann, "easy_balrog", List.of("orrin"), h.now));
        assertEquals(2, picker().left(), "its reservation holds a seat before it is enlisted");
        ops.forEach(Runnable::run);
        assertEquals(2, picker().left(), "and once it is enlisted, as one waiting to sign up");
    }

    private Picker picker() {
        Picker picker = h.allies.picker(h.game.ann, "easy_balrog", h.now);
        assertNotNull(picker, "Ann on her list is offered the picker");
        return picker;
    }

    /** The companion is loaded (its Lv70 White Knight character, for the crew view). */
    private void loaded(int characterId) {
        Character bot = h.game.bots.get(characterId);
        when(bot.getLevel()).thenReturn(70);
        when(bot.getJob()).thenReturn(Job.WHITEKNIGHT);
        h.game.loaded.add(characterId);
    }
}
