package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h review (HIGH): when every companion has departed (handed back) while humans stay in the maze, the run lets go
 * of them as HPQ and LPQ do ("every companion left; the humans carry on"): it is released without a disposal and
 * without an exit, in every mode, whether the coordinator pass or the watchdog finds it. Before, the watchdog read the
 * departed Agent as "not loaded" and the release ejected the humans mid-maze and disposed their event. Only a run whose
 * event holds no player any more is failed and disposed as before; an Agent that is loaded but dead (not departed)
 * still has the run ticked.
 */
class AgentLmpqHumansCarryOnTest {
    private static final String CARRY_ON = "every companion left; the humans carry on";
    private static final long BIND = 10_000L;
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character ann = run.human(2, "Ann");
    private final Character cy = run.human(3, "Cy");
    private final Character kes = run.companion(4, "Kes");

    @AfterEach
    void tearDown() {
        run.close();
    }

    /** Three humans and one companion farming, seen whole by the event once. */
    private AgentLmpqSession farming() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, ann, cy, kes);
        AgentLmpqCoordinator.tickSession(session, 9_000L);
        return session;
    }

    /** Kes died and his respawn took him to the exit map: LudiMazePQ.js only unregistered him (4 > 3). */
    private void kesRespawnedOutside() {
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        when(kes.getEventInstance()).thenReturn(null);
        run.livePlayers(ben, ann, cy);
    }

    private void assertTheHumansCarryOn(AgentLmpqSession session) {
        assertNull(AgentLmpqSessionRegistry.forMember(ben.getId()), "the run let go");
        assertEquals(CARRY_ON, session.failure());
        verify(run.event, never()).exitPlayer(any());
        verify(run.event, never()).dispose();
        for (Character human : List.of(ben, ann, cy)) verify(run.party, never()).leaveCurrentParty(human);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(false),
                eq(CARRY_ON), anyLong(), any()));
    }

    @Test
    void thePassThatSeesTheLastCompanionDepartLetsTheHumansCarryOn() {
        AgentLmpqSession session = farming();
        kesRespawnedOutside();
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqTerminationService.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);

            assertTrue(session.departed(kes.getId()));
            assertTheHumansCarryOn(session);
            assertEquals(List.of("LMPQ session released: session=" + session.sessionId() + " outcome=" + CARRY_ON
                            + " members=4 companionRun=false pastTheClear=false"),
                    lines.startingWith("LMPQ session released:"));
        }
        AgentLmpqWatchdogRuntime.tick(session, 11_000L);
        verify(run.event, never()).dispose();
    }

    @Test
    void theWatchdogLetsTheHumansCarryOnWhenEveryCompanionHasDeparted() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, ann, cy, kes);
        session.noteHeld(List.of(ben.getId(), ann.getId(), cy.getId(), kes.getId()));
        assertTrue(session.markDeparted(kes.getId())); // departed and handed back by an earlier pass
        run.livePlayers(ben, ann, cy);

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertTheHumansCarryOn(session);
    }

    @Test
    void aCompanionTheEventNeverRegisteredLeavesTheHumansToCarryOn() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.PREPARING,
                ben, ann, cy, kes);
        session.clearEventInstance();
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP); // Kes was not at Rolly
        when(kes.getEventInstance()).thenReturn(null);
        run.livePlayers(ben, ann, cy);
        AgentLmpqCoordinator.tickSession(session, BIND);
        assertEquals(AgentLmpqSession.Phase.FARMING, session.phase(), "bound on Ben's entry");

        AgentLmpqCoordinator.tickSession(session, BIND + 4_999L);
        assertFalse(session.terminal(), "Kes may still be entering");
        AgentLmpqCoordinator.tickSession(session, BIND + 5_000L);

        assertTrue(session.departed(kes.getId()));
        assertTheHumansCarryOn(session);
    }

    @Test
    void aRunWhoseEventHoldsNoPlayerIsFailedAndDisposedAsBefore() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, ann, cy, kes);
        session.noteHeld(List.of(ben.getId(), ann.getId(), cy.getId(), kes.getId()));
        assertTrue(session.markDeparted(kes.getId()));
        run.livePlayers(); // the humans left too

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertEquals("Every LMPQ Agent departed", session.failure());
        assertNull(AgentLmpqSessionRegistry.forMember(ben.getId()), "released");
        verify(run.event).dispose();
    }

    @Test
    void anAgentLoadedButDeadAndNotDepartedStillHasTheRunTicked() {
        AgentLmpqSession session = farming();
        when(kes.getHp()).thenReturn(0); // down in the maze, still registered: its respawn decides

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertFalse(session.terminal(), session.failure());
        assertEquals(kes.getId(), session.executionAgentId());
        verify(run.event, never()).dispose();
    }

    @Test
    void anAgentNotLoadedAndNotDepartedStillFailsTheRun() {
        AgentLmpqSession session = farming();
        run.unload(kes);

        AgentLmpqWatchdogRuntime.tick(session, 10_000L);

        assertEquals("No LMPQ Agent is loaded", session.failure());
    }
}
