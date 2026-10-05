package server.agents.capabilities.combat;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.lpq.AgentLpqDeathDepartureGate;
import server.agents.capabilities.recovery.AgentRespawnCoordinator;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * LPQ base fix 1, the death-tick seam: a due respawn asks the LPQ departure gate first. A held Agent stays dead (the
 * frame is still consumed, and the next tick asks again); a released one respawns once; nothing asks before the respawn
 * is due. The public tick routes the stock respawn through the gate.
 */
class AgentLpqDeathTickSeamTest {
    private static AgentRuntimeEntry deadEntry(long deadUntilMs) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), mock(Character.class), null);
        AgentDeathStateRuntime.enterDeadState(entry, deadUntilMs, 0L);
        return entry;
    }

    @Test
    void aHeldRespawnLeavesTheAgentDeadUntilTheGateLetsItGo() {
        AgentRuntimeEntry entry = deadEntry(1_500L);
        AtomicInteger respawns = new AtomicInteger();
        Runnable respawn = () -> {
            respawns.incrementAndGet();
            AgentDeathStateRuntime.clear(entry);
        };
        AtomicInteger asked = new AtomicInteger();
        boolean[] proceed = {false};
        AgentDeathTickCoordinator.RespawnGate gate = (agent, action, nowMs) -> {
            asked.incrementAndGet();
            if (!proceed[0]) return false;
            action.run();
            return true;
        };

        boolean consumed = AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), () -> false, (e, a) -> { },
                AgentDeathTickCoordinator.gatedRespawn(entry.bot(), respawn, () -> 1_500L, gate), () -> 1_500L);

        assertTrue(consumed, "a held Agent's frame is still the dead tick");
        assertEquals(1, asked.get());
        assertEquals(0, respawns.get());
        assertTrue(AgentDeathStateRuntime.isDead(entry));
        assertTrue(AgentDeathStateRuntime.isRespawnDue(entry, 1_600L), "it asks again next tick");

        proceed[0] = true;
        AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), () -> false, (e, a) -> { },
                AgentDeathTickCoordinator.gatedRespawn(entry.bot(), respawn, () -> 1_600L, gate), () -> 1_600L);

        assertEquals(2, asked.get());
        assertEquals(1, respawns.get());
        assertFalse(AgentDeathStateRuntime.isDead(entry));
    }

    @Test
    void theGateIsNotAskedBeforeTheRespawnIsDue() {
        AgentRuntimeEntry entry = deadEntry(1_500L);
        AtomicInteger asked = new AtomicInteger();
        AgentDeathTickCoordinator.RespawnGate gate = (agent, action, nowMs) -> {
            asked.incrementAndGet();
            action.run();
            return true;
        };

        assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), () -> false, (e, a) -> { },
                AgentDeathTickCoordinator.gatedRespawn(entry.bot(), () -> { }, () -> 1_200L, gate), () -> 1_200L));

        assertEquals(0, asked.get());
    }

    @Test
    void thePublicTickRoutesTheStockRespawnThroughTheLpqGate() {
        AgentRuntimeEntry entry = deadEntry(1L);
        try (MockedStatic<AgentLpqDeathDepartureGate> gate = mockStatic(AgentLpqDeathDepartureGate.class);
             MockedStatic<AgentRespawnCoordinator> respawn = mockStatic(AgentRespawnCoordinator.class)) {
            gate.when(() -> AgentLpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenReturn(false);

            assertTrue(AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null));

            gate.verify(() -> AgentLpqDeathDepartureGate.respawnOrHold(eq(entry.bot()), any(), anyLong()), times(1));
            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(any(), any()), never());

            gate.when(() -> AgentLpqDeathDepartureGate.respawnOrHold(any(), any(), anyLong())).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return true;
            });
            AgentDeathTickCoordinator.handleDeadTick(entry, entry.bot(), null);

            respawn.verify(() -> AgentRespawnCoordinator.respawnAtNearestTown(entry, entry.bot()), times(1));
        }
    }
}
