package server.agents.capabilities.dialogue.jev;

import server.agents.runtime.AgentRuntimeEntry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "did you mean ...?" suggestion each Agent is waiting on, and whom it asked. That one's next message either
 * confirms it (the command runs and the original phrase is learned), declines it, or is something else, which drops
 * the suggestion so it is judged normally. Anyone else's line neither answers nor drops it: an administrator may
 * whisper the owner's companion, and the owner's "ok" to a party mate is no answer to the administrator's ask, nor the
 * administrator's "yes" to the owner's (third deploy review 2026-10-10).
 */
final class AgentChatPendingAsks {
    /** The asker of a suggestion anyone answers, and the speaker of a line that answers any. */
    static final int ANY_ASKER_ID = -1;
    private static final Set<String> YES = Set.of("yes", "y", "ya", "yah", "yea", "yeah", "yep", "yup", "ye",
            "sure", "ok", "okay", "k", "kk", "correct", "right", "exactly", "do it", "yes please", "please", "pls", "plz");
    private static final Set<String> NO = Set.of("no", "n", "nah", "nope", "no thanks", "never mind", "nevermind", "nvm");

    enum Reply { CONFIRMED, DECLINED, OTHER }

    /** {@code askerId}: the character id of whom it asked, or {@link #ANY_ASKER_ID}. */
    record Pending(String message, String canonicalCommand, long expiresAtMs, int askerId) {
        Pending(String message, String canonicalCommand, long expiresAtMs) {
            this(message, canonicalCommand, expiresAtMs, ANY_ASKER_ID);
        }

        /** True when a line {@code speakerId} said can answer it. */
        boolean answerableBy(int speakerId) {
            return askerId == ANY_ASKER_ID || speakerId == ANY_ASKER_ID || askerId == speakerId;
        }
    }

    record Resolution(Reply reply, Pending pending) {
    }

    private final long ttlMs;
    private final Map<AgentRuntimeEntry, Pending> pending = new ConcurrentHashMap<>();

    AgentChatPendingAsks(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    void remember(AgentRuntimeEntry entry, String message, String canonicalCommand, long nowMs) {
        remember(entry, ANY_ASKER_ID, message, canonicalCommand, nowMs);
    }

    /** The suggestion the Agent just asked {@code askerId}, replacing any it was waiting on. */
    void remember(AgentRuntimeEntry entry, int askerId, String message, String canonicalCommand, long nowMs) {
        pending.values().removeIf(ask -> ask.expiresAtMs() <= nowMs);
        pending.put(entry, new Pending(message, canonicalCommand, nowMs + ttlMs, askerId));
    }

    Resolution resolve(AgentRuntimeEntry entry, String message, long nowMs) {
        return resolve(entry, ANY_ASKER_ID, message, nowMs);
    }

    /**
     * Consumes the entry's live suggestion, if any, when {@code speakerId} may answer it, and classifies
     * {@code message} against it; a suggestion asked of someone else stays for them.
     */
    Resolution resolve(AgentRuntimeEntry entry, int speakerId, String message, long nowMs) {
        Pending ask = pending.get(entry);
        if (ask == null || !ask.answerableBy(speakerId) || !pending.remove(entry, ask) || ask.expiresAtMs() <= nowMs) {
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
