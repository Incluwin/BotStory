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
    }
}
