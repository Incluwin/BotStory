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
                          Look look,
                          String file,
                          Spouse spouse) {
        public Persona(String id, String name, String title, String job, LevelBand level, Strength strength,
                       String loadout, String personality, String dialogueStyle, String homeTown,
                       List<RoutineSlot> routine, boolean recruitable, String contract, List<String> questLines,
                       String arc, List<Relationship> relationships, List<String> rumors,
                       Map<String, List<String>> dialogue, int benchToleranceDays, int paceGap, int maxLag,
                       List<Integer> giftItemIds, int portraitNpcId, Look look, String file) {
            this(id, name, title, job, level, strength, loadout, personality, dialogueStyle, homeTown, routine,
                    recruitable, contract, questLines, arc, relationships, rumors, dialogue, benchToleranceDays, paceGap,
                    maxLag, giftItemIds, portraitNpcId, look, file, null);
        }

        public List<String> lines(String intent) {
            List<String> lines = dialogue.get(intent);
            return lines == null ? List.of() : lines;
        }
    }

    /**
     * A lore couple (docs/BOT_LORE_COUPLES_PLAN.md): the persona is married in its story. {@code persona} names another
     * persona whose own {@code spouse} names this one back; otherwise {@code name} and {@code gender} describe a spouse
     * who is no Resident, born as a citizen of the persona's home region.
     */
    public record Spouse(String persona, String name, String gender) {
        public boolean citizen() {
            return persona == null;
        }
    }

    /** Appearance overrides; unset fields (null gender, skin -1, face/hair 0) are picked per persona id. */
    public record Look(String gender, int skin, int face, int hair) {
        public static final Look NONE = new Look(null, -1, 0, 0);
    }

    /** {@code minFriendship}: friendship level 0-10 with the giver (server.living.bond.Friendship); 0 = none. */
    public record Requirements(int minLevel, int maxLevel, BondStage minBondStage, List<String> flags,
                               List<String> notFlags, List<String> questsCompleted, int minFriendship) {
        public Requirements(int minLevel, int maxLevel, BondStage minBondStage, List<String> flags,
                            List<String> notFlags, List<String> questsCompleted) {
            this(minLevel, maxLevel, minBondStage, flags, notFlags, questsCompleted, 0);
        }
    }

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

    /** {@code gear}: equipment the giver scrolls for the player, scaled by friendship (server.living.quest.FriendForge). */
    public record Reward(int meso, int exp, List<ItemStack> items, int bondPoints, List<String> flags,
                         int fame, List<String> worldFlags, List<GearReward> gear) {
        public Reward(int meso, int exp, List<ItemStack> items, int bondPoints, List<String> flags,
                      int fame, List<String> worldFlags) {
            this(meso, exp, items, bondPoints, flags, fame, worldFlags, List.of());
        }
    }

    /**
     * One forged item. Which piece: with {@code owned} the giver's own ({@code itemId} in YAML, the only pool entry),
     * given whatever the player's class; otherwise the first entry of {@code pool} made for the player's job (a weapon
     * of the type they wield first), so one entry can hold a piece for every class. How it is scrolled: to
     * {@code scrolls} with every scroll taking, or, with no recipe, by friendship.
     */
    public record GearReward(List<Integer> pool, boolean owned, List<ScrollRecipe> scrolls) {
        public GearReward(List<Integer> pool) {
            this(pool, false, List.of());
        }
    }

    /** {@code count} scrolls of the {@code success}% grade (10, 60 or 100); {@link #REST} fills every slot left. */
    public record ScrollRecipe(int success, int count) {
        public static final int REST = -1;
    }

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

    /**
     * A contract's terms. {@code lootShare} is null when the contract does not author it: such a companion's loot is not
     * split (LootTerms), as before the terms were enforced. {@code ownDropsPercent} is what an OWN_DROPS companion keeps
     * of its own kills' drops, as written; -1 when the contract does not write it ({@link #keptPercent} then reads the
     * authored default, 10).
     */
    public record Terms(int durationHours, int maxLevelGap, LootShare lootShare, List<LeaveRule> leavesOn,
                        int cooldownHoursAfterLeave, long rejoinMeso, int ownDropsPercent) {
        /** The authored default of ownDropsPercent for OWN_DROPS (docs/LIVING_RPG_PLAN.md, 11.5). */
        public static final int OWN_DROPS_DEFAULT_PERCENT = 10;

        /** True when the contract authors its loot terms, so its companion's loot is split by them. */
        public boolean splitsLoot() {
            return lootShare != null;
        }

        /** The share of its own kills' drops the companion keeps, 0-100 (none for NONE and LEADER, or no terms). */
        public int keptPercent() {
            if (lootShare != LootShare.OWN_DROPS) {
                return 0;
            }
            return ownDropsPercent < 0 ? OWN_DROPS_DEFAULT_PERCENT : Math.min(100, ownDropsPercent);
        }
    }

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

    /**
     * {@code outfit}: slot (hat, top, bottom, shoes, gloves, cape, weapon) -> pool of item ids a wearer picks one from
     * (0 = nothing in that slot); see ResidentWardrobe.
     */
    public record Loadout(String id, List<Integer> equipIds, List<ItemStack> consumables, Map<String, Integer> stats,
                          Map<String, List<Integer>> outfit) { }

    public record Phrase(String canonical, List<String> aliases, List<String> prefixes, List<String> tails,
                         Map<String, String> slots, String status, String reply) { }
}
