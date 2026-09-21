package server.living.config;

import java.util.List;
import java.util.Map;

/**
 * The authored content model (docs/LIVING_RPG_PLAN.md section 4). Records mirror the YAML
 * one to one; {@link ContentLoader} builds them and {@link ContentLint} cross-checks them.
 */
public final class Content {
    private Content() {
    }

    public enum Strength { ROOKIE, SEASONED, VETERAN, ELITE }

    public enum BondStage {
        NONE, MET, ACQUAINTED, TRUSTED, SWORN;

        public boolean atLeast(BondStage other) {
            return ordinal() >= other.ordinal();
        }
    }

    public enum StepType {
        TALK, KILL_MOBS, COLLECT_ITEM, DELIVER, VISIT_MAP, TALK_TO, HEAR_RUMOR, ESCORT, HUNT_WITH,
        PAY, GIVE_ITEM, REACH_LEVEL, WAIT, PARTY_QUEST_CLEAR, KILL_BOSS
    }

    public enum QuestLength { SHORT, MEDIUM, LONG }

    public enum LootShare { OWN_DROPS, NONE, LEADER }

    public enum LeaveRule { LEADER_OFFLINE_20M, DISMISS, LEADER_DEATH_3X, CONTRACT_END }

    public record ItemStack(int itemId, int count) { }

    public record RoutineSlot(int fromHour, int toHour, String spot, String activity, boolean materialize) {
        public boolean covers(int hour) {
            return fromHour <= toHour ? hour >= fromHour && hour < toHour
                    : hour >= fromHour || hour < toHour;
        }
    }

    public record Relationship(String persona, String kind) { }

    public record LevelBand(int min, int max) {
        public int pick(int seed) {
            return min + Math.floorMod(seed, Math.max(1, max - min + 1));
        }
    }

    public record Persona(String id,
                          String name,
                          String title,
                          String job,
                          LevelBand level,
                          Strength strength,
                          String loadout,
                          String personality,
                          String dialogueStyle,
                          String homeTown,
                          List<RoutineSlot> routine,
                          boolean recruitable,
                          String contract,
                          List<String> questLines,
                          String arc,
                          List<Relationship> relationships,
                          List<String> rumors,
                          Map<String, List<String>> dialogue,
                          int benchToleranceDays,
                          int paceGap,
                          int maxLag,
                          List<Integer> giftItemIds,
                          int portraitNpcId,
                          String file) {
        public List<String> lines(String intent) {
            List<String> lines = dialogue.get(intent);
            return lines == null ? List.of() : lines;
        }
    }

    public record Requirements(int minLevel, int maxLevel, BondStage minBondStage, List<String> flags,
                               List<String> notFlags, List<String> questsCompleted) { }

    public record DropRule(List<Integer> mobIds, double chance) { }

    public record Step(String id,
                       StepType type,
                       List<String> say,
                       int itemId,
                       int count,
                       List<Integer> mobIds,
                       List<Integer> mapIds,
                       String to,
                       String persona,
                       String rumorId,
                       int meso,
                       int level,
                       int hours,
                       DropRule dropFrom,
                       String with,
                       ItemStack take,
                       String label) { }

    public record Reward(int meso, int exp, List<ItemStack> items, int bondPoints, List<String> flags,
                         int fame, List<String> worldFlags) { }

    public record ArcAdvance(String arc, String to) { }

    public record Quest(String id,
                        String persona,
                        String title,
                        QuestLength length,
                        boolean repeatable,
                        Requirements requires,
                        List<Step> steps,
                        Reward rewards,
                        ArcAdvance onComplete,
                        String file) {
        public Step step(int index) {
            return index >= 0 && index < steps.size() ? steps.get(index) : null;
        }
    }

    public record OfferWhen(BondStage bondStage, List<String> flags, String arc, String chapterAtLeast) { }

    public record KillProof(List<Integer> mobIds, int count) { }

    public record ContractItem(int itemId, int count, boolean keep) { }

    public record Price(long meso, List<ContractItem> items, KillProof proof) { }

    public record Terms(int durationHours, int maxLevelGap, LootShare lootShare, List<LeaveRule> leavesOn,
                        int cooldownHoursAfterLeave, long rejoinMeso, int ownDropsPercent) { }

    public record Contract(String id, OfferWhen offerWhen, Price price, Terms terms, String file) { }

    public record ChapterGate(List<String> flags, String rumorPerformed, int rumorTimes, int adventurerHoursTotal,
                              int distinctPlayers) { }

    public record Chapter(String id,
                          String title,
                          ChapterGate enterWhen,
                          Map<String, List<String>> dialogueOverrides,
                          List<RoutineSlot> routineOverride,
                          List<String> unlockQuestLines,
                          List<String> unlockRumors,
                          String unlockContract,
                          Boolean recruitable,
                          String loadout) { }

    public record Arc(String id, String persona, List<Chapter> chapters, String file) {
        public Chapter chapter(String chapterId) {
            for (Chapter chapter : chapters) {
                if (chapter.id().equals(chapterId)) {
                    return chapter;
                }
            }
            return null;
        }

        public int indexOf(String chapterId) {
            for (int index = 0; index < chapters.size(); index++) {
                if (chapters.get(index).id().equals(chapterId)) {
                    return index;
                }
            }
            return -1;
        }
    }

    public record RumorLine(String by, String text) { }

    public record ArcGate(String arc, String chapterAtLeast) { }

    public record Rumor(String id,
                        List<String> participants,
                        String town,
                        String spot,
                        List<Integer> serverHours,
                        int everyDays,
                        ArcGate requiresArc,
                        List<RumorLine> lines,
                        String setsFlagOnListeners,
                        String file) { }

    public record Loadout(String id, List<Integer> equipIds, List<ItemStack> consumables, Map<String, Integer> stats) { }

    public record Phrase(String canonical, List<String> aliases, List<String> prefixes, List<String> tails,
                         Map<String, String> slots, String status, String reply) { }
}
