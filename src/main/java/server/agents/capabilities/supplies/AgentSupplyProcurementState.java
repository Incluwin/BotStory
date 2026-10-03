package server.agents.capabilities.supplies;

import server.agents.runtime.state.AgentCapabilityStateKey;
import server.agents.capabilities.contracts.AgentResourceCategory;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;

public final class AgentSupplyProcurementState {
    public static final AgentCapabilityStateKey<AgentSupplyProcurementState> STATE_KEY =
            new AgentCapabilityStateKey<>("supplies.procurement-execution", AgentSupplyProcurementState.class,
                    AgentSupplyProcurementState::new);

    private String requestId = "";
    private String objectiveId = "";
    private boolean shopRequested;
    private AgentResourceCategory category;
    private Phase phase = Phase.IDLE;
    private int supplierMapId;
    private int supplierNpcId;
    private int returnMapId;
    private int quantityBefore;
    private int mesosBefore;
    private AgentSupplyProcurementOutcome lastOutcome;
    private int recoveryAttempts;
    private long recoveryStartedAtMs;
    private long recoveryDeadlineAtMs;
    private int recoveryMapId;
    private int recoveryBaselineMeso;
    private int recoveryTargetMeso;
    private String stalledReason = "";
    private int shopFailures;
    private long shopRetryAtMs;
    private String abandonedReason = "";
    private boolean recalled;
    private boolean resumeFollow;
    private boolean resumeGrind;
    private int supplierHopBudget = -1;
    // Survives clear(): a category the Agent just failed to restock stays quiet for a while so
    // the next low-supply observation does not immediately pull it away from its party again.
    private final Map<AgentResourceCategory, Long> suppressedUntilMs =
            new EnumMap<>(AgentResourceCategory.class);
    // Survives clear() too: when a following Agent asked its party for a category it is still
    // short of, so it waits for a share once instead of leaving for the shop straight away.
    private final Map<AgentResourceCategory, Long> partyAskedAtMs =
            new EnumMap<>(AgentResourceCategory.class);

    public enum Phase {
        IDLE,
        TRAVEL_TO_SUPPLIER,
        SHOPPING,
        RETURNING,
        RESTING,
        INCOME_RECOVERY,
        STALLED
    }

    public synchronized boolean isActive() {
        return !requestId.isBlank();
    }

    public synchronized String requestId() {
        return requestId;
    }

    public synchronized String objectiveId() {
        return objectiveId;
    }

    public synchronized boolean shopRequested() {
        return shopRequested;
    }

    public synchronized AgentResourceCategory category() {
        return category;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized int supplierMapId() {
        return supplierMapId;
    }

    public synchronized int supplierNpcId() {
        return supplierNpcId;
    }

    public synchronized int returnMapId() {
        return returnMapId;
    }

    public synchronized int quantityBefore() {
        return quantityBefore;
    }

    public synchronized int mesosBefore() {
        return mesosBefore;
    }

    public synchronized AgentSupplyProcurementOutcome lastOutcome() {
        return lastOutcome;
    }

    public synchronized int recoveryAttempts() {
        return recoveryAttempts;
    }

    public synchronized long recoveryStartedAtMs() {
        return recoveryStartedAtMs;
    }

    public synchronized long recoveryDeadlineAtMs() {
        return recoveryDeadlineAtMs;
    }

    public synchronized int recoveryMapId() {
        return recoveryMapId;
    }

    public synchronized int recoveryBaselineMeso() {
        return recoveryBaselineMeso;
    }

    public synchronized int recoveryTargetMeso() {
        return recoveryTargetMeso;
    }

    public synchronized String stalledReason() {
        return stalledReason;
    }

    /** Failed NPC shop visits (BLOCKED/CANCELLED workflows) during this procurement. */
    public synchronized int shopFailures() {
        return shopFailures;
    }

    public synchronized long shopRetryAtMs() {
        return shopRetryAtMs;
    }

    /** True once this procurement gave up and is heading back to the suspended plan. */
    public synchronized boolean abandoned() {
        return !abandonedReason.isBlank();
    }

    public synchronized String abandonedReason() {
        return abandonedReason;
    }

    /** Movement mode the foreground was in when maintenance began; restored when it finishes. */
    public synchronized void rememberForegroundMode(boolean following, boolean grinding) {
        resumeFollow = following;
        resumeGrind = !following && grinding;
    }

    public synchronized boolean resumeFollow() {
        return resumeFollow;
    }

    /**
     * A host let the Agent follow again while this trip is under way: it ends by following instead of walking back to
     * the map it left. False, and nothing changed, once the trip is over (its finish took the mode in the same lock).
     */
    public synchronized boolean resumeFollowIfActive() {
        if (requestId.isBlank()) {
            return false;
        }
        resumeFollow = true;
        resumeGrind = false;
        return true;
    }

    /** How the Agent moves again once the trip is over. */
    public record ForegroundMode(boolean follow, boolean grind) { }

    /** Clears the trip and hands back the mode it ends in, in one step ({@link #resumeFollowIfActive} races it). */
    public synchronized ForegroundMode clearTakingForegroundMode() {
        ForegroundMode mode = new ForegroundMode(resumeFollow, resumeGrind);
        clear();
        return mode;
    }

    public synchronized boolean resumeGrind() {
        return resumeGrind;
    }

    /**
     * Longest route, in portal hops, this trip still accepts: its length when it started,
     * or -1 before the trip measured it.
     */
    public synchronized int supplierHopBudget() {
        return supplierHopBudget;
    }

    public synchronized void setSupplierHopBudget(int hops) {
        supplierHopBudget = hops;
    }

    /** Sends the trip to another supplier the router can still reach. */
    public synchronized void redirectSupplier(int supplierMapId, int supplierNpcId, int hopBudget) {
        this.supplierMapId = supplierMapId;
        this.supplierNpcId = supplierNpcId;
        supplierHopBudget = hopBudget;
        shopRequested = false;
    }

    /** When the Agent asked its party for this category, or -1 when it has not asked. */
    public synchronized long partyAskedAtMs(AgentResourceCategory category) {
        Long askedAt = category == null ? null : partyAskedAtMs.get(category);
        return askedAt == null ? -1L : askedAt;
    }

    public synchronized void markPartyAsked(AgentResourceCategory category, long nowMs) {
        if (category != null) {
            partyAskedAtMs.put(category, nowMs);
        }
    }

    public synchronized void forgetPartyAsk(AgentResourceCategory category) {
        if (category != null) {
            partyAskedAtMs.remove(category);
        }
    }

    /** Forgets the asks for categories the Agent is no longer short of. */
    public synchronized void retainPartyAsks(Predicate<AgentResourceCategory> stillShort) {
        partyAskedAtMs.keySet().removeIf(category -> !stillShort.test(category));
    }

    public synchronized void suppress(AgentResourceCategory category, long untilMs) {
        if (category != null) {
            suppressedUntilMs.merge(category, untilMs, Math::max);
        }
    }

    public synchronized boolean suppressed(AgentResourceCategory category, long nowMs) {
        Long until = category == null ? null : suppressedUntilMs.get(category);
        return until != null && nowMs < until;
    }

    public synchronized void start(String requestId, String objectiveId, AgentResourceCategory category) {
        start(requestId, objectiveId, category, 0, 0, 0, Phase.SHOPPING);
    }

    public synchronized void start(String requestId,
                                   String objectiveId,
                                   AgentResourceCategory category,
                                   int supplierMapId,
                                   int supplierNpcId,
                                   int returnMapId,
                                   Phase phase) {
        start(requestId, objectiveId, category, supplierMapId, supplierNpcId,
                returnMapId, phase, 0, 0);
    }

    public synchronized void start(String requestId,
                                   String objectiveId,
                                   AgentResourceCategory category,
                                   int supplierMapId,
                                   int supplierNpcId,
                                   int returnMapId,
                                   Phase phase,
                                   int quantityBefore,
                                   int mesosBefore) {
        this.requestId = requestId;
        this.objectiveId = objectiveId;
        this.category = category;
        this.supplierMapId = supplierMapId;
        this.supplierNpcId = supplierNpcId;
        this.returnMapId = returnMapId;
        this.phase = phase;
        this.quantityBefore = Math.max(0, quantityBefore);
        this.mesosBefore = Math.max(0, mesosBefore);
        shopRequested = false;
        recoveryAttempts = 0;
        recoveryStartedAtMs = 0L;
        recoveryDeadlineAtMs = 0L;
        recoveryMapId = 0;
        recoveryBaselineMeso = 0;
        recoveryTargetMeso = 0;
        stalledReason = "";
        shopFailures = 0;
        shopRetryAtMs = 0L;
        abandonedReason = "";
        recalled = false;
        resumeFollow = false;
        resumeGrind = false;
        supplierHopBudget = -1;
    }

    public synchronized void complete(AgentSupplyProcurementOutcome outcome) {
        lastOutcome = outcome;
    }

    public synchronized void markShopRequested() {
        shopRequested = true;
        phase = Phase.SHOPPING;
    }

    public synchronized void markReturning() {
        phase = Phase.RETURNING;
    }

    public synchronized void beginRecovery(
            int mapId, int baselineMeso, int targetMeso, long nowMs, long deadlineAtMs) {
        recoveryAttempts++;
        recoveryStartedAtMs = nowMs;
        recoveryDeadlineAtMs = Math.max(nowMs, deadlineAtMs);
        recoveryMapId = mapId;
        recoveryBaselineMeso = Math.max(0, baselineMeso);
        recoveryTargetMeso = Math.max(recoveryBaselineMeso, targetMeso);
        shopRequested = false;
        phase = Phase.RESTING;
    }

    public synchronized void markResting(long nowMs, long deadlineAtMs) {
        recoveryStartedAtMs = nowMs;
        recoveryDeadlineAtMs = Math.max(nowMs, deadlineAtMs);
        phase = Phase.RESTING;
    }

    public synchronized void markIncomeRecovery(long nowMs, long deadlineAtMs) {
        recoveryStartedAtMs = nowMs;
        recoveryDeadlineAtMs = Math.max(nowMs, deadlineAtMs);
        phase = Phase.INCOME_RECOVERY;
    }

    public synchronized void retrySupplier(boolean supplierIsCurrentMap) {
        shopRequested = false;
        phase = supplierIsCurrentMap ? Phase.SHOPPING : Phase.TRAVEL_TO_SUPPLIER;
        // The trip restarts from wherever recovery left the Agent.
        supplierHopBudget = -1;
    }

    public synchronized void markStalled(String reason) {
        stalledReason = reason == null ? "" : reason.trim();
        phase = Phase.STALLED;
    }

    /** Counts one failed shop visit and schedules another request no earlier than retryAtMs. */
    public synchronized void retryShopVisit(boolean supplierIsCurrentMap, long retryAtMs) {
        shopFailures++;
        shopRequested = false;
        shopRetryAtMs = retryAtMs;
        phase = supplierIsCurrentMap ? Phase.SHOPPING : Phase.TRAVEL_TO_SUPPLIER;
        supplierHopBudget = -1;
    }

    /**
     * Its owner called it back mid-trip (an order of theirs took the foreground): the trip is given up where the
     * Agent stands, without walking back to the map it left.
     */
    public synchronized void markRecalled(String reason) {
        markAbandoned(reason);
        recalled = true;
    }

    /** True once its owner called it back from this trip ({@link #markRecalled}). */
    public synchronized boolean recalled() {
        return recalled;
    }

    /** Gives up on this procurement: travel back to the plan, then finish it as failed. */
    public synchronized void markAbandoned(String reason) {
        abandonedReason = reason == null || reason.isBlank() ? "resupply abandoned" : reason.trim();
        shopRequested = false;
        phase = Phase.RETURNING;
    }

    public synchronized void clear() {
        requestId = "";
        objectiveId = "";
        category = null;
        shopRequested = false;
        phase = Phase.IDLE;
        supplierMapId = 0;
        supplierNpcId = 0;
        returnMapId = 0;
        quantityBefore = 0;
        mesosBefore = 0;
        recoveryAttempts = 0;
        recoveryStartedAtMs = 0L;
        recoveryDeadlineAtMs = 0L;
        recoveryMapId = 0;
        recoveryBaselineMeso = 0;
        recoveryTargetMeso = 0;
        stalledReason = "";
        shopFailures = 0;
        shopRetryAtMs = 0L;
        abandonedReason = "";
        recalled = false;
        resumeFollow = false;
        resumeGrind = false;
        supplierHopBudget = -1;
    }
}
