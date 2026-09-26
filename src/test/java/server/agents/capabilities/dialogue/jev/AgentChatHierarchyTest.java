package server.agents.capabilities.dialogue.jev;

import org.junit.jupiter.api.Test;
import server.agents.integration.typesafe.*;
import server.agents.integration.typesafe.state.JevState;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentChatHierarchyTest {
    private static final TypeSafeSettings SETTINGS = new TypeSafeSettings("key",
            TypeSafeSettings.DEFAULT_ENDPOINT, TypeSafeSettings.DEFAULT_MODEL, 1000);

    @Test void familiesCoverEveryIntentExactlyOnceAndPreserveDistinctCommands() {
        List<AgentChatIntent> all = Arrays.stream(AgentChatIntentFamily.values()).flatMap(f -> f.intents().stream()).toList();
        assertEquals(Set.of(AgentChatIntent.values()), new HashSet<>(all));
        assertEquals(AgentChatIntent.values().length, all.size());
        assertNotEquals(AgentChatIntent.GRIND.canonicalCommand(), AgentChatIntent.SENTRY.canonicalCommand());
        assertNotEquals(AgentChatIntent.STOP.canonicalCommand(), AgentChatIntent.LOGOUT.canonicalCommand());
        for (var family : AgentChatIntentFamily.values()) {
            if (family == AgentChatIntentFamily.CHAT) continue;
            var request = AgentChatIntentCatalog.branchRequest(Map.of("message", "test"), family);
            var choice = (JevQuestion.Choice) request.questions().get("intent");
            assertTrue(choice.criteria().containsKey("chat_not_a_command"));
            var expected = new HashSet<String>(List.of("intent", "addressed_to_bot"));
            family.intents().stream().map(AgentChatIntent::argument).filter(Objects::nonNull)
                    .forEach(a -> expected.add(a.questionId()));
            assertEquals(expected, request.questions().keySet());
        }
    }

    @Test void uncertainCategoryCannotBePromotedByConfidentLeafAndBothCallsAreCounted() {
        List<JevRequest> sent = new ArrayList<>();
        var decision = judge(sent, "combat", .6, "sentry", .99, false);
        assertEquals(AgentChatIntentJudge.Outcome.ASK, decision.outcome());
        assertEquals("farm here", decision.canonicalCommand());
        assertEquals(.6, decision.confidence());
        assertEquals(200, decision.inputTokens());
        assertEquals(20, decision.latencyMs());
        assertEquals(2, sent.size());
        assertEquals(2, sent.get(1).questions().size());
    }

    @Test void chatAndLowConfidenceCategoriesStopWithoutAnotherRequest() {
        for (var category : List.of("chat", "combat")) {
            List<JevRequest> sent = new ArrayList<>();
            assertEquals(AgentChatIntentJudge.Outcome.IGNORE,
                    judge(sent, category, category.equals("chat") ? .99 : .2, "grind", .99, false).outcome());
            assertEquals(1, sent.size());
        }
    }

    @Test void confidentBranchActsButNoMatchNeverActs() {
        assertEquals(AgentChatIntentJudge.Outcome.ACT, judge(new ArrayList<>(), "combat", .95, "patrol", .9, false).outcome());
        assertEquals(AgentChatIntentJudge.Outcome.IGNORE, judge(new ArrayList<>(), "combat", .95, "chat_not_a_command", .9, false).outcome());
    }

    @Test void failedOrOutOfFamilyBranchNeverDispatches() {
        assertEquals(AgentChatIntentJudge.Outcome.UNAVAILABLE, judge(new ArrayList<>(), "combat", .95, "grind", .9, true).outcome());
        assertEquals(AgentChatIntentJudge.Outcome.UNAVAILABLE, judge(new ArrayList<>(), "combat", .95, "logout", .9, false).outcome());
    }

    private AgentChatIntentJudge.Decision judge(List<JevRequest> sent, String family, double familyConfidence,
                                               String intent, double leafConfidence, boolean fail) {
        JevClient client = new JevClient(SETTINGS, (request, settings) -> {
            sent.add(request);
            boolean first = sent.size() == 1;
            if (!first && fail) throw new JevTransportException("offline", 503, false);
            String key = first ? "family" : "intent", label = first ? family : intent;
            return new JevResponse("test", Map.of(key, new JevAnswer("choice", label, Map.of(label, 1d),
                    null, null, null, first ? familyConfidence : leafConfidence)), 100, 0, 10);
        });
        return new AgentChatIntentJudge(client, .75, .45, 3000, true)
                .judge(JevState.create().put("message", "guard this spot", 100));
    }
}
