package server.agents.capabilities.supplies;

/**
 * Per-Agent supply-ask seam. The Agent layer stays owner-free: before an Agent says it is short of supplies or asks its
 * party for them, it asks this guard, and the host (the living world) installs the rule that knows who keeps quiet.
 * With nothing installed every Agent asks as before.
 */
public final class AgentSupplyAskGuard {
    /** The installed rule. */
    public interface Rule {
        /** True while this Agent character neither announces a supply shortage nor asks its party for supplies. */
        boolean keepsQuiet(int agentCharacterId);
    }

    private static volatile Rule rule;

    private AgentSupplyAskGuard() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    public static boolean keepsQuiet(int agentCharacterId) {
        Rule current = rule;
        return current != null && current.keepsQuiet(agentCharacterId);
    }
}
