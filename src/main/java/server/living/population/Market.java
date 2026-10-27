package server.living.population;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.shop.AgentFreeMarketStallService;
import server.agents.economy.integration.cosmic.AgentFreeMarketBuyerService;
import server.agents.economy.integration.cosmic.AgentFreeMarketBuyerService.ObservedStall;
import server.integration.AgentPresence;
import server.knowledge.ContentAnswers;
import server.knowledge.PersonalKnowledge;
import server.living.LivingWorldHooks;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Good;
import server.maps.PlayerShop;
import server.maps.reservation.CharacterSpaceReservation;
import server.maps.reservation.FreeMarketStorePlacementService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An economy players can join (plan 7): a price index seeded from the market basket and drifted
 * by what sells, citizen stalls in Free Market rooms selling what they looted, citizens buying
 * from human shops when the price is under the index, and the numbers behind "S> {item} {price}".
 */
public final class Market {
    private static final Logger log = LoggerFactory.getLogger(Market.class);
    private static final int FM_ROOMS = 22;
    private static final double ROOM_SPREAD = 2.5;
    private static final int STALL_MAX_LISTINGS = 8;
    private static final double BUY_BELOW_INDEX = 0.9d;
    private static final double INDEX_STEP = 0.08d;
    private static final int INTERACTION_RANGE_PX = 900;

    public record Listing(int itemId, String name, long price) { }

    private final Population population;
    private final Random random;
    private final Map<Integer, long[]> index = new ConcurrentHashMap<>();
    private final Map<Integer, long[]> buyBudget = new ConcurrentHashMap<>();
    private final AgentFreeMarketStallService stalls = new AgentFreeMarketStallService();
    private final AgentFreeMarketBuyerService buyers;
    private volatile int stallsOpen;
    /** {median index price, when it was worked out} for atOrUnderMedian. */
    private volatile long[] medianCache;
    private static final long MEDIAN_REFRESH_MS = 60_000L;
    private volatile long purchasesFromHumans;

    Market(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0x3a44e7L);
        this.buyers = new AgentFreeMarketBuyerService(INTERACTION_RANGE_PX, population::isCitizen);
        for (int[] row : population.store().prices()) {
            index.put(row[0], new long[]{row[1], row[2]});
        }
    }

    public String report() {
        return "market: index=" + index.size() + " stalls=" + stallsOpen + " boughtFromHumans=" + purchasesFromHumans;
    }

    // ---- price index ---------------------------------------------------------------------

    public long price(int itemId) {
        long[] row = index.get(itemId);
        if (row != null) {
            return row[0];
        }
        long seed = 0L;
        for (Good good : population.content().basket()) {
            if (good.itemId() == itemId) {
                seed = good.price();
            }
        }
        if (seed <= 0L) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            double base = ItemConstants.isEquipment(itemId) ? ii.getWholePrice(itemId) : ii.getUnitPrice(itemId);
            seed = Math.max(10L, Math.round(base * (ItemConstants.isEquipment(itemId) ? 2.5d : 4.0d)));
        }
        index.put(itemId, new long[]{seed, 0});
        population.store().savePrice(itemId, seed, 0);
        return seed;
    }

    /**
     * True when the index has a row for the item at or under the median of its prices: what a citizen or companion may
     * leave on the ground (F8 loot slip). Reads the index only (an item it has no row for is not cheap, and no row is
     * seeded, so it is safe on any thread); the median is worked out again at most once a minute.
     */
    public boolean atOrUnderMedian(int itemId) {
        long[] row = index.get(itemId);
        return row != null && row[0] <= median(System.currentTimeMillis());
    }

    private long median(long nowMs) {
        long[] cached = medianCache;
        if (cached != null && nowMs - cached[1] < MEDIAN_REFRESH_MS) {
            return cached[0];
        }
        long[] prices = index.values().stream().mapToLong(known -> known[0]).sorted().toArray();
        long median = prices.length == 0 ? 0L : prices[prices.length / 2];
        medianCache = new long[]{median, nowMs};
        return median;
    }

    /**
     * True when the index prices the item in its top tenth (a companion's letter calls such a find news). Reads the
     * index only: an item it has no row for is not, and no row is seeded.
     */
    public boolean topDecile(int itemId) {
        long[] row = index.get(itemId);
        if (row == null || index.size() < 10) {
            return false;
        }
        long[] prices = index.values().stream().mapToLong(known -> known[0]).sorted().toArray();
        return row[0] >= prices[prices.length * 9 / 10];
    }

    /**
     * What the index knows without guessing, for content PRICE answers: a quote only for an item a sale has
     * nudged (observations &gt; 0) or a basket good (its basket price until a sale moves it: a seed row nobody
     * bought at is not an observation); null otherwise. Unlike {@link #price} it never seeds a row and never
     * writes the DB, so it is safe on the chat thread.
     */
    public ContentAnswers.PriceQuote known(int itemId) {
        long[] row = index.get(itemId);
        long basket = 0L;
        for (Good good : population.content().basket()) {
            if (good.itemId() == itemId) {
                basket = good.price();
            }
        }
        if (row != null && row[1] > 0) {
            return new ContentAnswers.PriceQuote(row[0], (int) row[1], basket > 0L);
        }
        return basket > 0L ? new ContentAnswers.PriceQuote(basket, 0, true) : null;
    }

    /** Observed sale: nudge the index toward the price (exponential moving average). */
    public void observe(int itemId, long unitPrice) {
        if (unitPrice <= 0) {
            return;
        }
        long current = price(itemId);
        long[] row = index.get(itemId);
        long next = Math.max(1L, Math.round(current + (unitPrice - current) * INDEX_STEP));
        row[0] = next;
        row[1] = row[1] + 1;
        population.store().savePrice(itemId, next, (int) row[1]);
    }

    public static String formatPrice(long price) {
        if (price >= 1_000_000L) {
            double m = price / 1_000_000.0d;
            return (m == Math.floor(m) ? String.valueOf((long) m) : String.format(java.util.Locale.ROOT, "%.1f", m)) + "m";
        }
        if (price >= 1_000L) {
            return (price / 1000L) + "k";
        }
        return String.valueOf(price);
    }

    /** Something to talk about: a basket item at the index price, biased to the citizen's level. */
    public Listing talkAbout(CitizenSession session, Random rng) {
        List<Good> basket = population.content().basket();
        if (basket.isEmpty()) {
            return null;
        }
        Good good = basket.get(rng.nextInt(basket.size()));
        return new Listing(good.itemId(), itemName(good.itemId()), price(good.itemId()));
    }

    // ---- stalls --------------------------------------------------------------------------

    /**
     * A MARKET-activity citizen arrived somewhere in the Free Market: from the entrance it walks
     * into a room (which calls back here); in a room a merchant opens a stall, anyone else browses.
     */
    void onArrive(CitizenSession session) {
        if (!population.config().MARKET_ENABLED) {
            return;
        }
        Character agent = session.agent();
        if (agent == null) {
            return;
        }
        if (agent.getMapId() == FieldCatalog.FM_ENTRANCE) {
            int room = FieldCatalog.FM_ENTRANCE + 1 + pickRoom();
            population.presence().travel(session, Activity.MARKET, room, System.currentTimeMillis());
            return;
        }
        if (session.citizen.has(Citizen.FLAG_MERCHANT) && !session.stallOpen && stallsOpen < population.config().MARKET_STALLS_MAX) {
            server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY, () -> openStall(session), 3_000L);
        }
        // otherwise the market tick browses the room's shops
    }

    /**
     * Rooms fill like a real server: FM 1 is the busiest and the tail rooms are near-empty.
     * Exponential with a mean of {@link #ROOM_SPREAD} rooms, clamped to the room count.
     */
    int pickRoom() {
        double draw = -Math.log(1.0 - random.nextDouble()) * ROOM_SPREAD;
        return (int) Math.min(FM_ROOMS - 1, draw);
    }

    void onLeave(CitizenSession session) {
        closeStall(session, "moving on");
    }

    void onLogout(CitizenSession session) {
        closeStall(session, "logging off");
    }

    void closeAll(String reason) {
        for (CitizenSession session : population.presence().sessions()) {
            closeStall(session, reason);
        }
    }

    private void openStall(CitizenSession session) {
        Character agent = session.agent();
        if (agent == null || !FieldCatalog.isFreeMarket(agent.getMapId()) || agent.getMapId() == FieldCatalog.FM_ENTRANCE
                || session.stallOpen || stallsOpen >= population.config().MARKET_STALLS_MAX) {
            return;
        }
        try {
            List<Listing> listings = new ArrayList<>();
            List<AgentFreeMarketStallService.Listing> shopListings = new ArrayList<>();
            for (InventoryType type : new InventoryType[]{InventoryType.ETC, InventoryType.USE, InventoryType.EQUIP}) {
                Inventory inventory = agent.getInventory(type);
                for (Item item : inventory.list()) {
                    if (shopListings.size() >= STALL_MAX_LISTINGS || item.getQuantity() <= 0) {
                        break;
                    }
                    if (type == InventoryType.USE && !isTradeGood(item.getItemId())) {
                        continue; // keep potions
                    }
                    long unit = price(item.getItemId());
                    short perBundle = (short) (type == InventoryType.EQUIP ? 1 : Math.min(item.getQuantity(), type == InventoryType.ETC ? 100 : 10));
                    short bundles = (short) Math.max(1, item.getQuantity() / perBundle);
                    long bundlePrice = Math.max(1L, Math.round(unit * perBundle * (0.9d + random.nextDouble() * 0.3d)));
                    shopListings.add(new AgentFreeMarketStallService.Listing(type, item.getPosition(), perBundle, bundles,
                            (int) Math.min(Integer.MAX_VALUE, bundlePrice)));
                    listings.add(new Listing(item.getItemId(), itemName(item.getItemId()), unit));
                }
            }
            if (shopListings.isEmpty()) {
                return; // nothing to sell; browse instead
            }
            int permit = population.content().permitItemId();
            if (agent.getInventory(InventoryType.CASH).countById(permit) == 0) {
                InventoryManipulator.addById(agent.getClient(), permit, (short) 1);
            }
            CharacterSpaceReservation reservation = FreeMarketStorePlacementService.reserveNearest(agent).orElse(null);
            if (reservation == null) {
                return;
            }
            String description = listings.get(0).name() + (listings.size() > 1 ? " and more" : "") + ", fair prices";
            AgentFreeMarketStallService.Result result = stalls.open(agent, description, permit, shopListings, reservation);
            if (result.success()) {
                session.stallOpen = true;
                session.stallSalesSeen = 0;
                stallsOpen++;
                session.journal.add("market", "opened a shop selling " + listings.get(0).name());
                log.info("citizen {} opened a stall in room {} with {} listings", session.name(), agent.getMapId(), shopListings.size());
            } else {
                FreeMarketStorePlacementService.release(agent);
                log.debug("citizen {} stall failed: {}", session.name(), result.message());
            }
        } catch (RuntimeException failure) {
            log.debug("citizen {} stall opening failed", session.name(), failure);
        }
    }

    private void closeStall(CitizenSession session, String reason) {
        if (!session.stallOpen) {
            return;
        }
        Character agent = session.agent();
        session.stallOpen = false;
        stallsOpen = Math.max(0, stallsOpen - 1);
        if (agent == null) {
            return;
        }
        try {
            PlayerShop shop = agent.getPlayerShop();
            if (shop != null && shop.isOwner(agent)) {
                rememberSales(session, shop);
                for (PlayerShop.ListingView view : shop.listingSnapshot()) {
                    if (view.bundles() <= 0) {
                        observe(view.itemId(), Math.round(view.bundlePrice() / (double) Math.max(1, view.perBundle()) * 1.05d));
                    } else {
                        observe(view.itemId(), Math.round(view.bundlePrice() / (double) Math.max(1, view.perBundle()) * 0.97d));
                    }
                }
                agent.closePlayerShop(reason);
            }
            FreeMarketStorePlacementService.release(agent);
        } catch (RuntimeException failure) {
            log.debug("citizen {} stall close failed", session.name(), failure);
        }
    }

    private static boolean isTradeGood(int itemId) {
        int type = itemId / 10000;
        return type >= 204 && type <= 204 + 9 || type == 207 || type == 202; // scrolls, stars, food
    }

    // ---- buying --------------------------------------------------------------------------

    /** Every two minutes: browsing citizens buy under-index items from human shops within their daily budget. */
    public void tick() {
        if (!population.config().MARKET_ENABLED) {
            return;
        }
        long now = System.currentTimeMillis();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.stallOpen && agent != null && agent.getPlayerShop() != null && agent.getPlayerShop().isOwner(agent)) {
                rememberSales(session, agent.getPlayerShop());
            }
            if (session.virtual || agent == null || session.activity != Activity.MARKET || session.stallOpen
                    || !FieldCatalog.isFreeMarket(agent.getMapId())) {
                continue;
            }
            try {
                shop(session, agent, now);
            } catch (RuntimeException failure) {
                log.debug("citizen {} browsing failed", session.name(), failure);
            }
        }
    }

    /**
     * Whether browsing citizens buy at this character's stall: a human's, or a hired hand's (a player's companion now,
     * though still in the citizen registry); never another citizen's.
     */
    boolean buysFrom(int ownerCharacterId) {
        if (!population.isCitizen(ownerCharacterId)) {
            return true;
        }
        Citizen owner = population.citizen(ownerCharacterId);
        return owner != null && owner.hired();
    }

    /**
     * A citizen bought {@code itemId} at {@code unit} a unit at this character's stall: the index moves toward the price,
     * unless it was a companion's bench stall (BenchStallForm), which prices from the index itself, so its sales would
     * only pull the index after its own price, lower each time.
     */
    void bought(int ownerCharacterId, int itemId, long unit) {
        purchasesFromHumans++;
        if (!LivingWorldHooks.benchCompanion(ownerCharacterId)) {
            observe(itemId, unit);
        }
    }

    private void shop(CitizenSession session, Character agent, long now) {
        for (ObservedStall stall : buyers.observeNearby(agent)) {
            if (!buysFrom(stall.ownerCharacterId())) {
                continue; // humans' shops only (and a hired hand's)
            }
            for (PlayerShop.ListingView view : stall.listings()) {
                if (view.bundles() <= 0 || view.perBundle() <= 0) {
                    continue;
                }
                long unit = Math.round(view.bundlePrice() / (double) view.perBundle());
                long fair = price(view.itemId());
                if (unit > fair * BUY_BELOW_INDEX || view.bundlePrice() > agent.getMeso()) {
                    continue;
                }
                if (!spend(session.characterId, view.bundlePrice(), now)) {
                    return;
                }
                AgentFreeMarketBuyerService.PurchaseResult result = buyers.buy(agent, stall.objectId(), view.slot(), (short) 1);
                if (result.success()) {
                    bought(stall.ownerCharacterId(), view.itemId(), unit);
                    if (!population.isCitizen(stall.ownerCharacterId())) { // a player's shop, not a companion's bench stall
                        remember(session, stall.ownerCharacterId(), PersonMemory.Kind.BOUGHT, agent.getMapId(), view.itemId());
                    }
                    PersonalKnowledge.shared().gained(session.characterId, view.itemId());
                    session.journal.add("market", "bought " + itemName(view.itemId()) + " from " + stall.ownerName() + " for " + formatPrice(view.bundlePrice()));
                    log.info("citizen {} bought {} x{} from {} for {}", session.name(), itemName(view.itemId()), view.perBundle(), stall.ownerName(), view.bundlePrice());
                    return; // one purchase per tick per citizen
                }
            }
        }
    }

    /**
     * The players who bought at the citizen's open stall since it last looked: it remembers each one (a SOLD episode).
     * The stall's sold list names the buyer; one no longer online is looked up by name.
     */
    void rememberSales(CitizenSession session, PlayerShop shop) {
        List<PlayerShop.SoldItem> sold = shop.getSold();
        int seen = session.stallSalesSeen;
        session.stallSalesSeen = sold.size();
        Character agent = session.agent();
        net.server.world.World world = population.gameWorld();
        for (int i = Math.max(0, seen); i < sold.size(); i++) {
            PlayerShop.SoldItem sale = sold.get(i);
            Character online = world == null ? null : world.getPlayerStorage().getCharacterByName(sale.getBuyer());
            int buyerId = online != null ? online.getId() : Character.getIdByName(sale.getBuyer());
            if (buyerId > 0 && !population.isCitizen(buyerId) && (online == null || !AgentPresence.isAgent(online))) {
                remember(session, buyerId, PersonMemory.Kind.SOLD, agent == null ? 0 : agent.getMapId(), sale.getItemId());
            }
        }
    }

    private void remember(CitizenSession session, int playerId, PersonMemory.Kind kind, int mapId, int itemId) {
        PersonMemories memories = population.memories();
        if (memories != null) {
            memories.record(session.characterId, playerId, kind, mapId, itemName(itemId), 1);
        }
    }

    private boolean spend(int characterId, long meso, long now) {
        int day = (int) (now / 86_400_000L);
        long[] budget = buyBudget.computeIfAbsent(characterId, id -> new long[]{day, 0L});
        synchronized (budget) {
            if (budget[0] != day) {
                budget[0] = day;
                budget[1] = 0L;
            }
            if (budget[1] + meso > population.config().MARKET_BUY_BUDGET_PER_DAY) {
                return false;
            }
            budget[1] += meso;
            return true;
        }
    }

    /** Meso a citizen of this level should carry (so it can buy); granted on catch-up when short. */
    public static long expectedMeso(int level) {
        return Math.round(2_000d * Math.pow(Math.max(1, level), 1.5d));
    }

    static String itemName(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? "item " + itemId : name.toLowerCase(java.util.Locale.ROOT);
    }
}
