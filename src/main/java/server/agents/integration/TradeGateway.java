package server.agents.integration;

import client.Character;
import server.agents.capabilities.trade.AgentTradeOutcome;
import server.agents.capabilities.trade.AgentTradeWindow;

@AgentGatewayAffinity(
        value = AgentGatewayThreadAffinity.SHARD_SAFE_DIRECT,
        rationale = "Trade operations use Cosmic trade synchronization and one Agent-session writer.")
public interface TradeGateway {
    void startTrade(Character agent);

    void inviteTrade(Character agent, Character recipient);

    void cancelNoResponse(Character agent);

    /** Presses the Agent's Trade button and says what came of it (read from the trade itself, after the press). */
    AgentTradeOutcome completeTrade(Character agent);

    void visitTrade(Character agent, Character inviter);

    AgentTradeWindow currentWindow(Character agent);
}
