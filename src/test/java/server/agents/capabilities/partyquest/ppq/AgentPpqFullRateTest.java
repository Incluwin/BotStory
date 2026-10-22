package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;

/**
 * P6i Step 2, plan 7.1 and 7.9: a PPQ companion leaves FOLLOW for the whole run (map-sync would pull it onto its
 * leader's map past the doors and the chest rooms) and simulates at full rate while it stands in the event on a map its
 * leader is not on, or in Wu Yang's room; the hold goes when it rejoins him, departs, or the run ends. A stock run's
 * Agents keep today's behaviour.
 */
class AgentPpqFullRateTest {
    @Test
    void aCompanionAwayFromItsLeaderRunsAtFullRateUntilItRejoinsHim() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            AgentRuntimeEntry mira = run.entries.get(MIRA);
            run.onMap(BEN, AgentPpqDefinition.DECK_ONE_MAP);

            AgentPpqCoordinator.tickMember(run.session, mira, run.character(MIRA), 5_000L);
            assertTrue(mira.simulationState().fullRateSimulationRequired());
            assertEquals(Set.of(MIRA), run.session.fullRateHeld());

            run.onMap(BEN, AgentPpqDefinition.DECK_TWO_MAP);
            AgentPpqCoordinator.tickMember(run.session, mira, run.character(MIRA), 6_000L);
            assertFalse(mira.simulationState().fullRateSimulationRequired());
            assertEquals(Set.of(), run.session.fullRateHeld());
        }
    }

    @Test
    void theReleaseLetsGoOfEveryHold() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            run.onMap(BEN, AgentPpqDefinition.DECK_ONE_MAP);
            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);
            AgentPpqCoordinator.tickMember(run.session, run.entries.get(TOBI), run.character(TOBI), 5_000L);

            AgentPpqTerminationService.release(run.session, "stop", 6_000L, true);

            assertEquals(Set.of(), run.session.fullRateHeld());
            assertFalse(run.entries.get(MIRA).simulationState().fullRateSimulationRequired());
            assertFalse(run.entries.get(TOBI).simulationState().fullRateSimulationRequired());
        }
    }

    @Test
    void aFullRateRequirementSomethingElseSetIsKept() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            AgentRuntimeEntry mira = run.entries.get(MIRA);
            mira.simulationState().requireFullRateSimulation();
            run.onMap(BEN, AgentPpqDefinition.DECK_ONE_MAP);
            AgentPpqCoordinator.tickMember(run.session, mira, run.character(MIRA), 5_000L);
            run.onMap(BEN, AgentPpqDefinition.DECK_TWO_MAP);
            AgentPpqCoordinator.tickMember(run.session, mira, run.character(MIRA), 6_000L);

            assertTrue(mira.simulationState().fullRateSimulationRequired());
        }
    }

    @Test
    void aFollowingCompanionIsStoppedAndAStockAgentIsNotHeld() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_TWO_MAP)) {
            AgentRuntimeEntry mira = run.entries.get(MIRA);
            AgentModeStateRuntime.startFollowing(mira, BEN);

            AgentPpqCoordinator.tickMember(run.session, mira, run.character(MIRA), 5_000L);

            verify(run.actions).stop(mira);
        }
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_TWO_MAP)) {
            AgentRuntimeEntry mira = run.entries.get(MIRA);
            AgentModeStateRuntime.startFollowing(mira, BEN);
            run.onMap(BEN, AgentPpqDefinition.DECK_ONE_MAP);

            AgentPpqCoordinator.tickMember(run.session, mira, run.character(MIRA), 5_000L);

            assertFalse(mira.simulationState().fullRateSimulationRequired());
            verify(run.actions, never()).stop(mira);
        }
    }
}
