package server.agents.capabilities.navigation;

import server.agents.runtime.AgentRuntimeEntry;

/** Navigation-owned reset seam used by movement without exposing internal state holders. */
public final class AgentNavigationStateResetService {
    private AgentNavigationStateResetService() {
    }

    public static void clearTarget(AgentRuntimeEntry entry) {
        AgentNavigationDebugStateRuntime.clearNavTarget(entry);
    }

    public static void clearGraphWarmupFallback(AgentRuntimeEntry entry) {
        AgentNavigationDebugStateRuntime.clearGraphWarmupFallback(entry);
    }

    /** A teleport ends the traversal attempt uncharged (AgentNavigationEdgeReliabilityState.abandonAttempt). */
    public static void abandonTraversalAttempt(AgentRuntimeEntry entry) {
        AgentNavigationEdgeReliabilityRuntime.abandonAttempt(entry);
    }

    public static void clearStep(AgentRuntimeEntry entry) {
        AgentVerticalTraversalStateRuntime.clear(entry);
        AgentNavigationDebugStateRuntime.clearActiveNavigationEdge(entry);
        AgentNavigationDebugStateRuntime.clearNavJumpLaunch(entry);
    }
}
