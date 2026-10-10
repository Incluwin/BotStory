package server.agents.integration.cosmic;

import client.Character;
import server.Trade;
import server.agents.capabilities.trade.AgentTradeOutcome;
import server.agents.capabilities.trade.AgentTradeWindow;
import server.agents.integration.TradeGateway;

public enum CosmicTradeGateway implements TradeGateway {
    INSTANCE;

    @Override
    public void startTrade(Character agent) {
        Trade.startTrade(agent);
    }

    @Override
    public void inviteTrade(Character agent, Character recipient) {
        Trade.inviteTrade(agent, recipient);
    }

    @Override
    public void cancelNoResponse(Character agent) {
        Trade.cancelTrade(agent, Trade.TradeResult.NO_RESPONSE);
    }

    @Override
    public AgentTradeOutcome completeTrade(Character agent) {
        Trade trade = agent.getTrade();
        Trade.completeTrade(agent);
        if (trade == null) {
            return AgentTradeOutcome.CANCELLED;
        }
        // "still its trade?" is read before "settled?": the partner's press can commit on its own tick shard between the
        // two reads, and a commit marks the trade settled before it closes it, so a trade seen closed here and settled
        // next is a commit, not a refusal (deploy review 2026-10-10)
        boolean stillOpen = agent.getTrade() == trade;
        if (trade.isSettled()) {
            return AgentTradeOutcome.SETTLED;
        }
        // still its trade: the handshake waits for the partner's press; gone: a refusal or a cancel closed it
        return stillOpen ? AgentTradeOutcome.WAITING_FOR_PARTNER : AgentTradeOutcome.CANCELLED;
    }

    @Override
    public void visitTrade(Character agent, Character inviter) {
        Trade.visitTrade(agent, inviter);
    }

    @Override
    public AgentTradeWindow currentWindow(Character agent) {
        return CosmicAgentTradeWindow.wrap(agent.getTrade());
    }
}
