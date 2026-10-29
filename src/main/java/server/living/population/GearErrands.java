package server.living.population;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.knowledge.Decisions;
import server.knowledge.GearPicks;
import server.knowledge.ItemFact;
import server.knowledge.WorldKnowledge;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buy, don't receive, gear (docs/ECONOMY_PLAN.md E3): a citizen's level-band upgrade (CitizenGear) becomes an errand to
 * buy a piece for that slot at a stall, any seller's, players' first: one that fits the slot, its level band, job, gender
 * and stats and beats what it wears, at up to MARKET_GEAR_OVER_INDEX x the index. Only when none turns up within
 * MARKET_GEAR_ERRAND_HOURS, or it cannot pay the pick's index price, is the piece handed to it as before, and that gap
 * is logged. Errands are kept in living_gear_errand, so a restart keeps their deadlines (before, each restart reopened
 * every one with a fresh deadline, and with daily restarts none ever ran out).
 */
final class GearErrands implements CitizenGear.Wants {
    private static final Logger log = LoggerFactory.getLogger(GearErrands.class);
    private static final long HOUR_MS = 3_600_000L;
    /** An errand this long past its deadline belongs to a citizen that never loaded again (retired): dropped at start. */
    private static final long PRUNE_AFTER_MS = 7 * 24 * HOUR_MS;
    private static final String WEAPON = "weapon";

    /** Shopping for {@code slot}: an equip of {@code category} above {@code wornLevel}; a weapon keeps its type. */
    record Errand(String slot, String category, int wornLevel, int weaponType, long untilMs) { }

    /** What a citizen can wear, as of its last gear check: its level band, job branch, gender and base stats. */
    private record Shopper(int band, int jobMask, int gender, int[] stats) {
        static Shopper of(Character agent) {
            int branch = agent.getJob().getId() / 100 % 10;
            return new Shopper(agent.getLevel() / 10 * 10, branch < 1 ? 0 : 1 << (branch - 1), agent.getGender(),
                    new int[]{agent.getStr(), agent.getDex(), agent.getInt(), agent.getLuk()});
        }
    }

    private final Population population;
    private final Market market;
    private final Map<Integer, Map<String, Errand>> open = new ConcurrentHashMap<>();
    /**
     * The citizens whose gear was checked since boot (CitizenGear at a load), by id: the errands a stall can fill, as
     * only a loaded citizen comes to the market to buy (errands loaded from living_gear_errand wait for their load).
     */
    private final Map<Integer, Shopper> shoppers = new ConcurrentHashMap<>();
    private final AtomicLong opened = new AtomicLong();
    private final AtomicLong bought = new AtomicLong();
    private final AtomicLong handedOver = new AtomicLong();

    GearErrands(Population population, Market market) {
        this.population = population;
        this.market = market;
        CitizenStore store = population.store();
        store.pruneGearErrands(System.currentTimeMillis() - PRUNE_AFTER_MS);
        store.gearErrands().forEach((citizenId, errands) -> open.put(citizenId, new ConcurrentHashMap<>(errands)));
    }

    /** From CitizenGear at a load: true while the citizen shops for this slot, false to hand {@code pick} over. */
    @Override
    public boolean holdBack(Character agent, String slot, int pick) {
        PopulationConfig config = population.config();
        WorldKnowledge k = Decisions.active();
        if (!config.MARKET_ENABLED || !config.MARKET_BUY_GEAR || k == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        shoppers.put(agent.getId(), Shopper.of(agent));
        Map<String, Errand> errands = open.computeIfAbsent(agent.getId(), id -> new ConcurrentHashMap<>());
        Errand errand = errands.get(slot);
        if (errand != null) {
            if (now < errand.untilMs()) {
                return true;
            }
            errands.remove(slot);
            population.store().deleteGearErrand(agent.getId(), slot);
            handedOver.incrementAndGet();
            log.info("citizen {} found no {} at a stall in {} h: handed over as before", agent.getName(), slot,
                    config.MARKET_GEAR_ERRAND_HOURS);
            return false;
        }
        Optional<ItemFact> fact = k.item(pick);
        if (fact.isEmpty() || agent.getMeso() < market.quote(pick)) {
            handedOver.incrementAndGet(); // it could not pay a stall for it: handed over as before
            return false;
        }
        short position = CitizenGear.position(slot);
        Item worn = position == 0 ? null : agent.getInventory(InventoryType.EQUIPPED).getItem(position);
        int wornLevel = worn == null ? -1 : k.item(worn.getItemId()).map(ItemFact::reqLevel).orElse(0);
        Errand opening = new Errand(slot, fact.get().category(), wornLevel, WEAPON.equals(slot) ? pick / 10_000 : 0,
                now + config.MARKET_GEAR_ERRAND_HOURS * HOUR_MS);
        errands.put(slot, opening);
        population.store().saveGearErrand(agent.getId(), opening);
        opened.incrementAndGet();
        return true;
    }

    boolean any(int citizenId) {
        Map<String, Errand> errands = open.get(citizenId);
        return errands != null && !errands.isEmpty();
    }

    /** The most {@code agent} pays for one {@code itemId} on an open errand it fits; 0 for none. */
    long offer(Character agent, int itemId) {
        Errand errand = errandFor(agent, itemId);
        return errand == null ? 0L : Math.round(market.quote(itemId) * population.config().MARKET_GEAR_OVER_INDEX);
    }

    /** It bought {@code itemId} at a stall: worn for the errand it fits, which then ends. */
    void bought(Character agent, int itemId) {
        Errand errand = errandFor(agent, itemId);
        if (errand == null || !CitizenGear.wearBought(agent, itemId, errand.slot())) {
            return;
        }
        open.getOrDefault(agent.getId(), Map.of()).remove(errand.slot());
        population.store().deleteGearErrand(agent.getId(), errand.slot());
        bought.incrementAndGet();
        WorldKnowledge k = Decisions.active();
        log.info("citizen {} bought its {} at a stall: {}", agent.getName(), errand.slot(),
                k == null ? itemId : k.item(itemId).map(ItemFact::name).orElse(String.valueOf(itemId)));
    }

    String report() {
        return "gearErrands: open=" + openCount() + " opened=" + opened.get() + " bought=" + bought.get()
                + " handedOver=" + handedOver.get();
    }

    /** The same counts for the admin port's /runtime/stats/market. */
    String json() {
        return "{\"open\":" + openCount() + ",\"opened\":" + opened.get() + ",\"bought\":" + bought.get()
                + ",\"handed_over\":" + handedOver.get() + "}";
    }

    private int openCount() {
        return open.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * How many open errands one {@code itemId} at {@code sellerId}'s stall would fill: those of the other citizens
     * whose gear was checked since boot. A stall lists the equips most wanted first (E3), as a stall's eight lines
     * ranked by value alone were mostly scrolls and 1.5 % of the time held a piece any of 1,020 errands could buy
     * (live 10-29), though 997 of them fit a piece in some merchant's bag.
     */
    int demand(int sellerId, int itemId) {
        WorldKnowledge k = open.isEmpty() ? null : Decisions.active();
        Optional<ItemFact> fact = k == null ? Optional.empty() : k.item(itemId);
        if (fact.isEmpty() || !fact.get().equip()) {
            return 0;
        }
        int wanted = 0;
        for (Map.Entry<Integer, Map<String, Errand>> errands : open.entrySet()) {
            Shopper shopper = errands.getKey() == sellerId ? null : shoppers.get(errands.getKey());
            if (shopper != null && fitting(shopper, errands.getValue(), fact.get()) != null) {
                wanted++;
            }
        }
        return wanted;
    }

    private Errand errandFor(Character agent, int itemId) {
        Map<String, Errand> errands = agent == null ? null : open.get(agent.getId());
        WorldKnowledge k = errands == null || errands.isEmpty() ? null : Decisions.active();
        Optional<ItemFact> fact = k == null ? Optional.empty() : k.item(itemId);
        return fact.isEmpty() ? null : fitting(Shopper.of(agent), errands, fact.get());
    }

    /** The errand of {@code errands} {@code item} fills for {@code shopper}: above what it wears and fit to wear; or null. */
    private static Errand fitting(Shopper shopper, Map<String, Errand> errands, ItemFact item) {
        for (Errand errand : errands.values()) {
            if (item.reqLevel() > errand.wornLevel() && GearPicks.fitsSlot(item, errand.category(), shopper.band(),
                    shopper.jobMask(), shopper.gender(), errand.weaponType(), shopper.stats())) {
                return errand;
            }
        }
        return null;
    }
}
