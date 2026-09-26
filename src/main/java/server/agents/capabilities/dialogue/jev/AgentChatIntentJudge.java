package server.agents.capabilities.dialogue.jev;

import server.agents.integration.typesafe.JevChoicePermutations;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.JevRequest;
import server.agents.integration.typesafe.JevResponse;
import server.agents.integration.typesafe.state.JevState;

import java.util.Locale;
import java.util.Optional;

/**
 * Pure decision logic: given the state blocks for one unmatched chat message, ask System One and
 * turn the typed answers into an {@link Outcome}. No Cosmic types, no side effects, so the whole
 * policy is unit-testable with a fake transport.
 */
public final class AgentChatIntentJudge {
    private static final double ACT_CONFIDENCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.dialogue.jev.AgentChatIntentJudge.ACT_CONFIDENCE");
    private static final double ASK_CONFIDENCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.dialogue.jev.AgentChatIntentJudge.ASK_CONFIDENCE");
    private static final int STATE_BUDGET_TOKENS = config.AgentTuning.intValue(
            "server.agents.capabilities.dialogue.jev.AgentChatIntentJudge.STATE_BUDGET_TOKENS");

    private static final boolean HIERARCHICAL_ENABLED = config.AgentTuning.booleanValue(
            "server.agents.capabilities.dialogue.jev.AgentChatIntentJudge.HIERARCHICAL_ENABLED");

    public enum Outcome {
        /** Confident, actionable: re-dispatch {@link Decision#canonicalCommand()}. */
        ACT,
        /** Plausible but not confident: ask "did you mean ...?". */
        ASK,
        /** Chat, unaddressed, or unconfident: leave the message to the social fallback. */
        IGNORE,
        /** No judgment was obtained (unconfigured, breaker open, timeout, failure). */
        UNAVAILABLE
    }

    public record Decision(Outcome outcome,
                           AgentChatIntent intent,
                           String canonicalCommand,
                           double confidence,
                           double addressedProbability,
                           long latencyMs,
                           long inputTokens,
                           String droppedStateKeys) {
        public static Decision unavailable() {
            return new Decision(Outcome.UNAVAILABLE, null, null, 0.0d, 0.0d, 0L, 0L, "");
        }

        /** One line for shadow logs: {@code regex miss -> jev grind (0.82) ACT}. */
        public String summary() {
            return (intent == null ? "-" : intent.optionId())
                    + " conf=" + String.format(Locale.ROOT, "%.2f", confidence)
                    + " addressed=" + String.format(Locale.ROOT, "%.2f", addressedProbability)
                    + " -> " + outcome
                    + (canonicalCommand == null ? "" : " [" + canonicalCommand + "]")
                    + " " + latencyMs + "ms " + inputTokens + "tok"
                    + (droppedStateKeys.isEmpty() ? "" : " dropped=" + droppedStateKeys);
        }
    }

    private final boolean hierarchical;
    private final JevClient client;
    private final double actConfidence;
    private final double askConfidence;
    private final int stateBudgetTokens;

    public AgentChatIntentJudge(JevClient client) {
        this(client, ACT_CONFIDENCE, ASK_CONFIDENCE, STATE_BUDGET_TOKENS, HIERARCHICAL_ENABLED);
    }

    public AgentChatIntentJudge(JevClient client, double actConfidence, double askConfidence, int stateBudgetTokens) {
        this(client, actConfidence, askConfidence, stateBudgetTokens, false);
    }

    public AgentChatIntentJudge(JevClient client, double actConfidence, double askConfidence,
                               int stateBudgetTokens, boolean hierarchical) {
        this.hierarchical = hierarchical;
        this.client = client;
        this.actConfidence = actConfidence;
        this.askConfidence = Math.min(askConfidence, actConfidence);
        this.stateBudgetTokens = stateBudgetTokens;
    }

    /** Blocking; run it on the async gateway, never on a packet or mailbox thread. */
    public Decision judge(JevState state) {
        JevState.Built built = state.build(stateBudgetTokens);
        if (hierarchical) return judgeHierarchy(built);
        JevRequest request = AgentChatIntentCatalog.request(built.state());
        Optional<JevResponse> response = client.ask(request);
        if (response.isEmpty()) {
            return Decision.unavailable();
        }
        String droppedKeys = String.join(",", built.droppedKeys());
        Decision decision = decide(response.get(), droppedKeys);
        if (decision.outcome() != Outcome.ASK || client.permutationMode() == JevChoicePermutations.Mode.OFF) {
            return decision;
        }
        // Unsure: re-judge with option-order averaging, paying for it only on ambiguous messages.
        // SHADOW only feeds the permutation agreement stats; LIVE lets the averaged answer decide.
        Optional<JevResponse> averaged = client.ask(
                AgentChatIntentCatalog.unsureRequest(built.state(), decision.intent()));
        if (averaged.isEmpty() || client.permutationMode() != JevChoicePermutations.Mode.LIVE) {
            return decision;
        }
        Decision rejudged = decide(averaged.get(), droppedKeys);
        // A different intent may need an argument the narrow request did not carry; keep asking.
        return rejudged.intent() == decision.intent() ? rejudged : decision;
    }

    private Decision judgeHierarchy(JevState.Built built) {
        String dropped = String.join(",", built.droppedKeys());
        Optional<JevResponse> first = client.ask(AgentChatIntentCatalog.familyRequest(built.state()));
        if (first.isEmpty()) return Decision.unavailable();
        JevResponse route = first.get();
        var answer = route.answer("family").filter(a -> a.isChoice());
        if (answer.isEmpty()) return Decision.unavailable();
        AgentChatIntentFamily family = answer.get().mostLikelyOption()
                .map(AgentChatIntentFamily::fromOptionId).orElse(null);
        double confidence = answer.get().confidenceOrZero();
        if (family == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            return Decision.unavailable();
        }
        if (family == AgentChatIntentFamily.CHAT || confidence < askConfidence) {
            return new Decision(Outcome.IGNORE, AgentChatIntent.CHAT_NOT_A_COMMAND, null, confidence,
                    0, route.latencyMs(), route.inputTokens(), dropped);
        }
        Optional<JevResponse> second = client.ask(AgentChatIntentCatalog.branchRequest(built.state(), family));
        if (second.isEmpty()) {
            return new Decision(Outcome.UNAVAILABLE, null, null, 0, 0,
                    route.latencyMs(), route.inputTokens(), dropped);
        }
        JevResponse leaf = second.get();
        Decision decision = decide(leaf, dropped);
        if (decision.intent() != null && decision.intent().actionable() && !family.intents().contains(decision.intent())) {
            return new Decision(Outcome.UNAVAILABLE, null, null, 0, 0,
                    route.latencyMs() + leaf.latencyMs(), route.inputTokens() + leaf.inputTokens(), dropped);
        }
        // Never let a confident leaf hide an uncertain routing decision.
        double combined = Math.min(confidence, decision.confidence());
        Outcome outcome = decision.outcome();
        if (outcome == Outcome.ACT || outcome == Outcome.ASK) {
            outcome = combined >= actConfidence ? Outcome.ACT : combined >= askConfidence ? Outcome.ASK : Outcome.IGNORE;
        }
        return new Decision(outcome, decision.intent(), decision.canonicalCommand(), combined,
                decision.addressedProbability(), route.latencyMs() + leaf.latencyMs(),
                route.inputTokens() + leaf.inputTokens(), dropped);
    }

    Decision decide(JevResponse response, String droppedKeys) {
        Optional<AgentChatIntentCatalog.Resolution> resolved = AgentChatIntentCatalog.resolve(response);
        if (resolved.isEmpty()) {
            return new Decision(Outcome.IGNORE, null, null, 0.0d, 0.0d,
                    response.latencyMs(), response.inputTokens(), droppedKeys);
        }
        AgentChatIntentCatalog.Resolution resolution = resolved.get();
        Outcome outcome;
        if (!resolution.intent().actionable() || resolution.canonicalCommand() == null) {
            outcome = Outcome.IGNORE;
        } else if (resolution.confidence() >= actConfidence) {
            outcome = Outcome.ACT;
        } else if (resolution.confidence() >= askConfidence) {
            outcome = Outcome.ASK;
        } else {
            outcome = Outcome.IGNORE;
        }
        return new Decision(outcome, resolution.intent(), resolution.canonicalCommand(),
                resolution.confidence(), resolution.addressedProbability(),
                response.latencyMs(), response.inputTokens(), droppedKeys);
    }
}
