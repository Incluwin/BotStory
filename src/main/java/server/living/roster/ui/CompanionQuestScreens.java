package server.living.roster.ui;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.LivingContent;
import server.living.quest.QuestLedger;
import server.living.resident.LivingTalk;
import server.living.resident.LivingTalk.Option;
import server.living.store.LivingStore.QuestState;

import java.util.ArrayList;
import java.util.List;

import static server.living.roster.ui.CompanionMenus.portrait;

/**
 * A companion's own Resident quests on its card (docs/BOT_CONCEPT_PLAN.md F6, design point 1). Its owner's lines never
 * reach a Resident they own (ResidentDialogue.target hands them to the companion runtime) and a double-click opens the
 * card, so before this a recruited Resident could neither offer its quests to its owner nor take their hand-ins: Fenna's
 * friendship quests end in "DELIVER to fenna". The card lists what the Resident's own window lists: each quest it offers
 * the owner now, each of its quests in progress, and each quest that sends the owner to talk to it (TALK_TO). Picking one
 * offers it, or hands in what is ready ({@link QuestLedger#turnIn}), or says the step. Not gated: it fixes live content.
 */
final class CompanionQuestScreens {
    static final String LABEL = "Your quests...";

    private CompanionQuestScreens() {
    }

    /** True when the companion has a quest to offer its owner, one in progress, or a TALK_TO step addressed to it. */
    static boolean any(LivingWorld world, Character owner, Persona persona) {
        QuestLedger quests = world.quests();
        if (quests == null || LivingContent.isHire(persona.id())) {
            return false;
        }
        return !quests.available(owner, persona).isEmpty() || !inProgress(world, owner, persona).isEmpty();
    }

    /** The menu, with Back to {@code back} (the card). */
    static void show(LivingWorld world, Character owner, Persona persona, Runnable back) {
        QuestLedger quests = world.quests();
        List<Option> options = new ArrayList<>();
        for (Quest quest : quests.available(owner, persona)) {
            options.add(new Option("#b" + quest.title() + "#k", () -> quests.offer(owner, persona, quest)));
        }
        for (QuestState state : inProgress(world, owner, persona)) {
            Quest quest = world.content().quest(state.questId());
            options.add(new Option("#d" + quest.title() + " (in progress)#k", () -> {
                if (!quests.turnIn(owner, persona)) {
                    LivingTalk.open(owner, portrait(persona))
                            .ok(quest.title() + ": " + quests.describe(quest.step(state.stepIndex()), state))
                            .back(back)
                            .show();
                }
            }));
        }
        LivingTalk.open(owner, portrait(persona)).menu("about the work?\r\n", options).back(back).show();
    }

    /** The owner's active quests this companion gave, or whose current step is to talk to it. */
    private static List<QuestState> inProgress(LivingWorld world, Character owner, Persona persona) {
        List<QuestState> out = new ArrayList<>();
        for (QuestState state : world.quests().active(owner)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            boolean sentHere = step != null && step.type() == StepType.TALK_TO && persona.id().equals(step.persona());
            if (quest != null && (quest.persona().equals(persona.id()) || sentHere)) {
                out.add(state);
            }
        }
        return out;
    }
}
