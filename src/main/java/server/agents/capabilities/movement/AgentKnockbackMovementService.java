package server.agents.capabilities.movement;

import client.Character;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;

/**
 * Agent-owned seam for combat-driven knockback movement.
 */
public final class AgentKnockbackMovementService {
    static final int DASH_PROBE_STEP_PX = config.AgentTuning.intValue("server.agents.capabilities.movement.AgentKnockbackMovementService.DASH_PROBE_STEP_PX");

    private AgentKnockbackMovementService() {
    }

    public static void beginKnockback(AgentRuntimeEntry entry, Character agent, Point position, float initialVelocityY, int airVelocityX) {
        int preservedFacingDir = AgentMovementStateRuntime.facingDirection(entry);
        agent.setPosition(position);
        AgentClimbStateRuntime.clearBlockedRopeGrab(entry);
        AgentAirborneLaunchService.launchAirborne(entry, position, initialVelocityY, airVelocityX, true);
        AgentMovementPhysicsStateRuntime.setKnockbackFlight(entry, true);
        AgentMovementStateRuntime.setFacingDirection(entry, preservedFacingDir);
        AgentMovementPoseService.syncCharacterState(entry);
    }

    /**
     * A charge that carries the Agent along its ground (Rush, Assaulter, Corkscrew/Backspin Blow):
     * the distance is cut short at the first wall or ledge, and the ground physics then plays the
     * slide out during the attack's lock and broadcasts it, as a player's client would have.
     */
    public static void beginGroundDash(AgentRuntimeEntry entry, Character agent, int worldDeltaX) {
        if (worldDeltaX == 0 || AgentMovementStateRuntime.inAir(entry) || AgentMovementStateRuntime.climbing(entry)) {
            return;
        }
        MapleMap map = agent.getMap();
        Point from = agent.getPosition();
        Foothold foothold = map == null || from == null ? null : AgentGroundingService.findGroundFoothold(map, from);
        if (foothold == null) {
            return;
        }
        int reachable = AgentGroundPhysicsService.reachableSlide(map, from, foothold, worldDeltaX, DASH_PROBE_STEP_PX);
        if (reachable == 0) {
            return;
        }
        int preservedFacingDir = AgentMovementStateRuntime.facingDirection(entry);
        AgentMovementPhysicsStateRuntime.setPhysicsX(entry, from.x);
        AgentMovementPhysicsStateRuntime.setHorizontalSpeed(entry,
                Math.signum(reachable) * AgentGroundPhysicsService.slideSpeedFor(Math.abs(reachable)));
        AgentMovementStateRuntime.setFacingDirection(entry, preservedFacingDir);
    }

    public static void applyAirKnockback(AgentRuntimeEntry entry, Character agent, int airVelocityX) {
        int preservedFacingDir = AgentMovementStateRuntime.facingDirection(entry);
        Point position = agent.getPosition();
        AgentMovementStateRuntime.setInAir(entry, true);
        AgentClimbStateRuntime.setClimbingOnRope(entry, null);
        AgentMovementStateRuntime.setCrouching(entry, false);
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(entry, position);
        AgentMovementPhysicsStateRuntime.setHorizontalSpeed(entry, 0.0);
        AgentClimbStateRuntime.setClimbUpIntent(entry, true);
        AgentMovementPhysicsStateRuntime.setAirVelocityX(entry, airVelocityX);
        AgentMovementPhysicsStateRuntime.setAirSteerVelocityX(entry, 0.0);
        AgentMovementPhysicsStateRuntime.setFixedAirArc(entry, false);
        AgentMovementPhysicsStateRuntime.setKnockbackFlight(entry, true);
        AgentMovementStateRuntime.setDownJumpPending(entry, false);
        AgentClimbStateRuntime.clearBlockedRopeGrab(entry);
        AgentMovementStateRuntime.setMovementVelocity(entry,
                AgentMovementKinematicsService.velocityFromDeltaX(airVelocityX),
                AgentAirborneLaunchService.movementVelocityFromAirStep(
                        AgentMovementPhysicsStateRuntime.verticalVelocity(entry)));
        AgentMovementStateRuntime.setFacingDirection(entry, preservedFacingDir);
        AgentMovementPoseService.syncCharacterState(entry);
    }
}
