package server.agents.capabilities.trade;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.dialogue.AgentChatPendingAction;
import server.agents.capabilities.dialogue.AgentPendingActionStateRuntime;
import server.agents.commands.AgentTargetedCommandMatch;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPendingOfferResponseServiceTest {
    @Test
    void routesSinglePendingOfferResponseToOnlyMatch() {
        Character speaker = mock(Character.class);
        AgentRuntimeEntry match = new AgentRuntimeEntry(mock(Character.class), speaker, null);
        AgentRuntimeEntry other = new AgentRuntimeEntry(mock(Character.class), speaker, null);
        List<String> calls = new ArrayList<>();

        boolean handled = AgentPendingOfferResponseService.handlePendingOfferResponse(
                List.of(List.of(match, other)),
                speaker,
                "yes",
                hooks(match, calls));

        assertTrue(handled);
        assertEquals(List.of(
                "expire",
                "target:true",
                "expire",
                "target:false",
                "resolve:1:yes",
                "handle:yes"), calls);
    }

    @Test
    void targetedResponseUsesResolvedCommandText() {
        Character speaker = mock(Character.class);
        AgentRuntimeEntry first = new AgentRuntimeEntry(mock(Character.class), speaker, null);
        AgentRuntimeEntry second = new AgentRuntimeEntry(mock(Character.class), speaker, null);
        List<String> calls = new ArrayList<>();

        boolean handled = AgentPendingOfferResponseService.handlePendingOfferResponse(
                List.of(List.of(first, second)),
                speaker,
                "agent yes",
                new AgentPendingOfferResponseService.Hooks(
                        entry -> calls.add("expire"),
                        (entry, tickSpeaker) -> true,
                        (entries, message) -> {
                            calls.add("resolve:" + entries.size());
                            return new AgentTargetedCommandMatch<>(second, "yes", null);
                        },
                        (entry, tickSpeaker, message) -> {
                            calls.add("handle:" + (entry == second) + ":" + message);
                            return true;
                        },
                        (tickSpeaker, feedback) -> calls.add("feedback:" + feedback)));

        assertTrue(handled);
        assertEquals(List.of("expire", "expire", "resolve:2", "handle:true:yes"), calls);
    }

    @Test
    void ambiguousConfirmationProducesLegacyFeedback() {
        Character speaker = mock(Character.class);
        AgentRuntimeEntry first = new AgentRuntimeEntry(mock(Character.class), speaker, null);
        AgentRuntimeEntry second = new AgentRuntimeEntry(mock(Character.class), speaker, null);
        List<String> calls = new ArrayList<>();

        boolean handled = AgentPendingOfferResponseService.handlePendingOfferResponse(
                List.of(List.of(first, second)),
                speaker,
                "yes",
                new AgentPendingOfferResponseService.Hooks(
                        entry -> { },
                        (entry, tickSpeaker) -> true,
                        (entries, message) -> new AgentTargetedCommandMatch<>(null, null, null),
                        (entry, tickSpeaker, message) -> {
                            calls.add("handle");
                            return true;
                        },
                        (tickSpeaker, feedback) -> calls.add(feedback)));

        assertTrue(handled);
        assertEquals(List.of("More than one bot is waiting on you. Say '<botname> yes' or '<slot> yes'."), calls);
    }

    @Test
    void aLineThatIsMoreThanAReplyPassesTwoWaitingOffers() {
        // found 2026-09-23: with two bots offering loot, "ok follow me" got the question instead of reaching the bots
        Character speaker = mock(Character.class);
        AgentRuntimeEntry first = new AgentRuntimeEntry(mock(Character.class), speaker, null);
        AgentRuntimeEntry second = new AgentRuntimeEntry(mock(Character.class), speaker, null);
        List<String> calls = new ArrayList<>();
        AgentPendingOfferResponseService.Hooks<AgentRuntimeEntry> waiting = new AgentPendingOfferResponseService.Hooks<>(
                entry -> { },
                (entry, tickSpeaker) -> true,
                (entries, message) -> new AgentTargetedCommandMatch<>(null, null, null),
                (entry, tickSpeaker, message) -> {
                    calls.add("handle");
                    return true;
                },
                (tickSpeaker, feedback) -> calls.add(feedback));

        for (String line : List.of("ok follow me", "no stop", "dont go there", "y u no grind")) {
            assertFalse(AgentPendingOfferResponseService.handlePendingOfferResponse(
                    List.of(List.of(first, second)), speaker, line, waiting), line);
        }
        assertEquals(List.of(), calls);
        assertTrue(AgentPendingOfferResponseService.handlePendingOfferResponse(
                List.of(List.of(first, second)), speaker, "no thanks", waiting), "a plain reply still asks which bot");
    }

    @Test
    void twoBotsAskedToLogOutAfterOfferingLootTakeTheYes() {
        // found 2026-09-26 (the showcase teardown): both bots had offered looted daggers, then "logout" asked both; the
        // owner's "yes" got "More than one bot is waiting on you" and nobody logged out
        Character speaker = mock(Character.class);
        when(speaker.getId()).thenReturn(100);
        when(speaker.getMapId()).thenReturn(20000);
        AgentRuntimeEntry first = offering(speaker, "Hero");
        AgentRuntimeEntry second = offering(speaker, "Bish");
        AgentPendingActionStateRuntime.setPendingAction(first, AgentChatPendingAction.LOGOUT);
        AgentPendingActionStateRuntime.setPendingAction(second, AgentChatPendingAction.LOGOUT);

        assertFalse(AgentPendingOfferChatRouteService.handlePendingOfferResponse(
                List.of(List.of(first, second)), speaker, "yes"), "the newer question takes the yes");
        verify(speaker, never()).dropMessage(anyInt(), anyString());

        AgentPendingActionStateRuntime.clearPendingAction(first);
        AgentPendingActionStateRuntime.clearPendingAction(second);
        assertTrue(AgentPendingOfferChatRouteService.handlePendingOfferResponse(
                List.of(List.of(first, second)), speaker, "yes"), "only offers asked: which bot, as before");
    }

    private static AgentRuntimeEntry offering(Character speaker, String name) {
        Character bot = mock(Character.class);
        when(bot.getName()).thenReturn(name);
        when(bot.getMapId()).thenReturn(20000);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, speaker, null);
        AgentOfferStateRuntime.setPendingLootOffer(entry, mock(Item.class), speaker.getId(), Long.MAX_VALUE, false);
        return entry;
    }

    @Test
    void returnsFalseWhenNoOfferMatches() {
        Character speaker = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(mock(Character.class), speaker, null);

        boolean handled = AgentPendingOfferResponseService.handlePendingOfferResponse(
                List.of(List.of(entry)),
                speaker,
                "hello",
                hooks(null, new ArrayList<>()));

        assertFalse(handled);
    }

    @Test
    void defaultTargetCheckRequiresOfferRecipientAndSameMap() {
        Character speaker = mock(Character.class);
        Character agent = mock(Character.class);
        when(speaker.getId()).thenReturn(100);
        when(speaker.getMapId()).thenReturn(20000);
        when(agent.getMapId()).thenReturn(20000);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentOfferStateRuntime.setPendingLootOffer(entry, mock(Item.class), 100, Long.MAX_VALUE, false);

        assertTrue(AgentPendingOfferChatRouteService.isPendingOfferTarget(entry, speaker));

        when(speaker.getMapId()).thenReturn(30000);
        assertFalse(AgentPendingOfferChatRouteService.isPendingOfferTarget(entry, speaker));

        Character otherSpeaker = mock(Character.class);
        when(otherSpeaker.getId()).thenReturn(101);
        when(otherSpeaker.getMapId()).thenReturn(20000);
        assertFalse(AgentPendingOfferChatRouteService.isPendingOfferTarget(entry, otherSpeaker));

        assertFalse(AgentPendingOfferChatRouteService.isPendingOfferTarget(null, speaker));
    }

    private static AgentPendingOfferResponseService.Hooks<AgentRuntimeEntry> hooks(AgentRuntimeEntry match, List<String> calls) {
        return new AgentPendingOfferResponseService.Hooks(
                entry -> calls.add("expire"),
                (entry, speaker) -> {
                    boolean result = entry == match;
                    calls.add("target:" + result);
                    return result;
                },
                (entries, message) -> {
                    calls.add("resolve:" + entries.size() + ":" + message);
                    return new AgentTargetedCommandMatch<>(null, null, null);
                },
                (entry, speaker, message) -> {
                    calls.add("handle:" + message);
                    return true;
                },
                (speaker, feedback) -> calls.add("feedback:" + feedback));
    }
}

