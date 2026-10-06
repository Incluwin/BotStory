package server.agents.capabilities.combat;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.epq.AgentEpqDeathDepartureGate;
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
 * EPQ base fix 5, the death-tick seam: the stock respawn the LPQ gate runs asks the EPQ departure gate next, so a dead
 * EPQ member the event cannot spare stays down (the next tick asks again) and one it can spare respawns once. An Agent
 * without an EPQ session respawns in the same call, as before (KPQ, HPQ, LPQ, the field).
 */
class AgentEpqDeathTickSeamTest {
    private static AgentRuntimeEntry deadEntry() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), mock(Character.class), null);
        AgentDeathStateRuntime.enterDeadState(entry, 1L, 0L);
        return entry;
    }

    /** The LPQ gate as it answers an Agent without an LPQ session: it runs the respawn it was given. */
    private static void lpqPassesThrough(MockedStatic<AgentLpqDeathDepartureGate> lpq) {
        lpq.when(() -> AgentLpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
    }

    @Test
    void anEpqHoldKeepsTheAgentDead() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            lpqPassesThrough(lpq);
            epq.when(() -> AgentEpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenReturn(false);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            epq.verify(() -> AgentEpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
            assertTrue(AgentDeathStateRuntime.isDead(entry), "held: still dead, asked again next tick");
        }
    }

    @Test
    void anEpqProceedRespawnsOnce() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            lpqPassesThrough(lpq);
            epq.when(() -> AgentEpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return true;
            });

            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }

    @Test
    void anAgentWithoutAnEpqSessionRespawnsInTheSameCall() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class,
                     CALLS_REAL_METHODS);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            lpqPassesThrough(lpq);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            epq.verify(() -> AgentEpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }

    @Test
    void anLpqHoldNeverReachesTheEpqGate() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLpqDeathDepartureGate> lpq = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentEpqDeathDepartureGate> epq = mockStatic(AgentEpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            lpq.when(() -> AgentLpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenReturn(false);

            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            epq.verify(() -> AgentEpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong()), never());
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());
            assertTrue(AgentDeathStateRuntime.isDead(entry));
        }
    }
}
