package server.admin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTimerLagProbeTest {
    private static final long MS = 1_000_000L;

    private final AtomicLong clock = new AtomicLong(1_000_000 * MS);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final AdminTimerLagProbe probe = new AdminTimerLagProbe(clock::get, (task, delayMs) -> {
        scheduled.add(task);
        delays.add(delayMs);
    });

    /** Lets the lane run the outstanding probe task {@code lateMs} after its intended start. */
    private void runLate(long lateMs) {
        Runnable task = scheduled.remove(scheduled.size() - 1);
        clock.addAndGet((AdminTimerLagProbe.PERIOD_MS + lateMs) * MS);
        task.run();
    }

    @Test
    void recordsActualMinusIntendedAndReschedulesItself() {
        probe.ensureStarted();
        probe.ensureStarted();
        assertEquals(1, scheduled.size(), "starting twice schedules one task");
        assertEquals(List.of(AdminTimerLagProbe.PERIOD_MS), delays);

        runLate(0);
        assertEquals(0L, probe.lastLagMs());
        runLate(250);
        assertEquals(250L, probe.lastLagMs());
        runLate(40);
        assertEquals(40L, probe.lastLagMs());
        assertEquals(250L, probe.maxLagMs(clock.get()));
        assertEquals(3L, probe.samples());
        assertEquals(1, scheduled.size(), "each run schedules exactly the next one");
        assertTrue(probe.running());
    }

    @Test
    void maxOnlyCoversTheLastMinute() {
        probe.ensureStarted();
        runLate(900);
        for (int i = 0; i < 12; i++) {
            runLate(10);
        }
        // The 900 ms sample is now 12 periods + 120 ms = 60.12 s old.
        assertEquals(10L, probe.maxLagMs(clock.get()));
        assertEquals(900L, probe.maxLagMs(clock.get() - 200 * MS), "still inside the window 200 ms earlier");
    }

    @Test
    void ringOverwritesOldSamplesWithoutLosingTheRecentMax() {
        for (int i = 0; i < 40; i++) {
            long now = clock.addAndGet(AdminTimerLagProbe.PERIOD_MS * MS);
            probe.record(now - (i == 38 ? 700 : 5) * MS, now);
        }
        assertEquals(700L, probe.maxLagMs(clock.get()));
        assertEquals(5L, probe.lastLagMs());
        assertEquals(40L, probe.samples());
    }

    @Test
    void overdueShowsAStalledLaneBeforeAnySampleLands() {
        probe.ensureStarted();
        assertEquals(0L, probe.overdueMs(clock.get()), "not due yet");
        clock.addAndGet((AdminTimerLagProbe.PERIOD_MS + 3_000) * MS);
        assertEquals(3_000L, probe.overdueMs(clock.get()));
        assertEquals(0L, probe.samples());
        scheduled.remove(0).run();
        assertEquals(3_000L, probe.lastLagMs());
        assertEquals(0L, probe.overdueMs(clock.get()), "the next probe is not due yet");
    }

    @Test
    void earlyRunsCountAsNoLag() {
        long now = clock.get();
        probe.record(now + 5 * MS, now);
        assertEquals(0L, probe.lastLagMs());
    }

    @Test
    void timersNotStartedLeavesTheProbeStoppedAndRetriesLater() {
        List<Runnable> accepted = new ArrayList<>();
        boolean[] failing = {true};
        AdminTimerLagProbe fragile = new AdminTimerLagProbe(clock::get, (task, delayMs) -> {
            if (failing[0]) {
                throw new IllegalStateException("timers stopped");
            }
            accepted.add(task);
        });
        fragile.ensureStarted();
        assertFalse(fragile.running());
        assertEquals(0L, fragile.overdueMs(clock.get() + 60_000 * MS), "nothing outstanding");
        failing[0] = false;
        fragile.ensureStarted();
        assertTrue(fragile.running());
        assertEquals(1, accepted.size());
    }
}
