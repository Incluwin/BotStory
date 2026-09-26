package server.agents.capabilities.dialogue.jev;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.dialogue.jev.AgentChatPendingAsks.Reply;
import server.agents.commands.AgentReplyChannel;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentChatPendingAsksTest {
    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);

    @Test
    void confirmationConsumesTheSuggestion() {
        AgentChatPendingAsks asks = new AgentChatPendingAsks(30_000L);
        asks.remember(entry, "mind boosting my reputation a bit?", "fame me", 0L);

        AgentChatPendingAsks.Resolution answer = asks.resolve(entry, "Yeah!", 1_000L);

        assertEquals(Reply.CONFIRMED, answer.reply());
        assertEquals("fame me", answer.pending().canonicalCommand());
        assertEquals(Reply.OTHER, asks.resolve(entry, "yes", 2_000L).reply(), "a suggestion is answered once");
    }

    @Test
    void declineAndUnrelatedMessagesDropTheSuggestion() {
        AgentChatPendingAsks asks = new AgentChatPendingAsks(30_000L);
        asks.remember(entry, "ease off", "stop", 0L);
        assertEquals(Reply.DECLINED, asks.resolve(entry, "nah", 1L).reply());

        asks.remember(entry, "ease off", "stop", 0L);
        assertEquals(Reply.OTHER, asks.resolve(entry, "go grind", 1L).reply());
        assertEquals(Reply.OTHER, asks.resolve(entry, "yes", 2L).reply(), "the unrelated message cleared it");
    }

    @Test
    void expiredSuggestionIsIgnored() {
        AgentChatPendingAsks asks = new AgentChatPendingAsks(30_000L);
        asks.remember(entry, "ease off", "stop", 0L);

        AgentChatPendingAsks.Resolution answer = asks.resolve(entry, "yes", 30_000L);

        assertEquals(Reply.OTHER, answer.reply());
        assertNull(answer.pending());
    }

    @Test
    void confirmedAskRedispatchesAndLearnsTheOriginalPhrase() {
        List<String> dispatched = new ArrayList<>();
        List<String> learned = new ArrayList<>();
        AgentChatIntentRuntime.installLearner((message, canonical, confidence, confirmed) ->
                learned.add(message + " -> " + canonical + " @" + confidence + (confirmed ? " player" : "")));
        try {
            AgentChatIntentRuntime.rememberAsk(entry, "can u farm around here pls", "farm here");

            boolean handled = AgentChatIntentRuntime.judgeUnmatched(entry, "yes", AgentReplyChannel.MAP,
                    (e, command, channel) -> {
                        dispatched.add(command);
                        return CompletableFuture.completedFuture(true);
                    },
                    (e, reply) -> { }).toCompletableFuture().join();

            assertTrue(handled);
            assertEquals(List.of("farm here"), dispatched);
            assertEquals(List.of("can u farm around here pls -> farm here @1.0 player"), learned);
        } finally {
            AgentChatIntentRuntime.installLearner(null);
        }
    }

    @Test
    void restatingTheSameRequestConfirmsItInsteadOfAskingAgain() {
        List<String> dispatched = new ArrayList<>();
        List<String> replies = new ArrayList<>();
        List<String> learned = new ArrayList<>();
        AgentChatIntentRuntime.installLearner((message, canonical, confidence, confirmed) ->
                learned.add(message + " -> " + canonical + (confirmed ? " player" : "")));
        AgentChatIntentRuntime.Redispatch redispatch = (e, command, channel) -> {
            dispatched.add(command);
            return CompletableFuture.completedFuture(true);
        };
        AgentChatPendingAsks.Pending dropped =
                new AgentChatPendingAsks.Pending("mind boosting my reputation a bit?", "fame me", Long.MAX_VALUE);
        try {
            CompletableFuture<Boolean> same = new CompletableFuture<>();
            AgentChatIntentRuntime.apply(entry, AgentReplyChannel.MAP, "yeah, bump my reputation up pls",
                    ask("fame me"), dropped, redispatch, (e, reply) -> replies.add(reply), same);
            CompletableFuture<Boolean> other = new CompletableFuture<>();
            AgentChatIntentRuntime.apply(entry, AgentReplyChannel.MAP, "ease off",
                    ask("stop"), dropped, redispatch, (e, reply) -> replies.add(reply), other);

            assertTrue(same.join());
            assertTrue(other.join());
            assertEquals(List.of("fame me"), dispatched);
            assertEquals(List.of("u want me to stop?"), replies);
            assertEquals(List.of(
                    "mind boosting my reputation a bit? -> fame me player",
                    "yeah, bump my reputation up pls -> fame me player"), learned);
        } finally {
            AgentChatIntentRuntime.installLearner(null);
        }
    }

    @Test
    void everydayCommandsAreAskedInAPlayersVoice() {
        assertEquals("u want me to follow u?", AgentChatIntentRuntime.askText("follow me"));
        assertEquals("u want a fame?", AgentChatIntentRuntime.askText("fame me"));
        assertEquals("u want everyone to gather here?", AgentChatIntentRuntime.askText("gather up"));
        assertEquals("u want everyone over here for buffs?", AgentChatIntentRuntime.askText("gather up for buffs"));
        assertEquals("did you mean 'autoequip'?", AgentChatIntentRuntime.askText("autoequip"));
    }

    private static AgentChatIntentJudge.Decision ask(String canonical) {
        return new AgentChatIntentJudge.Decision(AgentChatIntentJudge.Outcome.ASK, null, canonical,
                0.62d, 0.6d, 0L, 0L, "");
    }

    @Test
    void declinedAskRepliesAndGmCommandsAreNeverJudged() {
        List<String> replies = new ArrayList<>();
        AgentChatIntentRuntime.rememberAsk(entry, "ease off", "stop");

        boolean declined = AgentChatIntentRuntime.judgeUnmatched(entry, "no", AgentReplyChannel.MAP,
                (e, command, channel) -> CompletableFuture.completedFuture(true),
                (e, reply) -> replies.add(reply)).toCompletableFuture().join();
        boolean gm = AgentChatIntentRuntime.judgeUnmatched(entry, "d !warpto ProbeHero", AgentReplyChannel.MAP,
                (e, command, channel) -> CompletableFuture.completedFuture(true),
                (e, reply) -> replies.add(reply)).toCompletableFuture().join();

        assertTrue(declined);
        assertEquals(List.of("okay, never mind."), replies);
        assertFalse(gm);
    }
}
