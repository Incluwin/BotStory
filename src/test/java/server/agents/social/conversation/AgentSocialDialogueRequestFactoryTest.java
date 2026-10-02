package server.agents.social.conversation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.social.contracts.DialogueContextSnapshot;
import server.agents.social.contracts.DialogueStyleSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composable grounding (docs/SERVER_KNOWLEDGE_PLAN.md 6.1): providers in registration order, an id registered again
 * replaced in place, the legacy single hook as one provider, a throwing provider or an invalid key skipped instead of
 * silencing the reply, the 48-fact cap, and the order kept all the way into the dialogue snapshot.
 */
class AgentSocialDialogueRequestFactoryTest {
    private static final GroundingRequest REQUEST = new GroundingRequest(100, 200, "Alice", "who drops steelies?",
            "casual.question", 100_000_000, Map.of("world.mapId", "100000000"));

    @AfterEach
    void clear() {
        for (String id : List.of("legacy", "a", "b", "boom", "bad", "many")) {
            GroundingRegistry.unregister(id);
        }
    }

    @Test
    void providersComposeInRegistrationOrderAndReplaceInPlace() {
        GroundingRegistry.register("a", request -> ordered("a.first", "1", "a.second", "2"));
        GroundingRegistry.register("b", request -> ordered("b.only", request.utterance()));
        GroundingRegistry.register("a", request -> ordered("a.again", "3"));

        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("world.mapId", "100000000");
        GroundingRegistry.collect(REQUEST, facts);

        List<String> keys = new ArrayList<>(facts.keySet());
        assertEquals(0, keys.indexOf("world.mapId"));
        assertTrue(keys.indexOf("a.again") >= 0 && keys.indexOf("a.again") < keys.indexOf("b.only"), keys.toString());
        assertFalse(keys.contains("a.first"), "the second registration of a replaced the first");
        assertEquals("who drops steelies?", facts.get("b.only"), "a provider sees the line, punctuation intact");
    }

    @Test
    void theLegacyHookIsOneProviderAndNullRemovesIt() {
        AgentSocialDialogueRequestFactory.installGrounding((agentId, speakerId) -> Map.of("who i am", "agent " + agentId + " to " + speakerId));
        Map<String, String> facts = new LinkedHashMap<>();
        GroundingRegistry.collect(REQUEST, facts);
        assertEquals("agent 100 to 200", facts.get("who i am"));

        AgentSocialDialogueRequestFactory.installGrounding(null);
        Map<String, String> none = new LinkedHashMap<>();
        GroundingRegistry.collect(REQUEST, none);
        assertFalse(none.containsKey("who i am"));
    }

    @Test
    void aThrowingProviderOrAnInvalidFactIsSkippedNotFatal() {
        GroundingRegistry.register("boom", request -> {
            throw new IllegalStateException("provider bug");
        });
        String longKey = "k".repeat(DialogueContextSnapshot.MAX_FACT_CHARS + 1);
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put(longKey, "too long a key");
        bad.put(" ", "blank key");
        bad.put("blank.value", " ");
        bad.put("long.value", "v".repeat(DialogueContextSnapshot.MAX_FACT_CHARS + 40));
        bad.put("ok", "fine");
        GroundingRegistry.register("bad", request -> bad);

        Map<String, String> facts = new LinkedHashMap<>();
        GroundingRegistry.collect(REQUEST, facts);

        assertTrue(facts.keySet().containsAll(List.of("long.value", "ok")));
        assertFalse(facts.containsKey(longKey) || facts.containsKey(" ") || facts.containsKey("blank.value"));
        assertEquals(DialogueContextSnapshot.MAX_FACT_CHARS, facts.get("long.value").length(), "values are cut, not rejected");
        new DialogueContextSnapshot(100, 1, "Mina", "relaxed-v1", style(), "between activities", "none", 100, facts);
    }

    @Test
    void factsStopAtTheSnapshotCap() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < DialogueContextSnapshot.MAX_PUBLIC_FACTS + 10; i++) {
            many.put("fact." + i, "v" + i);
        }
        GroundingRegistry.register("many", request -> many);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("world.mapId", "100000000");
        GroundingRegistry.collect(REQUEST, facts);

        assertEquals(DialogueContextSnapshot.MAX_PUBLIC_FACTS, facts.size());
        assertEquals("fact.0", new ArrayList<>(facts.keySet()).get(1));
    }

    @Test
    void theSnapshotKeepsFactOrder() {
        Map<String, String> facts = new LinkedHashMap<>();
        for (String key : List.of("world.mapId", "content.question", "content.item_source", "world.map", "a", "z", "m")) {
            facts.put(key, "x");
        }
        DialogueContextSnapshot snapshot = new DialogueContextSnapshot(100, 1, "Mina", "relaxed-v1", style(), "between activities",
                "none", 100, facts);

        assertEquals(new ArrayList<>(facts.keySet()), new ArrayList<>(snapshot.publicFacts().keySet()));
        assertFalse(snapshot.publicFacts() == facts, "a copy, not the caller's map");
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static DialogueStyleSnapshot style() {
        return new DialogueStyleSnapshot("friendly-casual-v1", 1, "friendly and casual", 25, 45, 40, 80, 15, List.of("yo"));
    }
}
