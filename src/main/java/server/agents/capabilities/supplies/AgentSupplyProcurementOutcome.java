package server.agents.capabilities.supplies;

import server.agents.capabilities.contracts.AgentResourceCategory;

/** Reconciled result of one complete resupply attempt. */
public record AgentSupplyProcurementOutcome(
        Status status,
        AgentResourceCategory category,
        int quantityBefore,
        int quantityAfter,
        int mesosBefore,
        int mesosAfter,
        long occurredAtMs,
        String reason) {

    /**
     * How the reason of a walk to the supplier that the no-progress watchdog gave up begins: it stood on one map for
     * TRAVEL_NO_PROGRESS_MS without crossing to another.
     */
    public static final String NO_PROGRESS_TOWARD_SUPPLIER = "no progress toward the supplier";

    public enum Status {
        RESTORED,
        PARTIALLY_RESTORED,
        INSUFFICIENT_MESO,
        NO_PROGRESS,
        ROUTE_FAILED,
        SHOP_FAILED
    }

    public AgentSupplyProcurementOutcome {
        if (status == null || category == null || quantityBefore < 0 || quantityAfter < 0
                || mesosBefore < 0 || mesosAfter < 0 || occurredAtMs < 0L) {
            throw new IllegalArgumentException("valid procurement outcome facts are required");
        }
        reason = reason == null ? "" : reason.trim();
    }

    public boolean restored() {
        return status == Status.RESTORED;
    }

    /**
     * True when the no-progress watchdog gave the walk to the supplier up ({@link #NO_PROGRESS_TOWARD_SUPPLIER}); a
     * walk with no route, a shop that sold nothing and a walk back that stalled are not.
     */
    public boolean stalledTowardSupplier() {
        return status == Status.ROUTE_FAILED && reason.startsWith(NO_PROGRESS_TOWARD_SUPPLIER);
    }

    public boolean requiresRecoveryIncome() {
        return status == Status.INSUFFICIENT_MESO
                || status == Status.PARTIALLY_RESTORED
                || status == Status.NO_PROGRESS;
    }
}
