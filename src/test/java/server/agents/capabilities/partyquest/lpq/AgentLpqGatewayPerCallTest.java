package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PartyGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The LPQ coordinator resolves its gateways per call (P6g LPQ, C2.0a): it is loaded once per JVM, before any test's
 * mock, and a later test's stand-in still receives the calls; two tests in a row each see their own.
 */
class AgentLpqGatewayPerCallTest {

    private static Object field(String name) throws ReflectiveOperationException {
        Field field = AgentLpqCoordinator.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @Test
    void aCoordinatorLoadedBeforeTheMockStillCallsTheMock() throws ReflectiveOperationException {
        Class.forName(AgentLpqCoordinator.class.getName(), true, AgentLpqCoordinator.class.getClassLoader());
        Character agent = mock(Character.class);
        for (int round = 0; round < 2; round++) {
            PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
            PartyQuestGateway lpq = mock(PartyQuestGateway.class);
            PartyGateway party = mock(PartyGateway.class);
            when(actions.grounded(agent)).thenReturn(true);
            try (MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives =
                         mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
                 MockedStatic<AgentPartyQuestGatewayRuntime> partyQuests =
                         mockStatic(AgentPartyQuestGatewayRuntime.class);
                 MockedStatic<AgentPartyGatewayRuntime> parties = mockStatic(AgentPartyGatewayRuntime.class)) {
                primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(actions);
                partyQuests.when(AgentPartyQuestGatewayRuntime::partyQuest).thenReturn(lpq);
                parties.when(AgentPartyGatewayRuntime::party).thenReturn(party);

                assertTrue(((PrimitiveCapabilityGateway) field("ACTIONS")).grounded(agent), "round " + round);
                ((PartyQuestGateway) field("LPQ")).event(agent);
                ((PartyGateway) field("PARTY")).sendPartyChat(agent, "hello");

                verify(actions).grounded(agent);
                verify(lpq).event(agent);
                verify(party).sendPartyChat(agent, "hello");
            }
        }
    }

    @Test
    void theCoordinatorsOwnReadsGoThroughTheCurrentGateway() {
        Character leader = mock(Character.class);
        EventInstanceManager event = mock(EventInstanceManager.class);
        PartyQuestGateway lpq = mock(PartyQuestGateway.class);
        when(lpq.event(leader)).thenReturn(null);
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, 1, 6, 1_000L);
        session.transition(AgentLpqSession.Phase.STAGE_3, 2_000L);
        session.bindEventInstance(event);
        try (MockedStatic<AgentPartyQuestGatewayRuntime> partyQuests =
                     mockStatic(AgentPartyQuestGatewayRuntime.class)) {
            partyQuests.when(AgentPartyQuestGatewayRuntime::partyQuest).thenReturn(lpq);

            assertEquals("The LPQ event instance ended or expired",
                    AgentLpqCoordinator.liveEventFailure(session, leader));
            verify(lpq).event(leader);
        }
    }

    @Test
    void theStandInsCompareAndPrintAsThemselves() throws ReflectiveOperationException {
        Object actions = field("ACTIONS");
        assertEquals(actions, actions);
        assertNotEquals(actions, field("LPQ"));
        assertEquals(System.identityHashCode(actions), actions.hashCode());
        assertTrue(actions.toString().contains("PrimitiveCapabilityGateway"), actions.toString());
    }
}
