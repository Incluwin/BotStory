package server.quest.requirements;

import client.Character;
import client.QuestStatus;
import provider.Data;
import server.quest.Quest;
import server.quest.QuestRequirementType;
import server.quest.QuestTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Check.img's dayByDay: the quest can be done again once per calendar day (server time), the same way the Family
 * entitlements reset.
 */
public class DayByDayRequirement extends AbstractQuestRequirement {
    private final int questID;

    public DayByDayRequirement(Quest quest, Data data) {
        super(QuestRequirementType.DAY_BY_DAY);
        questID = quest.getId();
        processData(data);
    }

    @Override
    public void processData(Data data) {
        // the value is always 1: the key alone marks a daily quest
    }

    @Override
    public boolean check(Character chr, Integer npcid) {
        QuestStatus status = chr.getQuest(Quest.getInstance(questID));
        if (!status.getStatus().equals(QuestStatus.Status.COMPLETED)
                || availableAgain(status.getCompletionTime(), QuestTime.now(), ZoneId.systemDefault())) {
            return true;
        }
        chr.message("This quest will become available again tomorrow.");
        return false;
    }

    static boolean availableAgain(long completedAt, long now, ZoneId zone) {
        LocalDate completedDay = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return today.isAfter(completedDay);
    }
}
