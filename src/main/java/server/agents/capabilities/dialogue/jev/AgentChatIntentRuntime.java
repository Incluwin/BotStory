package server.agents.capabilities.dialogue.jev;

import client.Character;
import config.AgentYamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentPendingActionStateRuntime;
import server.agents.capabilities.dialogue.jev.AgentChatIntentJudge.Decision;
import server.agents.capabilities.dialogue.jev.AgentChatIntentJudge.Outcome;
import server.agents.capabilities.trade.AgentOfferService;
import server.agents.capabilities.trade.AgentOfferStateRuntime;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.cosmic.typesafe.AgentChatJevStateFactory;
import server.agents.integration.typesafe.JevClient;
import server.agents.integration.typesafe.JevJudgmentMode;
import server.agents.integration.typesafe.state.JevState;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.async.AgentAsyncTaskGateway;
import server.agents.runtime.async.AgentAsyncWorkKind;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Runs the chat intent judge for a message none of the deterministic classifiers matched, on the
 * Agent async gateway, and (in LIVE mode) re-dispatches the canonical command or asks the player.
 * In SHADOW mode it only logs {@code regex miss vs. Jev decision}, which is how thresholds get tuned.
 */
public final class AgentChatIntentRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentChatIntentRuntime.class);
    private static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime.TIMEOUT_MS");
    private static final long PENDING_ASK_TTL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.dialogue.jev.AgentChatIntentRuntime.PENDING_ASK_TTL_MS");
    /** GM commands ("!warp ...", including mistyped ones) are never companion instructions. */
    private static final Pattern GM_COMMAND = Pattern.compile("(^|\\s)![A-Za-z]");
    private static final AgentChatPendingAsks PENDING_ASKS = new AgentChatPendingAsks(PENDING_ASK_TTL_MS);
    private static final String REQUEST_KEY = "typesafe-chat-intent";
    private static final String REPLY_PREFIX = "reply:";
    private static volatile AgentChatIntentJudge judge;
    private static volatile Prejudge prejudge;
    private static volatile Learner learner;
    private static volatile ContentGate contentGate;

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

    /**
     * Deterministic resolver consulted before the judge (phrase packs, learned phrases). Returns the
     * canonical command to re-dispatch, {@code reply:<text>} to answer without dispatching, or null.
     */
    @FunctionalInterface
    public interface Prejudge {
        String resolve(String message);
    }

    /**
     * Receives every LIVE ACT the judge produced, and every "did you mean" the player confirmed
     * ({@code confirmedByPlayer}), so phrases can be remembered.
     */
    @FunctionalInterface
    public interface Learner {
        void learned(String message, String canonicalCommand, double confidence, boolean confirmedByPlayer);
    }

    public static void installPrejudge(Prejudge replacement) {
        prejudge = replacement;
    }

    public static void installLearner(Learner replacement) {
        learner = replacement;
    }

    /**
     * Lines a host answers from its own knowledge (server knowledge: "who drops steelies", or the owner's reply to the
     * agent's own ask-back "blue or red snail?"). Consulted after pending asks and the prejudge, so phrase packs still
     * resolve first; true skips the judge and leaves the line to the social reply, saving one Jev call per companion.
     */
    @FunctionalInterface
    public interface ContentGate {
        boolean answers(Character agent, Character speaker, String message);
    }

    public static void installContentGate(ContentGate replacement) {
        contentGate = replacement;
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
     * so the existing social fallback keeps its turn. Commands the orchestrator carried out but
     * reports as unhandled (its legacy fall-through) are never judged.
     */
    public static CompletionStage<Boolean> judgeUnmatched(AgentRuntimeEntry entry,
                                                           String message,
                                                           AgentReplyChannel channel,
                                                           Redispatch redispatch,
                                                           ReplyQueue replies) {
        return judgeUnmatched(entry, AgentChatPendingAsks.ANY_ASKER_ID, message, channel, redispatch, replies);
    }

    /**
     * {@link #judgeUnmatched(AgentRuntimeEntry, String, AgentReplyChannel, Redispatch, ReplyQueue)} for a line
     * {@code speakerId} said: it answers only a "did you mean" the Agent asked that speaker, and one it asks now waits
     * for that speaker's answer (third deploy review 2026-10-10). -1: anyone's.
     */
    public static CompletionStage<Boolean> judgeUnmatched(AgentRuntimeEntry entry,
                                                           int speakerId,
                                                           String message,
                                                           AgentReplyChannel channel,
                                                           Redispatch redispatch,
                                                           ReplyQueue replies) {
        JevJudgmentMode mode = mode();
        if (entry == null || message == null || message.isBlank()
                || AgentChatFallThroughCommands.handledDeterministically(message)
                || GM_COMMAND.matcher(message).find()) {
            return CompletableFuture.completedFuture(false);
        }
        AgentChatPendingAsks.Resolution answer =
                PENDING_ASKS.resolve(entry, speakerId, message, System.currentTimeMillis());
        if (answer.reply() == AgentChatPendingAsks.Reply.CONFIRMED) {
            String canonical = answer.pending().canonicalCommand();
            log.info("[typesafe-chat] confirmed \"{}\" -> {}", answer.pending().message(), canonical);
            return redispatch.handle(entry, canonical, channel).thenApply(handled -> {
                boolean acted = actHandled(canonical, handled);
                Learner memory = learner;
                if (acted && memory != null) {
                    memory.learned(answer.pending().message(), canonical, 1.0d, true);
                }
                return acted;
            });
        }
        if (answer.reply() == AgentChatPendingAsks.Reply.DECLINED) {
            replies.queue(entry, "okay, never mind.");
            return CompletableFuture.completedFuture(true);
        }
        AgentChatPendingAsks.Pending dropped = answer.pending();
        Prejudge resolver = prejudge;
        if (resolver != null) {
            String canonical = resolver.resolve(message);
            if (canonical != null && canonical.startsWith(REPLY_PREFIX)) {
                replies.queue(entry, canonical.substring(REPLY_PREFIX.length()));
                return CompletableFuture.completedFuture(true);
            }
            if (canonical != null && !canonical.isBlank()) {
                log.info("[phrase] message=\"{}\" -> {}", message, canonical);
                return redispatch.handle(entry, canonical, channel)
                        .thenApply(handled -> actHandled(canonical, handled));
            }
        }
        ContentGate content = contentGate;
        if (content != null && content.answers(entry.bot(), entry.owner(), message)) {
            return CompletableFuture.completedFuture(false);
        }
        if (!mode.asks()) {
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
                    apply(completionEntry, speakerId, channel, message, decision, dropped, redispatch, replies, result);
                });
        if (!submission.accepted()) {
            return CompletableFuture.completedFuture(false);
        }
        return result;
    }

    /**
     * {@code dropped} is the "did you mean" this message replaced without a yes or no. If the
     * message asks for the same command again ("yeah, bump my reputation up pls" after "did you
     * mean 'fame me'?"), the restatement is the confirmation: act instead of asking twice.
     */
    static void apply(AgentRuntimeEntry entry,
                      AgentReplyChannel channel,
                      String message,
                      Decision decision,
                      AgentChatPendingAsks.Pending dropped,
                      Redispatch redispatch,
                      ReplyQueue replies,
                      CompletableFuture<Boolean> result) {
        apply(entry, AgentChatPendingAsks.ANY_ASKER_ID, channel, message, decision, dropped, redispatch, replies,
                result);
    }

    /** {@code speakerId}: who said {@code message}, whose answer an ask waits for. */
    static void apply(AgentRuntimeEntry entry,
                      int speakerId,
                      AgentReplyChannel channel,
                      String message,
                      Decision decision,
                      AgentChatPendingAsks.Pending dropped,
                      Redispatch redispatch,
                      ReplyQueue replies,
                      CompletableFuture<Boolean> result) {
        boolean restated = dropped != null && decision.outcome() == Outcome.ASK
                && dropped.canonicalCommand().equals(decision.canonicalCommand());
        switch (decision.outcome()) {
            case ACT, ASK -> {
                if (decision.outcome() == Outcome.ASK && !restated) {
                    rememberAsk(entry, speakerId, message, decision.canonicalCommand());
                    replies.queue(entry, askText(decision.canonicalCommand()));
                    result.complete(true);
                    return;
                }
                redispatch.handle(entry, decision.canonicalCommand(), channel)
                        .whenComplete((handled, failure) -> {
                            boolean acted = failure == null && actHandled(decision.canonicalCommand(), handled);
                            Learner memory = learner;
                            if (acted && memory != null && restated) {
                                memory.learned(dropped.message(), decision.canonicalCommand(), 1.0d, true);
                                memory.learned(message, decision.canonicalCommand(), 1.0d, true);
                            } else if (acted && memory != null) {
                                memory.learned(message, decision.canonicalCommand(), decision.confidence(), false);
                            }
                            result.complete(acted);
                        });
            }
            default -> result.complete(false);
        }
    }

    /** The clarifying question in a player's voice for everyday commands; the rest keep the literal form. */
    static String askText(String canonicalCommand) {
        return switch (canonicalCommand) {
            case "follow me" -> "u want me to follow u?";
            case "stop" -> "u want me to stop?";
            case "move here" -> "u want me to come to u?";
            case "gather up" -> "u want everyone to gather here?";
            case "gather up for buffs" -> "u want everyone over here for buffs?";
            case "grind" -> "u want me to go hunt?";
            case "patrol" -> "u want me to hunt around this spot?";
            case "farm here" -> "u want me to farm right here?";
            case "fame me" -> "u want a fame?";
            case "logout" -> "u want me to log off?";
            default -> "did you mean '" + canonicalCommand + "'?";
        };
    }

    /**
     * A canonical command the orchestrator carries out but reports as unhandled (its legacy
     * fall-through) still counts as acted on, so the owner-command record is updated and the
     * social small-talk fallback stays quiet. The whisper route asks the same of the whispered line.
     */
    public static boolean actHandled(String canonicalCommand, Boolean handled) {
        return Boolean.TRUE.equals(handled) || AgentChatFallThroughCommands.handledDeterministically(canonicalCommand);
    }

    /**
     * True when {@code line} is a plain yes or no and an Agent {@code speaker} leads waits on their answer: a "did you
     * mean ...?" it asked them, a question of its own ("log off?"), or an offer to them. The living world's nearest
     * Resident leaves such a line to them (found 2026-09-23, "did you mean X?" then "yes" did nothing: in town the
     * Resident beside the owner took the "yes" as accepting its quest, and the Agents never saw it).
     */
    public static boolean answersOwnAgent(Character speaker, String line) {
        if (speaker == null || line == null || !AgentChatPendingAsks.isAnswer(line)) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        for (AgentRuntimeEntry entry : AgentRuntimeRegistry.entriesForLeader(speaker.getId())) {
            if (PENDING_ASKS.waitsOn(entry, speaker.getId(), nowMs)
                    || AgentPendingActionStateRuntime.hasPendingAction(entry)
                    || AgentOfferService.offerAwaitsAnswer(entry) && AgentOfferStateRuntime.pendingOfferRecipientIs(entry, speaker)) {
                return true;
            }
        }
        return false;
    }

    /** The owner's next message can confirm or decline this suggestion; see {@link AgentChatPendingAsks}. */
    static void rememberAsk(AgentRuntimeEntry entry, String message, String canonicalCommand) {
        rememberAsk(entry, AgentChatPendingAsks.ANY_ASKER_ID, message, canonicalCommand);
    }

    /** Only {@code askerId}'s next message can confirm or decline it. */
    static void rememberAsk(AgentRuntimeEntry entry, int askerId, String message, String canonicalCommand) {
        PENDING_ASKS.remember(entry, askerId, message, canonicalCommand, System.currentTimeMillis());
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
