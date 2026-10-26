package server.agents.monitoring;

import server.agents.runtime.AgentForeignWriteRuntime;
import server.agents.runtime.activity.control.AgentWorldDirectorPolicyRuntime;
import server.agents.runtime.scheduler.AgentSchedulerConfig;
import server.agents.runtime.scheduler.AgentSchedulerMode;

import java.util.Locale;

/**
 * The periodic "Agent scheduler health" log line beside "Scale health" (docs/SCALE_PLATFORM_PLAN.md section 2): what
 * the scheduler did since the previous line, so a live log shows tick lag without anyone running @agentscheduler.
 * Queue lag is how late each Agent update started after it was due, over every update of the interval (power-of-two
 * buckets, max exact); work is one update's run time over the rolling window; counts are deltas since the last line:
 * foreign = writes routed to another Agent's mailbox (AgentForeignWriteRuntime) and those its mailbox refused;
 * director = the World Director policy's proposals, started directives and steps that found nothing to do.
 */
public final class AgentSchedulerHealthLine {
    private static AgentSchedulerMetrics.Snapshot previous;
    private static final long[] COUNTS = new long[5];

    private AgentSchedulerHealthLine() {
    }

    /** The line for the interval since the previous call, then a new interval starts. */
    public static synchronized String next() {
        AgentSchedulerMetrics.Snapshot now = AgentSchedulerMetrics.snapshot();
        AgentSchedulerMetrics.Snapshot before = previous;
        previous = now;
        AgentSchedulerMetrics.IntervalQueueLag lag = AgentSchedulerMetrics.drainIntervalQueueLag();
        AgentSchedulerMode mode = AgentSchedulerConfig.configuredMode();
        long[] counts = {AgentForeignWriteRuntime.deferredCount(), AgentForeignWriteRuntime.droppedCount(),
                AgentWorldDirectorPolicyRuntime.proposedCount(), AgentWorldDirectorPolicyRuntime.submittedCount(),
                AgentWorldDirectorPolicyRuntime.skippedCount()};
        long[] deltas = new long[counts.length];
        for (int index = 0; index < counts.length; index++) {
            deltas[index] = delta(counts[index], COUNTS[index]);
            COUNTS[index] = counts[index];
        }
        return String.format(Locale.ROOT,
                "mode=%s shards=%d updates=%d queueLagMs p50<%s p95<%s p99<%s max=%d workUs p50=%d p95=%d p99=%d"
                        + " slow=%d failed=%d deferred=%d budgetExhausted=%d continuations=%d"
                        + " foreign=%d foreignDropped=%d directorProposed=%d directorStarted=%d directorIdle=%d",
                mode.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                mode == AgentSchedulerMode.CENTRAL_SHARDED ? Math.max(1, AgentSchedulerMetrics.shardSnapshots().size()) : 1,
                lag.updates(),
                bound(lag.p50UnderMs()), bound(lag.p95UnderMs()), bound(lag.p99UnderMs()), lag.maxMs(),
                now.workDurationP50Ns() / 1_000L, now.workDurationP95Ns() / 1_000L, now.workDurationP99Ns() / 1_000L,
                delta(now.slowAgents(), before == null ? 0L : before.slowAgents()),
                delta(now.failedAgents(), before == null ? 0L : before.failedAgents()),
                delta(now.deferredWork(), before == null ? 0L : before.deferredWork()),
                delta(now.budgetExhaustions(), before == null ? 0L : before.budgetExhaustions()),
                delta(now.tickContinuations(), before == null ? 0L : before.tickContinuations()),
                deltas[0], deltas[1], deltas[2], deltas[3], deltas[4]);
    }

    private static String bound(long underMs) {
        return underMs == Long.MAX_VALUE ? "inf" : Long.toString(underMs);
    }

    private static long delta(long now, long before) {
        return Math.max(0L, now - before);
    }
}
