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
    void theBossOrdersAllowTheSummons() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        int start = source.indexOf("    private static void boss(");
        String bossFlow = source.substring(start, source.indexOf("    private static void assignStageNineRoles", start))
                .replaceAll("\\s+", " ");

        assertFalse(bossFlow.contains("ACTIONS.grind(entry, Set.of(AgentLpqDefinition.ALISHAR));"),
                "no grind on Alishar alone remains");
        // Since LPQ base fix 5 every Alishar order takes its mobs from the stage-9 tiers, each allowing the summons.
        assertTrue(bossFlow.contains("order.targets().preferred(), order.targets().fallback()"));
        String order = AgentLpqStageNineStationTest.method(Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqStageNineBossPolicy.java"))
                .replace("\r\n", "\n"), "    static Order order(").replaceAll("\\s+", " ");
        assertTrue(order.contains("targets(false, false)") && order.contains("targets(true, "), order);
        for (boolean shooter : new boolean[]{true, false}) {
            for (boolean threat : new boolean[]{true, false}) {
                AgentLpqStageNineBossPolicy.Targets targets = AgentLpqStageNineBossPolicy.targets(shooter, threat);
                assertTrue(targets.preferred().containsAll(AgentLpqDefinition.ALISHAR_SUMMONS)
                        || targets.fallback().containsAll(AgentLpqDefinition.ALISHAR_SUMMONS));
            }
        }
    }
}
