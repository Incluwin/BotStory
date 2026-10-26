package server.agents.integration.cosmic;

import server.TimerManager;
import server.agents.integration.SchedulerGateway;

import java.util.concurrent.ScheduledFuture;

public enum CosmicSchedulerGateway implements SchedulerGateway {
    INSTANCE;

    @Override
    public ScheduledFuture<?> schedule(Runnable action, long delayMs) {
        return TimerManager.getInstance().schedule(action, delayMs);
    }

    @Override
    public ScheduledFuture<?> register(Runnable action, long periodMs) {
        return TimerManager.getInstance().register(action, periodMs);
    }

    @Override
    public ScheduledFuture<?> scheduleLowPriority(Runnable action, long delayMs) {
        return TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, action, delayMs);
    }

    @Override
    public ScheduledFuture<?> registerAgentLoop(Runnable action, long periodMs) {
        return TimerManager.getInstance().register(TimerManager.SchedulerLane.AGENT, action, periodMs, periodMs);
    }

    @Override
    public ScheduledFuture<?> scheduleAgentLoop(Runnable action, long delayMs) {
        return TimerManager.getInstance().schedule(TimerManager.SchedulerLane.AGENT, action, delayMs);
    }

    @Override
    public ScheduledFuture<?> registerLowPriority(Runnable action, long periodMs, long initialDelayMs) {
        return TimerManager.getInstance().register(
                TimerManager.SchedulerLane.LOW_PRIORITY, action, periodMs, initialDelayMs);
    }
}
