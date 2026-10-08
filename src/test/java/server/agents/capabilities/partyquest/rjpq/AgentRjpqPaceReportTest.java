package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 4: an RJPQ run's spans, cut from its marks in the profile's order, and the record the host stores. */
class AgentRjpqPaceReportTest {
    private static AgentRjpqSession session() {
        AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.ALCADNO, 1L, 865, 1_000L);
        for (int id : List.of(865, 11, 12, 13)) {
            session.addMember(id, id == 865 ? AgentRjpqMemberState.MemberType.HUMAN
                    : AgentRjpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(865, 11);
        return session;
    }

    @Test
    void aWholeRunIsTenCompleteSpansInOrder() {
        AgentRjpqSession session = session();
        AgentRjpqPaceMarks pace = session.pace();
        pace.markEntered(List.of(), List.of(865, 11, 12, 13), 2_000L);
        pace.markPhase(AgentRjpqSession.Phase.TUNNEL, 90_000L);
        pace.markPhase(AgentRjpqSession.Phase.BEAKERS, 200_000L);
        pace.markPhase(AgentRjpqSession.Phase.KEYS, 400_000L);
        pace.mark(AgentRjpqPaceMarks.Boundary.DOORS_OPEN, 460_000L);
        pace.mark(AgentRjpqPaceMarks.Boundary.FILES_HELD, 700_000L);
        pace.mark(AgentRjpqPaceMarks.Boundary.FILES_IN, 730_000L);
        pace.markPhase(AgentRjpqSession.Phase.UNITS, 900_000L);
        pace.mark(AgentRjpqPaceMarks.Boundary.COMPANIONS_OUT, 1_150_000L);
        pace.mark(AgentRjpqPaceMarks.Boundary.LEADER_OUT, 1_300_000L);
        pace.mark(AgentRjpqPaceMarks.Boundary.CLEARED, 1_600_000L);
        pace.markDown(12);

        AgentPqPace record = AgentRjpqPaceReport.of(session, 1_700_000L);

        assertEquals("rjpqj", record.questKey());
        assertEquals(List.of("books", "tunnel", "beakers", "keys", "labs", "handin", "office", "units", "unitsLeader",
                "boss"), record.spans().stream().map(AgentPqPace.Span::key).toList());
        assertTrue(record.spans().stream().allMatch(AgentPqPace.Span::complete));
        assertEquals(88_000L, record.spans().get(0).toMs() - record.spans().get(0).fromMs());
        assertEquals(240_000L, record.spans().get(4).durationMs(), "labs: doors open to both files held");
        assertEquals(30_000L, record.spans().get(5).durationMs(), "handin: both files held to the files in");
        assertEquals(300_000L, record.spans().get(9).toMs() - record.spans().get(9).fromMs());
        assertEquals(1_600_000L, record.clearedAtMs());
        assertEquals(1, record.runs().stream().filter(run -> run.characterId() == 12).findFirst().orElseThrow()
                .deaths());
    }

    @Test
    void aSpanWhoseEndWasNotSeenRunsToTheNextSeenBoundaryAndIsOpen() {
        AgentRjpqSession session = session();
        AgentRjpqPaceMarks pace = session.pace();
        pace.markEntered(List.of(), List.of(865, 11, 12, 13), 2_000L);
        pace.markPhase(AgentRjpqSession.Phase.KEYS, 400_000L);

        List<AgentRjpqPaceReport.SpanReport> spans =
                AgentRjpqPaceReport.spans(session, pace.snapshot(), 500_000L);

        assertEquals("books", spans.get(0).key());
        assertFalse(spans.get(0).complete());
        assertEquals(400_000L, spans.get(0).toMs());
        assertEquals("keys", spans.get(1).key());
        assertEquals(500_000L, spans.get(1).toMs());
        assertEquals("open", spans.get(1).why());
    }
}
