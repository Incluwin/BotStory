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
}
