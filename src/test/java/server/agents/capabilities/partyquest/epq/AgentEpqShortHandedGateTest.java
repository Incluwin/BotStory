package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 4.4: in a companion run at the stock floor the departure gate holds a dead companion as before; when no other
 * companion stands, the dying companion's own tick ends the run WENT_DOWN in its own words (with every companion down
 * no other tick runs). Its next due tick finds the run ended and lets it go. A standing companion, or a stock run,
 * keeps today's hold; a companion that cannot be loaded ends the run without a line; the gate and the lease holder's
 * SHORT_HANDED at once give one line and one failure.
 */
class AgentEpqShortHandedGateTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_ONE_MAP);
    private final AtomicInteger respawns = new AtomicInteger();

    /** EllinPQ's event as the gate reads it: four registered, Ben leads, not cleared. */
    private final AgentEpqDeathDepartureGate.EventView event = new AgentEpqDeathDepartureGate.EventView() {
        @Override
        public boolean disposed() { return false; }

        @Override
        public boolean cleared() { return false; }

        @Override
        public int leaderId() { return ben.getId(); }

        @Override
        public Set<Integer> registeredIds() { return Set.of(1, 2, 3, 4); }
    };

    AgentEpqShortHandedGateTest() {
        when(run.actions.liveMonsterCount(any(), any())).thenReturn(5);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(boolean companionRun) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun, AgentEpqSession.Phase.STAGE_ONE,
                ben, mira, tobi, una);
    }

    private boolean gate(AgentEpqSession session, Character dead, long nowMs) {
        return AgentEpqDeathDepartureGate.respawnOrHold(session, dead.getId(), dead.getName(), event,
                respawns::incrementAndGet, nowMs);
    }

    @Test
    void theLastCompanionDownEndsTheRunInItsOwnWords() {
        AgentEpqSession session = session(true);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class)) {
            when(mira.getHp()).thenReturn(0);
            assertFalse(gate(session, mira, 2_000L), "held");
            when(tobi.getHp()).thenReturn(0);
            assertFalse(gate(session, tobi, 2_000L), "held");
            assertFalse(session.terminal(), "Una still stood");

            when(una.getHp()).thenReturn(0);
            assertFalse(gate(session, una, 3_000L), "held, and the run ends");

            assertEquals(List.of("EPQ parting: session=" + session.sessionId() + " kind=WENT_DOWN speaker=Una"),
                    lines.startingWith("EPQ parting:"));
        }
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        verify(run.packets, times(1)).broadcastChatText(una, "sorry, i went down too. let's regroup outside.",
                false, 0);
        assertEquals(0, respawns.get());

        assertTrue(gate(session, una, 4_000L), "the next due tick lets it go");
        assertEquals(1, respawns.get());
    }

    @Test
    void pinACompanionStillStandingKeepsTheHold() {
        AgentEpqSession session = session(true);
        when(mira.getHp()).thenReturn(0);
        when(una.getHp()).thenReturn(0);

        assertFalse(gate(session, una, 3_000L));

        assertFalse(session.terminal());
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void pinAStockRunKeepsTheHold() {
        AgentEpqSession session = session(false);
        when(mira.getHp()).thenReturn(0);
        when(tobi.getHp()).thenReturn(0);
        when(una.getHp()).thenReturn(0);

        assertFalse(gate(session, una, 3_000L));

        assertFalse(session.terminal());
    }

    @Test
    void aCompanionThatCannotBeLoadedEndsTheRunWithoutALine() {
        AgentEpqSession session = session(true);
        when(mira.getHp()).thenReturn(0);
        when(tobi.getHp()).thenReturn(0);
        run.offline(una);

        assertFalse(AgentEpqDeathDepartureGate.respawnOrHold(session, una.getId(), "Una", event,
                respawns::incrementAndGet, 3_000L));

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void theGateAndTheLeaseHolderAtOnceGiveOneLineAndOneFailure() throws Exception {
        AgentEpqSession session = session(true);
        when(mira.getHp()).thenReturn(0);
        when(tobi.getHp()).thenReturn(0);
        when(una.getHp()).thenReturn(0);
        CountDownLatch start = new CountDownLatch(1);
        try (EpqLogLines companion = new EpqLogLines(AgentEpqCompanionRun.class);
             EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            Thread gateThread = new Thread(() -> {
                await(start);
                gate(session, una, 3_000L);
            });
            Thread passThread = new Thread(() -> {
                await(start);
                AgentEpqCoordinator.tickSession(session, 3_000L);
            });
            gateThread.start();
            passThread.start();
            start.countDown();
            gateThread.join(5_000L);
            passThread.join(5_000L);

            assertEquals(1, companion.startingWith("EPQ parting:").size());
            assertEquals(1, coordinator.startingWith("EPQ session failure:").size());
        }
        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
