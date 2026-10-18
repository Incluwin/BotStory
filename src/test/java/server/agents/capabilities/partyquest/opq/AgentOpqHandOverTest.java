package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-18, the rules of a human-led OPQ run's fragment hand-overs (AgentOpqHandOver, PPQ's medal shape): walk to
 * him, drop at his feet or right beside him by where the map lands it, go to Eak when he cannot be reached or the walk
 * runs too long, and pick a stack he leaves lying back up before it expires, a bounded number of times.
 */
class AgentOpqHandOverTest {
    private static final Point LEADER = new Point(500, -138);

    private static AgentOpqHandOver.HandOver branch(Point carrier, Point landing, boolean reachable, long walkingMs) {
        return AgentOpqHandOver.handOver(carrier, LEADER, true, true, landing, reachable, walkingMs, 30_000L, 60);
    }

    @Test
    void aHolderWalksToHimAndDropsWhereTheStackLandsAtHisFeet() {
        assertEquals(AgentOpqHandOver.HandOver.WALK, branch(new Point(300, -138), null, true, 0L));
        assertEquals(AgentOpqHandOver.HandOver.DROP_AT_FEET, branch(new Point(530, -138), null, true, 0L));
        assertEquals(AgentOpqHandOver.HandOver.DROP_AT_FEET,
                branch(new Point(530, -138), new Point(520, -138), true, 0L));
        assertEquals(AgentOpqHandOver.HandOver.DROP_BESIDE,
                branch(new Point(530, -138), new Point(600, -138), true, 0L));
        assertEquals(AgentOpqHandOver.HandOver.WALK, branch(new Point(530, -138), new Point(530, -40), true, 0L),
                "a drop that would fall off his ledge is no hand-over");
    }

    @Test
    void anUnreachableLeaderOrALongWalkSendsTheStackToEak() {
        assertEquals(AgentOpqHandOver.HandOver.BY_EAK, branch(new Point(300, -138), null, false, 0L));
        assertEquals(AgentOpqHandOver.HandOver.BY_EAK, branch(new Point(300, -138), null, true, 30_001L));
        assertEquals(AgentOpqHandOver.Place.EAK,
                AgentOpqHandOver.place(AgentOpqHandOver.HandOver.BY_EAK, new Point(900, -138), LEADER, 60));
        assertEquals(AgentOpqHandOver.Place.BESIDE,
                AgentOpqHandOver.place(AgentOpqHandOver.HandOver.BY_EAK, new Point(510, -138), LEADER, 60),
                "an Eak drop that lands by him is beside him");
        assertEquals(AgentOpqHandOver.Place.FEET,
                AgentOpqHandOver.place(AgentOpqHandOver.HandOver.DROP_AT_FEET, null, LEADER, 60));
    }

    @Test
    void aStackLeftLyingIsPointedOutThenPickedBackUpAtMostTwice() {
        assertFalse(AgentOpqHandOver.nudgeDue(10_000L, 39_999L, 30_000L));
        assertTrue(AgentOpqHandOver.nudgeDue(10_000L, 40_000L, 30_000L));
        assertFalse(AgentOpqHandOver.recollectDue(10_000L, 129_999L, 120_000L, 0, 2));
        assertTrue(AgentOpqHandOver.recollectDue(10_000L, 130_000L, 120_000L, 1, 2));
        assertFalse(AgentOpqHandOver.recollectDue(10_000L, 130_000L, 120_000L, 2, 2), "then it is left to him");
        assertEquals(170_000L, AgentOpqHandOver.recollectAfterMs(175_000L, AgentOpqHandOver.RECOLLECT_LATEST_MS));
        assertTrue(AgentOpqHandOver.RECOLLECT_MS < 180_000L, "the shipped value is picked up before it expires");
    }
}
