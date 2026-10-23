package server.agents.capabilities.combat;

import server.agents.runtime.state.AgentCapabilityStateKey;

/**
 * Allocation-light, ephemeral evidence describing the last combat target decision.
 * It observes policy execution; it never participates in target selection.
 */
public final class AgentCombatDecisionTraceState {
    public static final AgentCapabilityStateKey<AgentCombatDecisionTraceState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.decision-trace",
                    AgentCombatDecisionTraceState.class, AgentCombatDecisionTraceState::new);

    private Mode mode = Mode.NONE;
    private Outcome outcome = Outcome.NONE;
    private long recordedAtMs;
    private int baseCandidates;
    private int objectiveCandidates;
    private int policyCandidates;
    private int claimCandidates;
    private int scoredCandidates;
    private boolean mapWidePreferredEscalation;
    private boolean rankedVariationConsumed;
    private int selectedObjectId;
    private int selectedMobId;

    public synchronized void record(Mode mode,
                                    Outcome outcome,
                                    long recordedAtMs,
                                    int baseCandidates,
                                    int objectiveCandidates,
                                    int policyCandidates,
                                    int claimCandidates,
                                    int scoredCandidates,
                                    boolean mapWidePreferredEscalation,
                                    boolean rankedVariationConsumed,
                                    int selectedObjectId,
                                    int selectedMobId) {
        this.mode = mode == null ? Mode.NONE : mode;
        this.outcome = outcome == null ? Outcome.NONE : outcome;
        this.recordedAtMs = recordedAtMs;
        this.baseCandidates = Math.max(0, baseCandidates);
        this.objectiveCandidates = Math.max(0, objectiveCandidates);
        this.policyCandidates = Math.max(0, policyCandidates);
        this.claimCandidates = Math.max(0, claimCandidates);
        this.scoredCandidates = Math.max(0, scoredCandidates);
        this.mapWidePreferredEscalation = mapWidePreferredEscalation;
        this.rankedVariationConsumed = rankedVariationConsumed;
        this.selectedObjectId = Math.max(0, selectedObjectId);
        this.selectedMobId = Math.max(0, selectedMobId);
    }

    /** How the last grind tick left the engagement step (debug only: !cwkpqtest why). */
    private String engagementExit = "-";
    private long engagementAtMs;
    private int planSkillId = -1;
    private AgentAttackRoute planRoute;
    private boolean planInRange;
    private AgentAttackTransactionResult lastAttack;
    private long lastAttackAtMs;

    public synchronized void noteEngagement(String exit, AgentAttackPlan plan, boolean inRange, long nowMs) {
        engagementExit = exit;
        engagementAtMs = nowMs;
        planSkillId = plan == null ? -1 : plan.skillId;
        planRoute = plan == null ? null : plan.route;
        planInRange = inRange;
    }

    public synchronized void noteAttack(AgentAttackTransactionResult result, long nowMs) {
        lastAttack = result;
        lastAttackAtMs = nowMs;
    }

    /** "engage <exit> N ms ago, plan skill S ROUTE in range|out of range, last attack STATUS/REASON ...". */
    public synchronized String engagementText(long nowMs) {
        String text = "engage " + engagementExit + " " + (nowMs - engagementAtMs) + " ms ago, plan "
                + (planSkillId < 0 ? "none" : "skill " + planSkillId + " " + planRoute
                + (planInRange ? " in range" : " out of range"));
        if (lastAttack != null) {
            text += ", last attack " + lastAttack.status() + "/" + lastAttack.reason() + " skill " + lastAttack.skillId()
                    + " hits " + lastAttack.hitLines() + " misses " + lastAttack.missLines() + " "
                    + (nowMs - lastAttackAtMs) + " ms ago";
        }
        return text;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mode, outcome, recordedAtMs, baseCandidates,
                objectiveCandidates, policyCandidates, claimCandidates,
                scoredCandidates, mapWidePreferredEscalation,
                rankedVariationConsumed, selectedObjectId, selectedMobId);
    }

    public enum Mode {
        NONE,
        GRIND,
        PATROL,
        FOLLOW,
        ROUTE_BLOCKER
    }

    public enum Outcome {
        NONE,
        SELECTED,
        PRE_EXIT_LOOT,
        NO_CANDIDATES,
        OBJECTIVE_FILTERED,
        POLICY_FILTERED,
        CLAIMS_FILTERED,
        RESPONSE_DEFERRED,
        GRAPH_UNAVAILABLE,
        UNREACHABLE
    }

    public record Snapshot(Mode mode,
                           Outcome outcome,
                           long recordedAtMs,
                           int baseCandidates,
                           int objectiveCandidates,
                           int policyCandidates,
                           int claimCandidates,
                           int scoredCandidates,
                           boolean mapWidePreferredEscalation,
                           boolean rankedVariationConsumed,
                           int selectedObjectId,
                           int selectedMobId) {
    }
}
