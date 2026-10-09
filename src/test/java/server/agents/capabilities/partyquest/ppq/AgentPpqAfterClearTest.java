package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.UNA;

/**
 * The PPQ review's finding 1 and its split item (every mode): after the clear a run is bounded. Wu Yang's room has no
 * exit, so a companion that cannot take its Feather, or a human idling there, never holds the run open past
 * AFTER_CLEAR_LIMIT_MS (5 minutes); the release then walks any Agent still in the cleared event out. A companion run
 * waits for its companions only, never a human, and no run completes while its members are still on the boss map
 * between setEventCleared and clearPQ's warp.
 */
class AgentPpqAfterClearTest {
    private static final long MINUTE = 60_000L;

    /** Everyone but {@code stay} took Wu Yang's exit: off the PPQ maps and out of the event. */
    private static void allOutBut(PpqRunFixture run, int stay) {
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            if (id == stay) continue;
            run.onMap(id, AgentPpqDefinition.RECRUIT_MAP);
            when(run.character(id).getEventInstance()).thenReturn(null);
        }
    }

    @Test
    void aCompanionWithNoRoomForItsFeatherEndsTheRunAtTheLimitAndIsWalkedOut() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             PpqLogLines lines = new PpqLogLines(AgentPpqCoordinator.class)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L);
            allOutBut(run, MIRA); // Wu Yang: 'Please make room in ETC.', over and over

            run.tick(2_000L + 5 * MINUTE - 1);
            assertFalse(run.session.terminal());
            run.tick(2_000L + 5 * MINUTE);

            assertEquals(AgentPpqSession.Phase.COMPLETED, run.session.phase());
            assertEquals(List.of("PPQ after clear: session=" + run.session.sessionId()
                    + " stillInside=true afterClearMs=300000 companionRun=true"), lines.startingWith("PPQ after clear:"));
            AgentPpqTerminationService.release(run.session, "", 2_000L + 5 * MINUTE, false);
            verify(run.event).exitPlayer(run.character(MIRA));
        }
    }

    @Test
    void aCompanionRunNeverWaitsForAHumanByWuYang() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L);
            allOutBut(run, BEN);

            run.tick(3_000L);

            assertEquals(AgentPpqSession.Phase.COMPLETED, run.session.phase());
        }
    }

    @Test
    void anotherModeWaitsForAHumanByWuYangUpToTheLimit() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L);
            allOutBut(run, BEN);

            run.tick(2_000L + MINUTE);
            assertFalse(run.session.terminal());
            run.tick(2_000L + 5 * MINUTE);

            assertEquals(AgentPpqSession.Phase.COMPLETED, run.session.phase());
        }
    }

    @Test
    void noRunCompletesWhileItsMembersAreStillOnTheBossMap() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.BOSS_MAP)) {
            // setEventCleared has run; clearPQ's warp to 925100600 has not.
            when(run.event.isEventCleared()).thenReturn(true);

            run.tick(2_000L);

            assertEquals(AgentPpqSession.Phase.CLEARING, run.session.phase());
        }
    }

    @Test
    void aStockReleaseAfterTheClearWalksItsAgentsOutButNotTheHuman() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.session.complete(9_000L);

            AgentPpqTerminationService.release(run.session, "", 9_000L, false);

            for (int id : PpqRunFixture.AGENTS) {
                verify(run.event).exitPlayer(run.character(id));
            }
            verify(run.event, never()).exitPlayer(run.character(BEN));
            verify(run.event, never()).dispose();
        }
    }
}
