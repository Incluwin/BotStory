package server.agents.social.policy;

import client.Character;
import server.agents.runtime.AgentRuntimeHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Deterministically elects at most one responder for untargeted social chat. */
public final class AgentSocialResponderElection {
    private static volatile Preference preference;

    private AgentSocialResponderElection() {
    }

    /**
     * Host-supplied narrowing (server knowledge: for a content question, the companions whose level covers the
     * answer). The election runs among the preferred candidates when there are any, else among all of them.
     */
    @FunctionalInterface
    public interface Preference {
        boolean prefers(int agentCharacterId, Character speaker, String message);
    }

    public static void installPreference(Preference replacement) {
        preference = replacement;
    }

    public static <E extends AgentRuntimeHandle> E elect(
            Character speaker,
            List<E> candidates,
            String message,
            ToIntFunction<E> agentId) {
        if (speaker == null || candidates == null || candidates.isEmpty() || agentId == null) {
            return null;
        }
        int seed = 31 * speaker.getId() + (message == null ? 0 : message.toLowerCase().hashCode());
        E selected = null;
        long selectedScore = Long.MAX_VALUE;
        for (E candidate : preferred(speaker, candidates, message, agentId)) {
            int candidateId = agentId.applyAsInt(candidate);
            if (candidateId <= 0) {
                continue;
            }
            long score = Integer.toUnsignedLong(mix(seed ^ candidateId));
            if (score < selectedScore) {
                selected = candidate;
                selectedScore = score;
            }
        }
        return selected;
    }

    private static <E> List<E> preferred(Character speaker, List<E> candidates, String message, ToIntFunction<E> agentId) {
        Preference host = preference;
        if (host == null || message == null) {
            return candidates;
        }
        List<E> preferred = new ArrayList<>();
        for (E candidate : candidates) {
            int candidateId = agentId.applyAsInt(candidate);
            try {
                if (candidateId > 0 && host.prefers(candidateId, speaker, message)) {
                    preferred.add(candidate);
                }
            } catch (RuntimeException ignored) {
                return candidates; // the plain election still picks someone
            }
        }
        return preferred.isEmpty() ? candidates : preferred;
    }

    private static int mix(int value) {
        int mixed = value ^ (value >>> 16);
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        return mixed ^ (mixed >>> 16);
    }
}
