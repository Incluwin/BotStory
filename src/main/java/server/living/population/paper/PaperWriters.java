package server.living.population.paper;

import java.util.List;
import java.util.Map;

/**
 * The stories existing hooks file (docs/WORLD_SYSTEMS_PLAN.md N1.2), one call each: weddings (stock, companion, lore
 * couple), level 70/120/200 and 3rd/4th job for players and citizens, guilds founded and alliances made. A player's
 * story weighs {@link #HUMAN_BONUS} more than a citizen's, so a few hundred citizens never crowd a player out of the
 * Town section. Every call is cheap; {@link PaperDesk#file} does nothing while the paper is off.
 */
public final class PaperWriters {
    static final int HUMAN_BONUS = 15;
    static final int W_WEDDING = 70;
    static final int W_GUILD = 50;
    static final int W_ALLIANCE = 60;
    static final int W_THIRD_JOB = 40;
    static final int W_FOURTH_JOB = 60;

    private PaperWriters() {
    }

    /** The milestone's weight, or 0 when {@code level} is none (70, 120, 200). */
    static int levelWeight(int level) {
        return switch (level) {
            case 70 -> 35;
            case 120 -> 60;
            case 200 -> 100;
            default -> 0;
        };
    }

    /** The highest milestone in {@code (from, to]}, or 0: an offline citizen's stored level jumps several at once. */
    public static int milestoneCrossed(int from, int to) {
        for (int level : new int[] {200, 120, 70}) {
            if (from < level && level <= to) {
                return level;
            }
        }
        return 0;
    }

    /** A player ({@code human}) or citizen reached {@code level}: filed only at a milestone. */
    public static void levelReached(String name, int level, boolean human) {
        int weight = levelWeight(level);
        if (weight > 0 && name != null) {
            PaperDesk.file("town.level", weight + (human ? HUMAN_BONUS : 0), Map.of("name", name,
                    "level", String.valueOf(level)));
        }
    }

    /** The job tier of a v83 job id: 3 for a third job (111, 1111, 2111), 4 for a fourth (112, 2112), else 0. */
    static int tier(int jobId) {
        if (jobId < 100 || jobId % 100 == 0) {
            return 0;
        }
        return switch (jobId % 10) {
            case 1 -> 3;
            case 2 -> 4;
            default -> 0;
        };
    }

    /** A player or citizen took job {@code jobId} ({@code jobName} as players say it): filed at 3rd and 4th job. */
    public static void jobReached(String name, int jobId, String jobName, boolean human) {
        int tier = tier(jobId);
        if (tier == 0 || name == null || jobName == null || jobName.isBlank()) {
            return;
        }
        PaperDesk.file("town.job", (tier == 4 ? W_FOURTH_JOB : W_THIRD_JOB) + (human ? HUMAN_BONUS : 0),
                Map.of("name", name, "job", jobName));
    }

    public static void wedding(String name, String partner) {
        if (name != null && partner != null && !name.isBlank() && !partner.isBlank()) {
            PaperDesk.file("town.wedding", W_WEDDING, Map.of("name", name, "partner", partner));
        }
    }

    /** A guild {@code guild} was founded by character {@code leaderId} (its name is looked up off the game thread). */
    public static void guildFounded(String guild, int leaderId) {
        if (guild != null && !guild.isBlank() && leaderId > 0) {
            PaperDesk.fileNaming("town.guild", W_GUILD, Map.of("guild", guild), "leader", leaderId);
        }
    }

    public static void allianceMade(String alliance, List<String> guilds) {
        if (alliance != null && !alliance.isBlank() && !guilds.isEmpty()) {
            PaperDesk.file("town.alliance", W_ALLIANCE, Map.of("alliance", alliance,
                    PaperCompose.NAMES_SLOT, String.join(", ", guilds)));
        }
    }
}
