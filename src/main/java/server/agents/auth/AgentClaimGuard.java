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
        /** True when the actor may whisper-command, dismiss or otherwise order this loaded Agent character. */
        boolean mayControl(Character actor, int agentCharacterId);

        /**
         * True when the actor may spawn, recruit or pull this Agent character into their party. A host may refuse
         * here what it allows in {@link #mayControl}, so no administrator loads a character the host claims.
         */
        default boolean mayLoad(Character actor, int agentCharacterId) {
            return mayControl(actor, agentCharacterId);
        }

        /** True when the host claims this character, so chat may never re-point its runtime leader. */
        boolean isClaimed(int agentCharacterId);
    }

    /** A leader-level chat verb whose Agent route would unload or unclaim the Agent it names. */
    public enum Verb {
        /** "dismiss", "disown" or "release" &lt;name&gt;: the route unregisters the Agent and leaves it online. */
        DISMISS,
        /** A confirmed "logout" (targeted or not): the route disconnects the Agent. */
        LOGOUT
    }

    /** The host's own handling of a {@link Verb} for an Agent it claims. */
    @FunctionalInterface
    public interface VerbRoute {
        /** True when the host carried the verb out itself, so the Agent route must do nothing. */
        boolean take(Character actor, int agentCharacterId, Verb verb);
    }

    private static volatile Rule rule;
    private static volatile VerbRoute verbs;

    private AgentClaimGuard() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    public static void installVerbs(VerbRoute installed) {
        verbs = installed;
    }

    /** True when the installed host took {@code verb} for this Agent; with nothing installed the Agent route runs. */
    public static boolean takeVerb(Character actor, int agentCharacterId, Verb verb) {
        VerbRoute current = verbs;
        return current != null && current.take(actor, agentCharacterId, verb);
    }

    public static boolean mayControl(Character actor, int agentCharacterId) {
        Rule current = rule;
        return current == null || current.mayControl(actor, agentCharacterId);
    }

    public static boolean mayLoad(Character actor, int agentCharacterId) {
        Rule current = rule;
        return current == null || current.mayLoad(actor, agentCharacterId);
    }

    public static boolean isClaimed(int agentCharacterId) {
        Rule current = rule;
        return current != null && current.isClaimed(agentCharacterId);
    }
}
