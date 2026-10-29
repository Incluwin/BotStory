package server.living.population;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.progression.AgentCareerShopCatalog;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Citizens go to sell when their equip bag fills (docs/ECONOMY_PLAN.md E3). A citizen's only shop visits were supply
 * trips, which start when its potions run low, so one carrying a big potion stock never went: on 10-29, 270 of 730
 * citizens had every equip slot full (1,112 potions each on average, against 341 for the rest), 85 % of it loot the
 * visit sells, and 63 % of the citizens on gear errands could not buy. Now a loaded citizen with SELL_TRIP_FREE_SLOTS
 * free equip slots or fewer goes, at its next plan, to the nearest potion shop it can walk to (the supply trips' pick),
 * walks up to the shopkeeper and asks the sell-trash visit there: the same sale of trash equips and spare drops a
 * supply visit makes. At most once each SELL_TRIP_EVERY_HOURS, so a bag of pieces it keeps does not send it every plan.
 */
final class CitizenSellTrips {
    private static final Logger log = LoggerFactory.getLogger(CitizenSellTrips.class);
    private static final long HOUR_MS = 3_600_000L;
    /** A trip whose sale never began (it ended up elsewhere) is over after this long. */
    static final long TRIP_TIMEOUT_MS = 15 * 60_000L;
    /** A sale that never ends (the visit failed quietly) is over after this long. */
    static final long VISIT_TIMEOUT_MS = 5 * 60_000L;
    /** How long a sale asked of the Agent may take to start (it is asked on the Agent's own mailbox). */
    static final long VISIT_START_MS = 5_000L;
    /** Within this of the shopkeeper it asks for the sale; beyond it the visit's own approach ran out of time (10-29). */
    static final int REACH_PX = 60;
    static final long CHECK_MS = 1_000L;
    /** It asks wherever it stands after walking this long. */
    static final long WALK_MAX_MS = 60_000L;

    /** The shop a citizen goes to sell at: its map and shopkeeper, and when the trip was planned. */
    record Trip(int mapId, int npcId, long plannedAtMs) {
    }

    private final Population population;
    private final Map<Integer, Long> lastTripAt = new ConcurrentHashMap<>();
    /** When each citizen asked its shopkeeper for the sale, by id. */
    private final Map<Integer, Long> asked = new ConcurrentHashMap<>();
    /** Free equip slots, as many as it likes with no bag (a test's own). */
    ToIntFunction<Character> freeEquipSlots = agent -> {
        Inventory bag = agent.getInventory(InventoryType.EQUIP);
        return bag == null ? Integer.MAX_VALUE : bag.getNumFreeSlot();
    };
    /** The nearest potion shop it can walk to from where it is, {map, npc}; null for none (a test's own). */
    Function<CitizenSession, int[]> shopFor = CitizenSellTrips::nearestShop;
    /** Asks the Agent's shopkeeper for the sale, on the Agent's mailbox (a test's own). */
    SaleRequest sale = (entry, agent, npcId) -> AgentSchedulerRuntime.afterRandomDelay(entry, 500, 1_500,
            () -> AgentShopService.requestSaleAt(entry, agent, npcId));
    /** Whether the Agent's shop visit is still under way (a test's own). */
    Predicate<AgentRuntimeEntry> visiting = AgentShopStateRuntime::shopVisitPending;
    /** Walks the Agent to a point (a test's own). */
    BiConsumer<AgentRuntimeEntry, Point> walk = (entry, point) -> AgentMovementCommandRuntime.moveTo(entry, point, false);
    LongSupplier clock = System::currentTimeMillis;
    /** Runs a task after a delay (ms); a test runs them by hand. */
    BiConsumer<Runnable, Long> later = (task, delayMs) ->
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, task, delayMs);

    interface SaleRequest {
        void ask(AgentRuntimeEntry entry, Character agent, int npcId);
    }

    CitizenSellTrips(Population population) {
        this.population = population;
    }

    /** At its plan: the shop a loaded citizen whose equip bag is nearly full goes to sell at; null for none. */
    Trip planned(CitizenSession session, long now) {
        PopulationConfig config = population.config();
        Character agent = session.virtual ? null : session.agent();
        Citizen citizen = population.citizen(session.characterId);
        if (!config.SELL_TRIPS_ENABLED || agent == null || session.entry == null || citizen == null || citizen.hired()) {
            return null;
        }
        int free = freeEquipSlots.applyAsInt(agent);
        Long last = lastTripAt.get(session.characterId);
        if (free > config.SELL_TRIP_FREE_SLOTS || last != null && now - last < config.SELL_TRIP_EVERY_HOURS * HOUR_MS) {
            return null;
        }
        int[] shop = shopFor.apply(session);
        if (shop == null) {
            return null;
        }
        lastTripAt.put(session.characterId, now);
        asked.remove(session.characterId);
        log.info("citizen {} goes to sell its loot at map {} ({} free equip slots)", session.name(), shop[0], free);
        return new Trip(shop[0], shop[1], now);
    }

    /**
     * It arrived on {@code map} for its trip: it walks up to the shopkeeper, as a supply trip does before its visit, and
     * asks for the sale there. Asked from the arrival portal, the visit's own approach ran out of time at an unwatched
     * Agent's tick rate (21 of 48 trips on 10-29). False when the shopkeeper is not here.
     */
    boolean begin(CitizenSession session, MapleMap map) {
        Trip trip = session.sellTrip;
        Character agent = session.agent();
        NPC npc = trip == null || map == null || map.getId() != trip.mapId() ? null : map.getNPCById(trip.npcId());
        if (npc == null || agent == null || session.entry == null) {
            return false;
        }
        Point counter = npc.getPosition();
        walk.accept(session.entry, counter);
        long startedAt = clock.getAsLong();
        later.accept(() -> step(session, trip, counter, startedAt), CHECK_MS);
        return true;
    }

    /** It looks again: at the counter (or walked long enough), it asks for the sale. */
    void step(CitizenSession session, Trip trip, Point counter, long startedAt) {
        Character agent = session.agent();
        if (session.sellTrip != trip || !session.loaded() || session.activity != CitizenSession.Activity.TOWN
                || agent == null || session.entry == null) {
            return; // it moved on or logged off
        }
        Point at = agent.getPosition();
        long now = clock.getAsLong();
        if ((at == null || at.distance(counter) > REACH_PX) && now - startedAt < WALK_MAX_MS) {
            later.accept(() -> step(session, trip, counter, startedAt), CHECK_MS);
            return;
        }
        asked.put(session.characterId, now);
        sale.ask(session.entry, agent, trip.npcId());
    }

    /** Whether its trip is over: the sale it asked for ended, never began, or ran too long. */
    boolean finished(CitizenSession session, long now) {
        Trip trip = session.sellTrip;
        if (trip == null) {
            return true;
        }
        Long at = asked.get(session.characterId);
        if (at == null) {
            return now - trip.plannedAtMs() >= TRIP_TIMEOUT_MS;
        }
        if (now - at < VISIT_START_MS || now - at < VISIT_TIMEOUT_MS && session.entry != null && visiting.test(session.entry)) {
            return false;
        }
        asked.remove(session.characterId);
        return true;
    }

    /** The potion shop supply trips would pick from where it is: the nearest one it can walk to. */
    private static int[] nearestShop(CitizenSession session) {
        AgentRuntimeEntry entry = session.entry;
        Character agent = session.agent();
        if (entry == null || agent == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        AgentCareerProgressionState career = entry.capabilityStates().find(AgentCareerProgressionState.STATE_KEY).orElse(null);
        AgentCareerShopCatalog.ShopStop stop = AgentCareerShopCatalog.forSupply(career == null ? null : career.bundle(),
                AgentResourceCategory.HP_POTION, agent.getMapId(),
                (source, destination) -> AgentVictoriaRouteRuntime.routeHops(entry, source, destination, now));
        return stop == null ? null : new int[]{stop.mapId(), stop.npcId()};
    }
}
