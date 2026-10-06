package server.agents.capabilities.partyquest.epq;

import java.awt.Point;
import java.util.List;

/**
 * EPQ HP recovery rules, as pure statics (EPQ base fix 6). Players drink up before they go in and before a boss; the
 * generic autopot drinks only after a hit leaves HP under its alert, so an Agent that walked in at 50 HP (its respawn
 * HP) died to its first golem hit without a drink. An EPQ Agent therefore tops up to TOP_UP_HP_FRACTION of its
 * maximum on the entrance map, and on the golem's map while no golem lives and the event is not cleared.
 *
 * <p>EPQ base fix 7 (LPQ base fix 6's rule): while a golem lives, a member with fewer than POT_STOP HP potions and HP
 * under REST_HP_FRACTION rests out of the golem's reach (REACH_PX covers every ordinary golem attack and its Seal)
 * until RESUME_HP_FRACTION or POT_STOP potions, and there fights only a golem add that reaches it.
 */
final class AgentEpqRecoveryPolicy {
    static final double TOP_UP_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.TOP_UP_HP_FRACTION");
    static final long TOP_UP_INTERVAL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.TOP_UP_INTERVAL_MS");
    static final int REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.REACH_PX");
    static final int FLOOR_EDGE_MARGIN_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.FLOOR_EDGE_MARGIN_PX");
    static final int REST_ARRIVAL_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.REST_ARRIVAL_PX");
    static final int ADD_THREAT_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.ADD_THREAT_PX");
    static final double REST_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.REST_HP_FRACTION");
    static final double RESUME_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.RESUME_HP_FRACTION");

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

    /**
     * Whether a living golem-fight member out of potions rests: it starts with fewer than {@code potStop} HP potions
     * and HP under {@code rest} of its maximum (the generic out-of-potions stop's own thresholds) and keeps resting
     * until its HP reaches {@code resume} of its maximum or it holds {@code potStop} potions again. An unknown count
     * (-1) never rests.
     */
    static boolean resting(boolean wasResting, int hpPotions, int potStop, int hp, int maxHp,
                           double rest, double resume) {
        if (hpPotions < 0) return false;
        if (wasResting) return hpPotions < potStop && hp < maxHp * resume;
        return hpPotions < potStop && hp < maxHp * rest;
    }

    /**
     * Where a resting member waits: its stored rest point while that stays more than {@code reachPx} from the golem,
     * so the golem's steps around a threshold do not walk it to and fro; otherwise the usable floor end (inside
     * {@code edgeMarginPx} of each wall) on its side of the golem while that end is out of reach, else the other end.
     * The usable floor is wider than twice the reach, so one end always is.
     */
    static Point restPoint(Point golem, Point position, Point stored, AgentEpqDefinition.BossFloor floor,
                           int edgeMarginPx, int reachPx) {
        if (stored != null && Math.abs(stored.x - golem.x) > reachPx) return stored;
        Point left = new Point(floor.leftX() + edgeMarginPx, floor.y());
        Point right = new Point(floor.rightX() - edgeMarginPx, floor.y());
        Point near = position.x < golem.x ? left : right;
        return Math.abs(near.x - golem.x) > reachPx ? near : (near == left ? right : left);
    }

    /** Whether a golem add stands within {@code threatPx} of this position, both ways. */
    static boolean addThreat(Point position, List<Point> adds, int threatPx) {
        if (position == null || adds == null) return false;
        for (Point add : adds) {
            if (add != null && Math.abs(add.x - position.x) <= threatPx
                    && Math.abs(add.y - position.y) <= threatPx) {
                return true;
            }
        }
        return false;
    }
}
