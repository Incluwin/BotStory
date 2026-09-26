package server.agents.capabilities.dialogue.jev;

import server.agents.runtime.AgentRuntimeEntry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "did you mean ...?" suggestion each Agent is waiting on. The owner's next message either
 * confirms it (the command runs and the original phrase is learned), declines it, or is something
 * else, which drops the suggestion so it is judged normally.
 */
final class AgentChatPendingAsks {
    private static final Set<String> YES = Set.of("yes", "y", "ya", "yah", "yea", "yeah", "yep", "yup", "ye",
            "sure", "ok", "okay", "k", "kk", "correct", "right", "exactly", "do it", "yes please", "please", "pls", "plz");
    private static final Set<String> NO = Set.of("no", "n", "nah", "nope", "no thanks", "never mind", "nevermind", "nvm");

    enum Reply { CONFIRMED, DECLINED, OTHER }

    record Pending(String message, String canonicalCommand, long expiresAtMs) {
    }

    record Resolution(Reply reply, Pending pending) {
    }

    private final long ttlMs;
    private final Map<AgentRuntimeEntry, Pending> pending = new ConcurrentHashMap<>();

    AgentChatPendingAsks(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    void remember(AgentRuntimeEntry entry, String message, String canonicalCommand, long nowMs) {
        pending.values().removeIf(ask -> ask.expiresAtMs() <= nowMs);
        pending.put(entry, new Pending(message, canonicalCommand, nowMs + ttlMs));
    }

    /** Consumes the entry's live suggestion, if any, and classifies {@code message} against it. */
    Resolution resolve(AgentRuntimeEntry entry, String message, long nowMs) {
        Pending ask = pending.remove(entry);
        if (ask == null || ask.expiresAtMs() <= nowMs) {
            return new Resolution(Reply.OTHER, null);
        }
        String normalized = normalize(message);
        if (YES.contains(normalized)) {
            return new Resolution(Reply.CONFIRMED, ask);
        }
        if (NO.contains(normalized)) {
            return new Resolution(Reply.DECLINED, ask);
        }
        return new Resolution(Reply.OTHER, ask);
    }

    static String normalize(String message) {
        return message.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").trim().replaceAll("\\s+", " ");
    }
}
