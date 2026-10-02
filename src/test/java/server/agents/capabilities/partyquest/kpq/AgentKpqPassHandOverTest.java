package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage-5 hand-over's own rules, apart from the coordinator: where a pass goes down for the leader to pick it up
 * where he stands, and when a pass on the floor stops counting as on its way to him.
 */
class AgentKpqPassHandOverTest {
    private static final Point LEADER = new Point(260, -2717);

    @Test
    void aPassGoesDownOnlyOnTheLeadersOwnSpotWithBothOnAFoothold() {
        assertTrue(AgentKpqPassHandOver.atFeet(new Point(270, -2717), LEADER, true, true, 10));
        assertTrue(AgentKpqPassHandOver.atFeet(new Point(250, -2707), LEADER, true, true, 10));
        assertFalse(AgentKpqPassHandOver.atFeet(new Point(271, -2717), LEADER, true, true, 10));
        // Level with him in x but on another foothold 40 px up: the pass would land where he cannot pick it up.
        assertFalse(AgentKpqPassHandOver.atFeet(new Point(260, -2757), LEADER, true, true, 10));
        assertFalse(AgentKpqPassHandOver.atFeet(new Point(260, -2677), LEADER, true, true, 10));
        // Never while either of them is in the air or on a rope or ladder.
        assertFalse(AgentKpqPassHandOver.atFeet(new Point(260, -2717), LEADER, false, true, 10));
        assertFalse(AgentKpqPassHandOver.atFeet(new Point(260, -2717), LEADER, true, false, 10));
        assertFalse(AgentKpqPassHandOver.atFeet(null, LEADER, true, true, 10));
    }

    @Test
    void aCarrierIsAboutToGoDownOnlyLowOnHpOutOfPotionsWithAMobAlive() {
        assertTrue(AgentKpqPassHandOver.lowOnHp(124, 500, 0.25));
        assertFalse(AgentKpqPassHandOver.lowOnHp(125, 500, 0.25));
        assertFalse(AgentKpqPassHandOver.lowOnHp(0, 500, 0.25));
        assertTrue(AgentKpqPassHandOver.aboutToGoDown(true, 0, true));
        assertFalse(AgentKpqPassHandOver.aboutToGoDown(false, 0, true));
        assertFalse(AgentKpqPassHandOver.aboutToGoDown(true, 3, true));
        // Nothing left to fight: in no danger.
        assertFalse(AgentKpqPassHandOver.aboutToGoDown(true, 0, false));
        // A count the gateway cannot tell is not an empty bag.
        assertFalse(AgentKpqPassHandOver.aboutToGoDown(true, -1, true));
    }

    @Test
    void aFloorPassIsStaleOnlyPastThePatienceOutOfReachUnfetchedAndNotWaitingByCloto() {
        assertTrue(AgentKpqPassHandOver.stale(30_000L, false, false, false, 30_000L));
        assertFalse(AgentKpqPassHandOver.stale(29_999L, false, false, false, 30_000L));
        // At the leader's feet, he can pick it up whenever he likes.
        assertFalse(AgentKpqPassHandOver.stale(40_000L, true, false, false, 30_000L));
        // Left by Cloto, where he has to go.
        assertFalse(AgentKpqPassHandOver.stale(40_000L, false, true, false, 30_000L));
        // A companion is on its way to it.
        assertFalse(AgentKpqPassHandOver.stale(40_000L, false, false, true, 30_000L));
    }

    // Where a drop for the leader goes down: the map lands a player's drop on the first foothold under a point 85 px
    // over the dropper. These stand in for the map: a flat floor at y 0, and platforms over parts of it.

    @Test
    void aDropLandsAtASpotOnlyWithinThePixelsOnBothAxes() {
        assertTrue(AgentKpqPassHandOver.landsAt(new Point(10, -10), new Point(0, 0), 10));
        assertFalse(AgentKpqPassHandOver.landsAt(new Point(11, 0), new Point(0, 0), 10));
        // Caught on the ramp 83 px over him, live.
        assertFalse(AgentKpqPassHandOver.landsAt(new Point(470, 28), new Point(464, 111), 10));
        assertFalse(AgentKpqPassHandOver.landsAt(null, new Point(0, 0), 10));
    }

    @Test
    void withNothingOverHimHisOwnSpotIsTheDropSpot() {
        assertEquals(FLOOR_SPOT, dropSpot(FLOOR_SPOT, 6, x -> false));
    }

    @Test
    void aPlatformOverHisRightSendsTheDropperToTheNearestClearSpotOnHisLeft() {
        // Caught from x 3 on, as the ramp over the live leader caught a drop 6 px right of him.
        Point spot = dropSpot(FLOOR_SPOT, 6, x -> x >= 3);

        // Every spot within 10 px of it, where a precise walk may stop, lands on the floor.
        assertEquals(new Point(-10, 0), spot);
    }

    @Test
    void theDroppersOwnSideIsTriedFirstAtTheSameDistance() {
        // A narrow platform right over him: both sides clear 15 px out.
        assertEquals(new Point(15, 0), dropSpot(FLOOR_SPOT, 6, x -> Math.abs(x) <= 4));
        assertEquals(new Point(-15, 0), dropSpot(FLOOR_SPOT, -6, x -> Math.abs(x) <= 4));
    }

    @Test
    void aSpotIsLookedForOnlyOnHisOwnFootholdWithinTheSearch() {
        // Clear only past 60 px: his foothold reaches there, but it is past the search.
        assertNull(dropSpot(FLOOR_SPOT, 6, x -> Math.abs(x) < 61));
        // Clear 30 px left of him, but his foothold ends 20 px left, over a drop to y 200.
        UnaryOperator<Point> ledge = point -> new Point(point.x, point.x < -20 ? 200 : 0);
        assertNull(AgentKpqPassHandOver.dropSpot(FLOOR_SPOT, -6, ledge, landing(x -> x > -30), 10, 60, 5));
        // Gentle slopes are still his foothold.
        UnaryOperator<Point> slope = point -> new Point(point.x, point.x / 2);
        assertEquals(new Point(-30, -15),
                AgentKpqPassHandOver.dropSpot(FLOOR_SPOT, -6, slope, landing(x -> x > -20), 10, 60, 5));
    }

    @Test
    void noSpotWhereEveryDropIsCaughtOrNoneCanBeTold() {
        assertNull(dropSpot(FLOOR_SPOT, 6, x -> true));
        assertNull(AgentKpqPassHandOver.dropSpot(FLOOR_SPOT, 6, FLAT, point -> null, 10, 60, 5));
        assertNull(AgentKpqPassHandOver.dropSpot(null, 6, FLAT, landing(x -> false), 10, 60, 5));
    }

    private static final Point FLOOR_SPOT = new Point(0, 0);
    /** The floor at y 0 everywhere. */
    private static final UnaryOperator<Point> FLAT = point -> new Point(point.x, 0);

    /** The spot on the flat floor for these values: 10 px at his feet, 60 px of search, 5 px steps. */
    private static Point dropSpot(Point leaderSpot, int dropperSide, IntPredicate caughtAt) {
        return AgentKpqPassHandOver.dropSpot(leaderSpot, dropperSide, FLAT, landing(caughtAt), 10, 60, 5);
    }

    /** A drop from x lands on a platform 60 px over the ground there where caught, else where it was dropped. */
    private static UnaryOperator<Point> landing(IntPredicate caughtAt) {
        return from -> new Point(from.x, caughtAt.test(from.x) ? from.y - 60 : from.y);
    }
}
