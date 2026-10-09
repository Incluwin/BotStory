package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.UNA;

/**
 * The PPQ review 2 finding 1 (every mode): a companion the death gate let go (its respawn took it to 925100700 and
 * PiratePQ.js unregistered it) or one that walked out has left the run. It leaves the session and is handed back to
 * its owner at once ('PPQ departed:'), instead of standing frozen for the rest of the run. A dead companion the event
 * still holds has not left, nor has one between two PPQ maps or one the event never held.
 */
class AgentPpqDepartureTest {
    /** Tobi's respawn: out of the event, on the exit map. */
    private static void respawnedOut(PpqRunFixture run, int id) {
        when(run.character(id).getEventInstance()).thenReturn(null);
        run.onMap(id, AgentPpqDefinition.EXIT_MAP);
    }

    @Test
    void aCompanionTheGateLetGoIsHandedBack() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             PpqLogLines lines = new PpqLogLines(AgentPpqCoordinator.class)) {
            run.tick(2_000L);
            respawnedOut(run, TOBI);

            run.tick(2_500L);

            assertNull(run.session.member(TOBI));
            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(TOBI, "ppq", 2_500L));
            assertEquals(List.of("PPQ departed: session=" + run.session.sessionId() + " member=" + TOBI
                            + " map=" + AgentPpqDefinition.EXIT_MAP + " registered=3 companionRun=true"),
                    lines.startingWith("PPQ departed:"));
        }
    }

    @Test
    void theLastCompanionOfAHumanLedRunGoesHomeAndTheHumansCarryOn() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             PpqLogLines lines = new PpqLogLines(AgentPpqCoordinator.class)) {
            // Ben's party had more humans than companions: Mira is the run's only companion.
            run.session.removeMember(TOBI);
            run.session.removeMember(UNA);
            run.tick(2_000L);
            respawnedOut(run, MIRA);

            run.tick(2_500L);

            assertEquals(AgentPpqTerminationService.HUMANS_CARRY_ON, run.session.failure());
            assertTrue(run.session.terminationStarted());
            verify(run.event, never()).dispose();
            verify(run.event, never()).exitPlayer(run.character(BEN));
            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(run.session.sessionId()),
                    eq(false), eq(AgentPpqTerminationService.HUMANS_CARRY_ON), eq(2_500L), any()));
            assertEquals(1, lines.startingWith("PPQ departed:").size());
            assertTrue(lines.startingWith("PPQ departed:").getFirst().endsWith("last=true"));
        }
    }

    @Test
    void aDeadCompanionTheEventStillHoldsHasNotLeft() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            run.tick(2_000L);
            when(run.character(UNA).getHp()).thenReturn(0);

            run.tick(2_500L);

            assertNotNull(run.session.member(UNA));
            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(anyInt(), anyString(),
                    anyLong()), never());
        }
    }

    @Test
    void aCompanionBetweenTwoPpqMapsHasNotLeft() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            run.tick(2_000L);
            when(run.character(TOBI).getEventInstance()).thenReturn(null); // still on deck 2

            run.tick(2_500L);

            assertNotNull(run.session.member(TOBI));
        }
    }

    @Test
    void aCompanionTheEventNeverHeldHasNotLeft() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            respawnedOut(run, TOBI);

            run.tick(2_000L);

            assertNotNull(run.session.member(TOBI));
        }
    }

    @Test
    void nothingLeavesAfterTheClear() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            run.tick(2_000L);
            when(run.event.isEventCleared()).thenReturn(true);
            respawnedOut(run, TOBI);

            run.tick(2_500L);

            assertNotNull(run.session.member(TOBI));
        }
    }
}
