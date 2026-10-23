package server.agents.capabilities.navigation;

public final class AgentNavigationDebugState {
    private String lastDecision = "-";
    private String lastEdgeBlockReason = null;
    private boolean graphWarmupFallback = false;
    /** The last goal the navigator resolved and its start and target regions (debug only: !cwkpqtest why). */
    private int goalX;
    private int goalY;
    private int goalStartRegionId = -1;
    private int goalTargetRegionId = -1;
    private boolean hasGoal;
    private boolean goalInAir;
    private boolean goalClimbing;

    public String lastDecision() {
        return lastDecision;
    }

    public void setLastDecision(String lastDecision) {
        this.lastDecision = lastDecision;
    }

    public String lastEdgeBlockReason() {
        return lastEdgeBlockReason;
    }

    public void setLastEdgeBlockReason(String lastEdgeBlockReason) {
        this.lastEdgeBlockReason = lastEdgeBlockReason;
    }

    public boolean graphWarmupFallback() {
        return graphWarmupFallback;
    }

    public void setGraphWarmupFallback(boolean graphWarmupFallback) {
        this.graphWarmupFallback = graphWarmupFallback;
    }

    public void noteGoal(int x, int y, int startRegionId, int targetRegionId, boolean inAir, boolean climbing) {
        goalInAir = inAir;
        goalClimbing = climbing;
        goalX = x;
        goalY = y;
        goalStartRegionId = startRegionId;
        goalTargetRegionId = targetRegionId;
        hasGoal = true;
    }

    /** "(x,y) region s->t" for the last goal, or "-" before any. */
    public String goalText() {
        return hasGoal ? "(" + goalX + "," + goalY + ") region " + goalStartRegionId + "->" + goalTargetRegionId
                + (goalInAir ? " (in the air)" : "") + (goalClimbing ? " (climbing)" : "") : "-";
    }
}
