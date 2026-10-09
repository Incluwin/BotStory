package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.supplies.AgentSupplyLogFields;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.expeditions.Expedition;
import server.maps.MapleMap;

import java.awt.Point;

/**
 * Everything a boss run does to the world, keyed by Agent character id where an Agent's runtime is needed. The
 * session reads and acts only through these, so tests drive it with fakes; {@link #LIVE} is the server.
 */
public interface AgentBossRunPorts {
    AgentBossRunPorts LIVE = new Live();

    /** The loaded Agent character, or null when it has no runtime. */
    Character agent(int agentId);

    /** True when the server drives the character (a headless Agent client); a human's character answers false. */
    boolean headless(Character character);

    /**
     * True while the character is logged in to its world: in the world's player storage and not on its way out. A
     * logout marks the character away before it leaves its event, so a human gone from the event that still answers
     * true walked out; false means it logged out or disconnected.
     */
    boolean online(int characterId);

    Point npcPosition(Character agent, int npcId);

    Point portalPosition(Character agent, int portalId);

    /** One headless NPC conversation with these window choices; false when it failed or threw. */
    boolean runNpc(Character agent, int npcId, int... selections);

    /** Takes the Agent off the expedition's list the way a member leaves it (the stock "has left" notice). */
    void leaveList(Expedition expedition, Character agent);

    void navigate(int agentId, Point destination, boolean precise);

    void stop(int agentId);

    void clearBoundary(int agentId);

    boolean enterPortal(Character agent, int portalId);

    /** Moves the Agent to the channel's {@code mapId} beside its portal 0, the way its respawn moves it. */
    void moveOut(Character agent, int mapId);

    /** The Agent follows its configured target (its owner) again. */
    void follow(int agentId);

    /** Stops the Agent's other exclusive activities so the run may drive it; false when one would not stop. */
    boolean prepareAdoption(int agentId, String reason, long nowMs);

    /** True while a potion trip is under way. */
    boolean restocking(int agentId, long nowMs);

    /** True while the Agent's own restock has yet to decide on a trip. */
    boolean restockPending(int agentId, long nowMs);

    /** True while the expedition is still the one its channel holds for its type. */
    boolean stillTheChannels(Expedition expedition);

    /** Hints that these NPCs' scripts run soon, so they are evaluated away from the Agent tick. */
    void prepareScripts(int... npcIds);

    void schedule(Runnable action, long delayMs);

    /**
     * What a log line shows of the character's supplies (AgentSupplyLogFields.of: " pots=H/M", a shooter's
     * " ammo=N noAmmo=B"); a dead member's bag still answers.
     */
    String supplies(Character agent);

    /** The live server. */
    final class Live implements AgentBossRunPorts {
        private static final Logger log = LoggerFactory.getLogger(AgentBossRunPorts.class);

        private Live() {
        }

        private static AgentRuntimeEntry entry(int agentId) {
            return AgentRuntimeRegistry.findByAgentCharacterId(agentId);
        }

        @Override
        public Character agent(int agentId) {
            AgentRuntimeEntry entry = entry(agentId);
            return entry == null ? null : entry.bot();
        }

        @Override
        public boolean headless(Character character) {
            return AgentCharacterGatewayRuntime.characters().isHeadlessControlled(character);
        }

        @Override
        public boolean online(int characterId) {
            Character character = AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(characterId);
            return character != null && character.isLoggedinWorld();
        }

        @Override
        public Point npcPosition(Character agent, int npcId) {
            return AgentPrimitiveCapabilityGatewayRuntime.gateway().npcPosition(agent, npcId);
        }

        @Override
        public Point portalPosition(Character agent, int portalId) {
            return AgentPrimitiveCapabilityGatewayRuntime.gateway().portalPosition(agent, portalId);
        }

        @Override
        public boolean runNpc(Character agent, int npcId, int... selections) {
            try {
                return AgentPartyQuestGatewayRuntime.partyQuest().runNpc(agent, npcId, selections);
            } catch (RuntimeException failure) {
                log.warn("boss-run NPC {} conversation failed for {}", npcId, agent.getId(), failure);
                return false;
            }
        }

        @Override
        public void leaveList(Expedition expedition, Character agent) {
            try {
                expedition.removeMember(agent);
            } catch (RuntimeException failure) {
                log.warn("boss-run list leave failed for {}", agent.getId(), failure);
            }
        }

        @Override
        public void navigate(int agentId, Point destination, boolean precise) {
            AgentRuntimeEntry entry = entry(agentId);
            if (entry != null && destination != null) {
                AgentPrimitiveCapabilityGatewayRuntime.gateway().navigate(entry, destination, precise);
            }
        }

        @Override
        public void stop(int agentId) {
            AgentRuntimeEntry entry = entry(agentId);
            if (entry != null) {
                AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
            }
        }

        @Override
        public void clearBoundary(int agentId) {
            AgentRuntimeEntry entry = entry(agentId);
            if (entry != null) {
                AgentPrimitiveCapabilityGatewayRuntime.gateway().clearHorizontalBoundary(entry);
            }
        }

        @Override
        public boolean enterPortal(Character agent, int portalId) {
            return AgentPrimitiveCapabilityGatewayRuntime.gateway().enterPortal(agent, portalId);
        }

        @Override
        public void moveOut(Character agent, int mapId) {
            var clients = AgentClientGatewayRuntime.clients();
            MapleMap map = AgentMapGatewayRuntime.map().resolveMap(clients.world(agent), clients.channel(agent), mapId);
            var portal = map == null ? null : map.getPortal(0);
            AgentMapGatewayRuntime.map().changeMapNear(agent, map, portal == null ? null : portal.getPosition());
        }

        @Override
        public void follow(int agentId) {
            AgentRuntimeEntry entry = entry(agentId);
            if (entry != null) {
                AgentMovementCommandRuntime.followConfiguredTarget(entry);
            }
        }

        @Override
        public boolean prepareAdoption(int agentId, String reason, long nowMs) {
            AgentRuntimeEntry entry = entry(agentId);
            Character agent = entry == null ? null : entry.bot();
            if (agent == null || !AgentActivityBootstrap.admission().prepare(
                    AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent, reason, nowMs)) {
                return false;
            }
            AgentPrimitiveCapabilityGatewayRuntime.gateway().prepareNavigation(entry, agent);
            return true;
        }

        @Override
        public boolean restocking(int agentId, long nowMs) {
            return AgentSupplyReadiness.status(entry(agentId), nowMs).restocking();
        }

        @Override
        public boolean restockPending(int agentId, long nowMs) {
            return AgentSupplyReadiness.restockPending(entry(agentId), nowMs);
        }

        @Override
        public boolean stillTheChannels(Expedition expedition) {
            MapleMap recruit = expedition == null ? null : expedition.getRecruitingMap();
            var channel = recruit == null ? null : recruit.getChannelServer();
            return channel != null && channel.getExpedition(expedition.getType()) == expedition;
        }

        @Override
        public void prepareScripts(int... npcIds) {
            AgentPartyQuestGatewayRuntime.partyQuest().prepareNpcScripts(npcIds);
        }

        @Override
        public void schedule(Runnable action, long delayMs) {
            AgentSchedulerRuntime.schedule(action, delayMs);
        }

        @Override
        public String supplies(Character agent) {
            return AgentSupplyLogFields.of(agent);
        }
    }
}
