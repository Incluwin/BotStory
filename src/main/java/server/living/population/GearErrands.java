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

    private final Population population;
    private final Market market;
    private final Map<Integer, Map<String, Errand>> open = new ConcurrentHashMap<>();
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

    private Errand errandFor(Character agent, int itemId) {
        Map<String, Errand> errands = agent == null ? null : open.get(agent.getId());
        WorldKnowledge k = errands == null || errands.isEmpty() ? null : Decisions.active();
        Optional<ItemFact> fact = k == null ? Optional.empty() : k.item(itemId);
        if (fact.isEmpty()) {
            return null;
        }
        int branch = agent.getJob().getId() / 100 % 10;
        int jobMask = branch < 1 ? 0 : 1 << (branch - 1);
        int[] stats = {agent.getStr(), agent.getDex(), agent.getInt(), agent.getLuk()};
        int band = agent.getLevel() / 10 * 10;
        for (Errand errand : errands.values()) {
            if (fact.get().reqLevel() > errand.wornLevel() && GearPicks.fitsSlot(fact.get(), errand.category(), band,
                    jobMask, agent.getGender(), errand.weaponType(), stats)) {
                return errand;
            }
        }
        return null;
    }
}
