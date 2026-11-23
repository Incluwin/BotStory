package server.living.population.paper;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event log's retention (decision D1, N1.3): rows older than PAPER_RETENTION_DAYS go in batches at the daily close,
 * within a time budget, keeping every kind a reader needs at any age; nothing while the paper or retention is off.
 */
class EventLogSweepTest {
    private static final long DAY = 24L * 3_600_000L;
    private final PopulationConfig config = new PopulationConfig();
    private final List<Long> cutoffs = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();

    private EventLogSweep sweep(int... batches) {
        int[] next = {0};
        return new EventLogSweep(() -> config, (cutoff, limit) -> {
            cutoffs.add(cutoff);
            clock.addAndGet(1_000L);
            return next[0] < batches.length ? batches[next[0]++] : 0;
        }, clock::get);
    }

    @Test
    void offSweepsNothing() {
        assertEquals(0, sweep(500, 3).atClose(100 * DAY));
        config.PAPER_ENABLED = true;
        config.PAPER_RETENTION_DAYS = 0;
        assertEquals(0, sweep(500, 3).atClose(100 * DAY));
        assertTrue(cutoffs.isEmpty());
    }

    @Test
    void batchesUntilOneComesBackShort() {
        config.PAPER_ENABLED = true;
        assertEquals(1003, sweep(500, 500, 3).atClose(100 * DAY));
        assertEquals(List.of(10 * DAY, 10 * DAY, 10 * DAY), cutoffs, "90 days back");
        assertEquals(0, sweep(-1).atClose(100 * DAY), "a failed delete stops the sweep");
    }

    @Test
    void aCloseSpendsAtMostItsBudget() {
        config.PAPER_ENABLED = true;
        int[] full = new int[100];
        java.util.Arrays.fill(full, EventLogSweep.BATCH);
        int deleted = sweep(full).atClose(100 * DAY);
        assertEquals(EventLogSweep.BUDGET_MS / 1_000L * EventLogSweep.BATCH, deleted, "the rest waits for the next close");
    }

    @Test
    void theKindsReadAsStateAreKept() {
        String clause = EventLogSweep.keptClause();
        for (String kind : List.of("letters.", "hire.offer", "gm.level.", "mentor.", "apprentice.", "banter.muted",
                "kit.issued", "bossgear.", "bench.camp-moved.told", "roster.away_told", "bench.live", "bench.live-end",
                "bench.stall", "bench.stall-close", "roster.camp")) {
            assertTrue(clause.contains(kind.endsWith(".") ? "kind NOT LIKE '" + kind + "%'" : "kind <> '" + kind + "'"),
                    kind);
        }
        assertTrue(PaperDesk.READ_EVENT.startsWith("letters."), "the paper's seen marker is a letters kind");
        for (String kept : EventLogSweep.KEPT) {
            assertTrue(!kept.contains("'") && !kept.contains("_") || kept.equals("roster.away_told"),
                    "no quote, no LIKE wildcard in a prefix: " + kept);
        }
    }
}
