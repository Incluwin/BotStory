package server.agents.capabilities.townlife;

/** Capability-neutral request to spend bounded or open-ended time in a town. */
public record AgentTownLifeVisitRequest(
        int townMapId,
        Purpose purpose,
        String reason,
        long freeTimeBudgetMs) {

    /** Reason for a visit that starts where a ship crossing has just landed the Agent. */
    public static final String SHIP_ARRIVAL_REASON = "arrived by ship";

    public enum Purpose {
        PROGRESSION,
        SUPPLIES,
        SHOPPING,
        LEISURE,
        SYSTEM
    }

    public AgentTownLifeVisitRequest {
        if (townMapId <= 0 || purpose == null || freeTimeBudgetMs < 0L) {
            throw new IllegalArgumentException("valid TownLife visit request is required");
        }
        reason = reason == null ? "" : reason.trim();
    }

    public static AgentTownLifeVisitRequest leisure(int townMapId) {
        return new AgentTownLifeVisitRequest(townMapId, Purpose.LEISURE, "", 0L);
    }

    public static AgentTownLifeVisitRequest shipArrival(int townMapId) {
        return new AgentTownLifeVisitRequest(townMapId, Purpose.LEISURE, SHIP_ARRIVAL_REASON, 0L);
    }
}
