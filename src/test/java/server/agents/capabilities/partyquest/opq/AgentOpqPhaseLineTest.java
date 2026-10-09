package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P6h OPQ step 1 (C4): 'OPQ phase:' is the phase line every PQ logs, for stock and companion runs alike: one line per
 * phase change, on a failure and on a completion, with the time spent in the phase it leaves and in the run so far.
 * A same-phase or backwards transition, and a second failure, log nothing.
 */
class AgentOpqPhaseLineTest {

    @Test
    void everyPhaseChangeIsLoggedOnceWithItsTimes() {
        try (OpqLogLines lines = new OpqLogLines(AgentOpqSession.class)) {
            AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.AUTONOMOUS, 7L, 1, 1_000L);
            String id = session.sessionId();

            session.transition(AgentOpqSession.Phase.ENTERING, 1_500L);
            session.transition(AgentOpqSession.Phase.ENTERING, 1_700L); // the same phase: progress only
            session.transition(AgentOpqSession.Phase.ENTRANCE, 4_000L);
            session.transition(AgentOpqSession.Phase.ENTERING, 5_000L); // backwards: ignored
            session.fail("No legal OPQ progress before timeout", 9_000L);
            session.fail("again", 9_500L); // already failed: no second line

            assertEquals(List.of(
                    "OPQ phase: session=" + id + " PREPARING->ENTERING phaseMs=500 runMs=500 companionRun=false",
                    "OPQ phase: session=" + id + " ENTERING->ENTRANCE phaseMs=2500 runMs=3000 companionRun=false",
                    "OPQ phase: session=" + id + " ENTRANCE->FAILED phaseMs=5000 runMs=8000 companionRun=false"),
                    lines.startingWith("OPQ phase:"));
        }
    }

    @Test
    void aCompanionRunSaysSoAndItsCompletionIsLogged() {
        try (OpqLogLines lines = new OpqLogLines(AgentOpqSession.class)) {
            AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, 1, 5, 2_000L);
            session.setCompanionAdopted(true);
            String id = session.sessionId();

            session.transition(AgentOpqSession.Phase.GARDEN, 3_000L);
            session.transition(AgentOpqSession.Phase.CLAIMING_REWARD, 10_000L);
            session.complete(12_500L);
            session.complete(13_000L); // already complete: no second line

            assertEquals(List.of(
                    "OPQ phase: session=" + id + " PREPARING->GARDEN phaseMs=1000 runMs=1000 companionRun=true",
                    "OPQ phase: session=" + id + " GARDEN->CLAIMING_REWARD phaseMs=7000 runMs=8000 companionRun=true",
                    "OPQ phase: session=" + id
                            + " CLAIMING_REWARD->COMPLETED phaseMs=2500 runMs=10500 companionRun=true"),
                    lines.startingWith("OPQ phase:"));
        }
    }
}
