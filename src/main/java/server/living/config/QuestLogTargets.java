package server.living.config;

import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the client's quest log tracks for a living quest: the monsters to defeat and the items to collect, written into
 * Quest.wz {@code Check.img/<id>/1} by tools/wz-patcher or MapleEdit (from QuestLogExportMain) so the Quest Helper takes
 * the quest and counts it. The client lists the targets of every step at once (the WZ entry is static), reads each monster's
 * kills as three digits of the quest's progress string in this order, and counts items from the inventory itself.
 *
 * <p>Each kill step (KILL_MOBS, KILL_BOSS, HUNT_WITH) tracks its first monster with its count (the client counts each
 * listed monster separately; the ledger counts any of a step's monsters toward one total); each COLLECT_ITEM and
 * GIVE_ITEM step its item. A monster or item two steps share is listed once, with the larger count.
 *
 * <p>The detail text shows the counts live with the codes stock quests use: {@code #a<quest id><slot>#} prints the
 * kills of the slot-th listed monster (one digit, from 1) and {@code #c<item id>#} how many of the item the inventory
 * holds; {@link #mobSlot} gives the slot.
 */
public final class QuestLogTargets {
    /** Slots the detail text's {@code #a} code can name: it has one digit for the slot. */
    public static final int CODED_SLOTS = 9;

    /** A monster or item and how many the quest asks for. */
    public record Target(int id, int count) { }

    private QuestLogTargets() {
    }

    /** The monsters, in the order the client reads their kill counts. */
    public static List<Target> mobs(Quest quest) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Step step : quest.steps()) {
            if (isKill(step)) {
                counts.merge(step.mobIds().get(0), Math.max(1, step.count()), Math::max);
            }
        }
        return targets(counts);
    }

    /** The items to collect or hand over. */
    public static List<Target> items(Quest quest) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Step step : quest.steps()) {
            if ((step.type() == StepType.COLLECT_ITEM || step.type() == StepType.GIVE_ITEM) && step.itemId() > 0) {
                counts.merge(step.itemId(), Math.max(1, step.count()), Math::max);
            }
        }
        return targets(counts);
    }

    /**
     * The 1-based place of a kill step's tracked monster in {@link #mobs} (the slot its {@code #a} code reads; a monster
     * two steps share has one slot), or 0 for any other step.
     */
    public static int mobSlot(Quest quest, Step step) {
        if (!isKill(step)) {
            return 0;
        }
        List<Target> mobs = mobs(quest);
        for (int i = 0; i < mobs.size(); i++) {
            if (mobs.get(i).id() == step.mobIds().get(0)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * The kills to show per tracked monster (same order as {@link #mobs}) for a ledger state: a step before the current
     * one counts in full, the current one its progress, a later one nothing; a shared monster shows its best step.
     */
    public static Map<Integer, Integer> kills(Quest quest, int stepIndex, int progress) {
        Map<Integer, Integer> kills = new LinkedHashMap<>();
        for (Target target : mobs(quest)) {
            kills.put(target.id(), 0);
        }
        for (int i = 0; i < quest.steps().size(); i++) {
            Step step = quest.step(i);
            if (!isKill(step)) {
                continue;
            }
            int count = Math.max(1, step.count());
            int shown = i < stepIndex ? count : i == stepIndex ? Math.min(progress, count) : 0;
            kills.merge(step.mobIds().get(0), shown, Math::max);
        }
        return kills;
    }

    private static boolean isKill(Step step) {
        return (step.type() == StepType.KILL_MOBS || step.type() == StepType.KILL_BOSS || step.type() == StepType.HUNT_WITH)
                && step.mobIds() != null && !step.mobIds().isEmpty();
    }

    private static List<Target> targets(Map<Integer, Integer> counts) {
        List<Target> out = new ArrayList<>();
        counts.forEach((id, count) -> out.add(new Target(id, count)));
        return out;
    }
}
