package server.agents.capabilities.combat;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.epq.AgentEpqDeathDepartureGate;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqDeathDepartureGate;
import server.agents.capabilities.partyquest.lpq.AgentLpqDeathDepartureGate;
import server.agents.capabilities.partyquest.opq.AgentOpqDeathDepartureGate;
import server.agents.capabilities.partyquest.ppq.AgentPpqDeathDepartureGate;
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
 * The PPQ death-tick seam (Step 1b.5): the respawn the OPQ gate hands on asks the PPQ departure gate, whose own respawn
 * asks the RJPQ gate (LPQ, EPQ, LMPQ, OPQ, PPQ, then RJPQ). A dead PPQ member the gate holds stays down and never
 * reaches the RJPQ gate or the respawn; one it lets go respawns once. An Agent without a PPQ session goes on in the
 * same call, as before; a hold by an outer gate never reaches the PPQ gate.
 */
class AgentPpqDeathTickSeamTest {
    private static AgentRuntimeEntry deadEntry() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), mock(Character.class), null);
        AgentDeathStateRuntime.enterDeadState(entry, 1L, 0L);
        return entry;
    }

    private static void passThrough(MockedStatic<AgentLpqDeathDepartureGate> lpq,
                                    MockedStatic<AgentEpqDeathDepartureGate> epq,
                                    MockedStatic<AgentLmpqDeathDepartureGate> lmpq,
                                    MockedStatic<AgentOpqDeathDepartureGate> opq,
                                    MockedStatic<AgentRjpqDeathDepartureGate> rjpq) {
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
        opq.when(() -> AgentOpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
        rjpq.when(() -> AgentRjpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
    }

    @Test
    void aPpqHoldKeepsTheAgentDeadAndNeverAsksTheRjpqGate() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentOpqDeathDepartureGate> opq = mockStatic(AgentOpqDeathDepartureGate.class);
             MockedStatic<AgentPpqDeathDepartureGate> ppq = mockStatic(AgentPpqDeathDepartureGate.class);
             MockedStatic<AgentRjpqDeathDepartureGate> rjpq = mockStatic(AgentRjpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            passThrough(lpq, epq, lmpq, opq, rjpq);
            ppq.when(() -> AgentPpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenReturn(false);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            ppq.verify(() -> AgentPpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            rjpq.verify(() -> AgentRjpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()), never());
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
            assertTrue(AgentDeathStateRuntime.isDead(entry), "held: still dead, asked again next tick");
        }
    }

    @Test
    void aPpqProceedRespawnsOnceThroughTheRjpqGate() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentOpqDeathDepartureGate> opq = mockStatic(AgentOpqDeathDepartureGate.class);
             MockedStatic<AgentPpqDeathDepartureGate> ppq = mockStatic(AgentPpqDeathDepartureGate.class);
             MockedStatic<AgentRjpqDeathDepartureGate> rjpq = mockStatic(AgentRjpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            passThrough(lpq, epq, lmpq, opq, rjpq);
            ppq.when(() -> AgentPpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return true;
            });

            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            rjpq.verify(() -> AgentRjpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }

    @Test
    void anAgentWithoutAPpqSessionRespawnsInTheSameCall() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentOpqDeathDepartureGate> opq = mockStatic(AgentOpqDeathDepartureGate.class);
             MockedStatic<AgentPpqDeathDepartureGate> ppq = mockStatic(AgentPpqDeathDepartureGate.class,
                     CALLS_REAL_METHODS);
             MockedStatic<AgentRjpqDeathDepartureGate> rjpq = mockStatic(AgentRjpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            passThrough(lpq, epq, lmpq, opq, rjpq);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            ppq.verify(() -> AgentPpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }

    @Test
    void anOuterHoldNeverReachesThePpqGate() {
        for (int holder = 0; holder < 4; holder++) {
            AgentRuntimeEntry entry = deadEntry();
            try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
                 MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
                 MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
                 MockedStatic<AgentOpqDeathDepartureGate> opq = mockStatic(AgentOpqDeathDepartureGate.class);
                 MockedStatic<AgentPpqDeathDepartureGate> ppq = mockStatic(AgentPpqDeathDepartureGate.class);
                 MockedStatic<AgentRjpqDeathDepartureGate> rjpq = mockStatic(AgentRjpqDeathDepartureGate.class);
                 MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
                passThrough(lpq, epq, lmpq, opq, rjpq);
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

                ppq.verify(() -> AgentPpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()), never());
                respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
                assertTrue(AgentDeathStateRuntime.isDead(entry), "holder " + holder);
            }
        }
    }
}
