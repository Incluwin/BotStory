package server.quest.requirements;

import client.Character;
import provider.Data;
import provider.DataTool;
import server.quest.Quest;
import server.quest.QuestRequirementType;

import java.util.ArrayList;
import java.util.List;

/**
 * Check.img's skill list. Only rows with acquire=1 are checked: the player must have learned that skill (mount
 * quests need Monster Rider, the "Using Power Strike" tutorials need the skill). Rows without acquire are left
 * alone: they list every job's copy of a skill (Maple Warrior, Hero's Will, a beginner skill a quest grants), and
 * the data does not say whether a row means "has it" or "doesn't yet", so checking them could lock a quest out.
 */
public class SkillRequirement extends AbstractQuestRequirement {
    private final List<Integer> acquiredSkills = new ArrayList<>();

    public SkillRequirement(Quest quest, Data data) {
        super(QuestRequirementType.SKILL);
        processData(data);
    }

    @Override
    public void processData(Data data) {
        for (Data entry : data.getChildren()) {
            if (DataTool.getInt(entry.getChildByPath("acquire"), 0) > 0) {
                acquiredSkills.add(DataTool.getInt(entry.getChildByPath("id")));
            }
        }
    }

    @Override
    public boolean check(Character chr, Integer npcid) {
        for (int skillId : acquiredSkills) {
            if (chr.getSkillLevel(skillId) <= 0) {
                return false;
            }
        }
        return true;
    }

    public List<Integer> getAcquiredSkills() {
        return acquiredSkills;
    }
}
