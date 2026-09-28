package server.living.roster;

import net.server.world.CauseScope;
import net.server.world.PartyEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.PERION;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * HOLD and its rejoin (docs/BOT_MANAGEMENT_PLAN.md, "Seat states", flow 3 steps 4 and 9): a companion waiting outside
 * a run is loaded without follow, out of the party, standing where it was told, its seat kept as a HOLD reservation;
 * it refuses every party join but the roster's own rejoin. When its owner is out of the run (they come into a map
 * that is no event instance's copy) every hold rejoins: it walks over on the same map, or travels from another, once
 * the party changes that waited for the run are reconciled.
 */
class HoldRejoinTest {
    private static final Point BY_LAKELIS = new Point(1450, 120);

    private final LifecycleHarness game = new LifecycleHarness();
    private Persona olwen;

    @BeforeEach
    void outWithAnnInKerning() {
        game.moveAnn(KERNING, 1500, 120);
        olwen = game.companion("olwen", "Olwen", 900, RosterState.ACTIVE, LITH);
        game.outWithAnn(olwen);
        game.roster();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aHoldLeavesThePartyStopsFollowingAndKeepsItsSeat() {
        RosterResult result = game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);

        assertTrue(result.ok());
        assertEquals(List.of("stop Olwen", "leave Olwen", "walk Olwen to 1450,120"), game.calls);
        RosterEntry row = game.row(olwen);
        assertEquals(RosterState.HOLD, row.state());
        assertEquals(KERNING, row.holdMapId());
        assertEquals(CompanionSlots.Kind.HOLD, game.slots.reservation("olwen").kind());
        assertFalse(game.members.contains(900));
        assertEquals(4, game.slots.free(game.ann), "the seat stays taken while it waits");
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ANN), game.leases.holder(900), "still loaded");
        verify(game.residents).whisper(olwen, game.ann, "i'll wait out here till you're done.");
        assertEquals(List.of(ANN + " olwen companion.hold map " + KERNING), game.store.events("companion.hold"));
    }

    @Test
    void aHoldRefusesEveryPartyJoinButTheRostersOwnRejoin() {
        game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);

        assertFalse(game.slots.admitsJoin(game.bot(olwen), game.party), "a stock invite, a lobby, a friend's party");
        try (PartyEvents.Scope cause = CauseScope.open(CompanionPartySync.CAUSE)) {
            assertFalse(game.slots.admitsJoin(game.bot(olwen), game.party), "not even the sync's regroup");
        }
        try (PartyEvents.Scope cause = CauseScope.open(RosterService.CAUSE)) {
            assertTrue(game.slots.admitsJoin(game.bot(olwen), game.party), "the roster's rejoin, on its HOLD seat");
        }
    }

    @Test
    void outOfTheRunOnTheSameMapItRejoinsAndWalksOver() {
        game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);
        game.annEntersInstance(103000800);
        game.roster().ownerEnteredMap(game.ann, game.ann.getMap()); // stage to stage inside the run: nothing
        assertEquals(RosterState.HOLD, game.row(olwen).state());
        game.calls.clear();

        game.moveAnn(KERNING, 1200, 120); // the run is over: the exit is Lakelis's map
        game.roster().ownerEnteredMap(game.ann, game.map(KERNING));

        assertEquals(List.of("join Olwen", "follow Olwen"), game.calls);
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
        assertEquals(0, game.row(olwen).holdMapId());
        assertNull(game.slots.reservation("olwen"), "a member again");
        assertTrue(game.members.contains(900));
        assertEquals(List.of(RosterService.CAUSE), game.causes.subList(game.causes.size() - 1, game.causes.size()));
    }

    @Test
    void outOfTheRunOnAnotherMapItRejoinsAtOnceAndTravels() {
        game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);
        game.calls.clear();

        game.moveAnn(PERION, 900, 200);
        game.roster().ownerEnteredMap(game.ann, game.map(PERION));

        assertEquals(List.of("join Olwen"), game.calls, "in the party at once, the window shows where it is");
        RosterEntry row = game.row(olwen);
        assertEquals(RosterState.TRAVELLING, row.state());
        assertTrue(row.etaAtMs() > System.currentTimeMillis());
        verify(game.residents).whisper(olwen, game.ann, "on my way. ~60 s.");

        game.runTimers();

        assertEquals(List.of("join Olwen", "warp Olwen to " + PERION + " at " + portalX(PERION) + ",200", "follow Olwen"),
                game.calls);
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
    }

    @Test
    void whileTheOwnerIsInTheRunACallIsAfterTheRunAndHoldsStay() {
        game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);
        game.annEntersInstance(103000800);
        game.calls.clear();

        RosterResult result = game.roster().call(game.ann, olwen);
        game.roster().rejoinHolds(game.ann);

        assertInstanceOf(RosterResult.InEventInstance.class, result);
        assertEquals(List.of(), game.calls);
        assertEquals(RosterState.HOLD, game.row(olwen).state());
    }

    @Test
    void aCommittedHoldIsLeftForItsRun() {
        game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);
        game.commitments.commit("expedition-7", ANN, 900);
        game.calls.clear();

        game.roster().rejoinHolds(game.ann);

        assertEquals(List.of(), game.calls);
        assertEquals(RosterState.HOLD, game.row(olwen).state());
    }

    @Test
    void benchingAHoldGivesItsSeatBackAndItWalksOut() {
        game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);
        game.calls.clear();

        RosterResult result = game.roster().bench(game.ann, olwen);

        assertEquals("Olwen heads back to camp to train.", RosterVoice.line(olwen, result));
        assertEquals(RosterState.BENCHED, game.row(olwen).state());
        assertNull(game.slots.reservation("olwen"));
        assertEquals(List.of("walk Olwen to " + portalX(KERNING) + ",200"), game.calls, "it walks to the portal on Ann's map");
    }

    @Test
    void aShareChangeThatWaitedForTheRunIsReconciledBeforeTheHoldsRejoin() {
        game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);
        CompanionPartySync sync = mock(CompanionPartySync.class);
        when(game.world.partySync()).thenReturn(sync);
        // a friend joined her friend's party during the run: its deferred rebalance puts Olwen's hold past her share
        doAnswer(inv -> game.roster().standDown(game.ann, olwen, "party's full.", "companion.overflow"))
                .when(sync).resumeDeferred();
        game.calls.clear();

        game.roster().ownerEnteredMap(game.ann, game.map(KERNING)); // out of the run

        verify(sync).resumeDeferred();
        assertEquals(RosterState.BENCHED, game.row(olwen).state());
        assertFalse(game.calls.contains("join Olwen"), "no join and a step-out seconds later: " + game.calls);
    }

    @Test
    void aHoldParksWithItsOwnerAndWalksBackInAtTheirLogin() {
        game.roster().hold(game.ann, olwen, KERNING, BY_LAKELIS);
        game.roster().onOwnerLogout(game.ann);
        assertTrue(game.calls.contains("remove Olwen"));
        RosterEntry parked = game.row(olwen);
        assertEquals(RosterState.PARKED, parked.state(), "a hold parks with its owner (decision 8a)");
        assertEquals(0, parked.holdMapId());
        assertNull(game.slots.reservation("olwen"), "its HOLD seat went back");
        game.moveAnn(PERION, 900, 200);
        game.calls.clear();

        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        assertEquals(List.of("spawn Olwen at " + PERION + " " + portalX(PERION) + ",200", "join Olwen", "follow Olwen"),
                game.calls, "within the grace it walks in from the portal nearest Ann");
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
    }
}
