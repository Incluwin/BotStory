package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.simulation.AgentSimulationState;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * P6h Step 2f: a companion of a companion run simulates at full rate while it stands on one of the event's maps; the
 * hold is let go when it leaves them, departs or the run is released, and a requirement something else set before is
 * never cleared. A stock run holds nothing.
 */
class AgentLmpqFullRateTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(40, "Ben");
    private final Character mira = run.companion(41, "Mira");
    private final Character kes = run.companion(42, "Kes");
    private final AgentSimulationState miraState = new AgentSimulationState();
    private final AgentSimulationState kesState = new AgentSimulationState();

    AgentLmpqFullRateTest() {
        when(run.entry(mira).simulationState()).thenReturn(miraState);
        when(run.entry(kes).simulationState()).thenReturn(kesState);
    }

    @AfterEach
    void close() {
        run.close();
    }

    @Test
    void aCompanionInTheMazeIsHeldAtFullRateUntilItLeavesIt() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        assertTrue(miraState.fullRateSimulationRequired());
        assertEquals(Set.of(41), session.fullRateHeld());

        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 4_000L);
        assertFalse(miraState.fullRateSimulationRequired());
        assertEquals(Set.of(), session.fullRateHeld());
    }

    @Test
    void aRequirementSetBeforeTheRunIsKept() {
        kesState.requireFullRateSimulation();
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes);

        AgentLmpqCoordinator.tickMember(session, run.entry(kes), kes, 3_000L);
        AgentLmpqFullRateSimulation.releaseAll(session);

        assertTrue(kesState.fullRateSimulationRequired(), "not the run's to clear");
    }

    @Test
    void theReleaseLetsGoOfEveryHold() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        AgentLmpqCoordinator.tickMember(session, run.entry(kes), kes, 3_000L);

        AgentLmpqTerminationService.release(session, "test", 4_000L, false);

        assertFalse(miraState.fullRateSimulationRequired());
        assertFalse(kesState.fullRateSimulationRequired());
        assertEquals(Set.of(), session.fullRateHeld());
    }

    @Test
    void aStockRunHoldsNothing() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        assertFalse(miraState.fullRateSimulationRequired());
        assertEquals(Set.of(), session.fullRateHeld());
    }
}
