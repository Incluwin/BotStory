package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6i Step 4, plan 9.4-9.5: how a PPQ companion run's early end is put in a companion's words, and why the bound
 * event ended under it before the clear.
 */
class AgentPpqPartingTest {
    private static final String NAME = "Crewfriendxx";

    @Test
    void theEventsEndIsNamedFromWhatTheLastPassSaw() {
        assertEquals(AgentPpqParting.Kind.DEFEATED, AgentPpqParting.endedCause(true, 1_000L, 2_000L, 3, 3),
                "the leader lay dead: his OK ended it");
        assertEquals(AgentPpqParting.Kind.TIMEOUT, AgentPpqParting.endedCause(false, 1_500L, 2_000L, 5, 3));
        assertEquals(AgentPpqParting.Kind.LEFT, AgentPpqParting.endedCause(false, 60_000L, 2_000L, 3, 3),
                "at the floor: one more leaving ended it");
        assertEquals(AgentPpqParting.Kind.GENERIC, AgentPpqParting.endedCause(false, 60_000L, 2_000L, 5, 3));
        assertEquals(AgentPpqParting.Kind.GENERIC, AgentPpqParting.endedCause(false, Long.MAX_VALUE, 2_000L, -1, 3),
                "nothing read yet");
    }

    @Test
    void theLinesArePlayerChat() {
        assertNull(AgentPpqParting.SILENT.line());
        assertEquals("that's it for this one.", AgentPpqParting.GENERIC.line());
        assertEquals("we're all down... that's it.", AgentPpqParting.of(AgentPpqParting.Kind.WENT_DOWN, NAME).line());
        assertEquals("not enough of us left. let's head back.",
                AgentPpqParting.of(AgentPpqParting.Kind.LEFT, NAME).line());
        assertEquals(NAME + ", you went down... we can't finish without you.",
                AgentPpqParting.of(AgentPpqParting.Kind.DEFEATED, NAME).line());
        assertEquals(NAME + "'s gone. we'll head back.", AgentPpqParting.of(AgentPpqParting.Kind.GONE, NAME).line());
        assertEquals("our leader's gone. we'll head back.", AgentPpqParting.of(AgentPpqParting.Kind.GONE, null).line());
        assertEquals("out of time... let's head back.", AgentPpqParting.of(AgentPpqParting.Kind.TIMEOUT, NAME).line());
        assertEquals("we're stuck. let's call it.", AgentPpqParting.of(AgentPpqParting.Kind.TOO_LONG, NAME).line());
        for (AgentPpqParting.Kind kind : AgentPpqParting.Kind.values()) {
            for (String leader : new String[] {NAME, null}) {
                String line = AgentPpqParting.of(kind, leader).line();
                if (kind == AgentPpqParting.Kind.SILENT) {
                    assertNull(line);
                    continue;
                }
                String withoutName = line.replace(NAME, "");
                assertEquals(withoutName.toLowerCase(Locale.ROOT), withoutName, line);
                assertTrue(line.length() <= 100, line);
                assertFalse(line.chars().anyMatch(c -> Character.isDigit(c) || "#[<{".indexOf(c) >= 0), line);
            }
        }
    }
}
