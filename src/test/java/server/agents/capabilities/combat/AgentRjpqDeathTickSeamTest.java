package server.agents.capabilities.combat;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.epq.AgentEpqDeathDepartureGate;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqDeathDepartureGate;
import server.agents.capabilities.partyquest.lpq.AgentLpqDeathDepartureGate;
import server.agents.capabilities.partyquest.opq.AgentOpqDeathDepartureGate;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqDeathDepartureGate;
import server.agents.capabilities.recovery.AgentRespawnCoordinator;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * The RJPQ death-tick seam: the stock respawn the OPQ gate runs asks the RJPQ departure gate next (LPQ, EPQ, LMPQ, OPQ,
 * then RJPQ), so a dead RJPQ member the gate holds stays down (the next tick asks again) and one it lets go respawns
 * once. An Agent without an RJPQ session respawns in the same call, as before; a hold by an outer gate never reaches
 * the RJPQ gate.
 */
class AgentRjpqDeathTickSeamTest {
    private static AgentRuntimeEntry deadEntry() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), mock(Character.class), null);
        AgentDeathStateRuntime.enterDeadState(entry, 1L, 0L);
        return entry;
    }

    /** The outer gates as they answer an Agent without their sessions: each runs the respawn it was given. */
    private static void outerGatesPassThrough(MockedStatic<AgentLpqDeathDepartureGate> lpq,
                                              MockedStatic<AgentEpqDeathDepartureGate> epq,
                                              MockedStatic<AgentLmpqDeathDepartureGate> lmpq,
                                              MockedStatic<AgentOpqDeathDepartureGate> opq) {
        opq.when(() -> AgentOpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
        lpq.when(() -> AgentLpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
        epq.when(() -> AgentEpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
        lmpq.when(() -> AgentLmpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
    }

    @Test
    void anRjpqHoldKeepsTheAgentDead() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentOpqDeathDepartureGate> opq = mockStatic(AgentOpqDeathDepartureGate.class);
             MockedStatic<AgentRjpqDeathDepartureGate> rjpq = mockStatic(AgentRjpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq, lmpq, opq);
            rjpq.when(() -> AgentRjpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenReturn(false);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            rjpq.verify(() -> AgentRjpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
            assertTrue(AgentDeathStateRuntime.isDead(entry), "held: still dead, asked again next tick");
        }
    }

    @Test
    void anRjpqProceedRespawnsOnce() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentOpqDeathDepartureGate> opq = mockStatic(AgentOpqDeathDepartureGate.class);
             MockedStatic<AgentRjpqDeathDepartureGate> rjpq = mockStatic(AgentRjpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq, lmpq, opq);
            rjpq.when(() -> AgentRjpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return true;
            });

            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }

    @Test
    void anAgentWithoutAnRjpqSessionRespawnsInTheSameCall() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentOpqDeathDepartureGate> opq = mockStatic(AgentOpqDeathDepartureGate.class);
             MockedStatic<AgentRjpqDeathDepartureGate> rjpq = mockStatic(AgentRjpqDeathDepartureGate.class,
                     CALLS_REAL_METHODS);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq, lmpq, opq);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            rjpq.verify(() -> AgentRjpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }

    @Test
    void anLpqHoldNeverReachesTheRjpqGate() {
        assertAnOuterHoldNeverReachesTheRjpqGate(0);
    }

    @Test
    void anEpqHoldNeverReachesTheRjpqGate() {
        assertAnOuterHoldNeverReachesTheRjpqGate(1);
    }

    @Test
    void anLmpqHoldNeverReachesTheRjpqGate() {
        assertAnOuterHoldNeverReachesTheRjpqGate(2);
    }

    @Test
    void anOpqHoldNeverReachesTheRjpqGate() {
        assertAnOuterHoldNeverReachesTheRjpqGate(3);
    }

    /** The outer gate at {@code holder} (0 LPQ, 1 EPQ, 2 LMPQ, 3 OPQ) holds; the RJPQ gate and the respawn never run. */
    private static void assertAnOuterHoldNeverReachesTheRjpqGate(int holder) {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentOpqDeathDepartureGate> opq = mockStatic(AgentOpqDeathDepartureGate.class);
             MockedStatic<AgentRjpqDeathDepartureGate> rjpq = mockStatic(AgentRjpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq, lmpq, opq);
            switch (holder) {
                case 0 -> lpq.when(() -> AgentLpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()))
                        .thenReturn(false);
                case 1 -> epq.when(() -> AgentEpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()))
                        .thenReturn(false);
                case 2 -> lmpq.when(() -> AgentLmpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()))
                        .thenReturn(false);
                default -> opq.when(() -> AgentOpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()))
                        .thenReturn(false);
            }

            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            if (holder == 3) {
                opq.verify(() -> AgentOpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()),
                        times(1));
            }
            rjpq.verify(() -> AgentRjpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()), never());
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
            assertTrue(AgentDeathStateRuntime.isDead(entry));
        }
    }
}
