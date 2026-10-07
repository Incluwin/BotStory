package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.agents.capabilities.partyquest.lpq.AgentLpqCompanionPlay.HandOver;
import static server.agents.capabilities.partyquest.lpq.AgentLpqCompanionPlay.PassPlace;

/** P6g LPQ C2.5: the pure hand-over rules of a companion run (the KPQ/HPQ pattern for LPQ's passes and key). */
class AgentLpqCompanionPlayTest {
    private static final Point SPOT = new Point(100, 200);

    @Test
    void whenACarrierIsDue() {
        assertTrue(AgentLpqCompanionPlay.handOverDue(5, 5, true, false, false, false), "a batch, he is near");
        assertFalse(AgentLpqCompanionPlay.handOverDue(4, 5, true, false, false, false), "under a batch");
        assertFalse(AgentLpqCompanionPlay.handOverDue(5, 5, false, false, false, false), "a batch, he is far");
        assertTrue(AgentLpqCompanionPlay.handOverDue(1, 5, false, true, false, false), "the count is reached");
        assertTrue(AgentLpqCompanionPlay.handOverDue(1, 5, false, false, true, false), "its room is done");
        assertTrue(AgentLpqCompanionPlay.handOverDue(1, 5, false, false, false, true), "about to go down");
        assertFalse(AgentLpqCompanionPlay.handOverDue(0, 5, true, true, true, true), "nothing carried");
    }

    @Test
    void whatACarrierDoesOnHisMap() {
        Point far = new Point(500, 200);
        assertEquals(HandOver.TO_BALLOON, AgentLpqCompanionPlay.handOver(far, SPOT, true, true, null, false, 0L,
                30_000L, 60), "no route to him");
        assertEquals(HandOver.TO_BALLOON, AgentLpqCompanionPlay.handOver(far, SPOT, true, true, null, true, 30_001L,
                30_000L, 60), "walked too long");
        assertEquals(HandOver.WALK, AgentLpqCompanionPlay.handOver(far, SPOT, true, true, null, true, 30_000L,
                30_000L, 60), "not at his feet yet");
        Point beside = new Point(140, 200);
        assertEquals(HandOver.DROP_AT_FEET, AgentLpqCompanionPlay.handOver(beside, SPOT, true, true,
                new Point(150, 200), true, 0L, 30_000L, 60));
        assertEquals(HandOver.DROP_BESIDE, AgentLpqCompanionPlay.handOver(beside, SPOT, true, true,
                new Point(170, 210), true, 0L, 30_000L, 60), "the map lands it a step off, on his floor");
        assertEquals(HandOver.WALK, AgentLpqCompanionPlay.handOver(beside, SPOT, true, true,
                new Point(150, 100), true, 0L, 30_000L, 60), "it would land up above him");
        assertEquals(HandOver.WALK, AgentLpqCompanionPlay.handOver(beside, SPOT, false, true, null, true, 0L,
                30_000L, 60), "never from a rope or in the air");
    }

    @Test
    void whereAStackLies() {
        assertEquals(PassPlace.FEET, PassPlace.of(new Point(130, 200), SPOT, 60));
        assertEquals(PassPlace.LEFT, PassPlace.of(new Point(30, 200), SPOT, 60));
        assertEquals(PassPlace.RIGHT, PassPlace.of(new Point(170, 205), SPOT, 60));
        assertEquals(PassPlace.ABOVE, PassPlace.of(new Point(100, 139), SPOT, 60));
        assertEquals(PassPlace.FEET, PassPlace.of(null, SPOT, 60));
    }

    @Test
    void theRecollectComesBeforeTheExpiryAndAtMostTheMax() {
        assertEquals(170_000L, AgentLpqDefinition.dropRecollectLatestMs(180_000L, 10_000L));
        assertEquals(170_000L, AgentLpqCompanionPlay.recollectAfterMs(175_000L,
                AgentLpqDefinition.dropRecollectLatestMs(180_000L, 10_000L)));
        assertEquals(150_000L, AgentLpqCompanionPlay.recollectAfterMs(150_000L, 170_000L));
        assertTrue(AgentLpqCompanionPlay.recollectDue(1_000L, 151_000L, 150_000L, 1, 2));
        assertFalse(AgentLpqCompanionPlay.recollectDue(1_000L, 150_999L, 150_000L, 1, 2), "1 ms early");
        assertFalse(AgentLpqCompanionPlay.recollectDue(1_000L, 900_000L, 150_000L, 2, 2), "the max is done");
        assertTrue(AgentLpqCompanionPlay.nudgeDue(1_000L, 41_000L, 40_000L));
        assertFalse(AgentLpqCompanionPlay.nudgeDue(1_000L, 40_999L, 40_000L));
    }

    @Test
    void aboutToGoDown() {
        assertTrue(AgentLpqCompanionPlay.dying(500, 2_000, 0, 25));
        assertFalse(AgentLpqCompanionPlay.dying(501, 2_000, 0, 25), "above a quarter");
        assertFalse(AgentLpqCompanionPlay.dying(500, 2_000, 1, 25), "a potion left");
        assertFalse(AgentLpqCompanionPlay.dying(500, 2_000, -1, 25), "an unknown count is not none");
        assertFalse(AgentLpqCompanionPlay.dying(0, 2_000, 0, 25), "already down");
    }

    @Test
    void theHandOverLines() {
        assertEquals("Crewfriend, the passes are at your feet. grab them and talk to the red balloon.",
                AgentLpqCompanionPlay.passesLine("Crewfriend", 1, PassPlace.FEET, false));
        assertEquals("Crewfriend, the passes are at your feet. grab them and talk to the yellow balloon. it explains"
                + " first, then talk to it again.", AgentLpqCompanionPlay.passesLine("Crewfriend", 3, PassPlace.FEET,
                true));
        assertEquals("Crewfriend, the passes landed up above you. hop up, grab them and talk to the lime balloon.",
                AgentLpqCompanionPlay.passesLine("Crewfriend", 4, PassPlace.ABOVE, false));
        assertEquals("Crewfriend, couldn't get to you, left the passes by the red balloon.",
                AgentLpqCompanionPlay.passesByTheBalloonLine("Crewfriend", 1));
        assertEquals("Crewfriend, the passes are still by your feet.",
                AgentLpqCompanionPlay.passesStillLyingLine("Crewfriend", true));
        assertEquals("Crewfriend, you left some passes on the ground back there.",
                AgentLpqCompanionPlay.passesStillLyingLine("Crewfriend", false));
        assertEquals("that's all the passes, Crewfriend. talk to the green balloon.",
                AgentLpqCompanionPlay.allPassesLine("Crewfriend", 5));
    }
}
