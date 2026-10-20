package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.agents.capabilities.partyquest.ppq.AgentPpqPaceMarks.Boundary;

/**
 * P6i Step 5 (plan 10): a PPQ companion run's spans as the calibration reads them, one per segment cut at the first
 * sighting of each boundary. A hand-in seen only where it ends is zero long and incomplete, a deck the leader left for
 * a chest or hidden room is incomplete, a span still running at the release is open, and a medal wave's work is the
 * kills the run counted.
 */
class AgentPpqPaceReportTest {
    private static final List<String> KEYS = List.of("stage1", "medals1", "handin1", "medals2", "handin2", "medals3",
            "handin3", "deck1", "deck2", "doors", "boss", "wuyang");

    private static AgentPpqPaceMarks fullRun() {
        AgentPpqPaceMarks marks = new AgentPpqPaceMarks();
        marks.markEntered(List.of(), List.of(865, 11, 12, 13), 1_000L);
        long at = 1_000L;
        for (Boundary boundary : Boundary.values()) {
            at += 10_000L;
            marks.mark(boundary, at);
        }
        return marks;
    }

    @Test
    void aWholeRunIsTwelveCompleteSpansInOrder() {
        AgentPpqPaceMarks marks = fullRun();
        marks.observeWave(0, 30);
        marks.observeWave(0, 26);
        marks.observeWave(0, 31); // the respawn: no kill
        marks.observeWave(0, 29);
        marks.observeWave(1, 33); // a new wave starts its own count
        marks.observeWave(1, 30);

        List<AgentPpqPaceReport.SpanReport> spans = AgentPpqPaceReport.spans(marks.snapshot(), 500_000L);

        assertEquals(KEYS, spans.stream().map(AgentPpqPaceReport.SpanReport::key).toList());
        assertTrue(spans.stream().allMatch(AgentPpqPaceReport.SpanReport::complete), spans.toString());
        assertEquals(1_000L, spans.getFirst().fromMs());
        assertEquals(11_000L, spans.getFirst().toMs());
        assertEquals(10_000L, spans.get(11).toMs() - spans.get(11).fromMs());
        assertEquals(6.0d, spans.get(1).work(), 1e-9, "four, then two after the respawn");
        assertEquals(3.0d, spans.get(3).work(), 1e-9);
        assertEquals(0.0d, spans.get(5).work(), 1e-9);
        assertEquals(0.0d, spans.get(0).work(), 1e-9);
    }

    @Test
    void aHandInWithinOnePassIsZeroLongAndIncomplete() {
        AgentPpqPaceMarks marks = new AgentPpqPaceMarks();
        marks.markEntered(List.of(), List.of(865, 11), 1_000L);
        marks.mark(Boundary.MEDALS, 5_000L);
        marks.mark(Boundary.WAVE_ONE_IN, 90_000L); // the twenty never seen in his bag

        List<AgentPpqPaceReport.SpanReport> spans = AgentPpqPaceReport.spans(marks.snapshot(), 100_000L);

        AgentPpqPaceReport.SpanReport medals = spans.get(1);
        AgentPpqPaceReport.SpanReport handIn = spans.get(2);
        assertEquals("medals1", medals.key());
        assertEquals(90_000L, medals.toMs());
        assertFalse(medals.complete());
        assertEquals("unseen-full", medals.why());
        assertEquals("handin1", handIn.key());
        assertEquals(90_000L, handIn.fromMs());
        assertEquals(90_000L, handIn.toMs());
        assertFalse(handIn.complete());
        assertEquals("unseen-full", handIn.why());
        assertEquals("medals2", spans.get(3).key());
        assertEquals("open", spans.get(3).why(), "the next wave still runs at the report");
    }

    @Test
    void aDeckWithAChestRoomDetourIsIncomplete() {
        AgentPpqPaceMarks marks = fullRun();
        marks.markDetour(AgentPpqDefinition.DECK_ONE_MAP);

        List<AgentPpqPaceReport.SpanReport> spans = AgentPpqPaceReport.spans(marks.snapshot(), 500_000L);

        assertEquals("deck1", spans.get(7).key());
        assertFalse(spans.get(7).complete());
        assertEquals("detour", spans.get(7).why());
        assertTrue(spans.get(8).complete(), "the second deck had no detour");
    }

    @Test
    void aRunReleasedOnTheBossDeckEndsWithAnOpenBossSpan() {
        AgentPpqPaceMarks marks = new AgentPpqPaceMarks();
        marks.markEntered(List.of(), List.of(865, 11), 1_000L);
        marks.mark(Boundary.MEDALS, 2_000L);
        marks.mark(Boundary.WAVE_ONE_FULL, 3_000L);
        marks.mark(Boundary.WAVE_ONE_IN, 4_000L);
        marks.mark(Boundary.WAVE_TWO_FULL, 5_000L);
        marks.mark(Boundary.WAVE_TWO_IN, 6_000L);
        marks.mark(Boundary.WAVE_THREE_FULL, 7_000L);
        marks.mark(Boundary.WAVE_THREE_IN, 8_000L);
        marks.mark(Boundary.DECK_TWO, 9_000L);
        marks.mark(Boundary.DOORS, 10_000L);
        marks.mark(Boundary.BOSS, 11_000L);

        List<AgentPpqPaceReport.SpanReport> spans = AgentPpqPaceReport.spans(marks.snapshot(), 50_000L);

        assertEquals(11, spans.size(), "wu yang never began");
        AgentPpqPaceReport.SpanReport boss = spans.getLast();
        assertEquals("boss", boss.key());
        assertEquals(50_000L, boss.toMs());
        assertEquals("open", boss.why());
    }

    @Test
    void theRecordCarriesTheQuestTheClearAndWhoWentDown() {
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_LEADER, 1L, 865, false, 500L);
        session.addMember(865, AgentPpqMemberState.MemberType.HUMAN);
        session.addMember(11, AgentPpqMemberState.MemberType.AGENT);
        session.setLeadership(865, 11);
        AgentPpqPaceMarks marks = session.pace();
        marks.markEntered(List.of(), List.of(865, 11, 12, 13), 1_000L);
        marks.mark(Boundary.CLEARED, 900_000L);
        marks.markDown(11);
        marks.markDown(11);
        marks.markDeparted(12);

        AgentPqPace pace = AgentPpqPaceReport.of(session, 950_000L);

        assertEquals("ppq", pace.questKey());
        assertEquals(session.sessionId(), pace.sessionId());
        assertEquals(865, pace.leaderId());
        assertEquals(900_000L, pace.clearedAtMs());
        Map<Integer, AgentPqPace.MemberRun> runs = new java.util.TreeMap<>();
        pace.runs().forEach(run -> runs.put(run.characterId(), run));
        assertEquals(List.of(11, 12, 13, 865), List.copyOf(runs.keySet()));
        assertEquals(2, runs.get(11).deaths());
        assertTrue(runs.get(12).tappedOut());
        assertFalse(runs.get(13).tappedOut());
    }
}
