package server.agents.capabilities.supplies;

/**
 * Per-Agent wallet-reserve seam. The Agent layer stays owner-free: the procurement's journey reserve asks this guard,
 * and the host (the living world) installs the rule that knows which Agents never pay their own way (a companion out
 * with its owner is carried along: its recall and its follow cost it nothing), so it may spend its whole purse on
 * supplies. With nothing installed, or no exemption for an Agent, the configured reserve applies as before.
 */
public final class AgentWalletReserve {
    /** The installed rule. */
    public interface Rule {
        /** True when this loaded Agent character keeps no journey reserve. */
        boolean exempt(int agentCharacterId);
    }

    private static volatile Rule rule;

    private AgentWalletReserve() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** True when the installed rule exempts this Agent from the journey reserve. */
    public static boolean exempt(int agentCharacterId) {
        Rule current = rule;
        return current != null && current.exempt(agentCharacterId);
    }
}
