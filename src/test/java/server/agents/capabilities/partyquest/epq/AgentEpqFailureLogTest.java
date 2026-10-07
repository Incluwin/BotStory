package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Each way the coordinator fails an EPQ run logs one 'EPQ session failure:' line with the phase the run was in, and
 * the run keeps its stock failure text: the event timer, a member in another event instance, and the watchdog.
 */
class AgentEpqFailureLogTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_TWO_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void theEventTimer() {
        AgentEpqSession session = stageTwo(true);
        session.markEnteredEvent(1_000L); // a companion run's clock runs from the party's entry (Step 2.3)
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 1_000L + 1_800_000L + 2_000L);

            assertEquals(List.of(failureLine(session, "EPQ event timer expired", true)),
                    lines.startingWith("EPQ session failure:"));
        }
        assertEquals("EPQ event timer expired", session.failure());
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void aMemberInAnotherEventInstance() {
        AgentEpqSession session = stageTwo(false);
        when(tobi.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 2_000L);

            assertEquals(List.of(failureLine(session, "EPQ member entered a different event instance", false)),
                    lines.startingWith("EPQ session failure:"));
        }
        assertEquals("EPQ member entered a different event instance", session.failure());
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void theWatchdog() {
        AgentEpqSession session = stageTwo(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqWatchdogRuntime.tick(session, 1_000L + 479_999L);
            assertEquals(List.of(), lines.startingWith("EPQ session failure:"));

            AgentEpqWatchdogRuntime.tick(session, 1_000L + 480_000L);

            assertEquals(List.of(failureLine(session, "EPQ stalled in STAGE_TWO for 480 seconds", true)),
                    lines.startingWith("EPQ session failure:"));
        }
        assertEquals("EPQ stalled in STAGE_TWO for 480 seconds", session.failure());
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    private AgentEpqSession stageTwo(boolean companionAdopted) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionAdopted,
                AgentEpqSession.Phase.STAGE_TWO, ben, mira, tobi, una);
    }

    private static String failureLine(AgentEpqSession session, String reason, boolean companionRun) {
        return "EPQ session failure: session=" + session.sessionId() + " phase=STAGE_TWO reason=" + reason
                + " members=4 companionRun=" + companionRun;
    }
}
