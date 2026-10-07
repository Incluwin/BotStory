package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6g LPQ C4.2 (rewritten from Step 1's provisional "stageN" spans, as that plan said it would be): a companion run's
 * pace in the keys of {@link AgentLpqPaceProfile}, cut at its stage phases and pace marks. Every boundary; a stage a
 * checkpoint skipped has no span; a span whose end was not seen runs to the next seen boundary and is incomplete
 * ("open"), one that ends before it starts is "out-of-order"; stage 8's work and drawn content are its judged checks
 * (the accepted one included); Alishar's span works his HP.
 */
class AgentLpqPaceReportTest {
    private static final int BEN = 743_100;
    private static final long S = 1_000L;

    private static AgentLpqSession session() {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, BEN, 6, 1_000L);
        session.addMember(BEN, AgentLpqMemberState.MemberType.HUMAN);
        session.addMember(743_101, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(BEN, 743_101);
        session.setCompanionAdopted(true);
        return session;
    }

    private static AgentPqPace.Span span(String key, long fromS, long toS, double work, boolean complete) {
        return new AgentPqPace.Span(key, fromS * S, toS * S, work, complete);
    }

    /** A whole cleared run, every boundary seen, one second apart from stage to stage plus the marks between. */
    private static AgentLpqSession aClearedRun() {
        AgentLpqSession session = session();
        AgentLpqPaceMarks marks = session.pace();
        session.transition(AgentLpqSession.Phase.ENTERING, 2 * S);
        session.transition(AgentLpqSession.Phase.STAGE_1, 5 * S);
        marks.markEntered(List.of(), List.of(BEN, 743_101), 5 * S);
        marks.markPassesAtFeet(1, 100 * S);
        session.transition(AgentLpqSession.Phase.STAGE_2, 130 * S);
        marks.markPassesAtFeet(2, 230 * S);
        session.transition(AgentLpqSession.Phase.STAGE_3, 250 * S);
        marks.markPassesAtFeet(3, 400 * S);
        session.transition(AgentLpqSession.Phase.STAGE_4, 420 * S);
        marks.markPassesAtFeet(4, 600 * S);
        session.transition(AgentLpqSession.Phase.STAGE_5, 630 * S);
        marks.markPassesAtFeet(5, 870 * S);
        session.transition(AgentLpqSession.Phase.STAGE_6, 900 * S);
        session.transition(AgentLpqSession.Phase.STAGE_7, 1_050 * S);
        marks.markPassesAtFeet(7, 1_170 * S);
        session.transition(AgentLpqSession.Phase.STAGE_8, 1_200 * S);
        marks.markJudgedCheck(1_230 * S);
        marks.markJudgedCheck(1_240 * S);
        marks.markJudgedCheck(1_250 * S);
        session.transition(AgentLpqSession.Phase.STAGE_9, 1_260 * S);
        marks.markAlisharSeen(125_000L, 1_275 * S);
        marks.markAlisharDown(1_375 * S);
        marks.markKeyAtFeet(1_390 * S);
        marks.markCleared(1_415 * S);
        session.transition(AgentLpqSession.Phase.BONUS, 1_416 * S);
        return session;
    }

    @Test
    void everyBoundaryOfAClearedRun() {
        AgentLpqSession session = aClearedRun();

        AgentPqPace pace = AgentLpqPaceReport.of(session, 1_500 * S);

        assertEquals("lpq", pace.questKey());
        assertEquals(session.sessionId(), pace.sessionId());
        assertEquals(BEN, pace.leaderId());
        assertEquals(1_000L, pace.startedAtMs(), "the Yes: the run's admission");
        assertEquals(1_415 * S, pace.clearedAtMs());
        assertTrue(pace.cleared());
        assertEquals(List.of(
                new AgentPqPace.Span("yes", 1_000L, 5 * S, 0.0d, true),
                span("s1.collect", 5, 100, 0, true), span("s1.handin", 100, 130, 0, true),
                span("s2.collect", 130, 230, 0, true), span("s2.handin", 230, 250, 0, true),
                span("s3.collect", 250, 400, 0, true), span("s3.handin", 400, 420, 0, true),
                span("s4.rooms", 420, 600, 0, true), span("s4.handin", 600, 630, 0, true),
                span("s5.rooms", 630, 870, 0, true), span("s5.handin", 870, 900, 0, true),
                span("s6.climb", 900, 1_050, 0, true),
                span("s7.fight", 1_050, 1_170, 0, true), span("s7.handin", 1_170, 1_200, 0, true),
                span("s8.entry", 1_200, 1_230, 0, true), span("s8.checks", 1_230, 1_260, 3, true),
                span("s9.ratz", 1_260, 1_275, 0, true), span("s9.boss", 1_275, 1_375, 125_000, true),
                span("s9.key", 1_375, 1_415, 0, true)), pace.spans());
        assertEquals(AgentLpqPaceProfile.load(125_000L).segmentKeys(6),
                pace.spans().stream().map(AgentPqPace.Span::key).toList(), "the profile's leaf keys, in order");
        assertEquals(Map.of("s8.checks", 3), pace.drawn());
        assertEquals(List.of(new AgentPqPace.MemberRun(BEN, 0, false, 0, 0),
                new AgentPqPace.MemberRun(743_101, 0, false, 0, 0)), pace.runs());
    }

    @Test
    void aStageACheckpointSkippedHasNoSpanAndItsPredecessorRunsToTheNextSeenBoundary() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.STAGE_1, 5 * S);
        session.pace().markPassesAtFeet(1, 60 * S);
        session.transition(AgentLpqSession.Phase.STAGE_3, 80 * S); // a GM checkpoint past stage 2
        session.transition(AgentLpqSession.Phase.STAGE_8, 120 * S); // and past stages 4-7

        AgentPqPace pace = AgentLpqPaceReport.of(session, 130 * S);

        assertEquals(List.of("yes", "s1.collect", "s1.handin", "s3.collect", "s8.entry"),
                pace.spans().stream().map(AgentPqPace.Span::key).toList());
        assertEquals(span("s1.handin", 60, 80, 0, false), pace.span("s1.handin"), "its own end (stage 2) unseen");
        assertEquals(span("s3.collect", 80, 120, 0, false), pace.span("s3.collect"));
        assertEquals(span("s8.entry", 120, 130, 0, false), pace.span("s8.entry"), "open to the report's time");
        assertNull(pace.span("s2.collect"));
        assertEquals(Map.of(), pace.drawn());
    }

    @Test
    void anEndBeforeItsStartIsOutOfOrder() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.STAGE_1, 5 * S);
        session.transition(AgentLpqSession.Phase.STAGE_2, 50 * S);
        session.pace().markPassesAtFeet(1, 70 * S); // seen only after stage 2 began

        List<AgentLpqPaceReport.SpanReport> spans = AgentLpqPaceReport.spans(session, session.pace().snapshot(),
                90 * S);

        AgentLpqPaceReport.SpanReport handin = spans.stream().filter(span -> span.key().equals("s1.handin"))
                .findFirst().orElseThrow();
        assertEquals(new AgentLpqPaceReport.SpanReport("s1.handin", 70 * S, 50 * S, 0.0d, false, "out-of-order"),
                handin);
    }

    @Test
    void stageEightsWorkAndDrawAreItsJudgedChecksAndAlisharsIsHisHp() {
        AgentLpqSession session = aClearedRun();
        session.pace().markJudgedCheck(1_300 * S); // the leader asked again after the accept: still counted once seen

        AgentPqPace pace = AgentLpqPaceReport.of(session, 1_500 * S);

        assertEquals(4.0d, pace.span("s8.checks").work(), 1e-12);
        assertEquals(Map.of("s8.checks", 4), pace.drawn());
        assertEquals(125_000.0d, pace.span("s9.boss").work(), 1e-12);
    }

    @Test
    void deathsAndTapOutsAreOnRecordPerMember() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.STAGE_1, 5 * S);
        session.pace().markEntered(List.of(), List.of(BEN, 743_101, 743_102), 5 * S);
        session.pace().markDown(743_102);
        session.pace().markDown(743_102);
        session.pace().markTappedOut(743_102);

        AgentPqPace pace = AgentLpqPaceReport.of(session, 9 * S);

        assertEquals(List.of(new AgentPqPace.MemberRun(BEN, 0, false, 0, 0),
                new AgentPqPace.MemberRun(743_101, 0, false, 0, 0),
                new AgentPqPace.MemberRun(743_102, 2, true, 0, 0)), pace.runs());
    }

    @Test
    void eachSpanIsOneLineBesideItsTheoryTimeWhileTheProfileIsWarm() {
        AgentLpqSession session = session();
        session.transition(AgentLpqSession.Phase.STAGE_1, 5 * S);
        session.pace().markPassesAtFeet(1, 65_250L);
        try (LpqLogLines log = new LpqLogLines(AgentLpqPaceReport.class)) {
            AgentLpqPaceReport.logSpans(session, 95 * S);

            String prefix = "LPQ pace span: session=" + session.sessionId();
            assertEquals(List.of(
                    prefix + " key=yes seconds=4.0 theorySeconds=-1 complete=true why=-",
                    prefix + " key=s1.collect seconds=60.3 theorySeconds=-1 complete=true why=-",
                    prefix + " key=s1.handin seconds=29.8 theorySeconds=-1 complete=false why=open"),
                    log.startingWith("LPQ pace span: "));
        }
        // Warm: the segment's time at the priors for the run's six members and its work.
        AgentLpqPaceProfile profile = AgentLpqPaceProfile.load(125_000L);
        List<AgentPqMemberPower> six = new ArrayList<>();
        for (int id = 1; id <= 6; id++) {
            six.add(new AgentPqMemberPower(id, "m" + id, id == 1, id == 1, 50, 100,
                    Map.of(9_300_012, 500.0d), Map.of(9_300_012, 0.9d), Map.of(9_300_012, 800.0d), 20.0d));
        }
        AgentPqPace pace = new AgentPqPace("lpq", "x", 1, 0L, 0L, List.of(), six, List.of(), Map.of());
        assertEquals(100.0d, AgentLpqPaceReport.theorySeconds(profile, pace,
                new AgentLpqPaceReport.SpanReport("s1.collect", 0L, 1L, 0.0d, true, "")), 1e-9);
        assertEquals(20.0d, AgentLpqPaceReport.theorySeconds(profile, pace,
                new AgentLpqPaceReport.SpanReport("s8.checks", 0L, 1L, 3.0d, true, "")), 1e-9,
                "two checks after the first at 10 s");
        assertEquals(-1.0d, AgentLpqPaceReport.theorySeconds(null, pace,
                new AgentLpqPaceReport.SpanReport("s1.collect", 0L, 1L, 0.0d, true, "")), 1e-12);
        assertEquals(-1.0d, AgentLpqPaceReport.theorySeconds(profile, new AgentPqPace("lpq", "x", 1, 0L, 0L,
                List.of(), List.of(), List.of(), Map.of()),
                new AgentLpqPaceReport.SpanReport("s1.collect", 0L, 1L, 0.0d, true, "")), 1e-12, "unread party");
    }
}
