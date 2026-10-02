package server.agents.capabilities.dialogue.jev;

import org.junit.jupiter.api.Test;
import server.agents.commands.AgentReplyChannel;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentChatFallThroughCommandsTest {
    @Test
    void movementCanonicalPhrasesAreHandledWithoutTheJudge() {
        for (AgentChatIntent intent : List.of(AgentChatIntent.FOLLOW, AgentChatIntent.STOP, AgentChatIntent.MOVE_HERE,
                AgentChatIntent.GRIND, AgentChatIntent.PATROL, AgentChatIntent.SENTRY, AgentChatIntent.FIDGET)) {
            assertTrue(AgentChatFallThroughCommands.handledDeterministically(intent.canonicalCommand()),
                    intent + " canonical phrase '" + intent.canonicalCommand() + "' is acted on by the movement flow");
        }
    }

    @Test
    void reportQueriesGreetingsAndJobNamesFallThroughButAreHandled() {
        assertTrue(AgentChatFallThroughCommands.handledDeterministically("stats"));
        assertTrue(AgentChatFallThroughCommands.handledDeterministically("hi"));
        assertTrue(AgentChatFallThroughCommands.handledDeterministically("fighter"));
    }

    @Test
    void liveActOnAFallThroughCanonicalCountsAsHandled() {
        assertTrue(AgentChatIntentRuntime.actHandled("grind", false), "movement flow acted, orchestrator said false");
        assertTrue(AgentChatIntentRuntime.actHandled("stop", null));
        assertTrue(AgentChatIntentRuntime.actHandled("autoequip", true));
        assertFalse(AgentChatIntentRuntime.actHandled("autoequip", false), "a real dispatcher miss stays a miss");
    }

    @Test
    void contentQuestionsSkipTheJudgeButPhrasePacksAndPendingAsksComeFirst() {
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        List<String> gated = new ArrayList<>();
        List<String> dispatched = new ArrayList<>();
        AgentChatIntentRuntime.installPrejudge(message -> message.equals("what level are you") ? "level" : null);
        AgentChatIntentRuntime.installContentGate((agent, speaker, message) -> gated.add(message)); // every line looks like content
        AgentChatIntentRuntime.Redispatch redispatch = (e, command, channel) -> {
            dispatched.add(command);
            return CompletableFuture.completedFuture(true);
        };
        try {
            boolean phrase = AgentChatIntentRuntime.judgeUnmatched(entry, "what level are you", AgentReplyChannel.MAP,
                    redispatch, (e, reply) -> { }).toCompletableFuture().join();
            assertTrue(phrase);
            assertEquals(List.of("level"), dispatched, "the phrase pack still resolves its question-shaped alias");
            assertEquals(List.of(), gated, "the gate is never asked about a line the prejudge resolved");

            AgentChatIntentRuntime.rememberAsk(entry, "ease off", "stop");
            boolean content = AgentChatIntentRuntime.judgeUnmatched(entry, "who drops steelies", AgentReplyChannel.MAP,
                    redispatch, (e, reply) -> { }).toCompletableFuture().join();
            assertFalse(content, "left to the social reply, which answers it from knowledge");
            assertEquals(List.of("who drops steelies"), gated);
            boolean yes = AgentChatIntentRuntime.judgeUnmatched(entry, "yes", AgentReplyChannel.MAP,
                    redispatch, (e, reply) -> { }).toCompletableFuture().join();
            assertFalse(yes, "the content line cleared the pending 'did you mean stop?'");
            assertEquals(List.of("level"), dispatched);
        } finally {
            AgentChatIntentRuntime.installPrejudge(null);
            AgentChatIntentRuntime.installContentGate(null);
        }
    }

    @Test
    void regexMissesStillReachTheJudge() {
        assertFalse(AgentChatFallThroughCommands.handledDeterministically("can u farm around here pls"));
        assertFalse(AgentChatFallThroughCommands.handledDeterministically("stick with me for a bit"));
        assertFalse(AgentChatFallThroughCommands.handledDeterministically("what a nice day"));
        assertFalse(AgentChatFallThroughCommands.handledDeterministically(""));
        assertFalse(AgentChatFallThroughCommands.handledDeterministically(null));
    }
}
