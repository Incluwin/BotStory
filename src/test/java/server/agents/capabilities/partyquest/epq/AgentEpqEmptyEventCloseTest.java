package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.9 (Z1): a companion run's leave that leaves no player in its event closes the event, so EllinPQ's one lobby on
 * the channel is free again (only dispose frees it). The check reads the event's players after the exits; a human
 * still registered (alive, or dead before he presses OK) keeps it open, and the stock exit ends it when he leaves.
 */
class AgentEpqEmptyEventCloseTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun() {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true, AgentEpqSession.Phase.BOSS,
                ben, mira, tobi, una);
    }

    @Test
    void theLastPlayersOutCloseTheEvent() {
        AgentEpqSession session = companionRun();
        when(run.event.getPlayers()).thenReturn(List.of(mira, tobi), List.of());
        when(run.event.getName()).thenReturn("EllinPQ_1");
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTerminationService.class)) {
            AgentEpqTerminationService.exitAgentsOnly(session);

            assertEquals(List.of("EPQ companion exit closed the empty event: session=" + session.sessionId()
                    + " event=EllinPQ_1"), lines.startingWith("EPQ companion exit closed"));
        }
        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(tobi);
        verify(run.event, times(1)).dispose();
    }

    @Test
    void aHumanStillInTheEventKeepsItOpen() {
        AgentEpqSession session = companionRun();
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi), List.of(ben));

        AgentEpqTerminationService.exitAgentsOnly(session);

        verify(run.event, never()).dispose();
    }

    @Test
    void aFailingExitStillLetsTheCheckRun() {
        AgentEpqSession session = companionRun();
        when(run.event.getPlayers()).thenReturn(List.of(mira, tobi), List.of());
        doThrow(new IllegalStateException("gone")).when(run.event).exitPlayer(mira);

        AgentEpqTerminationService.exitAgentsOnly(session);

        verify(run.event).exitPlayer(tobi);
        verify(run.event, times(1)).dispose();
    }

    @Test
    void aReleasePastTheClearWithNoHumanLeftClosesTheEvent() {
        AgentEpqSession session = companionRun();
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(mira, tobi, una), List.of());

        AgentEpqTerminationService.release(session, "Agent runtime removed", 5_000L, true);

        verify(run.event, times(1)).dispose();
    }

    @Test
    void aReleasePastTheClearWithTheLeaderStillInTheEventKeepsItOpen() {
        AgentEpqSession session = companionRun();
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una), List.of(ben));

        AgentEpqTerminationService.release(session, "Agent runtime removed", 5_000L, true);

        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(ben);
    }
}
