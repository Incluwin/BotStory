package server.agents.capabilities.partyquest.kpq;

/**
 * The stage-1 pace levers of a companion run, each behind its own switch. Only a companion run with a human leader
 * uses them; a stock run and an all-Agent run keep stage 1 as it was ({@link #NONE}).
 *
 * @param leaderCoupons   L2: the neediest collector asks a human leader holding coupons to drop them by it
 * @param fightBeforeLoot L6: a collector finishes the Ligators on its platform before it walks off for a coupon
 * @param rangedStandoff  L4: a bow, crossbow, claw or gun member shoots from a spot away from the spawn points
 * @param clusters        L1: every companion camps a platform cluster instead of grinding map-wide or a whole tower
 */
record AgentKpqStageOneLevers(boolean leaderCoupons, boolean fightBeforeLoot, boolean rangedStandoff,
                              boolean clusters) {
    private static final boolean LEADER_COUPONS_ENABLED = config.AgentTuning.booleanValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqStageOneLevers.LEADER_COUPONS_ENABLED");
    private static final boolean FIGHT_BEFORE_LOOT_ENABLED = config.AgentTuning.booleanValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqStageOneLevers.FIGHT_BEFORE_LOOT_ENABLED");
    private static final boolean RANGED_STANDOFF_ENABLED = config.AgentTuning.booleanValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqStageOneLevers.RANGED_STANDOFF_ENABLED");
    private static final boolean CLUSTERS_ENABLED = config.AgentTuning.booleanValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqStageOneLevers.CLUSTERS_ENABLED");

    static final AgentKpqStageOneLevers CONFIGURED = new AgentKpqStageOneLevers(
            LEADER_COUPONS_ENABLED, FIGHT_BEFORE_LOOT_ENABLED, RANGED_STANDOFF_ENABLED, CLUSTERS_ENABLED);
    static final AgentKpqStageOneLevers NONE = new AgentKpqStageOneLevers(false, false, false, false);

    /** The live Ligators' positions are read only when a lever looks at them. */
    boolean watchesLigators() {
        return fightBeforeLoot || clusters;
    }
}
