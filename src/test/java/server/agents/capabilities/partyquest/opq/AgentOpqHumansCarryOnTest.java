package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
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
 * P6h review (the LMPQ HIGH, for OPQ): when every companion has departed (handed back) while humans stay in the
 * tower, the run lets go of them as HPQ and LPQ do ("every companion left; the humans carry on"): it is released
 * without a disposal and without an exit, in every mode, whether the coordinator pass or the watchdog finds it. Before,
 * the watchdog kept driving the run through the departed Agent (or, unloaded, failed it) and its release ejected the
 * humans and disposed their event. Only a run whose event holds no player any more is failed and disposed as before;
 * an Agent that is loaded but dead (not departed) still has the run ticked.
 */
class AgentOpqHumansCarryOnTest {
    private static final String CARRY_ON = "every companion left; the humans carry on";
    private static final long GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqCoordinator.ENTRY_HOLD_GRACE_MS");
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character ann = run.human(2, "Ann");
    private final Character cy = run.human(3, "Cy");
    private final Character dee = run.human(4, "Dee");
    private final Character eve = run.human(5, "Eve");
    private final Character kes = run.companion(6, "Kes");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession fiveHumansAndKes() {
        return run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false, AgentOpqSession.Phase.SPLIT_ROOMS,
                ben, ann, cy, dee, eve, kes);
    }

    /** From now on the event holds exactly {@code players}. */
    private void holds(Character... players) {
        List<Character> held = List.of(players);
        when(run.event.getPlayers()).thenAnswer(call -> new ArrayList<>(held));
    }

    private void assertTheHumansCarryOn(AgentOpqSession session) {
        assertNull(AgentOpqSessionRegistry.forMember(ben.getId()), "the run let go");
        assertEquals(CARRY_ON, session.failure());
        verify(run.event, never()).exitPlayer(any());
        verify(run.event, never()).dispose();
        for (Character human : List.of(ben, ann, cy, dee, eve)) verify(run.party, never()).leaveCurrentParty(human);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(false),
                eq(CARRY_ON), anyLong(), any()));
    }

    @Test
    void thePassThatSeesTheLastCompanionDepartLetsTheHumansCarryOn() {
        AgentOpqSession session = fiveHumansAndKes();
        AgentOpqCoordinator.tickSession(session, 3_000L);
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP); // its respawn took it out (6 > 5)
        when(kes.getEventInstance()).thenReturn(null);
        holds(ben, ann, cy, dee, eve);
        try (OpqLogLines lines = new OpqLogLines(AgentOpqTerminationService.class)) {
            AgentOpqCoordinator.tickSession(session, 4_000L);

            assertTrue(session.departed(kes.getId()));
            assertTheHumansCarryOn(session);
            assertEquals(List.of("OPQ session released: session=" + session.sessionId() + " outcome=" + CARRY_ON
                            + " members=6 companionRun=false"),
                    lines.startingWith("OPQ session released:"));
        }
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(kes.getId(), "opq", 4_000L));
        AgentOpqWatchdogRuntime.tick(session, 5_000L);
        verify(run.event, never()).dispose();
    }

    @Test
    void theWatchdogLetsTheHumansCarryOnWhenEveryCompanionHasDeparted() {
        AgentOpqSession session = fiveHumansAndKes();
        session.noteHeld(List.of(ben.getId(), ann.getId(), cy.getId(), dee.getId(), eve.getId(), kes.getId()));
        assertTrue(session.markDeparted(kes.getId())); // departed and handed back by an earlier pass
        holds(ben, ann, cy, dee, eve);

        AgentOpqWatchdogRuntime.tick(session, 5_000L);

        assertTheHumansCarryOn(session);
    }

    @Test
    void aDepartedCompanionThatHasSinceLoggedOutStillLeavesTheHumansToCarryOn() {
        AgentOpqSession session = fiveHumansAndKes();
        session.noteHeld(List.of(ben.getId(), ann.getId(), cy.getId(), dee.getId(), eve.getId(), kes.getId()));
        assertTrue(session.markDeparted(kes.getId()));
        // handed back, then logged out: departed, not "not loaded"
        run.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(kes.getId())).thenReturn(null);
        holds(ben, ann, cy, dee, eve);

        AgentOpqWatchdogRuntime.tick(session, 5_000L);

        assertTheHumansCarryOn(session);
    }

    @Test
    void aCompanionTheEventNeverRegisteredLeavesTheHumansToCarryOn() {
        AgentOpqSession session = fiveHumansAndKes();
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP); // Kes was not at Wonky
        when(kes.getEventInstance()).thenReturn(null);
        holds(ben, ann, cy, dee, eve);
        AgentOpqCoordinator.tickSession(session, 10_000L);
        AgentOpqCoordinator.tickSession(session, 10_000L + GRACE_MS - 1L);
        assertFalse(session.terminal(), "Kes may still be entering");

        AgentOpqCoordinator.tickSession(session, 10_000L + GRACE_MS);

        assertTrue(session.departed(kes.getId()));
        assertTheHumansCarryOn(session);
    }

    @Test
    void aRunWhoseEventHoldsNoPlayerIsFailedAndDisposedAsBefore() {
        AgentOpqSession session = fiveHumansAndKes();
        session.noteHeld(List.of(ben.getId(), ann.getId(), cy.getId(), dee.getId(), eve.getId(), kes.getId()));
        assertTrue(session.markDeparted(kes.getId()));
        holds(); // the humans left too

        AgentOpqWatchdogRuntime.tick(session, 5_000L);

        assertEquals("Every OPQ Agent departed", session.failure());
        assertNull(AgentOpqSessionRegistry.forMember(ben.getId()), "released");
        verify(run.event).dispose();
    }

    @Test
    void anAgentLoadedButDeadAndNotDepartedStillHasTheRunTicked() {
        AgentOpqSession session = fiveHumansAndKes();
        when(kes.getHp()).thenReturn(0); // down in the tower, still registered: the death gate decides

        AgentOpqWatchdogRuntime.tick(session, 5_000L);

        assertFalse(session.terminal(), session.failure());
        assertEquals(kes.getId(), session.executionAgentId());
        verify(run.event, never()).dispose();
    }
}
