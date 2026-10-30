package server.agents.capabilities.expedition;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

/**
 * The battle behavior a boss run with adopted (claimed) Agents uses. It is the fight half of an
 * {@link AgentExpeditionScenario} without the fixture contract: production never reaches
 * {@code spec()} or {@code prepareMember()}, and members keep their own level, gear and appearance.
 */
public interface AgentBossFight {
    /** Gives an adopted member its formation slot for the rest of the run. */
    void adopt(int characterId, int ordinal);

    /** Drives the alive adopted members that stand on the battle map of {@code event}. */
    void tick(List<Character> fighters, EventInstanceManager event, long nowMs);

    /**
     * Drives {@code fighters} as {@link #tick(List, EventInstanceManager, long)} does, while what the fight reports of
     * the members (its status line) covers all of {@code party}: the fighters and the members another play steers in
     * the same fight (CWKPQ's Council Hall mages).
     */
    default void tick(List<Character> fighters, List<Character> party, EventInstanceManager event, long nowMs) {
        tick(fighters, event, nowMs);
    }

    /** Releases fight-owned state when the run ends. */
    void end(EventInstanceManager event);

    /**
     * Whether the fight still drives its members on {@code mapId} after the clear (CWKPQ plan C8: the keep's Secret
     * Chamber and bonus Armory); a fight that ends at its clear says no, and its followers follow their owner again.
     */
    default boolean playsAfterClear(int mapId) {
        return false;
    }

    /** After the clear: drives the alive members inside that stand on a map {@link #playsAfterClear} names. */
    default void tickAfterClear(List<Character> members, EventInstanceManager event, long nowMs) {
    }

    /**
     * How the run went segment by segment, for the clear-time records (CWKPQ plan C8), read once as the run ends and
     * before {@link #end}; null for a fight that measures none (every boss but CWKPQ).
     */
    default AgentPqPace pace(long sessionId, long startedAtMs, long endedAtMs) {
        return null;
    }
}
