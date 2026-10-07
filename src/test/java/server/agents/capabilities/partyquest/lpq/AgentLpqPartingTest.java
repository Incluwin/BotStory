package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.lpq.AgentLpqParting.Kind;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6g LPQ C3.1 (section 10.6): how a companion run's companions put its early end, every kind before and after the
 * clear (the wrap-ups), self and named, "we need five" for LPQ's floor, SILENT for a run that never got going, and the
 * ended event's cause (LudiPQ's 45-minute timer, or no reason a player would name). Every line passes the companion
 * lint of section 10.0.
 */
class AgentLpqPartingTest {
    private static final int KES = 3;
    private static final long TIMER = 2_700_000L;
    private static final long SLACK = 2_000L;

    private static String before(Kind kind, String subject, int subjectId, int speakerId) {
        return new AgentLpqParting(kind, subjectId, subject).line(speakerId, false);
    }

    private static String after(Kind kind, String subject, int subjectId, int speakerId) {
        return new AgentLpqParting(kind, subjectId, subject).line(speakerId, true);
    }

    @Test
    void everyKindBeforeTheClear() {
        assertEquals("we can't finish this one. let's head out.", before(Kind.GENERIC, null, 0, KES));
        assertEquals("sorry, i went down. we need five for this, so let's head out.",
                before(Kind.WENT_DOWN, "Kes", KES, KES));
        assertEquals("Pel went down, and we need five for this. let's head out.",
                before(Kind.WENT_DOWN, "Pel", 4, KES));
        assertEquals("we're short now, and we need five for this. let's head out.",
                before(Kind.WENT_DOWN, null, 0, KES));
        assertEquals("Mira left, and we need five for this. let's head out.", before(Kind.LEFT, "Mira", 2, KES));
        assertEquals("we're short now, and we need five for this. let's head out.", before(Kind.LEFT, null, 0, KES));
        assertEquals("sorry, i went down. we can't finish this one, let's head out.",
                before(Kind.DEFEATED, "Kes", KES, KES));
        assertEquals("Crewfriend went down, so we can't finish this one. let's head out.",
                before(Kind.DEFEATED, "Crewfriend", 1, KES));
        assertEquals("we can't finish this one. let's head out.", before(Kind.DEFEATED, null, 0, KES));
        assertEquals("Crewfriend dropped out, so we can't finish this one. let's head out.",
                before(Kind.GONE, "Crewfriend", 1, KES));
        assertEquals("we can't finish this one. let's head out.", before(Kind.GONE, null, 0, KES));
        assertEquals("we ran out of time. let's head out.", before(Kind.TIMEOUT, null, 0, KES));
        assertEquals("we've been stuck too long. let's call it and head out.", before(Kind.TOO_LONG, null, 0, KES));
        assertNull(before(Kind.SILENT, "Crewfriend", 1, KES), "a run that never got going ends without a word");
    }

    @Test
    void afterTheClearItIsAWrapUpNeverACantFinish() {
        String wrapUp = "let's wrap it up and head back.";
        assertEquals(wrapUp, after(Kind.GENERIC, null, 0, KES));
        assertEquals(wrapUp, after(Kind.TIMEOUT, null, 0, KES));
        assertEquals("sorry, i went down. " + wrapUp, after(Kind.WENT_DOWN, "Kes", KES, KES));
        assertEquals("Pel went down. " + wrapUp, after(Kind.WENT_DOWN, "Pel", 4, KES));
        assertEquals(wrapUp, after(Kind.WENT_DOWN, null, 0, KES));
        assertEquals("sorry, i went down. " + wrapUp, after(Kind.DEFEATED, "Kes", KES, KES));
        assertEquals("Crewfriend went down. " + wrapUp, after(Kind.DEFEATED, "Crewfriend", 1, KES));
        assertEquals("Mira left. " + wrapUp, after(Kind.LEFT, "Mira", 2, KES));
        assertEquals(wrapUp, after(Kind.LEFT, null, 0, KES));
        assertEquals("Crewfriend dropped out. " + wrapUp, after(Kind.GONE, "Crewfriend", 1, KES));
        assertEquals("we've been at this a while. " + wrapUp, after(Kind.TOO_LONG, null, 0, KES));
        assertNull(after(Kind.SILENT, null, 0, KES));
        for (Kind kind : Kind.values()) {
            String line = after(kind, "Pel", 4, KES);
            if (line == null) continue;
            assertTrue(!line.contains("can't finish") && !line.contains("five"), kind + ": " + line);
        }
    }

    @Test
    void theEventEndedByTheTimerOnlyNearItsEnd() {
        assertEquals(Kind.TIMEOUT, AgentLpqParting.eventEnded(TIMER - SLACK, TIMER, SLACK));
        assertEquals(Kind.TIMEOUT, AgentLpqParting.eventEnded(2_698_500L, TIMER, SLACK));
        assertEquals(Kind.GENERIC, AgentLpqParting.eventEnded(TIMER - SLACK - 1L, TIMER, SLACK));
        assertEquals(Kind.GENERIC, AgentLpqParting.eventEnded(600_000L, TIMER, SLACK));
    }

    @Test
    void ofNamesTheSubjectOrNoOne() {
        assertEquals(new AgentLpqParting(Kind.LEFT, 0, null), AgentLpqParting.of(Kind.LEFT));
        assertEquals(new AgentLpqParting(Kind.LEFT, 0, null), AgentLpqParting.of(Kind.LEFT, null));
        assertEquals(Kind.SILENT, AgentLpqParting.SILENT.kind());
        assertEquals(Kind.GENERIC, AgentLpqParting.GENERIC.kind());
    }

    @Test
    void everyPartingLinePassesTheCompanionLint() {
        List<String> bad = new ArrayList<>();
        int lines = 0;
        for (Kind kind : Kind.values()) {
            for (boolean pastTheClear : new boolean[] {false, true}) {
                for (AgentLpqParting parting : List.of(new AgentLpqParting(kind, 0, null),
                        new AgentLpqParting(kind, 1, "Crewfriend"), new AgentLpqParting(kind, KES, "Kes"))) {
                    String line = parting.line(KES, pastTheClear);
                    if (line == null) continue;
                    lines++;
                    List<String> problems = AgentLpqCompanionLinesTest.problems(line);
                    if (!problems.isEmpty()) bad.add(problems + ": " + line);
                }
            }
        }
        assertTrue(lines > 20, "lines " + lines);
        assertEquals(List.of(), bad);
    }
}
