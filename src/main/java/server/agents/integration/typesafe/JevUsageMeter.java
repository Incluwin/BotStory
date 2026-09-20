package server.agents.integration.typesafe;

import java.util.concurrent.atomic.AtomicLong;

/** Request, failure, token and latency counters surfaced through the server diagnostics. */
public final class JevUsageMeter {
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong refusedCircuitOpen = new AtomicLong();
    private final AtomicLong refusedUnconfigured = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final AtomicLong maxLatencyMs = new AtomicLong();

    public void recordSuccess(JevResponse response) {
        requests.incrementAndGet();
        successes.incrementAndGet();
        inputTokens.addAndGet(response.inputTokens());
        outputTokens.addAndGet(response.outputTokens());
        totalLatencyMs.addAndGet(response.latencyMs());
        maxLatencyMs.accumulateAndGet(response.latencyMs(), Math::max);
    }

    public void recordFailure() {
        requests.incrementAndGet();
        failures.incrementAndGet();
    }

    public void recordRefusedCircuitOpen() {
        refusedCircuitOpen.incrementAndGet();
    }

    public void recordRefusedUnconfigured() {
        refusedUnconfigured.incrementAndGet();
    }

    public long requests() {
        return requests.get();
    }

    public long successes() {
        return successes.get();
    }

    public long failures() {
        return failures.get();
    }

    public long inputTokens() {
        return inputTokens.get();
    }

    public long averageLatencyMs() {
        long count = successes.get();
        return count == 0 ? 0L : totalLatencyMs.get() / count;
    }

    public String diagnostics() {
        return "requests=" + requests.get()
                + " ok=" + successes.get()
                + " failed=" + failures.get()
                + " refusedOpen=" + refusedCircuitOpen.get()
                + " refusedUnconfigured=" + refusedUnconfigured.get()
                + " inputTokens=" + inputTokens.get()
                + " outputTokens=" + outputTokens.get()
                + " avgLatencyMs=" + averageLatencyMs()
                + " maxLatencyMs=" + maxLatencyMs.get();
    }
}
