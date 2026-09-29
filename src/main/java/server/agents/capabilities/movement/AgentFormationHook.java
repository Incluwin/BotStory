package server.agents.capabilities.movement;

import client.Character;

/**
 * Leader formation seam. The Agent layer stays owner-free: when a leader sets their Agents' formation in chat (the
 * formation command: a formation, or its snap range), the host (the living world) installed here hears the state now
 * kept for that leader, to keep it past a relog or a restart. With nothing installed nothing else happens, as before.
 */
public final class AgentFormationHook {
    /** The installed rule. */
    public interface Rule {
        /** {@code leader} just set their Agents' formation in chat: {@code formation} is kept for them now. */
        void formationSet(Character leader, AgentFormationService.FormationState formation);
    }

    private static volatile Rule rule;

    private AgentFormationHook() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** Tells the installed host; nothing with none installed. */
    public static void formationSet(Character leader, AgentFormationService.FormationState formation) {
        Rule current = rule;
        if (current != null) {
            current.formationSet(leader, formation);
        }
    }
}
