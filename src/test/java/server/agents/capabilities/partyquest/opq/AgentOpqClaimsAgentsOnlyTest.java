package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ base fix OB4: the claim phase ends when every AGENT member has claimed (back at Wonky) or is out of the
 * event, never waiting for a human. A human in the jail bonus or still talking to Minerva used to hold the run, and
 * the companions with it, until he left the tower too.
 */
class AgentOpqClaimsAgentsOnlyTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession claiming(boolean companionRun) {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, companionRun,
                AgentOpqSession.Phase.CLAIMING_REWARD, ben, mira, kes, pel, hal);
        when(run.event.getProperty("statusStg8")).thenReturn("1");
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.CLEAR_MAP); // the human still in the tower
        return session;
    }

    @Test
    void theCompanionsClaimingEndTheRunWhileTheHumanStaysInside() {
        AgentOpqSession session = claiming(true);
        for (Character agent : new Character[]{mira, kes, pel, hal}) {
            when(agent.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        }

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        assertNull(AgentOpqSessionRegistry.forMember(mira.getId()));
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    @Test
    void anAgentOutOfTheEventCountsAsOut() {
        AgentOpqSession session = claiming(true);
        for (Character agent : new Character[]{mira, kes, pel}) {
            when(agent.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        }
        // Hal left through the event's exit (unrewarded) and stands outside the instance.
        when(hal.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);
        when(hal.getEventInstance()).thenReturn(null);

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void anAgentStillInsideTheEventKeepsTheRunClaiming() {
        AgentOpqSession session = claiming(false);
        for (Character agent : new Character[]{mira, kes, pel}) {
            when(agent.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        }
        when(hal.getMapId()).thenReturn(AgentOpqDefinition.CLEAR_MAP); // still asking Minerva

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.CLAIMING_REWARD, session.phase());
        assertSame(session, AgentOpqSessionRegistry.forMember(hal.getId()));
    }

    @Test
    void aStockRunWithAHumanEndsTheSameWay() {
        AgentOpqSession session = claiming(false);
        for (Character agent : new Character[]{mira, kes, pel, hal}) {
            when(agent.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        }

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        verify(run.party).leaveCurrentParty(mira);
        verify(run.party, never()).leaveCurrentParty(ben);
    }
}
