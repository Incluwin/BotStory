package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static server.agents.capabilities.partyquest.ppq.AgentPpqMemberVerdicts.Verdict;

/**
 * P6i Step 4, plan 9.2: how one non-leader member's state bears on a PPQ companion run, rule by rule.
 */
class AgentPpqMemberVerdictTest {
    private static final AgentPpqMemberState.MemberType AGENT = AgentPpqMemberState.MemberType.AGENT;
    private static final AgentPpqMemberState.MemberType HUMAN = AgentPpqMemberState.MemberType.HUMAN;

    private static Verdict of(boolean inside, AgentPpqMemberState.MemberType type, boolean present, long hp,
                              boolean inEvent, boolean seen, long downSince, boolean held, int standing) {
        return AgentPpqMemberVerdicts.verdict(new AgentPpqMemberVerdicts.Facts(inside, type, present, hp, inEvent,
                seen, downSince, held, standing));
    }

    @Test
    void theRulesInOrder() {
        // inside, type, present, hp, in event, seen inside, down since, held, standing companions
        assertEquals(Verdict.IGNORE, of(false, AGENT, false, 0, false, true, 0, false, 0), "not inside: nothing");
        assertEquals(Verdict.IGNORE, of(false, HUMAN, true, 0, true, true, 5, true, 0), "past the clear");
        assertEquals(Verdict.MISSING, of(true, AGENT, false, 0, false, true, 0, false, 2), "an unloaded companion");
        assertEquals(Verdict.MISSING, of(true, AGENT, false, 0, false, false, 0, false, 0));
        assertEquals(Verdict.DROP_HUMAN, of(true, HUMAN, false, 0, false, true, 0, false, 2), "logged off inside");
        assertEquals(Verdict.IGNORE, of(true, HUMAN, false, 0, false, false, 0, false, 2), "never went in");
        assertEquals(Verdict.DROP_HUMAN, of(true, HUMAN, true, 900, false, true, 0, false, 2), "walked out alive");
        assertEquals(Verdict.DROP_HUMAN, of(true, HUMAN, true, 900, false, true, 4_000, false, 2),
                "pressed OK after going down");
        assertEquals(Verdict.IGNORE, of(true, HUMAN, true, 900, false, false, 0, false, 2), "not in yet");
        assertEquals(Verdict.IGNORE, of(true, AGENT, true, 900, false, true, 0, false, 2),
                "a companion out of the event is the departure pass's");
        assertEquals(Verdict.DOWN_WAIT, of(true, AGENT, true, 0, true, true, 0, false, 0),
                "down above the floor: its gate lets it go");
        assertEquals(Verdict.DOWN_WAIT, of(true, AGENT, true, 0, true, true, 0, true, 1), "held, another stands");
        assertEquals(Verdict.SHORT_HANDED, of(true, AGENT, true, 0, true, true, 3_000, true, 0),
                "held at the floor and no other companion stands");
        assertEquals(Verdict.DOWN_WAIT, of(true, HUMAN, true, 0, true, true, 0, false, 0),
                "a human down waits for his own OK");
        assertEquals(Verdict.REVIVED, of(true, AGENT, true, 300, true, true, 3_000, false, 2), "up again");
        assertEquals(Verdict.REVIVED, of(true, HUMAN, true, 300, true, true, 3_000, false, 2));
        assertEquals(Verdict.OK, of(true, AGENT, true, 300, true, true, 0, false, 2));
        assertEquals(Verdict.OK, of(true, HUMAN, true, 300, true, true, 0, false, 0));
    }
}
