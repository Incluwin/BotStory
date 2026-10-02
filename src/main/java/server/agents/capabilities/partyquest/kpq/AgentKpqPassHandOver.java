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
        return recollectedLine(leader, PassPlace.FEET);
    }

    /** The re-collect's line, by where the passes it brought lie (see PassPlace). */
    static String recollectedLine(String leader, PassPlace place) {
        return leader + ", got the rest of the passes, " + switch (place == null ? PassPlace.FEET : place) {
            case FEET -> "they're at your feet.";
            case LEFT -> "they're just to your left.";
            case RIGHT -> "they're just to your right.";
            case AROUND -> "they're right around you.";
            case ABOVE -> "they landed up above you.";
        };
    }

    /** Said once, when a companion run's stage-1 passes are all down, by where they lie (see PassPlace). */
    static String stageOnePassesLine(String leader, PassPlace place) {
        return leader + ", " + passesLie(place) + (place == PassPlace.ABOVE
                ? ". hop up, grab them and talk to cloto." : ". pick them up and talk to cloto.");
    }

    /** Said once by the last stage-5 deliverer to drop at the leader's feet, by where the passes lie. */
    static String stageFivePassesLine(String leader, PassPlace place) {
        return leader + ", " + passesLie(place) + (place == PassPlace.ABOVE
                ? ". hop up, grab them and talk to cloto." : ". grab them and talk to cloto.");
    }

    private static String passesLie(PassPlace place) {
        return switch (place == null ? PassPlace.FEET : place) {
            case FEET -> "the passes are at your feet";
            case LEFT -> "the passes are just to your left";
            case RIGHT -> "the passes are just to your right";
            case AROUND -> "the passes are right around you";
            case ABOVE -> "the passes landed up above you";
        };
    }

    /**
     * Where passes a companion dropped for a companion run's leader lie against his spot, by the map's own placement
     * of the drop, as the lines that tell him name it: a drop from a spot a deliverer stepped aside to lies to his
     * left or right, up to PASS_DROP_SPOT_SEARCH_PX plus PASS_DROP_AT_FEET_PX from him, where "at your feet" would be
     * false, and one the map put on a foothold over him, where no spot by him lands on his, lies above him.
     */
    enum PassPlace {
        FEET, LEFT, RIGHT, AROUND, ABOVE;

        /** Where the passes of two drops lie, told as one: what he has to go to; at his feet only when both are. */
        PassPlace and(PassPlace other) {
            if (other == null || other == this || other == FEET) return this;
            if (this == FEET) return other;
            if (this == ABOVE || other == ABOVE) return ABOVE;
            return AROUND;
        }

        /** Where a drop the map lands at {@code landing} lies against his spot; at his feet when it cannot be told. */
        static PassPlace of(Point landing, Point leaderSpot, int feetPx) {
            if (landing == null || leaderSpot == null) return FEET;
            if (landing.y < leaderSpot.y - feetPx) return ABOVE;
            if (Math.abs(landing.x - leaderSpot.x) <= feetPx) return FEET;
            return landing.x < leaderSpot.x ? LEFT : RIGHT;
        }
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

    /**
     * How far a deliverer may step aside along the leader's foothold to drop: {@code searchPx}, but never so far that
     * a drop landing up to {@code feetPx} past the spot lies outside his reach ({@code nearPx} on each axis).
     */
    static int spotReach(int searchPx, int nearPx, int feetPx) {
        return Math.max(0, Math.min(searchPx, nearPx - feetPx));
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
     * dropper's side first, and returns the nearest spot from which a drop, and one from anywhere within
     * {@code feetPx} of it where a deliverer may stop, lands back on that spot's ground by {@code landing}. Where the
     * dropper already stands within {@code feetPx} of a spot, its own drop ({@code dropperLanding}, where the map
     * puts a drop from where it stands) must land there too: a catch narrower than a step between two of those looks
     * would otherwise have it drop into it, or walk on the spot it stands on until its bound. His own spot when drops
     * around it land there; null when no spot does, or none can be told.
     */
    static Point dropSpot(Point leaderSpot, Point dropper, Point dropperLanding, UnaryOperator<Point> groundAt,
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
        int first = dropper != null && dropper.x < leaderSpot.x ? -1 : 1;
        for (int distance = 0; distance * stepPx <= searchPx; distance++) {
            for (int side : distance == 0 ? new int[] {first} : new int[] {first, -first}) {
                int center = reach + side * distance;
                boolean clear = true;
                for (int i = center - margin; i <= center + margin && clear; i++) clear = landsThere[i];
                if (clear && dropperLanding != null && landsAt(dropper, ground[center], feetPx)
                        && !landsAt(dropperLanding, ground[center], feetPx)) {
                    clear = false;
                }
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
