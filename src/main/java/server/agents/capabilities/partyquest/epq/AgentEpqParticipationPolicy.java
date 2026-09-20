package server.agents.capabilities.partyquest.epq;

import java.util.List;

/** Deterministic EPQ-only work distribution for flowers and Stage 5 boxes. */
final class AgentEpqParticipationPolicy {
    private AgentEpqParticipationPolicy() { }

    static int assignedAgent(long seed, int objectId, List<Integer> sortedAgentIds) {
        if (sortedAgentIds == null || sortedAgentIds.isEmpty()) return 0;
        int index = Math.floorMod(objectId + Long.hashCode(seed), sortedAgentIds.size());
        return sortedAgentIds.get(index);
    }
}
