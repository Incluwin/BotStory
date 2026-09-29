package server.agents.capabilities.dialogue;

/**
 * Support toggle seam. The Agent layer stays owner-free: when chat turns one of an Agent's support toggles on or off
 * (AgentChatToggleFlow: its skill buffs, its party heals, its buff potions), the host (the living world) installed
 * here hears it, to keep the choice as the Agent's own setting. With nothing installed nothing else happens, as
 * before.
 */
public final class AgentChatToggleHook {
    /** A support toggle chat turns on or off. */
    public enum Toggle {
        SKILL_BUFFS,
        SUPPORT_HEALS,
        BUFF_CONSUMABLES
    }

    /** The installed rule. */
    public interface Rule {
        /** Chat just turned {@code toggle} on ({@code enabled}) or off for this loaded Agent character. */
        void toggled(int agentCharacterId, Toggle toggle, boolean enabled);
    }

    private static volatile Rule rule;

    private AgentChatToggleHook() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** Tells the installed host; nothing with none installed. */
    public static void toggled(int agentCharacterId, Toggle toggle, boolean enabled) {
        Rule current = rule;
        if (current != null) {
            current.toggled(agentCharacterId, toggle, enabled);
        }
    }
}
