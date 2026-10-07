package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C2.8 (decision D-f): a companion run past its clear never records a failure. Anything that would fail it
 * (a phase timeout, the leader leaving, a stop) and its own after-clear limit wrap it up as completed: its companions
 * leave through the event's exit, the event and the human stay, 'LPQ after-clear wrap-up:' once. A stock run's failure
 * after the clear still disposes the event.
 */
class AgentLpqAfterClearReleaseTest {
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession cleared(boolean companion) {
        crew = fx.crew(AgentLpqDefinition.BONUS_MAP, new Point(0, 0));
        AgentLpqSession session = fx.session(companion, AgentLpqSession.Phase.CLAIMING_REWARD,
                crew.toArray(Character[]::new));
        session.freezeRewardEligibility();
        return session;
    }

    @Test
    void aFailurePastTheClearCompletesTheRun() {
        AgentLpqSession session = cleared(true);
        try (LpqLogLines log = new LpqLogLines(AgentLpqTerminationService.class)) {
            AgentLpqTerminationService.fail(session, "The LPQ event leader left the bound instance map", 5_000L);

            assertEquals(AgentLpqSession.Phase.COMPLETED, session.phase());
            assertEquals(1, log.startingWith("LPQ after-clear wrap-up: session=" + session.sessionId()).size());
            assertEquals(List.of(), log.startingWith("LPQ session failure: "));
        }
        fx.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(true),
                eq("completed"), eq(5_000L), any()));
        verify(fx.event, never()).dispose();
        verify(fx.event, never()).exitPlayer(crew.getFirst());
        for (Character companion : crew.subList(1, 6)) verify(fx.event).exitPlayer(companion);
    }

    @Test
    void theAfterClearLimitWrapsItUpTheSameWay() {
        AgentLpqSession session = cleared(true);
        fx.tick(session, 3_000L);
        assertEquals(3_000L, session.clearedAtMs());
        try (LpqLogLines log = new LpqLogLines(AgentLpqTerminationService.class)) {
            fx.tick(session, 303_001L);

            assertEquals(AgentLpqSession.Phase.COMPLETED, session.phase());
            assertEquals(1, log.startingWith("LPQ after-clear wrap-up: session=" + session.sessionId()
                    + " phase=CLAIMING_REWARD reason=The companions did not all claim within the after-clear limit"
                    + " unresolved=6").size());
        }
        verify(fx.event, never()).dispose();
    }

    @Test
    void aStockFailureAfterTheClearStillDisposes() {
        AgentLpqSession session = cleared(false);

        AgentLpqTerminationService.fail(session, "No LPQ progress before phase timeout", 5_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        verify(fx.event).dispose();
        fx.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(anyString(), anyBoolean(),
                eq("No LPQ progress before phase timeout"), anyLong(), any()));
    }
}
