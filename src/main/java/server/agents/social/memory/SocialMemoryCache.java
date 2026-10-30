package server.agents.social.memory;

import server.agents.social.contracts.ConversationTurn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Non-blocking bounded read model used by Agent mailboxes and prompt projection. */
public final class SocialMemoryCache {
    public static final int MAX_RECENT_TURNS = 8;

    private final ConcurrentMap<SocialRelationshipKey, SocialRelationshipMemory> relationships =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<SocialRelationshipKey, ArrayDeque<ConversationTurn>> turns =
            new ConcurrentHashMap<>();
    /**
     * Whispered exchanges, kept apart (whisper review P2, 2026-10-10): a public reply's prompt never sees what the
     * player whispered, so a line said on the map cannot repeat it; a whispered reply sees both.
     */
    private final ConcurrentMap<SocialRelationshipKey, ArrayDeque<ConversationTurn>> whispered =
            new ConcurrentHashMap<>();

    public SocialMemorySnapshot snapshot(SocialRelationshipKey key, long nowMs) {
        return snapshot(key, nowMs, false);
    }

    /** {@code whisper}: for a whispered reply, which also sees the whispered turns; a public one sees only public ones. */
    public SocialMemorySnapshot snapshot(SocialRelationshipKey key, long nowMs, boolean whisper) {
        SocialRelationshipMemory relationship = relationships.getOrDefault(
                key, SocialRelationshipMemory.neutral(key, nowMs));
        List<ConversationTurn> recent = copy(turns.get(key));
        if (whisper) {
            recent.addAll(copy(whispered.get(key)));
            recent.sort(java.util.Comparator.comparingLong(ConversationTurn::occurredAtMs));
            recent = new ArrayList<>(recent.subList(Math.max(0, recent.size() - MAX_RECENT_TURNS), recent.size()));
        }
        return new SocialMemorySnapshot(relationship, recent);
    }

    public SocialRelationshipMemory recordConversation(
            SocialRelationshipKey key,
            ConversationTurn playerTurn,
            ConversationTurn agentTurn,
            long nowMs) {
        return recordConversation(key, playerTurn, agentTurn, nowMs, false);
    }

    /** {@code whisper}: a whispered exchange, kept apart from the public turns. */
    public SocialRelationshipMemory recordConversation(
            SocialRelationshipKey key,
            ConversationTurn playerTurn,
            ConversationTurn agentTurn,
            long nowMs,
            boolean whisper) {
        SocialRelationshipMemory relationship = relationships.compute(
                key,
                (ignored, current) -> (current == null
                        ? SocialRelationshipMemory.neutral(key, nowMs)
                        : current).recordConversation(nowMs));
        ConcurrentMap<SocialRelationshipKey, ArrayDeque<ConversationTurn>> into = whisper ? whispered : turns;
        append(into, key, playerTurn);
        append(into, key, agentTurn);
        return relationship;
    }

    private static List<ConversationTurn> copy(ArrayDeque<ConversationTurn> history) {
        if (history == null) {
            return new ArrayList<>();
        }
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public void hydrate(
            SocialRelationshipKey key,
            SocialRelationshipMemory relationship,
            List<ConversationTurn> recentTurns) {
        if (relationship != null) {
            relationships.merge(key, relationship,
                    SocialMemoryCache::mergeRelationship);
        }
        if (recentTurns == null || recentTurns.isEmpty()) {
            return;
        }
        ArrayDeque<ConversationTurn> history = turns.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (history) {
            List<ConversationTurn> merged = new ArrayList<>(recentTurns);
            for (ConversationTurn current : history) {
                if (!merged.contains(current)) {
                    merged.add(current);
                }
            }
            merged.sort(java.util.Comparator.comparingLong(ConversationTurn::occurredAtMs));
            history.clear();
            merged.stream()
                    .skip(Math.max(0, merged.size() - MAX_RECENT_TURNS))
                    .forEach(history::addLast);
        }
    }

    private static SocialRelationshipMemory mergeRelationship(
            SocialRelationshipMemory current,
            SocialRelationshipMemory loaded) {
        if (current.interactionCount() > 0
                && current.createdAtMs() >= loaded.lastInteractionAtMs()) {
            SocialRelationshipMemory merged = loaded;
            for (long index = 0; index < current.interactionCount(); index++) {
                merged = merged.recordConversation(current.lastInteractionAtMs());
            }
            return merged;
        }
        return current.revision() >= loaded.revision() ? current : loaded;
    }

    public void clearAgent(int agentId) {
        relationships.keySet().removeIf(key -> key.agentId() == agentId);
        turns.keySet().removeIf(key -> key.agentId() == agentId);
        whispered.keySet().removeIf(key -> key.agentId() == agentId);
    }

    private static void append(ConcurrentMap<SocialRelationshipKey, ArrayDeque<ConversationTurn>> into,
                               SocialRelationshipKey key, ConversationTurn turn) {
        ArrayDeque<ConversationTurn> history = into.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (history) {
            while (history.size() >= MAX_RECENT_TURNS) {
                history.removeFirst();
            }
            history.addLast(turn);
        }
    }
}
