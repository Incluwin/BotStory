package server.agents.social.conversation;

import client.Character;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.ollama.OllamaDialogueProvider;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.social.config.SocialDialogueSettings;
import server.agents.social.contracts.ConversationTurn;
import server.agents.social.contracts.DialogueMode;
import server.agents.social.memory.SocialMemoryPersistenceRuntime;
import server.agents.social.memory.SocialCounterpartyType;
import server.agents.social.provider.DeterministicDialogueProvider;
import server.agents.social.provider.DialogueProvider;

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
        int speakerId = speaker.getId();
        String speakerName = speaker.getName();
        SocialCounterpartyType counterpartyType = AgentCharacterGatewayRuntime.characters()
                .isHeadlessControlled(speaker)
                ? SocialCounterpartyType.AGENT
                : SocialCounterpartyType.PLAYER;
        String normalized = message.trim();
        AgentMailboxRuntime.dispatch(entry, ignored -> {
            prepareAndSubmit(entry, speakerId, speakerName, counterpartyType, normalized);
            return null;
        });
    }

    private static void prepareAndSubmit(
            AgentRuntimeEntry entry,
            int speakerId,
            String speakerName,
            SocialCounterpartyType counterpartyType,
            String message) {
        long nowMs = System.currentTimeMillis();
        AgentSocialDialogueRequestFactory.PreparedDialogue prepared;
        try {
            prepared = REQUESTS.prepare(
                    entry, speakerId, speakerName, message, counterpartyType, nowMs);
        } catch (IllegalArgumentException ignored) {
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
                server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY,
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
}
