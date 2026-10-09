package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P6h LMPQ step 1, C5: 'LMPQ potions: session= start=' as farming begins, for companion runs only. The first caller
 * wins (both FARMING transitions go through beginFarming), and a count that throws never keeps farming from
 * beginning.
 */
class AgentLmpqPotionStartLineTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentLmpqSession preparing(boolean companionRun) {
        return run.session(AgentLmpqSession.Mode.HUMAN_LEADER, companionRun, AgentLmpqSession.Phase.PREPARING,
                ben, mira, kes, pel);
    }

    @Test
    void aCompanionRunLogsItsPotionsOnceAsFarmingBegins() {
        AgentLmpqSession session = preparing(true);
        when(run.actions.hpPotionCount(mira)).thenReturn(40);
        when(run.actions.mpPotionCount(mira)).thenReturn(50);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.beginFarming(session, 5_000L);
            when(run.actions.hpPotionCount(mira)).thenReturn(39);
            AgentLmpqCoordinator.beginFarming(session, 5_100L);

            String start = "{Mira:40/50, Kes:0/0, Pel:0/0}";
            assertEquals(List.of("LMPQ potions: session=" + session.sessionId() + " start=" + start),
                    lines.startingWith("LMPQ potions:"));
            assertEquals(start, session.potionsAtEntry());
            assertEquals(AgentLmpqSession.Phase.FARMING, session.phase());
        }
    }

    @Test
    void aStockRunLogsNoPotions() {
        AgentLmpqSession session = preparing(false);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.beginFarming(session, 5_000L);

            assertEquals(List.of(), lines.startingWith("LMPQ potions:"));
            assertNull(session.potionsAtEntry());
            assertEquals(AgentLmpqSession.Phase.FARMING, session.phase());
        }
    }

    @Test
    void aCountThatThrowsStillBeginsFarming() {
        AgentLmpqSession session = preparing(true);
        when(run.actions.hpPotionCount(any())).thenThrow(new IllegalStateException("inventory unavailable"));

        AgentLmpqCoordinator.beginFarming(session, 5_000L);

        assertEquals(AgentLmpqSession.Phase.FARMING, session.phase());
        assertNull(session.potionsAtEntry());
    }

    @Test
    void theLeadersEntryBeginsFarmingThroughIt() {
        AgentLmpqSession session = preparing(true);
        session.clearEventInstance(); // the run is not bound until the leader is inside
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 5_000L);

            assertEquals(AgentLmpqSession.Phase.FARMING, session.phase());
            assertEquals(1, lines.startingWith("LMPQ potions:").size());
        }
    }

    @Test
    void anAgentFoundInTheMazeBeginsFarmingThroughIt() {
        AgentLmpqSession session = preparing(true);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickMember(session, run.entry(kes), kes, 5_000L);

            assertEquals(AgentLmpqSession.Phase.FARMING, session.phase());
            assertEquals(1, lines.startingWith("LMPQ potions:").size());
        }
    }

    @Test
    void noFarmingTransitionBypassesIt() throws Exception {
        LmpqSource coordinator = LmpqSource.of("AgentLmpqCoordinator");
        assertEquals(Map.of("beginFarming", 1), coordinator.callers(
                "\\btransition\\s*\\(\\s*AgentLmpqSession\\s*\\.\\s*Phase\\s*\\.\\s*FARMING\\b"));
        assertEquals(Map.of("prepare", 1, "tickSession", 1), coordinator.callers("\\bbeginFarming\\s*\\("));
    }
}
