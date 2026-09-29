package server.agents.capabilities.combat.learning;

import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.List;

/**
 * Per-Agent side of the combat learner: which mode it runs in, the table it learns into, the
 * situation of its latest plan, and the one decision whose outcome is still open (the action
 * last taken; it closes when the next action is taken).
 */
public final class AgentCombatLearningState {
    public static final AgentCapabilityStateKey<AgentCombatLearningState> STATE_KEY =
            new AgentCapabilityStateKey<>("combat.learning",
                    AgentCombatLearningState.class, AgentCombatLearningState::new);

    /** A decision taken and not yet closed. */
    record Open(String context, int action, long atMs, double reward) {
    }

    /** The situation, the choices worth trying, and the best estimated damage rate of the latest plan. */
    record Plan(String context, List<Integer> actions, int reach, double bestDps, long atMs) {
    }

    private AgentCombatLearner.Mode mode = AgentCombatLearner.Mode.LIVE;
    private AgentCombatPolicyTable table;
    private double epsilon;
    private Plan plan;
    private String specialContext;
    private long specialAtMs;
    private Open open;
    private long decisions;
    /** The arena put it in its mode ({@link #begin}): nothing else changes the mode until it ends. */
    private boolean held;
    /** It learns out of sight ({@link #learnUnseen}) until {@link #seen}. */
    private boolean unseen;

    synchronized void begin(AgentCombatLearner.Mode mode, AgentCombatPolicyTable table, double epsilon) {
        this.mode = mode;
        this.table = table;
        this.epsilon = epsilon;
        this.open = null;
        this.plan = null;
        this.decisions = 0;
        this.held = true;
        this.unseen = false;
    }

    synchronized void end() {
        mode = AgentCombatLearner.Mode.LIVE;
        table = null;
        open = null;
        plan = null;
        held = false;
        unseen = false;
    }

    synchronized boolean held() {
        return held;
    }

    /** Out of sight: LEARN into {@code candidate}; a decision still open for another table is dropped, not learned from. */
    synchronized void learnUnseen(AgentCombatPolicyTable candidate, double epsilon) {
        if (unseen && table == candidate) {
            this.epsilon = epsilon;
            return;
        }
        begin(AgentCombatLearner.Mode.LEARN, candidate, epsilon);
        held = false;
        unseen = true;
    }

    /** Seen again: back to the live mode at once, the open decision dropped. */
    synchronized void seen() {
        if (unseen) {
            end();
        }
    }

    synchronized AgentCombatLearner.Mode mode() {
        return mode;
    }

    synchronized AgentCombatPolicyTable table() {
        return table;
    }

    synchronized double epsilon() {
        return epsilon;
    }

    synchronized void plan(String context, List<Integer> actions, int reach, double bestDps, long atMs) {
        plan = new Plan(context, List.copyOf(actions), reach, bestDps, atMs);
    }

    synchronized Plan plan() {
        return plan;
    }

    synchronized void special(String context, long atMs) {
        specialContext = context;
        specialAtMs = atMs;
    }

    synchronized String specialContext(long now, long freshMs) {
        return specialContext != null && now - specialAtMs <= freshMs ? specialContext : null;
    }

    synchronized Open open() {
        return open;
    }

    synchronized void open(Open decision) {
        open = decision;
        decisions++;
    }

    /** Damage that landed while the open decision was running without being its own cast (a summon's hits). */
    synchronized void addToOpen(double damage) {
        if (open != null) {
            open = new Open(open.context(), open.action(), open.atMs(), open.reward() + damage);
        }
    }

    synchronized void clearOpen() {
        open = null;
    }

    public synchronized long decisions() {
        return decisions;
    }
}
