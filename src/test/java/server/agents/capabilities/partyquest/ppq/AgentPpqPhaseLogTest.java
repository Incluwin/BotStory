package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPQ Step 1 (plan 4.5): every phase change and every stage the leader steps onto is logged ('PPQ phase:', 'PPQ
 * stage:'), in every mode, and neither marks progress yet.
 */
class AgentPpqPhaseLogTest {
    @Test
    void everyPpqMapNamesItsStage() {
        assertEquals(AgentPpqStage.LOBBY, AgentPpqDefinition.stageOf(251_010_404));
        assertEquals(AgentPpqStage.STAGE_ONE, AgentPpqDefinition.stageOf(925_100_000));
        assertEquals(AgentPpqStage.MEDALS, AgentPpqDefinition.stageOf(925_100_100));
        assertEquals(AgentPpqStage.DECK_ONE, AgentPpqDefinition.stageOf(925_100_200));
        assertEquals(AgentPpqStage.CHEST_ONE, AgentPpqDefinition.stageOf(925_100_201));
        assertEquals(AgentPpqStage.SIDE_ONE, AgentPpqDefinition.stageOf(925_100_202));
        assertEquals(AgentPpqStage.DECK_TWO, AgentPpqDefinition.stageOf(925_100_300));
        assertEquals(AgentPpqStage.CHEST_TWO, AgentPpqDefinition.stageOf(925_100_301));
        assertEquals(AgentPpqStage.SIDE_TWO, AgentPpqDefinition.stageOf(925_100_302));
        assertEquals(AgentPpqStage.DOORS, AgentPpqDefinition.stageOf(925_100_400));
        assertEquals(AgentPpqStage.BOSS, AgentPpqDefinition.stageOf(925_100_500));
        assertEquals(AgentPpqStage.CLEAR, AgentPpqDefinition.stageOf(925_100_600));
        assertEquals(AgentPpqStage.OUTSIDE, AgentPpqDefinition.stageOf(925_100_700));
    }

    @Test
    void theLeadersStageIsLoggedAndMarksNoProgressYet() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP);
             PpqLogLines lines = new PpqLogLines(AgentPpqCoordinator.class)) {
            run.properties.put("stage2", "1");
            long before = run.session.lastProgressAtMs();

            run.tick(5_000L);

            List<String> stage = lines.startingWith("PPQ stage:");
            assertEquals(1, stage.size(), stage.toString());
            assertEquals("PPQ stage: session=" + run.session.sessionId()
                    + " LOBBY->MEDALS stageMs=4000 runMs=4000 medalWave=1 companionRun=true", stage.getFirst());
            assertEquals(before, run.session.lastProgressAtMs(), "a stage change is not progress yet");
            run.tick(6_000L);
            assertEquals(1, lines.startingWith("PPQ stage:").size(), "the same stage is said once");
        }
    }

    @Test
    void aPhaseChangeIsLoggedInEveryMode() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP);
             PpqLogLines lines = new PpqLogLines(AgentPpqCoordinator.class)) {
            run.tick(3_000L);

            List<String> phase = lines.startingWith("PPQ phase:");
            assertTrue(phase.contains("PPQ phase: session=" + run.session.sessionId()
                    + " PREPARING->CLEARING phaseMs=2000 runMs=2000 companionRun=false"), phase.toString());
        }
    }
}
