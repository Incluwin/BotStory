package server.agents.capabilities.partyquest.kpq;

import java.awt.Point;
import java.util.Locale;

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

    /**
     * A floor pass is no longer on its way to the leader once it has lain out of his reach longer than the patience,
     * no companion is going for it, and it does not wait for him by Cloto; the missing-pass recovery may then count
     * it as lost.
     */
    static boolean stale(long ageMs, boolean inLeadersReach, boolean waitingByCloto, boolean fetched,
                         long patienceMs) {
        return ageMs >= patienceMs && !inLeadersReach && !waitingByCloto && !fetched;
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
