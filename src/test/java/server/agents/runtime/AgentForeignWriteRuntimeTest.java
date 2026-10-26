package server.agents.runtime;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMoveTargetStateRuntime;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.agents.runtime.scheduler.AgentSchedulerConfig;
import server.agents.runtime.scheduler.AgentSchedulerMode;
import server.agents.runtime.scheduler.AgentTickScheduler;

import java.awt.Point;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Race H1 (docs/SCALE_PLATFORM_PLAN.md section 6): in central-sharded mode a write from one Agent's tick to another
 * Agent's movement state goes through the target's mailbox, and its own tick applies it; central-sequential and writes
 * off the scheduler land in place as before.
 */
class AgentForeignWriteRuntimeTest {
    @AfterEach
    void clear() {
        System.clearProperty("agents.scheduler.mode");
        AgentRuntimeRegistry.clear();
    }

    @Test
    void aWriteFromAnotherAgentsTickIsDeferredToTheTargetsMailboxInShardedMode() {
        System.setProperty("agents.scheduler.mode", "central-sharded");
        AgentRuntimeEntry holder = entry(1);
        AgentRuntimeEntry member = entry(2);

        AgentRuntimeEntry interrupted = AgentForeignWriteRuntime.enterTick(holder);
        try {
            assertTrue(AgentForeignWriteRuntime.foreign(member));
            assertFalse(AgentForeignWriteRuntime.foreign(holder)); // its own state: in place
            CosmicPrimitiveCapabilityGateway.INSTANCE.navigate(member, new Point(100, 200), true);
            CosmicPrimitiveCapabilityGateway.INSTANCE.navigate(member, new Point(300, 400), true);
        } finally {
            AgentForeignWriteRuntime.exitTick(interrupted);
        }

        assertNull(AgentMoveTargetStateRuntime.moveTarget(member)); // not written from the holder's tick
        assertEquals(1, member.actionMailbox().size()); // the second navigate replaced the first

        AgentRuntimeEntry ownTick = AgentForeignWriteRuntime.enterTick(member);
        try {
            AgentMailboxRuntime.drain(member);
        } finally {
            AgentForeignWriteRuntime.exitTick(ownTick);
        }
        assertEquals(new Point(300, 400), AgentMoveTargetStateRuntime.moveTarget(member));
        assertEquals(0, member.actionMailbox().size());
    }

    @Test
    void centralSequentialAndWritesOffTheSchedulerLandInPlace() {
        AgentRuntimeEntry holder = entry(1);
        AgentRuntimeEntry member = entry(2);
        System.setProperty("agents.scheduler.mode", "central-sequential");
        AgentRuntimeEntry interrupted = AgentForeignWriteRuntime.enterTick(holder);
        try {
            assertFalse(AgentForeignWriteRuntime.foreign(member));
            CosmicPrimitiveCapabilityGateway.INSTANCE.navigate(member, new Point(5, 6), false);
        } finally {
            AgentForeignWriteRuntime.exitTick(interrupted);
        }
        assertEquals(new Point(5, 6), AgentMoveTargetStateRuntime.moveTarget(member));

        System.setProperty("agents.scheduler.mode", "central-sharded");
        assertNull(AgentForeignWriteRuntime.ticking());
        assertFalse(AgentForeignWriteRuntime.foreign(member)); // no tick on this thread: a timer or packet thread
    }

    @Test
    void theSchedulerMarksWhoseTickRunsAndRestoresItAfter() {
        AtomicLong now = new AtomicLong(1_000L);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AgentTickScheduler scheduler = new AgentTickScheduler(now::get, (task, period) -> future);
        AgentRuntimeEntry entry = entry(7);
        AgentRuntimeRegistry.registerEntry(7, entry);
        AtomicReference<AgentRuntimeEntry> seen = new AtomicReference<>();
        scheduler.register(entry, () -> seen.set(AgentForeignWriteRuntime.ticking()), 50L);

        scheduler.tickAll();

        assertSame(entry, seen.get());
        assertNull(AgentForeignWriteRuntime.ticking());
        assertEquals(AgentSchedulerMode.CENTRAL_SEQUENTIAL, AgentSchedulerConfig.configuredMode());
    }

    private static AgentRuntimeEntry entry(int id) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        return new AgentRuntimeEntry(agent, null, null);
    }
}
