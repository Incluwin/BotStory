package server.agents.capabilities.behavior;

/**
 * Measuring seam for the tell audit (docs/BOT_CONCEPT_PLAN.md C2): the Agent engine reports the moments a watching
 * player could time or count (a reaction to a new mob, a chat reply's delay, the HP point it drinks at, the share of a
 * buff's duration at which it recasts, the route of a walk), and the host (the living world) installs the sink that
 * folds them. With no sink installed each report is one volatile read. Reports never change what the Agent does.
 */
public final class AgentTellProbe {
    /** The installed sink; every method runs on the reporting thread and must return at once. */
    public interface Sink {
        /** An Agent first acted on a new set of mobs {@code reactionMs} after it saw them. */
        void combatReaction(int agentId, long reactionMs);

        /** A content chat reply was timed to go out {@code delayMs} after the question. */
        void chatReply(long delayMs);

        /** An Agent drank an HP potion by its autopot, which fires at {@code alertPercent} of its maximum HP. */
        void potionPoint(int alertPercent);

        /** An Agent recast a buff it still had, {@code percentOfDuration} of the way through that buff's duration. */
        void buffRecast(int percentOfDuration);

        /** A host-started walk arrived: {@code routeHash} folds the maps and graph edges it walked, in order. */
        void routeWalked(int agentId, int fromMapId, int toMapId, long routeHash);
    }

    private static volatile Sink sink;

    private AgentTellProbe() {
    }

    public static void install(Sink installed) {
        sink = installed;
    }

    /** True while a sink listens, so a caller can skip work only a report needs. */
    public static boolean on() {
        return sink != null;
    }

    public static void combatReaction(int agentId, long reactionMs) {
        Sink current = sink;
        if (current != null) {
            current.combatReaction(agentId, reactionMs);
        }
    }

    public static void chatReply(long delayMs) {
        Sink current = sink;
        if (current != null) {
            current.chatReply(delayMs);
        }
    }

    public static void potionPoint(int alertPercent) {
        Sink current = sink;
        if (current != null) {
            current.potionPoint(alertPercent);
        }
    }

    public static void buffRecast(int percentOfDuration) {
        Sink current = sink;
        if (current != null) {
            current.buffRecast(percentOfDuration);
        }
    }

    public static void routeWalked(int agentId, int fromMapId, int toMapId, long routeHash) {
        Sink current = sink;
        if (current != null) {
            current.routeWalked(agentId, fromMapId, toMapId, routeHash);
        }
    }
}
