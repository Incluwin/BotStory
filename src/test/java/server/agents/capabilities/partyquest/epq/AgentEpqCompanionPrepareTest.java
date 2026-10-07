package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Step 2.3: a companion run waits for its human leader at Ellin. Nobody announces anything and nobody talks to Ellin:
 * he clicks her himself. A leader who has not taken the party in within the entry wait ends the run without a word,
 * online or not. A stock run keeps its lobby lines and its own clock.
 */
class AgentEpqCompanionPrepareTest {
    private static final long START = 1_000L;
    private static final long ENTRY_WAIT_MS = 30_000L;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.RECRUIT_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.RECRUIT_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.RECRUIT_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.RECRUIT_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aCompanionInTheLobbySaysNothingAndLeavesEllinToItsLeader() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L); // past Mira's lobby chat time

        verify(run.party, never()).sendPartyChat(any(), anyString());
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        verifyNoInteractions(run.epq);
        verify(run.actions).stop(run.entry(mira));
    }

    @Test
    void pinAStockLobbyStillSaysItsReadyLine() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.PREPARING, ben, mira, tobi, una);

        AgentEpqCoordinator.tickMember(session, run.entry(mira), mira, 2_000L);

        verify(run.party).sendPartyChat(mira, "Ready for EPQ!");
    }

    @Test
    void aLeaderWhoDoesNotGoInEndsTheRunWithoutAWordAfterTheEntryWait() {
        AgentEpqSession session = run.unboundSession(AgentEpqSession.Mode.HUMAN_ASSISTED, true, ben, mira, tobi, una);
        when(ben.getEventInstance()).thenReturn(null);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, START + ENTRY_WAIT_MS - 1L);
            assertEquals(AgentEpqSession.Phase.PREPARING, session.phase());

            AgentEpqCoordinator.tickSession(session, START + ENTRY_WAIT_MS);

            assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
            assertEquals("The companion run's leader did not take the party into EPQ within the entry wait",
                    session.failure());
            assertEquals(1, lines.startingWith("EPQ session failure:").size());
        }
        verify(run.party, never()).sendPartyChat(any(), anyString());
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void anOfflineLeaderEndsTheRunAfterTheEntryWaitToo() {
        AgentEpqSession session = run.unboundSession(AgentEpqSession.Mode.HUMAN_ASSISTED, true, ben, mira, tobi, una);
        run.offline(ben);

        AgentEpqCoordinator.tickSession(session, START + ENTRY_WAIT_MS - 1L);
        assertEquals(AgentEpqSession.Phase.PREPARING, session.phase());
        AgentEpqCoordinator.tickSession(session, START + ENTRY_WAIT_MS);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        assertEquals("The companion run's leader did not take the party into EPQ within the entry wait",
                session.failure());
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void pinAStockRunIsStillPreparingThen() {
        AgentEpqSession online = run.unboundSession(AgentEpqSession.Mode.HUMAN_ASSISTED, false, ben, mira, tobi, una);
        when(ben.getEventInstance()).thenReturn(null);

        AgentEpqCoordinator.tickSession(online, START + ENTRY_WAIT_MS);

        assertEquals(AgentEpqSession.Phase.PREPARING, online.phase());
        run.offline(ben);
        AgentEpqCoordinator.tickSession(online, START + ENTRY_WAIT_MS + 1L);
        assertEquals(AgentEpqSession.Phase.PREPARING, online.phase());
        assertEquals(List.of(), List.of(online.failure()).stream().filter(text -> !text.isEmpty()).toList());
    }
}
