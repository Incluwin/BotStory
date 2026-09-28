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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
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
 * as an offline member (its seat there is its own, even at 6/6). Past the grace they go to their duty from the
 * deadline, credited up to now, and the usual lineup is called with travel ETAs (join-then-travel), as it is when the
 * sweep had benched them already. One whisper sums up what the bench earned while Ann was away, once.
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
        World world = Server.getInstance().getWorld(0);
        when(world.getParty(PARTY_ID)).thenReturn(game.party);
        doAnswer(inv -> game.members.remove((Integer) ((PartyCharacter) inv.getArgument(2)).getId()))
                .when(world).updateParty(eq(PARTY_ID), eq(PartyOperation.EXPEL), any());
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
        assertEquals(List.of("spawn Jinx at " + camp, "join Jinx", "spawn Hadrik at " + camp, "join Hadrik"), game.calls,
                "the usual lineup in order, from their camp: they left the offline party and join it again");
        assertTrue(game.store.saved.stream().anyMatch(row -> row.personaId().equals("hadrik")
                && row.state() == RosterState.BENCHED && row.benchedAtMs() == deadline), "benched from the deadline");
        verify(game.residents).whisper(jinx, game.ann, "on my way. ~60 s.");
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

        game.roster().onOwnerLogin(game.ann);
        game.roster().onOwnerLogin(game.ann); // a second login hook for the same session says nothing more

        verify(game.residents).whisper(olwen, game.ann, "while you were away: Olwen +1,234 exp +3,120 meso.");
        verify(game.residents, times(1)).whisper(any(), eq(game.ann), startsWith("while you"));
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
