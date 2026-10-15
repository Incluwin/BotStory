package server.agents.runtime.simulation;

/**
 * Records an abstract execution window until the Agent returns to exact world simulation.
 *
 * TownLife is initially mutation-free while abstract, so reconciliation only has to prove
 * that no unsupported mutable outcome was recorded. Any future abstract capability must
 * add a typed outcome applier before it can be allowlisted.
 *
 * A window that cannot reconcile stays closed while its abstract work is in flight. Once
 * that work has ended or the Agent is needed in the exact world, the transition service may
 * {@link #abandon abandon} the window: the Character is already the exact state, so nothing
 * is replayed and the failure is kept as evidence instead.
 */
public final class AgentBackgroundOutcomeLedger {
    private boolean active;
    private AgentAbstractExecutionScope scope = AgentAbstractExecutionScope.NONE;
    private long startedAtMs;
    private long lastHeartbeatAtMs;
    private long heartbeatCount;
    private String unsupportedOutcome;
    private long reconciliationCount;
    private AgentMaterializedStateFingerprint materializedBaseline;
    private long firstFailedReconciliationAtMs = -1L;
    private long abandonmentCount;
    private Abandonment lastAbandonment;

    public synchronized void begin(AgentAbstractExecutionScope nextScope, long nowMs) {
        begin(nextScope, nowMs, null);
    }

    public synchronized void begin(AgentAbstractExecutionScope nextScope,
                                   long nowMs,
                                   AgentMaterializedStateFingerprint baseline) {
        if (nextScope == null || nextScope == AgentAbstractExecutionScope.NONE) {
            throw new IllegalArgumentException("An abstract execution scope is required");
        }
        if (active && scope == nextScope) {
            heartbeat(nowMs);
            return;
        }
        active = true;
        scope = nextScope;
        startedAtMs = Math.max(0L, nowMs);
        lastHeartbeatAtMs = startedAtMs;
        heartbeatCount = 0L;
        unsupportedOutcome = null;
        materializedBaseline = baseline;
        firstFailedReconciliationAtMs = -1L;
    }

    public synchronized void heartbeat(long nowMs) {
        if (!active) {
            return;
        }
        lastHeartbeatAtMs = Math.max(lastHeartbeatAtMs, Math.max(0L, nowMs));
        heartbeatCount++;
    }

    public synchronized void recordUnsupportedOutcome(String description) {
        if (!active) {
            return;
        }
        unsupportedOutcome = description == null || description.isBlank()
                ? "unspecified abstract mutation"
                : description.trim();
    }

    public synchronized boolean reconcile() {
        return reconcile(materializedBaseline);
    }

    public synchronized boolean reconcile(AgentMaterializedStateFingerprint currentState) {
        if (!active) {
            return true;
        }
        if (unsupportedOutcome != null
                || materializedBaseline != null && !materializedBaseline.equals(currentState)) {
            return false;
        }
        active = false;
        scope = AgentAbstractExecutionScope.NONE;
        materializedBaseline = null;
        firstFailedReconciliationAtMs = -1L;
        reconciliationCount++;
        return true;
    }

    /**
     * Notes a failed reconciliation and returns when this window first failed to reconcile,
     * or -1 when no window is open.
     */
    public synchronized long recordFailedReconciliation(long nowMs) {
        if (!active) {
            return -1L;
        }
        if (firstFailedReconciliationAtMs < 0L) {
            firstFailedReconciliationAtMs = Math.max(0L, nowMs);
        }
        return firstFailedReconciliationAtMs;
    }

    /**
     * Closes a window that cannot reconcile without replaying anything. Returns what was
     * dropped, or null when no window was open.
     */
    public synchronized Abandonment abandon(String reason,
                                            AgentMaterializedStateFingerprint currentState,
                                            long nowMs) {
        if (!active) {
            return null;
        }
        Abandonment abandonment = new Abandonment(
                reason == null || reason.isBlank() ? "unspecified" : reason.trim(),
                scope,
                startedAtMs,
                firstFailedReconciliationAtMs,
                Math.max(0L, nowMs),
                unsupportedOutcome,
                materializedBaseline,
                currentState);
        active = false;
        scope = AgentAbstractExecutionScope.NONE;
        unsupportedOutcome = null;
        materializedBaseline = null;
        firstFailedReconciliationAtMs = -1L;
        abandonmentCount++;
        lastAbandonment = abandonment;
        return abandonment;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                active,
                scope,
                startedAtMs,
                lastHeartbeatAtMs,
                heartbeatCount,
                unsupportedOutcome,
                reconciliationCount,
                materializedBaseline,
                firstFailedReconciliationAtMs,
                abandonmentCount,
                lastAbandonment);
    }

    public record Snapshot(
            boolean active,
            AgentAbstractExecutionScope scope,
            long startedAtMs,
            long lastHeartbeatAtMs,
            long heartbeatCount,
            String unsupportedOutcome,
            long reconciliationCount,
            AgentMaterializedStateFingerprint materializedBaseline,
            long firstFailedReconciliationAtMs,
            long abandonmentCount,
            Abandonment lastAbandonment) {
    }

    /** Evidence for a window closed without reconciling. */
    public record Abandonment(
            String reason,
            AgentAbstractExecutionScope scope,
            long startedAtMs,
            long firstFailedReconciliationAtMs,
            long abandonedAtMs,
            String unsupportedOutcome,
            AgentMaterializedStateFingerprint materializedBaseline,
            AgentMaterializedStateFingerprint currentState) {
    }
}
