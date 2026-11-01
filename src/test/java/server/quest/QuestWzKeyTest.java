package server.quest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Check.img / Act.img keys the loader used to drop silently: dayByDay (63 quests; the code matched "daybyday"),
 * endmeso (54 quests' completion fee), skill (45 rows) and Act.img's quest (18 actions).
 */
class QuestWzKeyTest {
    @Test
    void checkImgKeysAsTheDataSpellsThem() {
        assertEquals(QuestRequirementType.DAY_BY_DAY, QuestRequirementType.getByWZName("dayByDay"));
        assertEquals(QuestRequirementType.MESO, QuestRequirementType.getByWZName("endmeso"));
        assertEquals(QuestRequirementType.SKILL, QuestRequirementType.getByWZName("skill"));
    }

    @Test
    void actImgQuestKey() {
        assertEquals(QuestActionType.QUEST, QuestActionType.getByWZName("quest"));
    }
}
