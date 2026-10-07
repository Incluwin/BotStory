package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Step 5.3: a companion run's spans in the profile's order, cut at its marks. A span is reported once it has begun;
 * one whose end was not seen runs to the next boundary that was (or the report's time) and is incomplete ("open"); one
 * that ends before it begins is "out-of-order".
 */
class AgentEpqPaceReportTest {
    private static AgentEpqSession session() {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 7L, 1, 1_000L);
        session.addMember(1, AgentEpqMemberState.MemberType.HUMAN);
        session.addMember(2, AgentEpqMemberState.MemberType.AGENT);
        session.setLeadership(1, 2);
        return session;
    }

    private static String table(List<AgentEpqPaceReport.SpanReport> spans) {
        StringBuilder table = new StringBuilder();
        for (AgentEpqPaceReport.SpanReport span : spans) {
            table.append(span.key()).append(' ').append(span.fromMs()).append('-').append(span.toMs()).append(' ')
                    .append(span.complete() ? "complete" : span.why()).append('\n');
        }
        return table.toString();
    }

    @Test
    void aWholeRunIsTenCompleteSpans() {
        AgentEpqSession session = session();
        AgentEpqPaceMarks marks = session.pace();
        marks.markEntered(List.of(), List.of(1, 2), 3_000L);
        marks.markPhase(AgentEpqSession.Phase.STAGE_TWO, 170_000L);
        marks.markPhase(AgentEpqSession.Phase.STAGE_THREE, 275_000L);
        marks.markPhase(AgentEpqSession.Phase.STAGE_FOUR, 315_000L);
        marks.markMarbles(615_000L);
        marks.markPhase(AgentEpqSession.Phase.STAGE_FIVE, 645_000L);
        marks.markStone(810_000L);
        marks.markPhase(AgentEpqSession.Phase.BOSS, 830_000L);
        marks.markGolem(855_000L);
        marks.markCleared(1_265_000L);

        assertEquals("""
                yes 1000-3000 complete
                trees 3000-170000 complete
                bottles 170000-275000 complete
                maze 275000-315000 complete
                marbles 315000-615000 complete
                handin 615000-645000 complete
                climb 645000-810000 complete
                sprite 810000-830000 complete
                altar 830000-855000 complete
                golem 855000-1265000 complete
                """, table(AgentEpqPaceReport.spans(session, marks.snapshot(), 2_000_000L)));
    }

    @Test
    void aCheckpointJumpWithMissingMarksLeavesThoseSpansIncomplete() {
        AgentEpqSession session = session();
        AgentEpqPaceMarks marks = session.pace();
        marks.markEntered(List.of(), List.of(1, 2), 3_000L);
        // '!epqtest checkpoint climb': no stage 2, 3 or 4 marks, no marbles.
        marks.markPhase(AgentEpqSession.Phase.STAGE_FIVE, 20_000L);
        marks.markStone(180_000L);

        assertEquals("""
                yes 1000-3000 complete
                trees 3000-20000 open
                climb 20000-180000 complete
                sprite 180000-500000 open
                """, table(AgentEpqPaceReport.spans(session, marks.snapshot(), 500_000L)));
    }

    @Test
    void aSpanEndingBeforeItBeginsIsOutOfOrder() {
        AgentEpqSession session = session();
        AgentEpqPaceMarks marks = session.pace();
        marks.markEntered(List.of(), List.of(1, 2), 3_000L);
        marks.markPhase(AgentEpqSession.Phase.STAGE_FOUR, 300_000L);
        marks.markMarbles(250_000L); // he held twenty before the stage read STAGE_FOUR

        List<AgentEpqPaceReport.SpanReport> spans = AgentEpqPaceReport.spans(session, marks.snapshot(), 400_000L);

        AgentEpqPaceReport.SpanReport marbles = spans.stream().filter(span -> span.key().equals("marbles"))
                .findFirst().orElseThrow();
        assertEquals("out-of-order", marbles.why());
    }

    @Test
    void theRecordCarriesTheRunAndItsMembers() {
        AgentEpqSession session = session();
        session.pace().markEntered(List.of(), List.of(1, 2), 3_000L);
        session.pace().markDown(2);
        session.pace().markCleared(9_000L);

        AgentPqPace pace = AgentEpqPaceReport.of(session, 10_000L);

        assertEquals("epq", pace.questKey());
        assertEquals(session.sessionId(), pace.sessionId());
        assertEquals(1, pace.leaderId());
        assertEquals(1_000L, pace.startedAtMs());
        assertEquals(9_000L, pace.clearedAtMs());
        assertEquals(List.of(new AgentPqPace.MemberRun(1, 0, false, 0, 0), new AgentPqPace.MemberRun(2, 1, false, 0, 0)),
                pace.runs());
    }
}
