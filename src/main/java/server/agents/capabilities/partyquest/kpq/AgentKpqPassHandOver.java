package server.agents.capabilities.partyquest.kpq;

import java.awt.Point;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The rules a companion run's stage-5 pass hand-over decides by, and the lines its companions say, kept apart from
 * the coordinator so each is pinned on its own. Live (2026-10-01, run pace1b) the human leader stood on the arrival
 * ledge, which nothing below can reach; the carriers stood still for 90 s, dropped the passes 2,300 px under him, and
 * the event timer ran out while those passes held the missing-pass recovery back.
 */
final class AgentKpqPassHandOver {
    /** Why a companion let go of passes it carried, as the pass-drop log line names it. */
    enum DropReason {
        BESIDE, AT_CLOTO, OVERDUE, DYING, CLEAR, RECOLLECT;

        String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    static final String CURSE_EYES_CAREFUL = "careful, curse eyes down there.";
    static final String LEAVING_PASSES = "almost out of pots, leaving my passes here, grab them.";
    static final String RECOLLECT_START = "i'll go grab the passes still lying around.";

    private AgentKpqPassHandOver() {
    }

    /** Everyone arrives on the left top island; Cloto stands on the right one, past a gap no jump clears. */
    static String routeLine(String leader) {
        return leader + ", cloto's across the gap on the right. hop down and take the two ladders up,"
                + " we'll bring the passes there.";
    }

    /** Said once more if the leader is still where nobody can bring him anything. */
    static String routeAgainLine(String leader) {
        return leader + ", you still up there? drop off the ledge and take the two ladders on the right up to cloto.";
    }

    static String leftByClotoLine(String leader) {
        return leader + ", couldn't get to you, left the passes by cloto.";
    }

    /** A stage-1 holder that could not get onto the leader's own spot in time. */
    static String passBesideLine(String leader) {
        return leader + ", dropped my pass right next to you.";
    }

    /**
     * Said once to a companion run's human leader who has left a companion's stage-1 pass lying: live (2026-10-02,
     * run kpq-af7b89bb) he picked up two of three passes dropped at his feet, and the third lay there until it
     * expired. Where the passes no longer lie by his feet (he walked off), it says so instead.
     */
    static String stageOneNudgeLine(String leader, int passes, boolean byHisFeet) {
        if (byHisFeet) {
            return passes > 1 ? leader + ", there are still passes right by your feet."
                    : leader + ", there's still a pass right by your feet.";
        }
        return passes > 1 ? leader + ", you left some passes on the ground back there."
                : leader + ", you left a pass on the ground back there.";
    }

    /**
     * Said once to a companion run's human leader who has left stage-5 passes a companion dropped on his spot: live
     * (2026-10-02, run kpq-a6f696f0) two companions dropped ten passes beside him, he never picked them up, and the
     * recovery waited for them until they expired 180 s later. Where they no longer lie by his feet (he walked off), it
     * says so instead.
     */
    static String stageFiveNudgeLine(String leader, int passes, boolean byHisFeet) {
        if (byHisFeet) {
            return passes > 1 ? leader + ", the passes are right by your feet."
                    : leader + ", the pass is right by your feet.";
        }
        return passes > 1 ? leader + ", you left the passes on the ground back there."
                : leader + ", you left a pass on the ground back there.";
    }

    static String recollectedLine(String leader) {
        return leader + ", got the rest of the passes, they're at your feet.";
    }

    /**
     * A player picks up only what lies almost under him (the WASM client from about 9 px left to 41 px right of a
     * drop), so passes go down on the leader's own spot: both standing on a foothold, within {@code px} of each
     * other on both axes. Never while either is in the air or on a rope or ladder, where the drop would fall away.
     */
    static boolean atFeet(Point dropper, Point leaderSpot, boolean dropperOnGround, boolean leaderOnGround, int px) {
        return dropper != null && leaderSpot != null && dropperOnGround && leaderOnGround
                && Math.abs(dropper.x - leaderSpot.x) <= px && Math.abs(dropper.y - leaderSpot.y) <= px;
    }

    /** A drop the map would land within {@code px} of this spot on both axes: on that foothold, not on one over it. */
    static boolean landsAt(Point landing, Point spot, int px) {
        return landing != null && spot != null
                && Math.abs(landing.x - spot.x) <= px && Math.abs(landing.y - spot.y) <= px;
    }

    /**
     * Where on the leader's foothold a deliverer stands to drop for him. The map lands a player's drop on the first
     * foothold under a point 85 px over the dropper, so a ramp, stair or platform less than that over him catches
     * it: live (2026-10-02, kpq-fea2a83b) a pass dropped 6 px from him landed 83 px up. This walks his foothold from
     * his spot, {@code stepPx} at a time ({@code groundAt} gives the ground nearest a point, and his foothold ends
     * where it lies more than {@code feetPx} above or below the last step's), up to {@code searchPx} either way, the
     * dropper's side ({@code dropperSide}, its sign) first, and returns the nearest spot from which a drop, and one
     * from anywhere within {@code feetPx} of it where a deliverer may stop, lands back on that spot's ground by
     * {@code landing}. His own spot when drops around it land there; null when no spot does, or none can be told.
     */
    static Point dropSpot(Point leaderSpot, int dropperSide, UnaryOperator<Point> groundAt,
                          UnaryOperator<Point> landing, int feetPx, int searchPx, int stepPx) {
        if (leaderSpot == null || stepPx <= 0 || searchPx < 0 || feetPx < 0) return null;
        int margin = (feetPx + stepPx - 1) / stepPx;
        int reach = searchPx / stepPx + margin;
        Point[] ground = new Point[2 * reach + 1];
        ground[reach] = new Point(leaderSpot);
        for (int side = -1; side <= 1; side += 2) {
            Point previous = ground[reach];
            for (int step = 1; step <= reach; step++) {
                Point next = groundAt.apply(new Point(leaderSpot.x + side * step * stepPx, previous.y));
                if (next == null || Math.abs(next.y - previous.y) > feetPx) break;
                ground[reach + side * step] = next;
                previous = next;
            }
        }
        boolean[] landsThere = new boolean[ground.length];
        for (int i = 0; i < ground.length; i++) {
            landsThere[i] = ground[i] != null && landsAt(landing.apply(new Point(ground[i])), ground[i], feetPx);
        }
        int first = dropperSide < 0 ? -1 : 1;
        for (int distance = 0; distance * stepPx <= searchPx; distance++) {
            for (int side : distance == 0 ? new int[] {first} : new int[] {first, -first}) {
                int center = reach + side * distance;
                boolean clear = true;
                for (int i = center - margin; i <= center + margin && clear; i++) clear = landsThere[i];
                if (clear) return new Point(ground[center]);
            }
        }
        return null;
    }

    /**
     * A floor pass is no longer on its way to the leader once it has lain out of his reach longer than the patience,
     * no companion is going for it, and it does not wait for him by Cloto; the missing-pass recovery may then count
     * it as lost.
     */
    static boolean stale(long ageMs, boolean inLeadersReach, boolean waitingByCloto, boolean fetched,
                         long patienceMs) {
        return ageMs >= patienceMs && !inLeadersReach && !waitingByCloto && !fetched;
    }

    /**
     * A floor pass lying in the leader's reach is no longer on its way to him once he has left it there longer than
     * the patience and no companion is going for it: it needs no re-collect, it already lies by him, and the
     * missing-pass recovery may then count it as left.
     */
    static boolean leftAtFeet(long ageMs, boolean inLeadersReach, boolean fetched, long patienceMs) {
        return ageMs >= patienceMs && inLeadersReach && !fetched;
    }

    /**
     * A drop on the leader's own spot, at the hand-over or the re-collect: the only stage-5 drops he is reminded of.
     * Passes left by Cloto, where a companion stood, or before it went down were explained as they were dropped, and
     * a pass a companion's kill left lying is no drop of its at all.
     */
    static boolean droppedOnLeadersSpot(DropReason reason) {
        return reason == DropReason.BESIDE || reason == DropReason.RECOLLECT;
    }

    /** Still standing, below this fraction of its HP. */
    static boolean lowOnHp(int hp, int maxHp, double hpFraction) {
        return hp > 0 && maxHp > 0 && hp < maxHp * hpFraction;
    }

    /**
     * A carrier low on HP, with no HP potion left, while something it could go down to still lives on stage 5, is
     * about to go down, and its passes with it. A potion count that cannot be told (negative) is not none.
     */
    static boolean aboutToGoDown(boolean lowOnHp, int hpPotions, boolean mobsAlive) {
        return lowOnHp && hpPotions == 0 && mobsAlive;
    }
}
