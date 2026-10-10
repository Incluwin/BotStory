package server.agents.capabilities.partyquest;

import client.Character;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.session.AgentActivityAdmissionResult;

/** Lifecycle-only contract. Every PQ retains its own session, coordinator, and tuning. */
public interface AgentPartyQuestSystem {
    AgentPartyQuestDefinition definition();

    boolean sessionActive(int characterId);

    boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs);

    boolean requestStop(int characterId, String reason, long nowMs);

    void forceStop(int characterId, String reason, long nowMs);

    void runtimeRemoved(int characterId, long nowMs);

    AgentPartyQuestSessionView sessionView(int characterId);

    boolean pause(int characterId);

    boolean resumeExact(int characterId, String sessionId, long nowMs);

    AgentActivityAdmissionResult requestEntry(
            AgentRuntimeEntry entry, Character agent, String scenarioId,
            int partySize, int maximumRuns, long nowMs);

    String entryBlocker(Character agent, String scenarioId, int partySize, int maximumRuns);

    /**
     * True while {@code characterId} is a member of this quest's session that a host adopted for a human leader and
     * his companions (AgentCompanionRunSession.companionAdopted); false in a stock run or none. Memory only.
     */
    boolean companionRun(int characterId);

    /**
     * True when this quest's companion play says itself that the member ran out of projectiles on {@code mapId}
     * (LPQ stage 7 names who takes its ratz), so the generic in-run line stays quiet. Memory only.
     */
    default boolean ownsDryLine(int characterId, int mapId) {
        return false;
    }

    /**
     * True while this quest's own play holds the member on {@code mapId} while it is low on HP potions (the EPQ golem
     * rest, and its after-clear add fight), so the generic out-of-potions grind stop leaves its moves and words to the
     * quest. Memory only.
     */
    default boolean ownsPotionRest(int characterId, int mapId) {
        return false;
    }
}
