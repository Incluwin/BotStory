package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The LMPQ coordinator resolves its gateways per call (P6h LMPQ, C0): it is loaded once per JVM, before any test's
 * mock, and a later test's stand-in still receives the calls; two tests in a row each see their own.
 */
class AgentLmpqGatewayPerCallTest {
    private static final int HUMAN = 781_000;
    private static final int IDLE = 781_001;
    private static final int WORKER = 781_002;

    @Test
    void aCoordinatorLoadedBeforeTheMockStillCallsTheMock() throws ClassNotFoundException {
        Class.forName(AgentLmpqCoordinator.class.getName(), true, AgentLmpqCoordinator.class.getClassLoader());
        for (int round = 0; round < 2; round++) {
            PrimitiveCapabilityGateway actions = mock(PrimitiveCapabilityGateway.class);
            PartyQuestGateway lmpq = mock(PartyQuestGateway.class);
            try (MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives =
                         mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
                 MockedStatic<AgentPartyQuestGatewayRuntime> partyQuests =
                         mockStatic(AgentPartyQuestGatewayRuntime.class);
                 MockedStatic<AgentCharacterGatewayRuntime> characters =
                         mockStatic(AgentCharacterGatewayRuntime.class)) {
                primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(actions);
                partyQuests.when(AgentPartyQuestGatewayRuntime::partyQuest).thenReturn(lmpq);
                characters.when(AgentCharacterGatewayRuntime::characters).thenReturn(mock(CharacterGateway.class));

                // farm() off the maze stops the Agent through the primitive gateway installed now.
                AgentLmpqSession idleRun = farmingSession();
                Character idle = agent(IDLE, AgentLmpqDefinition.RECRUIT_MAP);
                AgentRuntimeEntry idleEntry = mock(AgentRuntimeEntry.class);
                AgentLmpqCoordinator.tickMember(idleRun, idleEntry, idle, 5_000L);
                verify(actions).stop(idleEntry);

                // farm() in its own room loots through the party-quest gateway installed now.
                AgentLmpqSession workRun = farmingSession();
                workRun.member(WORKER).assignTargetRoom(6);
                workRun.rooms().reserve(6, WORKER, 5_000L);
                Character worker = agent(WORKER, AgentLmpqDefinition.mapForRoom(6));
                when(lmpq.lootNearby(worker, Set.of(AgentLmpqDefinition.COUPON))).thenReturn(true);
                AgentLmpqCoordinator.tickMember(workRun, mock(AgentRuntimeEntry.class), worker, 5_000L);
                verify(lmpq).lootNearby(worker, Set.of(AgentLmpqDefinition.COUPON));
            }
        }
    }

    private static AgentLmpqSession farmingSession() {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 7L, HUMAN, 3, 1_000L);
        session.addMember(HUMAN, AgentLmpqMemberState.MemberType.HUMAN);
        session.addMember(IDLE, AgentLmpqMemberState.MemberType.AGENT);
        session.addMember(WORKER, AgentLmpqMemberState.MemberType.AGENT);
        session.setLeadership(HUMAN, IDLE);
        session.transition(AgentLmpqSession.Phase.FARMING, 2_000L);
        return session;
    }

    private static Character agent(int id, int mapId) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn("Agent" + id);
        when(agent.getMapId()).thenReturn(mapId);
        return agent;
    }
}
