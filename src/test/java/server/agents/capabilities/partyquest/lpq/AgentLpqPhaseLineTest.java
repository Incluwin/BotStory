package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every LPQ session, stock or companion run, logs one 'LPQ phase:' line per phase change in the shape the HPQ and KPQ
 * lines have ('&lt;PQ&gt; phase: session= A-&gt;B phaseMs= runMs= ... companionRun='), after the 'LPQ phase timing:' line
 * it always logged, which stays word for word (pqkit's logwatch parses both).
 */
class AgentLpqPhaseLineTest {
    private static AgentLpqSession session(boolean companionRun) {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, 742_800, 6, 1_000L);
        session.addMember(742_800, AgentLpqMemberState.MemberType.HUMAN);
        session.addMember(742_801, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(742_800, 742_801);
        if (companionRun) session.setCompanionAdopted(true);
        return session;
    }

    @Test
    void aStockRunLogsEachPhaseChange() {
        AgentLpqSession session = session(false);
        try (LpqLogLines log = new LpqLogLines(AgentLpqSession.class)) {
            session.transition(AgentLpqSession.Phase.ENTERING, 1_500L);
            session.transition(AgentLpqSession.Phase.STAGE_1, 4_000L);
            session.transition(AgentLpqSession.Phase.STAGE_1, 4_500L); // no change, no line

            String id = session.sessionId();
            assertEquals(List.of(
                    "LPQ phase: session=" + id + " PREPARING->ENTERING phaseMs=500 runMs=500 companionRun=false",
                    "LPQ phase: session=" + id + " ENTERING->STAGE_1 phaseMs=2500 runMs=3000 companionRun=false"),
                    log.startingWith("LPQ phase: "));
        }
    }

    @Test
    void anAdoptedRunSaysSo() {
        AgentLpqSession session = session(true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqSession.class)) {
            session.transition(AgentLpqSession.Phase.ENTERING, 2_000L);

            assertEquals(List.of("LPQ phase: session=" + session.sessionId()
                            + " PREPARING->ENTERING phaseMs=1000 runMs=1000 companionRun=true"),
                    log.startingWith("LPQ phase: "));
        }
    }

    @Test
    void theFailureAndTheCompletionAreLoggedToo() {
        AgentLpqSession failed = session(true);
        AgentLpqSession completed = session(false);
        try (LpqLogLines log = new LpqLogLines(AgentLpqSession.class)) {
            failed.transition(AgentLpqSession.Phase.STAGE_3, 61_000L);
            failed.fail("The LPQ event timer expired", 91_000L);
            completed.transition(AgentLpqSession.Phase.EXITING, 2_000L);
            completed.complete(3_000L);

            assertEquals(List.of(
                    "LPQ phase: session=" + failed.sessionId()
                            + " PREPARING->STAGE_3 phaseMs=60000 runMs=60000 companionRun=true",
                    "LPQ phase: session=" + failed.sessionId()
                            + " STAGE_3->FAILED phaseMs=30000 runMs=90000 companionRun=true",
                    "LPQ phase: session=" + completed.sessionId()
                            + " PREPARING->EXITING phaseMs=1000 runMs=1000 companionRun=false",
                    "LPQ phase: session=" + completed.sessionId()
                            + " EXITING->COMPLETED phaseMs=1000 runMs=2000 companionRun=false"),
                    log.startingWith("LPQ phase: "));
        }
    }

    @Test
    void thePhaseTimingLineIsUnchangedAndComesFirst() {
        AgentLpqSession session = session(true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqSession.class)) {
            session.transition(AgentLpqSession.Phase.STAGE_2, 5_000L);

            assertEquals(List.of(
                    "LPQ phase timing: session=" + session.sessionId() + " phase=PREPARING durationMs=4000 next=STAGE_2",
                    "LPQ phase: session=" + session.sessionId()
                            + " PREPARING->STAGE_2 phaseMs=4000 runMs=4000 companionRun=true"),
                    log.startingWith("LPQ phase"));
        }
    }
}
