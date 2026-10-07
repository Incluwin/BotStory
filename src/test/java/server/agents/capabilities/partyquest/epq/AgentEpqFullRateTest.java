package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.simulation.AgentSimulationState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Step 2.12: a companion run's companions standing in its EPQ instance simulate at full rate (KPQ's rule: rooms nobody
 * watches would run at reduced fidelity, as LPQ's stage 5 showed). The hold is the run's own: it lets go of a companion
 * out of the event, ends with the run, is never taken back once the release began, and never clears a requirement
 * something else set. A stock run leaves the cadence alone.
 */
class AgentEpqFullRateTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Map<Character, AgentSimulationState> states = new HashMap<>();

    AgentEpqFullRateTest() {
        for (Character agent : List.of(mira, tobi, una)) {
            AgentSimulationState state = new AgentSimulationState();
            states.put(agent, state);
            when(run.entry(agent).simulationState()).thenReturn(state);
        }
        run.party(ben, mira, tobi, una);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    private boolean fullRate(Character agent) {
        return states.get(agent).fullRateSimulationRequired();
    }

    private AgentEpqSession session(boolean companionRun) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun, AgentEpqSession.Phase.STAGE_ONE,
                ben, mira, tobi, una);
    }

    @Test
    void aCompanionRunsCompanionsInItsInstanceRunAtFullRate() {
        AgentEpqSession session = session(true);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertTrue(fullRate(mira));
        assertTrue(fullRate(tobi));
        assertTrue(fullRate(una));
    }

    @Test
    void aCompanionOutOfTheEventIsLetGo() {
        AgentEpqSession session = session(true);
        AgentEpqCoordinator.tickSession(session, 2_000L);

        when(tobi.getEventInstance()).thenReturn(null);
        AgentEpqCoordinator.tickSession(session, 3_000L);

        assertFalse(fullRate(tobi));
        assertTrue(fullRate(mira));
    }

    @Test
    void aRequirementSomethingElseSetIsNeverCleared() {
        states.get(tobi).requireFullRateSimulation();
        AgentEpqSession session = session(true);
        AgentEpqCoordinator.tickSession(session, 2_000L);

        when(tobi.getEventInstance()).thenReturn(null);
        AgentEpqCoordinator.tickSession(session, 3_000L);

        assertTrue(fullRate(tobi), "something else still wants it");
    }

    @Test
    void theHoldEndsWithTheRun() {
        AgentEpqSession session = session(true);
        AgentEpqCoordinator.tickSession(session, 2_000L);

        AgentEpqTerminationService.release(session, "stopped by operator", 3_000L, true);

        assertFalse(fullRate(mira));
        assertFalse(fullRate(tobi));
        assertFalse(fullRate(una));
    }

    @Test
    void aReleaseThatBeganMeanwhileTakesNoHoldBack() {
        AgentEpqSession session = session(true);
        session.beginTermination(); // another thread's release has begun, the run is not yet terminal

        AgentEpqFullRateSimulation.apply(session, mira.getId(), run.entry(mira), true);

        assertFalse(fullRate(mira));
    }

    @Test
    void pinAStockRunLeavesTheCadenceAlone() {
        AgentEpqSession session = session(false);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertFalse(fullRate(mira));
        assertFalse(fullRate(tobi));
    }
}
