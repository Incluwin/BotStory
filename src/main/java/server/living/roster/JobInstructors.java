package server.living.roster;

import client.Job;
import constants.game.GameConstants;

/**
 * Who gives each job advancement and where they stand, as in the stock NPC and quest scripts. An Explorer: the first
 * and second job from the class's instructor in its town, the third from its chief in El Nath's Chief's Residence, the
 * fourth from its elder in Leafre's Forest of the Priest. A Cygnus Knight: every advancement from its Chief Knight on
 * Ereve (quests 20101-20105, 20201-20205, 20311-20315). An Aran: the first job from the Giant Polearm, the rest from
 * Maha, both in Rien (21101, 21201, 21302, 21401). A companion going for its next job walks up to this NPC
 * (JobErrands), as does a citizen whose life reaches its next job (population.CitizenJobErrands). {@code name} is
 * what a player calls them.
 */
public final class JobInstructors {
    public record Instructor(int npcId, int mapId, String name, String town) { }

    private static final int CHIEFS_RESIDENCE_MAP_ID = 211000001;
    private static final int FOREST_OF_THE_PRIEST_MAP_ID = 240010501;
    private static final int EREVE_MAP_ID = 130000000;
    private static final int RIEN_MAP_ID = 140000000;

    private JobInstructors() {
    }

    /** The instructor who gives {@code job}; null for a beginner job and outside the Explorer, Cygnus and Aran lines. */
    public static Instructor of(Job job) {
        if (job == null || job.getId() % 1000 == 0) {
            return null;
        }
        if (GameConstants.isCygnus(job.getId())) {
            return GameConstants.getJobBranch(job) > 3 ? null : switch (job.getJobNiche()) {
                case 1 -> new Instructor(1101003, EREVE_MAP_ID, "Mihile", "Ereve");
                case 2 -> new Instructor(1101004, EREVE_MAP_ID, "Oz", "Ereve");
                case 3 -> new Instructor(1101005, EREVE_MAP_ID, "Irena", "Ereve");
                case 4 -> new Instructor(1101006, EREVE_MAP_ID, "Eckhart", "Ereve");
                default -> new Instructor(1101007, EREVE_MAP_ID, "Hawkeye", "Ereve");
            };
        }
        if (GameConstants.isAran(job.getId())) {
            return GameConstants.getJobBranch(job) == 1 ? new Instructor(1201001, RIEN_MAP_ID, "the Giant Polearm", "Rien")
                    : new Instructor(1201002, RIEN_MAP_ID, "Maha", "Rien");
        }
        if (job.getId() >= 600) {
            return null;
        }
        int family = job.getId() / 100;
        return switch (GameConstants.getJobBranch(job)) {
            case 1, 2 -> switch (family) {
                case 1 -> new Instructor(1022000, 102000003, "Dances with Balrog", "Perion");
                case 2 -> new Instructor(1032001, 101000003, "Grendel", "Ellinia");
                case 3 -> new Instructor(1012100, 100000201, "Athena Pierce", "Henesys");
                case 4 -> new Instructor(1052001, 103000003, "the Dark Lord", "Kerning City");
                default -> new Instructor(1090000, 120000101, "Kyrin", "Nautilus");
            };
            case 3 -> switch (family) {
                case 1 -> new Instructor(2020008, CHIEFS_RESIDENCE_MAP_ID, "Tylus", "El Nath");
                case 2 -> new Instructor(2020009, CHIEFS_RESIDENCE_MAP_ID, "Robeira", "El Nath");
                case 3 -> new Instructor(2020010, CHIEFS_RESIDENCE_MAP_ID, "Rene", "El Nath");
                case 4 -> new Instructor(2020011, CHIEFS_RESIDENCE_MAP_ID, "Arec", "El Nath");
                default -> new Instructor(2020013, CHIEFS_RESIDENCE_MAP_ID, "Pedro", "El Nath");
            };
            case 4 -> switch (family) {
                case 1 -> new Instructor(2081100, FOREST_OF_THE_PRIEST_MAP_ID, "Harmonia", "Leafre");
                case 2 -> new Instructor(2081200, FOREST_OF_THE_PRIEST_MAP_ID, "Gritto", "Leafre");
                case 3 -> new Instructor(2081300, FOREST_OF_THE_PRIEST_MAP_ID, "Legor", "Leafre");
                case 4 -> new Instructor(2081400, FOREST_OF_THE_PRIEST_MAP_ID, "Hellin", "Leafre");
                default -> new Instructor(2081500, FOREST_OF_THE_PRIEST_MAP_ID, "Samuel", "Leafre");
            };
            default -> null;
        };
    }

    /** "1st" to "4th": which advancement {@code job} is, as players say it. */
    public static String tier(Job job) {
        return switch (GameConstants.getJobBranch(job)) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> "4th";
        };
    }
}
