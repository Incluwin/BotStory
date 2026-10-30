package server.agents.capabilities.shop;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable runtime state for one Agent shop visit/resupply sequence.
 */
public final class AgentShopState {
    /**
     * Whether the visit sells the Agent's junk at the counter, and who wanted it. ASKED: its owner's "sell junk" or its
     * own supply trip (the sale and its lines as before). ROUTINE: the quiet sale a companion makes at every shop visit
     * (AgentJunkSale), gear only and said nowhere. ASKED always wins over ROUTINE.
     */
    public enum Sale { NONE, ROUTINE, ASKED }

    private final AgentShopWorkflow workflow = new AgentShopWorkflow();
    private final AtomicLong visitGeneration = new AtomicLong();
    private volatile boolean visitPending = false;
    private volatile Point npcPosition = null;
    private volatile Point targetPosition = null;
    private int approachDelayMs = 0;
    private boolean sequenceActive = false;
    private long visitStartedAtMs = 0L;
    private long visitAllowanceMs = 0L;
    private long sequenceStartedAtMs = 0L;
    private long sequenceAllowanceMs = 0L;
    private volatile Sale sale = Sale.NONE;
    /** A visit opened only to sell (its owner opened a shop on its map, a bench sale slice): it buys nothing. */
    private boolean saleOnly = false;
    /** The map whose owner-opened shop it last looked at; outlives a visit, reset on each arrival (onArrival). */
    private int ownerShopSaleMapId = 0;
    private boolean saleOnArrival = false;
    private int minimumMesoReserve = 0;
    private int requiredItemId = 0;
    private int requiredItemCount = 0;
    private int lastRequiredItemId = 0;
    private int lastRequiredItemCount = 0;
    private Point stuckCheckPosition = null;
    private long stuckCheckAtMs = 0L;
    private int closestApproachPx = Integer.MAX_VALUE;
    private long approachProgressAtMs = 0L;

    public boolean visitPending() {
        return visitPending;
    }

    /**
     * Changes each time a visit opens and each time it ends, so a step scheduled for an ended visit (even one scheduled
     * after it ended) can tell it from the next one. Asking for the open visit again keeps it.
     */
    public long visitGeneration() {
        return visitGeneration.get();
    }

    public AgentShopWorkflow workflow() {
        return workflow;
    }

    public boolean sequenceActive() {
        return sequenceActive;
    }

    public boolean hasActiveTransition() {
        return visitPending || sequenceActive;
    }

    public Point npcPosition() {
        return npcPosition == null ? null : new Point(npcPosition);
    }

    public Point targetPosition() {
        return targetPosition == null ? null : new Point(targetPosition);
    }

    public Point activeTargetPosition() {
        Point target = targetPosition != null ? targetPosition : npcPosition;
        return target == null ? null : new Point(target);
    }

    public int approachDelayMs() {
        return approachDelayMs;
    }

    public void setApproachDelayMs(int delayMs) {
        approachDelayMs = Math.max(0, delayMs);
    }

    /** True until the approach delay has passed in wall-clock time since the visit started, like the visit timeout. */
    public boolean approachDelayPending(long nowMs) {
        return approachDelayMs > 0 && nowMs - visitStartedAtMs < approachDelayMs;
    }

    public long visitStartedAtMs() {
        return visitStartedAtMs;
    }

    /** Time the visit was granted on top of the visit timeout: its approach delay and the walk to the shop. */
    public long visitAllowanceMs() {
        return visitAllowanceMs;
    }

    /** Adds the walk to the shop (the nav route's travel time) to the visit's allowance. */
    public void extendVisit(long extraMs) {
        if (visitPending && !sequenceActive) {
            visitAllowanceMs += Math.max(0L, extraMs);
        }
    }

    public long sequenceStartedAtMs() {
        return sequenceStartedAtMs;
    }

    /** Time the open transaction was granted on top of the sequence timeout for steps planned after it opened. */
    public long sequenceAllowanceMs() {
        return sequenceAllowanceMs;
    }

    public void extendSequence(long extraMs) {
        if (sequenceActive) {
            sequenceAllowanceMs += Math.max(0L, extraMs);
        }
    }

    /** True while the visit sells at the counter, asked or routine. */
    public boolean sellTrashPending() {
        return sale != Sale.NONE;
    }

    public Sale sale() {
        return sale;
    }

    /** True when its owner or its own supply trip asked for the sale. */
    public boolean saleAsked() {
        return sale == Sale.ASKED;
    }

    /** True when the sale is the quiet one of every visit, nobody asked for. */
    public boolean routineSale() {
        return sale == Sale.ROUTINE;
    }

    /** The quiet sale of every visit: NONE becomes ROUTINE; an asked sale stays asked. */
    public void requestRoutineSale() {
        if (sale == Sale.NONE) {
            sale = Sale.ROUTINE;
        }
    }

    public boolean saleOnly() {
        return saleOnly;
    }

    /** The visit was opened only to sell: it buys nothing and says nothing of buying. */
    public void markSaleOnly() {
        saleOnly = true;
    }

    public int ownerShopSaleMapId() {
        return ownerShopSaleMapId;
    }

    public void setOwnerShopSaleMapId(int mapId) {
        ownerShopSaleMapId = mapId;
    }

    /** A sale-only visit asked before its arrival on the map ran (a bench sale slice's load); outlives a clear. */
    public boolean saleOnArrival() {
        return saleOnArrival;
    }

    public void setSaleOnArrival(boolean pending) {
        saleOnArrival = pending;
    }

    public int minimumMesoReserve() {
        return minimumMesoReserve;
    }

    public void ensureMinimumMesoReserve(int mesos) {
        minimumMesoReserve = Math.max(minimumMesoReserve, Math.max(0, mesos));
    }

    public int requiredItemId() {
        return requiredItemId;
    }

    public int requiredItemCount() {
        return requiredItemCount;
    }

    /** true: an asked sale (its owner's "sell junk", its own supply trip); false: no sale. */
    public void setSellTrashPending(boolean pending) {
        sale = pending ? Sale.ASKED : Sale.NONE;
    }

    public boolean hasNpcPosition() {
        return npcPosition != null;
    }

    public boolean visitTimedOut(long nowMs, long timeoutMs) {
        return visitStartedAtMs > 0 && !sequenceActive && nowMs - visitStartedAtMs > timeoutMs + visitAllowanceMs;
    }

    /**
     * On its way to the shopkeeper, the Agent stood {@code distancePx} from where it trades at {@code nowMs}: closer
     * than it has been by more than {@code stepPx} restarts the no-progress clock, which its first look starts.
     */
    public void noteApproach(int distancePx, int stepPx, long nowMs) {
        if (approachProgressAtMs == 0L || distancePx < closestApproachPx - stepPx) {
            closestApproachPx = Math.min(closestApproachPx, distancePx);
            approachProgressAtMs = nowMs;
        }
    }

    /** No step closer to the shopkeeper for more than {@code stallMs}: a walk that stopped waits no longer. */
    public boolean approachStalled(long nowMs, long stallMs) {
        return visitPending && !sequenceActive && approachProgressAtMs > 0L && nowMs - approachProgressAtMs > stallMs;
    }

    public boolean sequenceTimedOut(long nowMs, long timeoutMs) {
        return sequenceActive && sequenceStartedAtMs > 0
                && nowMs - sequenceStartedAtMs > timeoutMs + sequenceAllowanceMs;
    }

    public void startVisit(Point npcPosition, Point targetPosition, int approachDelayMs, long startedAtMs) {
        startVisit(npcPosition, targetPosition, approachDelayMs, 0, startedAtMs);
    }

    public void startVisit(Point npcPosition,
                           Point targetPosition,
                           int approachDelayMs,
                           int minimumMesoReserve,
                           long startedAtMs) {
        startVisit(npcPosition, targetPosition, approachDelayMs, minimumMesoReserve, 0, 0, startedAtMs);
    }

    public void startVisit(Point npcPosition,
                           Point targetPosition,
                           int approachDelayMs,
                           int minimumMesoReserve,
                           int requiredItemId,
                           int requiredItemCount,
                           long startedAtMs) {
        if (workflow.phase() == AgentShopWorkflowPhase.IDLE || workflow.phase().terminal()) {
            workflow.start("shop:" + startedAtMs, 0, startedAtMs);
            workflow.transition(AgentShopWorkflowPhase.APPROACHING, "travelling to shop NPC", startedAtMs);
        }
        if (!visitPending) {
            visitGeneration.incrementAndGet(); // a new visit: a step scheduled after the last one ended is not its own
        }
        visitPending = true;
        // a trip or "sell junk" that reaches a sale-only visit buys and speaks as any visit; its sale stays
        saleOnly = false;
        this.npcPosition = npcPosition == null ? null : new Point(npcPosition);
        this.targetPosition = targetPosition == null ? null : new Point(targetPosition);
        setApproachDelayMs(approachDelayMs);
        // The Agent stands still through the approach delay, so the visit timeout starts counting after it.
        visitAllowanceMs = this.approachDelayMs;
        this.minimumMesoReserve = Math.max(0, minimumMesoReserve);
        this.requiredItemId = Math.max(0, requiredItemId);
        this.requiredItemCount = Math.max(0, requiredItemCount);
        lastRequiredItemId = 0;
        lastRequiredItemCount = 0;
        visitStartedAtMs = startedAtMs;
        sequenceStartedAtMs = 0L;
        sequenceAllowanceMs = 0L;
        closestApproachPx = Integer.MAX_VALUE;
        approachProgressAtMs = 0L;
    }

    public void markSequenceActive(long startedAtMs) {
        workflow.transition(AgentShopWorkflowPhase.TRANSACTING, "shop transaction opened", startedAtMs);
        sequenceActive = true;
        sequenceStartedAtMs = startedAtMs;
        sequenceAllowanceMs = 0L;
    }

    public boolean stuckNearNpc(Point botPosition, long nowMs, long fallbackMs, int moveTolerancePx,
                                int arriveDistance) {
        if (npcPosition == null || botPosition == null) {
            return false;
        }
        if (stuckCheckPosition == null) {
            stuckCheckPosition = new Point(botPosition);
            stuckCheckAtMs = nowMs;
            return false;
        }
        if (botPosition.distanceSq(stuckCheckPosition) > (long) moveTolerancePx * moveTolerancePx) {
            stuckCheckPosition.setLocation(botPosition);
            stuckCheckAtMs = nowMs;
            return false;
        }
        if (nowMs - stuckCheckAtMs < fallbackMs) {
            return false;
        }
        return Math.abs(botPosition.x - npcPosition.x) + Math.abs(botPosition.y - npcPosition.y) <= arriveDistance;
    }

    public boolean sequenceValid(Point botPosition, Point npcPosition, int arriveDistance) {
        if (!visitPending || !sequenceActive || npcPosition == null || botPosition == null) {
            return false;
        }
        Point approach = targetPosition != null ? targetPosition : npcPosition;
        return manhattan(botPosition, approach) <= arriveDistance
                || manhattan(botPosition, npcPosition) <= arriveDistance;
    }

    public void clear() {
        lastRequiredItemId = requiredItemId;
        lastRequiredItemCount = requiredItemCount;
        if (!workflow.phase().terminal() && workflow.phase() != AgentShopWorkflowPhase.IDLE) {
            workflow.transition(AgentShopWorkflowPhase.CANCELLED, "legacy shop state cleared",
                    Math.max(System.currentTimeMillis(), workflow.updatedAtMs()));
        }
        if (visitPending || sequenceActive) {
            visitGeneration.incrementAndGet(); // a visit ends; a clear with none open (a movement command) ends none
        }
        visitPending = false;
        npcPosition = null;
        targetPosition = null;
        approachDelayMs = 0;
        sequenceActive = false;
        visitStartedAtMs = 0L;
        visitAllowanceMs = 0L;
        sequenceStartedAtMs = 0L;
        sequenceAllowanceMs = 0L;
        sale = Sale.NONE;
        saleOnly = false;
        minimumMesoReserve = 0;
        requiredItemId = 0;
        requiredItemCount = 0;
        stuckCheckPosition = null;
        stuckCheckAtMs = 0L;
        closestApproachPx = Integer.MAX_VALUE;
        approachProgressAtMs = 0L;
    }

    public boolean lastVisitRequestedItem(int itemId, int itemCount) {
        return itemId > 0
                && itemCount > 0
                && lastRequiredItemId == itemId
                && lastRequiredItemCount == itemCount;
    }

    public void complete(String reason, long nowMs) {
        if (!workflow.phase().terminal() && workflow.phase() != AgentShopWorkflowPhase.IDLE) {
            workflow.transition(AgentShopWorkflowPhase.COMPLETED, reason, Math.max(nowMs, workflow.updatedAtMs()));
        }
    }

    public void block(String reason, long nowMs) {
        if (!workflow.phase().terminal() && workflow.phase() != AgentShopWorkflowPhase.IDLE) {
            workflow.transition(AgentShopWorkflowPhase.BLOCKED, reason, Math.max(nowMs, workflow.updatedAtMs()));
        }
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
