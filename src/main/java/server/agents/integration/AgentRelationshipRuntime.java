package server.agents.integration;

import client.Character;
import server.agents.commands.AgentReplyRoute;
import server.agents.runtime.AgentRuntimeEntry;

/** Boundary access to optional Agent relationships. */
public final class AgentRelationshipRuntime {
    private AgentRelationshipRuntime() {
    }

    public static Character followTarget(AgentRuntimeEntry entry) {
        return entry == null ? null : entry.relationshipState().followTarget();
    }

    public static void setFollowTarget(AgentRuntimeEntry entry, Character target) {
        if (entry != null) {
            entry.relationshipState().setFollowTarget(target);
        }
    }

    public static Character interactionTarget(AgentRuntimeEntry entry) {
        return entry == null ? null : entry.relationshipState().interactionTarget();
    }

    /**
     * Whom "me" means in the line the Agent is handling: the one who whispered it (AgentReplyRoute.current, which an
     * answer scheduled for later carries), else its interaction target. The whisper bridge gives an administrator's
     * companion back to its owner once the line is handled, but "fame me", "i need pots" and "give me mesos" act for
     * the administrator 500-900 ms later (third deploy review 2026-10-10). What the owner decides (a job prompt, a gear
     * offer) keeps {@link #interactionTarget}.
     */
    public static Character requester(AgentRuntimeEntry entry) {
        AgentReplyRoute route = AgentReplyRoute.current(entry);
        return route != null && route.whisperTo() != null ? route.whisperTo() : interactionTarget(entry);
    }

    public static void setInteractionTarget(AgentRuntimeEntry entry, Character target) {
        if (entry != null) {
            entry.relationshipState().setInteractionTarget(target);
        }
    }

    public static long cohortId(AgentRuntimeEntry entry) {
        return entry == null ? 0L : entry.relationshipState().cohortId();
    }

    public static void setCohortId(AgentRuntimeEntry entry, long cohortId) {
        if (entry != null) {
            entry.relationshipState().setCohortId(cohortId);
        }
    }

    public static long formationId(AgentRuntimeEntry entry) {
        return entry == null ? 0L : entry.relationshipState().formationId();
    }

    public static void setFormationId(AgentRuntimeEntry entry, long formationId) {
        if (entry != null) {
            entry.relationshipState().setFormationId(formationId);
        }
    }
}
