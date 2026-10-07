package server.agents.capabilities.partyquest.lpq;

import java.awt.Point;
import java.util.List;

/**
 * A companion run's LPQ play, kept apart from the coordinator so each rule and every line is pinned on its own: when
 * the party counts as inside, and what the companions say on each stage. Lines are lower case and name NPCs in lower
 * case; only a player's name keeps its capitals. No line carries a counter, an arrow, an id or a stage number.
 */
final class AgentLpqCompanionPlay {
    /** S2a, said on the leader's map while the two scouts go into stage 2 first. */
    static final String SCOUTS_GO_FIRST = "two of us go in first, there's a trap in there. wait here a sec.";
    /** S2b, once a scout reached the trap room and the way is clear. */
    static final String TRAP_CLEAR = "trap's clear, let's go in.";
    /** H0, the run's first batch that does not make the count yet (once a run). */
    static final String BATCH_EXPLAINED = "i'll drop my passes at your feet as we get them, grab them.";
    /** H5, a carrier about to go down (low on HP, no HP potion left) leaves its passes where it stands. */
    static final String LEAVING_PASSES = "almost out of pots, leaving my passes here, grab them.";
    /** S6, said at the bottom of the portal maze, where everyone starts. */
    static final String MAZE_CODE = "it's 133 221 333 123 111 from the bottom, left to right. see you up top.";

    private static final List<String> BALLOONS = List.of("red balloon", "orange balloon", "yellow balloon",
            "lime balloon", "green balloon", "aqua balloon", "sky-blue balloon", "blue balloon", "violet balloon");

    private AgentLpqCompanionPlay() {
    }

    /** The stage's balloon as players call it (stages 1-9). */
    static String balloonName(int stage) {
        if (stage < 1 || stage > BALLOONS.size()) throw new IllegalArgumentException("LPQ stage 1-9: " + stage);
        return BALLOONS.get(stage - 1);
    }

    /** S1, as the party stands inside on stage 1. */
    static String stageOneLine(String leader) {
        return leader + ", talk to the " + balloonName(1) + " so it knows we're here. we'll get the passes.";
    }

    /** S3, as stage 3 begins. */
    static String stageThreeLine(String leader) {
        return leader + ", talk to the " + balloonName(3) + ", we'll get the passes.";
    }

    /** The line a stage begins with (S1, S3), or null for a stage that has none of its own. */
    static String stageStartLine(int stage, String leader) {
        return switch (stage) {
            case 1 -> stageOneLine(leader);
            case 3 -> stageThreeLine(leader);
            default -> null;
        };
    }

    // ---- hand-overs (H0-H5) ----

    /** Where a hand-over stack lies against the leader's spot, as the lines name it (KPQ's PassPlace, plus two). */
    enum PassPlace {
        FEET, LEFT, RIGHT, AROUND, ABOVE, BALLOON, DYING;

        /** Where a drop the map lands at {@code landing} lies against his spot; at his feet when it cannot be told. */
        static PassPlace of(Point landing, Point leaderSpot, int feetPx) {
            if (landing == null || leaderSpot == null) return FEET;
            if (landing.y < leaderSpot.y - feetPx) return ABOVE;
            if (Math.abs(landing.x - leaderSpot.x) <= feetPx) return FEET;
            return landing.x < leaderSpot.x ? LEFT : RIGHT;
        }
    }

    /** H1 (and H1p while the balloon still has to explain itself), by where the passes lie; null for BALLOON/DYING. */
    static String passesLine(String leader, int stage, PassPlace place, boolean balloonExplainsFirst) {
        String balloon = balloonName(stage);
        String explains = balloonExplainsFirst ? " it explains first, then talk to it again." : "";
        return switch (place == null ? PassPlace.FEET : place) {
            case FEET -> leader + ", the passes are at your feet. grab them and talk to the " + balloon + "." + explains;
            case LEFT -> leader + ", the passes are just to your left. grab them and talk to the " + balloon + "."
                    + explains;
            case RIGHT -> leader + ", the passes are just to your right. grab them and talk to the " + balloon + "."
                    + explains;
            case AROUND -> leader + ", the passes are right around you. grab them and talk to the " + balloon + "."
                    + explains;
            case ABOVE -> leader + ", the passes landed up above you. hop up, grab them and talk to the " + balloon
                    + "." + explains;
            case BALLOON, DYING -> null;
        };
    }

    /** H1b: the leader could not be reached (or the walk ran out of time), so the passes lie by the balloon. */
    static String passesByTheBalloonLine(String leader, int stage) {
        return leader + ", couldn't get to you, left the passes by the " + balloonName(stage) + ".";
    }

    /** H2: a hand-over stack he left lying, by his feet or back where he was. */
    static String passesStillLyingLine(String leader, boolean byHisFeet) {
        return byHisFeet ? leader + ", the passes are still by your feet."
                : leader + ", you left some passes on the ground back there.";
    }

    /** H3: a stack he left lying was picked back up before it vanished and dropped at his feet again. */
    static String passesRecollectedLine(String leader) {
        return leader + ", picked the passes back up so they don't vanish. they're at your feet again.";
    }

    /** H4: he holds the stage's passes and no hand-over line told him where to take them. */
    static String allPassesLine(String leader, int stage) {
        return "that's all the passes, " + leader + ". talk to the " + balloonName(stage) + ".";
    }

    /**
     * A carrier brings its passes over once it holds {@code batch} and he is near on its map, at once when the party
     * holds the stage's count or its split room is done, and where it stands when it is about to go down. Nothing
     * carried, nothing due. (The same-map rule is the coordinator's: only dying overrides it.)
     */
    static boolean handOverDue(int carried, int batch, boolean leaderNear, boolean countReached, boolean roomDone,
                               boolean dying) {
        if (carried <= 0) return false;
        return dying || (carried >= batch && leaderNear) || countReached || roomDone;
    }

    /** What a carrier does next on the leader's map: walk on, drop at or beside his feet, or take it to the balloon. */
    enum HandOver { WALK, DROP_AT_FEET, DROP_BESIDE, TO_BALLOON }

    static HandOver handOver(Point carrier, Point leaderSpot, boolean carrierOnGround, boolean leaderOnGround,
                             Point landing, boolean leaderReachable, long walkingMs, long timeoutMs, int reachPx) {
        if (!leaderReachable || walkingMs > timeoutMs) return HandOver.TO_BALLOON;
        if (!atFeet(carrier, leaderSpot, carrierOnGround, leaderOnGround, reachPx)) return HandOver.WALK;
        if (landing == null || landsAt(landing, leaderSpot, reachPx)) return HandOver.DROP_AT_FEET;
        if (Math.abs(landing.y - leaderSpot.y) <= reachPx) return HandOver.DROP_BESIDE;
        return HandOver.WALK;
    }

    /** Both stand on a foothold within {@code px} of each other on both axes (never in the air or on a rope). */
    static boolean atFeet(Point dropper, Point spot, boolean dropperOnGround, boolean spotOnGround, int px) {
        return dropper != null && spot != null && dropperOnGround && spotOnGround
                && Math.abs(dropper.x - spot.x) <= px && Math.abs(dropper.y - spot.y) <= px;
    }

    /** A drop the map lands within {@code px} of this spot on both axes. */
    static boolean landsAt(Point landing, Point spot, int px) {
        return landing != null && spot != null
                && Math.abs(landing.x - spot.x) <= px && Math.abs(landing.y - spot.y) <= px;
    }

    /** The configured re-collect time, never later than the server's own latest safe time. */
    static long recollectAfterMs(long configuredMs, long latestMs) {
        return Math.min(configuredMs, latestMs);
    }

    static boolean recollectDue(long droppedAtMs, long nowMs, long recollectAfterMs, int recollectsDone,
                                int maxRecollects) {
        return recollectsDone < maxRecollects && nowMs - droppedAtMs >= recollectAfterMs;
    }

    static boolean nudgeDue(long droppedAtMs, long nowMs, long nudgeMs) {
        return nowMs - droppedAtMs >= nudgeMs;
    }

    static boolean lowOnHp(int hp, int maxHp, int dyingPercent) {
        return hp > 0 && maxHp > 0 && (long) hp * 100L <= (long) maxHp * dyingPercent;
    }

    /** About to go down: low on HP with no HP potion left (an unknown count, -1, is not none). */
    static boolean dying(int hp, int maxHp, int hpPotions, int dyingPercent) {
        return lowOnHp(hp, maxHp, dyingPercent) && hpPotions == 0;
    }

    /** How a companion run's entry stands, as HPQ's: everyone inside, keep waiting, go on without some, or give up. */
    enum Entry { WAIT, ENTERED, EJECT_ABSENT, GIVE_UP }

    static Entry entering(int inside, int members, long waitedMs, long entryWaitMs, int minimumParty) {
        if (inside >= members) return Entry.ENTERED;
        if (waitedMs < entryWaitMs) return Entry.WAIT;
        return inside >= minimumParty ? Entry.EJECT_ABSENT : Entry.GIVE_UP;
    }
}
