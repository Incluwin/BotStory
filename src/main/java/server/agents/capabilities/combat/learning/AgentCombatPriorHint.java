package server.agents.capabilities.combat.learning;

/**
 * Per-Agent prior seam for the combat learner. The Agent layer stays owner-free: when the learner seeds a skill's prior
 * (outside LIVE, as it does on a bench with nobody watching), it asks this guard how much higher that prior starts, and
 * the host (the living world) installs the rule that knows who the Agent learns from: a companion of its owner's job
 * starts a little higher on the skills that land its owner's damage (docs/BOT_CONCEPT_PLAN.md F2.4). A seeded prior is
 * only where a value starts; the measured value moves away from it with every visit, so what is measured still wins.
 * With nothing installed every prior is the scorer's own.
 */
public final class AgentCombatPriorHint {
    /** The installed rule. */
    public interface Rule {
        /** The share {@code skillId}'s prior starts higher by for this loaded Agent character (0: the scorer's own). */
        double bump(int agentCharacterId, int skillId);
    }

    private static volatile Rule rule;

    private AgentCombatPriorHint() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** {@code prior} as this Agent starts it for {@code skillId}: the scorer's own, raised by its host's share. */
    public static double prior(int agentCharacterId, int skillId, double prior) {
        Rule current = rule;
        double bump = current == null ? 0.0d : Math.max(0.0d, current.bump(agentCharacterId, skillId));
        return bump <= 0.0d || prior <= 0.0d ? prior : prior * (1.0d + bump);
    }
}
