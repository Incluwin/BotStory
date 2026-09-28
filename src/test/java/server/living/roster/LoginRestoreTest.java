package server.living.roster;

import net.server.Server;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.PARTY_ID;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * The login after a logout (decision 8a, flow 7 step 6). Within the grace the PARKED companions log back in one by
 * one, 2-8 s apart once the field has loaded, each walking in from the portal nearest Ann into the party it stayed in
 * as an offline member (its seat there is its own, even at 6/6), without a join line. Past the grace they go to their
 * duty from the deadline, credited up to now, and the usual lineup is called with travel ETAs (join-then-travel) on the
 * seats they kept, as it is when the sweep had benched them already; one that cannot come leaves the party then. A
 * parked one benched, refused or released before its restore leaves the party it stayed in, and a logout before the
 * restore is done keeps the whole lineup. One whisper sums up what the bench earned while Ann was away, once.
 */
class LoginRestoreTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private Persona hadrik;
    private Persona jinx;
    private Persona olwen;

    @BeforeEach
    void parkedWithAnnInHerOwnParty() {
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        jinx = game.companion("jinx", "Jinx", 902, RosterState.ACTIVE, LITH);
        olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
        game.outWithAnn(jinx);
        game.outWithAnn(hadrik);
        game.roster().onOwnerLogout(game.ann, null);
        game.offline.addAll(List.of(901, 902));
        game.calls.clear();
        game.causes.clear();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void withinTheGraceTheyLogBackInOneByOneWalkingInFromThePortal() {
        game.roster().onOwnerLogin(game.ann);

        assertEquals(2, game.timers.size());
        assertEquals(ArrivalPlacement.LOGIN_ARRIVAL_MS, game.timers.get(0).delayMs(), "the first once the field has loaded");
        long gap = game.timers.get(1).delayMs() - game.timers.get(0).delayMs();
        assertTrue(gap >= ArrivalPlacement.STAGGER_MIN_MS && gap <= ArrivalPlacement.STAGGER_MAX_MS, "2-8 s apart: " + gap);

        game.runDueTimers();

        String portal = HENESYS_FIELD + " " + portalX(HENESYS_FIELD) + ",200";
        assertEquals(List.of("spawn Jinx at " + portal, "follow Jinx", "spawn Hadrik at " + portal, "follow Hadrik"),
                game.calls, "in lineup order, back in the party they stayed in (no new join)");
        assertEquals(List.of(RosterService.CAUSE, RosterService.CAUSE), game.causes, "already members: the party row updates");
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertEquals(RosterState.ACTIVE, game.row(jinx).state());
        assertEquals(List.of(ANN, 902, 901), game.members);
    }

    @Test
    void anOfflineMemberHasItsSeatEvenInAFullParty() {
        game.members.addAll(List.of(950, 951, 952)); // three guests joined Ann's party meanwhile: 6/6

        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertEquals(RosterState.ACTIVE, game.row(jinx).state());
        assertEquals(6, game.members.size());
    }

    @Test
    void pastTheGraceTheyGoToTheirDutyAndTheUsualLineupIsCalledWithTravel() {
        World world = expels();
        long graceMs = game.config.LEADER_OFFLINE_BENCH_MS;
        ParkGraceTest.parkedAgo(game, hadrik, graceMs + 60_000L);
        ParkGraceTest.parkedAgo(game, jinx, graceMs + 60_000L);
        long deadline = game.row(hadrik).parkedAtMs() + graceMs;

        game.roster().onOwnerLogin(game.ann);

        verify(game.world.bench(), times(2)).accrueNow(any()); // their duty since the deadline, before the call
        for (Persona persona : List.of(hadrik, jinx)) {
            RosterEntry row = game.row(persona);
            assertEquals(RosterState.TRAVELLING, row.state(), persona.name());
            assertTrue(row.etaAtMs() > System.currentTimeMillis(), "join-then-travel");
        }
        String camp = LITH + " " + portalX(LITH) + ",200";
        assertEquals(List.of("spawn Jinx at " + camp, "spawn Hadrik at " + camp), game.calls,
                "the usual lineup in order, from their camp, on the seats they kept as offline members: no join");
        verify(world, never()).updateParty(anyInt(), any(), any()); // no stock expel notice in front of Ann
        assertEquals(List.of(ANN, 902, 901), game.members);
        assertTrue(game.store.saved.stream().anyMatch(row -> row.personaId().equals("hadrik")
                && row.state() == RosterState.BENCHED && row.benchedAtMs() == deadline), "benched from the deadline");
        verify(game.residents).whisper(jinx, game.ann, "on my way. ~60 s.");
    }

    @Test
    void pastTheGraceOneThatCannotComeBackLeavesThePartyItStayedIn() {
        World world = expels();
        when(game.budget.admit(any(), any(client.Character.class))).thenReturn(CompanionBudget.Admission.deny("test: no load"));
        long graceMs = game.config.LEADER_OFFLINE_BENCH_MS;
        ParkGraceTest.parkedAgo(game, hadrik, graceMs + 60_000L);
        ParkGraceTest.parkedAgo(game, jinx, graceMs + 60_000L);

        game.roster().onOwnerLogin(game.ann);

        assertEquals(RosterState.BENCHED, game.row(hadrik).state());
        assertEquals(RosterState.BENCHED, game.row(jinx).state());
        verify(world, times(2)).updateParty(eq(PARTY_ID), eq(PartyOperation.EXPEL), any());
        assertEquals(List.of(ANN), game.members, "no ghost offline member keeps a seat");
    }

    /** Ann's party is the world's, and an expel takes the member out of it. */
    private World expels() {
        World world = Server.getInstance().getWorld(0);
        when(world.getParty(PARTY_ID)).thenReturn(game.party);
        doAnswer(inv -> game.members.remove((Integer) ((PartyCharacter) inv.getArgument(2)).getId()))
                .when(world).updateParty(eq(PARTY_ID), eq(PartyOperation.EXPEL), any());
        return world;
    }

    @Test
    void afterTheSweepSentThemToTheirDutyTheLoginCallsTheUsualLineup() {
        game.annOnline = false;
        ParkGraceTest.parkedAgo(game, hadrik, game.config.LEADER_OFFLINE_BENCH_MS + 60_000L);
        ParkGraceTest.parkedAgo(game, jinx, game.config.LEADER_OFFLINE_BENCH_MS + 60_000L);
        game.roster().benchParkedPastGrace();
        game.members.removeAll(List.of(901, 902)); // the sweep's expel
        game.annOnline = true;

        game.roster().onOwnerLogin(game.ann);

        assertEquals(RosterState.TRAVELLING, game.row(jinx).state());
        assertEquals(RosterState.TRAVELLING, game.row(hadrik).state());
        assertEquals(RosterState.BENCHED, game.row(olwen).state(), "only the usual lineup is called");
    }

    @Test
    void oneWhisperSumsUpWhatTheBenchEarnedWhileAway() {
        RosterEntry row = game.row(olwen); // it banked this on its duty meanwhile
        game.store.seed(new RosterEntry(ANN, "olwen", RosterState.BENCHED, row.assignment(), 0, 0, LITH, 1_234L, 3_120L,
                0L, row.recruitedAtMs(), row.benchedAtMs(), row.lastLedgerAtMs(), 0, null));
        game.store.levels.put(900, 12); // 2,144 exp to its next level

        game.roster().onOwnerLogin(game.ann);
        game.roster().onOwnerLogin(game.ann); // a second login hook for the same session says nothing more

        verify(game.residents).whisper(olwen, game.ann, "while you were away: me +57% exp +3,120 meso.");
        verify(game.residents, times(1)).whisper(any(), eq(game.ann), startsWith("while you"));
    }

    @Test
    void theWhisperNamesThreeAndCountsTheRest() {
        game.store.lineup.clear();
        for (String[] bench : new String[][] {{"brin", "Brin", "903"}, {"cato", "Cato", "904"}, {"dara", "Dara", "905"},
                {"esk", "Esk", "906"}}) {
            game.companion(bench[0], bench[1], Integer.parseInt(bench[2]), RosterState.BENCHED, LITH);
        }
        game.roster().onOwnerLogout(game.ann, null); // the baseline, with all five on the bench
        for (String id : List.of("olwen", "brin", "cato", "dara", "esk")) {
            RosterEntry row = game.store.row(ANN, id);
            game.store.seed(new RosterEntry(ANN, id, RosterState.BENCHED, row.assignment(), 0, 0, LITH, 0L, 500L, 0L,
                    row.recruitedAtMs(), row.benchedAtMs(), row.lastLedgerAtMs(), 0, null));
        }

        game.roster().onOwnerLogin(game.ann);

        verify(game.residents).whisper(olwen, game.ann,
                "while you were away: me +500 meso, Brin +500 meso, Cato +500 meso, and 2 others.");
    }

    @Test
    void withinTheGraceTheyWalkBackInWithoutAJoinLine() {
        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        verify(game.residents, never()).reply(any(), any(), anyString());
    }

    @Test
    void aLogoutDuringTheStaggeredRestoreKeepsTheWholeUsualLineup() {
        game.roster().onOwnerLogin(game.ann);
        game.timers.remove(0).task().run(); // Jinx is back; Hadrik has not walked in yet
        assertEquals(RosterState.ACTIVE, game.row(jinx).state());
        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        game.timers.clear();

        game.roster().onOwnerLogout(game.ann, null); // gone again before Hadrik's turn
        game.offline.add(902);
        ParkGraceTest.parkedAgo(game, hadrik, game.config.LEADER_OFFLINE_BENCH_MS + 60_000L);
        ParkGraceTest.parkedAgo(game, jinx, game.config.LEADER_OFFLINE_BENCH_MS + 60_000L);
        game.roster().onOwnerLogin(game.ann);

        assertEquals(java.util.Map.of("jinx", 1, "hadrik", 2), game.store.store.lineupSlots(ANN));
        assertEquals(RosterState.TRAVELLING, game.row(jinx).state());
        assertEquals(RosterState.TRAVELLING, game.row(hadrik).state(), "a parked one keeps its place in the lineup");
    }

    @Test
    void aParkedCompanionStoodDownBeforeItsRestoreLeavesThePartyItStayedIn() {
        World world = expels();
        game.roster().onOwnerLogin(game.ann);

        game.roster().standDown(game.ann, hadrik, "party's full.", "companion.overflow"); // a friend's JOIN rebalance

        assertEquals(RosterState.BENCHED, game.row(hadrik).state());
        verify(world).updateParty(eq(PARTY_ID), eq(PartyOperation.EXPEL), any());
        assertEquals(List.of(ANN, 902), game.members, "no ghost keeps a seat against Ann's share");
    }

    @Test
    void aParkedCompanionReleasedBeforeItsRestoreLeavesThePartyItStayedIn() {
        World world = expels();
        when(game.world.contracts()).thenReturn(mock(server.living.bond.ContractService.class));
        game.roster().onOwnerLogin(game.ann);

        game.roster().release(game.ann, hadrik, "dismissed");

        assertEquals(List.of(ANN, 902), game.members);
        verify(world).updateParty(eq(PARTY_ID), eq(PartyOperation.EXPEL), any());
    }

    @Test
    void aRestoreRefusedForTheBudgetLeavesThePartyItStayedIn() {
        World world = expels();
        when(game.budget.admit(any(), any(client.Character.class))).thenReturn(CompanionBudget.Admission.deny("test: no load"));
        game.roster().onOwnerLogin(game.ann);

        game.runDueTimers();

        assertEquals(RosterState.BENCHED, game.row(hadrik).state());
        assertEquals(RosterState.BENCHED, game.row(jinx).state());
        verify(world, times(2)).updateParty(eq(PARTY_ID), eq(PartyOperation.EXPEL), any());
        assertEquals(List.of(ANN), game.members);
    }

    @Test
    void aBenchThatEarnedNothingSaysNothing() {
        game.roster().onOwnerLogin(game.ann);

        verify(game.residents, never()).whisper(any(), any(), startsWith("while you"));
    }

    @Test
    void aLoginWithoutALogoutCallsNobody() {
        game.roster().onOwnerLogin(game.ann); // within the grace: they log back in
        game.runDueTimers();
        List<String> after = new ArrayList<>(game.calls);

        game.roster().onOwnerLogin(game.ann); // the logout hook's own restore for the same session
        game.runDueTimers();

        assertEquals(after, game.calls, "nothing is loaded twice");
    }
}
