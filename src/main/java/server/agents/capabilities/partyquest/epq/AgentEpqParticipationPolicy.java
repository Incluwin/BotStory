package server.agents.capabilities.partyquest.epq;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/** Deterministic EPQ-only work distribution for flowers and Stage 5 boxes. */
final class AgentEpqParticipationPolicy {
    private AgentEpqParticipationPolicy() { }

    /**
     * The stage-4 flower an Agent goes for (EPQ base fix 8): the nearest live flower assigned to it, else the nearest
     * live flower of any owner (its own are all caught: it helps with the last ones), else null. Several Agents on one
     * flower are harmless: a session member's hits never kill a flower, and a second capture spends no purifier.
     */
    static <T> T captureTarget(List<T> live, ToIntFunction<T> owner, int self, ToDoubleFunction<T> distanceSq) {
        if (live == null || live.isEmpty()) return null;
        Comparator<T> nearest = Comparator.comparingDouble(distanceSq);
        return live.stream().filter(flower -> owner.applyAsInt(flower) == self).min(nearest)
                .orElseGet(() -> live.stream().min(nearest).orElse(null));
    }

    static int assignedAgent(long seed, int objectId, List<Integer> sortedAgentIds) {
        if (sortedAgentIds == null || sortedAgentIds.isEmpty()) return 0;
        int index = Math.floorMod(objectId + Long.hashCode(seed), sortedAgentIds.size());
        return sortedAgentIds.get(index);
    }
}
