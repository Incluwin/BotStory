package server.agents.runtime.activity.control;

import server.agents.runtime.activity.session.AgentActivityKind;
import server.agents.runtime.activity.world.AgentWorldDirectorMode;
import server.agents.runtime.activity.world.AgentWorldShadowReport;
import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.ArrayDeque;

/**
 * Session-local scheduler diagnostics; it performs no persistence itself. The sample count starts from the persisted
 * session's observation count ({@link #seed}) and is checkpointed back by {@link AgentWorldDirectorObserveRuntime}.
 * In ASSISTED and AUTONOMOUS modes the samples also carry the Director's own choice: how many samples running it has
 * chosen the same switch ({@link Snapshot#stableSamples}), which {@link AgentWorldDirectorPolicyRuntime} acts on.
 */
public final class AgentWorldDirectorObserveState {
    public static final AgentCapabilityStateKey<AgentWorldDirectorObserveState> STATE_KEY =
            new AgentCapabilityStateKey<>("runtime.world-director-observe",
                    AgentWorldDirectorObserveState.class, AgentWorldDirectorObserveState::new);

    private AgentWorldDirectorMode mode = AgentWorldDirectorMode.DISABLED;
    private long intervalMs = 5_000L;
    private long lastSampleAtMs;
    private long sampleCount;
    private long checkpointedSamples;
    private AgentActivityKind selectedKind;
    private String selectedProposalId = "";
    private String evidence = "";
    private boolean switchRequired;
    private long stableSamples;
    private long policyQuietUntilMs;
    private final ArrayDeque<Long> directorFailuresAtMs = new ArrayDeque<>();

    /** Samples in an observation mode, or (for the Director's own choices) in ASSISTED or AUTONOMOUS. */
    public synchronized void configure(AgentWorldDirectorMode nextMode, long nextIntervalMs) {
        if (nextMode == null || !samples(nextMode) || nextIntervalMs < 1_000L) {
            throw new IllegalArgumentException("Observe mode and interval of at least one second are required");
        }
        mode = nextMode;
        intervalMs = nextIntervalMs;
    }

    public synchronized void disable() {
        mode = AgentWorldDirectorMode.DISABLED;
        stableSamples = 0L;
    }

    /** True for the modes that sample: OBSERVE/SHADOW, and ASSISTED/AUTONOMOUS whose policy needs the samples. */
    public static boolean samples(AgentWorldDirectorMode mode) {
        return mode != null && (mode.isObservationOnly() || mode.allowsAutomaticProposals());
    }

    /** The persisted observation count (the session file) a fresh session continues from. */
    public synchronized void seed(long persistedSamples) {
        if (persistedSamples > sampleCount) {
            sampleCount = persistedSamples;
        }
        checkpointedSamples = Math.max(checkpointedSamples, persistedSamples);
    }

    public synchronized boolean due(long nowMs) {
        return samples(mode)
                && (lastSampleAtMs == 0L || nowMs - lastSampleAtMs >= intervalMs);
    }

    public synchronized void sampled(AgentWorldShadowReport report, long nowMs) {
        lastSampleAtMs = nowMs;
        sampleCount++;
        String proposalId = report.decision().proposalId();
        boolean wantsSwitch = report.decision().switchRequired() && report.decision().kind() != null
                && proposalId != null && !proposalId.isEmpty();
        stableSamples = !wantsSwitch ? 0L
                : switchRequired && proposalId.equals(selectedProposalId) ? stableSamples + 1L : 1L;
        switchRequired = wantsSwitch;
        selectedKind = report.decision().kind();
        selectedProposalId = proposalId == null ? "" : proposalId;
        evidence = report.decision().evidence();
    }

    public synchronized void failed(String reason, long nowMs) {
        lastSampleAtMs = nowMs;
        stableSamples = 0L;
        evidence = reason == null ? "" : reason.trim();
    }

    /**
     * The policy acted on (or gave up on) the current choice: it waits {@code quietMs} and a fresh run of stable
     * samples before acting again.
     */
    public synchronized void policyActed(long nowMs, long quietMs) {
        stableSamples = 0L;
        policyQuietUntilMs = nowMs + Math.max(0L, quietMs);
    }

    public synchronized boolean policyQuiet(long nowMs) {
        return nowMs < policyQuietUntilMs;
    }

    /** A Director directive of this Agent was rejected or its step failed; failures older than the window are dropped. */
    public synchronized void directorFailed(long nowMs, long windowMs) {
        directorFailuresAtMs.addLast(nowMs);
        while (!directorFailuresAtMs.isEmpty() && nowMs - directorFailuresAtMs.peekFirst() > windowMs) {
            directorFailuresAtMs.removeFirst();
        }
    }

    /** Director failures within {@code windowMs} before now (the autonomous gate's MAX_RECENT_FAILURES input). */
    public synchronized int recentDirectorFailures(long nowMs, long windowMs) {
        int count = 0;
        for (long at : directorFailuresAtMs) {
            if (nowMs - at <= windowMs) {
                count++;
            }
        }
        return count;
    }

    /** The sample count to write back to the session file when {@code every} more samples were taken; -1 otherwise. */
    public synchronized long checkpointDue(long every) {
        if (every <= 0L || sampleCount - checkpointedSamples < every) {
            return -1L;
        }
        checkpointedSamples = sampleCount;
        return sampleCount;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mode, intervalMs, lastSampleAtMs, sampleCount,
                selectedKind, selectedProposalId, evidence, switchRequired ? stableSamples : 0L);
    }

    public record Snapshot(
            AgentWorldDirectorMode mode,
            long intervalMs,
            long lastSampleAtMs,
            long sampleCount,
            AgentActivityKind selectedKind,
            String selectedProposalId,
            String evidence,
            long stableSamples) { }
}
