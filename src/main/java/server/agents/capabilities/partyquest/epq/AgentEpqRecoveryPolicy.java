package server.agents.capabilities.partyquest.epq;

/**
 * EPQ HP recovery rules, as pure statics (EPQ base fix 6). Players drink up before they go in and before a boss; the
 * generic autopot drinks only after a hit leaves HP under its alert, so an Agent that walked in at 50 HP (its respawn
 * HP) died to its first golem hit without a drink. An EPQ Agent therefore tops up to TOP_UP_HP_FRACTION of its
 * maximum on the entrance map, and on the golem's map while no golem lives and the event is not cleared.
 */
final class AgentEpqRecoveryPolicy {
    static final double TOP_UP_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.TOP_UP_HP_FRACTION");
    static final long TOP_UP_INTERVAL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.TOP_UP_INTERVAL_MS");

    private AgentEpqRecoveryPolicy() { }

    /**
     * Whether an Agent tops up on this map: the entrance always, the golem's map only while no golem lives and the
     * event is not cleared (during the fight the autopot covers it); never the recruit map, which has shops in reach.
     */
    static boolean topUpMap(int mapId, boolean golemAlive, boolean cleared) {
        return mapId == AgentEpqDefinition.ENTRANCE_MAP
                || (mapId == AgentEpqDefinition.BOSS_MAP && !golemAlive && !cleared);
    }

    /**
     * Whether a living member with known HP and HP potions is still under {@code target} of its maximum. An unknown
     * count (-1) or maximum never tops up.
     */
    static boolean needsTopUp(int hp, int maxHp, int hpPotions, double target) {
        return hp > 0 && maxHp > 0 && hpPotions > 0 && hp < maxHp * target;
    }
}
