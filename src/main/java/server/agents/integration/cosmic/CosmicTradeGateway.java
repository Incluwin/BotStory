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
        if (trade != null && trade.isSettled()) {
            return AgentTradeOutcome.SETTLED;
        }
        // still its trade: the handshake waits for the partner's press; gone: a refusal or a cancel closed it
        return trade != null && agent.getTrade() == trade
                ? AgentTradeOutcome.WAITING_FOR_PARTNER
                : AgentTradeOutcome.CANCELLED;
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
