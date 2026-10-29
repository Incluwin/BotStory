package config;

/**
 * The one economy (docs/ECONOMY_PLAN.md): E0 telemetry and the E1 switches that stop bots minting meso. A missing
 * {@code economy:} block keeps every default below; each E1 switch turns its old behaviour back on when set false.
 */
public class EconomyConfig {
    /** E0: count every meso made, destroyed or moved (MesoFlows) and close each UTC day into the economy_* tables. */
    public boolean LEDGER_ENABLED = true;
    /** Days economy_transaction_journal and economy_transaction_outbox keep a row; 0 keeps them forever. */
    public int JOURNAL_RETENTION_DAYS = 90;
    /** The price basket (CPI): item ids, each priced every day from sales, else open listings, else the price index. */
    public String CPI_BASKET = "2000004,2000005,2000006,2002004,2020014,"
            + "2040804,2043001,2043801,2044701,2044501,2044001,2043301,2040001,2040301,2040501,2040701,2041013,"
            + "2040805,2043002,2044702,2040502,2049100,2070005,2070006,"
            + "4011000,4011001,4011004,4011006,4021007,4021008,4005000,4005001,4005002,4005003,4005004,"
            + "1302020,1382009,1452022,1472030,1092030,1332025,1002357,1082149";
    /** A day's executed price for a basket item needs at least this many sales; fewer and the listings price it. */
    public int CPI_MIN_SALES = 3;

    /** E1 (decision D2): a mob's meso row drops at its own chance; the drop rate no longer multiplies it. */
    public boolean MESO_CHANCE_IGNORES_DROP_RATE = true;
    /** E1 (decision D3): a drop coupon doubles item drops only, not meso. */
    public boolean DROP_COUPONS_SKIP_MESO = true;
    /** E1: a citizen gets its pocket money once, at its first load, instead of a top-up on every load. */
    public boolean CITIZEN_POCKET_MONEY_ONCE = true;
    /** E1: an offline bench STALL sells its haul at the NPC price, as SELL does; a markup needs a real buyer. */
    public boolean BENCH_OFFLINE_STALL_AT_NPC_PRICE = true;
    /** E1: a citizen founds its guild for the players' fee (server.CREATE_GUILD_COST), from the founder's purse. */
    public boolean CITIZEN_GUILD_FEE = true;
    /** E1: a loadout piece a citizen's gear upgrade displaces leaves its bag instead of staying there to sell. */
    public boolean CITIZEN_DISPLACED_GEAR_REMOVED = true;
    /**
     * E1 (decision D1): at the next boot, before any citizen loads, every citizen purse is set once to a level-shaped
     * draw (lognormal: median PURSE_RESET_MEDIAN x Market.expectedMeso, PURSE_RESET_SIGMA). The old purses are kept
     * in economy_purse_reset; the world flag economy.purse-reset stops it running twice.
     */
    public boolean CITIZEN_PURSE_RESET = false;
    public double PURSE_RESET_MEDIAN = 3.0d;
    public double PURSE_RESET_SIGMA = 0.6d;

    /**
     * E4: at each daily close the controller sets the next day's citizen levers from the day's numbers, within the
     * bounds below, and logs each setting to economy_controller_daily. Off: the levers sit at neutral (every drop picked
     * up, the configured buy budget). CONTROLLER_SHADOW works the settings out and logs them without applying them.
     */
    public boolean CONTROLLER_ENABLED = true;
    public boolean CONTROLLER_SHADOW = false;
    /** The organic daily supply growth it steers to: the day's external net without the purse reset, over the supply. */
    public double CONTROLLER_TARGET_GROWTH = 0.01d;
    /** Pickup selectivity: the least share of monster equip drops a citizen still picks up, and the most a day moves it. */
    public double CONTROLLER_PICKUP_MIN = 0.3d;
    public double CONTROLLER_PICKUP_STEP = 0.15d;
    /**
     * Luxury spending: the most a day's chance can be that a citizen holding over LUXURY_OVER x the meso its level
     * should carry spends on a luxury (a salon restyle), and the most a day moves it. It rises only once pickup is at
     * its least, and eases off before pickup rises again.
     */
    public double CONTROLLER_LUXURY_MAX = 0.5d;
    public double CONTROLLER_LUXURY_STEP = 0.1d;
    public double CONTROLLER_LUXURY_OVER = 3.0d;
    /**
     * Market buy budgets: the bounds of the scale on population.MARKET_BUY_BUDGET_PER_DAY and the most a day moves it;
     * a basket index change within CONTROLLER_CPI_BAND of the day before leaves it.
     */
    public double CONTROLLER_BUDGET_MIN = 0.5d;
    public double CONTROLLER_BUDGET_MAX = 2.0d;
    public double CONTROLLER_BUDGET_STEP = 0.25d;
    public double CONTROLLER_CPI_BAND = 0.02d;

    public void validate() {
        if (JOURNAL_RETENTION_DAYS < 0 || CPI_MIN_SALES < 1) {
            throw new IllegalArgumentException("economy.JOURNAL_RETENTION_DAYS must be >= 0 and CPI_MIN_SALES >= 1");
        }
        if (CPI_BASKET == null || !CPI_BASKET.matches("\\s*\\d+(\\s*,\\s*\\d+)*\\s*")) {
            throw new IllegalArgumentException("economy.CPI_BASKET must be a comma-separated list of item ids");
        }
        if (PURSE_RESET_MEDIAN <= 0d || PURSE_RESET_SIGMA < 0d || PURSE_RESET_SIGMA > 2d) {
            throw new IllegalArgumentException("economy.PURSE_RESET_MEDIAN must be > 0 and PURSE_RESET_SIGMA in 0..2");
        }
        if (CONTROLLER_PICKUP_MIN <= 0d || CONTROLLER_PICKUP_MIN > 1d || CONTROLLER_PICKUP_STEP <= 0d
                || CONTROLLER_BUDGET_MIN <= 0d || CONTROLLER_BUDGET_MAX < CONTROLLER_BUDGET_MIN
                || CONTROLLER_BUDGET_STEP <= 0d || CONTROLLER_CPI_BAND < 0d
                || CONTROLLER_LUXURY_MAX < 0d || CONTROLLER_LUXURY_MAX > 1d || CONTROLLER_LUXURY_STEP <= 0d
                || CONTROLLER_LUXURY_OVER <= 0d) {
            throw new IllegalArgumentException("economy.CONTROLLER_*: PICKUP_MIN in (0, 1], BUDGET_MIN > 0, "
                    + "BUDGET_MAX >= BUDGET_MIN, LUXURY_MAX in 0..1, LUXURY_OVER > 0, steps > 0 and CPI_BAND >= 0");
        }
    }
}
