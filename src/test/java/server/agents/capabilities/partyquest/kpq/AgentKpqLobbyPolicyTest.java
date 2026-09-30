package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Background KPQ never takes the last free lobby (P6 WP4). The population admits PRODUCTION, so the old cap, which
 * counted only BACKGROUND_POPULATION sessions, never bound: channel 1 background parties held both lobbies for 15 min
 * and raced the human for Lakelis. Stock tunables: two lobbies, one reserved for humans, one background party per
 * channel, two in all.
 */
class AgentKpqLobbyPolicyTest {
    private static final int WORLD = 0;
    private static final int CHANNEL = 1;

    private final List<AgentPartyQuestEngagement> engagements = new ArrayList<>();
    private final List<AgentRuntimeEntry> entries = new ArrayList<>();

    @AfterEach
    void clear() {
        engagements.forEach(AgentPartyQuestEngagementRegistry::remove);
        entries.forEach(AgentRuntimeRegistry::unregisterEntry);
    }

    @Test
    void anInstanceAlreadyOpenLeavesNoSlotWhileOneIsReservedForHumans() {
        assertFalse(AgentKpqLobbyPolicy.backgroundSlotAvailable(0, 0, 0, 1, 2, 1, 1, 2));
        assertTrue(AgentKpqLobbyPolicy.backgroundSlotAvailable(0, 0, 0, 0, 2, 1, 1, 2));
    }

    @Test
    void aLobbyStillFormingUsesUpAFreeInstanceLikeAnOpenOne() {
        assertTrue(AgentKpqLobbyPolicy.backgroundSlotAvailable(1, 1, 0, 1, 3, 1, 2, 4));
        assertFalse(AgentKpqLobbyPolicy.backgroundSlotAvailable(1, 1, 1, 1, 3, 1, 2, 4));
    }

    @Test
    void aKpqOwnedPartyMayEnterOnlyWhileTheReservedLobbyStaysFree() {
        assertFalse(AgentKpqLobbyPolicy.backgroundEntryAllowed(1, 2, 1));
        assertTrue(AgentKpqLobbyPolicy.backgroundEntryAllowed(0, 2, 1));
    }

    @Test
    void aHumanInstanceOpenOnTheChannelBlocksTheNextBackgroundParty() {
        assertTrue(AgentKpqLobbyPolicy.backgroundSlotAvailable(WORLD, CHANNEL, anchor(0)));
        assertFalse(AgentKpqLobbyPolicy.backgroundSlotAvailable(WORLD, CHANNEL, anchor(1)));
    }

    @Test
    void agentOperatedProductionEngagementCountsAsBackground() {
        registerAgent(71_001);
        AgentPartyQuestEngagement forming = forming(
                AgentPartyQuestEngagement.Mode.PRODUCTION, 71_001, 71_001);

        assertTrue(AgentKpqLobbyPolicy.background(forming));
        assertFalse(AgentKpqLobbyPolicy.backgroundSlotAvailable(WORLD, CHANNEL, anchor(0)),
                "the population's forming PRODUCTION lobby holds the channel's background slot");
    }

    @Test
    void aHumanLedCompanionLobbyAndAGmTestRunAreNotBackground() {
        registerAgent(71_011);
        registerAgent(71_021);
        AgentPartyQuestEngagement companions = forming(
                AgentPartyQuestEngagement.Mode.PRODUCTION, 71_010, 71_011); // a human leader's companion run
        AgentPartyQuestEngagement test = forming(
                AgentPartyQuestEngagement.Mode.TEST_OBSERVATION, 71_021, 71_021);

        assertFalse(AgentKpqLobbyPolicy.background(companions));
        assertFalse(AgentKpqLobbyPolicy.background(test));
        assertTrue(AgentKpqLobbyPolicy.backgroundSlotAvailable(WORLD, CHANNEL, anchor(0)));
    }

    /** A kpq lobby forming with its operator and one Agent member, registered like a real one. */
    private AgentPartyQuestEngagement forming(AgentPartyQuestEngagement.Mode mode, int operatorId, int agentId) {
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement("kpq", mode, 1L, operatorId, 3, 1_000L);
        engagement.addMember(agentId, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        engagement.beginLobby("lobby-" + operatorId, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        engagements.add(engagement);
        return engagement;
    }

    private void registerAgent(int characterId) {
        Character agent = onChannel(mock(Character.class));
        when(agent.getId()).thenReturn(characterId);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentRuntimeRegistry.registerEntry(entry);
        entries.add(entry);
    }

    /** A character on the channel whose Kerning PQ event manager has this many instances open. */
    private static Character anchor(int openInstances) {
        Character anchor = onChannel(mock(Character.class));
        EventManager kerningPq = mock(EventManager.class);
        List<EventInstanceManager> open = new ArrayList<>();
        for (int i = 0; i < openInstances; i++) open.add(mock(EventInstanceManager.class));
        when(kerningPq.getInstances()).thenReturn(open);
        when(anchor.getClient().getEventManager("KerningPQ")).thenReturn(kerningPq);
        return anchor;
    }

    private static Character onChannel(Character character) {
        Client client = mock(Client.class);
        when(client.getWorld()).thenReturn(WORLD);
        when(client.getChannel()).thenReturn(CHANNEL);
        when(character.getClient()).thenReturn(client);
        return character;
    }
}
