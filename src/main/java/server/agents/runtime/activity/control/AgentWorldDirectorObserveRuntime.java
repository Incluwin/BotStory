package server.agents.runtime.activity.control;

import client.Character;
import server.agents.integration.cosmic.CosmicAgentWorldContextFactory;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.world.AgentWorldShadowEvaluator;

/**
 * Cosmic capture adapter invoked by the central Agent scheduler. After a sample in ASSISTED or AUTONOMOUS mode the
 * Director's policy may act on it ({@link AgentWorldDirectorPolicyRuntime}); every CHECKPOINT_SAMPLES samples the count
 * is written to the Agent's Observe evidence file, so the evidence outlives the session.
 */
public final class AgentWorldDirectorObserveRuntime {
    private static final AgentWorldShadowEvaluator EVALUATOR =
            AgentWorldShadowEvaluator.baseline();
    private static final long CHECKPOINT_SAMPLES = config.AgentTuning.longValue(
            "server.agents.runtime.activity.control.AgentWorldDirectorObserveRuntime.CHECKPOINT_SAMPLES");

    private AgentWorldDirectorObserveRuntime() { }

    public static void tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (entry == null || agent == null) return;
        AgentWorldDirectorObserveState state = entry.capabilityStates()
                .find(AgentWorldDirectorObserveState.STATE_KEY).orElse(null);
        if (state == null || !state.due(nowMs)) return;
        try {
            AgentWorldDirectorObserveTickService.tick(state, EVALUATOR,
                    CosmicAgentWorldContextFactory.capture(entry, agent, nowMs), nowMs);
        } catch (RuntimeException failure) {
            state.failed("Observe sampling failed: " + failure.getMessage(), nowMs);
            return;
        }
        checkpoint(Evidence.STORE, agent.getId(), state, nowMs);
        AgentWorldDirectorPolicyRuntime.afterSample(entry, agent, state, nowMs);
    }

    /** Writes the live sample count to the evidence file once CHECKPOINT_SAMPLES more were taken. */
    static void checkpoint(AgentWorldDirectorObserveEvidenceStore store, int agentId,
                           AgentWorldDirectorObserveState state, long nowMs) {
        long samples = state.checkpointDue(CHECKPOINT_SAMPLES);
        if (samples < 0L) return;
        try {
            store.save(agentId, samples);
        } catch (RuntimeException failure) {
            state.failed("Observe checkpoint failed: " + failure.getMessage(), nowMs);
        }
    }

    private static final class Evidence {
        static final AgentWorldDirectorObserveEvidenceStore STORE = AgentWorldDirectorObserveEvidenceStore.runtimeDefault();
    }
}
