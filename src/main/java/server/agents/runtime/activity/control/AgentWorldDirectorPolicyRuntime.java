package server.agents.runtime.activity.control;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.control.proposal.AgentDirectorProposalService;
import server.agents.runtime.activity.control.proposal.AgentDirectorProposalSource;
import server.agents.runtime.activity.control.proposal.AgentDirectorProposalStatus;
import server.agents.runtime.activity.control.proposal.AgentFileDirectorProposalStore;
import server.agents.runtime.activity.control.rollout.AgentWorldDirectorRolloutConfigLoader;
import server.agents.runtime.activity.world.AgentFileWorldDirectiveInbox;
import server.agents.runtime.activity.world.AgentWorldDirectiveSource;
import server.agents.runtime.activity.world.AgentWorldDirectorMode;

import java.util.concurrent.atomic.LongAdder;

/**
 * The World Director's own choices (docs/SCALE_PLATFORM_PLAN.md section 4). Until this existed every directive was
 * OPERATOR, so ASSISTED acted like MANUAL and AUTONOMOUS never acted. After each Observe sample in ASSISTED or
 * AUTONOMOUS mode: once the Director has chosen the same activity switch for POLICY_STABLE_SAMPLES samples running,
 * with no directive in flight,
 * <ul>
 *   <li>ASSISTED (and the Agent in the assisted canary cohort): the choice becomes a POLICY proposal; an operator's
 *   approval issues it as a POLICY directive, which the assisted gate judges (cohort, targets, capacity, rollback).</li>
 *   <li>AUTONOMOUS (and the Agent in the autonomous cohort): the choice is issued as a POLICY directive with no
 *   approval; the autonomous gate judges it (Observe evidence, recent Director failures, capacity, targets, rollback)
 *   and it expires after POLICY_DIRECTIVE_TTL_MS if the gate never lets it through.</li>
 * </ul>
 * Either way the policy then keeps quiet for POLICY_QUIET_MS. Nothing here runs for an Agent without a Director
 * session in one of those modes, and both rollout switches ship closed.
 */
public final class AgentWorldDirectorPolicyRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentWorldDirectorPolicyRuntime.class);
    static final long STABLE_SAMPLES = config.AgentTuning.longValue(
            "server.agents.runtime.activity.control.AgentWorldDirectorPolicyRuntime.POLICY_STABLE_SAMPLES");
    static final long QUIET_MS = config.AgentTuning.longValue(
            "server.agents.runtime.activity.control.AgentWorldDirectorPolicyRuntime.POLICY_QUIET_MS");
    static final long DIRECTIVE_TTL_MS = config.AgentTuning.longValue(
            "server.agents.runtime.activity.control.AgentWorldDirectorPolicyRuntime.POLICY_DIRECTIVE_TTL_MS");
    static final long FAILURE_WINDOW_MS = config.AgentTuning.longValue(
            "server.agents.runtime.activity.control.AgentWorldDirectorPolicyRuntime.FAILURE_WINDOW_MS");

    private static final LongAdder PROPOSED = new LongAdder();
    private static final LongAdder SUBMITTED = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();

    private AgentWorldDirectorPolicyRuntime() { }

    /** What the policy does after a sample. */
    enum Step { WAIT, PROPOSE, SUBMIT }

    /** The pure decision: mode, the sample evidence, quiet time and whether the Agent is in the mode's cohort. */
    static Step next(AgentWorldDirectorMode mode, AgentWorldDirectorObserveState.Snapshot observe,
                     long stableNeeded, boolean quiet, boolean cohort) {
        if (mode == null || observe == null || quiet || !cohort || observe.stableSamples() < Math.max(1L, stableNeeded)
                || observe.selectedKind() == null || observe.selectedProposalId().isEmpty()) {
            return Step.WAIT;
        }
        return switch (mode) {
            case ASSISTED -> Step.PROPOSE;
            case AUTONOMOUS -> Step.SUBMIT;
            default -> Step.WAIT;
        };
    }

    /** The Director action a sample's choice maps to (AgentDirectorActionCatalog lists proposals this way). */
    static String actionId(String proposalId) {
        return "proposal:" + proposalId;
    }

    /** Called on the Agent's own tick right after an Observe sample. */
    static void afterSample(AgentRuntimeEntry entry, Character agent, AgentWorldDirectorObserveState state, long nowMs) {
        AgentWorldDirectorObserveState.Snapshot observe = state.snapshot();
        AgentWorldDirectorMode mode = entry.capabilityStates()
                .require(AgentWorldDirectorRuntimeState.STATE_KEY).snapshot().mode();
        if (!mode.allowsAutomaticProposals() || observe.stableSamples() < Math.max(1L, STABLE_SAMPLES)) {
            return; // the common case: nothing to read from disk
        }
        Step step = next(mode, observe, STABLE_SAMPLES, state.policyQuiet(nowMs), cohort(mode, agent.getId()));
        if (step == Step.WAIT) {
            return;
        }
        state.policyActed(nowMs, QUIET_MS); // whatever happens below, a fresh run of samples comes first
        try {
            if (Holder.DIRECTIVES.list(agent.getId()).stream().anyMatch(envelope -> !envelope.status().terminal())) {
                SKIPPED.increment();
                return; // a directive is in flight: the Director waits for it
            }
            AgentDirectorExecutiveView view = Holder.EXECUTIVE.view(entry, agent, 1, nowMs);
            String actionId = actionId(observe.selectedProposalId());
            AgentDirectorAction action = view.actions().stream()
                    .filter(candidate -> candidate.actionId().equals(actionId)).findFirst().orElse(null);
            if (action == null || !action.availability().executable()) {
                SKIPPED.increment();
                log.debug("World Director policy: agent {} keeps {} ({} not executable: {})", agent.getId(),
                        view.context().currentActivityKind(), actionId, action == null ? "no such action" : action.reason());
                return;
            }
            String why = "Director policy: chose " + observe.selectedKind() + " for " + observe.stableSamples()
                    + " samples running (" + observe.evidence() + ")";
            if (step == Step.PROPOSE) {
                boolean pending = Holder.PROPOSALS.list(agent.getId(), nowMs).stream().anyMatch(proposal ->
                        proposal.source() == AgentDirectorProposalSource.POLICY
                                && proposal.status() == AgentDirectorProposalStatus.PENDING);
                if (pending) {
                    SKIPPED.increment();
                    return; // the operator has not answered the last one yet
                }
                var proposal = Holder.PROPOSALS.propose(view, actionId, AgentDirectorProposalSource.POLICY, why, 0, nowMs);
                PROPOSED.increment();
                log.info("World Director policy: agent {} proposes {} (proposal {}, awaiting approval)",
                        agent.getId(), action.label(), proposal.proposalId());
            } else {
                String directiveId = "policy:" + agent.getId() + ':' + observe.selectedProposalId() + ':'
                        + observe.sampleCount();
                Holder.EXECUTIVE.submit(entry, agent, actionId, view.contextRevision(), directiveId, why, false,
                        AgentWorldDirectiveSource.POLICY, nowMs + DIRECTIVE_TTL_MS, nowMs);
                SUBMITTED.increment();
                log.info("World Director policy: agent {} starts {} (directive {})", agent.getId(), action.label(),
                        directiveId);
            }
        } catch (RuntimeException failure) {
            SKIPPED.increment();
            log.warn("World Director policy step failed for agent {}: {}", agent.getId(), failure.getMessage());
        }
    }

    private static boolean cohort(AgentWorldDirectorMode mode, int agentId) {
        if (mode == AgentWorldDirectorMode.ASSISTED) {
            var assisted = AgentWorldDirectorRolloutConfigLoader.assisted();
            return assisted.assistedEnabled() && assisted.allowedAgentIds().contains(agentId);
        }
        var autonomous = AgentWorldDirectorRolloutConfigLoader.autonomous();
        return autonomous.enabled() && autonomous.includes(agentId);
    }

    /** POLICY proposals made, POLICY directives submitted, and policy steps that found nothing to do, since boot. */
    public static long proposedCount() {
        return PROPOSED.sum();
    }

    public static long submittedCount() {
        return SUBMITTED.sum();
    }

    public static long skippedCount() {
        return SKIPPED.sum();
    }

    /** Built on first use: only a server with a Director session in ASSISTED or AUTONOMOUS mode touches these. */
    private static final class Holder {
        static final AgentWorldDirectorExecutive EXECUTIVE = AgentWorldDirectorExecutive.runtimeDefault();
        static final AgentFileWorldDirectiveInbox DIRECTIVES = AgentFileWorldDirectiveInbox.runtimeDefault();
        static final AgentDirectorProposalService PROPOSALS =
                new AgentDirectorProposalService(AgentFileDirectorProposalStore.runtimeDefault());
    }
}
