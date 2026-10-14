package server.agents.runtime.simulation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.integration.MapGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Agents wedged in BACKGROUND_ABSTRACT by outside changes rejoin the live world (2026-10-13 Garrick). */
class AgentAbstractRecoveryScenarioTest {
    private static final int PERION = 102000000;
    private static final int LEAFRE = 240000000;
    private static final long ESCAPE_MS = 30_000L;

    private final MapGateway maps = mock(MapGateway.class);
    private final MapleMap perion = map(PERION);
    private final MapleMap leafre = map(LEAFRE);
    private final Character agent = mock(Character.class);
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
    private final AtomicBoolean mailboxWork = new AtomicBoolean();
    private final AgentSimulationPolicy policy = new AgentDefaultSimulationPolicy(
            true,
            true,
            maps,
            (runtime, map) -> !mailboxWork.get()
                    && runtime.simulationState().abstractExecutionScope()
                    != AgentAbstractExecutionScope.NONE);
    private final AgentSimulationTransitionService transitions = new AgentSimulationTransitionService(
            runtime -> true,
            AgentBackgroundOutcomeReconciler.ledgerBacked(),
            new AgentAbstractRecoveryPolicy(maps::isObservedByPlayer, ESCAPE_MS));

    AgentAbstractRecoveryScenarioTest() {
        when(agent.getId()).thenReturn(835);
        when(agent.getName()).thenReturn("Garrick");
        standAt(perion, new Point(100, 200));
        entry.simulationState().allowAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
    }

    @Test
    void rosterMoveAfterTownLifeEndedLeavesAbstractInsteadOfWedging() {
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(1_000L));

        standAt(leafre, new Point(5, 5));
        // TownLife notices the map change on its next abstract tick and ends (terminateLocal).
        entry.simulationState().clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
        AgentAbstractTickRuntime.tick(entry, 6_000L);

        assertNull(ledger().unsupportedOutcome());
        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(6_000L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED_LEDGER_ABANDONED,
                entry.simulationState().lastTransitionEvidence().outcome());
        assertFalse(ledger().active());
        assertEquals(1L, ledger().abandonmentCount());
        AgentBackgroundOutcomeLedger.Abandonment abandonment = ledger().lastAbandonment();
        assertEquals("abstract scope ended", abandonment.reason());
        assertEquals(PERION, abandonment.materializedBaseline().mapId());
        assertEquals(LEAFRE, abandonment.currentState().mapId());

        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(11_000L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.ALREADY_IN_MODE,
                entry.simulationState().lastTransitionEvidence().outcome());
    }

    @Test
    void townLifeEndingWithoutOutsideChangesReconcilesNormally() {
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(1_000L));

        entry.simulationState().clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
        AgentAbstractTickRuntime.tick(entry, 6_000L);

        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(11_000L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED,
                entry.simulationState().lastTransitionEvidence().outcome());
        assertEquals(1L, ledger().reconciliationCount());
        assertEquals(0L, ledger().abandonmentCount());
    }

    @Test
    void outsideLevelChangeStaysAbstractUntilAPlayerArrives() {
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(1_000L));

        when(agent.getLevel()).thenReturn(50);
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(6_000L));
        assertEquals(0L, ledger().abandonmentCount());

        when(maps.isObservedByPlayer(perion)).thenReturn(true);
        assertEquals(AgentSimulationMode.PRESENTATION, decide(11_000L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED_LEDGER_ABANDONED,
                entry.simulationState().lastTransitionEvidence().outcome());
        assertEquals("player on map " + PERION, ledger().lastAbandonment().reason());
    }

    @Test
    void fullRateHoldLeavesAnUnreconciledWindowAtOnce() {
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(1_000L));

        when(agent.getMeso()).thenReturn(5_000);
        entry.simulationState().requireFullRateSimulation();

        assertEquals(AgentSimulationMode.PRESENTATION, decide(6_000L));
        assertEquals("full-rate simulation required", ledger().lastAbandonment().reason());
    }

    @Test
    void mailboxWorkWhileTownLifeIsInFlightFailsClosedUntilTheEscapeBound() {
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(1_000L));

        when(agent.getMeso()).thenReturn(5_000);
        mailboxWork.set(true);

        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(6_000L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.OUTCOME_RECONCILIATION_FAILED,
                entry.simulationState().lastTransitionEvidence().outcome());
        assertEquals(6_000L, ledger().firstFailedReconciliationAtMs());
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(6_000L + ESCAPE_MS - 1L));
        assertEquals(0L, ledger().abandonmentCount());

        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(6_000L + ESCAPE_MS));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED_LEDGER_ABANDONED,
                entry.simulationState().lastTransitionEvidence().outcome());
        assertEquals("reconciliation failed for " + ESCAPE_MS + " ms",
                ledger().lastAbandonment().reason());
        assertEquals(-1L, ledger().firstFailedReconciliationAtMs());
    }

    @Test
    void nextAbstractWindowStartsFromTheCurrentStateAfterAnAbandon() {
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(1_000L));
        standAt(leafre, new Point(5, 5));
        entry.simulationState().clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(6_000L));

        entry.simulationState().allowAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
        assertEquals(AgentSimulationMode.BACKGROUND_ABSTRACT, decide(11_000L));
        assertEquals(LEAFRE, ledger().materializedBaseline().mapId());
        mailboxWork.set(true);

        assertEquals(AgentSimulationMode.BACKGROUND_ACTIVE, decide(16_000L));
        assertEquals(AgentSimulationTransitionEvidence.Outcome.APPLIED,
                entry.simulationState().lastTransitionEvidence().outcome());
        assertEquals(1L, ledger().reconciliationCount());
        assertEquals(1L, ledger().abandonmentCount());
    }

    private AgentSimulationMode decide(long nowMs) {
        return transitions.transition(entry, policy.selectMode(entry), nowMs);
    }

    private AgentBackgroundOutcomeLedger.Snapshot ledger() {
        return entry.simulationState().backgroundOutcomes().snapshot();
    }

    private void standAt(MapleMap map, Point position) {
        int mapId = map.getId();
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getPosition()).thenReturn(position);
    }

    private static MapleMap map(int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        return map;
    }
}
