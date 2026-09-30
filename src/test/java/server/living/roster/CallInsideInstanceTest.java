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
import static org.mockito.Mockito.verify;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Nobody is called into a run (P6): while the owner is inside an event instance a call is "after the run". A companion
 * with no trip to make (camped on that map's id) would load onto the run's map unregistered, and one from farther away
 * would join the party at once and travel, growing the party mid-run (KerningPQ ends a run it outgrows).
 */
class CallInsideInstanceTest {
    private static final int STAGE = 103000802;

    private final LifecycleHarness game = new LifecycleHarness();

    @BeforeEach
    void annIsInTheRun() {
        game.annEntersInstance(STAGE);
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
}
