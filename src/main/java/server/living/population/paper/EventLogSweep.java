package server.living.population.paper;

import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.store.SqlTime;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The event log's retention (decision D1, docs/WORLD_SYSTEMS_PLAN.md N1.3): at each daily economy close,
 * living_event_log rows older than {@code PAPER_RETENTION_DAYS} are deleted in batches of {@link #BATCH}, for at most
 * {@link #BUDGET_MS} a close (a backlog drains over a few days), except the kinds read as state however old they are.
 *
 * <p>{@link #KEPT} (a prefix ends in a dot) is every kind a reader needs at any age, from an audit of every
 * living_event_log reader on 2026-11-22: the letters' and away-report markers (cursors: a news row is read only after
 * the newest marker, so the marker must stay and the news before it may go), GM level holds, the mentor's first-login
 * gate and its tips, picks and mutes, an apprentice's build, the crew's banter mute, the kit a Resident was issued,
 * boss gear worn, and the bench duty and stall history read as its 60 newest rows per persona. Everything else is
 * history or a count over minutes, a day or a week (hire offers, friendship gifts, letters' weekly gift count).</p>
 */
public final class EventLogSweep {
    private static final Logger log = LoggerFactory.getLogger(EventLogSweep.class);
    static final int BATCH = 500;
    static final long BUDGET_MS = 30_000L;
    static final long DAY_MS = 24L * 3_600_000L;
    /** Kinds kept at any age; an entry ending in '.' keeps every kind it starts. */
    public static final List<String> KEPT = List.of(
            "letters.",               // letters.away / letters.told (cursors), letters.gift, letters.paper (N1)
            "hire.offer",             // decision D1
            "gm.level.",              // GmLevelHolds: hold / release, read at any age
            "mentor.",                // Mentorship: the first-login gate, and tip / pick / muted restored at boot
            "apprentice.",            // Apprentices: the latest build is re-applied after a job
            "banter.muted",           // Crew: the latest mute
            "kit.issued",             // ResidentRuntime: every piece ever issued
            "bossgear.",              // BossGearRecord: worn / removed, the latest per persona
            "bench.camp-moved.told",  // RosterService.campMoved: the marker
            "roster.away_told",       // RosterService.awayReleases: the marker
            "bench.live",             // BenchDutyDispatcher: with bench.live-end, the 60 newest per persona
            "bench.live-end",
            "bench.stall",            // BenchStallForm: with bench.stall-close, the 60 newest per persona
            "bench.stall-close",
            "roster.camp");           // BenchDutyDispatcher: with the live duties' history

    /** Deletes up to {@code limit} rows older than {@code cutoffMs} that are not kept; how many, or -1 on failure. */
    interface Deleter {
        int delete(long cutoffMs, int limit);
    }

    private final Supplier<PopulationConfig> config;
    private final Deleter deleter;
    private final LongSupplier clock;

    EventLogSweep(Supplier<PopulationConfig> config, Deleter deleter, LongSupplier clock) {
        this.config = config;
        this.deleter = deleter;
        this.clock = clock;
    }

    public static EventLogSweep live(Supplier<PopulationConfig> config) {
        return new EventLogSweep(config, EventLogSweep::deleteRows, System::currentTimeMillis);
    }

    /** The close's sweep: rows deleted, 0 while the paper or the retention is off. */
    public int atClose(long nowMs) {
        PopulationConfig current = config.get();
        if (current == null || !current.PAPER_ENABLED || current.PAPER_RETENTION_DAYS <= 0) {
            return 0;
        }
        long cutoff = nowMs - current.PAPER_RETENTION_DAYS * DAY_MS;
        long started = clock.getAsLong();
        int total = 0;
        while (true) {
            int deleted = deleter.delete(cutoff, BATCH);
            if (deleted > 0) {
                total += deleted;
            }
            if (deleted < BATCH || clock.getAsLong() - started >= BUDGET_MS) {
                break;
            }
        }
        if (total > 0) {
            log.info("event log sweep: {} rows older than {} days deleted", total, current.PAPER_RETENTION_DAYS);
        }
        return total;
    }

    /** The WHERE clause after the age: no kept kind. */
    static String keptClause() {
        StringBuilder out = new StringBuilder();
        for (String kind : KEPT) {
            out.append(" AND kind ").append(kind.endsWith(".") ? "NOT LIKE '" + kind + "%'" : "<> '" + kind + "'");
        }
        return out.toString();
    }

    private static int deleteRows(long cutoffMs, int limit) {
        if (!DatabaseConnection.isInitialized()) {
            return -1;
        }
        String sql = SqlTime.utc("DELETE FROM living_event_log WHERE at < ?" + keptClause() + " ORDER BY at LIMIT ?");
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            SqlTime.set(ps, 1, cutoffMs);
            ps.setInt(2, limit);
            return ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("event log sweep failed", failure);
            return -1;
        }
    }
}
