package server.agents.capabilities.combat;

import server.agents.runtime.state.AgentCapabilityStateKey;

/** Acquisition timing state. Target commitment remains owned by combat. */
public final class AgentCombatBehaviorState {
    public static final AgentCapabilityStateKey<AgentCombatBehaviorState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.behavior", AgentCombatBehaviorState.class,
                    AgentCombatBehaviorState::new);

    private long stimulus;
    private long readyAtMs;
    private boolean responseDeferred;
    private boolean candidateOpportunity;
    private long waitStartedAtMs;
    private boolean reactionTaken;

    public synchronized boolean ready(long newStimulus, long nowMs, int delayMs) {
        if (newStimulus != stimulus) {
            if (stimulus == 0L) { // a new wait for a target: mobs changing during it do not restart the reaction
                waitStartedAtMs = nowMs;
                reactionTaken = false;
            }
            stimulus = newStimulus;
            readyAtMs = nowMs + Math.max(0, delayMs);
        }
        responseDeferred = nowMs < readyAtMs;
        return !responseDeferred;
    }

    /** Once a wait, when it first ends ready: how long since the wait began (the tell audit); else -1. */
    public synchronized long takeReactionMs(long nowMs) {
        if (responseDeferred || reactionTaken || stimulus == 0L) {
            return -1L;
        }
        reactionTaken = true;
        return nowMs - waitStartedAtMs;
    }

    public synchronized void clearStimulus() {
        stimulus = 0L;
        readyAtMs = 0L;
        responseDeferred = false;
        candidateOpportunity = false;
    }

    public synchronized void markCandidateOpportunity() { candidateOpportunity = true; }
    public synchronized boolean responseDeferred() { return responseDeferred; }
    public synchronized boolean candidateOpportunity() { return candidateOpportunity; }
}
