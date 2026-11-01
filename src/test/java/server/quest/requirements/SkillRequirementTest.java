package server.quest.requirements;

import client.Character;
import org.junit.jupiter.api.Test;
import provider.Data;
import server.quest.Quest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Check.img's skill rows: acquire=1 needs the skill learned; rows without acquire are not checked. */
class SkillRequirementTest {
    private static final int MONSTER_RIDER = 10001004;
    private static final int MAPLE_WARRIOR = 1121000;

    private static Data row(int skillId, Integer acquire) {
        Data row = mock(Data.class);
        Data id = mock(Data.class);
        when(id.getData()).thenReturn(skillId);
        when(row.getChildByPath("id")).thenReturn(id);
        if (acquire != null) {
            Data acq = mock(Data.class);
            when(acq.getData()).thenReturn(acquire);
            when(row.getChildByPath("acquire")).thenReturn(acq);
        }
        return row;
    }

    private static SkillRequirement requirement(Data... rows) {
        Data root = mock(Data.class);
        when(root.getChildren()).thenReturn(List.of(rows));
        return new SkillRequirement(mock(Quest.class), root);
    }

    @Test
    void anAcquiredSkillMustBeLearned() {
        SkillRequirement requirement = requirement(row(MONSTER_RIDER, 1));
        Character without = mock(Character.class);
        Character with = mock(Character.class);
        when(with.getSkillLevel(MONSTER_RIDER)).thenReturn(1);

        assertFalse(requirement.check(without, null));
        assertTrue(requirement.check(with, null));
    }

    @Test
    void rowsWithoutAcquireAreNotChecked() {
        SkillRequirement requirement = requirement(row(MAPLE_WARRIOR, null));

        assertEquals(List.of(), requirement.getAcquiredSkills());
        assertTrue(requirement.check(mock(Character.class), null));
    }
}
