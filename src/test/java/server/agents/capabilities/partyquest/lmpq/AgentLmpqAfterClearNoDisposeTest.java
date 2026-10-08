package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 2 (all modes): past the clear (the event's own cleared flag) no release takes a human out of the
 * event or disposes it under him. A failure lets only the Agents go through the event's exit, and the event is
 * disposed only when nobody is left in it (an all-Agent stock run ends as before). A run that makes no progress in
 * REWARD fails after REWARD_STALL_MS (120 s) as "LMPQ reward stalled".
 */
class AgentLmpqAfterClearNoDisposeTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentLmpqSession cleared(AgentLmpqSession.Mode mode, Character... members) {
        AgentLmpqSession session = run.session(mode, AgentLmpqSession.Phase.REWARD, members);
        when(run.event.isEventCleared()).thenReturn(true);
        return session;
    }

    @Test
    void aStockFailureAfterTheClearLeavesTheHumanAndTheEvent() {
        AgentLmpqSession session = cleared(AgentLmpqSession.Mode.HUMAN_LEADER, ben, mira, kes, pel);
        List<Character> held = run.livePlayers(ben, mira, kes, pel);
        session.fail("LMPQ reward stalled", 5_000L);

        AgentLmpqRuntime.tick(run.entry(mira), mira, 6_000L);

        assertEquals(List.of(ben), held);
        verify(run.event, never()).dispose();
        for (Character agent : List.of(mira, kes, pel)) verify(run.party).leaveCurrentParty(agent);
        verify(run.party, never()).leaveCurrentParty(ben);
        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void anAllAgentStockRunEndsAsBefore() {
        AgentLmpqSession session = cleared(AgentLmpqSession.Mode.AUTONOMOUS, mira, kes, pel);
        List<Character> held = run.livePlayers(mira, kes, pel);
        session.fail("LMPQ reward stalled", 5_000L);

        AgentLmpqRuntime.tick(run.entry(mira), mira, 6_000L);

        assertTrue(held.isEmpty());
        verify(run.event, times(1)).dispose();
    }

    @Test
    void aForcedStopAfterTheClearTakesOnlyTheAgentsOut() {
        cleared(AgentLmpqSession.Mode.HUMAN_LEADER, ben, mira, kes, pel);
        List<Character> held = run.livePlayers(ben, mira, kes, pel);

        AgentLmpqRuntime.forceStop(kes.getId(), "Agent runtime removed", 6_000L);

        assertEquals(List.of(ben), held);
        verify(run.event, never()).dispose();
    }

    @Test
    void aStopRequestAfterTheClearTakesTheAgentsOutToo() {
        AgentLmpqSession session = cleared(AgentLmpqSession.Mode.HUMAN_LEADER, ben, mira, kes, pel);
        List<Character> held = run.livePlayers(ben, mira, kes, pel);
        session.fail("LMPQ reward stalled", 5_000L);

        assertTrue(AgentLmpqRuntime.requestStop(pel.getId(), "owner logged out", 6_000L));

        assertEquals(List.of(ben), held);
        verify(run.event, never()).dispose();
    }

    @Test
    void aCompletedRunIsNeverDisposedAfterTheClear() {
        AgentLmpqSession session = cleared(AgentLmpqSession.Mode.HUMAN_LEADER, ben, mira, kes, pel);
        session.complete(5_000L);

        AgentLmpqRuntime.forceStop(kes.getId(), "Agent runtime removed", 6_000L);

        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        assertEquals(AgentLmpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void aRewardWithNoProgressFailsAfterTheRewardStall() {
        AgentLmpqSession session = cleared(AgentLmpqSession.Mode.HUMAN_LEADER, ben, mira, kes, pel);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);

        AgentLmpqCoordinator.tickSession(session, 10_000L);
        AgentLmpqCoordinator.tickSession(session, 129_999L);
        assertFalse(session.terminal());

        AgentLmpqCoordinator.tickSession(session, 130_000L);
        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        assertEquals("LMPQ reward stalled", session.failure());
    }

    @Test
    void farmingKeepsItsOwnStall() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);

        AgentLmpqCoordinator.tickSession(session, 10_000L);
        AgentLmpqCoordinator.tickSession(session, 130_000L);
        assertFalse(session.terminal(), "a human-led run may stand still four minutes before the clear");
        AgentLmpqCoordinator.tickSession(session, 250_000L);
        assertEquals("LMPQ stalled in FARMING", session.failure());
    }
}
