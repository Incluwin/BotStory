package server.living.population;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.movement.AgentMoveTargetStateRuntime;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.shop.AgentFreeMarketStallService;
import server.agents.economy.integration.cosmic.AgentFreeMarketBuyerService;
import server.agents.economy.integration.cosmic.AgentFreeMarketBuyerService.ObservedStall;
import server.integration.AgentPresence;
import server.knowledge.ContentAnswers;
import server.knowledge.PersonalKnowledge;
import server.living.LivingWorldHooks;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Good;
import server.living.population.needs.NeedLedger;
import server.maps.PlayerShop;
import server.maps.reservation.CharacterSpace;
import server.maps.reservation.CharacterSpaceOwner;
import server.maps.reservation.CharacterSpaceReservation;
import server.maps.reservation.CharacterSpaceReservationRuntime;
import server.maps.reservation.FreeMarketCharacterSpaceCatalog;
import server.maps.reservation.FreeMarketStorePlacementService;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntFunction;
import java.util.function.IntToLongFunction;
import java.util.function.Predicate;

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
    /** The index learns from the median of an item's last few sale prices (docs/ECONOMY_PLAN.md E2, rule 5)... */
    static final int RECENT_SALES = 7;
    /** ...kept within this factor of the index, so one odd sale moves it a step, never a leap. */
    static final double SALE_BAND = 3.0d;
    /** A line still unsold when its stall closes pulls the index toward this share of min(ask, index). */
    static final double UNSOLD_STEP_DOWN = 0.9d;
    /** A seed never starts lower; a row still at it is a seed, whatever its sale count says (reseedUnmoved). */
    private static final long MINIMUM_SEED = 10L;
    private static final int INTERACTION_RANGE_PX = 900;
    /** A merchant on its stall spot: a precise move stops within a few pixels of it. */
    static final int STALL_ARRIVAL_PX = 24;
    private static final long STALL_WALK_POLL_MS = 1_000L;
    static final long STALL_WALK_TIMEOUT_MS = 45_000L;
    /** A browsing citizen walks to one of the nearest few stall spots it is not already at, and stands beside it. */
    private static final int BROWSE_CHOICES = 4;
    static final int BROWSE_MIN_STEP_PX = 60;
    static final int BROWSE_BESIDE_MIN_PX = 25;
    static final int BROWSE_BESIDE_MAX_PX = 50;

    public record Listing(int itemId, String name, long price) { }

    /** A sale at a player shop or hired merchant: who sold, what, and the price of one unit. */
    record Sale(int sellerId, int itemId, long unitPrice) { }

    private final Population population;
    private final IntToLongFunction shopPrice;
    private final ScrollSeeds scrollSeeds;
    private final Random random;
    private final Map<Integer, long[]> index = new ConcurrentHashMap<>();
    private final Map<Integer, long[]> buyBudget = new ConcurrentHashMap<>();
    /** Shop sales queued from the buyer's thread (PopulationHooks.shopSale); tick() learns them on the market's own. */
    private final Queue<Sale> sales = new ConcurrentLinkedQueue<>();
    /** An item's last RECENT_SALES unit sale prices, oldest first. */
    private final Map<Integer, ArrayDeque<Long>> recentSales = new HashMap<>();
    private final AgentFreeMarketStallService stalls = new AgentFreeMarketStallService();
    private final AgentFreeMarketBuyerService buyers;
    private volatile int stallsOpen;
    /** {median index price, when it was worked out} for atOrUnderMedian. */
    private volatile long[] medianCache;
    private static final long MEDIAN_REFRESH_MS = 60_000L;
    private volatile long purchases;
    /** The E4 controller's scale on MARKET_BUY_BUDGET_PER_DAY (EconomyController); 1 is the configured budget. */
    private volatile double buyBudgetScale = 1.0d;
    private final GearErrands gearErrands;
    private final CitizenScrolling scrolling;

    Market(Population population) {
        this(population, itemId -> ItemInformationProvider.getInstance().getWholePrice(itemId),
                itemId -> ItemInformationProvider.getInstance().getEquipStats(itemId));
    }

    /** {@code shopPrice}: what a shop pays for one of the item (the WZ price; -1 when it has none). */
    Market(Population population, IntToLongFunction shopPrice) {
        this(population, shopPrice, itemId -> Map.of());
    }

    /** {@code scrollStats}: a scroll's WZ info (success, inc* bonuses) for the seeds of scrolls no table prices. */
    Market(Population population, IntToLongFunction shopPrice, IntFunction<Map<String, Integer>> scrollStats) {
        this.population = population;
        this.shopPrice = shopPrice;
        this.scrollSeeds = new ScrollSeeds(scrollStats, PriceSeeds::all);
        this.gearErrands = new GearErrands(population, this);
        this.scrolling = new CitizenScrolling(population, this::quote);
        this.random = new Random(population.config().SEED ^ 0x3a44e7L);
        this.buyers = new AgentFreeMarketBuyerService(INTERACTION_RANGE_PX, population::isCitizen);
        for (int[] row : population.store().prices()) {
            index.put(row[0], new long[]{row[1], row[2]});
        }
    }

    public String report() {
        return "market: index=" + index.size() + " stalls=" + stallsOpen + " bought=" + purchases + " " + gearErrands.report()
                + " " + scrolling.report();
    }

    /** {@link #report()} as JSON for the admin port's /runtime/stats/market; counts since boot. */
    public String statsJson() {
        return "{\"ok\":true,\"index_items\":" + index.size() + ",\"stalls_open\":" + stallsOpen + ",\"stalls_max\":"
                + population.config().MARKET_STALLS_MAX + ",\"purchases\":" + purchases + ",\"gear_errands\":"
                + gearErrands.json() + ",\"scrolls\":" + scrolling.json() + "}";
    }

    // ---- price index ---------------------------------------------------------------------

    public long price(int itemId) {
        long[] row = index.get(itemId);
        if (row != null) {
            return row[0];
        }
        long seed = seed(itemId);
        index.put(itemId, new long[]{seed, 0});
        population.store().savePrice(itemId, seed, 0);
        return seed;
    }

    /** The index price without seeding a row or writing the DB, so any thread may ask (an Agent's shop visit). */
    long quote(int itemId) {
        long[] row = index.get(itemId);
        return row != null ? row[0] : seed(itemId);
    }

    /**
     * List or vendor (docs/ECONOMY_PLAN.md E3), installed as the Agents' AgentStallHolds rule: of the equips a citizen's
     * shop visit would sell, the ones it keeps for its stall. Worth listing is an index price of at least
     * MARKET_HOLD_OVER_NPC x the shop's; it keeps the best of them by how much more the market pays, up to its hold
     * (a merchant's stall, else MARKET_HOLD_OTHERS), so better loot pushes out worse and the rest is vendored. An item
     * that never sells is listed, nudges its index down at each close, and in time goes back to the shop.
     */
    Set<Item> keepForStall(Character agent, List<Item> candidates) {
        PopulationConfig config = population.config();
        Citizen citizen = population.citizen(agent.getId());
        if (!config.MARKET_ENABLED || !config.MARKET_HOLD_FOR_STALL || citizen == null || citizen.hired()) {
            return Set.of();
        }
        int room = citizen.has(Citizen.FLAG_MERCHANT) ? config.MARKET_HOLD_MERCHANT : config.MARKET_HOLD_OTHERS;
        if (room <= 0) {
            return Set.of();
        }
        return worthListing(candidates, room, config.MARKET_HOLD_OVER_NPC, shopPrice, this::quote);
    }

    /** The best {@code room} of {@code candidates} the market values at {@code overShop} x their shop price or more. */
    static Set<Item> worthListing(List<Item> candidates, int room, double overShop, IntToLongFunction shopPrice,
                                  IntToLongFunction marketPrice) {
        List<Item> worth = new ArrayList<>();
        Map<Item, Long> margin = new HashMap<>();
        for (Item item : candidates) {
            long shop = shopPrice.applyAsLong(item.getItemId());
            long market = marketPrice.applyAsLong(item.getItemId());
            if (shop > 0L && market >= shop * overShop) {
                worth.add(item);
                margin.put(item, market - shop);
            }
        }
        worth.sort(Comparator.comparingLong((Item item) -> margin.get(item)).reversed());
        return Set.copyOf(worth.subList(0, Math.min(room, worth.size())));
    }

    /**
     * A row's starting price: the basket's, else the player price tables' (PriceSeeds), else a scroll's priced against
     * theirs (ScrollSeeds), else the NPC price multiple; never under what an NPC pays for one.
     */
    private long seed(int itemId) {
        return Math.max(npcUnit(itemId), rawSeed(itemId));
    }

    private long rawSeed(int itemId) {
        for (Good good : population.content().basket()) {
            if (good.itemId() == itemId && good.price() > 0L) {
                return good.price();
            }
        }
        long table = PriceSeeds.of(itemId);
        if (table > 0L) {
            return table;
        }
        long scroll = scrollSeeds.seed(itemId);
        if (scroll > 0L) {
            return scroll;
        }
        if (ItemConstants.isEquipment(itemId)) {
            return Math.max(MINIMUM_SEED, Math.round(shopPrice.applyAsLong(itemId) * 2.5d));
        }
        return Math.max(MINIMUM_SEED, Math.round(npcUnit(itemId) * 4.0d));
    }

    /**
     * What an NPC shop pays for {@code quantity} of the item, as Shop's sell works it out
     * (ItemInformationProvider.getPrice); 0 when it has no price. No stall asks under it: buying there and selling to
     * the shop would print mesos.
     */
    public long npcPays(int itemId, int quantity) {
        long whole = shopPrice.applyAsLong(itemId);
        if (whole < 0L) {
            return 0L;
        }
        if (!ItemConstants.isRechargeable(itemId)) {
            return whole * quantity;
        }
        return whole + (long) Math.ceil(quantity * ItemInformationProvider.getInstance().getUnitPrice(itemId));
    }

    /** The index's floor: what an NPC pays for one (a rechargeable's unit price; its base is paid once per bundle). */
    private long npcUnit(int itemId) {
        if (ItemConstants.isRechargeable(itemId)) {
            return (long) Math.ceil(ItemInformationProvider.getInstance().getUnitPrice(itemId));
        }
        return npcPays(itemId, 1);
    }

    /**
     * At start: a row no sale ever moved is only a seed, so it takes today's seed (the player price tables came in with
     * docs/ECONOMY_PLAN.md E2), and so does a row under what an NPC pays or at the old 10-meso minimum, neither ever a
     * market price (the old non-equip seed was 10, and stall closes used to count as sales at it, as did sales off
     * listings priced from it). Writes only the rows that changed; the number of them.
     */
    int reseedUnmoved() {
        int changed = 0;
        for (Map.Entry<Integer, long[]> entry : index.entrySet()) {
            long[] row = entry.getValue();
            if (row[1] != 0 && row[0] > MINIMUM_SEED && row[0] >= npcUnit(entry.getKey())) {
                continue;
            }
            long seed = seed(entry.getKey());
            if (seed != row[0]) {
                row[0] = seed;
                population.store().savePrice(entry.getKey(), seed, (int) row[1]);
                changed++;
            }
        }
        return changed;
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

    /**
     * Any shop sale on the server, human or bot, from the buyer's thread: queued only (no DB, no lock), and learned by
     * the next {@link #tick()}.
     */
    void sold(int sellerId, int itemId, long unitPrice) {
        if (unitPrice > 0L && population.config().MARKET_ENABLED) {
            sales.add(new Sale(sellerId, itemId, unitPrice));
        }
    }

    /**
     * The queued sales move the index (docs/ECONOMY_PLAN.md E2, rule 5), except a companion's bench stall's
     * (BenchStallForm): it prices from the index itself, a little under it, so its sales would only pull the index
     * after its own price, lower each time.
     */
    void learnSales() {
        for (Sale sale = sales.poll(); sale != null; sale = sales.poll()) {
            if (!LivingWorldHooks.benchCompanion(sale.sellerId())) {
                observe(sale.itemId(), saleEstimate(sale.itemId(), sale.unitPrice()));
            }
        }
    }

    /** The median of the item's recent sale prices (this one included), within SALE_BAND of the index. */
    private long saleEstimate(int itemId, long unitPrice) {
        ArrayDeque<Long> recent = recentSales.computeIfAbsent(itemId, id -> new ArrayDeque<>());
        recent.addLast(unitPrice);
        if (recent.size() > RECENT_SALES) {
            recent.removeFirst();
        }
        long[] sorted = recent.stream().mapToLong(Long::longValue).sorted().toArray();
        long current = price(itemId);
        long median = sorted[sorted.length / 2];
        return Math.max(Math.round(current / SALE_BAND), Math.min(Math.round(current * SALE_BAND), median));
    }

    /** Observed sale: nudge the index toward the price (exponential moving average). */
    public void observe(int itemId, long unitPrice) {
        nudge(itemId, unitPrice, true);
    }

    /**
     * A line nobody bought by its stall's close: the index moves down, toward under both the ask and itself (the asks
     * average a little over the index, so a pull toward the ask alone used to push unsold goods up). Not a sale.
     */
    void unsold(int itemId, long unitAsk) {
        nudge(itemId, Math.round(Math.min(unitAsk, price(itemId)) * UNSOLD_STEP_DOWN), false);
    }

    /**
     * {@code sale}: an executed sale, counted as an observation; else an ask nobody took, a weaker signal. Never under
     * what an NPC pays for one: an item nobody buys settles at the shop's price, and goes back to the shop.
     */
    private void nudge(int itemId, long unitPrice, boolean sale) {
        if (unitPrice <= 0) {
            return;
        }
        long current = price(itemId);
        long[] row = index.get(itemId);
        long next = Math.max(Math.max(1L, npcUnit(itemId)), Math.round(current + (unitPrice - current) * INDEX_STEP));
        row[0] = next;
        if (sale) {
            row[1] = row[1] + 1;
        }
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
            int wantedRoom = wantedRoom(session, System.currentTimeMillis());
            int room = wantedRoom > 0 ? wantedRoom : FieldCatalog.FM_ENTRANCE + 1 + pickRoom();
            population.presence().travel(session, Activity.MARKET, room, System.currentTimeMillis());
            return;
        }
        if (session.citizen.has(Citizen.FLAG_MERCHANT) && !session.stallOpen && session.stallSpot == null
                && stallsOpen < population.config().MARKET_STALLS_MAX) {
            server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY, () -> walkToStall(session), 3_000L);
        }
        // otherwise the market tick browses the room's shops
    }

    /**
     * Where a browsing citizen in a Free Market room strolls next: beside one of the few stall spots nearest it, on any
     * of the room's platforms. The arrival ledge holds only two spots, so a stroll kept to its own platform left every
     * citizen of the room standing there above the exit portal (live 10-28). Null outside the rooms.
     */
    static Point browseSpot(int mapId, Point here, Random random) {
        if (here == null || !FreeMarketCharacterSpaceCatalog.isRoom(mapId)) {
            return null;
        }
        List<CharacterSpace> near = FreeMarketCharacterSpaceCatalog.spaces(mapId).stream()
                .filter(space -> space.position().distance(here) > BROWSE_MIN_STEP_PX)
                .sorted(Comparator.comparingDouble(space -> space.position().distanceSq(here)))
                .limit(BROWSE_CHOICES)
                .toList();
        if (near.isEmpty()) {
            return null;
        }
        Point spot = near.get(random.nextInt(near.size())).position();
        int beside = BROWSE_BESIDE_MIN_PX + random.nextInt(BROWSE_BESIDE_MAX_PX - BROWSE_BESIDE_MIN_PX);
        return new Point(spot.x + (random.nextBoolean() ? beside : -beside), spot.y);
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

    /** What a merchant does next on its way to its stall spot. */
    enum StallWalk { OPEN, WAIT, WALK_AGAIN, GIVE_UP }

    /**
     * A merchant takes a free stall spot of the room and walks to it; its stall opens once it stands there. A shop's sign
     * is drawn over its owner, so stalls opened where citizens came in were stacked on the arrival ledge above the exit
     * portal, and only the top one could be clicked (live 10-28).
     */
    private void walkToStall(CitizenSession session) {
        Character agent = session.agent();
        if (!mayOpenStall(session, agent) || session.entry == null || session.stallSpot != null || stock(agent).isEmpty()) {
            return; // nothing to sell: it browses instead
        }
        CharacterSpaceReservation spot = FreeMarketStorePlacementService.reserveNearestForWalking(agent).orElse(null);
        if (spot == null) {
            return; // every stall spot of the room is taken: it browses instead
        }
        session.stallSpot = spot;
        AgentMovementCommandRuntime.moveTo(session.entry, spot.position(), true);
        awaitStallSpot(session, spot, System.currentTimeMillis() + STALL_WALK_TIMEOUT_MS);
    }

    private void awaitStallSpot(CitizenSession session, CharacterSpaceReservation spot, long deadlineMs) {
        server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            if (session.stallSpot != spot) {
                return; // it moved on or logged off, which let the spot go
            }
            Character agent = session.agent();
            StallWalk next = agent == null || agent.getMapId() != spot.scope().mapId() || session.activity != Activity.MARKET
                    ? StallWalk.GIVE_UP
                    : stallWalk(agent.getPosition(), spot.position(),
                            session.entry != null && AgentMoveTargetStateRuntime.hasMoveTarget(session.entry),
                            System.currentTimeMillis(), deadlineMs);
            switch (next) {
                case OPEN -> openStall(session, spot);
                case WAIT -> awaitStallSpot(session, spot, deadlineMs);
                case WALK_AGAIN -> {
                    AgentMovementCommandRuntime.moveTo(session.entry, spot.position(), true);
                    awaitStallSpot(session, spot, deadlineMs);
                }
                case GIVE_UP -> {
                    releaseStallSpot(session);
                    log.info("citizen {} did not reach stall spot #{} in room {}; it browses instead",
                            session.name(), spot.centerSpace().spotNumber(), spot.scope().mapId());
                }
            }
        }, STALL_WALK_POLL_MS);
    }

    /** It opens on the spot, walks on while its move runs, asks again when the move ended short, gives up at the deadline. */
    static StallWalk stallWalk(Point at, Point spot, boolean moving, long nowMs, long deadlineMs) {
        if (at != null && at.distance(spot) <= STALL_ARRIVAL_PX) {
            return StallWalk.OPEN;
        }
        if (nowMs >= deadlineMs) {
            return StallWalk.GIVE_UP;
        }
        return moving ? StallWalk.WAIT : StallWalk.WALK_AGAIN;
    }

    private boolean mayOpenStall(CitizenSession session, Character agent) {
        return agent != null && FieldCatalog.isFreeMarket(agent.getMapId()) && agent.getMapId() != FieldCatalog.FM_ENTRANCE
                && !session.stallOpen && stallsOpen < population.config().MARKET_STALLS_MAX;
    }

    private void openStall(CitizenSession session, CharacterSpaceReservation spot) {
        Character agent = session.agent();
        if (!mayOpenStall(session, agent)) {
            releaseStallSpot(session);
            return;
        }
        try {
            Stock stock = stock(agent);
            if (stock.isEmpty()) {
                releaseStallSpot(session);
                return; // sold or used up on the way: it browses instead
            }
            int permit = population.content().permitItemId();
            if (agent.getInventory(InventoryType.CASH).countById(permit) == 0) {
                InventoryManipulator.addById(agent.getClient(), permit, (short) 1);
            }
            List<Listing> listings = stock.listings();
            String description = listings.get(0).name() + (listings.size() > 1 ? " and more" : "") + ", fair prices";
            AgentFreeMarketStallService.Result result = stalls.open(agent, description, permit, stock.shopListings(), spot);
            if (result.success()) {
                session.stallSpot = null; // the open stall holds the spot now; closeStall lets it go
                session.stallOpen = true;
                session.stallSalesSeen = 0;
                stallsOpen++;
                session.journal.add("market", "opened a shop selling " + listings.get(0).name());
                log.info("citizen {} opened a stall in room {} at spot #{} with {} listings", session.name(), agent.getMapId(),
                        spot.centerSpace().spotNumber(), stock.shopListings().size());
            } else {
                releaseStallSpot(session);
                log.debug("citizen {} stall failed: {}", session.name(), result.message());
            }
        } catch (RuntimeException failure) {
            releaseStallSpot(session);
            log.debug("citizen {} stall opening failed", session.name(), failure);
        }
    }

    /** What a citizen's stall would list: the shop rows, and the names and index prices behind them. */
    /**
     * What a stall lists: the bag's tradeable stacks (no potions), the most valuable first by {@code unitPrice} x
     * quantity, up to STALL_MAX_LISTINGS, so the equips it held back for the stall (E3) lead.
     */
    static List<Item> stallStacks(Character agent, IntToLongFunction unitPrice) {
        return stallStacks(agent, unitPrice, item -> false);
    }

    /** As above, leaving out what {@code keeps} holds back (the scrolls it would use on its own gear). */
    static List<Item> stallStacks(Character agent, IntToLongFunction unitPrice, Predicate<Item> keeps) {
        List<Item> stacks = new ArrayList<>();
        for (InventoryType type : new InventoryType[]{InventoryType.ETC, InventoryType.USE, InventoryType.EQUIP}) {
            for (Item item : agent.getInventory(type).list()) {
                if (item.getQuantity() > 0 && (type != InventoryType.USE || isTradeGood(item.getItemId()))
                        && !keeps.test(item)) {
                    stacks.add(item);
                }
            }
        }
        stacks.sort(Comparator.comparingLong((Item item) -> unitPrice.applyAsLong(item.getItemId()) * item.getQuantity())
                .reversed());
        return stacks.subList(0, Math.min(STALL_MAX_LISTINGS, stacks.size()));
    }

    private record Stock(List<Listing> listings, List<AgentFreeMarketStallService.Listing> shopListings) {
        boolean isEmpty() {
            return shopListings.isEmpty();
        }
    }

    private Stock stock(Character agent) {
        List<Listing> listings = new ArrayList<>();
        List<AgentFreeMarketStallService.Listing> shopListings = new ArrayList<>();
        for (Item item : stallStacks(agent, this::price, item -> scrolling.keeps(agent, item))) {
            InventoryType type = item.getInventoryType();
            long unit = price(item.getItemId());
            short perBundle = (short) (type == InventoryType.EQUIP ? 1 : Math.min(item.getQuantity(), type == InventoryType.ETC ? 100 : 10));
            short bundles = (short) Math.max(1, item.getQuantity() / perBundle);
            long bundlePrice = Math.max(Math.max(1L, npcPays(item.getItemId(), perBundle)),
                    Math.round(unit * perBundle * (0.9d + random.nextDouble() * 0.3d)));
            shopListings.add(new AgentFreeMarketStallService.Listing(type, item.getPosition(), perBundle, bundles,
                    (int) Math.min(Integer.MAX_VALUE, bundlePrice)));
            listings.add(new Listing(item.getItemId(), itemName(item.getItemId()), unit));
        }
        return new Stock(listings, shopListings);
    }

    /** Lets go of the spot it was walking to (or opening on), by its character id: its character may be gone. */
    private static void releaseStallSpot(CitizenSession session) {
        session.stallSpot = null;
        CharacterSpaceReservationRuntime.release(CharacterSpaceOwner.character(session.characterId));
    }

    private void closeStall(CitizenSession session, String reason) {
        if (session.stallSpot != null) {
            releaseStallSpot(session); // still on its way to its stall spot
        }
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
                    if (view.bundles() > 0) { // unsold at close: asked too much (its sales were learned as they happened)
                        unsold(view.itemId(), Math.round(view.bundlePrice() / (double) Math.max(1, view.perBundle())));
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

    /**
     * Every two minutes: browsing citizens buy under-index items from human shops within their daily budget, and loaded
     * citizens out and about use a scroll from their bags on their gear (CitizenScrolling).
     */
    public void tick() {
        if (!population.config().MARKET_ENABLED) {
            return;
        }
        learnSales();
        long now = System.currentTimeMillis();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            if (session.stallOpen && agent != null && agent.getPlayerShop() != null && agent.getPlayerShop().isOwner(agent)) {
                rememberSales(session, agent.getPlayerShop());
            }
            if (!session.virtual && agent != null) {
                try {
                    scrolling.tick(session, agent, now);
                } catch (RuntimeException failure) {
                    log.debug("citizen {} scrolling failed", session.name(), failure);
                }
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
     * Whether browsing citizens buy at this character's stall: a human's, a hired hand's (a player's companion now,
     * though still in the citizen registry), and another citizen's with MARKET_BUY_FROM_CITIZENS (decision D6).
     */
    boolean buysFrom(int ownerCharacterId) {
        if (!population.isCitizen(ownerCharacterId)) {
            return true;
        }
        Citizen owner = population.citizen(ownerCharacterId);
        return owner != null && (owner.hired() || population.config().MARKET_BUY_FROM_CITIZENS);
    }

    private void shop(CitizenSession session, Character agent, long now) {
        List<ObservedStall> stalls = new ArrayList<>(buyers.observeNearby(agent));
        stalls.sort(Comparator.comparing(stall -> population.isCitizen(stall.ownerCharacterId()))); // players' first (D6)
        for (ObservedStall stall : stalls) {
            if (!buysFrom(stall.ownerCharacterId())) {
                continue;
            }
            for (PlayerShop.ListingView view : stall.listings()) {
                if (view.bundles() <= 0 || view.perBundle() <= 0) {
                    continue;
                }
                long unit = Math.round(view.bundlePrice() / (double) view.perBundle());
                long wanted = wanted(session, view, now);
                if (wanted > 0L) { // a needs-board want, a gear errand or a scroll: up to its offer, outside the browse budget
                    if (unit > wanted || view.bundlePrice() > agent.getMeso()) {
                        continue;
                    }
                } else {
                    long fair = price(view.itemId());
                    if (unit > fair * BUY_BELOW_INDEX || view.bundlePrice() > agent.getMeso()) {
                        continue;
                    }
                    if (!spend(session.characterId, view.bundlePrice(), now)) {
                        return;
                    }
                }
                AgentFreeMarketBuyerService.PurchaseResult result = buyers.buy(agent, stall.objectId(), view.slot(), (short) 1);
                if (result.success()) {
                    purchases++; // the sale itself reaches the index through sold()
                    if (wanted > 0L) {
                        if (population.needs() != null) {
                            population.needs().boughtAtMarket(session, agent, view.itemId(), stall.ownerCharacterId(), now);
                        }
                        gearErrands.bought(agent, view.itemId());
                        scrolling.bought(agent, view.itemId());
                    }
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
     * The most it pays a unit for this listing: the needs board's offer (docs/BOT_CONCEPT_PLAN.md F1) for a single piece
     * of what its open GEAR or SCROLL want asks for, else a gear errand's (GearErrands), else a scroll for its own gear
     * (CitizenScrolling); 0 for none.
     */
    private long wanted(CitizenSession session, PlayerShop.ListingView view, long now) {
        if (view.perBundle() != 1) {
            return 0L;
        }
        NeedLedger needs = population.needs();
        long asked = needs == null ? 0L : needs.marketOffer(session.characterId, view.itemId(), view.attributes(), now);
        if (asked > 0L) {
            return asked;
        }
        long gear = gearErrands.offer(session.agent(), view.itemId());
        return gear > 0L ? gear : scrolling.offer(session.agent(), view.itemId()); // equips and scrolls never overlap
    }

    /**
     * The needs board: the Free Market room whose player shop sells what the citizen wants at its offer or less, the
     * room it goes to from the entrance (the Owl of Minerva a player would use); 0 for none or a room nobody loaded.
     */
    /** Whether the citizen goes to the market for something: a needs-board want or an open gear errand (E3). */
    boolean wantsFromMarket(int citizenId) {
        NeedLedger needs = population.needs();
        return needs != null && needs.wantsFromMarket(citizenId) || gearErrands.any(citizenId);
    }

    GearErrands gearErrands() {
        return gearErrands;
    }

    private int wantedRoom(CitizenSession session, long now) {
        net.server.world.World world = population.gameWorld();
        net.server.channel.Channel channel = world == null ? null : world.getChannel(session.channel);
        if (channel == null || !wantsFromMarket(session.characterId)) {
            return 0;
        }
        for (int room = FieldCatalog.FM_ENTRANCE + 1; room <= FieldCatalog.FM_ENTRANCE + FM_ROOMS; room++) {
            if (!channel.getMapFactory().isMapLoaded(room)) {
                continue;
            }
            for (PlayerShop shop : channel.getMapFactory().getMap(room).getAllPlayerShops()) {
                if (!buysFrom(shop.getOwnerId())) {
                    continue;
                }
                for (PlayerShop.ListingView view : shop.listingSnapshot()) {
                    long wanted = view.bundles() > 0 ? wanted(session, view, now) : 0L;
                    if (wanted > 0L && view.bundlePrice() <= wanted) {
                        return room;
                    }
                }
            }
        }
        return 0;
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

    void setBuyBudgetScale(double scale) {
        buyBudgetScale = scale;
    }

    private boolean spend(int characterId, long meso, long now) {
        int day = (int) (now / 86_400_000L);
        long[] budget = buyBudget.computeIfAbsent(characterId, id -> new long[]{day, 0L});
        synchronized (budget) {
            if (budget[0] != day) {
                budget[0] = day;
                budget[1] = 0L;
            }
            if (budget[1] + meso > Math.round(population.config().MARKET_BUY_BUDGET_PER_DAY * buyBudgetScale)) {
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
