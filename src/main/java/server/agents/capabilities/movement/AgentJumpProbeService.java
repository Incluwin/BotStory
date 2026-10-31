package server.agents.capabilities.movement;

import server.agents.physics.AgentWallCollisionPolicy;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationPhysicsService;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Set;

public final class AgentJumpProbeService {
    private static final int SYNTHETIC_MAP_BOUND_SIZE = 1 << 18;
    private static final float MAX_FALL_PXS = config.AgentTuning.floatValue("server.agents.capabilities.movement.AgentJumpProbeService.MAX_FALL_PXS");
    private static final int FALL_SIM_TICK_CAP = config.AgentTuning.intValue("server.agents.capabilities.movement.AgentJumpProbeService.FALL_SIM_TICK_CAP");

    private AgentJumpProbeService() {
    }

    public static AgentJumpLanding simulateJumpLanding(MapleMap map, Point from, int stepX) {
        return simulateJumpLanding(map, from, stepX, AgentMovementProfile.base());
    }

    public static AgentJumpLanding simulateJumpLanding(MapleMap map,
                                                       Point from,
                                                       int stepX,
                                                       AgentMovementProfile profile) {
        return simulateLanding(map, from, -AgentMovementKinematicsService.jumpForcePerTick(profile), stepX, 0L);
    }

    public static AgentJumpLanding simulateFlashJumpLanding(MapleMap map,
                                                            Point from,
                                                            int direction,
                                                            AgentMovementProfile profile) {
        if (direction == 0) {
            return null;
        }
        int initialStepX = Integer.signum(direction)
                * AgentMovementKinematicsService.walkStep(map, profile);
        double tickSeconds = AgentMovementPhysicsConfig.configuredMovementTickMs() / 1000.0;
        FlashImpulse impulse = new FlashImpulse(
                (int) Math.round(Integer.signum(direction)
                        * AgentMovementSkillConfig.FLASH_JUMP_HORIZONTAL_SPEED_PXS * tickSeconds),
                -AgentMovementSkillConfig.FLASH_JUMP_UPWARD_SPEED_PXS * (float) tickSeconds);
        return simulateLanding(map, from,
                -AgentMovementKinematicsService.jumpForcePerTick(profile),
                initialStepX, 0L, null, impulse);
    }

    public static AgentJumpLanding simulateRopeJumpLanding(MapleMap map,
                                                           Point from,
                                                           int direction,
                                                           AgentMovementProfile profile) {
        return simulateLanding(map, from, -AgentMovementKinematicsService.ropeJumpForcePerTick(profile),
                AgentMovementKinematicsService.ropeJumpVelocityX(map, profile, direction), 0L);
    }

    public static AgentJumpLanding simulateFallLanding(MapleMap map, Point from, int stepX) {
        return simulateLanding(map, from, 0f, stepX, 0L);
    }

    public static AgentJumpLanding simulateFallLanding(MapleMap map,
                                                       Point from,
                                                       int stepX,
                                                       int moverZMass) {
        return simulateLanding(map, from, 0f, stepX, 0L, moverZMass);
    }

    public static AgentJumpLanding simulateDownJumpLanding(MapleMap map, Point from) {
        if (!AgentGroundCollisionService.canStartDownJump(map, from)) {
            return null;
        }
        return simulateLanding(map, from, -AgentAirborneLaunchService.downJumpForcePerTick(), 0,
                AgentMovementPhysicsConfig.configuredDownJumpGraceMs());
    }

    public static AgentWalkOffLanding simulateWalkOffLanding(MapleMap map,
                                                             Point from,
                                                             int desiredDir,
                                                             AgentMovementProfile profile) {
        return from == null ? null : simulateWalkOffLanding(
                map, from, desiredDir, new AgentGroundTravelState(from.x, 0.0, 0.0), profile);
    }

    public static List<AgentWalkOffLanding> walkOffLandingVariants(MapleMap map,
                                                                   Point from,
                                                                   int desiredDir,
                                                                   AgentMovementProfile profile) {
        return walkOffLandingVariants(
                map, from, desiredDir, profile, AgentWallCollisionPolicy.moverZMassAt(map, from));
    }

    public static List<AgentWalkOffLanding> walkOffLandingVariants(MapleMap map,
                                                                   Point from,
                                                                   int desiredDir,
                                                                   AgentMovementProfile profile,
                                                                   int moverZMass) {
        List<AgentWalkOffLanding> outcomes = new java.util.ArrayList<>();
        double terminalHSpeed = AgentMovementKinematicsService.maxHorizontalSpeedPerClientStep(profile) * desiredDir;
        double[] physicsXPhases = {0.0, -0.4, 0.4};
        double[] carryPhases = {0.0, AgentMovementKinematicsService.CLIENT_GROUND_STEP_MS / 2.0};
        double[] launchSpeeds = {0.0, terminalHSpeed};
        for (double phase : physicsXPhases) {
            for (double carryMs : carryPhases) {
                for (double horizontalSpeed : launchSpeeds) {
                    outcomes.add(simulateWalkOffLanding(map, from, desiredDir,
                            new AgentGroundTravelState(from.x + phase, horizontalSpeed, carryMs),
                            profile, moverZMass));
                }
            }
        }
        return outcomes;
    }

    public static AgentWalkOffLanding simulateWalkOffLanding(MapleMap map,
                                                             Point from,
                                                             int desiredDir,
                                                             AgentGroundTravelState initialState,
                                                             AgentMovementProfile profile) {
        return simulateWalkOffLanding(
                map, from, desiredDir, initialState, profile,
                AgentWallCollisionPolicy.moverZMassAt(map, from));
    }

    public static AgentWalkOffLanding simulateWalkOffLanding(MapleMap map,
                                                             Point from,
                                                             int desiredDir,
                                                             AgentGroundTravelState initialState,
                                                             AgentMovementProfile profile,
                                                             int moverZMass) {
        if (map == null || from == null || desiredDir == 0 || initialState == null) {
            return null;
        }

        Foothold foothold = AgentGroundingService.findGroundFoothold(map, from);
        if (foothold == null) {
            return null;
        }

        Point cursor = new Point(from);
        Foothold currentFoothold = foothold;
        AgentGroundTravelState state = initialState;
        int elapsedMs = 0;
        for (int i = 0; i < 256; i++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            AgentGroundPhysicsService.GroundStepResult step =
                    AgentGroundPhysicsService.simulateGroundMotion(map, cursor, currentFoothold, desiredDir, state, profile);
            if (step.lostGround()) {
                if (step.stepX() == 0) {
                    return null;
                }
                AgentJumpLanding landing = simulateFallLanding(
                        map, step.point(), step.stepX(), moverZMass);
                if (landing == null) {
                    return null;
                }
                return new AgentWalkOffLanding(new Point(step.point()), step.stepX(), landing,
                        elapsedMs + estimateFallLandingTimeMs(map, step.point(), step.stepX()));
            }

            cursor = step.point();
            currentFoothold = step.foothold() != null ? step.foothold() : currentFoothold;
            state = step.state();
            elapsedMs += AgentMovementPhysicsConfig.configuredMovementTickMs();
        }
        return null;
    }

    public static int estimateFallLandingTimeMs(MapleMap map, Point from, int stepX) {
        return estimateLandingTimeMs(map, from, 0f, stepX, 0L);
    }

    public static Point simulateGroundJumpRopeGrab(MapleMap map,
                                                   Point from,
                                                   int stepX,
                                                   Rope targetRope,
                                                   AgentMovementProfile profile) {
        return simulateRopeGrab(map, from, -AgentMovementKinematicsService.jumpForcePerTick(profile), stepX,
                targetRope, 0L);
    }

    public static int estimateGroundJumpRopeGrabTimeMs(MapleMap map,
                                                       Point from,
                                                       int stepX,
                                                       Rope targetRope,
                                                       AgentMovementProfile profile) {
        return estimateRopeGrabTimeMs(map, from, -AgentMovementKinematicsService.jumpForcePerTick(profile), stepX,
                targetRope, 0L);
    }

    public static Point simulateDownJumpRopeGrab(MapleMap map, Point from, Rope targetRope) {
        return simulateRopeGrab(map, from, -AgentAirborneLaunchService.downJumpForcePerTick(), 0,
                targetRope, AgentMovementPhysicsConfig.configuredDownJumpGraceMs());
    }

    public static int estimateDownJumpRopeGrabTimeMs(MapleMap map, Point from, Rope targetRope) {
        return estimateRopeGrabTimeMs(map, from, -AgentAirborneLaunchService.downJumpForcePerTick(), 0,
                targetRope, AgentMovementPhysicsConfig.configuredDownJumpGraceMs());
    }

    public static int estimateRopeJumpLandingTimeMs(MapleMap map,
                                                    Point from,
                                                    int direction,
                                                    AgentMovementProfile profile) {
        return estimateLandingTimeMs(map, from, -AgentMovementKinematicsService.ropeJumpForcePerTick(profile),
                AgentMovementKinematicsService.ropeJumpVelocityX(map, profile, direction), 0L);
    }

    public static Point simulateRopeJumpGrab(MapleMap map,
                                             Point from,
                                             int direction,
                                             Rope targetRope,
                                             AgentMovementProfile profile) {
        return simulateRopeGrab(map, from, -AgentMovementKinematicsService.ropeJumpForcePerTick(profile),
                AgentMovementKinematicsService.ropeJumpVelocityX(map, profile, direction), targetRope, 0L);
    }

    public static int estimateRopeJumpGrabTimeMs(MapleMap map,
                                                 Point from,
                                                 int direction,
                                                 Rope targetRope,
                                                 AgentMovementProfile profile) {
        return estimateRopeGrabTimeMs(map, from, -AgentMovementKinematicsService.ropeJumpForcePerTick(profile),
                AgentMovementKinematicsService.ropeJumpVelocityX(map, profile, direction), targetRope, 0L);
    }

    public static AgentPostLandingJump simulateJumpLandingWithPostLandingTicks(MapleMap map,
                                                                               Point from,
                                                                               int stepX,
                                                                               AgentMovementProfile profile,
                                                                               int postLandingTicks) {
        AgentJumpLanding landing = simulateJumpLanding(map, from, stepX, profile);
        if (landing == null) {
            return null;
        }
        return simulatePostLandingGroundTicks(map, landing, Integer.compare(stepX, 0), profile, postLandingTicks);
    }

    /**
     * A rope jump's landing and where it is {@code ticks} later with no key held: it lands with the speed it flew
     * (AgentAirbornePhysicsService.landOnGround), so a landing in a platform's last pixels slides off it.
     */
    public static AgentPostLandingJump simulateRopeJumpLandingAtRest(MapleMap map,
                                                                     Point from,
                                                                     int direction,
                                                                     AgentMovementProfile profile,
                                                                     int ticks) {
        AgentJumpLanding landing = simulateRopeJumpLanding(map, from, direction, profile);
        if (landing == null) {
            return null;
        }
        double speed = AgentAirbornePhysicsService.landingGroundHSpeed(map, landing.foothold(),
                landing.incomingDeltaX(), landing.incomingDeltaY(), profile);
        return simulatePostLandingGroundTicks(map, landing, 0,
                new AgentGroundTravelState(landing.point().x, speed, 0.0), profile, ticks);
    }

    public static boolean canReachRopeFromGround(MapleMap map, Point from, Rope rope) {
        return canReachRopeFromGround(map, from, rope, AgentMovementProfile.base());
    }

    public static boolean canReachRopeFromGround(MapleMap map, Point from, Rope rope, AgentMovementProfile profile) {
        int dx = Math.abs(rope.x() - from.x);
        if (dx <= AgentMovementPhysicsConfig.configuredRopeGrabX()
                && from.y >= AgentNavigationPhysicsService.firstClimbableY(rope)
                && from.y <= rope.bottomY()) {
            return true;
        }
        if (rope.topY() >= from.y) {
            return false;
        }

        int jumpReach = (int) Math.ceil(AgentMovementKinematicsService.calculateMaxJumpHeight(profile));
        return rope.bottomY() >= from.y - jumpReach
                && dx <= AgentMovementKinematicsService.maxJumpHorizontalTravel(map, profile);
    }

    private static AgentPostLandingJump simulatePostLandingGroundTicks(MapleMap map,
                                                                       AgentJumpLanding landing,
                                                                       int desiredDir,
                                                                       AgentMovementProfile profile,
                                                                       int ticks) {
        return simulatePostLandingGroundTicks(map, landing, desiredDir,
                new AgentGroundTravelState(landing.point().x, 0.0, 0.0), profile, ticks);
    }

    private static AgentPostLandingJump simulatePostLandingGroundTicks(MapleMap map,
                                                                       AgentJumpLanding landing,
                                                                       int desiredDir,
                                                                       AgentGroundTravelState initialState,
                                                                       AgentMovementProfile profile,
                                                                       int ticks) {
        Point cursor = landing.point();
        Foothold currentFoothold = landing.foothold();
        AgentGroundTravelState state = initialState;
        for (int i = 0; i < Math.max(0, ticks); i++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            AgentGroundPhysicsService.GroundStepResult step =
                    AgentGroundPhysicsService.simulateGroundMotion(map, cursor, currentFoothold, desiredDir, state, profile);
            if (step.lostGround()) {
                return new AgentPostLandingJump(landing, step.point(), currentFoothold, true);
            }
            cursor = step.point();
            currentFoothold = step.foothold() != null ? step.foothold() : currentFoothold;
            state = step.state();
        }
        return new AgentPostLandingJump(landing, cursor, currentFoothold, false);
    }

    private static Point simulateRopeGrab(MapleMap map,
                                          Point from,
                                          float initialVelocityY,
                                          double stepX,
                                          Rope targetRope,
                                          long landingGraceMs) {
        RopeGrabResult result = simulateRopeGrabCore(map, from, initialVelocityY, stepX, targetRope, landingGraceMs);
        return result != null ? result.point() : null;
    }

    private static int estimateRopeGrabTimeMs(MapleMap map,
                                              Point from,
                                              float initialVelocityY,
                                              double stepX,
                                              Rope targetRope,
                                              long landingGraceMs) {
        RopeGrabResult result = simulateRopeGrabCore(map, from, initialVelocityY, stepX, targetRope, landingGraceMs);
        return result != null ? result.ticks() * AgentMovementPhysicsConfig.configuredMovementTickMs()
                : Integer.MAX_VALUE;
    }

    private static RopeGrabResult simulateRopeGrabCore(MapleMap map,
                                                       Point from,
                                                       float initialVelocityY,
                                                       double stepX,
                                                       Rope targetRope,
                                                       long landingGraceMs) {
        if (targetRope == null) {
            return null;
        }

        float velocityY = initialVelocityY;
        double physicsX = from.x;
        double physicsY = from.y;
        int previousIntY = from.y;
        long remainingLandingGraceMs = Math.max(0L, landingGraceMs);
        float gravity = AgentMovementKinematicsService.gravityPerTick();
        float maxFall = maxFallPerTick();
        int floorY = mapFloorY(map);
        int tickLimit = fallSimulationTickLimit(from.y, initialVelocityY, gravity, maxFall, floorY);

        for (int tick = 0; tick < tickLimit; tick++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            Point current = new Point((int) Math.round(physicsX), (int) Math.round(physicsY));
            if (canGrabRopeAtPoint(current, targetRope)) {
                return new RopeGrabResult(new Point(targetRope.x(), current.y), tick);
            }

            if (remainingLandingGraceMs > 0L) {
                remainingLandingGraceMs = Math.max(0L,
                        remainingLandingGraceMs - AgentMovementPhysicsConfig.configuredMovementTickMs());
            }

            physicsX += stepX;
            physicsY += velocityY + 0.5f * gravity;
            velocityY = Math.min(velocityY + gravity, maxFall);

            int x = (int) Math.round(physicsX);
            int intY = (int) Math.round(physicsY);
            Point previousPoint = new Point((int) Math.round(physicsX - stepX), previousIntY);
            Point nextPoint = new Point(x, intY);
            AirCollision collision = resolveAirCollision(map, previousPoint, nextPoint);
            if (collision.type() == AirCollisionType.WALL) {
                physicsX = collision.point().x;
                physicsY = collision.point().y;
                stepX = 0;
                previousIntY = collision.point().y;
                continue;
            }
            if (collision.type() == AirCollisionType.CEILING) {
                physicsX = collision.point().x;
                physicsY = collision.point().y;
                velocityY = 0f;
                previousIntY = collision.point().y;
                continue;
            }
            if (collision.type() == AirCollisionType.LAND && remainingLandingGraceMs == 0L) {
                return null;
            }

            if (intY > floorY) {
                return null;
            }
            Point grab = ropeGrabAfterTick(previousPoint, nextPoint, targetRope);
            if (grab != null) {
                return new RopeGrabResult(grab, tick + 1);
            }
            previousIntY = intY;
        }

        return null;
    }

    private static boolean canGrabRopeAtPoint(Point position, Rope rope) {
        return Math.abs(position.x - rope.x()) <= AgentMovementPhysicsConfig.configuredRopeGrabX()
                && position.y >= AgentNavigationPhysicsService.firstClimbableY(rope)
                && position.y <= rope.bottomY();
    }

    /**
     * Where a climbing-intent Agent catches {@code rope} after a free-flight tick from {@code from}
     * to {@code to}: at the tick end when that is within reach, else at the first point of the
     * tick's path within reach, else null. Tick ends alone miss a rope passed mid-tick: an Agent
     * can be within reach above the rope's bottom during a tick and below it when the tick ends.
     * The graph's rope-grab simulation and the live airborne executor both use this rule.
     */
    public static Point ropeGrabAfterTick(Point from, Point to, Rope rope) {
        if (canGrabRopeAtPoint(to, rope)) {
            return new Point(rope.x(), to.y);
        }
        return ropeGrabAlongPath(from, to, rope, AgentNavigationPhysicsService.firstClimbableY(rope), rope.bottomY());
    }

    /**
     * The first point of the straight path from {@code from} to {@code to} within grab reach of
     * {@code rope} at a height in [minY, maxY], as a point on the rope, or null.
     */
    static Point ropeGrabAlongPath(Point from, Point to, Rope rope, int minY, int maxY) {
        int reach = AgentMovementPhysicsConfig.configuredRopeGrabX();
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double enter = 0.0;
        double exit = 1.0;
        if (dx == 0.0) {
            if (Math.abs(from.x - rope.x()) > reach) {
                return null;
            }
        } else {
            double left = (rope.x() - reach - from.x) / dx;
            double right = (rope.x() + reach - from.x) / dx;
            enter = Math.max(enter, Math.min(left, right));
            exit = Math.min(exit, Math.max(left, right));
        }
        if (dy == 0.0) {
            if (from.y < minY || from.y > maxY) {
                return null;
            }
        } else {
            double top = (minY - from.y) / dy;
            double bottom = (maxY - from.y) / dy;
            enter = Math.max(enter, Math.min(top, bottom));
            exit = Math.min(exit, Math.max(top, bottom));
        }
        if (enter > exit) {
            return null;
        }
        int y = (int) Math.round(from.y + dy * enter);
        return new Point(rope.x(), Math.clamp(y, minY, maxY));
    }

    private static AgentJumpLanding simulateLanding(MapleMap map,
                                                    Point from,
                                                    float initialVelocityY,
                                                    double stepX,
                                                    long landingGraceMs) {
        return simulateLanding(map, from, initialVelocityY, stepX, landingGraceMs, null);
    }

    private static AgentJumpLanding simulateLanding(MapleMap map,
                                                    Point from,
                                                    float initialVelocityY,
                                                    double stepX,
                                                    long landingGraceMs,
                                                    Integer moverZMass) {
        return simulateLanding(map, from, initialVelocityY, stepX, landingGraceMs, moverZMass, null);
    }

    private static AgentJumpLanding simulateLanding(MapleMap map,
                                                    Point from,
                                                    float initialVelocityY,
                                                    double stepX,
                                                    long landingGraceMs,
                                                    Integer moverZMass,
                                                    FlashImpulse flashImpulse) {
        float velocityY = initialVelocityY;
        double physicsX = from.x;
        double physicsY = from.y;
        int previousIntY = from.y;
        boolean flashFired = false;
        long remainingLandingGraceMs = Math.max(0L, landingGraceMs);
        float gravity = AgentMovementKinematicsService.gravityPerTick();
        float maxFall = maxFallPerTick();
        int floorY = mapFloorY(map);
        int tickLimit = fallSimulationTickLimit(from.y, initialVelocityY, gravity, maxFall, floorY);

        for (int tick = 0; tick < tickLimit; tick++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            if (remainingLandingGraceMs > 0L) {
                remainingLandingGraceMs = Math.max(0L,
                        remainingLandingGraceMs - AgentMovementPhysicsConfig.configuredMovementTickMs());
            }

            if (!flashFired && flashImpulse != null && velocityY >= 0f) {
                stepX = flashImpulse.stepX();
                velocityY = flashImpulse.velocityY();
                flashFired = true;
            }

            physicsX += stepX;
            physicsY += velocityY + 0.5f * gravity;
            velocityY = Math.min(velocityY + gravity, maxFall);

            int x = (int) Math.round(physicsX);
            int intY = (int) Math.round(physicsY);
            Point previousPoint = new Point((int) Math.round(physicsX - stepX), previousIntY);
            Point nextPoint = new Point(x, intY);
            AirCollision collision = moverZMass == null
                    ? resolveAirCollision(map, previousPoint, nextPoint)
                    : resolveAirCollision(map, previousPoint, nextPoint, moverZMass);
            if (collision.type() == AirCollisionType.WALL) {
                physicsX = collision.point().x;
                physicsY = collision.point().y;
                stepX = 0;
                previousIntY = collision.point().y;
                continue;
            }
            if (collision.type() == AirCollisionType.CEILING) {
                physicsX = collision.point().x;
                physicsY = collision.point().y;
                velocityY = 0f;
                previousIntY = collision.point().y;
                continue;
            }
            if (collision.type() == AirCollisionType.LAND && remainingLandingGraceMs == 0L) {
                return new AgentJumpLanding(collision.point(), collision.foothold(),
                        nextPoint.x - previousPoint.x, nextPoint.y - previousPoint.y,
                        (tick + 1) * AgentMovementPhysicsConfig.configuredMovementTickMs());
            }

            if (intY > floorY) {
                return null;
            }
            previousIntY = intY;
        }

        return null;
    }

    private record FlashImpulse(int stepX, float velocityY) {
    }

    private static int mapFloorY(MapleMap map) {
        Rectangle area = map == null ? null : map.getMapArea();
        return area != null && area.height > 0 ? area.y + area.height + 600 : Integer.MAX_VALUE;
    }

    /**
     * Bounds expensive collision probes to the free-fall time needed to pass the map floor.
     * Wall collisions preserve vertical progress and ceiling collisions shorten an ascent, so
     * free fall plus a small rounding margin is a safe upper bound when VR/minimap bounds exist.
     * Maps without bounds retain the large runaway backstop.
     */
    private static int fallSimulationTickLimit(int fromY,
                                               float initialVelocityY,
                                               float gravity,
                                               float maxFall,
                                               int floorY) {
        if (floorY == Integer.MAX_VALUE || gravity <= 0f || maxFall <= 0f) {
            return FALL_SIM_TICK_CAP;
        }

        double y = fromY;
        float velocityY = initialVelocityY;
        for (int tick = 0; tick < FALL_SIM_TICK_CAP; tick++) {
            y += velocityY + 0.5f * gravity;
            velocityY = Math.min(velocityY + gravity, maxFall);
            if (y > floorY) {
                return Math.min(FALL_SIM_TICK_CAP, tick + 9);
            }
        }
        return FALL_SIM_TICK_CAP;
    }

    private static int estimateLandingTimeMs(MapleMap map,
                                             Point from,
                                             float initialVelocityY,
                                             double stepX,
                                             long landingGraceMs) {
        AgentJumpLanding landing = simulateLanding(map, from, initialVelocityY, stepX, landingGraceMs);
        return landing != null ? landing.timeMs() : Integer.MAX_VALUE;
    }

    static AirCollision resolveAirCollision(MapleMap map, Point previousPos, Point nextPos) {
        return resolveAirCollision(map, previousPos, nextPos,
                AgentWallCollisionPolicy.moverZMassAt(map, previousPos));
    }

    static AirCollision resolveAirCollision(MapleMap map, Point previousPos, Point nextPos, int moverZMass) {
        if (map == null || map.getFootholds() == null || previousPos == null || nextPos == null) {
            return AirCollision.none();
        }
        AirCollision wall = findWallCollision(map, previousPos, nextPos, moverZMass);
        AirCollision ceiling = findCeilingCollision(map, previousPos, nextPos);
        AirCollision landing = findGroundCollision(map, previousPos, nextPos);
        AirCollision best = AirCollision.none();
        if (wall.type() != AirCollisionType.NONE) {
            best = wall;
        }
        if (ceiling.type() != AirCollisionType.NONE && ceiling.progress() < best.progress()) {
            best = ceiling;
        }
        // A landing as far along as a wall hit, on a floor that runs back toward where the Agent came from, is
        // the Agent reaching that floor's end where its wall starts: it lands on that end. Taking the wall held it
        // beside the wall under the floor's top, and it fell through the floor (Drake's Nest blocks, 105090310
        // x 362/538 and 105090312 x 1213).
        if (landing.type() != AirCollisionType.NONE
                && (landing.progress() < best.progress()
                || best == wall && landing.progress() == wall.progress()
                && floorRunsBackFrom(landing, Integer.compare(nextPos.x, previousPos.x)))) {
            best = landing;
        }
        return best;
    }

    private static boolean floorRunsBackFrom(AirCollision landing, int dir) {
        Foothold floor = landing.foothold();
        if (floor == null || dir == 0) {
            return false;
        }
        return dir < 0
                ? Math.max(floor.getX1(), floor.getX2()) > landing.point().x
                : Math.min(floor.getX1(), floor.getX2()) < landing.point().x;
    }

    private static AirCollision findWallCollision(MapleMap map, Point previousPos, Point nextPos, int moverZMass) {
        if (map == null || map.getFootholds() == null || previousPos.x == nextPos.x) {
            return AirCollision.none();
        }

        AirCollision best = mapSideBoundaryCollision(map, previousPos, nextPos);
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            if (!AgentWallCollisionPolicy.collides(map, foothold, moverZMass)) {
                continue;
            }
            AirCollision collision = wallCollision(map, foothold, previousPos, nextPos, false);
            if (collision.type() == AirCollisionType.WALL && collision.progress() < best.progress()) {
                best = collision;
            }
        }
        return best;
    }

    private static AirCollision mapSideBoundaryCollision(MapleMap map, Point previousPos, Point nextPos) {
        Rectangle area = map.getMapArea();
        if (area == null || area.width <= 0 || area.height <= 0 || previousPos.x == nextPos.x) {
            return AirCollision.none();
        }

        int dir = Integer.compare(nextPos.x, previousPos.x);
        int boundaryX = dir > 0 ? effectiveRightBoundaryX(map, area) : effectiveLeftBoundaryX(map, area);
        if (dir > 0 && (previousPos.x > boundaryX || nextPos.x <= boundaryX)) {
            return AirCollision.none();
        }
        if (dir < 0 && (previousPos.x < boundaryX || nextPos.x >= boundaryX)) {
            return AirCollision.none();
        }

        double progress = (boundaryX - previousPos.x) / (double) (nextPos.x - previousPos.x);
        if (progress < 0.0 || progress > 1.0) {
            return AirCollision.none();
        }

        double yAtBoundary = previousPos.y + (nextPos.y - previousPos.y) * progress;
        return new AirCollision(AirCollisionType.WALL,
                new Point(boundaryX, (int) Math.round(yAtBoundary)),
                null,
                progress);
    }

    private static int effectiveLeftBoundaryX(MapleMap map, Rectangle area) {
        if (!hasUsableFootholdXBounds(map)) {
            return area.x;
        }
        int footholdMinX = map.getFootholds().getMinDropX();
        if (isSyntheticMapArea(area)) {
            return footholdMinX;
        }
        return Math.min(area.x, footholdMinX);
    }

    private static int effectiveRightBoundaryX(MapleMap map, Rectangle area) {
        if (!hasUsableFootholdXBounds(map)) {
            return area.x + area.width;
        }
        int footholdMaxX = map.getFootholds().getMaxDropX();
        if (isSyntheticMapArea(area)) {
            return footholdMaxX;
        }
        return Math.max(area.x + area.width, footholdMaxX);
    }

    private static boolean isSyntheticMapArea(Rectangle area) {
        return area.width >= SYNTHETIC_MAP_BOUND_SIZE && area.height >= SYNTHETIC_MAP_BOUND_SIZE;
    }

    private static boolean hasUsableFootholdXBounds(MapleMap map) {
        return map.getFootholds() != null
                && map.getFootholds().getMinDropX() < map.getFootholds().getMaxDropX();
    }

    private static Set<Integer> getCollidableFromBelowIds(MapleMap map) {
        Set<Integer> cached = AgentNavigationGraphService.getCachedCollidableFromBelowIds(map.getId());
        if (cached != null) {
            return cached;
        }
        return AgentNavigationGraphService.computeCollidableFromBelowIds(map);
    }

    private static AirCollision findCeilingCollision(MapleMap map, Point previousPos, Point nextPos) {
        if (map == null || map.getFootholds() == null || nextPos.y >= previousPos.y) {
            return AirCollision.none();
        }

        Set<Integer> collidableFromBelow = getCollidableFromBelowIds(map);
        if (collidableFromBelow.isEmpty()) {
            return AirCollision.none();
        }

        AirCollision best = AirCollision.none();
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            if (foothold.isWall() || !collidableFromBelow.contains(foothold.getId())) {
                continue;
            }
            AirCollision collision = ceilingCollision(foothold, previousPos, nextPos);
            if (collision.type() == AirCollisionType.CEILING && collision.progress() < best.progress()) {
                best = collision;
            }
        }
        return best;
    }

    private static AirCollision findGroundCollision(MapleMap map, Point previousPos, Point nextPos) {
        if (map == null || previousPos == null || nextPos == null || nextPos.y < previousPos.y) {
            return AirCollision.none();
        }

        int startX = previousPos.x;
        int endX = nextPos.x;
        int dir = Integer.compare(endX, startX);
        if (dir == 0) {
            return landingAtX(map, previousPos, nextPos, startX, 1.0);
        }

        int steps = Math.abs(endX - startX);
        // The floor under the Agent where the move starts, followed along its chain: where it rises toward the Agent
        // to the Agent's own height or above (a bump's flank or a dip's far side on Drake's Nest's bottom floor,
        // 105090312 x 1235 and x 1350), the probes, which start a pixel below the Agent, missed it and the Agent
        // went through.
        Foothold under = map.getFootholds() == null ? null : map.getFootholds().findBelow(previousPos);
        java.util.Map<Integer, Foothold> footholdsById = under == null ? null : footholdsById(map);
        for (int i = 0; i <= steps; i++) {
            int x = startX + dir * i;
            double progress = i / (double) steps;
            AirCollision landing = landingAtX(map, previousPos, nextPos, x, progress);
            if (landing.type == AirCollisionType.LAND) {
                return landing;
            }
            under = floorChainAt(map, footholdsById, under, x);
            if (i > 0 && under != null) {
                AirCollision rising = landingOnRisingFloor(map, previousPos, nextPos, under, x, progress);
                if (rising.type == AirCollisionType.LAND) {
                    return rising;
                }
            }
        }
        return AirCollision.none();
    }

    private static java.util.Map<Integer, Foothold> footholdsById(MapleMap map) {
        server.agents.capabilities.navigation.AgentNavigationWalkRegionLookupService.WalkRegionLookup lookup =
                server.agents.capabilities.navigation.AgentNavigationWalkRegionLookupService
                        .resolveWalkRegionLookup(map);
        return lookup == null ? null : lookup.footholdsById();
    }

    private static Foothold floorChainAt(MapleMap map,
                                         java.util.Map<Integer, Foothold> footholdsById,
                                         Foothold floor,
                                         int x) {
        for (int hops = 0; floor != null && hops < 64; hops++) {
            if (floor.isWall() || floor.getX1() >= floor.getX2()) {
                return null;
            }
            if (x >= floor.getX1() && x <= floor.getX2()) {
                return floor;
            }
            floor = footholdById(map, footholdsById, x < floor.getX1() ? floor.getPrev() : floor.getNext());
        }
        return null;
    }

    private static Foothold footholdById(MapleMap map, java.util.Map<Integer, Foothold> footholdsById, int id) {
        if (id <= 0) {
            return null;
        }
        if (footholdsById != null) {
            return footholdsById.get(id);
        }
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            if (foothold.getId() == id) {
                return foothold;
            }
        }
        return null;
    }

    private static AirCollision landingOnRisingFloor(MapleMap map,
                                                     Point previousPos,
                                                     Point nextPos,
                                                     Foothold floor,
                                                     int x,
                                                     double progress) {
        int yAtX = (int) Math.round(previousPos.y + (nextPos.y - previousPos.y) * progress);
        int floorY = (int) Math.floor(floor.getY1()
                + (floor.getY2() - floor.getY1()) * (x - floor.getX1()) / (double) (floor.getX2() - floor.getX1()));
        Point probe = new Point(x, floorY - 2);
        Point ground = map.getPointBelow(probe);
        Foothold foothold = map.getFootholds().findBelow(probe);
        if (ground == null || foothold == null || foothold.getId() != floor.getId()
                || ground.y > previousPos.y || yAtX < ground.y) {
            return AirCollision.none();
        }
        return new AirCollision(AirCollisionType.LAND, new Point(x, ground.y), foothold, progress);
    }

    private static AirCollision landingAtX(MapleMap map,
                                           Point previousPos,
                                           Point nextPos,
                                           int x,
                                           double progress) {
        int yAtX = (int) Math.round(previousPos.y + (nextPos.y - previousPos.y) * progress);
        // A fall straight down probes from the Agent's own height, so one that starts exactly on a floor's top
        // lands on it: held beside a wall at the foot of a cliff it is level with the floor there, and probing a
        // pixel lower let it fall through that floor (105090312 x 1214). A move with a horizontal part keeps the
        // pixel: it leaves the floor it starts on (a walk-off from a floor's end) and lands tangent further on.
        int probeY = previousPos.x == nextPos.x ? previousPos.y : previousPos.y + 1;
        AirCollision landing = landingAtProbeY(map, previousPos, x, yAtX, progress, probeY, false);
        if (landing.type() == AirCollisionType.LAND) {
            return landing;
        }

        if (x != previousPos.x) {
            return landingAtProbeY(map, previousPos, x, yAtX, progress, previousPos.y, true);
        }

        return AirCollision.none();
    }

    private static AirCollision landingAtProbeY(MapleMap map,
                                                Point previousPos,
                                                int x,
                                                int yAtX,
                                                double progress,
                                                int probeY,
                                                boolean requireTangentFloor) {
        var footholds = map.getFootholds();
        if (footholds == null) {
            return AirCollision.none();
        }
        Point probe = new Point(x, probeY);
        Point floor = map.getPointBelow(probe);
        if (floor == null) {
            return AirCollision.none();
        }

        int minY = Math.min(previousPos.y, yAtX);
        int maxY = Math.max(previousPos.y, yAtX);
        if (floor.y < minY || floor.y > maxY) {
            return AirCollision.none();
        }
        if (requireTangentFloor && floor.y != previousPos.y) {
            return AirCollision.none();
        }

        Foothold foothold = footholds.findBelow(probe);
        if (foothold == null) {
            return AirCollision.none();
        }

        return new AirCollision(AirCollisionType.LAND, new Point(x, floor.y), foothold, progress);
    }

    private static AirCollision wallCollision(MapleMap map,
                                              Foothold wall,
                                              Point previousPos,
                                              Point nextPos,
                                              boolean allowWalkableGroundEndpoint) {
        int wallX = wall.getX1();
        int startX = previousPos.x;
        int endX = nextPos.x;
        if (startX == endX) {
            return AirCollision.none();
        }

        int dir = Integer.compare(endX, startX);
        double progress = (wallX - startX) / (double) (endX - startX);
        int minY = Math.min(wall.getY1(), wall.getY2());
        int maxY = Math.max(wall.getY1(), wall.getY2());
        // Starting on the wall's own x below its top, the move hits it when it heads into the ground the wall
        // bounds. Skipping that case let a knockback from a floor's first pixel fly west through the cliff rising
        // from it (105090312 x 1213) and fall under the ledge above to the bottom of the map. A move from the
        // wall's top corner is on the floor capping it, not in the rock.
        boolean startsOnWall = progress == 0.0
                && previousPos.y > minY
                && dir == AgentWallCollisionPolicy.groundSide(map, wall);
        if ((progress <= 0.0 && !startsOnWall) || progress > 1.0) {
            return AirCollision.none();
        }

        double yAtWall = previousPos.y + (nextPos.y - previousPos.y) * progress;
        if (yAtWall < minY || yAtWall > maxY) {
            return AirCollision.none();
        }
        if (allowWalkableGroundEndpoint && isWalkableGroundWallEndpoint(yAtWall, minY, maxY)) {
            return AirCollision.none();
        }

        int safeX = wallX - dir;
        return new AirCollision(AirCollisionType.WALL,
                new Point(safeX, (int) Math.round(yAtWall)),
                wall,
                progress);
    }

    private static AirCollision ceilingCollision(Foothold foothold, Point previousPos, Point nextPos) {
        if (foothold.getY1() != foothold.getY2()) {
            return AirCollision.none();
        }

        int ceilingY = foothold.getY1();
        if (ceilingY > previousPos.y || ceilingY < nextPos.y) {
            return AirCollision.none();
        }

        double progress = (ceilingY - previousPos.y) / (double) (nextPos.y - previousPos.y);
        if (progress <= 0.0 || progress > 1.0) {
            return AirCollision.none();
        }

        double xAtCeiling = previousPos.x + (nextPos.x - previousPos.x) * progress;
        int minX = Math.min(foothold.getX1(), foothold.getX2());
        int maxX = Math.max(foothold.getX1(), foothold.getX2());
        if (xAtCeiling < minX || xAtCeiling > maxX) {
            return AirCollision.none();
        }

        return new AirCollision(AirCollisionType.CEILING,
                new Point((int) Math.round(xAtCeiling), ceilingY + 1),
                foothold,
                progress);
    }

    private static boolean isWalkableGroundWallEndpoint(double yAtWall, int minY, int maxY) {
        if (Math.abs(yAtWall - minY) < 0.001) {
            return true;
        }
        return Math.abs(yAtWall - maxY) < 0.001
                && maxY - minY <= AgentMovementPhysicsConfig.configuredMaxSlopeUp();
    }

    private static float maxFallPerTick() {
        return MAX_FALL_PXS * AgentMovementPhysicsConfig.configuredMovementTickMs() / 1000f;
    }

    enum AirCollisionType {
        NONE,
        WALL,
        CEILING,
        LAND
    }

    record AirCollision(AirCollisionType type, Point point, Foothold foothold, double progress) {
        static AirCollision none() {
            return new AirCollision(AirCollisionType.NONE, null, null, Double.POSITIVE_INFINITY);
        }
    }

    private record RopeGrabResult(Point point, int ticks) {
    }
}
