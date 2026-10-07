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
    /** S4, as stage 4 begins. */
    static final String DARK_ROOMS = "we'll split up for the dark rooms. wait by the lime balloon, we'll bring the passes.";
    /** S5, as stage 5 begins. */
    static final String ROOMS = "we'll take the rooms. wait by the green balloon, we'll bring the passes.";
    /** C1, on the bonus map after the clear. */
    static final String CLEARED = "we did it! break the boxes for loot. the pink balloon lets you out early, arturo has"
            + " the rewards.";
    /** C2, when the first companion reaches the reward map. */
    static final String ARTURO = "arturo's up at the top. grab your reward, we'll wait for you outside.";
    /** S8b, the first formation stands on its boxes. */
    static final String ON_THE_BOXES = "we're on the boxes. try the balloon.";
    /** S8w, a check came while one of them was still moving to its box. */
    static final String STILL_MOVING = "wait, still moving. try again when we're all on.";
    /** R1, said by a stage-9 member out of potions as it backs off. */
    static final String BACKING_OFF = "out of pots, backing off for a bit.";
    private static final List<String> NEXT_FORMATION = List.of("next one, try the balloon.", "moved. try it again.",
            "new boxes, check again.", "next one, try it.");
    /** H0, the run's first batch that does not make the count yet (once a run). */
    static final String BATCH_EXPLAINED = "i'll drop my passes at your feet as we get them, grab them.";
    /** H5, a carrier about to go down (low on HP, no HP potion left) leaves its passes where it stands. */
    static final String LEAVING_PASSES = "almost out of pots, leaving my passes here, grab them.";
    /** K5, said by Alishar's key carrier as it drops the key, about to go down. */
    static final String LEAVING_THE_KEY = "almost out of pots, leaving the key here, grab it.";
    /** H1p/K1p's own second line: the balloon talks first before it takes what he brings. */
    static final String EXPLAINS_FIRST = "it explains first, then talk to it again.";
    /** S6, said at the bottom of the portal maze, where everyone starts. */
    static final String MAZE_CODE = "it's 133 221 333 123 111 from the bottom, left to right. see you up top.";

    /**
     * Joins the two map-chat lines of a line too long for one (a player can send at most 127 characters): the same
     * speaker says them one after the other (chatLines).
     */
    static final char THEN = '\n';

    private static final List<String> BALLOONS = List.of("red balloon", "orange balloon", "yellow balloon",
            "lime balloon", "green balloon", "aqua balloon", "sky-blue balloon", "blue balloon", "violet balloon");

    private AgentLpqCompanionPlay() {
    }

    /** The map-chat lines a line is said as, in order: one, or the parts THEN joins. */
    static List<String> chatLines(String line) {
        if (line == null) return List.of();
        return java.util.Arrays.stream(line.split(String.valueOf(THEN))).filter(part -> !part.isBlank()).toList();
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

    /** The line a stage begins with (S1, S3, S4, S5), or null for a stage that has none of its own. */
    static String stageStartLine(int stage, String leader) {
        return switch (stage) {
            case 1 -> stageOneLine(leader);
            case 3 -> stageThreeLine(leader);
            case 4 -> DARK_ROOMS;
            case 5 -> ROOMS;
            default -> null;
        };
    }

    /** S9, as stage 9 begins. */
    static String stageNineLine(String leader) {
        return "shoot the black ratz on the ledge first, then alishar comes. " + leader + ", stay out of his reach.";
    }

    /** S6n, said from the top of the maze while he is still below it. */
    static String mazeClimbLine(String leader) {
        return leader + ", we're up top. 133 221 333 123 111 from the bottom, left to right.";
    }

    /** N1, said at the next portal while he stays away from it after the clear. */
    static String portalOpenLine(String leader) {
        return leader + ", the portal's open. come on.";
    }

    /** S7: who takes the ratz on the ledges, and where he waits; null without a shooter. */
    static String stageSevenLine(List<String> shooters, String leader) {
        if (shooters == null || shooters.isEmpty()) return null;
        String who = shooters.size() == 1 ? shooters.getFirst() : shooters.get(0) + " and " + shooters.get(1);
        return who + ", take the ratz up on the ledges." + THEN + "the rest of us wait for rombard down here. " + leader
                + ", stay at the bottom with us.";
    }

    /** S8a, while the blue balloon has not explained the boxes yet. */
    static String stageEightStartLine(String leader) {
        return leader + ", talk to the blue balloon first." + THEN
                + "then we stand on the boxes and you ask it if we got it right.";
    }

    /** S8b for the first formation, then S8n by turns for each later one ({@code attempt} counts from 0). */
    static String formationLine(int attempt) {
        return attempt <= 0 ? ON_THE_BOXES : NEXT_FORMATION.get((attempt - 1) % NEXT_FORMATION.size());
    }

    /** S8x: in a six-member run the leader stands on a box. */
    static String offTheBoxesLine(String leader) {
        return leader + ", stay off the boxes, only five can stand on them.";
    }

    /** K1 (and K1p while the violet balloon still has to explain itself), by where the key lies. */
    static String keyLine(String leader, PassPlace place, boolean balloonExplainsFirst) {
        String explains = balloonExplainsFirst ? THEN + EXPLAINS_FIRST : "";
        String talk = " grab it and talk to the " + balloonName(9) + "." + explains;
        return switch (place == null ? PassPlace.FEET : place) {
            case FEET -> leader + ", alishar's key is at your feet." + talk;
            case LEFT -> leader + ", alishar's key is just to your left." + talk;
            case RIGHT -> leader + ", alishar's key is just to your right." + talk;
            case AROUND -> leader + ", alishar's key is right around you." + talk;
            case ABOVE -> leader + ", alishar's key landed up above you. hop up," + talk;
            case BALLOON, DYING -> null;
        };
    }

    /** K1b: he could not be reached, so the key lies by the violet balloon. */
    static String keyByTheBalloonLine(String leader) {
        return leader + ", couldn't get to you, left the key by the " + balloonName(9) + ".";
    }

    /** K2: the key he left lying. */
    static String keyStillLyingLine(String leader) {
        return leader + ", the key's still by your feet.";
    }

    /** K3: the key he left lying was picked back up and dropped at his feet again. */
    static String keyRecollectedLine(String leader) {
        return leader + ", picked the key back up so it doesn't vanish. it's at your feet again.";
    }

    /** K4: he has held the key a while without talking to the balloon. */
    static String keyHeldLine(String leader) {
        return leader + ", take the key to the " + balloonName(9) + " up on the platform.";
    }

    /** An ordinary stage-5 room's door as a player names it (502-505), or null for the rooms that never give up. */
    static String stageFiveDoor(int roomMapId) {
        return switch (roomMapId) {
            case 922_010_502 -> "the second door from the top";
            case 922_010_503 -> "the third door from the top";
            case 922_010_504 -> "the fourth door from the top";
            case 922_010_505 -> "the door above the balloon";
            default -> null;
        };
    }

    /** H6p (a pass is left) or H6b (a box is left): a given-up room, so he can fetch it himself. */
    static String roomGivenUpLine(String leader, int roomMapId, boolean passLeft) {
        String door = stageFiveDoor(roomMapId);
        if (door == null) return null;
        return passLeft ? leader + ", there's a pass i can't reach in " + door + ". can you get it?"
                : leader + ", there's a box i can't get to in " + door + ". can you break it?";
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
        String explains = balloonExplainsFirst ? THEN + EXPLAINS_FIRST : "";
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

    private static final List<String> COUNT_WORDS = List.of("none", "one", "two", "three", "four", "five", "six");

    /** T1: a player left the run and it carries on with the rest (the count in words; LPQ never has more than six). */
    static String carryOnLine(String member, int remaining) {
        if (remaining < 0 || remaining >= COUNT_WORDS.size()) {
            throw new IllegalArgumentException("an LPQ party has zero to six members: " + remaining);
        }
        return member + " had to go. we'll keep going with " + COUNT_WORDS.get(remaining) + ".";
    }

    private static final List<String> BOX_NAMES = List.of("top left", "top right", "middle left", "middle",
            "middle right", "bottom left", "second from the left at the bottom", "third from the left at the bottom",
            "bottom right");

    /** Stage 8's box {@code platform} (1-9: the top row's two, the middle row's three, the bottom row's four) as players name it. */
    static String boxName(int platform) {
        if (platform < 1 || platform > BOX_NAMES.size()) {
            throw new IllegalArgumentException("stage 8 has boxes 1 to 9: " + platform);
        }
        return BOX_NAMES.get(platform - 1);
    }

    /** L8a: in a five-member run the leader stands on a box too; said when he is first given one. */
    static String yourBoxLine(String leader, int platform) {
        return leader + ", you take the " + boxName(platform) + " box.";
    }

    /** L8m: his box changed for the next formation. */
    static String moveToBoxLine(String leader, int platform) {
        return leader + ", move to the " + boxName(platform) + " box.";
    }

    /** How a companion run's entry stands, as HPQ's: everyone inside, keep waiting, go on without some, or give up. */
    enum Entry { WAIT, ENTERED, EJECT_ABSENT, GIVE_UP }

    static Entry entering(int inside, int members, long waitedMs, long entryWaitMs, int minimumParty) {
        if (inside >= members) return Entry.ENTERED;
        if (waitedMs < entryWaitMs) return Entry.WAIT;
        return inside >= minimumParty ? Entry.EJECT_ABSENT : Entry.GIVE_UP;
    }
}
