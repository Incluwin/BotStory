package server.living.roster;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The roster side of a companion leaving a camp too rough for it (RoughCampTest): a camp its owner sets and a camp it
 * moves itself to are both logged (roster.camp), so its record there starts again; the move keeps its duty; a move made
 * while its owner was offline is told once at the login.
 */
class RoughCampRosterTest {
    // ---- the roster side ---------------------------------------------------------------------

    @Test
    void aCampSetOrMovedIsLoggedSoItsRecordStartsAgainAndTheMoveKeepsItsDuty() {
        LifecycleHarness roster = new LifecycleHarness();
        try {
            Persona olwen = roster.companion("olwen", "Olwen", 900, RosterState.BENCHED, LifecycleHarness.LITH);
            roster.roster().setCamp(roster.ann, olwen, LifecycleHarness.HENESYS_FIELD);
            assertEquals(List.of(LifecycleHarness.ANN + " olwen " + RosterService.CAMP_EVENT + " camp "
                    + LifecycleHarness.HENESYS_FIELD), roster.store.events(RosterService.CAMP_EVENT));

            RosterEntry moved = roster.roster().moveCamp(LifecycleHarness.ANN, "olwen", 104010000, "too rough at 1");

            assertNotNull(moved);
            assertEquals(104010000, roster.store.row(LifecycleHarness.ANN, "olwen").campMapId());
            assertEquals(roster.store.row(LifecycleHarness.ANN, "olwen").assignment(), moved.assignment());
            assertEquals(2, roster.store.events(RosterService.CAMP_EVENT).size());
        } finally {
            roster.close();
        }
    }

    @Test
    void theLoginLineTellsAMoveMadeOfflineOnce() {
        LifecycleHarness roster = new LifecycleHarness();
        try {
            roster.companion("olwen", "Olwen", 900, RosterState.BENCHED, LifecycleHarness.HENESYS_FIELD);
            when(roster.store.store.untoldEvents(LifecycleHarness.ANN, BenchDutyDispatcher.CAMP_MOVED_EVENT,
                    RosterService.CAMP_TOO_ROUGH, RosterService.CAMP_MOVES_TOLD_EVENT)).thenReturn(List.of("olwen"), List.of());
            try (MockedStatic<MapFactory> names = mockStatic(MapFactory.class)) {
                names.when(() -> MapFactory.loadPlaceName(LifecycleHarness.HENESYS_FIELD)).thenReturn("The Hill East of Henesys");
                roster.roster().onOwnerLogin(roster.ann);
                roster.roster().onOwnerLogin(roster.ann); // a channel change: told already
            }
            verify(roster.ann, times(1)).dropMessage(5,
                    "While you were away, Olwen moved camp to The Hill East of Henesys: the old one was too rough.");
            assertEquals(1, roster.store.events(RosterService.CAMP_MOVES_TOLD_EVENT).size());
        } finally {
            roster.close();
        }
    }
}
