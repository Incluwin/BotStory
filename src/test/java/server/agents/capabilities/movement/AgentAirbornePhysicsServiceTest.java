package server.agents.capabilities.movement;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.AgentMobKnockbackPolicy;
import server.agents.capabilities.mobcontrol.AgentMobPhysicsConfig;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAirbornePhysicsServiceTest {
    @Test
    void mushroomCastleFallBlocksAtTheActualPositionWithoutTeleporting() {
        MapleMap map = mock(MapleMap.class);
        when(map.getMapArea()).thenReturn(new Rectangle(0, 0, 1000, 500));
        when(map.getPointBelow(any(Point.class))).thenReturn(new Point(250, 400));
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(106020401);
        when(agent.getPosition()).thenReturn(new Point(250, 565));
        when(agent.getHp()).thenReturn(1);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        var state = entry.capabilityStates().require(
                server.agents.progression.AgentMushroomKingdomState.STATE_KEY);
        state.begin(1L);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, agent.getPosition());

        assertEquals(AgentAirborneStepResult.CONTINUE,
                AgentAirbornePhysicsService.stepAirborne(entry, agent));
        assertEquals(server.agents.progression.AgentMushroomKingdomState.Phase.BLOCKED, state.phase());
        org.mockito.Mockito.verify(agent, org.mockito.Mockito.never()).setPosition(any(Point.class));
        assertFalse(AgentMovementStateRuntime.inAir(entry));
    }

    @Test
    void stepAirborneMapsLegacyContinueResult() {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(10, 20));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(null);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, new Point(10, 20));

        AgentAirborneStepResult result = AgentAirbornePhysicsService.stepAirborne(entry, agent);

        assertEquals(AgentAirborneStepResult.CONTINUE, result);
        assertTrue(AgentMovementStateRuntime.inAir(entry));
    }

    @Test
    void stepAirborneAppliesAgentOwnedAirSteering() {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(10, 20));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(null);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementStateRuntime.setMoveDirection(entry, 1);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, new Point(10, 20));

        AgentAirborneStepResult result = AgentAirbornePhysicsService.stepAirborne(entry, agent);

        assertEquals(AgentAirborneStepResult.CONTINUE, result);
        assertEquals(0.5, AgentMovementPhysicsStateRuntime.airSteerVelocityX(entry));
        assertEquals(1, AgentMovementStateRuntime.facingDirection(entry));
    }

    @Test
    void stepAirborneRecoversAgentThatHasFallenBelowKnownMapBounds() {
        MapleMap map = mock(MapleMap.class);
        when(map.getMapArea()).thenReturn(new Rectangle(0, 0, 1000, 500));
        when(map.getPointBelow(any(Point.class))).thenAnswer(invocation -> {
            Point probe = invocation.getArgument(0);
            return probe.x == 250 ? new Point(250, 400) : null;
        });

        Character agent = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(250, 565));
        when(agent.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(map);

        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, position.get());

        AgentAirborneStepResult result = AgentAirbornePhysicsService.stepAirborne(entry, agent);

        assertEquals(AgentAirborneStepResult.LANDED, result);
        assertEquals(new Point(250, 400), position.get());
        assertFalse(AgentMovementStateRuntime.inAir(entry));
    }

    @Test
    void belowMapRecoveryPrefersTheActualEntryPortalOverAnUpperFoothold() {
        MapleMap map = mock(MapleMap.class);
        Portal entryPortal = mock(Portal.class);
        when(map.getMapArea()).thenReturn(new Rectangle(0, 0, 1000, 500));
        when(map.getPortal(4)).thenReturn(entryPortal);
        when(entryPortal.getPosition()).thenReturn(new Point(455, 100));
        when(map.getPointBelow(any(Point.class))).thenAnswer(invocation -> {
            Point probe = invocation.getArgument(0);
            return probe.x == 455 ? new Point(455, 400) : new Point(probe.x, 150);
        });

        assertEquals(new Point(455, 400),
                AgentAirbornePhysicsService.belowMapRecoveryPoint(
                        map, new Point(26, 565), 4));
    }

    @Test
    void pendingFlashJumpFiresAtTheApex() {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(10, 20));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(null);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, new Point(10, 20));
        AgentMovementPhysicsStateRuntime.setAirVelocityX(entry, 1);
        AgentMovementPhysicsStateRuntime.setVerticalVelocity(entry, 0);
        AgentMovementSkillStateRuntime.state(entry).setFlashJumpPending(true);

        AgentAirbornePhysicsService.stepAirborne(entry, agent);

        AgentMovementSkillState skillState = AgentMovementSkillStateRuntime.state(entry);
        assertFalse(skillState.flashJumpPending());
        assertTrue(skillState.flashJumpFired());
        assertTrue(AgentMovementPhysicsStateRuntime.airVelocityX(entry) > 1);
        assertTrue(AgentMovementPhysicsStateRuntime.verticalVelocity(entry) < 0);
    }

    @Test
    void ordinaryAirborneStepDoesNotAllocateMovementSkillState() {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenReturn(new Point(10, 20));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(null);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, new Point(10, 20));

        AgentAirbornePhysicsService.stepAirborne(entry, agent);

        assertTrue(entry.capabilityStates().find(AgentMovementSkillState.STATE_KEY).isEmpty());
    }

    @Test
    void airborneAgentCollidesWithConfiguredActivityBoundary() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(105100400);
        Character agent = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(1067, 100));
        when(agent.getPosition()).thenAnswer(ignored -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(map);

        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, position.get());
        AgentMovementPhysicsStateRuntime.setAirVelocityX(entry, 5);
        AgentHorizontalBoundaryStateRuntime.set(entry, 105100400, -100, 1068);

        AgentAirborneStepResult result = AgentAirbornePhysicsService.stepAirborne(entry, agent);

        assertEquals(AgentAirborneStepResult.WALL, result);
        assertEquals(1068, position.get().x);
        assertTrue(position.get().y > 100, "the wall must preserve ordinary vertical physics");
        assertEquals(0, AgentMovementPhysicsStateRuntime.airVelocityX(entry));
        assertTrue(AgentMovementStateRuntime.inAir(entry));
    }

    @Test
    void knockbackIntoTheFightBoundaryLandsOnTheFloorUnderTheWall() {
        // Live 2026-10-07: a boss hit knocked Isolde left into the Easy Balrog wall (x=-100). The tick that
        // clamped x was also the tick that crossed fh1 (y=262), so she fell through and was recovered at
        // (-100,468). With the live tuning (-9 px/tick, -21.875 px/tick) launches at x=-28..-20 hit that tick;
        // sweep the whole floor left of x=0 so a tuning change cannot hide the case.
        MapleMap map = easyBalrogFightMap();
        int tickMs = AgentMovementPhysicsConfig.configuredMovementTickMs();
        float launchVelocityY = -AgentMobKnockbackPolicy.scaledOpenStoryStep(
                AgentMobPhysicsConfig.cfg.KNOCKBACK_VFORCE, tickMs);
        int knockbackVelocityX = AgentMobKnockbackPolicy.airVelocityForDamageDirection(
                0, AgentMobPhysicsConfig.cfg.KNOCKBACK_HSPEED, tickMs);
        assertTrue(knockbackVelocityX < 0, "a hit from the right knocks the Agent toward the left wall");

        for (int launchX = -99; launchX <= 0; launchX++) {
            AtomicReference<Point> position = new AtomicReference<>(new Point(launchX, 262));
            Character agent = trackedAgent(map, position);
            AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
            AgentHorizontalBoundaryStateRuntime.set(entry, 105100400, -100, 1068);
            AgentKnockbackMovementService.beginKnockback(
                    entry, agent, new Point(launchX, 262), launchVelocityY, knockbackVelocityX);

            AgentAirborneStepResult result = AgentAirborneStepResult.CONTINUE;
            for (int tick = 1; tick <= 40 && AgentMovementStateRuntime.inAir(entry); tick++) {
                result = AgentAirbornePhysicsService.stepAirborne(entry, agent);
                assertTrue(position.get().y <= 262,
                        "launch x=" + launchX + " passed through the floor on tick " + tick
                                + " to " + position.get() + " (" + result + ")");
            }

            assertEquals(AgentAirborneStepResult.LANDED, result, "launch x=" + launchX);
            assertFalse(AgentMovementStateRuntime.inAir(entry), "launch x=" + launchX);
            assertEquals(262, position.get().y, "launch x=" + launchX);
            assertTrue(position.get().x >= -100 && position.get().x <= launchX,
                    "launch x=" + launchX + " landed at " + position.get());
        }
    }

    @Test
    void airSteeringPastTheFightBoundaryLandsOnTheFloorItCrosses() {
        MapleMap map = easyBalrogFightMap();
        AtomicReference<Point> position = new AtomicReference<>(new Point(-100, 250));
        Character agent = trackedAgent(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, position.get());
        AgentMovementPhysicsStateRuntime.setAirVelocityX(entry, -5);
        AgentMovementPhysicsStateRuntime.setVerticalVelocity(entry, 20f);
        AgentHorizontalBoundaryStateRuntime.set(entry, 105100400, -100, 1068);

        AgentAirborneStepResult result = AgentAirbornePhysicsService.stepAirborne(entry, agent);

        assertEquals(AgentAirborneStepResult.LANDED, result);
        assertEquals(new Point(-100, 262), position.get());
        assertFalse(AgentMovementStateRuntime.inAir(entry));
        assertEquals(0, AgentMovementPhysicsStateRuntime.airVelocityX(entry));
    }

    @Test
    void fightBoundaryClampBelowTheMapRecoversOnTheSameTick() {
        MapleMap map = easyBalrogFightMap();
        AtomicReference<Point> position = new AtomicReference<>(new Point(-100, 470));
        Character agent = trackedAgent(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, position.get());
        AgentMovementPhysicsStateRuntime.setAirVelocityX(entry, -5);
        AgentMovementPhysicsStateRuntime.setVerticalVelocity(entry, 10f);
        AgentHorizontalBoundaryStateRuntime.set(entry, 105100400, -100, 1068);

        AgentAirborneStepResult result = AgentAirbornePhysicsService.stepAirborne(entry, agent);

        assertEquals(AgentAirborneStepResult.LANDED, result);
        assertEquals(new Point(-100, 262), position.get());
        assertFalse(AgentMovementStateRuntime.inAir(entry));
    }

    @Test
    void knockbackOffTheMapsOutermostFloorLandsBackOnIt() {
        // Live 2026-10-29, Agents fell below Wild Cargo's Area (105090301) at x -381 dozens of times a day: the
        // 1,020 px drop to its bottom floor (y 1120, from x -387, its side wall only below it) deals fall damage,
        // and the knockback flew past the floor's left end to the map edge (x -438) where nothing is below.
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(105090301);
        int tickMs = AgentMovementPhysicsConfig.configuredMovementTickMs();
        float launchVelocityY = -AgentMobKnockbackPolicy.scaledOpenStoryStep(
                AgentMobPhysicsConfig.cfg.KNOCKBACK_VFORCE, tickMs);
        int knockbackVelocityX = -Math.round(AgentMobKnockbackPolicy.scaledOpenStoryStep(
                AgentMobPhysicsConfig.cfg.KNOCKBACK_HSPEED, tickMs));

        for (int launchX = -387; launchX <= -361; launchX++) {
            AtomicReference<Point> position = new AtomicReference<>(new Point(launchX, 1120));
            Character agent = trackedAgent(map, position);
            AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
            AgentKnockbackMovementService.beginKnockback(
                    entry, agent, new Point(launchX, 1120), launchVelocityY, knockbackVelocityX);

            AgentAirborneStepResult result = AgentAirborneStepResult.CONTINUE;
            for (int tick = 1; tick <= 40 && AgentMovementStateRuntime.inAir(entry); tick++) {
                result = AgentAirbornePhysicsService.stepAirborne(entry, agent);
                assertTrue(position.get().y <= 1120 && position.get().x >= -387,
                        "launch x=" + launchX + " left the floor on tick " + tick
                                + " to " + position.get() + " (" + result + ")");
            }

            assertEquals(AgentAirborneStepResult.LANDED, result, "launch x=" + launchX);
            assertEquals(1120, position.get().y, "launch x=" + launchX);
            assertFalse(AgentMovementPhysicsStateRuntime.knockbackFlight(entry), "launch x=" + launchX);
        }
    }

    @Test
    void aJumpTheAgentMakesIsNotHeldToTheFootholds() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(105090301);
        AtomicReference<Point> position = new AtomicReference<>(new Point(-387, 1100));
        Character agent = trackedAgent(map, position);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentAirborneLaunchService.launchAirborne(entry, position.get(), -2f, -6, false);

        AgentAirbornePhysicsService.stepAirborne(entry, agent);

        assertTrue(position.get().x < -387, "a chosen jump keeps the map's own bounds: " + position.get());
    }

    /** Easy Balrog fight map 105100400: VR -117..1083 x -510..400 and its three floor footholds. */
    private static MapleMap easyBalrogFightMap() {
        MapleMap map = new MapleMap(105100400, 0, 0, 105100400, 1.0f);
        map.setMapLineBoundings(-510, 400, -117, 1083);
        map.setFootholds(new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000)));
        Foothold fh1 = new Foothold(new Point(-116, 262), new Point(197, 262), 1);
        Foothold fh2 = new Foothold(new Point(197, 262), new Point(213, 258), 2);
        Foothold fh3 = new Foothold(new Point(213, 258), new Point(1084, 258), 3);
        fh1.setNext(2);
        fh2.setPrev(1);
        fh2.setNext(3);
        fh3.setPrev(2);
        map.getFootholds().insert(fh1);
        map.getFootholds().insert(fh2);
        map.getFootholds().insert(fh3);
        return map;
    }

    private static Character trackedAgent(MapleMap map, AtomicReference<Point> position) {
        Character agent = mock(Character.class);
        when(agent.getPosition()).thenAnswer(ignored -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(agent).setPosition(any(Point.class));
        when(agent.getHp()).thenReturn(1);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        return agent;
    }
}
