package server.agents.capabilities.trade;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;
import server.agents.capabilities.dialogue.AgentEmote;
import server.agents.capabilities.inventory.AgentInventoryRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The completion goes through the real Cosmic trade gateway with server.Trade's static calls stubbed: the Agent's own
 * Trade object tells it whether the trade settled, still waits for its partner's press, or closed.
 */
class AgentTradeCompletionServiceTest {
    /** An Agent whose press of Trade settles the trade (the commit path marks its Trade settled). */
    private static Character agentWhoseTradeSettles() {
        Character agent = mock(Character.class);
        Trade trade = mock(Trade.class);
        when(trade.isSettled()).thenReturn(true);
        when(agent.getTrade()).thenReturn(trade);
        return agent;
    }

    /** An Agent whose trade stays open and unsettled after its press: the partner has not pressed Trade yet. */
    private static Character agentWaitingForItsPartner() {
        Character agent = mock(Character.class);
        Trade trade = mock(Trade.class);
        when(agent.getTrade()).thenReturn(trade);
        return agent;
    }

    @Test
    void receivedTradeSnapshotsOwnerGivenEquipsCompletesAndThanks() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character agent = agentWhoseTradeSettles();
        Item equip = new Item(1002000, (short) 1, (short) 1);
        AtomicBoolean delayedReply = new AtomicBoolean(false);

        try (MockedStatic<Trade> tradeStatic = mockStatic(Trade.class);
             MockedStatic<AgentInventoryRuntime> inventory = mockStatic(AgentInventoryRuntime.class)) {
            inventory.when(() -> AgentInventoryRuntime.afterDelay(org.mockito.ArgumentMatchers.eq(entry), org.mockito.ArgumentMatchers.eq(900L), org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        delayedReply.set(true);
                        invocation.<Runnable>getArgument(2).run();
                        return null;
                    });

            AgentTradeCompletionService.completeAndReact(
                    entry,
                    agent,
                    List.of(equip),
                    true,
                    () -> 900L,
                    () -> "thanks",
                    () -> "freebie",
                    () -> 99,
                    () -> true);

            assertTrue(AgentPendingTradeStateRuntime.hasOwnerGivenItems(entry));
            verify(agent).changeFaceExpression(AgentEmote.HAPPY.getValue());
            tradeStatic.verify(() -> Trade.completeTrade(agent));
            inventory.verify(() -> AgentInventoryRuntime.visibleSayNow(entry, "thanks"));
            assertTrue(delayedReply.get());
        }
    }

    @Test
    void emptyTradeMaySendFreebieReaction() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character agent = agentWhoseTradeSettles();

        try (MockedStatic<Trade> tradeStatic = mockStatic(Trade.class);
             MockedStatic<AgentInventoryRuntime> inventory = mockStatic(AgentInventoryRuntime.class)) {
            inventory.when(() -> AgentInventoryRuntime.afterDelay(org.mockito.ArgumentMatchers.eq(entry), org.mockito.ArgumentMatchers.eq(800L), org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        invocation.<Runnable>getArgument(2).run();
                        return null;
                    });

            AgentTradeCompletionService.completeAndReact(
                    entry,
                    agent,
                    List.of(),
                    false,
                    () -> 800L,
                    () -> "thanks",
                    () -> "freebie",
                    () -> 0,
                    () -> false);

            assertFalse(AgentPendingTradeStateRuntime.hasOwnerGivenItems(entry));
            verify(agent).changeFaceExpression(AgentEmote.ANNOYED.getValue());
            tradeStatic.verify(() -> Trade.completeTrade(agent));
            inventory.verify(() -> AgentInventoryRuntime.visibleSayNow(entry, "freebie"));
        }
    }

    @Test
    void aTradeThatClosedWithNothingMovedGetsNoReactionAtAll() {
        // live 2026-10-10: a gift into a full Equip bag ended in "Trade unsuccessful."; every refusal (bag or meso room,
        // one-of-a-kind, daily meso limit) closes the trade unsettled, so neither "ty!" nor a freebie line follows
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character agent = mock(Character.class);
        AtomicReference<Trade> open = new AtomicReference<>();
        when(agent.getTrade()).thenAnswer(invocation -> open.get());
        Item cap = new Item(1002000, (short) 1, (short) 1);

        try (MockedStatic<Trade> tradeStatic = mockStatic(Trade.class);
             MockedStatic<AgentInventoryRuntime> inventory = mockStatic(AgentInventoryRuntime.class)) {
            tradeStatic.when(() -> Trade.completeTrade(agent))
                    .thenAnswer(invocation -> {
                        open.set(null); // what the refusal's cancel does to both sides
                        return null;
                    });
            inventory.when(() -> AgentInventoryRuntime.afterDelay(org.mockito.ArgumentMatchers.eq(entry), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        invocation.<Runnable>getArgument(2).run();
                        return null;
                    });

            for (boolean received : new boolean[]{true, false}) {
                open.set(mock(Trade.class));
                AgentTradeCompletionService.completeAndReact(
                        entry,
                        agent,
                        received ? List.of(cap) : List.of(),
                        received,
                        () -> 900L,
                        () -> "thanks",
                        () -> "freebie",
                        () -> 0,
                        () -> true);
            }

            tradeStatic.verify(() -> Trade.completeTrade(agent), org.mockito.Mockito.times(2));
            inventory.verify(() -> AgentInventoryRuntime.visibleSayNow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()), org.mockito.Mockito.never());
            verify(agent, org.mockito.Mockito.never()).changeFaceExpression(org.mockito.ArgumentMatchers.anyInt());
        }
    }

    @Test
    void aGiftWhosePartnerHasNotPressedTradeYetGetsNoThanksYet() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character agent = agentWaitingForItsPartner();

        try (MockedStatic<Trade> ignored = mockStatic(Trade.class);
             MockedStatic<AgentInventoryRuntime> inventory = mockStatic(AgentInventoryRuntime.class)) {
            AgentTradeCompletionService.completeAndReact(
                    entry,
                    agent,
                    List.of(new Item(1002000, (short) 1, (short) 1)),
                    true,
                    () -> 900L,
                    () -> "thanks",
                    () -> "freebie",
                    () -> 0,
                    () -> true);

            inventory.verify(() -> AgentInventoryRuntime.afterDelay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(Runnable.class)), org.mockito.Mockito.never());
            verify(agent, org.mockito.Mockito.never()).changeFaceExpression(org.mockito.ArgumentMatchers.anyInt());
        }
    }

    /**
     * A giver whose recipient is an Agent presses Trade first (WAITING_FOR_PARTNER); the recipient's press on its own
     * tick settles or refuses the gift. The giver's face and freebie line wait until the line is due and come only if
     * the trade settled by then (deploy review 2026-10-10: before, it said "enjoy" for a gift its partner refused).
     */
    private static void giverPressesFirst(AgentRuntimeEntry entry, Character agent, List<Runnable> dueLater,
                                          MockedStatic<AgentInventoryRuntime> inventory) {
        inventory.when(() -> AgentInventoryRuntime.afterDelay(org.mockito.ArgumentMatchers.eq(entry), org.mockito.ArgumentMatchers.eq(800L), org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> dueLater.add(invocation.getArgument(2)));

        AgentTradeCompletionService.completeAndReact(
                entry,
                agent,
                List.of(),
                false,
                () -> 800L,
                () -> "thanks",
                () -> "freebie",
                () -> 0,
                () -> false);

        verify(agent, org.mockito.Mockito.never()).changeFaceExpression(org.mockito.ArgumentMatchers.anyInt());
        assertEquals(1, dueLater.size(), "one line due later");
    }

    @Test
    void aGiverWhosePartnersPressSettledTheGiftSaysItsFreebieLineWhenDue() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character agent = mock(Character.class);
        Trade trade = mock(Trade.class);
        AtomicBoolean settled = new AtomicBoolean();
        when(agent.getTrade()).thenReturn(trade);
        when(trade.isSettled()).thenAnswer(invocation -> settled.get());
        List<Runnable> dueLater = new java.util.ArrayList<>();

        try (MockedStatic<Trade> ignored = mockStatic(Trade.class);
             MockedStatic<AgentInventoryRuntime> inventory = mockStatic(AgentInventoryRuntime.class)) {
            giverPressesFirst(entry, agent, dueLater, inventory);
            settled.set(true); // the partner's press on its own tick settled it
            dueLater.forEach(Runnable::run);

            verify(agent).changeFaceExpression(AgentEmote.ANNOYED.getValue());
            inventory.verify(() -> AgentInventoryRuntime.visibleSayNow(entry, "freebie"));
        }
    }

    @Test
    void aGiverWhoseTradeHasNotSettledWhenTheLineIsDueSaysNothing() {
        // the partner's press refused the gift (or has not come yet): nothing arrived, so no "enjoy" and no face
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character agent = agentWaitingForItsPartner();
        List<Runnable> dueLater = new java.util.ArrayList<>();

        try (MockedStatic<Trade> ignored = mockStatic(Trade.class);
             MockedStatic<AgentInventoryRuntime> inventory = mockStatic(AgentInventoryRuntime.class)) {
            giverPressesFirst(entry, agent, dueLater, inventory);
            dueLater.forEach(Runnable::run);

            verify(agent, org.mockito.Mockito.never()).changeFaceExpression(org.mockito.ArgumentMatchers.anyInt());
            inventory.verify(() -> AgentInventoryRuntime.visibleSayNow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()), org.mockito.Mockito.never());
        }
    }
}
