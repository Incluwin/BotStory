package server.agents.capabilities.partyquest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Per-Agent party-quest handback seam. The Agent layer stays owner-free: when a run lets its Agents go, recovery asks
 * this seam where each one goes, and the host (the living world) installs the rule that knows which Agents are out
 * with an owner. With nothing installed every Agent recovers into TownLife at the quest's town, as before; that sent
 * owned companions into Kerning TownLife after a live run instead of back to their owner.
 */
public final class AgentPartyQuestHandback {
    private static final Logger log = LoggerFactory.getLogger(AgentPartyQuestHandback.class);

    /** Where a released Agent goes once its run lets it go. */
    public enum Handback {
        /** Warped to the quest's recovery town and handed to TownLife (the stock recovery). */
        TOWN_LIFE,
        /** Follows its runtime leader from wherever the run left it, once it is alive and outside the event. */
        FOLLOW_LEADER,
        /** Only stopped: the host moves it itself. */
        RELEASE
    }

    /** A downed Agent that respawned outside its run's event: the host may take it out of the party. */
    public record TapOut(String questKey, String sessionId, int agentCharacterId, int mapId, long atMs) {
    }

    /** A party-quest run ended: its child session id, and a blank quest key when no engagement held the run. */
    public record RunEnded(String questKey, String sessionId, int operatorId, boolean success, String reason,
                           long endedAtMs) {
    }

    /**
     * The installed handback rule. Every method answers from memory on whatever thread releases the run (an Agent
     * tick, the KPQ watchdog, a packet thread), so any work it needs must be submitted elsewhere.
     */
    public interface Rule {
        /** Where this Agent character goes after {@code questKey} lets it go; null keeps TownLife. */
        Handback handback(int agentCharacterId, String questKey);

        /** True when the host takes this Agent out of the run's party itself; false leaves the run to fail. */
        default boolean tapOut(TapOut request) {
            return false;
        }

        /** The run ended; called once per finished child session, after its Agents were handed back or deferred. */
        default void runEnded(RunEnded ended) {
        }
    }

    private static volatile Rule rule;

    private AgentPartyQuestHandback() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    /** The installed rule's answer; TOWN_LIFE with none installed, a null answer, or a failing rule. */
    public static Handback handback(int agentCharacterId, String questKey) {
        Rule current = rule;
        if (current == null) return Handback.TOWN_LIFE;
        try {
            Handback answer = current.handback(agentCharacterId, questKey);
            return answer == null ? Handback.TOWN_LIFE : answer;
        } catch (RuntimeException failure) {
            log.warn("party-quest handback rule failed for {} in {}", agentCharacterId, questKey, failure);
            return Handback.TOWN_LIFE;
        }
    }

    /** True when the installed host took the Agent out of the party; false with none installed or a failing rule. */
    public static boolean tapOut(TapOut request) {
        Rule current = rule;
        if (current == null) return false;
        try {
            return current.tapOut(request);
        } catch (RuntimeException failure) {
            log.warn("party-quest tap-out rule failed for {}", request, failure);
            return false;
        }
    }

    /** Tells the installed host the run ended; nothing with none installed, and a failing rule is only logged. */
    public static void runEnded(RunEnded ended) {
        Rule current = rule;
        if (current == null) return;
        try {
            current.runEnded(ended);
        } catch (RuntimeException failure) {
            log.warn("party-quest run-ended rule failed for {}", ended, failure);
        }
    }

    /** True when any of these Agents still recovers into TownLife, which keeps the post-run hold. */
    static boolean anyTownLife(List<Integer> agentIds, String questKey) {
        return agentIds.stream().anyMatch(id -> handback(id, questKey) == Handback.TOWN_LIFE);
    }
}
