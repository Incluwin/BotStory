package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6d LPQ step 1 (C6), a companion run's provisional pace: one span per stage the run entered ("stage1".."stage9"),
 * from the stage's first entry to the first entry of a later stage or the bonus; a stage whose end was not seen runs
 * to the report's time and is incomplete. The clear is the bonus map's entry. Step 4's pace profile replaces the
 * keys; nothing consumes them yet.
 */
class AgentLpqPaceReportTest {
    private static final int BEN = 743_100;

    private static AgentLpqSession session() {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, BEN, 6, 1_000L);
        session.addMember(BEN, AgentLpqMemberState.MemberType.HUMAN);
        session.addMember(743_101, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(BEN, 743_101);
        session.setCompanionAdopted(true);
        return session;
    }

    @Test
    void eachEnteredStageIsASpanToTheNextStagesFirstEntry() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.ENTERING, 2_000L);
        session.transition(AgentLpqSession.Phase.STAGE_1, 5_000L);
        session.transition(AgentLpqSession.Phase.STAGE_2, 65_000L);
        session.transition(AgentLpqSession.Phase.STAGE_3, 125_500L);

        AgentPqPace pace = AgentLpqPaceReport.of(session, 200_000L);

        assertEquals("lpq", pace.questKey());
        assertEquals(session.sessionId(), pace.sessionId());
        assertEquals(BEN, pace.leaderId());
        assertEquals(5_000L, pace.startedAtMs(), "the run starts with stage 1");
        assertEquals(0L, pace.clearedAtMs());
        assertEquals(List.of(
                new AgentPqPace.Span("stage1", 5_000L, 65_000L, 0.0d, true),
                new AgentPqPace.Span("stage2", 65_000L, 125_500L, 0.0d, true),
                new AgentPqPace.Span("stage3", 125_500L, 200_000L, 0.0d, false)), pace.spans());
        assertEquals(List.of(), pace.members());
        assertEquals(List.of(), pace.runs());
        assertEquals(Map.of(), pace.drawn());
    }

    @Test
    void aSkippedStageIsAbsentAndItsPredecessorRunsToTheNextEnteredStage() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.STAGE_1, 5_000L);
        session.transition(AgentLpqSession.Phase.STAGE_3, 50_000L);
        session.transition(AgentLpqSession.Phase.STAGE_8, 80_000L); // a GM checkpoint

        AgentPqPace pace = AgentLpqPaceReport.of(session, 90_000L);

        assertEquals(List.of("stage1", "stage3", "stage8"), pace.spans().stream().map(AgentPqPace.Span::key).toList());
        assertEquals(new AgentPqPace.Span("stage3", 50_000L, 80_000L, 0.0d, true), pace.span("stage3"));
        assertNull(pace.span("stage4"));
    }

    @Test
    void theClearIsTheBonusEntryAndTheLastStageEndsThere() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.STAGE_1, 5_000L);
        session.transition(AgentLpqSession.Phase.STAGE_9, 1_000_000L);
        session.transition(AgentLpqSession.Phase.BONUS, 1_300_000L);
        session.transition(AgentLpqSession.Phase.CLAIMING_REWARD, 1_360_000L);
        session.complete(1_400_000L);

        AgentPqPace pace = AgentLpqPaceReport.of(session, 1_400_000L);

        assertEquals(1_300_000L, pace.clearedAtMs());
        assertTrue(pace.cleared());
        assertEquals(new AgentPqPace.Span("stage9", 1_000_000L, 1_300_000L, 0.0d, true), pace.span("stage9"));
        assertEquals(2, pace.spans().size());
    }

    @Test
    void stageEightReportsTheChecksItTook() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.STAGE_1, 5_000L);
        assertEquals(Map.of(), AgentLpqPaceReport.of(session, 6_000L).drawn());

        session.transition(AgentLpqSession.Phase.STAGE_8, 700_000L);
        session.advanceStage8(710_000L);
        session.advanceStage8(720_000L);

        assertEquals(Map.of("stage8Checks", 3), AgentLpqPaceReport.of(session, 730_000L).drawn());
    }

    @Test
    void aRunThatNeverReachedStageOneStartsAtItsAdmission() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.ENTERING, 2_000L);

        AgentPqPace pace = AgentLpqPaceReport.of(session, 9_000L);

        assertEquals(1_000L, pace.startedAtMs());
        assertEquals(List.of(), pace.spans());
    }

    @Test
    void eachSpanIsOneLineInThePaceSpanShape() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.STAGE_1, 5_000L);
        session.transition(AgentLpqSession.Phase.STAGE_2, 65_250L);
        try (LpqLogLines log = new LpqLogLines(AgentLpqPaceReport.class)) {
            AgentLpqPaceReport.logSpans(session, 95_000L);

            String prefix = "LPQ pace span: session=" + session.sessionId();
            assertEquals(List.of(
                    prefix + " key=stage1 seconds=60.3 theorySeconds=-1 complete=true why=-",
                    prefix + " key=stage2 seconds=29.8 theorySeconds=-1 complete=false why=open"),
                    log.startingWith("LPQ pace span: "));
        }
    }
}
