package server.living.resident;

import client.Character;
import client.QuestStatus;
import server.living.config.Content.Persona;
import server.quest.Quest;
import server.quest.QuestTime;

import java.util.Map;

/**
 * Stock quests a Resident's home town lies behind. Mushroom Castle's only door, the scripted portal TD_MC_first in
 * Henesys's Empty House (scripts/portal/TD_MC_first.js), lets a character through only with quest 2260 completed (or
 * one of 2300-2310 under way), and the agent route graph routes a bot through it only when the bot passes that check.
 * A Resident who lives there has been through the door, so its character counts the quest as done; otherwise a
 * Mushroom Castle companion could walk out with its owner and never be routed home again (docs/REGIONAL_RESIDENTS_PLAN.md
 * section 10.6). Marked completed without the quest's actions: no rewards, no packets beyond the quest record.
 */
final class ResidentHomeGates {
    /** home town profile id -> the stock quest its door checks. */
    static final Map<String, Integer> GATE_QUESTS = Map.of("mushroom-castle", 2260);

    private ResidentHomeGates() {
    }

    /** Completes the home town's gate quest on {@code agent} if it is not already; true when it changed anything. */
    static boolean open(Character agent, Persona persona) {
        Integer questId = GATE_QUESTS.get(persona.homeTown());
        if (questId == null || agent.getQuestStatus(questId) == QuestStatus.Status.COMPLETED.getId()) {
            return false;
        }
        QuestStatus done = new QuestStatus(Quest.getInstance(questId), QuestStatus.Status.COMPLETED, 0);
        done.setCompletionTime(QuestTime.now());
        agent.updateQuestStatus(done);
        return true;
    }
}
