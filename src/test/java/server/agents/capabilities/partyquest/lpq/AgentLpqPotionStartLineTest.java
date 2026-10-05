package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P6d LPQ step 1 (C6): when a companion run's party is inside and stage 1 begins, its companions' potions are logged
 * ('LPQ potions: session= start={...}') and kept for the end line. A stock run logs nothing; in every mode stage 1
 * begins as it always did, even if the potions cannot be read.
 */
class AgentLpqPotionStartLineTest {
    static {
        // Initialized with the real gateways before any test mocks them (the coordinator keeps them in fields).
        AgentLpqCoordinator.stageFivePassRestoreCount(0, 0);
    }

    private final LpqRunFixture run = new LpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character cas = run.companion(6, "Cas");

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aCompanionRunLogsAndKeepsItsCompanionsPotionsAsStageOneBegins() {
        when(run.actions.hpPotionCount(mira)).thenReturn(40);
        when(run.actions.mpPotionCount(mira)).thenReturn(50);
        when(run.actions.hpPotionCount(cas)).thenReturn(7);
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true,
                AgentLpqSession.Phase.ENTERING, ben, mira, kes, pel, hal, cas);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            AgentLpqCoordinator.beginStageOne(session, 4_000L);

            String potions = "{Mira:40/50, Kes:0/0, Pel:0/0, Hal:0/0, Cas:7/0}";
            assertEquals(List.of("LPQ potions: session=" + session.sessionId() + " start=" + potions),
                    log.startingWith("LPQ potions: "));
            assertEquals(potions, session.potionsAtEntry());
            assertEquals(AgentLpqSession.Phase.STAGE_1, session.phase());
            assertEquals(4_000L, session.firstEnteredAtMs(AgentLpqSession.Phase.STAGE_1));
        }
    }

    @Test
    void aStockRunLogsNothingAndBeginsStageOne() {
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, false,
                AgentLpqSession.Phase.ENTERING, ben, mira, kes, pel, hal, cas);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            AgentLpqCoordinator.beginStageOne(session, 4_000L);

            assertEquals(List.of(), log.startingWith("LPQ potions"));
            assertNull(session.potionsAtEntry());
            assertEquals(AgentLpqSession.Phase.STAGE_1, session.phase());
        }
    }

    @Test
    void potionsThatCannotBeReadStillLetStageOneBegin() {
        when(run.actions.hpPotionCount(any())).thenThrow(new IllegalStateException("inventory unavailable"));
        AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true,
                AgentLpqSession.Phase.ENTERING, ben, mira, kes, pel, hal, cas);
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            AgentLpqCoordinator.beginStageOne(session, 4_000L);

            assertEquals(List.of("LPQ potions failed: session=" + session.sessionId()),
                    log.startingWith("LPQ potions"));
            assertNull(session.potionsAtEntry());
            assertEquals(AgentLpqSession.Phase.STAGE_1, session.phase());
        }
    }

    @Test
    void enterBeginsStageOneThroughItWhereItTransitionedBefore() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"));
        int start = source.indexOf("private static void enter(");
        int end = source.indexOf("private static void collectionStage(", start);
        String enter = source.substring(start, end);
        assertTrue(enter.contains("if (allEntered) beginStageOne(session, nowMs);"), enter);
        Matcher stageOne = Pattern.compile("transition\\(\\s*AgentLpqSession\\.Phase\\.STAGE_1\\b").matcher(source);
        int transitions = 0;
        while (stageOne.find()) transitions++;
        assertEquals(1, transitions, "only beginStageOne transitions to STAGE_1");
        int begin = source.indexOf("static void beginStageOne(");
        assertTrue(begin > 0 && source.indexOf("session.transition(AgentLpqSession.Phase.STAGE_1, nowMs);", begin)
                > begin, "beginStageOne transitions");
    }
}
