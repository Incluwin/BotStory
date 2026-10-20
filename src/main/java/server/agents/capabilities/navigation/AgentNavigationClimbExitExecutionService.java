package server.agents.capabilities.navigation;

import client.Character;
import server.agents.capabilities.movement.AgentClimbMovementPolicy;
import server.agents.capabilities.movement.AgentClimbMovementService;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Rope;

import java.awt.Point;

/**
 * Agent-owned climb-exit edge execution for navigation. Its jumps fly the arc the graph planned
 * (AgentClimbMovementService.jumpOffRopeOnPlannedArc).
 */
public final class AgentNavigationClimbExitExecutionService {
    private AgentNavigationClimbExitExecutionService() {
    }

    public static boolean tryExecuteClimbExit(AgentNavigationGraph graph,
                                              AgentRuntimeEntry entry,
                                              Character agent,
                                              Point agentPos,
                                              AgentNavigationGraph.Edge edge) {
        if (!AgentNavigationRopeEdgeService.canExecuteClimbExitFromCurrentPosition(
                graph, agentPos, edge, region -> AgentNavigationGraphService.findRopeFromRegion(agent.getMap(), region))) {
            return false;
        }
        AgentNavigationGraph.Region toRegion = graph.getRegion(edge.toRegionId);

        if (toRegion != null && toRegion.isRopeRegion) {
            Rope targetRope = AgentNavigationGraphService.findRopeFromRegion(agent.getMap(), toRegion);
            if (targetRope == null || AgentClimbMovementPolicy.sameRope(AgentClimbStateRuntime.climbRope(entry), targetRope)) {
                return false;
            }
            AgentClimbMovementService.jumpToRopeOnPlannedArc(entry, agent, edge.launchStepX);
            return true;
        }

        if (edge.launchStepX == 0) {
            AgentClimbMovementService.stepOffRopeOntoGround(entry, agent, edge.endPoint);
            return true;
        }

        Rope sourceRope = AgentNavigationGraphService.findRopeFromRegion(agent.getMap(), graph.getRegion(edge.fromRegionId));
        if (AgentNavigationRopeEdgeService.isTopRopeJumpExitReady(sourceRope, agentPos, edge)
                && agentPos.y != edge.startPoint.y) {
            AgentNavigationClimbExecutionService.startClimbing(entry, agent, sourceRope, edge.startPoint.y);
        }
        AgentClimbMovementService.jumpOffRopeOnPlannedArc(entry, agent, edge.launchStepX);
        return true;
    }
}
