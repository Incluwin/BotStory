package server.agents.capabilities.dialogue.jev;

import client.Character;
import config.AgentYamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.jev.AgentChatIntentJudge.Decision;
import server.agents.capabilities.dialogue.jev.AgentChatIntentJudge.Outcome;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.cosmic.typesafe.AgentChatJevStateFactory;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.JevJudgmentMode;
import server.agents.integration.typesafe.state.JevState;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.async.AgentAsyncTaskGateway;
import server.agents.runtime.async.AgentAsyncWorkKind;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Runs the chat intent judge for a message none of the deterministic classifiers matched, on the
 * Agent async gateway, and (in LIVE mode) re-dispatches the canonical command or asks the player.
 * In SHADOW mode it only logs {@code regex miss vs. Jev decision}, which is how thresholds get tuned.
 */
public final class AgentChatIntentRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentChatIntentRuntime.class);
    private static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime.TIMEOUT_MS");
    private static final String REQUEST_KEY = "typesafe-chat-intent";
    private static volatile AgentChatIntentJudge judge;

    private AgentChatIntentRuntime() {
    }

    @FunctionalInterface
    public interface Redispatch {
        CompletionStage<Boolean> handle(AgentRuntimeEntry entry, String commandText, AgentReplyChannel channel);
    }

    @FunctionalInterface
    public interface ReplyQueue {
        void queue(AgentRuntimeEntry entry, String reply);
    }

    public static JevJudgmentMode mode() {
        return JevJudgmentMode.parse(AgentYamlConfig.config.agent.AGENT_TYPESAFE_CHAT_INTENT_MODE);
    }

    /** Test seam. */
    public static void installJudge(AgentChatIntentJudge replacement) {
        judge = replacement;
    }

    /**
     * Called only after the deterministic dispatcher reported {@code false}. Completes with
     * {@code true} when the judgment handled the message (acted or asked), otherwise {@code false}
     * so the existing social fallback keeps its turn.
     */
    public static CompletionStage<Boolean> judgeUnmatched(AgentRuntimeEntry entry,
                                                           String message,
                                                           AgentReplyChannel channel,
                                                           Redispatch redispatch,
                                                           ReplyQueue replies) {
        JevJudgmentMode mode = mode();
        if (!mode.asks() || entry == null || message == null || message.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        JevClient client = JevClient.runtime();
        if (!client.available()) {
            return CompletableFuture.completedFuture(false);
        }
        long nowMs = System.currentTimeMillis();
        Character speaker = entry.owner();
        JevState state = AgentChatJevStateFactory.forCommand(entry, speaker, message, channel, nowMs);
        AgentChatIntentJudge activeJudge = judge();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        AgentAsyncTaskGateway.Submission submission = AgentAsyncTaskGateway.runtime().submit(
                entry,
                AgentAsyncWorkKind.SYSTEM_ONE_NETWORK,
                REQUEST_KEY,
                TIMEOUT_MS,
                () -> activeJudge.judge(state),
                (completionEntry, completion) -> {
                    if (!completion.succeeded() || !(completion.result() instanceof Decision decision)) {
                        log.debug("[typesafe-chat] mode={} status={} message={}", mode, completion.status(), message);
                        result.complete(false);
                        return;
                    }
                    log.info("[typesafe-chat] mode={} message=\"{}\" {}", mode, message, decision.summary());
                    if (!mode.acts() || decision.outcome() == Outcome.UNAVAILABLE) {
                        result.complete(false);
                        return;
                    }
                    apply(completionEntry, channel, decision, redispatch, replies, result);
                });
        if (!submission.accepted()) {
            return CompletableFuture.completedFuture(false);
        }
        return result;
    }

    private static void apply(AgentRuntimeEntry entry,
                              AgentReplyChannel channel,
                              Decision decision,
                              Redispatch redispatch,
                              ReplyQueue replies,
                              CompletableFuture<Boolean> result) {
        switch (decision.outcome()) {
            case ACT -> redispatch.handle(entry, decision.canonicalCommand(), channel)
                    .whenComplete((handled, failure) ->
                            result.complete(failure == null && Boolean.TRUE.equals(handled)));
            case ASK -> {
                replies.queue(entry, "did you mean '" + decision.canonicalCommand() + "'?");
                result.complete(true);
            }
            default -> result.complete(false);
        }
    }

    private static AgentChatIntentJudge judge() {
        AgentChatIntentJudge current = judge;
        if (current == null) {
            synchronized (AgentChatIntentRuntime.class) {
                current = judge;
                if (current == null) {
                    current = new AgentChatIntentJudge(JevClient.runtime());
                    judge = current;
                }
            }
        }
        return current;
    }
}
