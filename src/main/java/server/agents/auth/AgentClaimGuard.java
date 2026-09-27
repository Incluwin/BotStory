package server.agents.auth;

import client.Character;

/**
 * Per-Agent control seam. The Agent layer stays owner-free: routes that act on one named Agent ask
 * this guard, and the host (the living world) installs the rule that knows who owns which
 * character. With nothing installed every route behaves as before.
 */
public final class AgentClaimGuard {
    /** The installed ownership rule. */
    public interface Rule {
        /** True when the actor may spawn, adopt, whisper-command or dismiss this Agent character. */
        boolean mayControl(Character actor, int agentCharacterId);

        /** True when the host claims this character, so chat may never re-point its runtime leader. */
        boolean isClaimed(int agentCharacterId);
    }

    private static volatile Rule rule;

    private AgentClaimGuard() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    public static boolean mayControl(Character actor, int agentCharacterId) {
        Rule current = rule;
        return current == null || current.mayControl(actor, agentCharacterId);
    }

    public static boolean isClaimed(int agentCharacterId) {
        Rule current = rule;
        return current != null && current.isClaimed(agentCharacterId);
    }
}
