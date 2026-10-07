package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6h LMPQ step 1, C1 pin (green on the base and after): how a run that is not a companion run is released, by the
 * termination service and through each runtime entry (the tick, a stop request, a forced stop). The companion seam
 * changes the release for companion runs only; these runs must not notice.
 */
class AgentLmpqStockReleasePinTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @BeforeEach
    void childFinishedReportsWithoutAPace() {
        // The four-argument report is the five-argument one with no pace; either form is recorded as the latter.
        run.lifecycle.when(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong())).thenCallRealMethod();
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aStockFailureExitsEveryoneDisposesAndTakesEveryAgentOutOfTheParty() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);

        AgentLmpqTerminationService.release(session, "LMPQ event timer expired", 5_000L, true);

        for (Character member : new Character[]{ben, mira, kes, pel}) verify(run.event).exitPlayer(member);
        verify(run.event).dispose();
        for (Character agent : new Character[]{mira, kes, pel}) {
            verify(run.party).leaveCurrentParty(agent);
            verify(run.actions).stop(run.entry(agent));
        }
        verify(run.party, never()).leaveCurrentParty(ben);
        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        assertEquals("LMPQ event timer expired", session.failure());
        assertNull(session.eventInstance());
        assertNull(AgentLmpqSessionRegistry.forMember(ben.getId()));
        String id = session.sessionId();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(id), eq(false), eq("LMPQ event timer expired"), eq(5_000L), isNull()));
    }

    @Test
    void aStockCompletionLeavesThePartyAndDisposesNothing() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.REWARD,
                ben, mira, kes, pel);
        session.complete(4_000L);

        AgentLmpqTerminationService.release(session, "", 5_000L, false);

        for (Character agent : new Character[]{mira, kes, pel}) verify(run.party).leaveCurrentParty(agent);
        verify(run.party, never()).leaveCurrentParty(ben);
        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        assertEquals(AgentLmpqSession.Phase.COMPLETED, session.phase());
        String id = session.sessionId();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(id), eq(true), eq(""), eq(5_000L), isNull()));
    }

    @Test
    void anObservationRunsAgentsLeaveTheirPartyToo() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.TEST_OBSERVATION,
                AgentLmpqSession.Phase.REGROUPING, mira, kes, pel);

        AgentLmpqTerminationService.release(session, "stopped by operator", 5_000L, true);

        for (Character agent : new Character[]{mira, kes, pel}) {
            verify(run.party).leaveCurrentParty(agent);
            verify(run.event).exitPlayer(agent);
        }
        verify(run.event).dispose();
    }

    @Test
    void theTickReleasesAFailedStockRunWithADispose() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.AUTONOMOUS, AgentLmpqSession.Phase.FARMING,
                mira, kes, pel);
        session.fail("LMPQ stalled in FARMING", 4_000L);

        AgentLmpqRuntime.tick(run.entry(mira), mira, 5_000L);

        verify(run.event).exitPlayer(mira);
        verify(run.event).dispose();
        verify(run.party).leaveCurrentParty(kes);
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()));
    }

    @Test
    void theTickReleasesACompletedStockRunWithoutADispose() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.AUTONOMOUS, AgentLmpqSession.Phase.REWARD,
                mira, kes, pel);
        session.complete(4_000L);

        AgentLmpqRuntime.tick(run.entry(mira), mira, 5_000L);

        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        verify(run.party).leaveCurrentParty(pel);
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()));
    }

    @Test
    void aStopRequestReleasesAFailedStockRunWithoutADisposeAndRefusesALiveOne() {
        AgentLmpqSession live = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        assertEquals(false, AgentLmpqRuntime.requestStop(mira.getId(), "owner logged out", 5_000L));
        assertEquals(live, AgentLmpqSessionRegistry.forMember(mira.getId()));

        live.fail("LMPQ stalled in FARMING", 5_500L);
        assertEquals(true, AgentLmpqRuntime.requestStop(mira.getId(), "owner logged out", 6_000L));

        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        verify(run.party).leaveCurrentParty(mira);
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()));
    }

    @Test
    void aForcedStopDisposesAStockRunInAnyPhase() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.CLEARING,
                ben, mira, kes, pel);

        AgentLmpqRuntime.forceStop(kes.getId(), "Agent runtime removed", 5_000L);

        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
        verify(run.party).leaveCurrentParty(kes);
        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        assertEquals("Agent runtime removed", session.failure());
    }

    @Test
    void aSecondReleaseDoesNothing() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel);
        AgentLmpqTerminationService.release(session, "first", 5_000L, true);
        AgentLmpqTerminationService.release(session, "second", 6_000L, true);

        verify(run.event).dispose();
        assertEquals("first", session.failure());
    }
}
