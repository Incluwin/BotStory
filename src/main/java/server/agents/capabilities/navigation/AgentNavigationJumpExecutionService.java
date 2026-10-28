package server.agents.capabilities.navigation;

import server.agents.capabilities.behavior.AgentJumpNoise;
import client.Character;
import server.agents.capabilities.movement.AgentJumpActionService;
import server.agents.capabilities.movement.AgentMovementKinematicsService;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationDebugStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Rope;

import java.awt.Point;

/**
 * Agent-owned jump edge execution for navigation.
 */
public final class AgentNavigationJumpExecutionService {
    private AgentNavigationJumpExecutionService() {
    }

    public static boolean tryExecuteJump(AgentNavigationGraph graph,
                                         AgentRuntimeEntry entry,
                                         Character agent,
                                         AgentNavigationGraph.Edge edge) {
        if (AgentMovementStateRuntime.inAir(entry) || AgentClimbStateRuntime.climbing(entry)) {
            return false;
        }
        Point agentPos = agent.getPosition();
        if (!canExecuteSelectedJumpFromCurrentPosition(graph, entry, agent, agentPos, edge)) {
            if (edge.startPoint.y > agentPos.y) {
                AgentNavigationGraph.Region fromRegion = graph.getRegion(edge.fromRegionId);
                if (fromRegion != null && fromRegion.isRopeRegion) {
                    Rope rope = AgentNavigationGraphService.findRopeFromRegion(agent.getMap(), fromRegion);
                    if (rope != null && AgentNavigationRopeEdgeService.canGrabRopeAtCurrentPosition(agentPos, rope)) {
                        AgentNavigationClimbExecutionService.startClimbing(entry, agent, rope, agentPos.y);
                        return true;
                    }
                }
            }
            AgentNavigationDebugStateRuntime.setLastEdgeBlockReason(entry, "jump-pos");
            return false;
        }

        AgentNavigationDebugStateRuntime.clearLastEdgeBlockReason(entry);
        AgentNavigationEdgeExecutionStateService.setEdgeExecutionTarget(entry, edge);
        AgentJumpActionService.initiateJump(entry, agent, edge.launchStepX);
        AgentJumpNoise.launched(entry, edge, agentPos);
        if (entry.navigationDebugState().tracingAir()) {
            entry.navigationDebugState().noteAirSource("jump edge " + edge.fromRegionId + "->" + edge.toRegionId
                    + " from (" + edge.startPoint.x + "," + edge.startPoint.y + ") to (" + edge.endPoint.x + ","
                    + edge.endPoint.y + ") step " + edge.launchStepX + " at (" + agentPos.x + "," + agentPos.y
                    + ") toward goal " + entry.navigationDebugState().goalText() + ", planned "
                    + AgentNavigationDebugStateRuntime.plannedNavigationTargetPosition(entry) + " region "
                    + AgentNavigationDebugStateRuntime.navTargetRegionId(entry));
        }
        return true;
    }

    private static boolean canExecuteSelectedJumpFromCurrentPosition(AgentNavigationGraph graph,
                                                                     AgentRuntimeEntry entry,
                                                                     Character agent,
                                                                     Point agentPos,
                                                                     AgentNavigationGraph.Edge edge) {
        Boolean noisy = AgentJumpNoise.readyAtDraw(entry, edge, agentPos);
        if (noisy != null) {
            return noisy;
        }
        if (!AgentNavigationEdgeReadinessService.canExecuteJumpFromCurrentPosition(graph, agentPos, edge)) {
            return false;
        }
        int launchX = AgentNavigationWaypointService.selectJumpLaunchX(entry, graph, edge);
        int tolerance = Math.max(1, AgentMovementKinematicsService.walkStep(agent.getMap(),
                entry != null ? AgentMovementStateRuntime.movementProfile(entry) : null));
        return AgentNavigationEdgeReadinessService.canExecuteSelectedJumpFromCurrentPosition(
                graph, agentPos, edge, launchX, tolerance);
    }
}
