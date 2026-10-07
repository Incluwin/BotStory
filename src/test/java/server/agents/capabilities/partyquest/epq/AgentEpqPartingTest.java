package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Step 4.2: every parting, as said by the member it is about, about a named member and about nobody; SILENT says
 * nothing; and why the event ended under the run.
 */
class AgentEpqPartingTest {
    private static final int MIRA = 2;

    private static String line(AgentEpqParting.Kind kind, boolean self, boolean named) {
        AgentEpqParting parting = new AgentEpqParting(kind, named || self ? MIRA : 0, named || self ? "Mira" : null, 0);
        return parting.line(self ? MIRA : 9);
    }

    @Test
    void everyKind() {
        for (boolean self : new boolean[] {true, false}) {
            for (boolean named : new boolean[] {true, false}) {
                assertNull(line(AgentEpqParting.Kind.SILENT, self, named));
                assertEquals("we can't finish this one. let's head out.", line(AgentEpqParting.Kind.GENERIC, self, named));
                assertEquals("we ran out of time. we'll get the golem next time.",
                        line(AgentEpqParting.Kind.TIMEOUT, self, named));
                assertEquals("we've been stuck too long. let's call it and head out.",
                        line(AgentEpqParting.Kind.TOO_LONG, self, named));
            }
        }
        assertEquals("sorry, i went down too. let's regroup outside.", line(AgentEpqParting.Kind.WENT_DOWN, true, true));
        assertEquals("too many of us went down. let's regroup outside.",
                line(AgentEpqParting.Kind.WENT_DOWN, false, true));
        assertEquals("too many of us went down. let's regroup outside.",
                line(AgentEpqParting.Kind.WENT_DOWN, false, false));
        assertEquals("Mira left, and we need four for the forest. let's head out.",
                line(AgentEpqParting.Kind.LEFT, false, true));
        assertEquals("we're short now, and we need four for the forest. let's head out.",
                line(AgentEpqParting.Kind.LEFT, false, false));
        assertEquals("Mira, you went down, so we can't finish this one. let's head out.",
                line(AgentEpqParting.Kind.DEFEATED, false, true));
        assertEquals("we can't finish this one. let's head out.", line(AgentEpqParting.Kind.DEFEATED, false, false));
        assertEquals("Mira dropped out, so we can't finish this one. let's head out.",
                line(AgentEpqParting.Kind.GONE, false, true));
        assertEquals("we can't finish this one. let's head out.", line(AgentEpqParting.Kind.GONE, false, false));
        assertEquals("we lost the monster marbles, so there's no way to finish now. let's head out.",
                AgentEpqParting.lost(AgentEpqDefinition.MONSTER_MARBLE).line(MIRA));
        assertEquals("we lost the purple stone, so there's no way to finish now. let's head out.",
                AgentEpqParting.lost(AgentEpqDefinition.MAGIC_STONE).line(MIRA));
    }

    @Test
    void whyTheEventEnded() {
        assertEquals(AgentEpqParting.Kind.DEFEATED, AgentEpqParting.endedCause(true, 0L, 1_800_000L, 2_000L));
        assertEquals(AgentEpqParting.Kind.TIMEOUT, AgentEpqParting.endedCause(false, 1_798_000L, 1_800_000L, 2_000L));
        assertEquals(AgentEpqParting.Kind.GENERIC, AgentEpqParting.endedCause(false, 1_797_999L, 1_800_000L, 2_000L));
    }
}
