package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestCandidateScope;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbySession;
import server.maps.MapleMap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.MIRA;

/**
 * P6h open item: LudiMazePQ.js keeps one lobby a channel, and a director's run of Agents only took it for its whole
 * run while Ben stood at Rolly's desk with his companions, who then got the stock "Another party has already
 * entered...". Now a director's Agents-only lobby goes back to forming while a party with a human in it stands outside
 * the maze on Rolly's map, as the citizen door leaves a lobby for each human party waiting; with none there it is
 * admitted as before.
 */
class AgentLmpqDirectorLobbyYieldTest {
    private final LmpqAdmitFixture fixture = new LmpqAdmitFixture();

    @AfterEach
    void tearDown() {
        for (var lobby : server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry.lobbies()) {
            if (lobby.memberIds().contains(MIRA)) AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), 0L);
        }
        fixture.close();
    }

    /** Mira's director lobby of three Agents at Rolly, formed and ready; Ben stands on the same map. */
    private AgentPartyQuestLobbySession directorLobby(AgentPartyQuestEngagement engagement) {
        AgentPartyQuestLobbySession lobby = new AgentPartyQuestLobbySession(engagement.engagementId(),
                AgentLmpqLobbyProfile.profile(), 7L, MIRA, 3, AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN,
                1_000L);
        Set<Integer> roster = new LinkedHashSet<>();
        for (Character agent : agents()) {
            AgentPartyQuestEngagementRegistry.addAndIndexMember(
                    engagement, agent.getId(), AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
            lobby.addMember(agent.getId(), AgentPartyQuestLobbySession.MemberType.AGENT,
                    AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, 1_000L);
            roster.add(agent.getId());
        }
        lobby.setCoordinatorAgentId(MIRA);
        lobby.reconcileParty(21, MIRA, roster, 1_000L);
        lobby.markReady(1_000L);
        engagement.beginLobby(lobby.lobbyId(), 1_000L);
        engagement.lobbyReady(1_000L);
        AgentPartyQuestLobbyRuntime.register(lobby, 1_000L);
        MapleMap rolly = mock(MapleMap.class);
        when(rolly.getAllPlayers()).thenReturn(List.of(fixture.mira, fixture.kes, fixture.pel, fixture.ben));
        agents().forEach(agent -> when(agent.getMap()).thenReturn(rolly));
        return lobby;
    }

    private List<Character> agents() {
        return List.of(fixture.mira, fixture.kes, fixture.pel);
    }

    private AgentPartyQuestEngagement engagement() {
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "lmpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, MIRA, 3, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        return engagement;
    }

    @Test
    void aDirectorLobbyOfAgentsWaitsWhileAHumanPartyStandsAtRolly() {
        when(fixture.party.hasParty(fixture.ben)).thenReturn(true); // Ben and his companions at the desk
        AgentPartyQuestEngagement engagement = engagement();
        AgentPartyQuestLobbySession lobby = directorLobby(engagement);

        AgentLmpqAdmissionService.AdmissionResult result = AgentLmpqAdmissionService.admitFromLobby(
                engagement, lobby, fixture.mira, fixture.mira, agents(), 7L, 2_000L);

        assertFalse(result.success());
        assertEquals(AgentLmpqAdmissionService.HUMAN_PARTY_FIRST, result.message());
        assertNull(AgentLmpqSessionRegistry.forOperator(MIRA), "no run took the lobby");
        assertEquals(AgentPartyQuestEngagement.State.LOBBY_FORMING, engagement.state(), "it forms again and retries");
    }

    @Test
    void withNoHumanPartyThereTheDirectorLobbyGoesOnToAdmission() {
        AgentPartyQuestEngagement engagement = engagement();
        AgentPartyQuestLobbySession lobby = directorLobby(engagement); // Ben has no party: nobody waits

        AgentLmpqAdmissionService.AdmissionResult result = AgentLmpqAdmissionService.admitFromLobby(
                engagement, lobby, fixture.mira, fixture.mira, agents(), 7L, 2_000L);

        assertNotEquals(AgentLmpqAdmissionService.HUMAN_PARTY_FIRST, result.message());
    }

    @Test
    void aHumanInsideAnEventOrInTheLobbyItselfIsNotWaiting() {
        directorLobby(engagement());
        when(fixture.party.hasParty(fixture.ben)).thenReturn(true);
        when(fixture.ben.getEventInstance()).thenReturn(mock(scripting.event.EventInstanceManager.class));
        assertFalse(AgentLmpqAdmissionService.humanPartyWaiting(fixture.mira, Set.of()), "in an event");
        when(fixture.ben.getEventInstance()).thenReturn(null);
        assertTrue(AgentLmpqAdmissionService.humanPartyWaiting(fixture.mira, Set.of()));
        assertFalse(AgentLmpqAdmissionService.humanPartyWaiting(fixture.mira, Set.of(fixture.ben.getId())),
                "one of the lobby's own");
    }
}
