package server.agents.capabilities.movement;

import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;

/**
 * Agent-owned adapter for AgentRuntimeEntry-backed explicit movement targets.
 */
public final class AgentMoveTargetStateRuntime {
    private static final int PRECISE_ARRIVAL_PX = 8;

    private AgentMoveTargetStateRuntime() {
    }

    public static Point moveTarget(AgentRuntimeEntry entry) {
        return entry.moveTargetState().target();
    }

    public static boolean hasMoveTarget(AgentRuntimeEntry entry) {
        return entry.moveTargetState().hasTarget();
    }

    public static boolean isPrecise(AgentRuntimeEntry entry) {
        return entry.moveTargetState().precise();
    }

    public static void setMoveTarget(AgentRuntimeEntry entry, Point target, boolean precise) {
        if (entry.navigationDebugState().tracingAir() && !entry.moveTargetState().targetEquals(target)) {
            entry.navigationDebugState().noteMoveTargetSource(StackWalker.getInstance().walk(frames -> frames.skip(1)
                    .limit(7).map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                            + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                    .collect(java.util.stream.Collectors.joining(" < "))));
        }
        entry.moveTargetState().setTarget(target, precise);
    }

    public static void setPreciseMoveTarget(AgentRuntimeEntry entry, Point target) {
        setMoveTarget(entry, target, true);
    }

    public static void clearMoveTarget(AgentRuntimeEntry entry) {
        entry.moveTargetState().clear();
    }

    public static boolean moveTargetEquals(AgentRuntimeEntry entry, Point point) {
        return entry.moveTargetState().targetEquals(point);
    }

    public static boolean hasReachedMoveTarget(AgentRuntimeEntry entry, Point currentPosition, int normalArrivalDistance) {
        Point target = moveTarget(entry);
        if (target == null || currentPosition == null) {
            return false;
        }
        if (isPrecise(entry)) {
            return standsAtPreciseTarget(entry, currentPosition, target);
        }
        return Math.abs(currentPosition.x - target.x) <= normalArrivalDistance
                && Math.abs(currentPosition.y - target.y) <= normalArrivalDistance;
    }

    /**
     * A precise target is reached within 8 px of it, standing on the target's own ground, or hanging on
     * the rope through it when no ground lies under it. The box alone cleared targets in the air, a jump
     * or fall passing the point, and from another foothold inside the box: a ladder beside a ledge, a
     * platform just above or below the spot. The Agent then stopped off its staging spot. Without map
     * geometry or a navigation graph the box alone decides, as before.
     */
    public static boolean standsAtPreciseTarget(AgentRuntimeEntry entry, Point position, Point target) {
        if (position == null || target == null
                || Math.abs(position.x - target.x) > PRECISE_ARRIVAL_PX
                || Math.abs(position.y - target.y) > PRECISE_ARRIVAL_PX) {
            return false;
        }
        if (AgentMovementStateRuntime.inAir(entry)) {
            return false;
        }
        MapleMap map = AgentRuntimeIdentityRuntime.botMap(entry);
        if (map == null || map.getFootholds() == null) {
            return true;
        }
        Point targetGround = AgentGroundingService.findGroundPoint(map, target);
        boolean groundTarget = targetGround != null && Math.abs(targetGround.y - target.y) <= PRECISE_ARRIVAL_PX;
        if (AgentClimbStateRuntime.climbing(entry)) {
            Rope rope = AgentClimbStateRuntime.climbRope(entry);
            return !groundTarget && (rope == null || Math.abs(rope.x() - target.x) <= PRECISE_ARRIVAL_PX);
        }
        Foothold standing = AgentGroundingService.findGroundFoothold(map, position);
        Foothold under = groundTarget ? AgentGroundingService.findGroundFoothold(map, target) : null;
        if (standing == null || under == null || standing.getId() == under.getId()) {
            return true;
        }
        AgentNavigationGraph graph = AgentNavigationGraphService.peekBestGraph(
                map, AgentMovementStateRuntime.movementProfile(entry));
        if (graph == null) {
            return true;
        }
        int standingRegion = graph.regionIdByFootholdId.getOrDefault(standing.getId(), -1);
        return standingRegion >= 0
                && standingRegion == graph.regionIdByFootholdId.getOrDefault(under.getId(), -1);
    }
}
