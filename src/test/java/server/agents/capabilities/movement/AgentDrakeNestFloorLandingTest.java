package server.agents.capabilities.movement;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.AgentMobKnockbackPolicy;
import server.agents.capabilities.mobcontrol.AgentMobPhysicsConfig;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drake's Nest on its real footholds. Citizens training there were recovered from below the map about 80 times a
 * day (105090312, 105090310). Its floors end at cliffs (a wall rising from a floor's end to the floor above) and
 * blocks (a floor whose walls run down from both ends), and each test is one way an Agent went into the rock and
 * fell through to the bottom of the map.
 */
class AgentDrakeNestFloorLandingTest {
    private static final int DRAKE_NEST = 105090312;
    private static final int DRAKE_NEST_UPPER = 105090310;

    @Test
    void aFallThatStartsOnAFloorsTopLandsOnThatFloor() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST);

        Point landing = fly(map, new Point(1380, 579), 0f, 0);

        assertEquals(new Point(1380, 579), landing);
    }

    @Test
    void aKnockbackWestFromTheFloorsFirstPixelStopsAtTheCliffRisingFromIt() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST);

        // x 1213 is where the floor (y 545) starts under the cliff that runs up to the y 470 floor.
        Point landing = fly(map, new Point(1213, 545), knockbackVelocityY(), -knockbackVelocityX());

        assertTrue(landing.x >= 1213 && landing.y == 545, "landed at " + landing);
    }

    @Test
    void aKnockbackIntoTheCliffComesDownOnTheFloorAtItsFoot() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST);

        // The wall stops it at x 1214, level with the floor there: the fall from that height must land on it.
        Point landing = fly(map, new Point(1261, 590), knockbackVelocityY(), -knockbackVelocityX());

        assertTrue(landing.x >= 1213 && landing.y <= 546, "landed at " + landing);
    }

    @Test
    void aKnockbackThatComesDownOnABlocksCornerLandsOnItsTop() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST_UPPER);

        // The block's top (y -640) spans x 362-538 with its walls running down from both ends; the arc reaches
        // the west corner on the same tick it reaches the wall.
        Point west = fly(map, new Point(446, -653), knockbackVelocityY(), -knockbackVelocityX());
        Point east = fly(map, new Point(453, -655), knockbackVelocityY(), knockbackVelocityX());

        assertEquals(new Point(362, -640), west);
        assertEquals(new Point(538, -640), east);
    }

    @Test
    void aWalkWestAlongTheBottomFloorStopsAtTheCliff() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST);
        AgentNavigationGraphService.rebuildGraph(map);
        AtomicReference<Point> position = new AtomicReference<>(new Point(1260, 590));
        Character agent = trackedAgent(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementPoseService.resetMotion(entry, position.get());

        for (int tick = 0; tick < 40; tick++) {
            Foothold foothold = AgentGroundPhysicsService.syncAndDetectGround(entry, agent);
            AgentMovementStateRuntime.setMoveDirection(entry, -1);
            assertFalse(AgentGroundPhysicsService.applyGroundMotion(entry, agent, foothold).lostGround(),
                    "walked off the floor at " + position.get() + " on tick " + tick);
        }

        assertTrue(position.get().x >= 1213 && position.get().x <= 1220, "stopped at " + position.get());
        assertFalse(AgentMovementStateRuntime.inAir(entry));
    }

    @Test
    void aChargeSlidesUpTheFloorsBumpsAndStopsAtTheCliff() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST);
        AgentNavigationGraphService.rebuildGraph(map);
        AtomicReference<Point> position = new AtomicReference<>(new Point(1382, 579));
        Character agent = trackedAgent(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementPoseService.resetMotion(entry, position.get());

        // Rush's reach (160 px); its first tick slides over 80 px, across the steep bumps west of the start.
        AgentKnockbackMovementService.beginGroundDash(entry, agent, -160);
        for (int tick = 0; tick < 20; tick++) {
            Foothold foothold = AgentGroundPhysicsService.syncAndDetectGround(entry, agent);
            AgentMovementStateRuntime.setMoveDirection(entry, 0);
            assertFalse(AgentGroundPhysicsService.applyGroundMotion(entry, agent, foothold).lostGround(),
                    "slid off the floor at " + position.get() + " on tick " + tick);
        }

        assertTrue(position.get().x >= 1213 && position.get().x < 1382, "stopped at " + position.get());
        assertFalse(AgentMovementStateRuntime.inAir(entry));
    }

    @Test
    void aFallFromTheBottomOfADipLandsOnItsFarSide() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST);

        // x 1350 is the bottom of a dip in the bottom floor (y 590); the floor west of it climbs back to y 589.
        Point landing = fly(map, new Point(1350, 590), 0f, -3);

        assertTrue(landing.x >= 1333 && landing.x < 1350 && landing.y >= 588 && landing.y <= 590,
                "landed at " + landing);
    }

    @Test
    void aWalkWestOffABumpLandsOnTheBumpsOfTheFloorBelow() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST);
        AgentNavigationGraphService.rebuildGraph(map);
        AtomicReference<Point> position = new AtomicReference<>(new Point(1317, 502));
        Character agent = trackedAgent(map, position);
        when(agent.getTotalMoveSpeedStat()).thenReturn(120);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, new AgentMovementProfile(120, 100));
        AgentMovementPoseService.resetMotion(entry, position.get());

        // The bump (y 549 at its west end, x 1271) ends in the air over the floor's next bump, which rises
        // westward toward the falling Agent.
        for (int tick = 0; tick < 30; tick++) {
            if (AgentMovementStateRuntime.inAir(entry)) {
                AgentMotionTimerService.tickMotionTimers(entry);
                AgentAirbornePhysicsService.stepAirborne(entry, agent);
            } else {
                Foothold foothold = AgentGroundPhysicsService.syncAndDetectGround(entry, agent);
                AgentMovementStateRuntime.setMoveDirection(entry, -1);
                AgentGroundPhysicsService.applyGroundMotion(entry, agent, foothold);
            }
            assertTrue(position.get().y <= 590, "fell through the floor at " + position.get() + " on tick " + tick);
        }
        assertTrue(position.get().x >= 1213, "went into the cliff at " + position.get());
    }

    @Test
    void aWalkWestStopsAtACliffWithALedgePartWayUp() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(DRAKE_NEST_UPPER);
        AgentNavigationGraphService.rebuildGraph(map);
        AtomicReference<Point> position = new AtomicReference<>(new Point(8, -261));
        Character agent = trackedAgent(map, position);
        when(agent.getTotalMoveSpeedStat()).thenReturn(140);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setMovementProfile(entry, new AgentMovementProfile(140, 100));
        AgentMovementPoseService.resetMotion(entry, position.get());

        // The cliff at x -2 rises from y -265 to the y -640 floor west of it; a ledge leaves it eastward at y -460.
        for (int tick = 0; tick < 20; tick++) {
            Foothold foothold = AgentGroundPhysicsService.syncAndDetectGround(entry, agent);
            AgentMovementStateRuntime.setMoveDirection(entry, -1);
            assertFalse(AgentGroundPhysicsService.applyGroundMotion(entry, agent, foothold).lostGround(),
                    "walked off the floor at " + position.get() + " on tick " + tick);
        }

        assertTrue(position.get().x >= -2 && position.get().x <= 4, "stopped at " + position.get());
    }

    private static float knockbackVelocityY() {
        return -AgentMobKnockbackPolicy.scaledOpenStoryStep(
                AgentMobPhysicsConfig.cfg.KNOCKBACK_VFORCE, AgentMovementPhysicsConfig.configuredMovementTickMs());
    }

    private static int knockbackVelocityX() {
        return Math.round(AgentMobKnockbackPolicy.scaledOpenStoryStep(
                AgentMobPhysicsConfig.cfg.KNOCKBACK_HSPEED, AgentMovementPhysicsConfig.configuredMovementTickMs()));
    }

    private static Point fly(MapleMap map, Point start, float velocityY, int velocityX) {
        AtomicReference<Point> position = new AtomicReference<>(new Point(start));
        Character agent = trackedAgent(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementPoseService.resetMotion(entry, start);
        AgentKnockbackMovementService.beginKnockback(entry, agent, start, velocityY, velocityX);
        for (int tick = 0; tick < 300 && AgentMovementStateRuntime.inAir(entry); tick++) {
            AgentMotionTimerService.tickMotionTimers(entry);
            AgentAirbornePhysicsService.stepAirborne(entry, agent);
        }
        return position.get();
    }

    private static Character trackedAgent(MapleMap map, AtomicReference<Point> position) {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenAnswer(ignored -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getTotalMoveSpeedStat()).thenReturn(100);
        when(agent.getTotalJumpStat()).thenReturn(100);
        return agent;
    }
}
