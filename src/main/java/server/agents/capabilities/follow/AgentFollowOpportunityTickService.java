package server.agents.capabilities.follow;

import client.Character;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;

public final class AgentFollowOpportunityTickService {
    private AgentFollowOpportunityTickService() {
    }

    public record Result(boolean consumedTick, Point targetPos) {
    }

    public record Hooks(LocalOpportunityAttack localOpportunityAttack,
                        int followDistance) {
    }

    @FunctionalInterface
    public interface LocalOpportunityAttack {
        Result attack(AgentRuntimeEntry entry,
                      Character agent,
                      Point agentPosition,
                      Point currentTargetPosition,
                      Point followTargetPosition);
    }

    /** Horizontal reach around the follow anchor inside which a following Agent may fight. */
    public static boolean withinFollowLeash(Point position, Point anchorPosition, int followDistance) {
        return position != null && anchorPosition != null
                && Math.abs(position.x - anchorPosition.x) <= followDistance * 5;
    }

    public static Result tickFollowOpportunity(AgentRuntimeEntry entry,
                                               Character agent,
                                               Point agentPosition,
                                               Point currentTargetPosition,
                                               Point followTargetPosition,
                                               Character followAnchor,
                                               boolean runAiTick,
                                               Hooks hooks) {
        if (!AgentModeStateRuntime.following(entry)
                || !runAiTick
                || AgentMovementStateRuntime.climbing(entry)
                || followAnchor == null
                || agent.getMapId() != followAnchor.getMapId()
                || !withinFollowLeash(agentPosition, followAnchor.getPosition(), hooks.followDistance())) {
            return new Result(false, currentTargetPosition);
        }

        return hooks.localOpportunityAttack().attack(
                entry,
                agent,
                agentPosition,
                currentTargetPosition,
                followTargetPosition);
    }
}
