package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * "Your call" is the companion's own call only until its owner decides (docs/BOT_MANAGEMENT_PLAN.md P5, flow 2): a duty or
 * camp its owner gives it turns it off; Your call's own pick is written as one duty-and-camp change that keeps it on.
 */
class YourCallRosterTest {
    private final LifecycleHarness game = new LifecycleHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aDutyOrACampItsOwnerGivesItEndsItsCall() {
        Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
        BenchDutyDispatcher bench = game.world.benchDuty();

        game.roster().assign(game.ann, olwen, Assignment.FARM_MESO, 0, 0);
        verify(bench).setYourCall(ANN, "olwen", false);
        game.roster().setCamp(game.ann, olwen, HENESYS_FIELD);
        verify(bench, times(2)).setYourCall(ANN, "olwen", false);
    }

    @Test
    void itsOwnPickSetsTheDutyAndCampAtOnceAndKeepsItOn() {
        game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
        BenchDutyDispatcher bench = game.world.benchDuty();

        RosterEntry now = game.roster().applyYourCall(ANN, "olwen", Assignment.TRAIN, HENESYS_FIELD);

        assertEquals(Assignment.TRAIN, now.assignment());
        assertEquals(HENESYS_FIELD, now.campMapId());
        assertEquals(now, game.store.row(ANN, "olwen"));
        verify(bench, never()).setYourCall(anyInt(), anyString(), anyBoolean());
        game.store.seed(game.store.row(ANN, "olwen").with(RosterState.ACTIVE, 1L)); // out with its owner now
        assertNull(game.roster().applyYourCall(ANN, "olwen", Assignment.FARM_MESO, LITH), "only a row on the bench");
    }
}
