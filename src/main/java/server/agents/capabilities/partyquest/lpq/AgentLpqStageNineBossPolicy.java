package server.agents.capabilities.partyquest.lpq;

import java.awt.Point;
import java.util.List;
import java.util.Set;

/**
 * Where an LPQ stage-9 Agent fights Alishar from (LPQ base fix 5), as pure geometry on the 922010900 floor.
 *
 * <p>Ranged players stand out of Alishar's reach and shoot: his magic attack1 reaches 320 px in front of him and his
 * Seal and Darkness (MobSkill 120/121 level 1) 300 px either side, so a shooter keeps a standoff of STANDOFF_MIN_PX to
 * STANDOFF_MAX_PX (below the 400-px projectile range) on one side of him. Alishar walks onto his latest Agent hitter,
 * so shooters are split into two groups that prefer opposite sides and keep him between them. A side is changed only
 * when its station no longer fits on the floor (inside FLOOR_EDGE_MARGIN_PX of a wall), and a member pushed off its
 * preferred side returns only once that side fits with a margin of the band's width, so Alishar stepping around a
 * threshold does not walk a group back and forth through him. A shooter with one of his summons within ADD_THREAT_PX
 * fights the summon first; melee members take the summons first and Alishar when none is up.
 */
final class AgentLpqStageNineBossPolicy {
    private static final int REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.REACH_PX");
    private static final int STANDOFF_MIN_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.STANDOFF_MIN_PX");
    private static final int STANDOFF_MAX_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.STANDOFF_MAX_PX");
    private static final int FLOOR_EDGE_MARGIN_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.FLOOR_EDGE_MARGIN_PX");
    private static final int ADD_THREAT_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.ADD_THREAT_PX");
    private static final double REST_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.REST_HP_FRACTION");
    private static final double RESUME_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqStageNineBossPolicy.RESUME_HP_FRACTION");

    enum Side {
        LEFT, RIGHT;

        Side opposite() { return this == LEFT ? RIGHT : LEFT; }
    }

    enum Group { A, B }

    /** The standoff band, in px from Alishar along the floor; a station stands at its middle. */
    record Band(int minPx, int maxPx) {
        int targetPx() { return (minPx + maxPx) / 2; }
    }

    /** The boss floor (walls and height) and how far from either wall a station or rest point must stay. */
    record Floor(int leftX, int rightX, int y, int edgeMarginPx) {
        int usableLeftX() { return leftX + edgeMarginPx; }

        int usableRightX() { return rightX - edgeMarginPx; }
    }

    /** The mob ids an order prefers, and those it falls back to. */
    record Targets(Set<Integer> preferred, Set<Integer> fallback) { }

    /**
     * A member's stage-9 fight state: its shooter group and the side it started on (null for a melee member and
     * before its first fighting pass), the side and station it fights from, and whether and where it rests.
     */
    record MemberState(Group group, Side homeSide, Side side, Point station, boolean resting, Point restPoint) {
        static final MemberState NONE = new MemberState(null, null, null, null, false, null);

        MemberState {
            station = station == null ? null : new Point(station);
            restPoint = restPoint == null ? null : new Point(restPoint);
        }

        @Override
        public Point station() { return station == null ? null : new Point(station); }

        @Override
        public Point restPoint() { return restPoint == null ? null : new Point(restPoint); }

        MemberState withoutRest() { return new MemberState(group, homeSide, side, station, false, null); }
    }

    /**
     * What one pass sees of a living member and the fight: whether it is a shooter, its rank among the living
     * shooters on the map (-1 when it is not one of them), whether no member has a group yet, where it and Alishar
     * stand, where his summons stand, and its HP potions (-1 unknown), POT_STOP, HP and maximum HP.
     */
    record Pass(boolean shooter, int rank, boolean groupsUnset, Point position, Point boss, List<Point> summons,
                int hpPotions, int potStop, int hp, int maxHp) { }

    /** A member's move for one pass. */
    enum Move {
        /** A melee member grinds the order's targets (his summons first). */
        MELEE,
        /** A shooter walks to its station without attacking (to cross Alishar). */
        WALK_TO_STATION,
        /** A shooter farms from its station: it attacks the targets in range and walks back between attacks. */
        FIGHT_FROM_STATION,
        /** A resting member walks to its rest point. */
        WALK_TO_REST,
        /** A resting member stands still at its rest point. */
        REST
    }

    /** The member's state after this pass, its move, the point the move goes to (null for MELEE) and its targets. */
    record Order(MemberState state, Move move, Point point, Targets targets) { }

    private AgentLpqStageNineBossPolicy() { }

    static Band band() { return new Band(STANDOFF_MIN_PX, STANDOFF_MAX_PX); }

    static int floorEdgeMarginPx() { return FLOOR_EDGE_MARGIN_PX; }

    /** How far Alishar reaches either side of him: the wider of his attack1 and his Seal and Darkness envelope. */
    static int reachPx() { return REACH_PX; }

    static int addThreatPx() { return ADD_THREAT_PX; }

    static double restHpFraction() { return REST_HP_FRACTION; }

    static double resumeHpFraction() { return RESUME_HP_FRACTION; }

    /** A magic attacker or a bow, crossbow, claw or gun user; a Lucky Seven rogue holding a dagger is melee. */
    static boolean shooter(boolean magicAttacker, boolean rangedWeapon) {
        return magicAttacker || rangedWeapon;
    }

    /** The group of the shooter at this rank (by character id) among the shooters on the map: A, B, A, B, ... */
    static Group group(int rank) {
        return rank % 2 == 0 ? Group.A : Group.B;
    }

    /** Group A holds the side it started on; group B goes round to the other side. */
    static Side preferredSide(Group group, Side home) {
        return group == Group.A ? home : home.opposite();
    }

    static Side sideOf(Point position, Point boss) {
        return position.x < boss.x ? Side.LEFT : Side.RIGHT;
    }

    static int stationX(Side side, Point boss, Band band) {
        return side == Side.LEFT ? boss.x - band.targetPx() : boss.x + band.targetPx();
    }

    /** Whether the station on {@code side} stands at least {@code slackPx} inside the usable floor. */
    static boolean fits(Side side, Point boss, int slackPx, Band band, Floor floor) {
        int x = stationX(side, boss, band);
        return x >= floor.usableLeftX() + slackPx && x <= floor.usableRightX() - slackPx;
    }

    /**
     * The side a shooter fights from. With none yet: the preferred side if it fits, else the other. On the preferred
     * side: keep it while it fits. Off it: go back once the preferred side fits by the band's width, else keep the
     * current side while it fits, else the preferred side (one side always fits: the floor is wider than two
     * standoffs).
     */
    static Side chosenSide(Side current, Side preferred, Point boss, Band band, Floor floor) {
        if (current == null) return fits(preferred, boss, 0, band, floor) ? preferred : preferred.opposite();
        if (current == preferred) return fits(current, boss, 0, band, floor) ? current : current.opposite();
        if (fits(preferred, boss, band.maxPx() - band.minPx(), band, floor)) return preferred;
        return fits(current, boss, 0, band, floor) ? current : preferred;
    }

    /**
     * The station: the stored one while it stays inside the band on the chosen side (and inside the usable floor), so
     * a shooter does not step with every step Alishar takes; otherwise the band's middle on the chosen side.
     */
    static Point nextStation(Point boss, Point current, Side chosen, Band band, Floor floor) {
        if (current != null && current.y == floor.y() && sideOf(current, boss) == chosen
                && current.x >= floor.usableLeftX() && current.x <= floor.usableRightX()) {
            int dx = Math.abs(current.x - boss.x);
            if (dx >= band.minPx() && dx <= band.maxPx()) return current;
        }
        return new Point(stationX(chosen, boss, band), floor.y());
    }

    /** Whether one of Alishar's summons stands within {@code threatPx} of this position, both ways. */
    static boolean addThreat(Point position, List<Point> summons, int threatPx) {
        if (position == null || summons == null) return false;
        for (Point summon : summons) {
            if (summon != null && Math.abs(summon.x - position.x) <= threatPx
                    && Math.abs(summon.y - position.y) <= threatPx) {
                return true;
            }
        }
        return false;
    }

    /**
     * A shooter fights Alishar and falls back to his summons; a shooter with a summon on it, and every melee member,
     * fights the summons first and Alishar when none is up.
     */
    static Targets targets(boolean shooter, boolean threat) {
        Set<Integer> alishar = Set.of(AgentLpqDefinition.ALISHAR);
        return shooter && !threat
                ? new Targets(alishar, AgentLpqDefinition.ALISHAR_SUMMONS)
                : new Targets(AgentLpqDefinition.ALISHAR_SUMMONS, alishar);
    }

    /**
     * Whether a living stage-9 member out of potions rests (LPQ base fix 6): it starts with fewer than
     * {@code potStop} HP potions and HP under {@code rest} of its maximum (the generic out-of-potions stop's own
     * thresholds) and keeps resting until its HP reaches {@code resume} of its maximum or it holds {@code potStop}
     * potions again. An unknown count (-1) never rests.
     */
    static boolean resting(boolean wasResting, int hpPotions, int potStop, int hp, int maxHp,
                           double rest, double resume) {
        if (hpPotions < 0) return false;
        if (wasResting) return hpPotions < potStop && hp < maxHp * resume;
        return hpPotions < potStop && hp < maxHp * rest;
    }

    /**
     * One living member's stage-9 order while Alishar lives (LPQ base fix 5 and 6), from its state and this pass.
     *
     * <p>A member out of potions rests (see {@link #resting}): it walks to its rest point and stands still there, and
     * its group, side and station are kept for its return. A melee member fights the summons first and Alishar when
     * none is up. A shooter without a group joins one: by its rank while no member has a group yet, else group A; its
     * home side is the side of Alishar it stands on. It then takes the {@link #chosenSide} and the
     * {@link #nextStation} on it: it walks there while it stands on the other side of Alishar, and otherwise farms
     * from the station, fighting a summon on it first ({@link #targets}).
     */
    static Order order(MemberState state, Pass pass, Floor floor) {
        MemberState current = state == null ? MemberState.NONE : state;
        Band band = band();
        Point position = pass.position();
        Point boss = pass.boss();
        boolean rest = resting(current.resting(), pass.hpPotions(), pass.potStop(), pass.hp(), pass.maxHp(),
                REST_HP_FRACTION, RESUME_HP_FRACTION);
        if (rest) {
            Point restPoint = restPoint(boss, position, current.restPoint(), floor, REACH_PX);
            MemberState next = new MemberState(current.group(), current.homeSide(), current.side(),
                    current.station(), true, restPoint);
            // Within the anchored farm's own 8-px arrival it stands still; otherwise it walks there.
            return new Order(next, near(position, restPoint, 8) ? Move.REST : Move.WALK_TO_REST, restPoint, null);
        }
        if (!pass.shooter()) return new Order(current.withoutRest(), Move.MELEE, null, targets(false, false));
        Group group = current.group();
        Side home = current.homeSide();
        if (group == null) {
            group = pass.groupsUnset() && pass.rank() >= 0 ? group(pass.rank()) : Group.A;
            home = sideOf(position, boss);
        }
        Side chosen = chosenSide(current.side(), preferredSide(group, home), boss, band, floor);
        Point station = nextStation(boss, current.station(), chosen, band, floor);
        MemberState next = new MemberState(group, home, chosen, station, false, null);
        if (sideOf(position, boss) != chosen) return new Order(next, Move.WALK_TO_STATION, station, null);
        return new Order(next, Move.FIGHT_FROM_STATION, station,
                targets(true, addThreat(position, pass.summons(), ADD_THREAT_PX)));
    }

    private static boolean near(Point a, Point b, int radius) {
        return a != null && b != null && Math.abs(a.x - b.x) <= radius && Math.abs(a.y - b.y) <= radius;
    }

    /**
     * Where a resting member waits: its stored rest point while that stays out of Alishar's reach (more than
     * {@code reachPx} from him), so his steps around a threshold do not walk it to and fro; otherwise the usable floor
     * end on its side of him while that end is out of his reach, else the other end. A resting member therefore
     * crosses him only when its own side has no room outside his reach (one end always has: the usable floor is
     * wider than twice his reach).
     */
    static Point restPoint(Point boss, Point position, Point stored, Floor floor, int reachPx) {
        if (stored != null && Math.abs(stored.x - boss.x) > reachPx) return stored;
        Point left = new Point(floor.usableLeftX(), floor.y());
        Point right = new Point(floor.usableRightX(), floor.y());
        Point near = sideOf(position, boss) == Side.LEFT ? left : right;
        return Math.abs(near.x - boss.x) > reachPx ? near : (near == left ? right : left);
    }
}
