package server.agents.capabilities.combat;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.epq.AgentEpqDeathDepartureGate;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqDeathDepartureGate;
import server.agents.capabilities.partyquest.lpq.AgentLpqDeathDepartureGate;
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
 * P6h B4, the death-tick seam: the stock respawn the EPQ gate runs asks the LMPQ departure gate next (LPQ, then EPQ,
 * then LMPQ), so a dead LMPQ member the gate holds stays down (the next tick asks again) and one it lets go respawns
 * once. An Agent without an LMPQ session respawns in the same call, as before; a hold by an outer gate never reaches
 * the LMPQ gate.
 */
class AgentLmpqDeathTickSeamTest {
    private static AgentRuntimeEntry deadEntry() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), mock(Character.class), null);
        AgentDeathStateRuntime.enterDeadState(entry, 1L, 0L);
        return entry;
    }

    /** The LPQ and EPQ gates as they answer an Agent without their sessions: each runs the respawn it was given. */
    private static void outerGatesPassThrough(MockedStatic<AgentLpqDeathDepartureGate> lpq,
                                              MockedStatic<AgentEpqDeathDepartureGate> epq) {
        lpq.when(() -> AgentLpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
        epq.when(() -> AgentEpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
    }

    @Test
    void anLmpqHoldKeepsTheAgentDead() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq);
            lmpq.when(() -> AgentLmpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenReturn(false);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            lmpq.verify(() -> AgentLmpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
            assertTrue(AgentDeathStateRuntime.isDead(entry), "held: still dead, asked again next tick");
        }
    }

    @Test
    void anLmpqProceedRespawnsOnce() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq);
            lmpq.when(() -> AgentLmpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return true;
            });

            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }

    @Test
    void anAgentWithoutAnLmpqSessionRespawnsInTheSameCall() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class,
                     CALLS_REAL_METHODS);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            lmpq.verify(() -> AgentLmpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }

    @Test
    void anLpqHoldNeverReachesTheLmpqGate() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq);
            lpq.when(() -> AgentLpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenReturn(false);

            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            epq.verify(() -> AgentEpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()), never());
            lmpq.verify(() -> AgentLmpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()), never());
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
            assertTrue(AgentDeathStateRuntime.isDead(entry));
        }
    }

    @Test
    void anEpqHoldNeverReachesTheLmpqGate() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentLmpqDeathDepartureGate> lmpq = mockStatic(AgentLmpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            outerGatesPassThrough(lpq, epq);
            epq.when(() -> AgentEpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenReturn(false);

            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            epq.verify(() -> AgentEpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            lmpq.verify(() -> AgentLmpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()), never());
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
            assertTrue(AgentDeathStateRuntime.isDead(entry));
        }
    }
}
