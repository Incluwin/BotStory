package server.agents.capabilities.movement;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentJumpProbeServiceTest {
    @Test
    void simulateJumpLandingPreservesNoFootholdResult() {
        MapleMap map = new MapleMap(910000034, 0, 0, 910000034, 1.0f);

        assertNull(AgentJumpProbeService.simulateJumpLanding(map, new Point(0, 100), 0));
    }

    @Test
    void simulateJumpLandingReturnsAgentOwnedResultType() {
        MapleMap map = new MapleMap(910000034, 0, 0, 910000034, 1.0f);

        AgentJumpLanding landing = AgentJumpProbeService.simulateJumpLanding(map, new Point(0, 100), 0);

        assertNull(landing);
    }

    @Test
    void simulatePostLandingJumpReturnsAgentOwnedResultType() {
        MapleMap map = new MapleMap(910000034, 0, 0, 910000034, 1.0f);

        AgentPostLandingJump landing = AgentJumpProbeService.simulateJumpLandingWithPostLandingTicks(
                map, new Point(0, 100), 0, AgentMovementProfile.base(), 3);

        assertNull(landing);
    }

    @Test
    void simulateFallLandingReturnsAgentOwnedResultType() {
        MapleMap map = new MapleMap(910000034, 0, 0, 910000034, 1.0f);

        AgentJumpLanding landing = AgentJumpProbeService.simulateFallLanding(map, new Point(0, 100), 0);

        assertNull(landing);
    }

    @Test
    void simulateFallLandingReachesFloorBeyondLegacyTimeCap() {
        MapleMap map = new MapleMap(910000035, 0, 0, 910000035, 1.0f);
        map.setMapLineBoundings(-100, 3_000, -500, 500);
        Foothold floor = new Foothold(new Point(-100, 2_500), new Point(100, 2_500), 1);
        FootholdTree footholds = new FootholdTree(new Point(-500, -100), new Point(500, 3_000));
        footholds.insert(floor);
        map.setFootholds(footholds);

        AgentJumpLanding landing = AgentJumpProbeService.simulateFallLanding(map, new Point(0, 0), 0);

        assertNotNull(landing);
        assertEquals(new Point(0, 2_500), landing.point());
        assertTrue(landing.timeMs() > 1_500,
                "fixture must prove the descent exceeds the removed 1500ms simulation cap");
    }

    @Test
    void simulateDownJumpLandingReturnsAgentOwnedResultType() {
        MapleMap map = new MapleMap(910000034, 0, 0, 910000034, 1.0f);

        AgentJumpLanding landing = AgentJumpProbeService.simulateDownJumpLanding(map, new Point(0, 100));

        assertNull(landing);
    }

    @Test
    void simulateWalkOffLandingReturnsAgentOwnedResultType() {
        MapleMap map = new MapleMap(910000034, 0, 0, 910000034, 1.0f);

        AgentWalkOffLanding landing = AgentJumpProbeService.simulateWalkOffLanding(
                map, new Point(0, 100), 1, new AgentGroundTravelState(0.0, 0.0, 0.0),
                AgentMovementProfile.base());

        assertNull(landing);
    }

    @Test
    void canReachRopeFromGroundPreservesNullMapLegacyResult() {
        Rope rope = new Rope(100, 40, 120, false);

        assertTrue(AgentJumpProbeService.canReachRopeFromGround(null, new Point(100, 120), rope));
    }

    @Test
    void ropeGrabAfterTickCatchesTheRopeAtTheTickEndWhenItIsWithinReach() {
        Rope rope = new Rope(100, 0, 50, false);

        assertEquals(new Point(100, 45),
                AgentJumpProbeService.ropeGrabAfterTick(new Point(120, 40), new Point(106, 45), rope));
    }

    @Test
    void ropeGrabAfterTickCatchesTheRopeMidTickWhenTheTickEndsBelowIt() {
        // Within reach from x 108 on, at y 47; the tick ends at y 60, past the rope's bottom.
        Rope rope = new Rope(100, 0, 50, false);

        assertEquals(new Point(100, 47),
                AgentJumpProbeService.ropeGrabAfterTick(new Point(116, 20), new Point(104, 60), rope));
    }

    @Test
    void ropeGrabAfterTickCatchesARopeTheTickFliesPast() {
        Rope rope = new Rope(100, 0, 50, false);

        assertEquals(new Point(100, 23),
                AgentJumpProbeService.ropeGrabAfterTick(new Point(112, 20), new Point(88, 40), rope));
    }

    @Test
    void ropeGrabAfterTickMissesARopeOutOfReachOrBelowIt() {
        Rope rope = new Rope(100, 0, 50, false);

        assertNull(AgentJumpProbeService.ropeGrabAfterTick(new Point(130, 20), new Point(120, 30), rope));
        assertNull(AgentJumpProbeService.ropeGrabAfterTick(new Point(116, 52), new Point(104, 70), rope));
        assertNull(AgentJumpProbeService.ropeGrabAfterTick(new Point(104, -30), new Point(104, -2), rope));
    }

    @Test
    void aMoveFromACliffsFootIntoTheRockIsHeldButAJumpFromABlocksTopCornerIsNot() {
        MapleMap map = new MapleMap(910000072, 0, 0, 910000072, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        // A cliff at x 0 (upper floor to the west), and a block whose west side is a wall at x 200.
        Foothold upper = new Foothold(new Point(-100, 0), new Point(0, 0), 1);
        Foothold cliff = new Foothold(new Point(0, 0), new Point(0, 80), 2);
        Foothold lower = new Foothold(new Point(0, 80), new Point(150, 80), 3);
        Foothold blockSide = new Foothold(new Point(200, 60), new Point(200, 20), 4);
        Foothold blockTop = new Foothold(new Point(200, 20), new Point(260, 20), 5);
        upper.setNext(2);
        cliff.setPrev(1);
        cliff.setNext(3);
        lower.setPrev(2);
        blockSide.setNext(5);
        blockTop.setPrev(4);
        for (Foothold foothold : new Foothold[]{upper, cliff, lower, blockSide, blockTop}) {
            footholds.insert(foothold);
        }
        map.setFootholds(footholds);

        AgentJumpProbeService.AirCollision intoCliff =
                AgentJumpProbeService.resolveAirCollision(map, new Point(0, 80), new Point(-9, 60));
        AgentJumpProbeService.AirCollision overBlock =
                AgentJumpProbeService.resolveAirCollision(map, new Point(200, 20), new Point(206, 0));

        assertEquals(AgentJumpProbeService.AirCollisionType.WALL, intoCliff.type());
        assertEquals(1, intoCliff.point().x);
        assertEquals(AgentJumpProbeService.AirCollisionType.NONE, overBlock.type());
    }

    @Test
    void aMoveFromTheFootOfAWallNoTallerThanASlopeStepIsNotHeld() {
        // Kerning City's roofs (103000000 x 340): a floor ends at a 16 px wall up to the next floor. The ground walk
        // passes such a step, so the fall the graph times from its foot must not be held at it either.
        MapleMap map = new MapleMap(910000074, 0, 0, 910000074, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold lower = new Foothold(new Point(0, 100), new Point(50, 100), 1);
        Foothold step = new Foothold(new Point(50, 100), new Point(50, 84), 2);
        Foothold upper = new Foothold(new Point(50, 84), new Point(120, 84), 3);
        lower.setNext(2);
        step.setPrev(1);
        step.setNext(3);
        upper.setPrev(2);
        for (Foothold foothold : new Foothold[]{lower, step, upper}) {
            footholds.insert(foothold);
        }
        map.setFootholds(footholds);

        AgentJumpProbeService.AirCollision fromTheFoot =
                AgentJumpProbeService.resolveAirCollision(map, new Point(50, 100), new Point(56, 104));

        assertEquals(AgentJumpProbeService.AirCollisionType.NONE, fromTheFoot.type());
    }

    @Test
    void anArcReachingAFloorsEndOnTheTickItReachesTheWallLandsOnlyOnAFloorRunningBack() {
        MapleMap map = new MapleMap(910000073, 0, 0, 910000073, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        // A block (top y 0, x 0..100) with walls running down from both ends.
        Foothold west = new Foothold(new Point(0, 60), new Point(0, 0), 1);
        Foothold top = new Foothold(new Point(0, 0), new Point(100, 0), 2);
        Foothold east = new Foothold(new Point(100, 0), new Point(100, 60), 3);
        west.setNext(2);
        top.setPrev(1);
        top.setNext(3);
        east.setPrev(2);
        for (Foothold foothold : new Foothold[]{west, top, east}) {
            footholds.insert(foothold);
        }
        map.setFootholds(footholds);

        AgentJumpProbeService.AirCollision westCorner =
                AgentJumpProbeService.resolveAirCollision(map, new Point(4, -4), new Point(-4, 4));
        AgentJumpProbeService.AirCollision eastCorner =
                AgentJumpProbeService.resolveAirCollision(map, new Point(96, -4), new Point(104, 4));

        assertEquals(AgentJumpProbeService.AirCollisionType.LAND, westCorner.type());
        assertEquals(new Point(0, 0), westCorner.point());
        assertEquals(AgentJumpProbeService.AirCollisionType.LAND, eastCorner.type());
        assertEquals(new Point(100, 0), eastCorner.point());
    }
}
