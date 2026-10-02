package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
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
        assertNull(AgentKpqPassHandOver.dropSpot(FLOOR_SPOT, new Point(-6, 0), null, ledge, landing(x -> x > -30), 10,
                60, 5));
        // Gentle slopes are still his foothold.
        UnaryOperator<Point> slope = point -> new Point(point.x, point.x / 2);
        assertEquals(new Point(-30, -15),
                AgentKpqPassHandOver.dropSpot(FLOOR_SPOT, new Point(-6, -3), null, slope, landing(x -> x > -20), 10, 60,
                        5));
    }

    @Test
    void noSpotWhereEveryDropIsCaughtOrNoneCanBeTold() {
        assertNull(dropSpot(FLOOR_SPOT, 6, x -> true));
        assertNull(AgentKpqPassHandOver.dropSpot(FLOOR_SPOT, new Point(6, 0), null, FLAT, point -> null, 10, 60, 5));
        assertNull(AgentKpqPassHandOver.dropSpot(null, new Point(6, 0), null, FLAT, landing(x -> false), 10, 60, 5));
    }

    @Test
    void aDropperStandingOverACatchNarrowerThanAStepIsNotSentToTheSpotItStandsBy() {
        // A 3 px ledge at x 2 to 4 between the 5 px looks at 0 and 5: from his spot and every look within 10 px of
        // it a drop lands on the floor, but the dropper 3 px from him would drop onto the ledge.
        IntPredicate ledge = x -> x >= 2 && x <= 4;
        // Told where it stands, it goes to the nearest spot whose stops leave its own out, 10 px left of him.
        assertEquals(new Point(-10, 0), dropSpot(FLOOR_SPOT, 3, ledge));
        // Without it, his own spot: the coordinator then dropped into the ledge it had just seen.
        assertEquals(FLOOR_SPOT,
                AgentKpqPassHandOver.dropSpot(FLOOR_SPOT, new Point(3, 0), null, FLAT, landing(ledge), 10, 60, 5));
    }

    @Test
    void aDropperByASpotWhereItsOwnDropIsCaughtIsSentToTheNextSpotNotLeftOnIt() {
        // Caught from x 3 on, so the spot is 10 px left of him; a 1 px catch at x -12 lies between its looks at -15
        // and -10, where a precise walk to -10 may stop.
        IntPredicate caught = x -> x >= 3 || x == -12;
        assertEquals(new Point(-10, 0), dropSpot(FLOOR_SPOT, -30, caught));
        // Standing there, at -12, its own drop is caught: the next spot whose stops leave it out, not -10 again,
        // where it walked on the spot it stood on until its bound ran out.
        assertEquals(new Point(-25, 0), dropSpot(FLOOR_SPOT, -12, caught));
        assertEquals(new Point(-10, 0), AgentKpqPassHandOver.dropSpot(FLOOR_SPOT, new Point(-12, 0), null, FLAT,
                landing(caught), 10, 60, 5));
    }

    @Test
    void aDeliverersStepAsideNeverPutsTheDropOutOfHisReach() {
        // 10 px at his feet within 85 px of reach: a spot further than 75 px could land a drop out of it.
        assertEquals(75, AgentKpqPassHandOver.spotReach(120, 85, 10));
        assertEquals(75, AgentKpqPassHandOver.spotReach(75, 85, 10));
        assertEquals(60, AgentKpqPassHandOver.spotReach(60, 85, 10));
        assertEquals(0, AgentKpqPassHandOver.spotReach(0, 85, 10));
        assertEquals(0, AgentKpqPassHandOver.spotReach(60, 5, 10));
    }

    @Test
    void whereADropLiesAgainstHimIsByWhereTheMapLandsIt() {
        Point him = new Point(464, 112);
        assertEquals(AgentKpqPassHandOver.PassPlace.FEET, placeOf(new Point(454, 112), him));
        assertEquals(AgentKpqPassHandOver.PassPlace.FEET, placeOf(null, him));
        assertEquals(AgentKpqPassHandOver.PassPlace.LEFT, placeOf(new Point(428, 115), him));
        assertEquals(AgentKpqPassHandOver.PassPlace.RIGHT, placeOf(new Point(500, 109), him));
        // Live, on the ramp 83 px over him.
        assertEquals(AgentKpqPassHandOver.PassPlace.ABOVE, placeOf(new Point(470, 28), him));
    }

    @Test
    void passesOfSeveralDropsAreToldByWhereHeHasToGo() {
        AgentKpqPassHandOver.PassPlace feet = AgentKpqPassHandOver.PassPlace.FEET;
        AgentKpqPassHandOver.PassPlace left = AgentKpqPassHandOver.PassPlace.LEFT;
        AgentKpqPassHandOver.PassPlace right = AgentKpqPassHandOver.PassPlace.RIGHT;
        AgentKpqPassHandOver.PassPlace above = AgentKpqPassHandOver.PassPlace.ABOVE;
        assertEquals(feet, feet.and(feet));
        assertEquals(left, feet.and(left));
        assertEquals(left, left.and(feet));
        assertEquals(left, left.and(left));
        assertEquals(AgentKpqPassHandOver.PassPlace.AROUND, left.and(right));
        assertEquals(AgentKpqPassHandOver.PassPlace.AROUND, AgentKpqPassHandOver.PassPlace.AROUND.and(left));
        assertEquals(above, right.and(above));
        assertEquals(above, above.and(feet));
        assertEquals(right, right.and(null));
    }

    @Test
    void theLinesSayWhereThePassesLieAndSoundLikePlayers() {
        assertEquals("Ben, the passes are at your feet. pick them up and talk to cloto.",
                AgentKpqPassHandOver.stageOnePassesLine("Ben", AgentKpqPassHandOver.PassPlace.FEET));
        assertEquals("Ben, the passes are at your feet. grab them and talk to cloto.",
                AgentKpqPassHandOver.stageFivePassesLine("Ben", AgentKpqPassHandOver.PassPlace.FEET));
        assertEquals("Ben, got the rest of the passes, they're at your feet.",
                AgentKpqPassHandOver.recollectedLine("Ben"));
        assertEquals("Ben, the passes are just to your right. grab them and talk to cloto.",
                AgentKpqPassHandOver.stageFivePassesLine("Ben", AgentKpqPassHandOver.PassPlace.RIGHT));
        assertEquals("Ben, the passes are right around you. pick them up and talk to cloto.",
                AgentKpqPassHandOver.stageOnePassesLine("Ben", AgentKpqPassHandOver.PassPlace.AROUND));
        assertEquals("Ben, got the rest of the passes, they landed up above you.",
                AgentKpqPassHandOver.recollectedLine("Ben", AgentKpqPassHandOver.PassPlace.ABOVE));
        Pattern engine = Pattern.compile("->|\\d+\\s*/\\s*\\d+|%|~|(?i:dps|estimate)");
        for (AgentKpqPassHandOver.PassPlace place : AgentKpqPassHandOver.PassPlace.values()) {
            for (String line : List.of(AgentKpqPassHandOver.stageOnePassesLine("Ben", place),
                    AgentKpqPassHandOver.stageFivePassesLine("Ben", place),
                    AgentKpqPassHandOver.recollectedLine("Ben", place))) {
                String spoken = line.substring("Ben".length());
                assertEquals(spoken.toLowerCase(Locale.ROOT), spoken, line);
                assertFalse(engine.matcher(line).find(), line);
                assertTrue(line.startsWith("Ben, "), line);
            }
        }
    }

    private static final Point FLOOR_SPOT = new Point(0, 0);

    /** Where a drop landing there lies against him, at 10 px for his feet. */
    private static AgentKpqPassHandOver.PassPlace placeOf(Point landing, Point him) {
        return AgentKpqPassHandOver.PassPlace.of(landing, him, 10);
    }
    /** The floor at y 0 everywhere. */
    private static final UnaryOperator<Point> FLAT = point -> new Point(point.x, 0);

    /**
     * The spot on the flat floor for these values (10 px at his feet, 60 px of search, 5 px steps) for a dropper
     * standing on the floor at {@code dropperX}, whose own drop lands as any other there.
     */
    private static Point dropSpot(Point leaderSpot, int dropperX, IntPredicate caughtAt) {
        Point dropper = new Point(dropperX, 0);
        return AgentKpqPassHandOver.dropSpot(leaderSpot, dropper, landing(caughtAt).apply(dropper), FLAT,
                landing(caughtAt), 10, 60, 5);
    }

    /** A drop from x lands on a platform 60 px over the ground there where caught, else where it was dropped. */
    private static UnaryOperator<Point> landing(IntPredicate caughtAt) {
        return from -> new Point(from.x, caughtAt.test(from.x) ? from.y - 60 : from.y);
    }
}
