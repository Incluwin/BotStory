package server.knowledge;

/**
 * What a bot plausibly knows (docs/SERVER_KNOWLEDGE_PLAN.md 6.5), the minimal immersion guard until step 4
 * replaces it with lived knowledge. Pure: the caller supplies the entity's level and place.
 *
 * <ul>
 *   <li>Anything referenced by the bot's own quests and arcs (residents) is known.</li>
 *   <li>Places in its home region, the hub towns and their interiors are always known.</li>
 *   <li>Otherwise it knows an entity whose level (mob level, item reqLevel, map mob level, PQ or quest minimum)
 *       is at most its own level plus the slack (default 10): a level-20 companion asked about Zakum's helmet
 *       (lv 50) deflects, a level-90 one answers.</li>
 *   <li>Graded beyond that ({@link #grade}): up to {@value #ROUGH_LEVELS} more levels, and famous bosses and PQs at
 *       any level, are known by name and rough place only ("ludi somewhere, high lvl tho"); past that, nothing.</li>
 * </ul>
 * Knowledge downward is unlimited.
 */
public final class KnowledgeHorizon {
    /** Levels past the slack where a bot still knows a name and roughly where it is. */
    static final int ROUGH_LEVELS = 15;

    /** FULL: everything; ROUGH: name and rough place, no route or details; NONE: say you don't know. */
    public enum Grade { FULL, ROUGH, NONE }

    private final int slack;

    public KnowledgeHorizon(int slack) {
        this.slack = Math.max(0, slack);
    }

    /** The bot doing the knowing. {@code homeRegion} null: no home (companions). */
    public record Knower(int level, String homeRegion) {
    }

    /**
     * @param level     the entity's level (0 = no level, always within reach)
     * @param region    the entity's region (where a place, NPC or PQ lobby is), null when it has none
     * @param townKnown the entity is a hub town, an interior of one, or stands in one
     * @param ownRef    the entity is part of the bot's own quests or arcs
     */
    public boolean knows(Knower bot, int level, String region, boolean townKnown, boolean ownRef) {
        if (ownRef || townKnown) {
            return true;
        }
        if (region != null && region.equals(bot.homeRegion())) {
            return true;
        }
        return level <= bot.level() + slack;
    }

    /** {@link #knows}, graded: {@code famous} (a boss, a PQ) is never below ROUGH. */
    public Grade grade(Knower bot, int level, String region, boolean townKnown, boolean ownRef, boolean famous) {
        if (knows(bot, level, region, townKnown, ownRef)) {
            return Grade.FULL;
        }
        return famous || level <= bot.level() + slack + ROUGH_LEVELS ? Grade.ROUGH : Grade.NONE;
    }

    /** Whether a level is within reach on its own (used to filter drop sources and training maps). */
    public boolean withinReach(Knower bot, int level) {
        return level <= bot.level() + slack;
    }

    public int slack() {
        return slack;
    }
}
