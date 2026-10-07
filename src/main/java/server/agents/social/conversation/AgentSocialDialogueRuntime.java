package server.agents.social.conversation;

import client.Character;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.ollama.OllamaDialogueProvider;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.social.config.SocialDialogueSettings;
import server.agents.social.contracts.ConversationTurn;
import server.agents.social.contracts.DialogueMode;
import server.agents.social.memory.SocialMemoryPersistenceRuntime;
import server.agents.social.memory.SocialCounterpartyType;
import server.agents.social.provider.DeterministicDialogueProvider;
import server.agents.social.provider.DialogueProvider;

import java.util.List;

/** Live entry point for optional generic social replies. */
public final class AgentSocialDialogueRuntime {
    private static final SocialDialogueSettings SETTINGS = SocialDialogueSettings.runtime();
    private static final DialogueProvider MODEL_PROVIDER = new OllamaDialogueProvider(SETTINGS);
    private static final AgentSocialDialogueService SERVICE = new AgentSocialDialogueService(
            SETTINGS.mode(), MODEL_PROVIDER, new DeterministicDialogueProvider());
    private static final AgentSocialDialogueApplication APPLICATION = new AgentSocialDialogueApplication(
            SERVICE);
    private static final AgentSocialDialogueRequestFactory REQUESTS =
            new AgentSocialDialogueRequestFactory(SETTINGS);

    private static volatile Styler styler;
    private static volatile Answerer answerer;

    /** A styled reply: the text to say and how long to "type" it; null leaves the reply untouched. */
    public record Styled(String text, long delayMs) { }

    /** Host-supplied transform for generated replies (voice packs). Return null to keep the default path. */
    @FunctionalInterface
    public interface Styler {
        Styled style(int agentCharacterId, int speakerCharacterId, String text, boolean fromModel);
    }

    public static void installStyler(Styler replacement) {
        styler = replacement;
    }

    /**
     * Host-supplied deterministic answers (server knowledge content questions), consulted on the mailbox after the
     * request is prepared and before the model: the lines to say, each with its delay from now; an empty list when
     * the host deliberately says nothing; null leaves the line to the model.
     */
    @FunctionalInterface
    public interface Answerer {
        List<Styled> answer(Character agent, Character speaker, String message, String styleId);
    }

    public static void installAnswerer(Answerer replacement) {
        answerer = replacement;
    }

    private AgentSocialDialogueRuntime() {
    }

    public static boolean enabled() {
        return true;
    }

    public static DialogueMode mode() {
        return SERVICE.mode();
    }

    public static void setMode(DialogueMode mode) {
        SERVICE.setMode(mode);
    }

    public static void clearAgentRuntimeState(int agentId) {
        AgentSocialDialogueApplication.clearAgentRuntimeState(agentId);
        SocialMemoryPersistenceRuntime.clearAgentRuntimeState(agentId);
    }

    public static void maybeRespond(AgentRuntimeEntry entry, Character speaker, String message) {
        if (entry == null || speaker == null || message == null || message.isBlank()) {
            return;
        }
        String speakerName = speaker.getName();
        SocialCounterpartyType counterpartyType = AgentCharacterGatewayRuntime.characters()
                .isHeadlessControlled(speaker)
                ? SocialCounterpartyType.AGENT
                : SocialCounterpartyType.PLAYER;
        String normalized = message.trim();
        AgentMailboxRuntime.dispatch(entry, ignored -> {
            prepareAndSubmit(entry, speaker, speakerName, counterpartyType, normalized);
            return null;
        });
    }

    private static void prepareAndSubmit(
            AgentRuntimeEntry entry,
            Character speaker,
            String speakerName,
            SocialCounterpartyType counterpartyType,
            String message) {
        long nowMs = System.currentTimeMillis();
        int speakerId = speaker.getId();
        AgentSocialDialogueRequestFactory.PreparedDialogue prepared;
        try {
            prepared = REQUESTS.prepare(
                    entry, speakerId, speakerName, message, counterpartyType, nowMs);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        List<Styled> answer = answer(entry, speaker, message, prepared);
        if (answer != null) {
            deliverAnswer(entry, speaker, prepared, answer);
            return;
        }
        APPLICATION.submit(entry, prepared.request(), result -> {
            String shown = result.displayText();
            Styler transform = styler;
            Styled styled = transform == null ? null : transform.style(prepared.request().context().agentId(), speakerId,
                    shown, result.source() == server.agents.social.contracts.DialogueResult.Source.MODEL);
            if (styled == null) {
                AgentDialogueTransportRuntime.replyNow(entry, shown);
            } else {
                shown = styled.text();
                String finalText = shown;
                AgentSchedulerRuntime.scheduleLowPriority(
                        () -> AgentDialogueTransportRuntime.replyNow(entry, finalText), Math.max(1L, styled.delayMs()));
            }
            long repliedAtMs = System.currentTimeMillis();
            ConversationTurn agentTurn = new ConversationTurn(
                    ConversationTurn.Role.AGENT,
                    prepared.request().context().agentName(),
                    shown,
                    repliedAtMs);
            SocialMemoryPersistenceRuntime.recordConversation(
                    prepared.relationshipKey(),
                    prepared.sessionId(),
                    prepared.speakerId(),
                    prepared.speakerTurn(),
                    agentTurn,
                    repliedAtMs);
        });
    }

    private static List<Styled> answer(AgentRuntimeEntry entry, Character speaker, String message,
                                       AgentSocialDialogueRequestFactory.PreparedDialogue prepared) {
        Answerer host = answerer;
        if (host == null) {
            return null;
        }
        try {
            return host.answer(entry.bot(), speaker, message, prepared.request().context().style().styleId());
        } catch (RuntimeException ignored) {
            return null; // the model keeps the turn
        }
    }

    /**
     * A host's answer goes out like a styled model reply (typing delay, same channel) and is remembered the same way;
     * a line still waiting when the asker has changed maps is dropped (they left before it was typed).
     */
    private static void deliverAnswer(AgentRuntimeEntry entry, Character speaker,
                                      AgentSocialDialogueRequestFactory.PreparedDialogue prepared, List<Styled> lines) {
        if (lines.isEmpty()) {
            return;
        }
        int askedOn = speaker.getMapId();
        StringBuilder said = new StringBuilder();
        for (Styled line : lines) {
            String text = line.text();
            AgentSchedulerRuntime.scheduleLowPriority(() -> {
                if (speaker.getMapId() == askedOn) {
                    AgentDialogueTransportRuntime.replyNow(entry, text);
                }
            }, Math.max(1L, line.delayMs()));
            said.append(said.isEmpty() ? "" : " ").append(text);
        }
        long repliedAtMs = System.currentTimeMillis();
        SocialMemoryPersistenceRuntime.recordConversation(
                prepared.relationshipKey(),
                prepared.sessionId(),
                prepared.speakerId(),
                prepared.speakerTurn(),
                new ConversationTurn(ConversationTurn.Role.AGENT, prepared.request().context().agentName(), said.toString(),
                        repliedAtMs),
                repliedAtMs);
    }
}
