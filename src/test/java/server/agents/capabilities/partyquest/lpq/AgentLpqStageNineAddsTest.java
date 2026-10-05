package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LPQ base fix 4: stage 9 fights Alishar's summons. Alishar (Mob.wz 9300012) casts MobSkill 200 at levels 29, 30 and
 * 31 (five 9300015, 9300016 and 9300017 each); the boss grind allowed only Alishar, which strips the adds even from
 * area attacks and keeps an add that hits an Agent from becoming its target.
 */
class AgentLpqStageNineAddsTest {
    @Test
    void theSummonIdsAreTheOnesAlisharsMobSkillsSummon() {
        Data alishar = DataProviderFactory.getDataProvider(WZFiles.MOB).getData("9300012.img");
        Set<Integer> summonLevels = new LinkedHashSet<>();
        for (Data skill : alishar.getChildByPath("info/skill").getChildren()) {
            if (DataTool.getInt("skill", skill, 0) == 200) summonLevels.add(DataTool.getInt("level", skill, 0));
        }
        assertEquals(Set.of(29, 30, 31), summonLevels);

        Data mobSkill = DataProviderFactory.getDataProvider(WZFiles.SKILL).getData("MobSkill.img");
        Set<Integer> summoned = new LinkedHashSet<>();
        for (int level : summonLevels) {
            Data entry = mobSkill.getChildByPath("200/level/" + level);
            for (int index = 0; entry.getChildByPath(String.valueOf(index)) != null; index++) {
                summoned.add(DataTool.getInt(String.valueOf(index), entry, 0));
            }
        }
        assertEquals(Set.of(9_300_015, 9_300_016, 9_300_017), summoned);
        assertEquals(summoned, AgentLpqDefinition.ALISHAR_SUMMONS);
    }

    @Test
    void theBossGrindAllowsTheSummons() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        int start = source.indexOf("    private static void boss(");
        String boss = source.substring(start, source.indexOf("\n    }\n", start)).replaceAll("\\s+", " ");

        assertFalse(boss.contains("ACTIONS.grind(entry, Set.of(AgentLpqDefinition.ALISHAR));"),
                "no grind on Alishar alone remains");
        assertTrue(List.of(boss.split("ACTIONS\\.grind")).stream().skip(1)
                .anyMatch(call -> call.startsWith("(entry, Set.of(AgentLpqDefinition.ALISHAR), "
                        + "AgentLpqDefinition.ALISHAR_SUMMONS)")), "Alishar preferred, his summons allowed");
    }
}
