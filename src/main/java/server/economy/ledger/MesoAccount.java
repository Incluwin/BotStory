package server.economy.ledger;

import server.economy.EconomyOperationKind;

/**
 * Where a meso movement comes from or goes to (docs/ECONOMY_PLAN.md E0). An external account creates or destroys meso:
 * the server's money supply changes by its amount. An internal one only moves meso between holders or pools (a trade,
 * a shop sale, the storage), so across the whole world it sums to zero, less whatever {@link #TAX} took.
 */
public enum MesoAccount {
    // faucets and sinks
    MOB_DROP(true),
    FLOOR(true),
    NPC_SALE(true),
    NPC_PURCHASE(true),
    NPC_RECHARGE(true),
    NPC_BUYBACK(true),
    QUEST(true),
    EVENT(true),
    SCRIPT(true),
    MAKER(true),
    ITEM(true),
    COUPON(true),
    CASH_SHOP(true),
    SKILL(true),
    GUILD(true),
    SERVICE(true),
    GM_GRANT(true),
    TOP_UP(true),
    BENCH_SIM(true),
    BENCH_SALE(true),
    AGENT_SIM(true),
    RESET(true),
    TAX(true),
    FREDRICK_FEE(true),
    /** A purse change no source claimed: counted as external so the day still balances, and listed so it gets a name. */
    UNATTRIBUTED(true),
    // transfers
    TRADE(false),
    PLAYER_SHOP(false),
    HIRED_MERCHANT(false),
    STORAGE(false),
    DUEY(false),
    FREDRICK(false),
    HAUL(false),
    COMPANION(false),
    NEEDS(false),
    /** A rival crew's stake, paid from its fighters' purses to the human who beat them (docs/PVP_PLAN.md V3.3). */
    PVP_STAKE(false),
    // a sink booked after the transfers so ordinal-keyed tallies keep their old slots: the coliseum ladder's entry fee
    PVP_FEE(true),
    /** The weekly tournament's prize money, paid out of its entry pool (a faucet; the house keeps a quarter, V5). */
    PVP_PRIZE(true);

    private final boolean external;

    MesoAccount(boolean external) {
        this.external = external;
    }

    public boolean external() {
        return external;
    }

    /** The account an {@link server.economy.EconomyTransactionCoordinator} operation's meso books to by default. */
    public static MesoAccount forOperation(EconomyOperationKind kind) {
        return switch (kind) {
            case SHOP_BUY -> NPC_PURCHASE;
            case SHOP_SELL -> NPC_SALE;
            case SHOP_RECHARGE -> NPC_RECHARGE;
            case SHOP_BUYBACK -> NPC_BUYBACK;
            case PLAYER_SHOP_LIST, PLAYER_SHOP_SALE -> PLAYER_SHOP;
            case PLAYER_TRADE -> TRADE;
            case SCROLL_APPLY -> SERVICE;
            case COMPANION_GIFT -> COMPANION;
            case CASH_SHOP_WINDOW -> CASH_SHOP;
            case NEED_FULFILLED -> NEEDS;
        };
    }
}
