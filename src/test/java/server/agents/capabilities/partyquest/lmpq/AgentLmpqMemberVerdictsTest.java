package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6h Step 3's tables, pure: how a non-leader member's state bears on a companion run (above the floor a companion
 * that left is tapped out and a human dropped; a dead one held at the floor waits, past the recovery timeout before
 * the clearing it is short-handed; after the clear nothing is asked), and the partings, each in a player's words.
 */
class AgentLmpqMemberVerdictsTest {
    private static final AgentLmpqMemberState.MemberType AGENT = AgentLmpqMemberState.MemberType.AGENT;
    private static final AgentLmpqMemberState.MemberType HUMAN = AgentLmpqMemberState.MemberType.HUMAN;
    private static final long TIMEOUT = 45_000L;

    private static AgentLmpqMemberVerdicts.Verdict verdict(AgentLmpqMemberState.MemberType type, int hp, boolean held,
                                                           boolean seenHeld, boolean cleared, long downSince,
                                                           boolean clearing, long now) {
        return AgentLmpqMemberVerdicts.verdict(new AgentLmpqMemberVerdicts.Facts(type, hp, held, seenHeld, false,
                cleared, downSince, clearing, now, TIMEOUT));
    }

    @Test
    void theTable() {
        assertEquals(AgentLmpqMemberVerdicts.Verdict.OK, verdict(AGENT, 900, true, true, false, 0L, false, 1L));
        assertEquals(AgentLmpqMemberVerdicts.Verdict.OK, verdict(AGENT, 900, false, false, false, 0L, false, 1L),
                "not held yet: still entering");
        assertEquals(AgentLmpqMemberVerdicts.Verdict.REQUEST_TAP_OUT,
                verdict(AGENT, 900, false, true, false, 1L, false, 2L), "it went down and its respawn took it out");
        assertEquals(AgentLmpqMemberVerdicts.Verdict.DROP_HUMAN, verdict(HUMAN, 900, false, true, false, 0L, false, 2L));
        assertEquals(AgentLmpqMemberVerdicts.Verdict.DOWN_WAIT, verdict(AGENT, 0, true, true, false, 0L, false, 2L));
        assertEquals(AgentLmpqMemberVerdicts.Verdict.DOWN_WAIT,
                verdict(AGENT, 0, true, true, false, 1_000L, false, 1_000L + TIMEOUT - 1));
        assertEquals(AgentLmpqMemberVerdicts.Verdict.SHORT_HANDED,
                verdict(AGENT, 0, true, true, false, 1_000L, false, 1_000L + TIMEOUT));
        assertEquals(AgentLmpqMemberVerdicts.Verdict.DOWN_WAIT,
                verdict(AGENT, 0, true, true, false, 1_000L, true, 900_000L), "from the clearing it waits for Pietri");
        assertEquals(AgentLmpqMemberVerdicts.Verdict.DOWN_WAIT,
                verdict(HUMAN, 0, true, true, false, 1_000L, false, 900_000L), "a human presses OK himself");
        assertEquals(AgentLmpqMemberVerdicts.Verdict.REVIVED, verdict(AGENT, 900, true, true, false, 5L, false, 9L));
        assertEquals(AgentLmpqMemberVerdicts.Verdict.IGNORE, verdict(AGENT, 0, false, true, true, 5L, false, 9L));
        assertEquals(AgentLmpqMemberVerdicts.Verdict.IGNORE, AgentLmpqMemberVerdicts.verdict(
                new AgentLmpqMemberVerdicts.Facts(AGENT, 0, false, true, true, false, 0L, false, 1L, TIMEOUT)),
                "already departed");
    }

    @Test
    void thePartingsSoundLikeAPlayer() {
        for (AgentLmpqParting.Kind kind : AgentLmpqParting.Kind.values()) {
            for (String subject : new String[]{"Ben", null}) {
                String line = AgentLmpqParting.of(kind, subject).line();
                if (kind == AgentLmpqParting.Kind.SILENT) {
                    assertNull(line);
                    continue;
                }
                String bare = subject == null ? line : line.replace(subject, "");
                assertEquals(bare.toLowerCase(Locale.ROOT), bare, line);
                for (String marker : List.of("#", "[", "<", "->", "lmpq", "LMPQ")) {
                    assertFalse(line.contains(marker), line);
                }
            }
        }
        assertEquals("Ben's gone. we'll head back.", AgentLmpqParting.of(AgentLmpqParting.Kind.GONE, "Ben").line());
        assertEquals("Kes went down and we're too few now. let's head out.",
                AgentLmpqParting.of(AgentLmpqParting.Kind.WENT_DOWN, "Kes").line());
    }

    @Test
    void anEventThatEndedIsPutDownToTheFloorDepartureThenTheTimer() {
        assertEquals(AgentLmpqParting.of(AgentLmpqParting.Kind.WENT_DOWN, "Kes"),
                AgentLmpqParting.eventEnded("Kes", 899_000L, 900_000L, 10_000L));
        assertEquals(AgentLmpqParting.Kind.TIMEOUT,
                AgentLmpqParting.eventEnded(null, 890_000L, 900_000L, 10_000L).kind());
        assertEquals(AgentLmpqParting.GENERIC, AgentLmpqParting.eventEnded(null, 600_000L, 900_000L, 10_000L));
        assertTrue(AgentLmpqParting.eventEnded("", 1L, 900_000L, 10_000L).line().startsWith("we're too few"));
    }
}
