package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ base fix OB1: a run that completes is released by the coordinator at once, as LPQ and HPQ release theirs.
 * Before, tickReward only marked the session COMPLETED and nothing in the tick released it: its members stayed
 * reserved, the host heard nothing, and only a later activity admission's stop request let it go.
 */
class AgentOpqCompletionReleaseTest {
    private final OpqRunFixture run = new OpqRunFixture();

    @AfterEach
    void tearDown() {
        run.close();
    }

    private static void outside(Character... members) {
        for (Character member : members) when(member.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
    }

    @Test
    void aStockRunIsReleasedTheTickItCompletes() {
        Character lea = run.companion(11, "Lea");
        Character mira = run.companion(12, "Mira");
        Character kes = run.companion(13, "Kes");
        Character pel = run.companion(14, "Pel");
        Character hal = run.companion(15, "Hal");
        Character gus = run.companion(16, "Gus");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.CLAIMING_REWARD,
                lea, mira, kes, pel, hal, gus);
        outside(lea, mira, kes, pel, hal, gus);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqTerminationService.class)) {
            AgentOpqCoordinator.tickSession(session, 10_000L);
            assertEquals(List.of("OPQ session released: session=" + session.sessionId()
                    + " outcome=completed members=6 companionRun=false"), lines.startingWith("OPQ session released:"));
            assertEquals(List.of(), lines.startingWith("OPQ session failure:"));
        }

        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        for (Character agent : new Character[]{lea, mira, kes, pel, hal, gus}) {
            assertNull(AgentOpqSessionRegistry.forMember(agent.getId()), "released " + agent.getName());
            verify(run.party).leaveCurrentParty(agent);
        }
        verify(run.event, never()).exitPlayer(any());
        verify(run.event, never()).dispose();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(true), eq("completed"), eq(10_000L), isNull()), times(1));
    }

    @Test
    void aCompanionRunIsReleasedAsACompletionWithItsPaceAndItsPartyKept() {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character kes = run.companion(3, "Kes");
        Character pel = run.companion(4, "Pel");
        Character hal = run.companion(5, "Hal");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                AgentOpqSession.Phase.CLAIMING_REWARD, ben, mira, kes, pel, hal);
        outside(ben, mira, kes, pel, hal);

        AgentOpqCoordinator.tickSession(session, 10_000L);
        AgentOpqCoordinator.tickSession(session, 11_000L); // nothing left to release

        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        assertNull(AgentOpqSessionRegistry.forOperator(ben.getId()));
        verify(run.party, never()).leaveCurrentParty(any());
        verify(run.event, never()).dispose();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(true), eq("completed"), eq(10_000L), notNull()), times(1));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong(), any()), times(1));
    }

    @Test
    void aRunWithAMemberStillInsideIsNotReleased() {
        Character lea = run.companion(11, "Lea");
        Character mira = run.companion(12, "Mira");
        Character kes = run.companion(13, "Kes");
        Character pel = run.companion(14, "Pel");
        Character hal = run.companion(15, "Hal");
        Character gus = run.companion(16, "Gus");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.CLAIMING_REWARD,
                lea, mira, kes, pel, hal, gus);
        outside(lea, mira, kes, pel, hal);
        when(gus.getMapId()).thenReturn(AgentOpqDefinition.CLEAR_MAP); // still asking Minerva

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.CLAIMING_REWARD, session.phase());
        assertEquals(session, AgentOpqSessionRegistry.forMember(gus.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong(), any()), never());
    }
}
