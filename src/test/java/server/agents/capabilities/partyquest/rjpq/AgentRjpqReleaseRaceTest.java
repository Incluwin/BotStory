package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * A release can begin off the central tick thread ('!rjpqtest stop', a director-bridge stop, a router forceStop): the
 * companions' own ticks then do nothing more, the run's pass stops, and no full-rate hold is taken back after the
 * release let go of them all. A hold taken before the release began is let go by it; a requirement something else
 * set is kept.
 */
class AgentRjpqReleaseRaceTest {
    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.BEAKER_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    private AgentSimulationState state(int id) {
        return fixture.entry(id).simulationState();
    }

    private void registered(int id) {
        fixture.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(fixture.entry(id));
    }

    @Test
    void aCompanionWhoseRunIsBeingReleasedDoesNothingMore() {
        fixture.session.beginTermination();

        fixture.tick(TOBI, 5_000L);

        assertFalse(state(TOBI).fullRateSimulationRequired());
        assertTrue(fixture.session.fullRateHeld().isEmpty());
        verifyNoInteractions(fixture.actions);
    }

    @Test
    void aHoldAskedForAfterTheReleaseBeganIsRefused() {
        fixture.session.beginTermination();

        AgentRjpqFullRateSimulation.apply(fixture.session, TOBI, fixture.entry(TOBI), true);

        assertFalse(state(TOBI).fullRateSimulationRequired());
        assertTrue(fixture.session.fullRateHeld().isEmpty());
    }

    @Test
    void aHoldTakenBeforeTheReleaseIsLetGoAndNotTakenBack() {
        registered(TOBI);
        fixture.tick(TOBI, 5_000L);
        assertTrue(state(TOBI).fullRateSimulationRequired());

        fixture.session.beginTermination();
        AgentRjpqFullRateSimulation.releaseAll(fixture.session);
        fixture.tick(TOBI, 5_100L);

        assertFalse(state(TOBI).fullRateSimulationRequired());
        assertTrue(fixture.session.fullRateHeld().isEmpty());
    }

    @Test
    void pinARequirementSomethingElseSetIsKept() {
        registered(TOBI);
        state(TOBI).requireFullRateSimulation();
        fixture.tick(TOBI, 5_000L);

        fixture.session.beginTermination();
        AgentRjpqFullRateSimulation.releaseAll(fixture.session);

        assertTrue(state(TOBI).fullRateSimulationRequired());
    }

    @Test
    void theRunsOwnPassStopsOnceItsReleaseBegan() {
        AgentRjpqSession.Phase before = fixture.session.phase();
        fixture.session.beginTermination();

        AgentRjpqCoordinator.tickSession(fixture.session, 5_000L);

        assertEquals(before, fixture.session.phase());
    }
}
