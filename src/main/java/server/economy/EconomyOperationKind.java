package server.economy;

public enum EconomyOperationKind {
    SHOP_BUY,
    SHOP_SELL,
    SHOP_RECHARGE,
    PLAYER_SHOP_LIST,
    PLAYER_SHOP_SALE,
    PLAYER_TRADE,
    OFFSCREEN_FARM_SETTLEMENT,
    SCROLL_APPLY,
    QUEST_START,
    QUEST_TURN_IN,
    VENUE_SUBSIDY,
    /** A companion's owner gives it coin for its supplies (docs/BOT_MANAGEMENT_PLAN.md P3b). */
    COMPANION_GIFT,
    /** A player buys back what they sold to an NPC shop, for what the sale paid (server.ShopBuyback). */
    SHOP_BUYBACK
}
