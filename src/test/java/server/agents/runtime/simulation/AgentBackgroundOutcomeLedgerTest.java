package server.agents.runtime.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBackgroundOutcomeLedgerTest {
    @Test
    void mutationFreeWindowReconcilesIdempotently() {
        AgentBackgroundOutcomeLedger ledger = new AgentBackgroundOutcomeLedger();

        ledger.begin(AgentAbstractExecutionScope.TOWN_LIFE, 100L);
        ledger.heartbeat(200L);

        assertTrue(ledger.reconcile());
        assertTrue(ledger.reconcile());
        assertFalse(ledger.snapshot().active());
        assertEquals(1L, ledger.snapshot().heartbeatCount());
        assertEquals(1L, ledger.snapshot().reconciliationCount());
    }

    @Test
    void unsupportedMutationPreventsReconciliation() {
        AgentBackgroundOutcomeLedger ledger = new AgentBackgroundOutcomeLedger();
        ledger.begin(AgentAbstractExecutionScope.TOWN_LIFE, 100L);
        ledger.recordUnsupportedOutcome("quest mutation");

        assertFalse(ledger.reconcile());
        assertTrue(ledger.snapshot().active());
        assertEquals("quest mutation", ledger.snapshot().unsupportedOutcome());
    }

    @Test
    void failureClockKeepsTheFirstFailureUntilTheWindowCloses() {
        AgentBackgroundOutcomeLedger ledger = new AgentBackgroundOutcomeLedger();
        assertEquals(-1L, ledger.recordFailedReconciliation(50L));

        ledger.begin(AgentAbstractExecutionScope.TOWN_LIFE, 100L);
        assertEquals(200L, ledger.recordFailedReconciliation(200L));
        assertEquals(200L, ledger.recordFailedReconciliation(900L));
        assertEquals(200L, ledger.snapshot().firstFailedReconciliationAtMs());

        assertTrue(ledger.reconcile());
        assertEquals(-1L, ledger.snapshot().firstFailedReconciliationAtMs());
    }

    @Test
    void abandonClosesTheWindowAndKeepsEvidence() {
        AgentMaterializedStateFingerprint baseline =
                new AgentMaterializedStateFingerprint(102000000, 10, 20, -1, 100, 30, 0);
        AgentMaterializedStateFingerprint moved =
                new AgentMaterializedStateFingerprint(240000000, 5, 5, -1, 100, 30, 0);
        AgentBackgroundOutcomeLedger ledger = new AgentBackgroundOutcomeLedger();
        ledger.begin(AgentAbstractExecutionScope.TOWN_LIFE, 100L, baseline);
        ledger.recordUnsupportedOutcome("quest mutation");
        ledger.recordFailedReconciliation(300L);
        assertFalse(ledger.reconcile(moved));

        AgentBackgroundOutcomeLedger.Abandonment abandonment =
                ledger.abandon("abstract scope ended", moved, 400L);

        assertEquals("abstract scope ended", abandonment.reason());
        assertEquals(AgentAbstractExecutionScope.TOWN_LIFE, abandonment.scope());
        assertEquals(100L, abandonment.startedAtMs());
        assertEquals(300L, abandonment.firstFailedReconciliationAtMs());
        assertEquals(400L, abandonment.abandonedAtMs());
        assertEquals("quest mutation", abandonment.unsupportedOutcome());
        assertEquals(baseline, abandonment.materializedBaseline());
        assertEquals(moved, abandonment.currentState());
        AgentBackgroundOutcomeLedger.Snapshot snapshot = ledger.snapshot();
        assertFalse(snapshot.active());
        assertEquals(AgentAbstractExecutionScope.NONE, snapshot.scope());
        assertNull(snapshot.unsupportedOutcome());
        assertNull(snapshot.materializedBaseline());
        assertEquals(-1L, snapshot.firstFailedReconciliationAtMs());
        assertEquals(1L, snapshot.abandonmentCount());
        assertEquals(0L, snapshot.reconciliationCount());
        assertEquals(abandonment, snapshot.lastAbandonment());
        assertTrue(ledger.reconcile(moved));
        assertNull(ledger.abandon("again", moved, 500L));
        assertEquals(1L, ledger.snapshot().abandonmentCount());
    }
}
