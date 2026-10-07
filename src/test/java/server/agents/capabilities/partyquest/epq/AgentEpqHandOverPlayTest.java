package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 2.7: the hand-over rules, each pinned on its own (pure tables). */
class AgentEpqHandOverPlayTest {
    private static final Point LEADER = new Point(300, 150);

    private static AgentEpqCompanionPlay.HandOver handOver(Point carrier, Point landing, boolean reachable,
                                                         long walkingMs) {
        return AgentEpqCompanionPlay.handOver(carrier, LEADER, true, true, landing, reachable, walkingMs, 30_000L,
                60);
    }

    @Test
    void theHandOverTable() {
        assertEquals(AgentEpqCompanionPlay.HandOver.TO_NPC, handOver(new Point(300, 150), null, false, 0L),
                "a leader with no route to him: the NPC at once");
        assertEquals(AgentEpqCompanionPlay.HandOver.TO_NPC, handOver(new Point(0, 150), null, true, 30_001L),
                "a walk past its limit");
        assertEquals(AgentEpqCompanionPlay.HandOver.WALK, handOver(new Point(0, 150), null, true, 30_000L));
        assertEquals(AgentEpqCompanionPlay.HandOver.WALK, handOver(new Point(239, 150), null, true, 0L));
        assertEquals(AgentEpqCompanionPlay.HandOver.DROP_AT_FEET, handOver(new Point(240, 150), null, true, 0L),
                "within reach, landing unknown: at his feet");
        assertEquals(AgentEpqCompanionPlay.HandOver.DROP_AT_FEET,
                handOver(new Point(300, 150), new Point(330, 150), true, 0L));
        assertEquals(AgentEpqCompanionPlay.HandOver.DROP_BESIDE,
                handOver(new Point(300, 150), new Point(400, 150), true, 0L), "a step off his spot, his floor");
        assertEquals(AgentEpqCompanionPlay.HandOver.WALK,
                handOver(new Point(300, 150), new Point(300, 250), true, 0L), "it would land under him");
        assertEquals(AgentEpqCompanionPlay.HandOver.WALK, AgentEpqCompanionPlay.handOver(new Point(300, 150),
                LEADER, false, true, null, true, 0L, 30_000L, 60), "the carrier is in the air");
    }

    @Test
    void theAtFeetAndLandingEdges() {
        assertTrue(AgentEpqCompanionPlay.atFeet(new Point(360, 90), LEADER, true, true, 60));
        assertFalse(AgentEpqCompanionPlay.atFeet(new Point(361, 150), LEADER, true, true, 60));
        assertFalse(AgentEpqCompanionPlay.atFeet(new Point(300, 150), LEADER, true, false, 60), "he is on a rope");
        assertTrue(AgentEpqCompanionPlay.landsAt(new Point(240, 210), LEADER, 60));
        assertFalse(AgentEpqCompanionPlay.landsAt(new Point(240, 211), LEADER, 60));
        assertFalse(AgentEpqCompanionPlay.landsAt(null, LEADER, 60));
    }

    @Test
    void theLineFollowsWhereTheStackLands() {
        assertEquals(AgentEpqCompanionPlay.Place.FEET, AgentEpqCompanionPlay.place(
                AgentEpqCompanionPlay.HandOver.DROP_AT_FEET, null, LEADER, 60, true));
        assertEquals(AgentEpqCompanionPlay.Place.BESIDE, AgentEpqCompanionPlay.place(
                AgentEpqCompanionPlay.HandOver.DROP_BESIDE, new Point(400, 150), LEADER, 60, true));
        assertEquals(AgentEpqCompanionPlay.Place.BESIDE, AgentEpqCompanionPlay.place(
                AgentEpqCompanionPlay.HandOver.TO_NPC, new Point(330, 150), LEADER, 60, true),
                "a drop meant for the NPC that lands by him is right next to him");
        assertEquals(AgentEpqCompanionPlay.Place.NPC, AgentEpqCompanionPlay.place(
                AgentEpqCompanionPlay.HandOver.TO_NPC, new Point(-200, 150), LEADER, 60, true));
        assertEquals(AgentEpqCompanionPlay.Place.HERE, AgentEpqCompanionPlay.place(
                AgentEpqCompanionPlay.HandOver.TO_NPC, new Point(-200, 150), LEADER, 60, false));
        assertEquals(AgentEpqCompanionPlay.Place.NPC, AgentEpqCompanionPlay.place(
                AgentEpqCompanionPlay.HandOver.TO_NPC, null, LEADER, 60, true));
    }

    @Test
    void theRecollectRules() {
        assertEquals(170_000L, AgentEpqCompanionPlay.recollectAfterMs(175_000L, 170_000L), "clamped below expiry");
        assertEquals(150_000L, AgentEpqCompanionPlay.recollectAfterMs(150_000L, 170_000L));
        assertTrue(AgentEpqCompanionPlay.recollectDue(1_000L, 151_000L, 150_000L, false, 0, 2));
        assertFalse(AgentEpqCompanionPlay.recollectDue(1_000L, 150_999L, 150_000L, false, 0, 2), "1 ms early");
        assertTrue(AgentEpqCompanionPlay.recollectDue(1_000L, 1_000L, 150_000L, true, 0, 2), "a dying stack");
        assertTrue(AgentEpqCompanionPlay.recollectDue(1_000L, 151_000L, 150_000L, false, 1, 2));
        assertFalse(AgentEpqCompanionPlay.recollectDue(1_000L, 900_000L, 150_000L, false, 2, 2), "past the max");
        assertFalse(AgentEpqCompanionPlay.recollectDue(1_000L, 1_000L, 150_000L, true, 2, 2));
        assertTrue(AgentEpqCompanionPlay.nudgeDue(1_000L, 41_000L, 40_000L));
        assertFalse(AgentEpqCompanionPlay.nudgeDue(1_000L, 40_999L, 40_000L));
    }

    @Test
    void aCarrierAboutToGoDownIsLowWithNoPotionLeft() {
        assertTrue(AgentEpqCompanionPlay.dying(250, 1_000, 0, 25));
        assertFalse(AgentEpqCompanionPlay.dying(250, 1_000, 1, 25), "it drinks its potion");
        assertFalse(AgentEpqCompanionPlay.dying(250, 1_000, -1, 25), "an unknown count is not none");
        assertFalse(AgentEpqCompanionPlay.dying(251, 1_000, 0, 25));
        assertFalse(AgentEpqCompanionPlay.dying(0, 1_000, 0, 25), "already down");
    }

    @Test
    void whenMarblesAreDue() {
        assertTrue(AgentEpqCompanionPlay.marbleHandOverDue(5, 5, true, false, 5, 20), "a batch, the leader near");
        assertFalse(AgentEpqCompanionPlay.marbleHandOverDue(5, 5, false, false, 5, 20), "a batch, the leader far");
        assertFalse(AgentEpqCompanionPlay.marbleHandOverDue(4, 4, true, false, 5, 20), "under a batch");
        assertTrue(AgentEpqCompanionPlay.marbleHandOverDue(1, 20, false, false, 5, 20), "the twenty are made");
        assertTrue(AgentEpqCompanionPlay.marbleHandOverDue(1, 9, false, true, 5, 20), "no flower left");
        assertFalse(AgentEpqCompanionPlay.marbleHandOverDue(0, 20, true, true, 5, 20), "it holds none");
        assertTrue(AgentEpqCompanionPlay.makesTwenty(6, 14, 0, 20));
        assertTrue(AgentEpqCompanionPlay.makesTwenty(6, 9, 5, 20));
        assertFalse(AgentEpqCompanionPlay.makesTwenty(6, 9, 4, 20));
        assertFalse(AgentEpqCompanionPlay.makesTwenty(6, -1, 0, 20));
    }
}
