package server.agents.capabilities.movement;

import client.Character;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;

/**
 * Agent-owned seam for grounded movement physics while internals migrate.
 */
public final class AgentGroundPhysicsService {
    private static final double CLIENT_GROUND_STEP_MS = config.AgentTuning.doubleValue("server.agents.capabilities.movement.AgentGroundPhysicsService.CLIENT_GROUND_STEP_MS");
    private static final double CLIENT_GROUND_STEP_S = CLIENT_GROUND_STEP_MS / 1000.0;
    private static final double GROUNDSLIP = config.AgentTuning.doubleValue("server.agents.capabilities.movement.AgentGroundPhysicsService.GROUNDSLIP");
    private static final double FRICTION = config.AgentTuning.doubleValue("server.agents.capabilities.movement.AgentGroundPhysicsService.FRICTION");
    private static final double SLOPEFACTOR = config.AgentTuning.doubleValue("server.agents.capabilities.movement.AgentGroundPhysicsService.SLOPEFACTOR");

    private AgentGroundPhysicsService() {
    }

    public static Foothold syncAndDetectGround(AgentRuntimeEntry entry, Character agent) {
        syncGroundPosition(entry, agent.getPosition().x);
        MapleMap map = agent.getMap();
        Point position = agent.getPosition();
        int lastRegionId = entry == null || map == null
                ? -1
                : entry.navigationContinuityState().lastRegionIdForMap(map.getId());
        Foothold foothold = lastRegionId < 0
                ? null
                : AgentGroundCollisionService.findWalkRegionGroundFoothold(
                map, lastRegionId, position.x, position.y);
        if (foothold == null) {
            foothold = AgentGroundingService.findGroundFoothold(map, position);
        }
        if (foothold == null) {
            beginFall(entry, agent, 0);
        }
        return foothold;
    }

    public static AgentGroundMotion applyGroundMotion(AgentRuntimeEntry entry, Character agent, Foothold foothold) {
        return applyGroundMotion(entry, agent, foothold, null);
    }

    /**
     * {@code restX} non-null: the walk input is released at the client step from which friction alone brings
     * the Agent to rest on that pixel ({@link #coastsOnto}), so a precise approach stops on its waypoint.
     */
    public static AgentGroundMotion applyGroundMotion(AgentRuntimeEntry entry,
                                                      Character agent,
                                                      Foothold foothold,
                                                      Integer restX) {
        MapleMap map = agent.getMap();
        Point currentPosition = agent.getPosition();
        int desiredDirection = AgentMovementStateRuntime.moveDirection(entry);
        GroundStepResult step = simulateGroundMotion(map, currentPosition, foothold, desiredDirection,
                new AgentGroundTravelState(AgentMovementPhysicsStateRuntime.physicsX(entry),
                        AgentMovementPhysicsStateRuntime.horizontalSpeed(entry),
                        AgentMovementPhysicsStateRuntime.groundPhysicsCarryMs(entry)),
                AgentMovementStateRuntime.movementProfile(entry), restX);

        int boundedX = AgentHorizontalBoundaryStateRuntime.clampX(
                entry, map == null ? -1 : map.getId(), step.point().x);
        if (boundedX != step.point().x) {
            AgentGroundCollisionService.GroundStepPreview boundaryPreview =
                    AgentGroundCollisionService.previewGroundStep(
                            map, currentPosition, foothold, boundedX);
            Point boundaryPoint = boundaryPreview != null
                    && !boundaryPreview.blocked() && !boundaryPreview.lostGround()
                    ? boundaryPreview.point() : currentPosition;
            Foothold boundaryFoothold = boundaryPreview != null
                    && boundaryPreview.foothold() != null
                    ? boundaryPreview.foothold() : foothold;
            step = new GroundStepResult(
                    boundaryPoint,
                    boundaryFoothold,
                    initialGroundTravelState(boundaryPoint),
                    boundaryPoint.x - currentPosition.x,
                    0,
                    false);
        }

        if (step.lostGround()) {
            beginFall(entry, agent, step.point(), step.stepX());
            return new AgentGroundMotion(step.stepX(), true);
        }

        Point position = step.point();
        agent.setPosition(position);
        AgentMovementStateRuntime.setInAir(entry, false);
        AgentClimbStateRuntime.setClimbingOnRope(entry, null);
        AgentMovementStateRuntime.setCrouching(entry, false);
        AgentClimbStateRuntime.setClimbUpIntent(entry, false);
        AgentMovementPhysicsStateRuntime.setVerticalVelocity(entry, 0f);
        AgentMovementPhysicsStateRuntime.setAirVelocityX(entry, 0);
        AgentMovementPhysicsStateRuntime.setAirSteerVelocityX(entry, 0.0);
        AgentMovementPhysicsStateRuntime.setFixedAirArc(entry, false);
        // Keep the continuous X accumulator even when the rendered integer position has not
        // changed yet. Replacing it with position.x here discards every subpixel ground step;
        // low-force slopes can then sit forever at a foothold endpoint with non-zero velocity
        // but never accumulate enough displacement to start the authored walk-off.
        AgentMovementPhysicsStateRuntime.setPhysicsPosition(
                entry, step.state().physX(), position.y);
        AgentMovementPhysicsStateRuntime.setHorizontalSpeed(entry, step.state().hspeed());
        AgentMovementPhysicsStateRuntime.setGroundPhysicsCarryMs(entry, step.state().carryMs());
        AgentMovementStateRuntime.setDownJumpPending(entry, false);
        AgentMovementStateRuntime.setMovementVelocity(entry, step.velocityX(), 0);
        AgentMovementPoseService.syncCharacterState(entry);
        return new AgentGroundMotion(step.stepX(), false);
    }

    /**
     * Ground speed (px per client ground step) that friction brings to rest after {@code distancePx}
     * on flat ground: with no input each step keeps (1 - (FRICTION + SLOPEFACTOR) / GROUNDSLIP) of
     * the speed, so the slide covers v0 / that loss in total.
     */
    public static double slideSpeedFor(double distancePx) {
        return distancePx * (FRICTION + SLOPEFACTOR) / GROUNDSLIP;
    }

    /** How far (signed px) the Agent can slide from {@code from} toward {@code deltaX} before a wall or an edge. */
    static int reachableSlide(MapleMap map, Point from, Foothold foothold, int deltaX, int probeStepPx) {
        int sign = Integer.signum(deltaX);
        Point current = from;
        Foothold currentFoothold = foothold;
        int moved = 0;
        while (sign != 0 && Math.abs(moved) < Math.abs(deltaX)) {
            int step = Math.min(Math.max(1, probeStepPx), Math.abs(deltaX) - Math.abs(moved));
            AgentGroundCollisionService.GroundStepPreview preview = AgentGroundCollisionService.previewGroundStep(
                    map, current, currentFoothold, current.x + sign * step);
            if (preview == null || preview.blocked() || preview.lostGround() || preview.point().x == current.x) {
                break;
            }
            moved += preview.point().x - current.x;
            current = preview.point();
            currentFoothold = preview.foothold() == null ? currentFoothold : preview.foothold();
        }
        return moved;
    }

    /** {@link #coastsOnto(double, double, Foothold, int, int)} from the Agent's current ground physics state. */
    public static boolean coastsOnto(AgentRuntimeEntry entry, Foothold foothold, int direction, int restX) {
        return coastsOnto(AgentMovementPhysicsStateRuntime.physicsX(entry),
                AgentMovementPhysicsStateRuntime.horizontalSpeed(entry), foothold, direction, restX);
    }

    /**
     * Whether friction alone, from {@code physicsX} at {@code horizontalSpeed}, brings the Agent to rest within half
     * a pixel of {@code restX} or beyond it, walking in {@code direction}: any more input carries it past.
     */
    static boolean coastsOnto(double physicsX, double horizontalSpeed, Foothold foothold, int direction, int restX) {
        double x = physicsX;
        double speed = horizontalSpeed;
        while (speed != 0.0) {
            double next = applyGroundPhysicsStep(speed, foothold, 0, null);
            if (Math.abs(next) >= Math.abs(speed)) {
                // Friction no longer slows it (a steep slope at speed): it slides past anything ahead of it.
                return Math.signum(speed) == direction;
            }
            speed = next;
            x += speed;
        }
        return (x - restX) * direction >= -0.5;
    }

    public static void stopGroundMotion(AgentRuntimeEntry entry) {
        AgentMovementPhysicsStateRuntime.setHorizontalSpeed(entry, 0.0);
    }

    public static int velocityFromDeltaX(double deltaX) {
        return AgentMovementKinematicsService.velocityFromDeltaX(deltaX);
    }

    private static void syncGroundPosition(AgentRuntimeEntry entry, int x) {
        if (AgentMovementPhysicsStateRuntime.horizontalSpeed(entry) == 0.0
                && AgentMovementPhysicsStateRuntime.roundedPhysicsX(entry) != x) {
            AgentMovementPhysicsStateRuntime.setPhysicsX(entry, x);
        }
    }

    static GroundStepResult simulateGroundMotion(MapleMap map,
                                                 Point currentPosition,
                                                 Foothold foothold,
                                                 int desiredDirection,
                                                 AgentGroundTravelState state,
                                                 AgentMovementProfile profile) {
        return simulateGroundMotion(map, currentPosition, foothold, desiredDirection, state, profile, null);
    }

    static GroundStepResult simulateGroundMotion(MapleMap map,
                                                 Point currentPosition,
                                                 Foothold foothold,
                                                 int desiredDirection,
                                                 AgentGroundTravelState state,
                                                 AgentMovementProfile profile,
                                                 Integer restX) {
        if (map == null || currentPosition == null || foothold == null || state == null) {
            return new GroundStepResult(currentPosition, foothold, state, 0, 0, true);
        }

        AgentGroundTravelState displaced = applyGroundDisplacement(
                map, foothold, desiredDirection, state, profile, restX);
        int newX = (int) Math.round(displaced.physX());
        int stepX = newX - currentPosition.x;
        // A step longer than a dash probe (a charge's slide covers half its distance in its first tick) is taken in
        // probe-sized pieces, the way reachableSlide measured the slide. Previewed whole, only its far end was
        // tested: a Corkscrew Blow up a steep bump read as running off the floor and dropped the Agent under the
        // bump, and one along a floor's end went into the cliff past it (Drake's Nest 105090312, through the map).
        Point from = currentPosition;
        Foothold fromFoothold = foothold;
        int pieceX = newX;
        AgentGroundCollisionService.GroundStepPreview preview;
        while (true) {
            pieceX = Math.abs(newX - from.x) > AgentKnockbackMovementService.DASH_PROBE_STEP_PX
                    ? from.x + Integer.signum(newX - from.x) * AgentKnockbackMovementService.DASH_PROBE_STEP_PX
                    : newX;
            preview = AgentGroundCollisionService.previewGroundStep(map, from, fromFoothold, pieceX);
            if (preview == null || preview.blocked() || preview.lostGround() || pieceX == newX) {
                break;
            }
            from = preview.point();
            fromFoothold = preview.foothold() != null ? preview.foothold() : fromFoothold;
        }
        if (preview == null) {
            return new GroundStepResult(currentPosition, foothold, state, 0, 0, true);
        }

        if (preview.blocked()) {
            return new GroundStepResult(from, fromFoothold, initialGroundTravelState(from),
                    from.x - currentPosition.x, 0, false);
        }

        if (preview.lostGround()) {
            return new GroundStepResult(new Point(pieceX, preview.baseY()), fromFoothold, displaced,
                    stepX, velocityFromDeltaX(displaced.physX() - currentPosition.x), true);
        }

        // At rest the continuous X keeps its subpixel offset from the rendered X; reporting that offset as a
        // velocity (e.g. -9 forever) also kept applyIdleOrInPlaceMotion from ever settling the Agent.
        return new GroundStepResult(preview.point(), preview.foothold() != null ? preview.foothold() : fromFoothold, displaced,
                stepX, displaced.hspeed() == 0.0 ? 0 : velocityFromDeltaX(displaced.physX() - currentPosition.x), false);
    }

    static AgentGroundTravelState initialGroundTravelState(Point position) {
        return new AgentGroundTravelState(position.x, 0.0, 0.0);
    }

    private static AgentGroundTravelState applyGroundDisplacement(MapleMap map,
                                                                  Foothold foothold,
                                                                  int desiredDirection,
                                                                  AgentGroundTravelState state,
                                                                  AgentMovementProfile profile,
                                                                  Integer restX) {
        GroundStepCounter counter = groundPhysicsSteps(state.carryMs(), map);
        if (counter.steps() == 0) {
            return state;
        }

        double physicsX = state.physX();
        double horizontalSpeed = state.hspeed();
        for (int i = 0; i < counter.steps(); i++) {
            int direction = restX != null
                    && coastsOnto(physicsX, horizontalSpeed, foothold, desiredDirection, restX)
                    ? 0 : desiredDirection;
            horizontalSpeed = applyGroundPhysicsStep(horizontalSpeed, foothold, direction, profile);
            physicsX += horizontalSpeed;
        }
        return new AgentGroundTravelState(physicsX, horizontalSpeed, counter.carryMs());
    }

    private static GroundStepCounter groundPhysicsSteps(double carryMs, MapleMap map) {
        double nextCarryMs = carryMs
                + AgentMovementPhysicsConfig.configuredMovementTickMs() * mapGroundSpeedScale(map);
        int steps = (int) (nextCarryMs / CLIENT_GROUND_STEP_MS);
        nextCarryMs -= steps * CLIENT_GROUND_STEP_MS;
        return new GroundStepCounter(steps, nextCarryMs);
    }

    private static double applyGroundPhysicsStep(double horizontalSpeed,
                                                 Foothold foothold,
                                                 int desiredDirection,
                                                 AgentMovementProfile profile) {
        double horizontalForce = desiredDirection * maxHorizontalForcePerClientStep(profile);
        if (horizontalForce == 0.0 && Math.abs(horizontalSpeed) < 0.1) {
            return 0.0;
        }

        double inertia = horizontalSpeed / GROUNDSLIP;
        double slope = clampedSlope(foothold);
        double drag = (FRICTION + SLOPEFACTOR * (1.0 + slope * -inertia)) * inertia;
        return horizontalSpeed + horizontalForce - drag;
    }

    private static double clampedSlope(Foothold foothold) {
        if (foothold == null) {
            return 0.0;
        }
        return Math.clamp(foothold.slope(), -0.5, 0.5);
    }

    private static double mapGroundSpeedScale(MapleMap map) {
        float footholdSpeed = map.getFootholdSpeed();
        if (footholdSpeed <= 0.0f) {
            return 1.0;
        }
        return footholdSpeed;
    }

    private static double maxHorizontalForcePerClientStep(AgentMovementProfile profile) {
        return profileOrBase(profile).hForcePxs() * CLIENT_GROUND_STEP_S;
    }

    private static AgentMovementProfile profileOrBase(AgentMovementProfile profile) {
        return profile != null ? profile : AgentMovementProfile.base();
    }

    private static void beginFall(AgentRuntimeEntry entry, Character agent, int airVelocityX) {
        beginFall(entry, agent, agent.getPosition(), airVelocityX);
    }

    private static void beginFall(AgentRuntimeEntry entry, Character agent, Point position, int airVelocityX) {
        AgentClimbStateRuntime.clearBlockedRopeGrab(entry);
        agent.setPosition(new Point(position));
        AgentAirborneLaunchService.launchAirborne(entry, position, 0f, airVelocityX, false);
    }

    record GroundStepResult(Point point,
                            Foothold foothold,
                            AgentGroundTravelState state,
                            int stepX,
                            int velocityX,
                            boolean lostGround) {
    }

    private record GroundStepCounter(int steps, double carryMs) {
    }
}
