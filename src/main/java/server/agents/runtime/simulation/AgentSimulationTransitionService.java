package server.agents.runtime.simulation;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;

public final class AgentSimulationTransitionService {
    private static final Logger log = LoggerFactory.getLogger(AgentSimulationTransitionService.class);
    private final AgentMaterializationService materializationService;
    private final AgentBackgroundOutcomeReconciler outcomeReconciler;
    private final AgentAbstractRecoveryPolicy recoveryPolicy;

    public AgentSimulationTransitionService(AgentMaterializationService materializationService,
                                            AgentBackgroundOutcomeReconciler outcomeReconciler) {
        this(materializationService, outcomeReconciler, AgentAbstractRecoveryPolicy.production());
    }

    public AgentSimulationTransitionService(AgentMaterializationService materializationService,
                                            AgentBackgroundOutcomeReconciler outcomeReconciler,
                                            AgentAbstractRecoveryPolicy recoveryPolicy) {
        if (materializationService == null || outcomeReconciler == null || recoveryPolicy == null) {
            throw new IllegalArgumentException("Agent simulation transition dependencies are required");
        }
        this.materializationService = materializationService;
        this.outcomeReconciler = outcomeReconciler;
        this.recoveryPolicy = recoveryPolicy;
    }

    public AgentSimulationMode transition(AgentRuntimeEntry entry,
                                          AgentSimulationMode requestedMode,
                                          long nowMs) {
        if (entry == null || requestedMode == null) {
            throw new IllegalArgumentException("Agent runtime entry and simulation mode are required");
        }
        AgentSimulationMode currentMode = entry.simulationState().mode();
        if (currentMode == requestedMode) {
            if (currentMode == AgentSimulationMode.BACKGROUND_ABSTRACT) {
                entry.simulationState().backgroundOutcomes().begin(
                        entry.simulationState().abstractExecutionScope(),
                        nowMs,
                        AgentMaterializedStateFingerprint.capture(
                                AgentRuntimeIdentityRuntime.bot(entry)));
            }
            entry.simulationState().recordTransitionAttempt(
                    currentMode,
                    requestedMode,
                    AgentSimulationTransitionEvidence.Outcome.ALREADY_IN_MODE,
                    nowMs);
            return currentMode;
        }
        boolean ledgerAbandoned = false;
        if (currentMode == AgentSimulationMode.BACKGROUND_ABSTRACT
                && requestedMode != AgentSimulationMode.BACKGROUND_ABSTRACT
                && !outcomeReconciler.reconcile(entry)) {
            if (!abandonUnreconciledWindow(entry, requestedMode, nowMs)) {
                entry.simulationState().recordTransitionAttempt(
                        currentMode,
                        requestedMode,
                        AgentSimulationTransitionEvidence.Outcome.OUTCOME_RECONCILIATION_FAILED,
                        nowMs);
                return currentMode;
            }
            ledgerAbandoned = true;
        }
        if (currentMode != AgentSimulationMode.PRESENTATION
                && requestedMode != AgentSimulationMode.BACKGROUND_ABSTRACT
                && !materializationService.materialize(entry)) {
            entry.simulationState().recordTransitionAttempt(
                    currentMode,
                    requestedMode,
                    AgentSimulationTransitionEvidence.Outcome.MATERIALIZATION_FAILED,
                    nowMs);
            return currentMode;
        }
        entry.simulationState().transitionTo(requestedMode, nowMs);
        if (requestedMode == AgentSimulationMode.BACKGROUND_ABSTRACT) {
            entry.simulationState().backgroundOutcomes().begin(
                    entry.simulationState().abstractExecutionScope(),
                    nowMs,
                    AgentMaterializedStateFingerprint.capture(
                            AgentRuntimeIdentityRuntime.bot(entry)));
        }
        entry.simulationState().recordTransitionAttempt(
                currentMode,
                requestedMode,
                ledgerAbandoned
                        ? AgentSimulationTransitionEvidence.Outcome.APPLIED_LEDGER_ABANDONED
                        : AgentSimulationTransitionEvidence.Outcome.APPLIED,
                nowMs);
        return requestedMode;
    }

    /** Fail closed while abstract work is in flight; otherwise drop the window with evidence. */
    private boolean abandonUnreconciledWindow(AgentRuntimeEntry entry,
                                              AgentSimulationMode requestedMode,
                                              long nowMs) {
        AgentBackgroundOutcomeLedger ledger = entry.simulationState().backgroundOutcomes();
        long firstFailedAtMs = ledger.recordFailedReconciliation(nowMs);
        String reason = recoveryPolicy.abandonReason(entry, firstFailedAtMs, nowMs);
        if (reason == null) {
            return false;
        }
        Character agent = AgentRuntimeIdentityRuntime.bot(entry);
        AgentBackgroundOutcomeLedger.Abandonment abandonment = ledger.abandon(
                reason, AgentMaterializedStateFingerprint.capture(agent), nowMs);
        String name = agent == null ? "?" : agent.getName();
        int id = agent == null ? 0 : agent.getId();
        if (abandonment == null) {
            log.warn("Agent {} ({}) left BACKGROUND_ABSTRACT for {} without reconciling: {}",
                    name, id, requestedMode, reason);
            return true;
        }
        log.warn("Agent {} ({}) left BACKGROUND_ABSTRACT for {} without reconciling: {};"
                        + " scope={} windowMs={} failingMs={} unsupported={} baseline={} now={}",
                name, id, requestedMode, reason, abandonment.scope(),
                nowMs - abandonment.startedAtMs(),
                nowMs - abandonment.firstFailedReconciliationAtMs(),
                abandonment.unsupportedOutcome(),
                abandonment.materializedBaseline(),
                abandonment.currentState());
        return true;
    }
}
