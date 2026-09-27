package server.agents.capabilities.party;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.PartyGateway;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AgentPartyLifecycleService reports what the party join did (docs/BOT_MANAGEMENT_PLAN.md, "JoinOutcome and spawn fixes"). */
class JoinOutcomeTest {
    private static final int PARTY = 77;

    private final PartyGateway gateway = mock(PartyGateway.class);
    private final Character leader = mock(Character.class);
    private final Character agent = mock(Character.class);
    private MockedStatic<AgentPartyGatewayRuntime> gateways;

    @BeforeEach
    void wireGateway() {
        gateways = mockStatic(AgentPartyGatewayRuntime.class);
        gateways.when(AgentPartyGatewayRuntime::party).thenReturn(gateway);
    }

    @AfterEach
    void closeGateway() {
        gateways.close();
    }

    @Test
    void joinsTheLeadersParty() {
        when(gateway.snapshot(leader)).thenReturn(party(PARTY, 2));
        when(gateway.joinAgentParty(agent, PARTY)).thenReturn(true);

        assertEquals(JoinOutcome.JOINED, AgentPartyLifecycleService.joinAgentToLeaderParty(leader, agent));
        verify(agent).updatePartyMemberHP();
    }

    @Test
    void anAgentAlreadyInTheLeadersPartyIsAMember() {
        when(gateway.snapshot(agent)).thenReturn(party(PARTY, 3));
        when(gateway.snapshot(leader)).thenReturn(party(PARTY, 3));

        assertEquals(JoinOutcome.ALREADY_MEMBER, AgentPartyLifecycleService.joinAgentToLeaderParty(leader, agent));
        verify(gateway).publishAgentOnline(agent, PARTY);
        verify(gateway, never()).joinAgentParty(any(), anyInt());
    }

    @Test
    void anAgentInAnotherPartyLeavesItAndJoins() {
        when(gateway.snapshot(agent)).thenReturn(party(55, 2));
        when(gateway.snapshot(leader)).thenReturn(party(PARTY, 2));
        when(gateway.joinAgentParty(agent, PARTY)).thenReturn(true);

        assertEquals(JoinOutcome.JOINED, AgentPartyLifecycleService.joinAgentToLeaderParty(leader, agent));
        verify(gateway).leaveCurrentParty(agent);
    }

    @Test
    void aPartylessLeaderGetsAPartyFirst() {
        when(gateway.snapshot(leader)).thenReturn(null, party(PARTY, 1));
        when(gateway.createAgentParty(leader)).thenReturn(true);
        when(gateway.joinAgentParty(agent, PARTY)).thenReturn(true);

        assertEquals(JoinOutcome.JOINED, AgentPartyLifecycleService.joinAgentToLeaderParty(leader, agent));
    }

    @Test
    void aLeaderWhoCannotFormAPartyHasNone() {
        // Party.createParty refuses a leader below level 10 (Party.java:336)
        when(gateway.createAgentParty(leader)).thenReturn(false);

        assertEquals(JoinOutcome.NO_PARTY, AgentPartyLifecycleService.joinAgentToLeaderParty(leader, agent));
        verify(gateway, never()).joinAgentParty(any(), anyInt());
    }

    @Test
    void aSixMemberPartyIsFull() {
        when(gateway.snapshot(leader)).thenReturn(party(PARTY, 6));

        assertEquals(JoinOutcome.PARTY_FULL, AgentPartyLifecycleService.joinAgentToLeaderParty(leader, agent));
        verify(agent, never()).updatePartyMemberHP();
    }

    @Test
    void aRefusedJoinWithRoomIsAFailure() {
        when(gateway.snapshot(leader)).thenReturn(party(PARTY, 3));

        assertEquals(JoinOutcome.FAILED, AgentPartyLifecycleService.joinAgentToLeaderParty(leader, agent));
    }

    private static AgentPartySnapshot party(int id, int size) {
        List<AgentPartyMemberSnapshot> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add(new AgentPartyMemberSnapshot(100 + i, "member" + i, i == 0, 104000000));
        }
        return new AgentPartySnapshot(id, members);
    }
}
