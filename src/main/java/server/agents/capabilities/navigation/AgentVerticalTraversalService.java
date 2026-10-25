package server.agents.capabilities.navigation;

import client.Character;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Rope;

import java.awt.Point;

/**
 * Generic policy for committing a complete vertical crossing rather than unrelated rope edges.
 */
public final class AgentVerticalTraversalService {
    private static final long POST_EXIT_REENTRY_BLOCK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.navigation.AgentVerticalTraversalService.POST_EXIT_REENTRY_BLOCK_MS");
    private static final int POST_EXIT_NUDGE_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.navigation.AgentVerticalTraversalService.POST_EXIT_NUDGE_PX");

    private AgentVerticalTraversalService() {
    }

    @FunctionalInterface
    public interface ExitEdgeFinder {
        AgentNavigationGraph.Edge findExitEdge(AgentNavigationGraph graph,
                                               Character bot,
                                               Point startPosition,
                                               int startRegionId,
                                               int targetRegionId,
                                               Point targetPosition);
    }

    public record TraversalDirective(AgentNavigationGraph.Edge edge,
                                     Point targetPosition,
                                     int targetRegionId,
                                     boolean holdGroundedExit) {
        public TraversalDirective {
            targetPosition = targetPosition == null ? null : new Point(targetPosition);
        }

        @Override
        public Point targetPosition() {
            return targetPosition == null ? null : new Point(targetPosition);
        }
    }

    /**
     * Starts a transaction only when the selected edge enters a rope and the route has a matching
     * climb exit. The one extra route lookup happens once per rope crossing, never per tick.
     */
    public static boolean beginIfRopeEntry(AgentNavigationGraph graph,
                                           AgentRuntimeEntry entry,
                                           Character bot,
                                           AgentNavigationGraph.Edge selectedEdge,
                                           int targetRegionId,
                                           Point targetPosition,
                                           ExitEdgeFinder exitEdgeFinder) {
        if (graph == null || entry == null || bot == null || selectedEdge == null
                || targetRegionId < 0
                || !AgentNavigationRopeEdgeService.isRopeEntryEdge(graph, selectedEdge)) {
            return false;
        }

        AgentVerticalTraversalState state = entry.verticalTraversalState();
        if (state.belongsTo(graph)) {
            return true;
        }
        state.clear();

        AgentNavigationGraph.Edge exitEdge = exitEdgeFinder.findExitEdge(
                graph, bot, selectedEdge.endPoint, selectedEdge.toRegionId, targetRegionId, targetPosition);
        if (!isMatchingExit(selectedEdge, exitEdge)) {
            return false;
        }

        state.begin(graph, selectedEdge, exitEdge, targetPosition, targetRegionId);
        return true;
    }

    /**
     * Returns the edge owned by the active transaction. A single grounded AI hand-off is held at
     * the destination so the same resolve call cannot immediately plan back onto the rope. A
     * rope-to-rope exit completes on the first AI tick spent on the caught destination rope.
     */
    public static TraversalDirective resolve(AgentNavigationGraph graph,
                                             AgentRuntimeEntry entry,
                                             Character bot,
                                             int currentRegionId,
                                             boolean runAiTick,
                                             AgentNavigationCommittedEdgeService.EdgeUsabilityChecker usabilityChecker) {
        if (entry == null) {
            return null;
        }
        AgentVerticalTraversalState state = entry.verticalTraversalState();
        if (!state.active()) {
            return null;
        }
        if (!state.belongsTo(graph)) {
            state.clear();
            return null;
        }

        AgentNavigationGraph.Edge entryEdge = state.entryEdge();
        AgentNavigationGraph.Edge exitEdge = state.exitEdge();
        if (AgentClimbStateRuntime.climbing(entry)) {
            state.observeRopeAttachment();
        }

        AgentNavigationGraph.Edge committedEdge;
        if (AgentClimbStateRuntime.climbing(entry)) {
            if (runAiTick && hangsOnExitDestination(graph, entry, exitEdge)) {
                // A rope-to-rope exit is done once the Agent hangs on the destination rope. Kept
                // as the committed edge, the finished jump was validated from its own destination,
                // rejected as "unexpected-source-region" and charged a reliability failure.
                state.completeOnCaughtRope(System.currentTimeMillis(), POST_EXIT_REENTRY_BLOCK_MS);
                return null;
            }
            committedEdge = exitEdge;
        } else if (AgentMovementStateRuntime.inAir(entry)) {
            committedEdge = state.ropeAttachmentObserved() ? exitEdge : entryEdge;
        } else if (!state.ropeAttachmentObserved()
                && (currentRegionId == entryEdge.fromRegionId || currentRegionId == entryEdge.toRegionId)) {
            committedEdge = entryEdge;
        } else if (state.ropeAttachmentObserved() && currentRegionId == exitEdge.fromRegionId) {
            committedEdge = exitEdge;
        } else if (state.ropeAttachmentObserved() && currentRegionId == exitEdge.toRegionId) {
            if (!state.groundedExitObserved()) {
                state.observeGroundedExit();
                return directive(state, null, true);
            }
            if (!runAiTick) {
                return directive(state, null, true);
            }
            state.complete(System.currentTimeMillis(), POST_EXIT_REENTRY_BLOCK_MS, POST_EXIT_NUDGE_PX);
            return null;
        } else {
            state.clear();
            return null;
        }

        if (!usabilityChecker.isUsable(graph, bot, committedEdge)) {
            state.clear();
            return null;
        }
        return directive(state, committedEdge, false);
    }

    public static int committedClimbDirection(AgentRuntimeEntry entry, int entryY) {
        if (entry == null || !entry.verticalTraversalState().active()) {
            return 0;
        }
        return Integer.compare(entry.verticalTraversalState().exitEdge().startPoint.y, entryY);
    }

    /** Returns the authored rope-exit anchor owned by the active vertical transaction. */
    public static Point committedClimbExitPosition(AgentRuntimeEntry entry) {
        if (entry == null || !entry.verticalTraversalState().active()) {
            return null;
        }
        return new Point(entry.verticalTraversalState().exitEdge().startPoint);
    }

    /**
     * The authored exit anchor, except while the Agent hangs on the rope a rope-to-rope exit jumps
     * to: there the anchor belongs to the rope just left, and climbing toward it can run off the
     * caught rope's end (Sleepy Dungeon 105040302: exit y=374 on ladder 6, below ladder 5's bottom
     * y=358). On any other rope, such as a ladder that the region lookup puts in the exit rope's
     * region (300020000: ladder x -201 in rope x -200's region), the Agent climbs to the anchor and
     * the exit runs from there. Holding on that ladder waited for the attempt timeout, which
     * charged a failure to the exit.
     */
    public static Point committedClimbExitPosition(AgentRuntimeEntry entry, Rope climbRope) {
        Point exit = committedClimbExitPosition(entry);
        if (exit == null || climbRope == null) {
            return null;
        }
        AgentVerticalTraversalState state = entry.verticalTraversalState();
        return isExitDestination(state.graph(), state.exitEdge(), climbRope) ? null : exit;
    }

    public static boolean blocksRecentInverseEntry(AgentNavigationGraph graph,
                                                   AgentRuntimeEntry entry,
                                                   AgentNavigationGraph.Edge edge,
                                                   long nowMs) {
        return entry != null
                && entry.verticalTraversalState().blocksRecentInverseEntry(graph, edge, nowMs);
    }

    /**
     * The step away from the rope just left, while its exit guard lasts. A target of the caller's own
     * on that ground at least as far from the rope takes precedence: walking there leaves the rope
     * anyway. A top step-off picks the nudge's side from the platform's extents, and on 920010700's
     * way up it walked the Agent away from its target for the whole guard.
     */
    public static Point recentExitNudgeTarget(AgentNavigationGraph graph,
                                              AgentRuntimeEntry entry,
                                              int currentRegionId,
                                              int targetRegionId,
                                              Point targetPosition,
                                              long nowMs) {
        if (entry == null
                || !entry.verticalTraversalState().hasRecentExitGuard(graph, currentRegionId, nowMs)) {
            return null;
        }
        AgentVerticalTraversalState state = entry.verticalTraversalState();
        Point nudge = state.recentExitNudgeTarget();
        AgentNavigationGraph.Region rope = graph.getRegion(state.recentExitRopeRegionId());
        if (nudge != null && rope != null && targetPosition != null && targetRegionId == currentRegionId
                && Math.abs(targetPosition.x - rope.minX) >= Math.abs(nudge.x - rope.minX)) {
            return null;
        }
        return nudge;
    }

    private static TraversalDirective directive(AgentVerticalTraversalState state,
                                                AgentNavigationGraph.Edge edge,
                                                boolean holdGroundedExit) {
        return new TraversalDirective(
                edge, state.targetPosition(), state.targetRegionId(), holdGroundedExit);
    }

    /**
     * Whether the Agent hangs on the rope a rope-to-rope exit jumps to. This compares the rope
     * itself, not the resolved region: the region lookup returns the first rope within grab range,
     * so on overlapping ladder pairs the source ladder can resolve to the destination (Tornado
     * Corridor 610030011) and the caught ladder to the source (300020000).
     */
    private static boolean hangsOnExitDestination(AgentNavigationGraph graph,
                                                  AgentRuntimeEntry entry,
                                                  AgentNavigationGraph.Edge exitEdge) {
        return isExitDestination(graph, exitEdge, AgentClimbStateRuntime.climbRope(entry));
    }

    private static boolean isExitDestination(AgentNavigationGraph graph,
                                             AgentNavigationGraph.Edge exitEdge,
                                             Rope rope) {
        AgentNavigationGraph.Region destination = graph.getRegion(exitEdge.toRegionId);
        return destination != null && destination.isRopeRegion
                && isRope(rope, destination.minX, destination.minY, destination.maxY);
    }

    /** Matches a rope on x, top and bottom, like AgentNavigationGraphService.findRopeFromRegion. */
    private static boolean isRope(Rope rope, int x, int topY, int bottomY) {
        return rope != null && rope.x() == x && rope.topY() == topY && rope.bottomY() == bottomY;
    }

    private static boolean isMatchingExit(AgentNavigationGraph.Edge entryEdge,
                                          AgentNavigationGraph.Edge exitEdge) {
        return exitEdge != null
                && exitEdge.type == AgentNavigationGraph.EdgeType.CLIMB
                && exitEdge.fromRegionId == entryEdge.toRegionId
                && exitEdge.toRegionId != entryEdge.fromRegionId;
    }
}
