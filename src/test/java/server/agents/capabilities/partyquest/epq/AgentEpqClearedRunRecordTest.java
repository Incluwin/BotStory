package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 2.10: a companion run past the clear is recorded as cleared whoever ends it (a stall, the host's stop, an Agent
 * removed): its host hears success, the run reads COMPLETED, only its companions leave the event, and one line says the
 * cleared run was wrapped up. The released line keeps the reason. A companion run before the clear and a stock run past
 * the clear keep today's failure.
 */
class AgentEpqClearedRunRecordTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.BOSS_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.BOSS_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.BOSS_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.BOSS_MAP);

    AgentEpqClearedRunRecordTest() {
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(boolean companionRun) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun, AgentEpqSession.Phase.BOSS,
                ben, mira, tobi, una);
    }

    private void releasedAs(AgentEpqSession session, String reason, boolean success) {
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(success),
                eq(reason), eq(5_000L), any()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()),
                eq(!success), any(), anyLong(), any()), never());
    }

    @Test
    void aStalledCompanionRunPastTheClearIsRecordedAsCleared() {
        when(run.event.isEventCleared()).thenReturn(true);
        AgentEpqSession session = session(true);
        String reason = "EPQ stalled in BOSS for 480 seconds";
        session.fail(reason, 4_000L);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTerminationService.class)) {
            AgentEpqTerminationService.release(session, reason, 5_000L, true);

            assertEquals(List.of("EPQ cleared run wrapped up: session=" + session.sessionId() + " reason=" + reason),
                    lines.startingWith("EPQ cleared run wrapped up:"));
            assertEquals(1, lines.startingWith("EPQ session released: session=" + session.sessionId()
                    + " outcome=" + reason + " ").size(), "the released line keeps the reason");
        }
        releasedAs(session, reason, true);
        assertEquals(AgentEpqSession.Phase.COMPLETED, session.phase());
        for (Character agent : List.of(mira, tobi, una)) verify(run.event).exitPlayer(agent);
        verify(run.event, never()).exitPlayer(ben);
    }

    @Test
    void anAgentRemovedPastTheClearIsRecordedAsClearedToo() {
        when(run.event.isEventCleared()).thenReturn(true);
        AgentEpqSession session = session(true);

        AgentEpqTerminationService.release(session, "Agent runtime removed", 5_000L, true);

        releasedAs(session, "Agent runtime removed", true);
        assertEquals(AgentEpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void pinACompanionRunBeforeTheClearStillFails() {
        AgentEpqSession session = session(true);

        AgentEpqTerminationService.release(session, "Agent runtime removed", 5_000L, true);

        releasedAs(session, "Agent runtime removed", false);
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void pinAStockRunPastTheClearStillFails() {
        when(run.event.isEventCleared()).thenReturn(true);
        AgentEpqSession session = session(false);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqTerminationService.class)) {
            AgentEpqTerminationService.release(session, "EPQ stalled in REWARD for 480 seconds", 5_000L, true);

            assertEquals(List.of(), lines.startingWith("EPQ cleared run wrapped up:"));
        }
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(false),
                eq("EPQ stalled in REWARD for 480 seconds"), eq(5_000L), any()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(any(), eq(true), any(), anyLong(),
                any()), never());
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        verify(run.event).exitPlayer(ben);
    }
}
