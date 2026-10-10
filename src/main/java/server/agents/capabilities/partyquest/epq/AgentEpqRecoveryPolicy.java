package server.agents.capabilities.partyquest.epq;

import java.awt.Point;
import java.util.List;

/**
 * EPQ HP recovery rules, as pure statics (EPQ base fix 6). Players drink up before they go in and before a boss; the
 * generic autopot drinks only after a hit leaves HP under its alert, so an Agent that walked in at 50 HP (its respawn
 * HP) died to its first golem hit without a drink. An EPQ Agent therefore tops up to TOP_UP_HP_FRACTION of its
 * maximum on the entrance map, and on the golem's map while no golem lives and the event is not cleared.
 *
 * <p>EPQ base fix 7 (LPQ base fix 6's rule), as the golem rest fix of 2026-10-09 changed it: while a golem lives, a
 * member with HP under REST_HP_FRACTION, or with fewer than POT_STOP HP potions, backs off on its own side of the golem
 * (REACH_PX covers every ordinary golem attack and its Seal), drinks up to RESUME_HP_FRACTION and rejoins only at
 * RESUME_HP_FRACTION with POT_STOP potions; there it fights only a golem add that reaches it. The review of that fix:
 * a pinned rester (its rest point itself in the golem's reach) fights an add that reaches it there too, a dry one also
 * hits the golem from there while its HP is at least REST_HP_FRACTION, and a dry rester asks its party for HP potions
 * every DRY_ASK_INTERVAL_MS.
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
    static final long DRY_ASK_INTERVAL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRecoveryPolicy.DRY_ASK_INTERVAL_MS");

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
     * Whether a living golem-fight member rests. It starts once its HP falls under {@code rest} of its maximum, with or
     * without potions (so it backs off while it still holds some, not after the autopot drained them in melee), or
     * while it holds fewer than {@code potStop} HP potions at any HP. It keeps resting until its HP reaches
     * {@code resume} of its maximum and it holds {@code potStop} potions again: a stack shared at low HP does not send
     * it back before it drank up. An unknown count (-1) or maximum never rests.
     */
    static boolean resting(boolean wasResting, int hpPotions, int potStop, int hp, int maxHp,
                           double rest, double resume) {
        if (hpPotions < 0 || maxHp <= 0) return false;
        boolean stocked = hpPotions >= potStop;
        if (wasResting) return !stocked || hp < maxHp * resume;
        return !stocked || hp < maxHp * rest;
    }

    /**
     * Where a resting member waits, always on its own side of the golem and never nearer the golem than it stands, so
     * its walk never passes the golem (live 2026-10-08: two members pinned at x -540 went down walking to x 1020).
     * Its stored rest point while that stays on its side more than {@code reachPx} from the golem, so the golem's steps
     * around a threshold do not walk it to and fro; else its own spot when it already stands out of reach; else the
     * usable floor end (inside {@code edgeMarginPx} of each wall) on its side, or its spot when it stands past that end.
     * Pinned within reach, it keeps a stored point on its side at least as far from the golem. A member level with the
     * golem takes the side with more floor.
     */
    static Point restPoint(Point golem, Point position, Point stored, AgentEpqDefinition.BossFloor floor,
                           int edgeMarginPx, int reachPx) {
        boolean left = position.x != golem.x
                ? position.x < golem.x
                : golem.x - floor.leftX() >= floor.rightX() - golem.x;
        boolean storedOnSide = stored != null && stored.x != golem.x && (stored.x < golem.x) == left;
        if (storedOnSide && Math.abs(stored.x - golem.x) > reachPx) return stored;
        int x = Math.max(floor.leftX(), Math.min(floor.rightX(), position.x));
        if (Math.abs(x - golem.x) <= reachPx) {
            x = left ? Math.min(x, floor.leftX() + edgeMarginPx) : Math.max(x, floor.rightX() - edgeMarginPx);
            if (storedOnSide && Math.abs(stored.x - golem.x) >= Math.abs(x - golem.x)) return stored;
        }
        return new Point(x, floor.y());
    }

    /**
     * Whether a rest point is itself within {@code reachPx} of the golem: the member's side of the floor has no spot out
     * of reach (live 2026-10-08: golem at x -230, Halvard and Kestrel at the wall, x -540). Holding still there spares it
     * no golem hit.
     */
    static boolean pinned(Point restPoint, Point golem, int reachPx) {
        return restPoint != null && golem != null && Math.abs(restPoint.x - golem.x) <= reachPx;
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
