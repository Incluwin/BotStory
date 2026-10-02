package server.knowledge;

/**
 * One quest from Quest.wz. Reward exp is the raw WZ value (the server pays a multiple of it).
 *
 * @param reqItems      items to hand in at completion, with {@code reqItemCounts}
 * @param reqMobs       mobs to kill, with {@code reqMobCounts}
 */
public record QuestFact(int id, String name, int startNpc, int completeNpc, int minLevel,
                        IntList reqItems, IntList reqItemCounts, IntList reqMobs, IntList reqMobCounts,
                        int rewardExp, int rewardMeso) {
}
