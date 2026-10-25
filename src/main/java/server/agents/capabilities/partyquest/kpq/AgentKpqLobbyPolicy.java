package server.agents.capabilities.partyquest.kpq;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

/** KPQ-only capacity policy; event scripts and background admission share one source of truth. */
public final class AgentKpqLobbyPolicy {
    private AgentKpqLobbyPolicy() {
    }

    public static int maxLobbies() {
        return Math.max(1, Math.min(8, config.AgentTuning.intValue(
                "server.agents.capabilities.partyquest.kpq.AgentKpqLobbyPolicy.MAX_LOBBIES")));
    }

    /** Lobbies per channel that background KPQ leaves free for human parties. */
    static int humanReservedLobbies() {
        return Math.max(0, config.AgentTuning.intValue(
                "server.agents.capabilities.partyquest.kpq.AgentKpqLobbyPolicy.HUMAN_RESERVED_LOBBIES"));
    }

    /**
     * A live KPQ lobby the population runs: operated by a registered Agent, or a legacy BACKGROUND_POPULATION one.
     * The population admits PRODUCTION, so counting BACKGROUND_POPULATION sessions alone never bound the caps. A
     * human's lobby (companions included) and a GM test run are never background.
     */
    static boolean background(AgentPartyQuestEngagement engagement) {
        if (!"kpq".equals(engagement.questKey())
                || engagement.mode() == AgentPartyQuestEngagement.Mode.TEST_OBSERVATION) return false;
        if (engagement.mode() != AgentPartyQuestEngagement.Mode.BACKGROUND_POPULATION
                && AgentRuntimeRegistry.findByAgentCharacterId(engagement.operatorId()) == null) return false;
        return switch (engagement.state()) {
            case ACQUIRING_AGENTS, LOBBY_FORMING, LOBBY_READY, RESERVING_ENTRY, ACTIVE_EVENT -> true;
            default -> false;
        };
    }

    /**
     * True when the population may form another KPQ party on this channel. Instances already open there (read
     * through {@code anchor}, human ones included) and background lobbies still forming both use up free lobbies,
     * so a background party never takes the last one a human could enter. The population asks it before a citizen
     * party goes in (CitizenPartyQuests); a citizen run is operated by its citizen leader, so it counts here too.
     */
    public static boolean backgroundSlotAvailable(int world, int channel, Character anchor) {
        int total = 0;
        int onChannel = 0;
        int pendingOnChannel = 0;
        for (AgentPartyQuestEngagement engagement : AgentPartyQuestEngagementRegistry.engagements()) {
            if (!background(engagement)) continue;
            total++;
            Character agent = firstAgent(engagement);
            if (agent != null
                    && AgentClientGatewayRuntime.clients().world(agent) == world
                    && AgentClientGatewayRuntime.clients().channel(agent) == channel) {
                onChannel++;
                if (engagement.state() != AgentPartyQuestEngagement.State.ACTIVE_EVENT) pendingOnChannel++;
            }
        }
        int globalLimit = Math.max(0, config.AgentTuning.intValue(
                "server.agents.capabilities.partyquest.kpq.AgentKpqLobbyPolicy.MAX_BACKGROUND_TOTAL"));
        int perChannelConfigured = Math.max(0, config.AgentTuning.intValue(
                "server.agents.capabilities.partyquest.kpq.AgentKpqLobbyPolicy.MAX_BACKGROUND_PER_CHANNEL"));
        int reserved = Math.max(0, config.AgentTuning.intValue(
                "server.agents.capabilities.partyquest.kpq.AgentKpqLobbyPolicy.HUMAN_RESERVED_LOBBIES"));
        int occupied = AgentPartyQuestGatewayRuntime.partyQuest().openInstances(anchor, "KerningPQ");
        return backgroundSlotAvailable(total, onChannel, pendingOnChannel, occupied, maxLobbies(), reserved,
                perChannelConfigured, globalLimit);
    }

    static boolean backgroundSlotAvailable(int total, int onChannel, int pendingOnChannel, int occupied,
                                           int maxLobbies, int reserved, int perChannelConfigured,
                                           int globalLimit) {
        return total < globalLimit
                && onChannel < Math.min(perChannelConfigured, Math.max(0, maxLobbies - reserved))
                && maxLobbies - occupied - pendingOnChannel > reserved;
    }

    static boolean backgroundEntryAllowed(int occupied, int maxLobbies, int reserved) {
        return maxLobbies - occupied > reserved;
    }

    /** The channel of an engagement is its operator's, else its first loaded Agent member's. */
    private static Character firstAgent(AgentPartyQuestEngagement engagement) {
        Character operator = agent(engagement.operatorId());
        if (operator != null) return operator;
        for (int agentId : engagement.agentIds()) {
            Character agent = agent(agentId);
            if (agent != null) return agent;
        }
        return null;
    }

    private static Character agent(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        return entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
    }
}
