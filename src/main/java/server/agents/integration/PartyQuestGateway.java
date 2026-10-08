package server.agents.integration;

import client.Character;
import scripting.event.EventInstanceManager;

import java.util.Set;
import java.util.List;

/** Semantic boundary for authoritative party-quest event and NPC operations. */
@AgentGatewayAffinity(
        value = AgentGatewayThreadAffinity.SHARD_SAFE_DIRECT,
        rationale = "KPQ operations run through normal channel-owned NPC, portal, event, and loot paths.")
public interface PartyQuestGateway {
    boolean runNpc(Character agent, int npcId, int... selections);

    /** Hints that these NPCs' scripts run soon, so they can be evaluated away from the Agent tick thread. */
    default void prepareNpcScripts(int... npcIds) {
    }

    boolean enterPortal(Character agent, int portalId);

    boolean lootNearby(Character agent, Set<Integer> itemIds);

    EventInstanceManager event(Character character);

    String eventName(Character character);

    String property(Character character, String key);

    int playerGrid(Character character);

    boolean sameEvent(Character first, Character second);

    List<Character> eventMembers(Character character);

    /** Instances of this event open on the anchor's channel, human and Agent lobbies alike. */
    default int openInstances(Character anchor, String eventName) {
        return 0;
    }

    /** Every NPC object of {@code npcId} on the agent's map, hidden ones too, in object-id order. */
    default List<NpcSighting> npcObjects(Character agent, int npcId) {
        return List.of();
    }

    /**
     * Runs {@code npcId}'s script as a click on the NPC object {@code npcObjectId} of the agent's map, for scripts
     * that tell their NPC objects apart; false when that object is not an {@code npcId} NPC on the agent's map.
     */
    default boolean runNpcObject(Character agent, int npcId, int npcObjectId, int... selections) {
        return false;
    }
}
