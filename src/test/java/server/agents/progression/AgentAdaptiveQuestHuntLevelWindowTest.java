package server.agents.progression;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Quest hunts keep to the training-field level window: a hunt map's strongest monster may be at most
 * LEVEL_WINDOW_ABOVE levels above the Agent. When no reachable map fits, the Agent takes the map whose
 * strongest monster is the least above it, so the quest is never stranded.
 */
class AgentAdaptiveQuestHuntLevelWindowTest {
    private static final int EUREK_QUEST = 6000;
    private static final String RED_WHIP = "6000:collect:1302013";
    private static final int WRAITH = 4230102;
    private static final int ELLINIA_MAGIC_SHOP = 101010102;
    private static final int SUBWAY_LINE_1_AREA_1 = 103000101;
    private static final int SUBWAY_LINE_1_AREA_4 = 103000105;
    private static final int JR_WRAITH_SUBWAY = 103000200;
    private static final int SWAMP_OF_DESPAIR_III = 107000200;
    private static final Set<Integer> JR_NECKI_SWAMPS = Set.of(107000001, 107000000);
    private static final Set<Integer> WRAITH_SUBWAY_MAPS = Set.of(103000201, 103000200);

    @Test
    void eurekQuestAtLevel15TakesTheSoftestMapInsteadOfTheWraithSubway() {
        Map<Integer, Integer> strongest = strongestMonsterByMap(EUREK_QUEST, RED_WHIP);
        assertTrue(strongest.values().stream().allMatch(level -> level >= 40),
                "no Red Whip map is inside a level-15 window, so this is the widened fallback");

        AgentAdaptiveQuestHuntSelector.Selection selection =
                select(9601, 15, ELLINIA_MAGIC_SHOP, EUREK_QUEST, RED_WHIP, Set.of());

        assertNotEquals(SUBWAY_LINE_1_AREA_4, selection.map().mapId());
        assertFalse(selection.map().targetMobIds().contains(WRAITH));
        assertEquals(40, strongest.get(selection.map().mapId()));
    }

    @Test
    void eurekQuestKeepsToMapsInsideTheWindowWhenOneFits() {
        Map<Integer, Integer> strongest = strongestMonsterByMap(EUREK_QUEST, RED_WHIP);

        AgentAdaptiveQuestHuntSelector.Selection selection =
                select(9602, 45, ELLINIA_MAGIC_SHOP, EUREK_QUEST, RED_WHIP, Set.of());

        assertTrue(strongest.get(selection.map().mapId()) <= 45 + windowAbove(),
                () -> "map " + selection.map().mapId() + " is outside the level window");
    }

    @Test
    void eurekQuestMayHuntTheWraithSubwayOnceItIsInsideTheWindow() {
        AgentAdaptiveQuestHuntSelector.Selection selection =
                select(9603, 53, ELLINIA_MAGIC_SHOP, EUREK_QUEST, RED_WHIP, Set.of());

        assertEquals(SUBWAY_LINE_1_AREA_4, selection.map().mapId());
    }

    @Test
    void lukeQuestDoesNotFallBackToTheWraithSubwayWhenBothJrNeckiSwampsFailed() {
        assertSwampFallback(9604, 2018, "2018:collect:4000034", 20);
    }

    @Test
    void faymusQuestDoesNotFallBackToTheWraithSubwayWhenBothJrNeckiSwampsFailed() {
        assertSwampFallback(9605, 2032, "2032:collect:4000034", 25);
    }

    @Test
    void aLeaseFurtherAboveTheWindowGivesWayWhenASofterMapReopens() {
        AgentAdaptiveQuestHuntSelector selector = newSelector();
        Set<Integer> everySwampFailed = Set.of(107000001, 107000000, 107000100, 107000200);

        AgentAdaptiveQuestHuntSelector.Selection leased = select(selector, 9606, 25,
                SUBWAY_LINE_1_AREA_1, 2032, "2032:collect:4000034", everySwampFailed, 100L);
        AgentAdaptiveQuestHuntSelector.Selection reopened = select(selector, 9606, 25,
                SUBWAY_LINE_1_AREA_1, 2032, "2032:collect:4000034", JR_NECKI_SWAMPS, 200L);

        assertEquals(JR_WRAITH_SUBWAY, leased.map().mapId());
        assertEquals(SWAMP_OF_DESPAIR_III, reopened.map().mapId());
    }

    private static void assertSwampFallback(int agentId, int questId, String objectiveId, int level) {
        Map<Integer, Integer> strongest = strongestMonsterByMap(questId, objectiveId);
        int startMapId = AgentVictoriaQuestRuntimeCatalogRepository.defaultRepository()
                .find(questId).orElseThrow().startMapIds().getFirst();

        for (int originMapId : List.of(startMapId, SUBWAY_LINE_1_AREA_1)) {
            AgentAdaptiveQuestHuntSelector.Selection selection =
                    select(agentId, level, originMapId, questId, objectiveId, JR_NECKI_SWAMPS);

            assertFalse(WRAITH_SUBWAY_MAPS.contains(selection.map().mapId()),
                    () -> "quest " + questId + " from " + originMapId
                            + " fell back to subway map " + selection.map().mapId());
            assertEquals(32, strongest.get(selection.map().mapId()),
                    () -> "quest " + questId + " from " + originMapId + " took map "
                            + selection.map().mapId() + ", not a Swamp of Despair map");
        }
    }

    private static AgentAdaptiveQuestHuntSelector.Selection select(
            int agentId,
            int level,
            int mapId,
            int questId,
            String objectiveId,
            Set<Integer> failedMaps) {
        return select(newSelector(), agentId, level, mapId, questId, objectiveId, failedMaps, 100L);
    }

    private static AgentAdaptiveQuestHuntSelector newSelector() {
        return new AgentAdaptiveQuestHuntSelector(
                AgentVictoriaQuestHuntPolicyRepository.defaultRepository(),
                AgentVictoriaQuestHuntIndexRepository.defaultRepository());
    }

    private static AgentAdaptiveQuestHuntSelector.Selection select(
            AgentAdaptiveQuestHuntSelector selector,
            int agentId,
            int level,
            int mapId,
            int questId,
            String objectiveId,
            Set<Integer> failedMaps,
            long nowMs) {
        AgentVictoriaQuestRuntimeCatalog.HuntingObjective objective =
                AgentVictoriaQuestRuntimeCatalogRepository.defaultRepository()
                        .find(questId).orElseThrow().huntingObjectives().stream()
                        .filter(candidate -> candidate.objectiveId().equals(objectiveId))
                        .findFirst().orElseThrow();
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(agentId);
        when(agent.getName()).thenReturn("LevelWindow" + agentId);
        when(agent.getJob()).thenReturn(Job.THIEF);
        when(agent.getLevel()).thenReturn(level);
        when(agent.getMapId()).thenReturn(mapId);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);

        return selector.select(new AgentHuntSelectionRequest(
                        entry, agent, "scheduler:" + questId + ":" + objectiveId,
                        List.of(new AgentHuntSelectionRequest.ObjectiveDemand(
                                questId, objective.objectiveId(), objective.type(),
                                objective.targetId(), objective.requiredCount(), 0,
                                Set.copyOf(objective.sourceMobIds()))),
                        objective.huntMaps(), failedMaps, false,
                        AgentHuntSelectionRequest.Reason.NORMAL, nowMs))
                .orElseThrow();
    }

    private static Map<Integer, Integer> strongestMonsterByMap(int questId, String objectiveId) {
        return AgentVictoriaQuestHuntIndexRepository.defaultRepository()
                .findObjective(questId, objectiveId).orElseThrow().candidates().stream()
                .collect(Collectors.toMap(
                        AgentVictoriaQuestHuntIndex.Candidate::mapId,
                        AgentVictoriaQuestHuntIndex.Candidate::maxMobLevel,
                        Math::max));
    }

    private static int windowAbove() {
        return config.AgentTuning.intValue(
                "server.agents.progression.AgentAdaptiveQuestHuntSelector.LEVEL_WINDOW_ABOVE");
    }
}
