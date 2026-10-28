package server.living.mentor;

import client.Job;

import java.util.List;
import java.util.Map;

/**
 * The way a mentor leads a new character (docs/BOT_CONCEPT_PLAN.md F4.3): Maple Island's one road, Mushroom Town to
 * Southperry (no route graph covers the island; MentorRouteTest checks each hop against WZ), Shanks's ship to Lith
 * Harbor, Lith's taxi a walk down from the ship deck (or, short of the fare, the deck's hidden way to the town's far
 * side), and on Victoria the route graphs to the instructor of the branch the newcomer picked. The first four hops are one-way (the server's portals have no way back); from the
 * Dangerous Forest on the road is two-way. Facts only: which leg comes next is the mentor sweep's call.
 */
public final class MentorRoute {
    public static final int MUSHROOM_TOWN = 10_000;
    public static final int SOUTHPERRY = 2_000_000;
    public static final int LITH_HARBOR = 104_000_000;
    /** Shanks on Southperry's beach: his ship goes to Lith Harbor for 150 meso from level 7 (22000.js), or a letter. */
    public static final int SHANKS = 22_000;
    public static final int SHANKS_LEVEL = 7;
    public static final int SHANKS_FARE = 150;
    public static final int SHANKS_LETTER = 4_031_801;
    /** Lith Harbor's Regular Cab (1002000.js): to the five job towns at a tenth of the fare for a Beginner. */
    public static final int LITH_TAXI = 1_002_000;
    public static final int TAXI_BEGINNER_FARE_MAX = 100;

    /** The road, in order; each map's next is the one after it. */
    static final List<Integer> ISLAND_ROAD = List.of(10_000, 20_000, 30_000, 40_000, 50_000, 1_000_000, 1_010_000,
            1_020_000, SOUTHPERRY);
    /** The hops with no way back, by the map they start from. */
    static final List<Integer> ONE_WAY_FROM = List.of(10_000, 20_000, 30_000, 40_000);
    /** The town of each first-job branch, where its instructor is (the taxi's destinations). */
    static final Map<Job, Integer> JOB_TOWNS = Map.of(Job.WARRIOR, 102_000_000, Job.MAGICIAN, 101_000_000,
            Job.BOWMAN, 100_000_000, Job.THIEF, 103_000_000, Job.PIRATE, 120_000_000);
    /** The five Explorer branches a newcomer picks from, in the menu's order. */
    public static final List<Job> BRANCHES = List.of(Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.PIRATE);
    /** Where an undecided newcomer is led: Henesys, the hub. */
    public static final int HUB_TOWN = 100_000_000;

    private MentorRoute() {
    }

    /** True on Maple Island (every map below Victoria's 100000000 that a Beginner walks: the road and its rooms). */
    public static boolean onIsland(int mapId) {
        return mapId >= 0 && mapId < 3_000_000;
    }

    /** The next map on the island road from {@code mapId}; 0 off the road or at its end (Southperry). */
    public static int nextOnIsland(int mapId) {
        int at = ISLAND_ROAD.indexOf(mapId);
        return at < 0 || at + 1 >= ISLAND_ROAD.size() ? 0 : ISLAND_ROAD.get(at + 1);
    }

    /** True when the island hop from {@code mapId} has no way back. */
    public static boolean oneWayFrom(int mapId) {
        return ONE_WAY_FROM.contains(mapId);
    }

    /** The town of a first-job branch (0 for none). */
    public static int townOf(Job branch) {
        Integer town = branch == null ? null : JOB_TOWNS.get(branch);
        return town == null ? 0 : town;
    }

    /** True when the newcomer may board Shanks's ship: level 7+ and the fare, or his letter. */
    public static boolean mayBoard(int level, int meso, boolean letter) {
        return letter || level >= SHANKS_LEVEL && meso >= SHANKS_FARE;
    }

    /** The first-job branch a chat line names ("warrior", "i wanna be a mage"); null for none or more than one. */
    public static Job branchIn(String line) {
        if (line == null) {
            return null;
        }
        String text = " " + line.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", " ") + " ";
        Job found = null;
        for (Map.Entry<Job, List<String>> words : BRANCH_WORDS.entrySet()) {
            for (String word : words.getValue()) {
                if (text.contains(" " + word + " ")) {
                    if (found != null && found != words.getKey()) {
                        return null; // two branches: no pick
                    }
                    found = words.getKey();
                }
            }
        }
        return found;
    }

    private static final Map<Job, List<String>> BRANCH_WORDS = Map.of(
            Job.WARRIOR, List.of("warrior", "warriors", "war", "swordsman"),
            Job.MAGICIAN, List.of("magician", "mage", "mages", "wizard", "magic"),
            Job.BOWMAN, List.of("bowman", "archer", "bowmen", "bow", "archers"),
            Job.THIEF, List.of("thief", "thieves", "rogue", "sin", "assassin"),
            Job.PIRATE, List.of("pirate", "pirates", "pirat"));
}
