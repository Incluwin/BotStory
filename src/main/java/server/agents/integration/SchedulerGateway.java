package server.agents.integration;

import java.util.concurrent.ScheduledFuture;

@AgentGatewayAffinity(
        value = AgentGatewayThreadAffinity.ASYNC_EXTERNAL,
        rationale = "The server scheduler accepts callbacks and never runs as an Agent state mutation gateway.")
public interface SchedulerGateway {
    ScheduledFuture<?> schedule(Runnable action, long delayMs);

    ScheduledFuture<?> register(Runnable action, long periodMs);

    /** Work that may wait behind gameplay timers, such as paced chat lines and usage reports. */
    ScheduledFuture<?> scheduleLowPriority(Runnable action, long delayMs);

    ScheduledFuture<?> registerLowPriority(Runnable action, long periodMs, long initialDelayMs);

    /**
     * A central-sharded scheduler shard's loop, on threads of its own so a slow Agent tick holds only its shard and
     * never a server core timer (docs/SCALE_PLATFORM_PLAN.md section 2). The server's core timers when not overridden.
     */
    default ScheduledFuture<?> registerAgentLoop(Runnable action, long periodMs) {
        return register(action, periodMs);
    }

    /** A shard loop's immediate continuation, on the same threads as {@link #registerAgentLoop}. */
    default ScheduledFuture<?> scheduleAgentLoop(Runnable action, long delayMs) {
        return schedule(action, delayMs);
    }
}
