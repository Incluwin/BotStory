package server.agents.capabilities.partyquest.opq;

import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6h OPQ step 1 (C5): a companion run's pace, cut from when each part of the run was first entered (provisional keys
 * until Step 4's profile): entrance, rooms, statue, garden, minerva and claim, the last to the completion. A part
 * whose end was not seen is open; a part the run skipped (a checkpoint) has no span and the one before it runs to the
 * next part it entered. The run started at the entrance and cleared when the coordinator first saw Minerva freed.
 */
class AgentOpqPaceReportTest {

    private static AgentOpqSession session() {
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, 1, 5, 1_000L);
        session.addMember(1, AgentOpqMemberState.MemberType.HUMAN);
        for (int id = 2; id <= 5; id++) session.addMember(id, AgentOpqMemberState.MemberType.AGENT);
        session.setLeadership(1, 2);
        session.setCompanionAdopted(true);
        return session;
    }

    @Test
    void aClearedRunHasOneCompleteSpanPerPart() {
        AgentOpqSession session = session();
        session.transition(AgentOpqSession.Phase.ENTERING, 2_000L);
        session.transition(AgentOpqSession.Phase.ENTRANCE, 10_000L);
        session.transition(AgentOpqSession.Phase.SPLIT_ROOMS, 130_000L);
        session.transition(AgentOpqSession.Phase.RESTORING_STATUE, 900_000L);
        session.transition(AgentOpqSession.Phase.GARDEN, 960_000L);
        session.transition(AgentOpqSession.Phase.RESTORING_MINERVA, 1_500_000L);
        session.markClearSeen(1_530_000L);
        session.transition(AgentOpqSession.Phase.CLAIMING_REWARD, 1_560_000L);
        session.complete(1_620_000L);

        AgentPqPace pace = AgentOpqPaceReport.of(session, 1_700_000L);

        assertEquals("opq", pace.questKey());
        assertEquals(session.sessionId(), pace.sessionId());
        assertEquals(1, pace.leaderId());
        assertEquals(10_000L, pace.startedAtMs(), "the run starts at the entrance");
        assertEquals(1_530_000L, pace.clearedAtMs(), "cleared when Minerva was first seen freed");
        assertTrue(pace.cleared());
        assertEquals(List.of(
                new AgentPqPace.Span("entrance", 10_000L, 130_000L, 0.0d, true),
                new AgentPqPace.Span("rooms", 130_000L, 900_000L, 0.0d, true),
                new AgentPqPace.Span("statue", 900_000L, 960_000L, 0.0d, true),
                new AgentPqPace.Span("garden", 960_000L, 1_500_000L, 0.0d, true),
                new AgentPqPace.Span("minerva", 1_500_000L, 1_560_000L, 0.0d, true),
                new AgentPqPace.Span("claim", 1_560_000L, 1_620_000L, 0.0d, true)), pace.spans());
        assertEquals(Map.of(), pace.drawn(), "no puzzle counter moved in a human-led run");
        assertEquals(List.of(), pace.members());
        assertEquals(List.of(), pace.runs());
    }

    @Test
    void aRunThatEndedEarlyLeavesItsLastPartOpenAndACheckpointSkipsParts() {
        AgentOpqSession session = session();
        session.transition(AgentOpqSession.Phase.ENTRANCE, 10_000L);
        session.transition(AgentOpqSession.Phase.GARDEN, 70_000L); // '!opqtest checkpoint garden'
        session.fail("OPQ leader unavailable", 400_000L);

        List<AgentOpqPaceReport.SpanReport> spans = AgentOpqPaceReport.spans(session, 450_000L);

        assertEquals(List.of(
                new AgentOpqPaceReport.SpanReport("entrance", 10_000L, 70_000L, true, ""),
                new AgentOpqPaceReport.SpanReport("garden", 70_000L, 450_000L, false, "open")), spans);
        AgentPqPace pace = AgentOpqPaceReport.of(session, 450_000L);
        assertFalse(pace.cleared());
        assertNull(pace.span("rooms"));
        assertFalse(pace.span("garden").complete());
    }

    @Test
    void aRunThatNeverEnteredStartsWhenItWasOpenedAndHasNoSpan() {
        AgentOpqSession session = session();
        AgentPqPace pace = AgentOpqPaceReport.of(session, 5_000L);
        assertEquals(1_000L, pace.startedAtMs());
        assertEquals(List.of(), pace.spans());
    }

    @Test
    void thePuzzleCountersAreDrawnOnlyWhenTheyMoved() {
        AgentOpqSession session = session();
        session.recordSealedVerdict(false); // Eak's answers to two wrong checks (2026-10-17: AgentOpqSealedSearch)
        session.advanceSealedAttempt(2_000L);
        session.recordSealedVerdict(true);
        session.advanceSealedAttempt(3_000L);
        assertEquals(Map.of("sealedWrongChecks", 2), AgentOpqPaceReport.of(session, 5_000L).drawn());
        session.advanceWayUpLeverAttempt(4_000L);
        assertEquals(Map.of("sealedWrongChecks", 2, "leverChecks", 1), AgentOpqPaceReport.of(session, 5_000L).drawn());
    }

    @Test
    void eachSpanHasOneLine() {
        AgentOpqSession session = session();
        session.transition(AgentOpqSession.Phase.ENTRANCE, 10_000L);
        session.transition(AgentOpqSession.Phase.SPLIT_ROOMS, 72_500L);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqPaceReport.class)) {
            AgentOpqPaceReport.logSpans(session, 100_000L);
            assertEquals(List.of(
                    "OPQ pace span: session=" + session.sessionId()
                            + " key=entrance seconds=62.5 theorySeconds=-1 complete=true why=-",
                    "OPQ pace span: session=" + session.sessionId()
                            + " key=rooms seconds=27.5 theorySeconds=-1 complete=false why=open"),
                    lines.startingWith("OPQ pace span:"));
        }
    }

    @Test
    void theCoordinatorRecordsTheFirstPassThatSawTheClear() {
        try (OpqRunFixture run = new OpqRunFixture()) {
            AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS,
                    AgentOpqSession.Phase.RESTORING_MINERVA, run.companion(11, "Lea"), run.companion(12, "Mira"),
                    run.companion(13, "Kes"), run.companion(14, "Pel"), run.companion(15, "Hal"),
                    run.companion(16, "Gus"));

            AgentOpqCoordinator.tickSession(session, 10_000L);
            assertEquals(0L, session.clearSeenAtMs(), "Minerva not yet free");
            when(run.event.getProperty("statusStg8")).thenReturn("1");
            AgentOpqCoordinator.tickSession(session, 11_000L);
            AgentOpqCoordinator.tickSession(session, 12_000L);

            assertEquals(11_000L, session.clearSeenAtMs());
            assertEquals(11_000L, AgentOpqPaceReport.of(session, 13_000L).clearedAtMs());
        }
    }
}
