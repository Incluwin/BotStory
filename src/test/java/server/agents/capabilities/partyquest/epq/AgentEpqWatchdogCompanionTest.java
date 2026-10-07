package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 4.6: a companion run the stall watchdog ends before the clear says why it stops (TOO_LONG, by a companion on
 * the leader's map); the failure's text is today's. A stock run fails as before, without a word.
 */
class AgentEpqWatchdogCompanionTest {
    private static final long IDLE = 480_000L; // HUMAN_ASSISTED_IDLE_MS

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_ONE_MAP);

    AgentEpqWatchdogCompanionTest() {
        when(run.actions.liveMonsterCount(any(), any())).thenReturn(5);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aStalledCompanionRunSaysItHasBeenStuckTooLong() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);
        try (EpqLogLines companion = new EpqLogLines(AgentEpqCompanionRun.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 2_000L + IDLE);

            assertEquals(List.of("EPQ parting: session=" + session.sessionId() + " kind=TOO_LONG speaker=Mira"),
                    companion.startingWith("EPQ parting:"));
        }
        verify(run.packets, times(1)).broadcastChatText(mira, "we've been stuck too long. let's call it and head out.",
                false, 0);
        assertEquals("EPQ stalled in STAGE_ONE for 480 seconds", session.failure());
    }

    @Test
    void pinAStalledStockRunFailsAsBefore() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + IDLE);

        assertEquals("EPQ stalled in STAGE_ONE for 480 seconds", session.failure());
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }
}
