package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6h LMPQ step 1, C5: a companion run's provisional pace record. One fixed span per phase the run entered (farm,
 * regroup, clear, reward), each to the first later boundary it reached; a span whose end was not seen runs to the
 * report and is incomplete ("open"); the leader's coupons at the clearing are drawn content.
 */
class AgentLmpqPaceReportTest {
    private static final int BEN = 781_900;

    private static AgentLmpqSession session() {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 7L, BEN, 3, 1_000L);
        session.addMember(BEN, AgentLmpqMemberState.MemberType.HUMAN);
        session.addMember(781_901, AgentLmpqMemberState.MemberType.AGENT);
        session.addMember(781_902, AgentLmpqMemberState.MemberType.AGENT);
        session.setLeadership(BEN, 781_901);
        session.setCompanionAdopted(true);
        return session;
    }

    private static AgentPqPace.Span span(String key, long from, long to, boolean complete) {
        return new AgentPqPace.Span(key, from, to, 0.0d, complete);
    }

    @Test
    void aClearedRunHasEveryFixedSpanAndItsCoupons() {
        AgentLmpqSession session = session();
        session.transition(AgentLmpqSession.Phase.FARMING, 10_000L);
        session.transition(AgentLmpqSession.Phase.REGROUPING, 400_000L);
        session.transition(AgentLmpqSession.Phase.CLEARING, 500_000L);
        session.setCouponsAtClearing(34);
        session.transition(AgentLmpqSession.Phase.REWARD, 560_000L);
        session.complete(600_000L);

        AgentPqPace pace = AgentLmpqPaceReport.of(session, 700_000L);

        assertEquals("lmpq", pace.questKey());
        assertEquals(session.sessionId(), pace.sessionId());
        assertEquals(BEN, pace.leaderId());
        assertEquals(10_000L, pace.startedAtMs());
        assertEquals(560_000L, pace.clearedAtMs());
        assertEquals(List.of(span("farm", 10_000L, 400_000L, true), span("regroup", 400_000L, 500_000L, true),
                span("clear", 500_000L, 560_000L, true), span("reward", 560_000L, 600_000L, true)), pace.spans());
        assertEquals(Map.of("coupons", 34), pace.drawn());
        assertEquals(List.of(), pace.members());
        assertEquals(List.of(), pace.runs());
    }

    @Test
    void aSpanWhoseEndWasNotSeenIsOpenAndRunsToTheReport() {
        AgentLmpqSession session = session();
        session.transition(AgentLmpqSession.Phase.FARMING, 10_000L);
        session.transition(AgentLmpqSession.Phase.REGROUPING, 400_000L);
        session.fail("LMPQ stalled in REGROUPING", 450_000L);

        AgentPqPace pace = AgentLmpqPaceReport.of(session, 700_000L);

        assertEquals(List.of(span("farm", 10_000L, 400_000L, true), span("regroup", 400_000L, 700_000L, false)),
                pace.spans());
        assertEquals(0L, pace.clearedAtMs());
        assertFalse(pace.cleared());
        assertEquals(Map.of(), pace.drawn());
        List<AgentLmpqPaceReport.SpanReport> spans = AgentLmpqPaceReport.spans(session, 700_000L);
        assertEquals("open", spans.get(1).why());
        assertEquals("", spans.get(0).why());
    }

    @Test
    void aSkippedPhaseClosesAtTheNextBoundaryReached() {
        AgentLmpqSession session = session();
        session.transition(AgentLmpqSession.Phase.FARMING, 10_000L);
        session.transition(AgentLmpqSession.Phase.REWARD, 300_000L);

        assertEquals(List.of(span("farm", 10_000L, 300_000L, true), span("reward", 300_000L, 320_000L, false)),
                AgentLmpqPaceReport.of(session, 320_000L).spans());
    }

    @Test
    void aRunThatNeverFarmedStartsAtAdmissionAndHasNoSpans() {
        AgentLmpqSession session = session();
        AgentPqPace pace = AgentLmpqPaceReport.of(session, 50_000L);
        assertEquals(1_000L, pace.startedAtMs());
        assertTrue(pace.spans().isEmpty());
    }

    @Test
    void eachSpanHasItsLine() {
        AgentLmpqSession session = session();
        session.transition(AgentLmpqSession.Phase.FARMING, 10_000L);
        session.transition(AgentLmpqSession.Phase.REGROUPING, 400_000L);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqPaceReport.class)) {
            AgentLmpqPaceReport.logSpans(session, 700_000L);
            String id = session.sessionId();
            assertEquals(List.of(
                    "LMPQ pace span: session=" + id + " key=farm seconds=390.0 theorySeconds=-1 complete=true why=-",
                    "LMPQ pace span: session=" + id + " key=regroup seconds=300.0 theorySeconds=-1 complete=false"
                            + " why=open"),
                    lines.startingWith("LMPQ pace span:"));
        }
    }
}
