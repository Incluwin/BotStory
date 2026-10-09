package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ step 1 (C5): a companion run logs its companions' potions once when it reaches the entrance ('OPQ potions:
 * session= start='), although the coordinator passes through the entrance branch on every tick while the leader stands
 * there: the first pass wins. A stock run logs none; a count that cannot be read is logged once and never keeps the
 * run from its entrance.
 */
class AgentOpqPotionStartLineTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession enteringRun(boolean companionRun) {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, companionRun,
                AgentOpqSession.Phase.ENTERING, ben, mira, kes, pel, hal);
        session.clearEventInstance(); // bound by the coordinator when the leader is inside
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(run.partyQuest.event(ben)).thenReturn(run.event);
        return session;
    }

    @Test
    void theFirstEntrancePassWinsAcrossRepeatedTicks() {
        AgentOpqSession session = enteringRun(true);
        when(run.actions.hpPotionCount(any())).thenReturn(40);
        when(run.actions.mpPotionCount(any())).thenReturn(50);
        String start = "{Mira:40/50, Kes:40/50, Pel:40/50, Hal:40/50}";

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 3_000L);
            when(run.actions.hpPotionCount(any())).thenReturn(38); // a pot drunk on the way: not a new start
            AgentOpqCoordinator.tickSession(session, 4_500L);
            AgentOpqCoordinator.tickSession(session, 6_000L);

            assertEquals(List.of("OPQ potions: session=" + session.sessionId() + " start=" + start),
                    lines.startingWith("OPQ potions:"));
        }
        assertEquals(start, session.potionsAtEntry());
        assertEquals(AgentOpqSession.Phase.ENTRANCE, session.phase());
        assertSame(run.event, session.eventInstance());
        assertEquals(3_000L, session.firstEnteredAtMs(AgentOpqSession.Phase.ENTRANCE));
    }

    @Test
    void aStockRunReachesTheEntranceWithoutAPotionLine() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, false, AgentOpqSession.Phase.ENTERING,
                mira, kes, pel, hal, run.companion(6, "Gus"), run.companion(7, "Ivy"));
        session.clearEventInstance();
        when(mira.getMapId()).thenReturn(AgentOpqDefinition.ENTRANCE_MAP);
        when(run.partyQuest.event(mira)).thenReturn(run.event);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 3_000L);
            assertEquals(List.of(), lines.startingWith("OPQ potions"));
        }
        assertNull(session.potionsAtEntry());
        assertEquals(AgentOpqSession.Phase.ENTRANCE, session.phase());
        assertSame(run.event, session.eventInstance());
    }

    @Test
    void aCountThatCannotBeReadIsLoggedOnceAndTheRunStillEnters() {
        AgentOpqSession session = enteringRun(true);
        when(run.actions.hpPotionCount(any())).thenThrow(new IllegalStateException("inventory locked"));

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 3_000L);
            AgentOpqCoordinator.tickSession(session, 4_500L);

            assertEquals(List.of("OPQ potions failed: session=" + session.sessionId()),
                    lines.startingWith("OPQ potions"));
        }
        assertEquals("unavailable", session.potionsAtEntry());
        assertEquals(AgentOpqSession.Phase.ENTRANCE, session.phase());
        assertSame(run.event, session.eventInstance());
    }
}
