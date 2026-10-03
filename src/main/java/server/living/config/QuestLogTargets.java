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
 * Quest.wz {@code Check.img/<id>/1} by MapleEdit (QuestLogExportMain) so the Quest Helper takes the quest and the detail
 * pane counts it. The client lists the targets of every step at once (the WZ entry is static), reads each monster's
 * kills as three digits of the quest's progress string in this order, and counts items from the inventory itself.
 *
 * <p>Each kill step (KILL_MOBS, KILL_BOSS, HUNT_WITH) tracks its first monster with its count (the client counts each
 * listed monster separately; the ledger counts any of a step's monsters toward one total); each COLLECT_ITEM step its
 * item. A monster or item two steps share is listed once, with the larger count.
 */
public final class QuestLogTargets {
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

    /** The items to collect. */
    public static List<Target> items(Quest quest) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Step step : quest.steps()) {
            if (step.type() == StepType.COLLECT_ITEM && step.itemId() > 0) {
                counts.merge(step.itemId(), Math.max(1, step.count()), Math::max);
            }
        }
        return targets(counts);
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
