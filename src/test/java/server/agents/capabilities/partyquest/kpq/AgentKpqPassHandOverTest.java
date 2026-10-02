package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
