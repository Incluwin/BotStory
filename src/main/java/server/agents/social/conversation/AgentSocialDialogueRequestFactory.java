package server.agents.social.conversation;

import server.agents.social.projection.AgentSocialContextProjectionRuntime;
import server.agents.context.AgentContextRuntime;
import server.agents.context.AgentContextSnapshot;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.social.config.SocialDialogueSettings;
import server.agents.social.contracts.ConversationTurn;
import server.agents.social.contracts.DialogueContextSnapshot;
import server.agents.social.contracts.DialogueRequest;
import server.agents.social.contracts.DialogueStyleSnapshot;
import server.agents.social.memory.SocialCounterpartyType;
import server.agents.social.memory.SocialMemoryPersistenceRuntime;
import server.agents.social.memory.SocialMemorySnapshot;
import server.agents.social.memory.SocialRelationshipKey;
import server.agents.social.personality.DialogueStyleProfileRepository;
import server.agents.behavior.AgentBehaviorAdaptationPersistenceRuntime;
import server.agents.runtime.activity.AgentActivityHostState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Builds model-safe dialogue requests from immutable Agent OS projections. */
public final class AgentSocialDialogueRequestFactory {
    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong();
    private static final String LEGACY_GROUNDING = "legacy";

    /**
     * Host-supplied facts for the model prompt (persona card, journal, scene). Keys become
     * labelled lines under "Public context"; values are truncated to the snapshot's fact limit.
     * Kept for single-hook hosts; new hosts register a {@link GroundingRegistry.Provider}, which also sees the line.
     */
    @FunctionalInterface
    public interface Grounding {
        Map<String, String> facts(int agentCharacterId, int speakerCharacterId);
    }

    /** The legacy single hook, now the registry's {@code "legacy"} provider; null removes it. */
    public static void installGrounding(Grounding replacement) {
        GroundingRegistry.register(LEGACY_GROUNDING, replacement == null ? null
                : request -> replacement.facts(request.agentCharacterId(), request.speakerCharacterId()));
    }

    private final SocialDialogueSettings settings;
    private final GenericDialogueIntentClassifier intentClassifier;
    private final DialogueFallbackCatalog fallbackCatalog;
    private final DialogueStyleProfileRepository styles;

    public AgentSocialDialogueRequestFactory(SocialDialogueSettings settings) {
        this(settings, new GenericDialogueIntentClassifier(), DialogueFallbackCatalog.defaultCatalog(),
                DialogueStyleProfileRepository.defaultRepository());
    }

    AgentSocialDialogueRequestFactory(
            SocialDialogueSettings settings,
            GenericDialogueIntentClassifier intentClassifier,
            DialogueFallbackCatalog fallbackCatalog,
            DialogueStyleProfileRepository styles) {
        if (settings == null || intentClassifier == null || fallbackCatalog == null || styles == null) {
            throw new IllegalArgumentException("Dialogue request factory dependencies are required");
        }
        this.settings = settings;
        this.intentClassifier = intentClassifier;
        this.fallbackCatalog = fallbackCatalog;
        this.styles = styles;
    }

    public PreparedDialogue prepare(
            AgentRuntimeEntry entry,
            int speakerId,
            String speakerName,
            String speakerText,
            long nowMs) {
        return prepare(entry, speakerId, speakerName, speakerText,
                SocialCounterpartyType.PLAYER, nowMs);
    }

    public PreparedDialogue prepare(
            AgentRuntimeEntry entry,
            int speakerId,
            String speakerName,
            String speakerText,
            SocialCounterpartyType counterpartyType,
            long nowMs) {
        AgentContextSnapshot identity = AgentContextRuntime.snapshot(entry);
        if (identity.characterId() <= 0 || speakerId <= 0 || speakerId == identity.characterId()) {
            throw new IllegalArgumentException("Distinct Agent and speaker identities are required");
        }
        String personalityId = identity.personalityProfileId().isBlank()
                ? "relaxed-v1"
                : identity.personalityProfileId();
        DialogueStyleSnapshot style = styles.resolve(personalityId).snapshot();
        SocialRelationshipKey relationshipKey = new SocialRelationshipKey(
                identity.characterId(), counterpartyType, speakerId);
        SocialMemorySnapshot memory = SocialMemoryPersistenceRuntime.snapshot(relationshipKey, nowMs);
        var operational = AgentSocialContextProjectionRuntime.snapshot(entry);
        String intentKey = intentClassifier.classify(speakerText);
        String requestId = identity.characterId() + ":" + speakerId + ":"
                + NEXT_REQUEST_ID.incrementAndGet();
        String sessionId = "chat:" + identity.characterId() + ':' + speakerId;
        DialogueContextSnapshot context = new DialogueContextSnapshot(
                identity.characterId(),
                operational.revision(),
                identity.characterName(),
                personalityId,
                style,
                activitySummary(operational.facts()),
                memory.relationship().summary(),
                AgentBehaviorAdaptationPersistenceRuntime.observe(
                        entry, identity.characterId(), entry.capabilityStates()
                                .find(AgentActivityHostState.STATE_KEY)
                                .map(AgentActivityHostState::activityKind).orElse(null),
                        nowMs).energyPercent(),
                publicFacts(operational.facts(), new GroundingRequest(identity.characterId(), speakerId, speakerName,
                        speakerText, intentKey, mapId(operational.facts()), operational.facts())));
        DialogueRequest request = new DialogueRequest(
                requestId,
                intentKey,
                speakerName,
                speakerText,
                context,
                memory.recentTurns(),
                fallbackCatalog.replies(intentKey, style),
                true,
                settings.maxResponseChars(),
                settings.requestTimeoutMs());
        ConversationTurn playerTurn = new ConversationTurn(
                counterpartyType == SocialCounterpartyType.AGENT
                        ? ConversationTurn.Role.AGENT
                        : ConversationTurn.Role.HUMAN,
                speakerName, speakerText, nowMs);
        return new PreparedDialogue(request, relationshipKey, sessionId, speakerId, playerTurn);
    }

    private static String activitySummary(Map<String, String> facts) {
        String objective = facts.get("objective.active");
        if (objective != null && !objective.isBlank()) {
            return "working on " + objective;
        }
        String townLife = facts.get("townlife.activity");
        if (townLife != null && !townLife.isBlank()) {
            return "spending time in town: " + townLife.toLowerCase();
        }
        String lifeState = facts.get("combat.lifeState");
        if (lifeState != null && !lifeState.isBlank()) {
            return "current life state: " + lifeState.toLowerCase();
        }
        return "between activities";
    }

    private static Map<String, String> publicFacts(Map<String, String> facts) {
        return publicFacts(facts, null);
    }

    private static Map<String, String> publicFacts(Map<String, String> facts, GroundingRequest request) {
        Map<String, String> safe = new LinkedHashMap<>();
        for (String key : new String[]{
                "world.mapId", "progression.level", "progression.jobId",
                "townlife.activity", "townlife.phase", "combat.lifeState"}) {
            String value = facts.get(key);
            if (value != null && !value.isBlank()) {
                safe.put(key, value);
            }
        }
        if (request != null && request.agentCharacterId() > 0) {
            GroundingRegistry.collect(request, safe);
        }
        return Collections.unmodifiableMap(safe);
    }

    private static int mapId(Map<String, String> facts) {
        try {
            return Integer.parseInt(facts.getOrDefault("world.mapId", "").trim());
        } catch (NumberFormatException unknown) {
            return 0;
        }
    }

    public record PreparedDialogue(
            DialogueRequest request,
            SocialRelationshipKey relationshipKey,
            String sessionId,
            int speakerId,
            ConversationTurn speakerTurn) {
    }
}
