package server.agents.integration.cosmic;

import client.Character;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import scripting.npc.NPCScriptManager;
import server.agents.integration.NpcSighting;
import server.agents.integration.PartyQuestGateway;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.List;

public enum CosmicPartyQuestGateway implements PartyQuestGateway {
    INSTANCE;

    @Override
    public boolean runNpc(Character agent, int npcId, int... selections) {
        return CosmicPrimitiveCapabilityGateway.INSTANCE.runNpcScript(agent, npcId, selections);
    }

    @Override
    public List<NpcSighting> npcObjects(Character agent, int npcId) {
        MapleMap map = agent == null ? null : agent.getMap();
        if (map == null) {
            return List.of();
        }
        List<NpcSighting> sightings = new ArrayList<>();
        for (MapObject object : map.getMapObjects()) {
            if (object instanceof NPC npc && npc.getId() == npcId) {
                sightings.add(new NpcSighting(npc.getObjectId(), npcId, npc.getPosition()));
            }
        }
        sightings.sort(Comparator.comparingInt(NpcSighting::objectId));
        return List.copyOf(sightings);
    }

    @Override
    public boolean runNpcObject(Character agent, int npcId, int npcObjectId, int... selections) {
        return runNpcObject(CosmicPreparedNpcScriptEngines.INSTANCE, agent, npcId, npcObjectId, selections);
    }

    static boolean runNpcObject(CosmicPreparedNpcScriptEngines engines, Character agent, int npcId, int npcObjectId,
                                int... selections) {
        MapleMap map = agent == null ? null : agent.getMap();
        if (map == null || !(map.getMapObject(npcObjectId) instanceof NPC npc) || npc.getId() != npcId) {
            return false;
        }
        return CosmicHeadlessNpcScriptGateway.executeOnObject(engines, agent, npcId, npcObjectId, selections);
    }

    @Override
    public void prepareNpcScripts(int... npcIds) {
        for (int npcId : npcIds) {
            CosmicPreparedNpcScriptEngines.INSTANCE.prepare(NPCScriptManager.npcScriptPath(npcId));
        }
    }

    @Override
    public boolean enterPortal(Character agent, int portalId) {
        return CosmicMapGateway.INSTANCE.enterPortal(agent, portalId);
    }

    @Override
    public boolean lootNearby(Character agent, Set<Integer> itemIds) {
        return CosmicPrimitiveCapabilityGateway.INSTANCE.lootNearby(agent, itemIds);
    }

    @Override
    public EventInstanceManager event(Character character) {
        return character == null ? null : character.getEventInstance();
    }

    @Override
    public String eventName(Character character) {
        EventInstanceManager event = event(character);
        return event == null ? "" : event.getName();
    }

    @Override
    public String property(Character character, String key) {
        EventInstanceManager event = event(character);
        return event == null ? null : event.getProperty(key);
    }

    @Override
    public int playerGrid(Character character) {
        EventInstanceManager event = event(character);
        return event == null ? -1 : event.gridCheck(character);
    }

    @Override
    public boolean sameEvent(Character first, Character second) {
        EventInstanceManager firstEvent = event(first);
        return firstEvent != null && firstEvent == event(second);
    }

    @Override
    public List<Character> eventMembers(Character character) {
        EventInstanceManager event = event(character);
        return event == null ? List.of() : List.copyOf(event.getPlayers());
    }

    @Override
    public int openInstances(Character anchor, String eventName) {
        EventManager em = anchor == null || anchor.getClient() == null
                ? null : anchor.getClient().getEventManager(eventName);
        return em == null ? 0 : em.getInstances().size();
    }
}
