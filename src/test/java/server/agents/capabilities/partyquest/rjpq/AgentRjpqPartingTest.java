package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static server.agents.capabilities.partyquest.rjpq.AgentRjpqParting.Kind;

/** How an RJPQ companion run says its early end, and why the event ended under it. */
class AgentRjpqPartingTest {
    @Test
    void theEventEndedForTheLeaderTheTimerOrNeither() {
        assertEquals(Kind.DEFEATED, AgentRjpqParting.endedCause(true, false, 600_000L, 6_000L));
        assertEquals(Kind.LEADER_DOWN, AgentRjpqParting.endedCause(true, true, 600_000L, 6_000L));
        assertEquals(Kind.TIMEOUT, AgentRjpqParting.endedCause(false, true, 1_200L, 6_000L));
        assertEquals(Kind.TIMEOUT, AgentRjpqParting.endedCause(false, false, -300L, 6_000L));
        assertEquals(Kind.GENERIC, AgentRjpqParting.endedCause(false, true, 600_000L, 6_000L));
        assertEquals(Kind.GENERIC, AgentRjpqParting.endedCause(false, false, Long.MAX_VALUE, 6_000L));
    }

    @Test
    void eachEndHasItsOwnPlayerLine() {
        assertNull(AgentRjpqParting.SILENT.line());
        assertEquals("that's it for this one.", AgentRjpqParting.GENERIC.line());
        assertEquals("i'm down too... that's it for us.", AgentRjpqParting.of(Kind.WENT_DOWN, "Ben").line());
        assertEquals("Ben, we can't hand in the files without you. let's head back.",
                AgentRjpqParting.of(Kind.DEFEATED, "Ben").line());
        assertEquals("Ben, we couldn't finish it without you. let's head back.",
                AgentRjpqParting.of(Kind.LEADER_DOWN, "Ben").line());
        assertEquals("Ben's gone. we'll head back.", AgentRjpqParting.of(Kind.GONE, "Ben").line());
        assertEquals("out of time... yulete got away.", AgentRjpqParting.of(Kind.TIMEOUT, "Ben").line());
        assertEquals("we're stuck. let's call it.", AgentRjpqParting.of(Kind.TOO_LONG, "Ben").line());
        assertEquals("our leader's gone. we'll head back.", AgentRjpqParting.of(Kind.GONE, null).line());
        for (Kind kind : Kind.values()) {
            String line = AgentRjpqParting.of(kind, "Crewfriendxx").line();
            if (line != null) {
                String rest = line.replace("Crewfriendxx", "");
                assertEquals(rest.toLowerCase(Locale.ROOT), rest, line);
                assertFalse(line.chars().anyMatch(Character::isDigit), line);
            }
        }
    }
}
