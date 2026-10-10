package server.agents.capabilities.trade;

/**
 * What an Agent's press of the Trade button came to (TradeGateway.completeTrade). Its completion thanks only for a trade
 * that settled: before 2026-10-10 it thanked for any gift offered, so a gift refused by the one-of-a-kind limit or a
 * meso limit still got "ty!" and a happy face.
 */
public enum AgentTradeOutcome {
    /** The trade went through: both sides' items and mesos moved. */
    SETTLED,
    /** Its side is locked and the partner has not pressed Trade yet; the partner's press settles or refuses it. */
    WAITING_FOR_PARTNER,
    /** The trade closed with nothing moved: a refusal (bag or meso room, one-of-a-kind, daily meso limit) or a cancel. */
    CANCELLED
}
