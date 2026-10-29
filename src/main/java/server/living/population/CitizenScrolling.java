package server.living.population;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.PopulationConfig;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.ScrollTransactionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.DoubleSupplier;
import java.util.function.IntFunction;
import java.util.function.IntToLongFunction;

/**
 * Citizens scroll their own gear (docs/ECONOMY_PLAN.md E3). Citizens loot scrolls at the server's drop rates and never
 * sold or used them (34,368 sat in their bags on 2026-10-29). Now a citizen out training, in town, browsing or AFK uses
 * a scroll from its bag on a worn piece with slots left, about one each eight minutes, up to SCROLLS_PER_DAY; a
 * browsing citizen with meso to spare buys a fitting one at a stall when its bag has none, and uses it at once. Each goes
 * through the real scrolling (ScrollTransactionService): the scroll is used up, the slot spent on a fail, the effect
 * shown to the map. Only scrolls that cannot destroy the piece are used (no dark, chaos or clean-slate ones), and only
 * ones worth a slot to its job; the rest stay in the bag for its stall.
 */
final class CitizenScrolling {
    private static final Logger log = LoggerFactory.getLogger(CitizenScrolling.class);
    private static final long DAY_MS = 86_400_000L;
    /**
     * The chance a citizen with a scroll to use uses it at a given tick: about one each eight minutes, so the citizens
     * do not all scroll (and save) at the same tick after a boot.
     */
    private static final double TICK_CHANCE = 0.25d;

    /** Applies {@code scrollSlot} (Use) to the worn {@code equipSlot}; the outcome, or null when it was refused. */
    interface Applier {
        String apply(Character agent, short scrollSlot, short equipSlot);
    }

    /** A scroll from the bag and the worn piece it goes on, worth {@code value} to the citizen's job. */
    record Pick(Item scroll, Equip equip, double value) { }

    private final Population population;
    private final IntToLongFunction quote;
    private final IntFunction<Map<String, Integer>> stats;
    private final BiPredicate<Integer, Integer> fits;
    private final Applier applier;
    private final Map<Integer, int[]> usedToday = new ConcurrentHashMap<>();
    private final AtomicLong used = new AtomicLong();
    private final AtomicLong bought = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Set<Integer> personas;
    /** An item's name for the log and the citizen's journal (WZ strings; a test's own). */
    IntFunction<String> names = Market::itemName;
    /** A roll in [0, 1) against TICK_CHANCE (a test's own). */
    DoubleSupplier roll = () -> ThreadLocalRandom.current().nextDouble();

    /** {@code quote}: the market's index price. */
    CitizenScrolling(Population population, IntToLongFunction quote) {
        this(population, quote, itemId -> ItemInformationProvider.getInstance().getEquipStats(itemId),
                (scrollId, equipId) -> ItemInformationProvider.getInstance().canApplyScroll(scrollId, equipId),
                (agent, scrollSlot, equipSlot) -> {
                    ScrollTransactionService.Result result =
                            ScrollTransactionService.apply(agent.getClient(), scrollSlot, equipSlot, (byte) 0);
                    return result.applied() ? result.outcome() : null;
                });
    }

    /** {@code stats}: a scroll's WZ info (success, cursed, inc* bonuses); {@code fits}: canApplyScroll. */
    CitizenScrolling(Population population, IntToLongFunction quote, IntFunction<Map<String, Integer>> stats,
                     BiPredicate<Integer, Integer> fits, Applier applier) {
        this.population = population;
        this.quote = quote;
        this.stats = stats;
        this.fits = fits;
        this.applier = applier;
    }

    /**
     * At a market tick: a loaded citizen out and about uses one scroll from its bag, if it has one worth a slot, with
     * TICK_CHANCE.
     */
    void tick(CitizenSession session, Character agent, long now) {
        if (!enabled() || !mayScroll(session, agent) || capped(agent.getId(), now) || roll.getAsDouble() >= TICK_CHANCE) {
            return;
        }
        Pick pick = best(agent, bagScrolls(agent), population.config().SCROLL_MIN_VALUE);
        if (pick != null) {
            String outcome = apply(agent, pick, now);
            if (outcome != null) {
                session.journal.add("gear", "used " + names.apply(pick.scroll().getItemId()) + " on its "
                        + names.apply(pick.equip().getItemId()) + ": " + outcome.toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    /**
     * The most {@code agent} pays for one {@code itemId} at a stall: a safe scroll worth SCROLL_BUY_MIN_VALUE to its job
     * for a worn piece with slots left, when its bag holds none it would use first, up to SCROLL_BUY_OVER_INDEX x the
     * index and the meso it has over SCROLL_BUY_RESERVE x what its level should carry; 0 for none.
     */
    long offer(Character agent, int itemId) {
        if (!enabled() || itemId / 10_000 != 204 || agent == null || !eligible(agent.getId())
                || capped(agent.getId(), System.currentTimeMillis())) {
            return 0L;
        }
        PopulationConfig config = population.config();
        Map<String, Integer> info = stats.apply(itemId);
        if (!safe(itemId, info) || value(agent.getJob().getId(), info) < config.SCROLL_BUY_MIN_VALUE
                || target(agent, itemId) == null) {
            return 0L;
        }
        long spare = agent.getMeso() - Math.round(config.SCROLL_BUY_RESERVE * Market.expectedMeso(agent.getLevel()));
        if (spare <= 0L || best(agent, bagScrolls(agent), config.SCROLL_MIN_VALUE) != null) {
            return 0L;
        }
        return Math.min(Math.round(quote.applyAsLong(itemId) * config.SCROLL_BUY_OVER_INDEX), spare);
    }

    /** It bought {@code itemId} at a stall for a scroll want: used on the piece it was for. */
    void bought(Character agent, int itemId) {
        if (!enabled() || itemId / 10_000 != 204) {
            return;
        }
        Item scroll = agent.getInventory(InventoryType.USE).findById(itemId);
        Pick pick = scroll == null ? null : best(agent, List.of(scroll), 0.0d);
        if (pick != null) {
            bought.incrementAndGet();
            apply(agent, pick, System.currentTimeMillis());
        }
    }

    /** Whether the citizen keeps {@code item} off its stall: a scroll from its bag it would use itself. */
    boolean keeps(Character agent, Item item) {
        return enabled() && item.getItemId() / 10_000 == 204 && eligible(agent.getId())
                && best(agent, List.of(item), population.config().SCROLL_MIN_VALUE) != null;
    }

    String report() {
        return "scrolls: used=" + used.get() + " bought=" + bought.get() + " success=" + succeeded.get()
                + " fail=" + failed.get();
    }

    /** The same counts for the admin port's /runtime/stats/market. */
    String json() {
        return "{\"used\":" + used.get() + ",\"bought\":" + bought.get() + ",\"success\":" + succeeded.get()
                + ",\"fail\":" + failed.get() + "}";
    }

    // ---- choosing ------------------------------------------------------------------------

    /** The scroll worth most to the citizen's job, at least {@code minValue}, and a worn piece with slots it fits. */
    Pick best(Character agent, List<Item> scrolls, double minValue) {
        int job = agent.getJob().getId();
        Pick best = null;
        for (Item scroll : scrolls) {
            Map<String, Integer> info = stats.apply(scroll.getItemId());
            if (scroll.getQuantity() < 1 || !safe(scroll.getItemId(), info)) {
                continue;
            }
            double value = value(job, info);
            if (value <= 0.0d || value < minValue || best != null && value <= best.value()) {
                continue;
            }
            Equip equip = target(agent, scroll.getItemId());
            if (equip != null) {
                best = new Pick(scroll, equip, value);
            }
        }
        return best;
    }

    private Equip target(Character agent, int scrollId) {
        for (Item worn : agent.getInventory(InventoryType.EQUIPPED).list()) {
            if (worn instanceof Equip equip && equip.getUpgradeSlots() > 0 && fits.test(scrollId, equip.getItemId())) {
                return equip;
            }
        }
        return null;
    }

    private static List<Item> bagScrolls(Character agent) {
        List<Item> scrolls = new ArrayList<>();
        for (Item item : agent.getInventory(InventoryType.USE).list()) {
            if (item.getItemId() / 10_000 == 204) {
                scrolls.add(item);
            }
        }
        return scrolls;
    }

    /** A scroll that cannot destroy the piece: no dark (cursed), chaos, clean-slate or flag scroll, and a success rate. */
    static boolean safe(int itemId, Map<String, Integer> info) {
        return itemId / 10_000 == 204 && info != null && !ItemConstants.isChaosScroll(itemId)
                && !ItemConstants.isCleanSlate(itemId) && !ItemConstants.isModifierScroll(itemId)
                && info.getOrDefault("success", 0) > 0 && info.getOrDefault("cursed", 0) == 0;
    }

    /**
     * What the scroll's bonuses are worth to a job, in points of its attack: its attack 1, its main stat 0.6, its second
     * 0.3, accuracy 0.2 (a mage's 0.05), avoid, speed and jump 0.05, defence 0.03, HP and MP 0.005 a point. A warrior
     * gives nothing for magic attack or INT, so a scroll for a stat it does not use is worth 0 to it.
     */
    static double value(int jobId, Map<String, Integer> info) {
        int branch = branch(jobId);
        boolean mage = branch == 2;
        String main = switch (branch) {
            case 2 -> "INT";
            case 3 -> "DEX";
            case 4 -> "LUK";
            case 52 -> "DEX";
            default -> "STR";
        };
        String second = switch (branch) {
            case 2 -> "LUK";
            case 3, 52 -> "STR";
            default -> "DEX";
        };
        double points = info.getOrDefault(mage ? "MAD" : "PAD", 0)
                + 0.6d * info.getOrDefault(main, 0) + 0.3d * info.getOrDefault(second, 0)
                + (mage ? 0.05d : 0.2d) * info.getOrDefault("ACC", 0)
                + 0.05d * (info.getOrDefault("EVA", 0) + info.getOrDefault("Speed", 0) + info.getOrDefault("Jump", 0))
                + 0.03d * (info.getOrDefault("PDD", 0) + info.getOrDefault("MDD", 0))
                + 0.005d * (info.getOrDefault("MHP", 0) + info.getOrDefault("MMP", 0));
        return points;
    }

    /** 1 warrior, 2 magician, 3 bowman, 4 thief, 5 brawler, 52 gunslinger; Cygnus and Aran as their Explorer branch. */
    static int branch(int jobId) {
        if (jobId >= 2000) {
            return 1; // Aran
        }
        int explorer = jobId >= 1000 ? jobId - 1000 : jobId;
        if (explorer / 10 == 52) {
            return 52;
        }
        int branch = explorer / 100;
        return branch == 0 ? 1 : branch;
    }

    // ---- applying ------------------------------------------------------------------------

    private String apply(Character agent, Pick pick, long now) {
        if (!agent.getClient().tryacquireClient()) {
            return null;
        }
        String outcome;
        try {
            outcome = applier.apply(agent, pick.scroll().getPosition(), pick.equip().getPosition());
        } finally {
            agent.getClient().releaseClient();
        }
        if (outcome == null) {
            return null;
        }
        count(agent.getId(), now);
        used.incrementAndGet();
        (Equip.ScrollResult.SUCCESS.name().equals(outcome) ? succeeded : failed).incrementAndGet();
        log.info("citizen {} used {} on its {}: {}", agent.getName(), names.apply(pick.scroll().getItemId()),
                names.apply(pick.equip().getItemId()), outcome);
        return outcome;
    }

    private boolean enabled() {
        PopulationConfig config = population.config();
        return config.MARKET_ENABLED && config.MARKET_SCROLLING;
    }

    /** Out training, in town, browsing or AFK, its stall shut: not in a run, on a job errand, at a minigame or a JQ. */
    private boolean mayScroll(CitizenSession session, Character agent) {
        CitizenSession.Activity activity = session.activity;
        return !session.virtual && agent != null && !session.stallOpen && eligible(agent.getId())
                && (activity == CitizenSession.Activity.TRAIN || activity == CitizenSession.Activity.TOWN
                || activity == CitizenSession.Activity.MARKET || activity == CitizenSession.Activity.AFK);
    }

    /** A citizen of the population's own: no player's hired hand, no persona (its gear is drawn elsewhere). */
    private boolean eligible(int characterId) {
        Citizen citizen = population.citizen(characterId);
        return citizen != null && !citizen.hired() && !personas().contains(characterId);
    }

    private boolean capped(int characterId, long now) {
        int[] count = usedToday.get(characterId);
        if (count == null) {
            return false;
        }
        synchronized (count) {
            return count[0] == (int) (now / DAY_MS) && count[1] >= population.config().SCROLLS_PER_DAY;
        }
    }

    private void count(int characterId, long now) {
        int day = (int) (now / DAY_MS);
        int[] count = usedToday.computeIfAbsent(characterId, id -> new int[]{day, 0});
        synchronized (count) {
            if (count[0] != day) {
                count[0] = day;
                count[1] = 0;
            }
            count[1]++;
        }
    }

    private Set<Integer> personas() {
        Set<Integer> known = personas;
        if (known == null) {
            known = population.store().personaCharacterIds();
            personas = known;
        }
        return known;
    }
}
