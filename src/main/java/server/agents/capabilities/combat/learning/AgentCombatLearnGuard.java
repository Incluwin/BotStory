package server.agents.capabilities.combat.learning;

import client.Character;

/**
 * Per-Agent seam for learning out of sight. The Agent layer stays owner-free: the combat learner asks this guard at each
 * decision, and the host (the living world) installs the rule that knows which Agents fight where no player can see
 * them (a benched companion grinding alone at its camp). Such an Agent explores in {@code LEARN} mode into its job's
 * candidate table ({@link AgentCombatCandidates}), which live play never reads; the moment the rule says otherwise it
 * plays as before. With nothing installed, every Agent plays as before.
 */
public final class AgentCombatLearnGuard {
    /** The installed rule. */
    public interface Rule {
        /** True while this loaded Agent character may explore: nobody but Agents can see it fight. */
        boolean learnUnseen(Character bot);

        /** How often it still explores once every choice has had its tries (the arena's epsilon-greedy). */
        double epsilon();
    }

    private static volatile Rule rule;

    private AgentCombatLearnGuard() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** The installed rule, or null. */
    static Rule rule() {
        return rule;
    }
}
