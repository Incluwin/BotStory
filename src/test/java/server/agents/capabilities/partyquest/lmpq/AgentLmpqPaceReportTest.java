package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A companion run's pace record under AgentLmpqPaceProfile's keys (P6h Step 4; Step 1's provisional farm, regroup,
 * clear and reward became farm, walk, handin, pietri and claim). One span per leaf segment the run entered, each to
 * the first later boundary it reached; the farm's work is the coupons secured at the regroup; the walk ends at the
 * leader's first arrival in the rendezvous room; a span whose end was not seen runs to the report and is incomplete
 * ("open"); the members are their power at the entry; the leader's coupons at the clearing are drawn content.
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
    void aClearedRunHasEverySpanTheWorkOfItsFarmAndItsCoupons() {
        AgentLmpqSession session = session();
        AgentPqMemberPower power = new AgentPqMemberPower(BEN, "Ben", true, true, 55, 130, Map.of(), Map.of(),
                Map.of(), 10.0d);
        session.recordPowersAtEntry(List.of(power));
        session.transition(AgentLmpqSession.Phase.FARMING, 10_000L);
        session.noteLeaderArrived(150_000L);
        session.setCouponsAtRegroup(37);
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
        assertEquals(List.of(new AgentPqPace.Span("farm", 10_000L, 400_000L, 37.0d, true),
                span("walk", 10_000L, 150_000L, true), span("handin", 400_000L, 500_000L, true),
                span("pietri", 500_000L, 560_000L, true), span("claim", 560_000L, 600_000L, true)), pace.spans());
        assertEquals(Map.of("coupons", 34), pace.drawn());
        assertEquals(List.of(power), pace.members());
        assertEquals(List.of(), pace.runs());
    }

    @Test
    void aSpanWhoseEndWasNotSeenIsOpenAndRunsToTheReport() {
        AgentLmpqSession session = session();
        session.transition(AgentLmpqSession.Phase.FARMING, 10_000L);
        session.setCouponsAtRegroup(30);
        session.transition(AgentLmpqSession.Phase.REGROUPING, 400_000L);
        session.fail("LMPQ stalled in REGROUPING", 450_000L);

        AgentPqPace pace = AgentLmpqPaceReport.of(session, 700_000L);

        assertEquals(List.of(new AgentPqPace.Span("farm", 10_000L, 400_000L, 30.0d, true),
                span("walk", 10_000L, 700_000L, false), span("handin", 400_000L, 700_000L, false)), pace.spans());
        assertEquals(0L, pace.clearedAtMs());
        assertFalse(pace.cleared());
        assertEquals(Map.of(), pace.drawn());
        List<AgentLmpqPaceReport.SpanReport> spans = AgentLmpqPaceReport.spans(session, 700_000L);
        assertEquals("open", spans.get(1).why(), "he never reached the rendezvous room");
        assertEquals("", spans.get(0).why());
    }

    @Test
    void anOpenFarmReportsNoWork() {
        AgentLmpqSession session = session();
        session.transition(AgentLmpqSession.Phase.FARMING, 10_000L);

        assertEquals(new AgentPqPace.Span("farm", 10_000L, 90_000L, 0.0d, false),
                AgentLmpqPaceReport.of(session, 90_000L).spans().getFirst());
    }

    @Test
    void aSkippedPhaseClosesAtTheNextBoundaryReached() {
        AgentLmpqSession session = session();
        session.transition(AgentLmpqSession.Phase.FARMING, 10_000L);
        session.noteLeaderArrived(20_000L);
        session.transition(AgentLmpqSession.Phase.REWARD, 300_000L);

        assertEquals(List.of(span("farm", 10_000L, 300_000L, true), span("walk", 10_000L, 20_000L, true),
                span("claim", 300_000L, 320_000L, false)), AgentLmpqPaceReport.of(session, 320_000L).spans());
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
        session.noteLeaderArrived(70_000L);
        session.transition(AgentLmpqSession.Phase.REGROUPING, 400_000L);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqPaceReport.class)) {
            AgentLmpqPaceReport.logSpans(session, 700_000L);
            String id = session.sessionId();
            assertEquals(List.of(
                    "LMPQ pace span: session=" + id + " key=farm seconds=390.0 theorySeconds=-1 complete=true why=-",
                    "LMPQ pace span: session=" + id + " key=walk seconds=60.0 theorySeconds=-1 complete=true why=-",
                    "LMPQ pace span: session=" + id + " key=handin seconds=300.0 theorySeconds=-1 complete=false"
                            + " why=open"),
                    lines.startingWith("LMPQ pace span:"));
        }
    }
}
