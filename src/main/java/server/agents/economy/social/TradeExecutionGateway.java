package server.agents.economy.social;

/** Cosmic adapter must execute this through EconomyTransactionCoordinator/real Trade primitives. */
public interface TradeExecutionGateway {
    Result execute(String idempotencyKey, String firstAgentId, TradeOffer firstOffer,
                   String secondAgentId, TradeOffer secondOffer);

    /**
     * {@code tradeOpened}: both sides joined a real Trade window, so a failure is that trade's own outcome (a refusal,
     * a holding that changed in it). False when it failed before any trade opened: the two were apart, one was
     * already trading or had an invite pending.
     */
    record Result(boolean succeeded, String transactionId, String evidence, boolean tradeOpened) { }
}
