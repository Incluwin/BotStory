package server.living.roster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lineup's seats come from its entrance (P6b HPQ step 5): her own choice fills the quest's preferred size when the
 * party can, else its minimum (KPQ: four, else three; HPQ: four, else three), the leader's pick may take up to the
 * quest's maximum (KPQ four, HPQ six), the bench is offered below the preferred size, and the blockers are the
 * entrance's words. KPQ's lineups are exactly as before.
 */
class LineupPlanSeatRulesTest {
    private static final int LEADER = 10;

    @Test
    void kpqStillTakesFourWhenItCanElseThreeAndThePickAtMostFour() {
        assertEquals(3, build("kpq", 1, 2, null).lineup().size());
        assertEquals(4, build("kpq", 1, 3, null).lineup().size());
        LineupPlan six = build("kpq", 1, 6, null);
        assertEquals(4, six.lineup().size());
        assertEquals(3, six.extras().size());
        assertEquals(4, build("kpq", 1, 6, companions(6)).lineup().size(), "the pick: four at most");
        assertEquals(4, build("kpq", 2, 5, companions(5)).lineup().size());
    }

    @Test
    void hpqPrefersFourAndTheLeadersPickMayTakeSix() {
        LineupPlan own = build("hpq", 1, 6, null);
        assertEquals(4, own.lineup().size(), "her choice: the leader and three");
        assertEquals(3, own.extras().size());
        assertTrue(own.startable());
        assertEquals(3, build("hpq", 1, 2, null).lineup().size());
        assertTrue(build("hpq", 1, 2, null).startable());

        LineupPlan pick = build("hpq", 1, 5, companions(5));
        assertEquals(6, pick.lineup().size(), "the leader's pick: up to six");
        assertTrue(pick.startable());
        assertEquals(6, build("hpq", 2, 5, companions(5)).lineup().size());
        assertEquals(5, build("hpq", 1, 4, companions(4)).lineup().size());
        assertTrue(build("hpq", 1, 4, companions(4)).startable());
    }

    @Test
    void theBenchIsOfferedBelowFourInEither() {
        assertTrue(build("hpq", 1, 2, null).benchUseful(), "three: room for a fourth");
        assertFalse(build("hpq", 1, 3, null).benchUseful(), "four: as she would choose");
        assertFalse(build("hpq", 1, 4, companions(4)).benchUseful(), "five, picked");
        assertTrue(build("kpq", 1, 2, null).benchUseful());
        assertFalse(build("kpq", 1, 3, null).benchUseful());
    }

    @Test
    void theBlockersAreTheEntrancesWords() {
        assertEquals("I need at least three of you, all level 10 or higher.", build("hpq", 1, 1, null).blocker());
        assertEquals("I can only send three to six of you up the hill.", build("hpq", 7, 0, null).blocker());
        assertNull(build("hpq", 6, 0, null).blocker(), "six humans may go up together");
        assertTrue(build("hpq", 6, 0, null).startable());

        assertEquals("I need at least three of you, all between level 21 and 30.", build("kpq", 1, 1, null).blocker());
        assertEquals("I can only take three or four of you in.", build("kpq", 5, 0, null).blocker());
        assertFalse(build("kpq", 5, 0, null).startable());
    }

    @Test
    void aCompanionShortOfPotionsSaysSoAtToryToo() {
        List<LineupPlan.Candidate> party = new ArrayList<>();
        party.add(human(LEADER));
        party.add(new LineupPlan.Candidate(companion(901), LineupPlan.Fit.WAITS, DeskLines.LOW_ON_POTIONS, false));
        party.add(new LineupPlan.Candidate(companion(902), LineupPlan.Fit.READY, null, false, 1.0d));

        assertEquals("Mira901 is low on potions.", LineupPlan.build("hpq", LEADER, 20, party, null).blocker());
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The leader and {@code humans} - 1 more players, and {@code seatable} companions of the leader's that can go. */
    private static LineupPlan build(String pqKey, int humans, int seatable, Set<Integer> picked) {
        List<LineupPlan.Candidate> party = new ArrayList<>();
        party.add(human(LEADER));
        for (int i = 1; i < humans; i++) {
            party.add(human(LEADER + i));
        }
        for (int i = 0; i < seatable; i++) {
            party.add(new LineupPlan.Candidate(companion(901 + i), LineupPlan.Fit.READY, null, false, 1.0d));
        }
        return LineupPlan.build(pqKey, LEADER, 20, party, picked);
    }

    /** The first {@code count} companions' ids. */
    private static Set<Integer> companions(int count) {
        Set<Integer> ids = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            ids.add(901 + i);
        }
        return ids;
    }

    private static LineupPlan.Candidate human(int id) {
        return new LineupPlan.Candidate(new LineupPlan.Seat(id, "Player" + id, 20, 100, "Warrior", true, 0, null,
                100_000_200, true, 0L, 0, 0), LineupPlan.Fit.READY, null, false);
    }

    private static LineupPlan.Seat companion(int id) {
        return new LineupPlan.Seat(id, "Mira" + id, 20, 300, "Archer", false, LEADER, "p" + id, 100_000_200, true, 0L,
                0, 0);
    }
}
