package server.agents.integration.cosmic;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;
import server.agents.capabilities.trade.AgentTradeOutcome;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * TradeGateway.completeTrade reads what came of an Agent's press of Trade from its Trade after the press. The partner's
 * press can settle the trade on the partner's tick shard at any moment, and a settlement marks the trade settled
 * before it closes both sides.
 */
class CosmicTradeGatewayTest {
    /**
     * Deploy review 2026-10-10: the gateway read "settled?" before "still its trade?". A commit landing between the two
     * reads gave "not settled" and then "closed", so a settled trade was reported CANCELLED (and its giver lost its
     * freebie line). Here the commit lands right after the gateway's first read after the press, whichever read that is.
     */
    @Test
    void aCommitLandingBetweenItsReadsIsReportedSettled() {
        Character agent = mock(Character.class);
        Trade trade = mock(Trade.class);
        AtomicBoolean pressed = new AtomicBoolean();
        AtomicBoolean committed = new AtomicBoolean();
        // before the commit: still its trade, not settled; the commit marks it settled, then closes it
        when(agent.getTrade()).thenAnswer(invocation -> {
            if (!pressed.get()) {
                return trade;
            }
            Trade seen = committed.get() ? null : trade;
            committed.set(true);
            return seen;
        });
        when(trade.isSettled()).thenAnswer(invocation -> {
            boolean seen = committed.get();
            committed.set(true);
            return seen;
        });

        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            trades.when(() -> Trade.completeTrade(agent)).thenAnswer(invocation -> {
                pressed.set(true); // its side locks; the partner has not pressed yet
                return null;
            });

            assertEquals(AgentTradeOutcome.SETTLED, CosmicTradeGateway.INSTANCE.completeTrade(agent));
        }
    }

    @Test
    void aTradeStillOpenAfterItsPressWaitsForThePartner() {
        Character agent = mock(Character.class);
        Trade trade = mock(Trade.class);
        when(agent.getTrade()).thenReturn(trade);

        try (MockedStatic<Trade> ignored = mockStatic(Trade.class)) {
            assertEquals(AgentTradeOutcome.WAITING_FOR_PARTNER, CosmicTradeGateway.INSTANCE.completeTrade(agent));
        }
    }

    @Test
    void aTradeClosedUnsettledByItsPressIsCancelled() {
        Character agent = mock(Character.class);
        Trade trade = mock(Trade.class);
        AtomicBoolean pressed = new AtomicBoolean();
        when(agent.getTrade()).thenAnswer(invocation -> pressed.get() ? null : trade);

        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            trades.when(() -> Trade.completeTrade(agent)).thenAnswer(invocation -> {
                pressed.set(true); // a refusal closes both sides and never marks the trade settled
                return null;
            });

            assertEquals(AgentTradeOutcome.CANCELLED, CosmicTradeGateway.INSTANCE.completeTrade(agent));
        }
    }
}
