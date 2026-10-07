package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 4.2: each failure site of a companion run says its parting once, by a companion, before 'EPQ session failure:'
 * (whose text and reason are today's). Nothing is said before the party went in, nothing ends a run past the clear,
 * a stock run hears no parting, and two threads failing one run at once give one line and one failure.
 */
class AgentEpqCompanionFailTest {
    private static final long TIMER = 1_800_000L;

    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_TWO_MAP);

    AgentEpqCompanionFailTest() {
        run.party(ben, mira, tobi, una);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession companionRun() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_TWO, ben, mira, tobi, una);
        session.markEnteredEvent(2_000L);
        for (Character member : List.of(ben, mira, tobi, una)) session.markSeenInEvent(member.getId());
        return session;
    }

    private void saidOnce(Character speaker, String line) {
        verify(run.packets, times(1)).broadcastChatText(speaker, line, false, 0);
    }

    @Test
    void theTimerSaysTimeout() {
        AgentEpqSession session = companionRun();
        try (EpqLogLines companion = new EpqLogLines(AgentEpqCompanionRun.class);
             EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L + TIMER + 2_000L);
            AgentEpqCoordinator.tickSession(session, 2_000L + TIMER + 3_000L);

            assertEquals(List.of("EPQ parting: session=" + session.sessionId() + " kind=TIMEOUT speaker=Mira"),
                    companion.startingWith("EPQ parting:"));
            assertEquals(1, coordinator.startingWith("EPQ session failure:").size());
        }
        saidOnce(mira, "we ran out of time. we'll get the golem next time.");
        assertEquals("EPQ event timer expired", session.failure());
    }

    @Test
    void theEventEndingUnderTheRunSaysWhy() {
        AgentEpqSession session = companionRun();
        when(ben.getEventInstance()).thenReturn(null); // the event no longer holds the leader

        AgentEpqCoordinator.tickSession(session, 5_000L);

        saidOnce(mira, "we can't finish this one. let's head out.");
        assertEquals("The EPQ event instance ended or expired", session.failure());
    }

    @Test
    void theEventEndingAfterTheLeaderWentDownAtTheGolemIsHisDefeat() {
        AgentEpqSession session = companionRun();
        session.markLeaderWentDown();
        when(ben.getEventInstance()).thenReturn(null);

        AgentEpqCoordinator.tickSession(session, 5_000L);

        saidOnce(mira, "Ben, you went down, so we can't finish this one. let's head out.");
    }

    @Test
    void theEventEndingAtItsTimerIsATimeout() {
        AgentEpqSession session = companionRun();
        when(ben.getEventInstance()).thenReturn(null);

        AgentEpqCoordinator.tickSession(session, 2_000L + TIMER - 2_000L);

        saidOnce(mira, "we ran out of time. we'll get the golem next time.");
    }

    @Test
    void aCompanionInAnotherEventEndsTheRunWithTheGenericLine() {
        AgentEpqSession session = companionRun();
        when(tobi.getEventInstance()).thenReturn(mock(EventInstanceManager.class));

        AgentEpqCoordinator.tickMember(session, run.entry(tobi), tobi, 5_000L);

        saidOnce(mira, "we can't finish this one. let's head out.");
        assertEquals("EPQ member entered a different event instance", session.failure());
    }

    @Test
    void nothingIsSaidBeforeThePartyWentIn() {
        AgentEpqSession session = run.unboundSession(AgentEpqSession.Mode.HUMAN_ASSISTED, true, ben, mira, tobi, una);
        for (Character member : List.of(ben, mira, tobi, una)) {
            run.onMap(member, AgentEpqDefinition.RECRUIT_MAP);
            when(member.getEventInstance()).thenReturn(null);
        }
        try (EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 1_000L + 30_000L);

            assertEquals(1, coordinator.startingWith("EPQ session failure:").size());
        }
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void noPartingIsSaidBeforeThePartyWentInWhateverItsKind() {
        AgentEpqSession session = run.unboundSession(AgentEpqSession.Mode.HUMAN_ASSISTED, true, ben, mira, tobi, una);
        for (Character member : List.of(ben, mira, tobi, una)) {
            run.onMap(member, AgentEpqDefinition.RECRUIT_MAP);
            when(member.getEventInstance()).thenReturn(null);
        }

        AgentEpqCompanionRun.fail(session, "The EPQ party was disbanded", AgentEpqParting.GENERIC, null, 5_000L);

        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        assertEquals("The EPQ party was disbanded", session.failure());
    }

    @Test
    void nothingEndsARunPastTheClear() {
        AgentEpqSession session = companionRun();
        when(run.event.isEventCleared()).thenReturn(true);
        try (EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCompanionRun.fail(session, "EPQ member entered a different event instance",
                    AgentEpqParting.GENERIC, null, 5_000L);

            assertEquals(List.of(), coordinator.startingWith("EPQ session failure:"));
        }
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        assertEquals(AgentEpqSession.Phase.EXITING, session.phase(), "its companions leave instead");
    }

    @Test
    void pinAStockRunHearsNoParting() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_TWO, ben, mira, tobi, una);

        AgentEpqCoordinator.tickSession(session, 1_000L + TIMER);

        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        assertEquals("EPQ event timer expired", session.failure());
    }

    @Test
    void twoThreadsFailingOneRunGiveOneLineAndOneFailure() throws Exception {
        AgentEpqSession session = companionRun();
        CountDownLatch start = new CountDownLatch(1);
        try (EpqLogLines companion = new EpqLogLines(AgentEpqCompanionRun.class);
             EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            Thread[] threads = new Thread[2];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    AgentEpqCompanionRun.fail(session, "Too many of the EPQ companion run went down",
                            AgentEpqParting.of(AgentEpqParting.Kind.WENT_DOWN), null, 5_000L);
                });
                threads[i].start();
            }
            start.countDown();
            for (Thread thread : threads) thread.join(5_000L);

            // Mockito's static stubs belong to the test's thread, so the two threads are counted by their log lines.
            assertEquals(1, companion.startingWith("EPQ parting:").size());
            assertEquals(1, coordinator.startingWith("EPQ session failure:").size());
        }
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }
}
