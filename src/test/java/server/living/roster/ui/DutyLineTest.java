package server.living.roster.ui;

import org.junit.jupiter.api.Test;
import server.living.roster.CompanionView;
import server.living.store.LivingStore.RosterState;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DutyLineTest {

    private static CompanionView view(RosterState state, boolean loaded, String where, String camp) {
        return new CompanionView("brannock", "Brannock", state, loaded, 15, "Warrior", where, 951, 951, "train", 0, 0L,
                100, 100, 10, camp, 0, 0, "", 0L, 0, "", null, false, false);
    }

    @Test
    void aBenchedCompanionStillWalkingOutReadsItsCampNotTheMapItStandsOn() {
        // seen live: benched in Kerning, still loaded there while it walked to the portal, camp Lith Harbor
        CompanionView walkingOut = view(RosterState.BENCHED, true, "Kerning City Construction Site", "Lith Harbor");
        assertEquals("train @ Lith Harbor", CompanionMenus.duty(walkingOut));
    }

    @Test
    void anErrandReadsItsCampTooAndFallsBackToWhereItIsWithNoCamp() {
        assertEquals("away: train @ Ant Tunnel", CompanionMenus.duty(view(RosterState.AWAY, true, "Ant Tunnel I", "Ant Tunnel")));
        assertEquals("train @ Perion", CompanionMenus.duty(view(RosterState.BENCHED, false, "Perion", "")));
    }
}
