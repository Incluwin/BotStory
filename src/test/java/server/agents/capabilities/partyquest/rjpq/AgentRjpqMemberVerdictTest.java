package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.agents.capabilities.partyquest.rjpq.AgentRjpqMemberState.MemberType.AGENT;
import static server.agents.capabilities.partyquest.rjpq.AgentRjpqMemberState.MemberType.HUMAN;
import static server.agents.capabilities.partyquest.rjpq.AgentRjpqMemberVerdicts.Verdict;

/** The member verdicts of an RJPQ companion run, row by row: exactly four, so nobody taps out or is replaced. */
class AgentRjpqMemberVerdictTest {
    private static Verdict verdict(boolean inside, AgentRjpqMemberState.MemberType type, boolean present, long hp,
                                   boolean inEvent, boolean cleared, long downSince, int standing) {
        return AgentRjpqMemberVerdicts.verdict(new AgentRjpqMemberVerdicts.Facts(inside, type, present, hp, inEvent,
                cleared, downSince, standing));
    }

    @Test
    void everyRowOfTheTable() {
        // up and in the event
        assertEquals(Verdict.OK, verdict(true, AGENT, true, 900, true, false, 0, 2));
        assertEquals(Verdict.OK, verdict(true, HUMAN, true, 900, true, false, 0, 2));
        // a corpse waits while another companion stands, a lone one ends the run, a dead player always waits
        assertEquals(Verdict.DOWN_WAIT, verdict(true, AGENT, true, 0, true, false, 0, 1));
        assertEquals(Verdict.SHORT_HANDED, verdict(true, AGENT, true, 0, true, false, 5_000, 0));
        assertEquals(Verdict.DOWN_WAIT, verdict(true, HUMAN, true, 0, true, false, 0, 0));
        // back up
        assertEquals(Verdict.REVIVED, verdict(true, AGENT, true, 50, true, false, 5_000, 1));
        assertEquals(Verdict.REVIVED, verdict(true, HUMAN, true, 50, true, false, 5_000, 1));
        // out of the event or gone before the clear
        assertEquals(Verdict.DROP_HUMAN, verdict(true, HUMAN, true, 900, false, false, 0, 2));
        assertEquals(Verdict.DROP_HUMAN, verdict(true, HUMAN, false, 0, false, false, 0, 2));
        assertEquals(Verdict.MISSING, verdict(true, AGENT, true, 900, false, false, 0, 2));
        assertEquals(Verdict.MISSING, verdict(true, AGENT, false, 0, false, false, 0, 2));
        // before the event nothing is asked
        assertEquals(Verdict.OK, verdict(false, AGENT, true, 0, false, false, 0, 0));
        assertEquals(Verdict.MISSING, verdict(false, HUMAN, false, 0, false, false, 0, 0));
        // after the clear nothing is asked of the dead, the gone or the down
        assertEquals(Verdict.IGNORE, verdict(true, AGENT, true, 0, true, true, 0, 0));
        assertEquals(Verdict.IGNORE, verdict(true, AGENT, false, 0, false, true, 0, 0));
        assertEquals(Verdict.IGNORE, verdict(true, HUMAN, false, 0, false, true, 0, 0));
        assertEquals(Verdict.IGNORE, verdict(true, AGENT, true, 50, true, true, 9_000, 1));
        assertEquals(Verdict.OK, verdict(true, AGENT, true, 50, false, true, 0, 1));
    }

    @Test
    void theStagesAreInsideTheRunAndTheEndsAreNot() {
        assertTrue(AgentRjpqMemberVerdicts.inside(AgentRjpqSession.Phase.BOOKS));
        assertTrue(AgentRjpqMemberVerdicts.inside(AgentRjpqSession.Phase.CENTER));
        assertFalse(AgentRjpqMemberVerdicts.inside(AgentRjpqSession.Phase.ENTERING));
        assertFalse(AgentRjpqMemberVerdicts.inside(AgentRjpqSession.Phase.CLAIMING));
    }
}
