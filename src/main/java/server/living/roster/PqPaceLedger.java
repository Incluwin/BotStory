package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceObservation;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.living.LivingWorld;
import server.living.store.LivingStore;
import server.living.store.LivingStore.PqRunPaceRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The PQ clear-time records (pq_run_pace) and what the clear-time model learned from them. At boot, on companion-ops
 * after the pace data is warm, each listed quest's newest runs of the current pace epoch are read and fitted
 * (AgentPqPaceCalibration.fit, once per leader on record and once for any other leader, the excluded leaders of
 * living_world.COMPANION_PQ_PACE_EXCLUDED_LEADERS teaching no leader term). As a companion run that got in ends, its
 * row (the entry NPC's estimate at the Yes beside how long it took) is written, the run is learned from, and the fitted
 * snapshot replaced, all on companion-ops; one "PQ pace:" line logs the estimate against the run. The lineup reads
 * {@link #calibration} and {@link #lastClearS} on the leader's I/O thread: a volatile read of an immutable snapshot,
 * never the store. A failed read leaves a quest without records (its estimate rests on the priors) until a run is
 * learned; a failed write learns nothing from that run.
 */
public final class PqPaceLedger {
    private static final Logger log = LoggerFactory.getLogger(PqPaceLedger.class);
    /** The leader a fit is made for when the lineup's leader has no run on record: no run has id 0. */
    static final int ANY_LEADER = 0;

    private final LivingWorld world;
    private final Function<String, AgentPqPaceProfile> profiles;
    /** Quest key -> what its records hold now: replaced whole on companion-ops, read from any thread. */
    private volatile Map<String, Snapshot> snapshots = Map.of();

    PqPaceLedger(LivingWorld world) {
        this(world, AgentPqPaceProfiles::cached);
    }

    /** {@code profiles}: a quest's warm pace profile, null while cold (never loads anything). */
    PqPaceLedger(LivingWorld world, Function<String, AgentPqPaceProfile> profiles) {
        this.world = world;
        this.profiles = profiles;
    }

    /**
     * One recorded run as the ledger keeps it: the run as reported, its outcome, its time from the Yes to the clear
     * (seconds; null unless it cleared) and its lineup companions.
     */
    record Recorded(AgentPqPace pace, String outcome, Integer actualS, Set<Integer> companions) {
        Recorded {
            companions = Set.copyOf(companions);
        }

        static Recorded of(PqRunPaceRow row) {
            return new Recorded(PqPaceJson.pace(row), row.outcome(), row.actualS(), PqPaceJson.companions(row.members()));
        }

        boolean cleared() {
            return "cleared".equals(outcome) && actualS != null && actualS > 0;
        }
    }

    /**
     * A quest's records under one epoch, newest first, and what was fitted from them: per leader on record, and for
     * any other leader.
     */
    record Snapshot(String pqKey, int epoch, List<Recorded> runs, Map<Integer, AgentPqPaceCalibration> byLeader,
                    AgentPqPaceCalibration anyLeader) {
        Snapshot {
            runs = List.copyOf(runs);
            byLeader = Map.copyOf(byLeader);
        }
    }

    /** At boot, on companion-ops after the pace data is warm: every listed quest's records are read and fitted. */
    public void load() {
        for (String pqKey : questKeys()) {
            load(pqKey);
        }
    }

    void load(String pqKey) {
        AgentPqPaceProfile profile = profile(pqKey);
        if (profile == null) {
            log.info("PQ pace records of {} not read: its pace data is cold, so its estimate rests on no records",
                    pqKey);
            return;
        }
        List<PqRunPaceRow> rows;
        try {
            rows = world.store().pqRunPaces(pqKey, profile.epoch(), AgentPqPaceCalibration.maxRows());
        } catch (LivingStore.ReadFailed failure) {
            log.warn("PQ pace records of {} could not be read; its estimate rests on the priors until a run is learned",
                    pqKey, failure);
            return;
        }
        List<Recorded> runs = new ArrayList<>();
        for (PqRunPaceRow row : rows) {
            try {
                runs.add(Recorded.of(row));
            } catch (RuntimeException unreadable) {
                log.warn("pq_run_pace row {} ({}) cannot be read; skipped", row.id(), row.sessionId(), unreadable);
            }
        }
        Snapshot snapshot;
        try {
            snapshot = fit(profile, runs);
        } catch (RuntimeException failure) {
            log.warn("PQ pace records of {} could not be fitted; its estimate rests on the priors", pqKey, failure);
            return;
        }
        swap(snapshot);
        AgentPqPaceCalibration learned = snapshot.anyLeader();
        log.info("PQ pace records of {} read: epoch={} runs={} learned={} notes={}", pqKey, snapshot.epoch(),
                runs.size(), learned(learned), learned.fitNotes());
    }

    /**
     * What the quest's records taught for this leader (any other leader's when they have no run on record); null while
     * none were read (the caller estimates from the profile's priors).
     */
    AgentPqPaceCalibration calibration(String pqKey, int leaderId) {
        Snapshot snapshot = snapshots.get(pqKey);
        return snapshot == null ? null : snapshot.byLeader().getOrDefault(leaderId, snapshot.anyLeader());
    }

    /**
     * How long the leader's newest clear with exactly these companions took, in seconds, in the current epoch; 0 when
     * there is none on record.
     */
    double lastClearS(String pqKey, int leaderId, Set<Integer> companionIds) {
        Snapshot snapshot = snapshots.get(pqKey);
        if (snapshot == null || companionIds == null) {
            return 0.0d;
        }
        for (Recorded run : snapshot.runs()) {
            if (run.pace().leaderId() == leaderId && run.cleared() && run.companions().equals(companionIds)) {
                return run.actualS();
            }
        }
        return 0.0d;
    }

    /**
     * On companion-ops, as a run ends: its record is written, learned from and logged on companion-ops, in a task of
     * its own. A run that never got in has nothing to record; one whose session reported no pace is only logged.
     */
    void record(CompanionRun run, AgentPqPace pace, long nowMs) {
        if (!run.started) {
            return;
        }
        if (pace == null) {
            log.info("PQ pace: session={} leader={} size={} not recorded: the run reported no pace", run.sessionId,
                    run.leaderId, run.partySize);
            return;
        }
        world.executor().submit("pq.pace.record " + run.sessionId, () -> write(run, pace, nowMs));
    }

    private void write(CompanionRun run, AgentPqPace pace, long nowMs) {
        AgentPqPaceProfile profile = profile(pace.questKey());
        PqRunPaceRow row = row(run, pace, profile == null ? 0 : profile.epoch(), nowMs);
        boolean written = world.store().savePqRunPace(row);
        Snapshot snapshot = snapshots.get(row.pqKey());
        if (written && profile != null) {
            try {
                List<Recorded> runs = new ArrayList<>();
                runs.add(Recorded.of(row)); // as a restart would read it back
                if (snapshot != null && snapshot.epoch() == profile.epoch()) {
                    runs.addAll(snapshot.runs());
                }
                Snapshot fitted = fit(profile, runs.subList(0, Math.min(runs.size(), AgentPqPaceCalibration.maxRows())));
                swap(fitted);
                snapshot = fitted;
            } catch (RuntimeException failure) {
                log.warn("PQ pace records of {} could not be refitted with {}; the last fit stays", row.pqKey(),
                        row.sessionId(), failure);
            }
        }
        log.info(line(row, pace, run.estimateAtYes, snapshot == null ? null : snapshot.anyLeader(), written));
    }

    /**
     * The row of a run that ended: its outcome (cleared when the run or its pace saw the clear), the estimate it was
     * shown at the Yes (its quantiles null when none was made, and when it said the lineup could not clear: then only
     * the records behind it are kept, so an unclearable estimate never counts as a time), its time from the start to
     * the clear (null unless the pace saw the clear), and its pace.
     */
    static PqRunPaceRow row(CompanionRun run, AgentPqPace pace, int epoch, long nowMs) {
        AgentPqPaceEstimate estimate = run.estimateAtYes;
        AgentPqPaceEstimate timed = estimate != null && estimate.clearable() ? estimate : null;
        Set<Integer> companions = run.ownerByCompanion.keySet();
        Integer actualS = pace.cleared()
                ? (int) Math.round(Math.max(0L, pace.clearedAtMs() - pace.startedAtMs()) / 1000.0d) : null;
        return new PqRunPaceRow(0L, pace.questKey(), pace.sessionId().isEmpty() ? run.sessionId : pace.sessionId(),
                pace.leaderId(), run.partySize, Math.max(0, run.memberIds.size() - companions.size()), epoch,
                pace.cleared() || run.cleared ? "cleared" : "failed",
                timed == null ? null : seconds(timed.p10S()), timed == null ? null : seconds(timed.p50S()),
                timed == null ? null : seconds(timed.p90S()), estimate == null ? null : estimate.recordsUsed(),
                actualS, pace.startedAtMs(), pace.clearedAtMs(), PqPaceJson.spans(pace),
                PqPaceJson.members(pace, run.memberIds, companions), PqPaceJson.drawn(pace), nowMs);
    }

    /**
     * The run-end line: what the entry NPC was shown at the Yes (median, then p10-p90 and whether records or priors
     * backed it) against the actual time, then per segment the estimate's mean seconds over the measured ones
     * ("-": no estimate; in brackets: an incomplete span the fit skips), then what the records teach now. A span the
     * run never saw (incomplete and zero long, as KPQ's descent) is shown as "-", and its estimate is added to the next
     * span's, the one that took it in, as the fit folds it.
     */
    static String line(PqRunPaceRow row, AgentPqPace pace, AgentPqPaceEstimate estimate,
                       AgentPqPaceCalibration learned, boolean written) {
        StringBuilder line = new StringBuilder("PQ pace: session=").append(row.sessionId())
                .append(" leader=").append(row.leaderId())
                .append(" size=").append(row.partySize())
                .append(" epoch=").append(row.epoch())
                .append(" shown=").append(shown(estimate))
                .append(" actual=").append(row.actualS() == null ? "none" : row.actualS() + "s")
                .append(" outcome=").append(row.outcome());
        double unseenS = 0.0d;
        for (AgentPqPace.Span span : pace.spans()) {
            Double estimated = estimate == null ? null : estimate.segmentMeanS().get(span.key());
            if (!span.complete() && span.durationMs() == 0L) {
                unseenS += estimated == null ? 0.0d : estimated;
                line.append(' ').append(span.key()).append("=-");
                continue;
            }
            if (estimated != null) {
                estimated += unseenS;
            }
            unseenS = 0.0d;
            long actual = Math.round(span.durationMs() / 1000.0d);
            line.append(' ').append(span.key()).append('=')
                    .append(estimated == null ? "-" : Long.toString(Math.round(estimated))).append('/')
                    .append(span.complete() ? Long.toString(actual) : "(" + actual + ")");
        }
        line.append(" learned=").append(learned(learned));
        if (learned != null && !learned.fitNotes().isEmpty()) {
            line.append(" notes=").append(learned.fitNotes());
        }
        if (!written) {
            line.append(" written=false");
        }
        return line.toString();
    }

    private static String shown(AgentPqPaceEstimate estimate) {
        if (estimate == null) {
            return "none";
        }
        String source = estimate.source().name().toLowerCase(Locale.ROOT);
        if (!estimate.clearable()) {
            return "never (" + source + ")";
        }
        return Math.round(estimate.p50S()) + "s (" + Math.round(estimate.p10S()) + "-" + Math.round(estimate.p90S())
                + ", " + source + ")";
    }

    private static String learned(AgentPqPaceCalibration learned) {
        return learned == null ? "none"
                : learned.source().name().toLowerCase(Locale.ROOT) + "/" + learned.records();
    }

    private static Integer seconds(double seconds) {
        return Double.isFinite(seconds) ? (int) Math.round(seconds) : null;
    }

    /** Fits the runs (newest first) for every leader on record and for any other leader. */
    private Snapshot fit(AgentPqPaceProfile profile, List<Recorded> runs) {
        List<AgentPqPaceObservation> observations = new ArrayList<>();
        for (int rank = 0; rank < runs.size(); rank++) {
            observations.add(new AgentPqPaceObservation(rank, profile.epoch(), runs.get(rank).pace()));
        }
        Set<Integer> excluded = world.config().companionPqPaceExcludedLeaders();
        Map<Integer, AgentPqPaceCalibration> byLeader = new TreeMap<>();
        for (Recorded run : runs) {
            byLeader.computeIfAbsent(run.pace().leaderId(),
                    leaderId -> AgentPqPaceCalibration.fit(profile, observations, leaderId, excluded));
        }
        return new Snapshot(profile.questKey(), profile.epoch(), runs, byLeader,
                AgentPqPaceCalibration.fit(profile, observations, ANY_LEADER, excluded));
    }

    private void swap(Snapshot snapshot) {
        Map<String, Snapshot> next = new HashMap<>(snapshots);
        next.put(snapshot.pqKey(), snapshot);
        snapshots = Map.copyOf(next);
    }

    private AgentPqPaceProfile profile(String pqKey) {
        try {
            return profiles.apply(pqKey);
        } catch (RuntimeException | LinkageError failure) {
            log.warn("PQ pace profile {} could not be read for its records", pqKey, failure);
            return null;
        }
    }

    /** The quests whose entry NPC weighs lineups (living_world.COMPANION_PQ_ENABLED_KEYS). */
    private Set<String> questKeys() {
        Set<String> keys = new LinkedHashSet<>();
        String listed = world.config().COMPANION_PQ_ENABLED_KEYS;
        for (String entry : listed == null ? new String[0] : listed.split(",")) {
            if (!entry.isBlank()) {
                keys.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }
}
