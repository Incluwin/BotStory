package server.agents.runtime.activity.control;

import server.agents.runtime.state.AgentCapabilityStateKey;

/**
 * Bounded scheduler cadence and last failure for durable directive polling. A poll that found nothing to do records
 * the files' change stamp ({@link #idle}); later polls skip the file reads while the stamp is unchanged
 * ({@link #unchanged}), with a full read at least every IDLE_FULL_POLL_MS.
 */
public final class AgentWorldDirectivePollState {
    public static final AgentCapabilityStateKey<AgentWorldDirectivePollState> STATE_KEY =
            new AgentCapabilityStateKey<>("runtime.world-director-poll",
                    AgentWorldDirectivePollState.class, AgentWorldDirectivePollState::new);

    private long lastPollAtMs;
    private String lastResult = "";
    private boolean idle;
    private long idleStamp;
    private long idleSinceMs;

    public synchronized boolean claim(long nowMs, long intervalMs) {
        if (intervalMs <= 0L || nowMs < 0L
                || lastPollAtMs > 0L && nowMs - lastPollAtMs < intervalMs) return false;
        lastPollAtMs = nowMs;
        return true;
    }

    public synchronized void result(String value) {
        lastResult = value == null ? "" : value.trim();
    }

    /** The last poll found nothing to do with the files at {@code stamp}. */
    public synchronized void idle(long stamp, long nowMs) {
        if (!idle || stamp != idleStamp) {
            idleSinceMs = nowMs;
        }
        idle = true;
        idleStamp = stamp;
    }

    /** The last poll found work (a directive pending, claimed or in progress): the next one reads the files. */
    public synchronized void busy() {
        idle = false;
    }

    /** True while nothing changed since an idle poll, and its full read is younger than {@code fullPollMs}. */
    public synchronized boolean unchanged(long stamp, long nowMs, long fullPollMs) {
        return idle && stamp == idleStamp && nowMs - idleSinceMs < fullPollMs;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(lastPollAtMs, lastResult);
    }

    public record Snapshot(long lastPollAtMs, String lastResult) { }
}
