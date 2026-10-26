package server.agents.runtime.activity.control;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.world.AgentWorldDirectorSession;
import server.agents.runtime.activity.world.AgentWorldDirectorSessionStore;

/** Loads persisted authority once at registration and synchronizes explicit mode changes. */
public final class AgentWorldDirectorModeRestoreRuntime {
    private final AgentWorldDirectorSessionStore sessions;
    private final long observeIntervalMs;
    private final AgentWorldDirectorObserveEvidenceStore evidence;

    public AgentWorldDirectorModeRestoreRuntime(
            AgentWorldDirectorSessionStore sessions, long observeIntervalMs) {
        this(sessions, observeIntervalMs, AgentWorldDirectorObserveEvidenceStore.runtimeDefault());
    }

    public AgentWorldDirectorModeRestoreRuntime(
            AgentWorldDirectorSessionStore sessions, long observeIntervalMs,
            AgentWorldDirectorObserveEvidenceStore evidence) {
        if (sessions == null || observeIntervalMs <= 0L || evidence == null) {
            throw new IllegalArgumentException("Director session store and observe interval are required");
        }
        this.sessions = sessions;
        this.observeIntervalMs = observeIntervalMs;
        this.evidence = evidence;
    }

    public AgentWorldDirectorSession restore(AgentRuntimeEntry entry, int agentId, long nowMs) {
        if (agentId <= 0) {
            apply(entry, null, nowMs);
            return null;
        }
        AgentWorldDirectorSession session = sessions.load(agentId).orElse(null);
        apply(entry, session, nowMs);
        return session;
    }

    public void apply(AgentRuntimeEntry entry, AgentWorldDirectorSession session, long nowMs) {
        if (entry == null || entry.capabilityStates() == null) return;
        AgentWorldDirectorRuntimeState runtime = entry.capabilityStates()
                .require(AgentWorldDirectorRuntimeState.STATE_KEY);
        AgentWorldDirectorObserveState observe = entry.capabilityStates()
                .require(AgentWorldDirectorObserveState.STATE_KEY);
        if (session == null) {
            runtime.restore(null, "no persisted Director session", nowMs);
            observe.disable();
            return;
        }
        runtime.restore(session.mode(), session.lastReason(), nowMs);
        // Observe evidence carries over a relogin and a restart (the autonomous gate's MIN_OBSERVE_SAMPLES)
        observe.seed(Math.max(session.observationCount(), evidence.load(session.agentId())));
        if (AgentWorldDirectorObserveState.samples(session.mode())) {
            observe.configure(session.mode(), observeIntervalMs);
        } else {
            observe.disable();
        }
    }
}
