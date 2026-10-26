package server.agents.runtime;

import server.agents.runtime.mailbox.AgentMailboxOptions;
import server.agents.runtime.mailbox.AgentMailboxSubmission;
import server.agents.runtime.scheduler.AgentSchedulerConfig;
import server.agents.runtime.scheduler.AgentSchedulerMode;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Cross-Agent writes in central-sharded mode (docs/SCALE_PLATFORM_PLAN.md section 6, race H1). A party-quest lease
 * holder's coordinator pass commands its members' movement and combat (navigate, grind, stop) from its own tick, and
 * each member's own tick reads and clears the same mode and target state. Central-sequential runs every tick on one
 * loop, so those writes land in place. In central-sharded mode the member ticks on another shard at the same time, so
 * a write from another Agent's tick goes into the member's mailbox instead, which the member's own tick drains before
 * its gameplay: the member's state keeps one writer. Coalesced per command, so a coordinator that repeats a command
 * every pass never floods the mailbox; not woken, so the member's cadence is unchanged.
 */
public final class AgentForeignWriteRuntime {
    private static final boolean ENABLED = config.AgentTuning.booleanValue(
            "server.agents.runtime.AgentForeignWriteRuntime.ENABLED");
    private static final boolean EXTERNAL = config.AgentTuning.booleanValue(
            "server.agents.runtime.AgentForeignWriteRuntime.ROUTE_EXTERNAL_WRITES");
    private static final ThreadLocal<AgentRuntimeEntry> TICKING = new ThreadLocal<>();
    private static final LongAdder DEFERRED = new LongAdder();
    private static final LongAdder DROPPED = new LongAdder();

    private AgentForeignWriteRuntime() {
    }

    /** The scheduler runs {@code entry}'s tick on this thread from now on; returns the tick it interrupts, if any. */
    public static AgentRuntimeEntry enterTick(AgentRuntimeEntry entry) {
        AgentRuntimeEntry previous = TICKING.get();
        TICKING.set(entry);
        return previous;
    }

    /** The tick {@link #enterTick} started is over. */
    public static void exitTick(AgentRuntimeEntry previous) {
        if (previous == null) {
            TICKING.remove();
        } else {
            TICKING.set(previous);
        }
    }

    /** The Agent whose tick runs on this thread now; null off the scheduler. */
    public static AgentRuntimeEntry ticking() {
        return TICKING.get();
    }

    /**
     * True when a write to {@code target} now must go to its mailbox ({@link #defer}) instead of landing in place:
     * another Agent's tick is running on this thread and the scheduler is central-sharded, or no Agent tick is
     * (a packet handler, a timer, the Director bridge) and ROUTE_EXTERNAL_WRITES routes those too.
     */
    public static boolean foreign(AgentRuntimeEntry target) {
        if (target == null) {
            return false;
        }
        AgentRuntimeEntry ticking = TICKING.get();
        if (ticking == target) {
            return false;
        }
        return ticking != null ? routing() : externalRouting();
    }

    /**
     * Puts {@code write} in {@code target}'s mailbox for its own tick to run. {@code command} names the coalescing slot:
     * the newest command of a kind replaces a pending one. A write from another Agent's tick does not wake the target
     * (its cadence stays); one from off the scheduler does, as any mailbox dispatch does. A closed or full mailbox (a
     * session that ended, an Agent that is not ticking) drops it, as it would any other mailbox action.
     */
    public static void defer(AgentRuntimeEntry target, String command, Consumer<AgentRuntimeEntry> write) {
        AgentMailboxSubmission<Object> submission = target.actionMailbox().submit(
                target.sessionGeneration(),
                owner -> {
                    write.accept(owner);
                    return null;
                },
                AgentMailboxOptions.coalesceLatest("foreign-write:" + command));
        (submission.accepted() ? DEFERRED : DROPPED).increment();
        if (submission.accepted() && TICKING.get() == null) {
            server.agents.runtime.scheduler.AgentScheduler.wake(target);
        }
    }

    static boolean routing() {
        return ENABLED && AgentSchedulerConfig.configuredMode() == AgentSchedulerMode.CENTRAL_SHARDED;
    }

    /**
     * Writes from off the scheduler go through the target's mailbox too (agent-engine.yaml ROUTE_EXTERNAL_WRITES, any
     * central mode): docs/SCALE_PLATFORM_PLAN.md section 3, the mailbox bypasses.
     */
    public static boolean externalRouting() {
        return EXTERNAL && AgentSchedulerConfig.configuredMode() != AgentSchedulerMode.LEGACY_PER_AGENT;
    }

    /** Writes routed to a target's mailbox since boot, and those its mailbox refused (closed or full). */
    public static long deferredCount() {
        return DEFERRED.sum();
    }

    public static long droppedCount() {
        return DROPPED.sum();
    }
}
