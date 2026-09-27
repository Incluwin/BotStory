package server.admin;

import server.TimerManager;

import java.util.concurrent.Future;
import java.util.function.LongSupplier;

/**
 * Lateness of one TimerManager lane, for /runtime/budget: a single task reschedules itself every
 * {@link #PERIOD_MS} and records how much later than intended it started. The LOW_PRIORITY lane
 * is one thread shared by every living-world timer, so this is the number that shows it falling
 * behind. One no-op task per period; the cost is negligible.
 *
 * TimerManager.stop() (an in-process restart) drains the queued task without cancelling it, so
 * the chain can die while still looking scheduled. {@link #ensureStarted()} re-arms once the
 * outstanding task is done or more than {@link #REARM_AFTER_MS} overdue, and counts it. A lane
 * that is really stalled therefore shows overdueMs climbing and rearms rising about every
 * REARM_AFTER_MS; when it recovers, the superseded task still records how late it ran.
 */
final class AdminTimerLagProbe {
    static final long PERIOD_MS = 5_000L;
    static final long WINDOW_MS = 60_000L;
    static final long REARM_AFTER_MS = 2 * WINDOW_MS;
    /** More than WINDOW_MS / PERIOD_MS: samples are never closer together than one period. */
    private static final int RING = 16;
    private static final long NANOS_PER_MS = 1_000_000L;

    /** Schedules a one-shot task on the probed lane. */
    interface Lane {
        Future<?> schedule(Runnable task, long delayMs);
    }

    static final AdminTimerLagProbe LOW_PRIORITY = new AdminTimerLagProbe(System::nanoTime,
            (task, delayMs) -> TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, task, delayMs));

    private final LongSupplier nanoTime;
    private final Lane lane;
    private final long[] sampledAtNanos = new long[RING];
    private final long[] lagMs = new long[RING];
    private int next;
    private long samples;
    private long lastLagMs;
    /** Intended start of the outstanding probe task, 0 when none is scheduled. */
    private long pendingIntendedNanos;
    private Future<?> pending;
    /** Tags the outstanding task; a superseded one still records its lag but does not reschedule. */
    private long generation;
    private long rearms;
    private boolean running;

    AdminTimerLagProbe(LongSupplier nanoTime, Lane lane) {
        this.nanoTime = nanoTime;
        this.lane = lane;
    }

    /** Starts the probe unless it is already running, or re-arms a dead chain; safe to call on every read. */
    synchronized void ensureStarted() {
        if (!running) {
            running = true;
            scheduleNext();
        } else if (pending == null || pending.isDone() || overdueMs(nanoTime.getAsLong()) > REARM_AFTER_MS) {
            rearms++;
            scheduleNext();
        }
    }

    private void scheduleNext() {
        long intended = nanoTime.getAsLong() + PERIOD_MS * NANOS_PER_MS;
        long tag = ++generation;
        pendingIntendedNanos = intended;
        try {
            pending = lane.schedule(() -> fire(tag, intended), PERIOD_MS);
        } catch (RuntimeException failure) {
            // Timers not started yet or shutting down: the next ensureStarted() tries again.
            running = false;
            pending = null;
            pendingIntendedNanos = 0L;
        }
    }

    private synchronized void fire(long tag, long intendedNanos) {
        record(intendedNanos, nanoTime.getAsLong());
        if (tag == generation) {
            scheduleNext();
        }
    }

    synchronized void record(long intendedNanos, long actualNanos) {
        long lag = Math.max(0L, (actualNanos - intendedNanos) / NANOS_PER_MS);
        sampledAtNanos[next] = actualNanos;
        lagMs[next] = lag;
        next = (next + 1) % RING;
        samples++;
        lastLagMs = lag;
    }

    synchronized long lastLagMs() {
        return lastLagMs;
    }

    /** Largest lag recorded within the last {@link #WINDOW_MS}. */
    synchronized long maxLagMs(long nowNanos) {
        long max = 0L;
        int filled = (int) Math.min(samples, RING);
        for (int i = 0; i < filled; i++) {
            if (nowNanos - sampledAtNanos[i] <= WINDOW_MS * NANOS_PER_MS) {
                max = Math.max(max, lagMs[i]);
            }
        }
        return max;
    }

    /** How far past its intended start the outstanding probe is: a stalled lane shows here before any sample lands. */
    synchronized long overdueMs(long nowNanos) {
        return pendingIntendedNanos == 0L ? 0L : Math.max(0L, (nowNanos - pendingIntendedNanos) / NANOS_PER_MS);
    }

    synchronized long samples() {
        return samples;
    }

    synchronized boolean running() {
        return running;
    }

    synchronized long rearms() {
        return rearms;
    }
}
