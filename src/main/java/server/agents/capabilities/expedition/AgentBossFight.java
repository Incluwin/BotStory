package server.agents.capabilities.expedition;

import client.Character;
import scripting.event.EventInstanceManager;

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
}
