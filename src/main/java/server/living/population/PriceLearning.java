package server.living.population;

import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.population.needs.Need;
import server.living.population.needs.NeedKind;
import server.living.population.needs.NeedOffers;
import server.living.store.LivingStore;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learned prices (docs/WORLD_SYSTEMS_PLAN.md I2, population.PRICE_LEARNING_ENABLED): three {@link PriceArms} levers in
 * place of fixed numbers, each starting from the rule it replaces as its prior.
 *
 * <ul>
 *   <li><b>ASK</b>, per item class: a citizen's stall ask against the index, arms 0.85 to 1.30 (today's
 *   {@code 0.9 + rand x 0.3} is the prior). Reward per bundle listed, when its stall closes: the bundle's price over
 *   its index value if it sold while the stall was open, else 0.</li>
 *   <li><b>OFFER</b>, for SUPPLY requests: the premium over the shop price a citizen offers on the needs board, arms
 *   1.00 to 1.30 (NEEDS_SUPPLY_PREMIUM is the prior). Reward when the request ends: the share helpers filled times
 *   {@code 1 / (1 + hours / PRICE_OFFER_HALF_HOURS) - PRICE_OFFER_COST x (premium - 1)}; 0 when nobody filled any.
 *   A citizen keeps one arm per potion while its request is open.</li>
 *   <li><b>BENCH</b>, per item class: a benched companion's live STALL starting ask against the index, before its
 *   own markdown (BenchStallForm), arms 0.85 to 1.30 (the index, 1.0, is the prior); rewarded as ASK, per slice.</li>
 * </ul>
 *
 * The buy side (0.9 x index) stays fixed (decision D16). Asks and offers only move meso between holders: no meso is
 * made here. Arm statistics are kept in companion_yield rows of source 'price' (LivingStore.YIELD_PRICE), beside the
 * citizens' spot learning, read once before the first use and written behind on companion-ops; until they have been
 * read, nothing is learned or priced by them (a read failure is tried again a minute later). Off, every call answers
 * as the fixed rule and nothing is booked or stored.
 */
public final class PriceLearning {
    private static final Logger log = LoggerFactory.getLogger(PriceLearning.class);
    static final String SUPPLY_KEY = NeedKind.SUPPLY.name();
    private static final long RETRY_READ_MS = 60_000L;

    private final Population population;
    private final PriceArms asks;
    private final PriceArms offers;
    private final PriceArms bench;
    private final Random random;
    /** citizenId:itemId -> the OFFER arm its open SUPPLY request was priced at. */
    private final Map<String, Integer> offerArms = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile long lastReadFailMs;

    PriceLearning(Population population) {
        this.population = population;
        PopulationConfig config = population.config();
        double sellRate = config == null ? 0.3d : config.PRICE_PRIOR_SELL_RATE;
        double premium = config == null ? 1.15d : config.NEEDS_SUPPLY_PREMIUM;
        double offerPrior = config == null ? 0.4d : config.PRICE_OFFER_PRIOR_REWARD;
        this.asks = new PriceArms("ASK", PriceArms.steps(0.85d, 1.30d, 0.05d),
                m -> m >= 0.9d - 1e-9 && m <= 1.2d + 1e-9 ? m * sellRate : 0.0d);
        this.offers = new PriceArms("OFFER", PriceArms.steps(1.00d, 1.30d, 0.05d),
                m -> Math.abs(m - premium) <= 0.05d + 1e-9 ? offerPrior : 0.0d);
        this.bench = new PriceArms("BENCH", PriceArms.steps(0.85d, 1.30d, 0.05d),
                m -> Math.abs(m - 1.0d) < 1e-9 ? sellRate : 0.0d);
        this.random = new Random((config == null ? 0L : config.SEED) ^ 0x5eed_1210L);
    }

    /** The switch, read live. */
    public boolean on() {
        PopulationConfig config = population.config();
        return config != null && config.PRICE_LEARNING_ENABLED;
    }

    /** On, and the stored arms have been read (or there is no living world to store them in). */
    boolean ready() {
        return on() && load();
    }

    PriceArms.Tuning tuning() {
        PopulationConfig config = population.config();
        return new PriceArms.Tuning(config.PRICE_EXPLORE, config.PRICE_TEMPERATURE, config.PRICE_PRIOR_SAMPLES);
    }

    PriceArms asks() {
        return asks;
    }

    PriceArms offers() {
        return offers;
    }

    PriceArms bench() {
        return bench;
    }

    // ---- asks ------------------------------------------------------------------------------

    /** The ASK arm for one item on a stall opening now; -1 when the learner is not in play (the fixed rule then). */
    int askArm(int itemId, Random draw) {
        return ready() ? asks.pick(PriceArms.ItemClass.of(itemId).name(), tuning(), draw) : -1;
    }

    /**
     * A stall line settled at its close: {@code listed} bundles at {@code ratio} (bundle price over its index value),
     * {@code sold} of them sold while it was open.
     */
    void askSettled(int itemId, int arm, double ratio, int listed, int sold) {
        settle(asks, PriceArms.ItemClass.of(itemId).name(), arm, ratio, listed, sold);
    }

    // ---- bench stall asks ------------------------------------------------------------------

    /** The BENCH arm for one item on a live bench STALL slice; -1 when the learner is not in play. */
    public int benchArm(int itemId) {
        if (!ready()) {
            return -1;
        }
        synchronized (random) {
            return bench.pick(PriceArms.ItemClass.of(itemId).name(), tuning(), random);
        }
    }

    public double benchMultiplier(int arm) {
        return bench.multiplier(arm);
    }

    /** A bench STALL slice's line settled at its close, as {@link #askSettled}. */
    public void benchSettled(int itemId, int arm, double ratio, int listed, int sold) {
        settle(bench, PriceArms.ItemClass.of(itemId).name(), arm, ratio, listed, sold);
    }

    private void settle(PriceArms lever, String key, int arm, double ratio, int listed, int sold) {
        if (arm < 0 || arm >= lever.arms() || listed <= 0 || !ready()) {
            return;
        }
        int took = Math.max(0, Math.min(listed, sold));
        PriceArms.Row row = lever.reward(key, arm, ratio, took);
        if (listed - took > 0) {
            row = lever.reward(key, arm, 0.0d, listed - took);
        }
        save(lever, row);
    }

    // ---- board offers ----------------------------------------------------------------------

    /** The premium a SUPPLY request of {@code citizenId} for {@code itemId} offers: the learned arm's, else {@code fixed}. */
    double offerPremium(int citizenId, int itemId, double fixed) {
        if (!ready()) {
            return fixed;
        }
        Integer open = offerArms.get(citizenId + ":" + itemId);
        if (open != null) {
            return offers.multiplier(open); // its open request keeps the offer it was posted at (NeedLedger.reconcile)
        }
        int arm;
        synchronized (random) {
            arm = offers.pick(SUPPLY_KEY, tuning(), random);
        }
        if (arm < 0) {
            return fixed;
        }
        offerArms.put(citizenId + ":" + itemId, arm);
        return offers.multiplier(arm);
    }

    /** A request ended: a SUPPLY one priced by an arm is rewarded by how (and how fast) it ended. */
    void offerClosed(Need closed, long nowMs) {
        if (closed == null || closed.kind() != NeedKind.SUPPLY) {
            return;
        }
        Integer arm = offerArms.remove(closed.citizenId() + ":" + closed.itemId());
        if (arm == null || !ready()) {
            return;
        }
        PopulationConfig config = population.config();
        long ended = closed.closedAtMs() > 0L ? closed.closedAtMs() : nowMs;
        double hours = Math.max(0L, ended - closed.createdAtMs()) / 3_600_000.0d;
        double reward = offerReward(fulfilled(closed), hours, offers.multiplier(arm), config.PRICE_OFFER_HALF_HOURS,
                config.PRICE_OFFER_COST);
        save(offers, offers.reward(SUPPLY_KEY, arm, reward, 1.0d));
    }

    /** The share of a request helpers filled: all of a MET one, else what was handed in of what it asked for. */
    static double fulfilled(Need closed) {
        if (closed.state() == Need.State.MET) {
            return 1.0d;
        }
        return closed.quantity() <= 0 ? 0.0d : Math.min(1.0d, Math.max(0, closed.delivered()) / (double) closed.quantity());
    }

    /**
     * {@code fulfilled} (0..1) of the request filled by helpers, {@code hours} after it was posted: the share filled
     * times (the sooner the better, less what the premium cost the citizen); 0 for one nobody filled.
     */
    static double offerReward(double fulfilled, double hours, double premium, double halfHours, double cost) {
        if (fulfilled <= 0.0d) {
            return 0.0d;
        }
        return fulfilled * (1.0d / (1.0d + hours / Math.max(1e-9d, halfHours)) - cost * (premium - 1.0d));
    }

    // ---- store and view --------------------------------------------------------------------

    private boolean load() {
        if (loaded) {
            return true;
        }
        LivingWorld world = population.world();
        if (world == null || world.store() == null) {
            loaded = true; // nowhere to keep them: in memory since boot
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastReadFailMs < RETRY_READ_MS) {
            return false;
        }
        synchronized (this) {
            if (loaded) {
                return true;
            }
            try {
                List<LivingStore.YieldRow> rows = world.store().priceArms();
                for (LivingStore.YieldRow row : rows) {
                    PriceArms lever = row.jobBranch().startsWith("ASK_") ? asks
                            : row.jobBranch().startsWith("OFFER_") ? offers
                            : row.jobBranch().startsWith("BENCH_") ? bench : null;
                    if (lever != null) {
                        lever.load(row.jobBranch().substring(lever.lever().length() + 1), row.levelBand(),
                                row.samples(), row.expPerHour());
                    }
                }
                loaded = true;
                log.info("price learning read {} arm rows", rows.size());
            } catch (RuntimeException failure) {
                lastReadFailMs = now;
                log.warn("price learning could not read its arms; the fixed prices hold until it can", failure);
            }
        }
        return loaded;
    }

    private void save(PriceArms lever, PriceArms.Row row) {
        LivingWorld world = population.world();
        if (world == null || world.store() == null || world.executor() == null) {
            return;
        }
        LivingStore.YieldRow stored = new LivingStore.YieldRow(lever.storedKey(row.key()), row.arm(), 0, row.n(),
                row.mean(), row.multiplier(), 0.0d, 0.0d);
        world.executor().submit("price.arm " + stored.jobBranch(), () -> world.store().savePriceArm(stored));
    }

    /** The levers as JSON for /runtime/stats/market: on, and per lever and key each arm's count and mean. */
    String json() {
        double k = population.config() == null ? 0.0d : population.config().PRICE_PRIOR_SAMPLES;
        return "{\"on\":" + on() + ",\"loaded\":" + loaded + ",\"ask\":" + asks.json(k) + ",\"offer\":" + offers.json(k)
                + ",\"bench\":" + bench.json(k) + "}";
    }

    /** Installs this learner behind the needs board's SUPPLY offers (NeedOffers). */
    void install() {
        NeedOffers.install(new NeedOffers.Learner() {
            @Override
            public double premium(int citizenId, int itemId, double fixed) {
                return offerPremium(citizenId, itemId, fixed);
            }

            @Override
            public void closed(Need closed, long nowMs) {
                offerClosed(closed, nowMs);
            }
        });
    }
}
