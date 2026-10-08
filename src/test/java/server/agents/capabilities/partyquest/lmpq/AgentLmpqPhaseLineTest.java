package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P6h LMPQ step 1, the 'LMPQ phase:' line (HPQ/KPQ shape), in every mode: one per phase change in transition, fail
 * and complete, with the time spent in the phase left and in the run; none for a same-phase or backwards transition
 * and none for a fail or complete of a run that already ended.
 */
class AgentLmpqPhaseLineTest {

    private static AgentLmpqSession session() {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 7L, 781_800, 3, 1_000L);
        session.addMember(781_800, AgentLmpqMemberState.MemberType.HUMAN);
        session.addMember(781_801, AgentLmpqMemberState.MemberType.AGENT);
        session.addMember(781_802, AgentLmpqMemberState.MemberType.AGENT);
        session.setLeadership(781_800, 781_801);
        return session;
    }

    @Test
    void everyPhaseChangeLogsOneLineAndNothingElseDoes() {
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqSession.class)) {
            AgentLmpqSession session = session();
            String id = session.sessionId();
            session.transition(AgentLmpqSession.Phase.FARMING, 4_000L);
            session.transition(AgentLmpqSession.Phase.FARMING, 5_000L);
            session.transition(AgentLmpqSession.Phase.ENTERING, 5_500L);
            session.transition(AgentLmpqSession.Phase.REGROUPING, 10_000L);
            session.fail("LMPQ stalled in REGROUPING", 12_000L);
            session.fail("again", 13_000L);
            session.complete(14_000L);
            session.transition(AgentLmpqSession.Phase.REWARD, 15_000L);

            assertEquals(List.of(
                    "LMPQ phase: session=" + id + " PREPARING->FARMING phaseMs=3000 runMs=3000 companionRun=false",
                    "LMPQ phase: session=" + id + " FARMING->REGROUPING phaseMs=6000 runMs=9000 companionRun=false",
                    "LMPQ phase: session=" + id + " REGROUPING->FAILED phaseMs=2000 runMs=11000 companionRun=false"),
                    lines.startingWith("LMPQ phase:"));
        }
    }

    @Test
    void aCompanionRunSaysSoAndItsCompletionIsLogged() {
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqSession.class)) {
            AgentLmpqSession session = session();
            session.setCompanionAdopted(true);
            String id = session.sessionId();
            session.transition(AgentLmpqSession.Phase.REWARD, 61_000L);
            session.complete(91_000L);
            session.complete(92_000L);
            session.fail("after the end", 93_000L);

            assertEquals(List.of(
                    "LMPQ phase: session=" + id + " PREPARING->REWARD phaseMs=60000 runMs=60000 companionRun=true",
                    "LMPQ phase: session=" + id + " REWARD->COMPLETED phaseMs=30000 runMs=90000 companionRun=true"),
                    lines.startingWith("LMPQ phase:"));
        }
    }
}
