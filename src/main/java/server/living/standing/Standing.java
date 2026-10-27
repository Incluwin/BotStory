package server.living.standing;

import server.living.bond.Friendship;
import server.living.population.PersonMemory;

import java.util.Locale;

/**
 * Standing (docs/BOT_CONCEPT_PLAN.md C1): one ladder for every (player, bot) pair, STRANGER, REGULAR, FRIEND and CLOSE,
 * read from what that kind of bot already keeps: a Resident's friendship level, a citizen's warmth (PersonMemory) and
 * a hired citizen's friendship level (its {@code hire:<characterId>} bond, kept while HIRE_FRIENDSHIP_ENABLED). A view,
 * not a fourth store: pure, so the mapping tables are tested as they are written in the plan.
 */
public final class Standing {
    /** The ladder, lowest first. */
    public enum Rung {
        STRANGER, REGULAR, FRIEND, CLOSE;

        public boolean atLeast(Rung other) {
            return ordinal() >= other.ordinal();
        }
    }

    /** Friendship levels a Resident's REGULAR, FRIEND and CLOSE begin at (Lv 2 ACQUAINTED, 4 TRUSTED, 7 Close friend). */
    public static final int REGULAR_LEVEL = 2;
    public static final int FRIEND_LEVEL = 4;
    public static final int CLOSE_LEVEL = Friendship.CLOSE_FRIEND;
    /** A hire at this friendship level or more works for less (C1.4). */
    public static final int WAGE_DISCOUNT_LEVEL = Friendship.CLOSE_FRIEND;
    /** A hire at this friendship level is permanent: its owner's long absence does not send it home (C1.4). */
    public static final int PERMANENT_LEVEL = Friendship.MAX_LEVEL;
    /** The warmth a released hire keeps at Lv 7-9 and at Lv 10 (the population has no CLOSE: it fades from there). */
    static final int CLOSE_WARMTH = 60;
    static final int BEST_WARMTH = 80;

    private Standing() {
    }

    /** A Resident's rung from its friendship level: Lv 0-1 STRANGER, 2-3 REGULAR, 4-6 FRIEND, 7+ CLOSE. */
    public static Rung ofResident(int level) {
        if (level >= CLOSE_LEVEL) {
            return Rung.CLOSE;
        }
        if (level >= FRIEND_LEVEL) {
            return Rung.FRIEND;
        }
        return level >= REGULAR_LEVEL ? Rung.REGULAR : Rung.STRANGER;
    }

    /**
     * A citizen's rung from how warm it feels toward the player now: under 15 STRANGER, 15-39 REGULAR, 40 and up FRIEND.
     * A citizen reaches CLOSE only after being hired.
     */
    public static Rung ofCitizen(int warmth) {
        if (warmth >= PersonMemory.FOND) {
            return Rung.FRIEND;
        }
        return warmth >= PersonMemory.WARM ? Rung.REGULAR : Rung.STRANGER;
    }

    /** A hire's rung from its owner's friendship level: Lv 0-3 REGULAR (it works for them), 4-6 FRIEND, 7+ CLOSE. */
    public static Rung ofHire(int level) {
        if (level >= CLOSE_LEVEL) {
            return Rung.CLOSE;
        }
        return level >= FRIEND_LEVEL ? Rung.FRIEND : Rung.REGULAR;
    }

    /** A grudge (warmth -30 or under): such a citizen never offers to join. */
    public static boolean grudge(int warmth) {
        return warmth <= PersonMemory.COLD;
    }

    /**
     * The bond points a citizen starts its hire with, once, from its warmth then: FRIEND (40+) the start of Lv 4,
     * REGULAR (15+) the start of Lv 2, anyone else the start of Lv 1 ({@code levelPoints} is FRIENDSHIP_LEVEL_POINTS:
     * 75, 25 and 1 by default).
     */
    public static int seedPoints(int warmth, int[] levelPoints) {
        int level = switch (ofCitizen(warmth)) {
            case FRIEND, CLOSE -> FRIEND_LEVEL;
            case REGULAR -> REGULAR_LEVEL;
            case STRANGER -> 1;
        };
        return levelPoints[Math.min(level, levelPoints.length) - 1];
    }

    /**
     * The warmth a hire let go keeps toward its old owner (at least; a warmer memory stays as it is), so it greets them
     * as the friend it became: Lv 0-3 15 (REGULAR), 4-6 40 (FRIEND), 7-9 60, 10 80.
     */
    public static int releaseWarmth(int level) {
        if (level >= PERMANENT_LEVEL) {
            return BEST_WARMTH;
        }
        if (level >= CLOSE_LEVEL) {
            return CLOSE_WARMTH;
        }
        return level >= FRIEND_LEVEL ? PersonMemory.FOND : PersonMemory.WARM;
    }

    /** "Friend" for FRIEND. */
    public static String label(Rung rung) {
        String name = rung.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }
}
