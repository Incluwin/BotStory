package server.agents.capabilities.supplies;

/**
 * Per-Agent potion seam. The Agent layer stays owner-free: every read of the autopot HP and MP thresholds for an
 * Agent asks this guard, and the host (the living world) installs the rule that knows an Agent's own potion
 * percentages. With nothing installed, or no answer for an Agent, it drinks at the configured AUTOPOT thresholds
 * as before. A Dark Knight's Berserk window still applies on top of the HP value this answers.
 */
public final class AgentPotionThresholds {
    /** The installed rule. */
    public interface Rule {
        /** The HP and MP fractions this loaded Agent character drinks below, or null for the configured ones. */
        Thresholds thresholds(int agentCharacterId);
    }

    /** The fractions of maximum HP and MP (0 to 1) below which the Agent drinks a potion. */
    public record Thresholds(float hp, float mp) {
    }

    private static volatile Rule rule;

    private AgentPotionThresholds() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** The HP fraction this Agent drinks below: its host's, else {@code configured}. */
    public static float hp(int agentCharacterId, float configured) {
        Thresholds own = of(agentCharacterId);
        return own == null ? configured : own.hp();
    }

    /** The MP fraction this Agent drinks below: its host's, else {@code configured}. */
    public static float mp(int agentCharacterId, float configured) {
        Thresholds own = of(agentCharacterId);
        return own == null ? configured : own.mp();
    }

    private static Thresholds of(int agentCharacterId) {
        Rule current = rule;
        return current == null ? null : current.thresholds(agentCharacterId);
    }
}
