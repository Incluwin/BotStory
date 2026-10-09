package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
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
 * The OPQ coordinator resolves its gateways per call (P6h OPQ, C0): it is loaded once per JVM, before any test's
 * mock, and a later test's stand-in still receives the calls; two tests in a row each see their own.
 */
class AgentOpqGatewayPerCallTest {

    private static Object field(String name) throws ReflectiveOperationException {
        Field field = AgentOpqCoordinator.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @Test
    void aCoordinatorLoadedBeforeTheMockStillCallsTheMock() throws ReflectiveOperationException {
        Class.forName(AgentOpqCoordinator.class.getName(), true, AgentOpqCoordinator.class.getClassLoader());
        Character agent = mock(Character.class);
        for (int round = 0; round < 2; round++) {
            PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
            PartyQuestGateway opq = mock(PartyQuestGateway.class);
            when(actions.grounded(agent)).thenReturn(true);
            when(opq.property(agent, "statusStg0")).thenReturn("1");
            try (MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives =
                         mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
                 MockedStatic<AgentPartyQuestGatewayRuntime> partyQuests =
                         mockStatic(AgentPartyQuestGatewayRuntime.class)) {
                primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(actions);
                partyQuests.when(AgentPartyQuestGatewayRuntime::partyQuest).thenReturn(opq);

                assertTrue(((PrimitiveCapabilityGateway) field("ACTIONS")).grounded(agent), "round " + round);
                assertEquals("1", ((PartyQuestGateway) field("OPQ")).property(agent, "statusStg0"), "round " + round);

                verify(actions).grounded(agent);
                verify(opq).property(agent, "statusStg0");
            }
        }
    }

    @Test
    void theCoordinatorsOwnCallsGoThroughTheCurrentGateway() {
        Character agent = mock(Character.class);
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.AUTONOMOUS, 7L, 1, 1_000L);
        session.addMember(11, AgentOpqMemberState.MemberType.AGENT);
        session.setLeadership(11, 11);
        PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
        try (MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives =
                     mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class)) {
            primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(actions);
            when(agent.getId()).thenReturn(11);
            when(agent.getPosition()).thenReturn(new java.awt.Point(0, 0));
            AgentOpqMemberState member = session.member(11);
            member.observeMovement(new java.awt.Point(0, 0), 1L);
            server.agents.runtime.AgentRuntimeEntry entry = mock(server.agents.runtime.AgentRuntimeEntry.class);

            // A stalled worker refreshes its route through ACTIONS: the call reaches this test's gateway.
            AgentOpqCoordinator.tickMember(session, entry, agent, 10_000_000L);

            verify(actions).refreshNavigation(entry, agent);
        }
    }

    @Test
    void theStandInsCompareAndPrintAsThemselves() throws ReflectiveOperationException {
        Object actions = field("ACTIONS");
        assertEquals(actions, actions);
        assertNotEquals(actions, field("OPQ"));
        assertEquals(System.identityHashCode(actions), actions.hashCode());
        assertTrue(actions.toString().contains("PrimitiveCapabilityGateway"), actions.toString());
    }
}
