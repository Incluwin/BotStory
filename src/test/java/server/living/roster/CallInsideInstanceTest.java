package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Nobody is called into a companion run (P6): while the owner is inside the instance of a party-quest run one of theirs
 * is committed to, or that they went in with, a call is "after the run". A companion with no trip to make (camped on
 * that map's id) would load onto the run's map unregistered, and one from farther away would join the party at once
 * and travel, growing the party mid-run (KerningPQ ends a run it outgrows). Any other event instance (a crane ride, a
 * wedding, a job test) takes a call as it did before P6: the companion sets off, and arrives once its owner is out.
 */
class CallInsideInstanceTest {
    private static final int STAGE = 103000802;
    /** The Orbis-Mu Lung crane's ride map, an event instance of its own for 60 s (Hak.js). */
    private static final int CRANE_RIDE = 200090300;

    private final LifecycleHarness game = new LifecycleHarness();

    @BeforeEach
    void annIsInTheRun() {
        game.annEntersInstance(STAGE);
        game.commitments.commit("pq:kpq:kpq-1", LifecycleHarness.ANN, 950); // one of hers went in with her
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aCompanionWithNoTripToMakeIsNotLoadedOntoTheRunsMap() {
        Persona mira = game.companion("mira", "Mira", 903, RosterState.BENCHED, STAGE); // ETA 0

        RosterResult result = game.roster().call(game.ann, mira);

        assertInstanceOf(RosterResult.InEventInstance.class, result);
        assertEquals(List.of(), game.calls, "no spawn, no join");
        assertEquals(RosterState.BENCHED, game.row(mira).state());
        verify(game.residents).whisper(mira, game.ann, "after the run.");
    }

    @Test
    void aCompanionFromFartherAwayDoesNotJoinThePartyMidRun() {
        Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);

        RosterResult result = game.roster().call(game.ann, olwen);

        assertInstanceOf(RosterResult.InEventInstance.class, result);
        assertEquals(List.of(), game.calls, "no join-then-travel");
        assertEquals(List.of(10), game.members);
        assertNull(game.slots.reservation("olwen"));
    }

    @Test
    void anOwnerWhoWentInWithTheLineupAloneIsInTheRunToo() {
        game.commitments.releaseKey("pq:kpq:kpq-1"); // nobody of hers committed: she went in with a friend's lineup
        CompanionPartyQuestService partyQuests = mock(CompanionPartyQuestService.class);
        when(partyQuests.runForCharacter(LifecycleHarness.ANN)).thenReturn(mock(CompanionRun.class));
        when(game.world.partyQuests()).thenReturn(partyQuests);
        Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);

        assertInstanceOf(RosterResult.InEventInstance.class, game.roster().call(game.ann, olwen));
        assertEquals(List.of(10), game.members);
    }

    @Test
    void anyOtherEventInstanceTakesTheCallAsBefore() {
        game.commitments.releaseKey("pq:kpq:kpq-1");
        game.annEntersInstance(CRANE_RIDE); // the crane ride: no party quest, no run
        Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);

        RosterResult result = game.roster().call(game.ann, olwen);

        assertInstanceOf(RosterResult.Travelling.class, result, "join-then-travel: it arrives once she is off");
        assertTrue(game.members.contains(900));
        verify(game.residents, never()).whisper(olwen, game.ann, "after the run.");
    }
}
