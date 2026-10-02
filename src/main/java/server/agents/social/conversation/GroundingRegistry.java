package server.agents.social.conversation;

import server.agents.social.contracts.DialogueContextSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Host-supplied facts for the dialogue prompt ("Public context"), composed from named providers: the population's
 * persona card, server knowledge's content facts, the legacy single hook. Providers run in registration order
 * (registering an id again replaces it in place). Collection runs on the Agent mailbox thread, so a provider must be
 * an in-memory lookup. A provider that throws is skipped, and so is a fact whose key is blank or longer than the
 * snapshot's fact limit, instead of costing the reply; values are cut to that limit and the total stops at the
 * snapshot's fact cap, keeping insertion order.
 */
public final class GroundingRegistry {
    /** One host's facts for a turn; keys become labelled lines under "Public context". */
    @FunctionalInterface
    public interface Provider {
        Map<String, String> facts(GroundingRequest request);
    }

    private record Registered(String id, Provider provider) {
    }

    private static volatile List<Registered> providers = List.of();

    private GroundingRegistry() {
    }

    public static synchronized void register(String id, Provider provider) {
        if (provider == null) {
            unregister(id);
            return;
        }
        List<Registered> next = new ArrayList<>(providers);
        Registered entry = new Registered(id, provider);
        int at = indexOf(next, id);
        if (at >= 0) {
            next.set(at, entry);
        } else {
            next.add(entry);
        }
        providers = List.copyOf(next);
    }

    public static synchronized void unregister(String id) {
        List<Registered> next = new ArrayList<>(providers);
        int at = indexOf(next, id);
        if (at >= 0) {
            next.remove(at);
            providers = List.copyOf(next);
        }
    }

    /** Adds every provider's valid facts to {@code into} (which may already hold some) until the fact cap. */
    static void collect(GroundingRequest request, Map<String, String> into) {
        for (Registered entry : providers) {
            if (into.size() >= DialogueContextSnapshot.MAX_PUBLIC_FACTS) {
                return;
            }
            Map<String, String> facts;
            try {
                facts = entry.provider().facts(request);
            } catch (RuntimeException ignored) {
                continue; // grounding is optional; the prompt keeps the other providers' facts
            }
            if (facts == null) {
                continue;
            }
            for (Map.Entry<String, String> fact : facts.entrySet()) {
                if (into.size() >= DialogueContextSnapshot.MAX_PUBLIC_FACTS) {
                    return;
                }
                String key = fact.getKey();
                String value = fact.getValue();
                if (key == null || key.isBlank() || key.length() > DialogueContextSnapshot.MAX_FACT_CHARS
                        || value == null || value.isBlank()) {
                    continue;
                }
                into.put(key, value.length() > DialogueContextSnapshot.MAX_FACT_CHARS
                        ? value.substring(0, DialogueContextSnapshot.MAX_FACT_CHARS) : value);
            }
        }
    }

    private static int indexOf(List<Registered> entries, String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
