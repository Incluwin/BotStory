package server.living.roster;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.runtime.AgentCapabilityInvocation;
import server.agents.capabilities.runtime.AgentCapabilityRuntime;
import server.agents.capabilities.shop.AgentFreeMarketStallCapability;
import server.agents.capabilities.shop.AgentFreeMarketStallService;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.population.Population;
import server.living.quest.QuestLedger;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopEscrowSnapshot;
import server.maps.PlayerShopEscrowStore;
import server.maps.reservation.CharacterSpace;
import server.maps.reservation.FreeMarketCharacterSpaceCatalog;
import server.maps.reservation.FreeMarketStorePlacementService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The STALL and SELL form of the bench (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): a real Free
 * Market shop under the COMMERCE pool. While its haul holds goods it may sell (tradeable kill loot and FARM_ITEM goods
 * its owner's quests do not need; never anything of its owner's), the companion loads in a Free Market room with a
 * free stall spot, registered director-idle; the goods move from its haul into its bag, and
 * AgentFreeMarketStallCapability walks it to a free stall spot and opens the shop (AgentFreeMarketStallService), titled
 * in its voice and priced from the population market's price index like the stalls around it (SELL a little under the
 * index, where browsing citizens buy; a STALL at the index, marked down by what its own last slices did not sell, to
 * SELL's price at most: {@link #asks}; the market keeps bench stalls' sales out of its index). Each tick the meso of its
 * sales goes into the haul. When the slice ends (its time, a call, a new duty, sold out) the shop closes, the last
 * sales go into the haul and the unsold stock goes back to it. Nothing is duplicated or lost: the haul gives up what
 * reached the bag, gets back what was not sold (never more than the bag holds) and the meso the sales paid. What a stall
 * holds of the haul is kept in living_stall_stock, and each move saves the companion before the haul is paid, so a
 * crash may lose a move in flight but never makes one twice; the companion's next load, whatever its duty, takes an
 * interrupted stall's escrow and stock back to the haul first ({@link #reclaim}).
 */
public final class BenchStallForm implements BenchDutyDispatcher.Form {
    private static final Logger log = LoggerFactory.getLogger(BenchStallForm.class);
    private static final Set<InventoryType> SOLD = EnumSet.of(InventoryType.EQUIP, InventoryType.USE,
            InventoryType.SETUP, InventoryType.ETC);
    /** The first Free Market rooms, where players and browsing citizens are: each companion keeps to one of them. */
    private static final int ROOMS = 6;
    /** Listings per stall, like a citizen's. */
    static final int MAX_LISTINGS = 8;
    /** SELL's price against the index: under the 0.9 below which browsing citizens buy (population Market). */
    static final double QUICK_SALE = 0.85d;
    /** The end of a stall slice that ran its whole length (BenchDutyDispatcher.timeUp): only those teach it a price. */
    static final String FULL_SLICE = "slice elapsed";
    /** The events a stall's own record is read from (each slice's stocking and its close), and how many of them. */
    static final List<String> STALL_HISTORY_KINDS = List.of("bench.stall", "bench.stall-close");
    static final int STALL_HISTORY_EVENTS = 60;
    private static final long OPEN_TIMEOUT_MS = 90_000L;
    private static final int OPEN_RETRIES = 2;
    private static final String CLOSE_REASON = "BENCH_SLICE_ENDED";
    /** A stall spot with a shop this close to it is taken (FreeMarketStorePlacementService's own clearance). */
    private static final int STALL_CLEARANCE_PX = 80;

    /** One live stall: what reached its bag from the haul, how it is bundled, and what has sold. */
    private static final class Stall {
        /** item id -> units moved from the haul into its bag. */
        final Map<Integer, Integer> stocked = new LinkedHashMap<>();
        /** item id -> units per listed bundle. */
        final Map<Integer, Integer> perBundle = new HashMap<>();
        /** item id -> units sold. */
        final Map<Integer, Integer> sold = new HashMap<>();
        /** Its shop once opened (companion-ops writes it; the card may read it from elsewhere). */
        volatile PlayerShop shop;
        int salesSeen;

        /** What it holds of the haul now: stocked less sold. */
        Map<Integer, Integer> held() {
            Map<Integer, Integer> out = new LinkedHashMap<>();
            stocked.forEach((itemId, units) -> {
                int left = units - sold.getOrDefault(itemId, 0);
                if (left > 0) {
                    out.put(itemId, left);
                }
            });
            return out;
        }
    }

    /** The game facts a stall needs: the game's (ItemInformationProvider, from WZ; the Free Market's stall spots), or a unit test's own. */
    interface Goods {
        /** True for what no player could put in a shop: unmerchable, quest-only (drop-restricted) or untradeable. */
        boolean restricted(int itemId);

        /** Units of the item one bag slot holds. */
        int slotMax(Character agent, int itemId);

        /** The item's name as the game shows it, or null. */
        String name(int itemId);

        /** True while the Free Market room has a stall spot no shop stands on. */
        default boolean hasFreeSpot(MapleMap room) {
            return true;
        }
    }

    private static final Goods WZ = new Goods() {
        @Override
        public boolean restricted(int itemId) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return ii.isUnmerchable(itemId) || ii.isDropRestricted(itemId); // trade-blocked, account-bound or quest items
        }

        @Override
        public int slotMax(Character agent, int itemId) {
            return ItemInformationProvider.getInstance().getSlotMax(agent.getClient(), itemId);
        }

        @Override
        public String name(int itemId) {
            return ItemInformationProvider.getInstance().getName(itemId);
        }

        @Override
        public boolean hasFreeSpot(MapleMap room) {
            long clearance = (long) STALL_CLEARANCE_PX * STALL_CLEARANCE_PX;
            for (CharacterSpace space : FreeMarketCharacterSpaceCatalog.spaces(room.getId())) {
                if (room.getMapObjectsInRange(space.position(), clearance,
                        List.of(MapObjectType.SHOP, MapObjectType.HIRED_MERCHANT)).isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    };

    private final LivingWorld world;
    private final Goods goods;
    /** persona id -> its live stall. */
    private final Map<String, Stall> stalls = new ConcurrentHashMap<>();

    public BenchStallForm(LivingWorld world) {
        this(world, WZ);
    }

    BenchStallForm(LivingWorld world, Goods goods) {
        this.world = world;
        this.goods = goods;
    }

    @Override
    public CompanionBudget.Kind pool() {
        return CompanionBudget.Kind.COMMERCE;
    }

    /**
     * True once the persona's live stall has opened its shop (the card's "stall open in FM room 3"); false while it still
     * walks to its spot and sets up, and when it has no live stall.
     */
    public boolean open(String personaId) {
        Stall stall = personaId == null ? null : stalls.get(personaId);
        return stall != null && stall.shop != null;
    }

    /**
     * Its Free Market room while its haul has something to sell (or an interrupted stall of its left stock or a shop
     * escrow to take back) and there is a price index: its own room, else the next of the first rooms with a free stall
     * spot; 0 otherwise (every room full, say), and the ledger keeps it.
     */
    @Override
    public int mapFor(RosterEntry entry, Persona persona, int characterId) {
        if (Population.get() == null || stock(entry).isEmpty() && !interrupted(entry.characterId(), persona.id(), characterId)) {
            return 0;
        }
        int own = Math.floorMod(persona.id().hashCode(), ROOMS);
        for (int i = 0; i < ROOMS; i++) {
            int roomId = FreeMarketCharacterSpaceCatalog.FIRST_ROOM_MAP_ID + (own + i) % ROOMS;
            MapleMap room = world.residents().map(roomId);
            if (room != null && goods.hasFreeSpot(room)) {
                return roomId;
            }
        }
        return 0;
    }

    /** True when an interrupted stall of the companion's left stock of the haul or a shop escrow behind. */
    private boolean interrupted(int ownerId, String personaId, int characterId) {
        try {
            return !world.store().stallStock(ownerId, personaId).isEmpty()
                    || PlayerShopEscrowStore.load(characterId).isPresent();
        } catch (RuntimeException failure) {
            log.warn("bench stall of {}: its last stall could not be read", personaId, failure);
            return false;
        }
    }

    @Override
    public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
        AgentRuntimeEntry entry = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
        Population population = Population.get();
        RosterEntry roster = world.store().rosterEntry(slice.ownerId(), slice.personaId());
        if (entry == null || population == null || roster == null || agent.getClient() == null) {
            return false;
        }
        if (!reclaimFor(slice.ownerId(), slice.personaId(), agent)) {
            return false; // an escrow still waits to come back: a new shop would write over it
        }
        Stall stall = new Stall();
        int listed = 0;
        for (HaulItem item : stock(roster)) {
            boolean equip = ItemConstants.getInventoryType(item.itemId()) == InventoryType.EQUIP;
            int units = equip ? Math.min(item.quantity(), MAX_LISTINGS - listed)
                    : Math.min(item.quantity(), goods.slotMax(agent, item.itemId()));
            int moved = units <= 0 ? 0 : toBag(slice, agent, item.itemId(), units, equip);
            if (moved > 0) {
                stall.stocked.put(item.itemId(), moved);
                world.store().saveStallStock(slice.ownerId(), slice.personaId(), stall.held());
                listed += equip ? moved : 1;
            }
            if (listed >= MAX_LISTINGS) {
                break;
            }
        }
        if (!stall.stocked.isEmpty()) {
            agent.saveCharToDB(true); // the bag as the haul gave it up, before anything else can happen to it
        }
        Map<Integer, Double> asks = asks(slice, stall.stocked.keySet());
        List<AgentFreeMarketStallService.Listing> listings = listings(stall, agent, population, asks);
        int permit = population.content().permitItemId();
        if (!listings.isEmpty() && agent.getInventory(InventoryType.CASH).countById(permit) == 0) {
            InventoryManipulator.addById(agent.getClient(), permit, (short) 1); // like a citizen's stall
        }
        if (listings.isEmpty() || !AgentCapabilityRuntime.assign(entry, new AgentCapabilityInvocation<>(
                new AgentFreeMarketStallCapability(), new AgentFreeMarketStallCapability.Command(slice.mapId(),
                title(slice, stall), permit, listings), OPEN_TIMEOUT_MS, OPEN_RETRIES))) {
            unstock(slice, agent, stall); // nothing to open: every unit goes straight back
            return false;
        }
        stalls.put(slice.personaId(), stall);
        world.store().event(slice.ownerId(), slice.personaId(), "bench.stall", "stocked " + stall.stocked
                + " in room " + slice.mapId() + " at " + percents(asks));
        return true;
    }

    @Override
    public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry roster, long nowMs) {
        MapleMap map = agent.getMap();
        if (map == null || map.getId() != slice.mapId()) {
            return "left the market";
        }
        Stall stall = stalls.get(slice.personaId());
        if (stall.shop == null) {
            PlayerShop shop = shopOf(agent);
            if (shop == null) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId());
                return entry != null && entry.capabilityRuntimeState().hasActiveCapability()
                        ? null : "could not open its stall"; // still walking to its spot, or it gave up
            }
            stall.shop = shop;
            world.store().event(slice.ownerId(), slice.personaId(), "bench.stall-open", "room " + slice.mapId());
        }
        sales(slice, agent, stall);
        return stall.shop.isOpen() ? null : "sold out";
    }

    @Override
    public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
        Stall stall = stalls.remove(slice.personaId());
        if (stall == null) {
            return;
        }
        if (agent == null) {
            // gone with its stock: living_stall_stock keeps what it holds, and its next load takes it back first
            log.warn("bench stall of {} ended without its companion; {} is taken back at its next load", slice.personaId(),
                    stall.held());
            return;
        }
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId());
        if (entry != null) {
            AgentCapabilityRuntime.cancelNow(entry, agent, System.currentTimeMillis()); // a stall not yet open never opens now
        }
        PlayerShop shop = stall.shop != null ? stall.shop : shopOf(agent);
        if (shop != null && agent.getPlayerShop() == shop) {
            try {
                agent.closePlayerShop(CLOSE_REASON); // visitors leave, the unsold escrow returns to its bag
            } catch (RuntimeException failure) {
                log.warn("bench stall of {} did not close cleanly", slice.personaId(), failure);
            }
        } else if (shop != null && agent.getWorldServer() != null && agent.getWorldServer().getPlayerShop(agent.getId()) == shop) {
            agent.getWorldServer().unregisterPlayerShop(shop); // sold out: the shop closed itself but stayed listed
        }
        stall.shop = shop;
        FreeMarketStorePlacementService.release(agent);
        if (stall.shop != null) {
            sales(slice, agent, stall);
        }
        unstock(slice, agent, stall);
        world.store().event(slice.ownerId(), slice.personaId(), "bench.stall-close", reason + "; sold " + stall.sold);
    }

    /**
     * The companion just loaded (BenchDutyDispatcher, a slice of any duty or a call): what an interrupted stall of its
     * left behind (a shop escrow, and the stock living_stall_stock says it held) goes back to the haul, as far as its bag
     * holds it. A live stall's own stock is left alone.
     */
    public void reclaim(int ownerId, String personaId, Character agent) {
        if (personaId != null && agent != null && !stalls.containsKey(personaId)) {
            reclaimFor(ownerId, personaId, agent);
        }
    }

    /**
     * Before a new stall, or at any load: an interrupted stall's escrow (one per character, which a new shop would
     * overwrite) returns to its bag, and what that stall held of the haul (living_stall_stock, the larger count per
     * item where the escrow says more, since both describe the same goods) moves from its bag into the haul. False
     * while an escrow is still waiting (its recovery failed: a full bag, say); what the stall held is kept for the next
     * try then. Anything the bag no longer holds was sold (its meso is the companion's own) or never reached it.
     */
    private boolean reclaimFor(int ownerId, String personaId, Character agent) {
        Map<Integer, Integer> due;
        try {
            due = new LinkedHashMap<>(world.store().stallStock(ownerId, personaId));
        } catch (LivingStore.ReadFailed failure) {
            log.warn("bench stall of {} could not read what its last stall held", personaId, failure);
            return false;
        }
        boolean escrowLeft = false;
        try {
            Optional<PlayerShopEscrowSnapshot> escrow = PlayerShopEscrowStore.load(agent.getId());
            if (escrow.isPresent()) {
                Map<Integer, Integer> listed = new HashMap<>(); // one listing per bag slot: the units add up per item
                for (PlayerShopEscrowSnapshot.Listing listing : escrow.get().listings()) {
                    listed.merge(listing.item().itemId(), listing.item().quantity() * listing.bundles(), Integer::sum);
                }
                if (new AgentFreeMarketStallService().recoverInterruptedEscrow(agent)) {
                    listed.forEach((itemId, units) -> due.merge(itemId, units, Math::max));
                } else {
                    escrowLeft = true;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("bench stall of {} could not recover its last escrow", personaId, failure);
            escrowLeft = true; // not known to be back: never open a shop over it, and keep what the stall held
        }
        if (escrowLeft) {
            return false;
        }
        if (!due.isEmpty()) {
            toHaul(ownerId, personaId, agent, due);
            world.store().saveStallStock(ownerId, personaId, Map.of());
        }
        return true;
    }

    /** What it may sell from its haul (in haul order, a stall's worth): tradeable goods its owner's quests do not need. */
    private List<HaulItem> stock(RosterEntry entry) {
        QuestLedger quests = world.quests();
        List<HaulItem> out = new ArrayList<>();
        for (HaulItem item : world.store().haul(entry.characterId(), entry.personaId())) {
            if (out.size() >= MAX_LISTINGS) {
                break;
            }
            if (item.quantity() > 0 && sellable(item.itemId())
                    && (quests == null || !quests.isQuestItemFor(entry.characterId(), item.itemId()))) {
                out.add(item);
            }
        }
        return out;
    }

    /** What a player could list in a shop: no cash, rechargeable, unmerchable, untradeable or quest-only goods. */
    private boolean sellable(int itemId) {
        return SOLD.contains(ItemConstants.getInventoryType(itemId)) && !ItemConstants.isRechargeable(itemId)
                && !goods.restricted(itemId);
    }

    /**
     * Moves {@code units} of the item from the haul into its bag: the haul gives up exactly what reached the bag, at once
     * (the caller saves the bag right after, so a crash in between loses the move rather than making it twice).
     */
    private int toBag(BenchDutyDispatcher.Slice slice, Character agent, int itemId, int units, boolean equip) {
        int before = held(agent, itemId);
        if (equip) {
            for (int i = 0; i < units && InventoryManipulator.addById(agent.getClient(), itemId, (short) 1); i++) {
                // one equip per slot
            }
        } else {
            InventoryManipulator.addById(agent.getClient(), itemId, (short) Math.min(units, Short.MAX_VALUE));
        }
        int moved = Math.max(0, Math.min(units, held(agent, itemId) - before));
        if (moved > 0) {
            world.store().removeHaul(slice.ownerId(), slice.personaId(), itemId, moved);
        }
        return moved;
    }

    /**
     * One bundle size per item (so a sale's units are its bundles times it), over the bag slots that hold its stock,
     * each priced at its ask against the market index ({@link #asks}).
     */
    private static List<AgentFreeMarketStallService.Listing> listings(Stall stall, Character agent, Population population,
                                                                       Map<Integer, Double> asks) {
        List<AgentFreeMarketStallService.Listing> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> stocked : stall.stocked.entrySet()) {
            int itemId = stocked.getKey();
            InventoryType type = ItemConstants.getInventoryType(itemId);
            int per = type == InventoryType.EQUIP ? 1 : Math.min(stocked.getValue(), type == InventoryType.ETC ? 100 : 10);
            long unit = Math.max(1L, Math.round(population.market().price(itemId) * asks.getOrDefault(itemId, 1.0d)));
            int price = (int) Math.min(Integer.MAX_VALUE, unit * per);
            int left = stocked.getValue();
            for (Item slot : agent.getInventory(type).listById(itemId)) {
                int bundles = Math.min(left, slot.getQuantity()) / per;
                if (out.size() >= MAX_LISTINGS || bundles <= 0) {
                    continue;
                }
                out.add(new AgentFreeMarketStallService.Listing(type, slot.getPosition(), (short) per, (short) bundles, price));
                left -= bundles * per;
            }
            stall.perBundle.put(itemId, per);
        }
        return out;
    }

    /**
     * Its price per item against the market index. SELL: QUICK_SALE, where browsing citizens buy. STALL (user,
     * 2026-10-25: it learns its price, as a seller marks down what does not move): the index less BENCH_STALL_MARKDOWN_STEP
     * for each of its own last stall slices in a row that listed the item for their whole length and sold none of it
     * ({@link #unsoldSlices}), never under QUICK_SALE; back at the index once a slice sold some.
     */
    private Map<Integer, Double> asks(BenchDutyDispatcher.Slice slice, Set<Integer> items) {
        double step = world.config().BENCH_STALL_MARKDOWN_STEP;
        boolean sell = slice.duty() == Assignment.SELL;
        Map<Integer, Integer> unsold = sell || step <= 0.0d || items.isEmpty() ? Map.of() : unsoldSlices(
                world.store().personaEvents(slice.ownerId(), slice.personaId(), STALL_HISTORY_KINDS, STALL_HISTORY_EVENTS),
                items);
        Map<Integer, Double> asks = new LinkedHashMap<>();
        for (int itemId : items) {
            asks.put(itemId, sell ? QUICK_SALE : Math.max(QUICK_SALE, 1.0d - step * unsold.getOrDefault(itemId, 0)));
        }
        return asks;
    }

    /**
     * Per item of {@code items}: how many of the companion's own last stall slices in a row, newest first, listed it,
     * ran their whole length ({@link #FULL_SLICE}) and sold none of it. {@code events}: its bench events oldest first as
     * {kind, detail}, a "bench.stall" ("stocked {id=units, ...} in room N ...") and the "bench.stall-close" after it
     * ("reason; sold {id=units, ...}"). A slice that sold some of it ends the count; one cut short (a shutdown, a call,
     * a new duty) or never closed neither counts nor ends it.
     */
    static Map<Integer, Integer> unsoldSlices(List<String[]> events, Set<Integer> items) {
        List<Set<Integer>> stocked = new ArrayList<>();
        List<Set<Integer>> sold = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        Set<Integer> listed = null;
        for (String[] event : events == null ? List.<String[]>of() : events) {
            String kind = event.length > 0 ? event[0] : null;
            String detail = event.length > 1 && event[1] != null ? event[1] : "";
            if ("bench.stall".equals(kind)) {
                listed = ids(detail, 0);
            } else if ("bench.stall-close".equals(kind) && listed != null) {
                int at = detail.lastIndexOf("; sold ");
                stocked.add(listed);
                sold.add(at < 0 ? Set.of() : ids(detail, at));
                reasons.add(at < 0 ? detail : detail.substring(0, at));
                listed = null;
            }
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (int itemId : items) {
            int count = 0;
            for (int i = stocked.size() - 1; i >= 0 && !sold.get(i).contains(itemId); i--) {
                if (FULL_SLICE.equals(reasons.get(i)) && stocked.get(i).contains(itemId)) {
                    count++;
                }
            }
            out.put(itemId, count);
        }
        return out;
    }

    /** The item ids of the first "{id=units, ...}" at or after {@code from} in {@code text}; none when there is none. */
    private static Set<Integer> ids(String text, int from) {
        Set<Integer> ids = new HashSet<>();
        int open = text.indexOf('{', from);
        int close = open < 0 ? -1 : text.indexOf('}', open);
        if (close < 0) {
            return ids;
        }
        for (String pair : text.substring(open + 1, close).split(",")) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    ids.add(Integer.parseInt(pair.substring(0, eq).trim()));
                }
            } catch (NumberFormatException ignored) {
                // not an item id: skipped
            }
        }
        return ids;
    }

    /** "{4000000=85%, 4000019=100%}": each ask as a share of the index, for the stocking event. */
    private static String percents(Map<Integer, Double> asks) {
        StringBuilder out = new StringBuilder("{");
        asks.forEach((itemId, ask) -> out.append(out.length() > 1 ? ", " : "").append(itemId).append('=')
                .append(Math.round(ask * 100.0d)).append('%'));
        return out.append('}').toString();
    }

    /**
     * The shop this load of the companion opened: its open shop, or the one the world still lists for it once it sold
     * out (a shop left from an earlier load has another owner instance, so it is never taken for this one).
     */
    private static PlayerShop shopOf(Character agent) {
        PlayerShop shop = agent.getPlayerShop();
        if (shop == null && agent.getWorldServer() != null) {
            shop = agent.getWorldServer().getPlayerShop(agent.getId());
        }
        return shop != null && shop.isOwner(agent) ? shop : null;
    }

    /** The shop's title: its own "stall" (or "sell") line when its persona has one, else what it sells, in its voice. */
    private String title(BenchDutyDispatcher.Slice slice, Stall stall) {
        boolean sell = slice.duty() == Assignment.SELL;
        Persona persona = world.content().persona(slice.personaId());
        List<String> lines = persona == null ? List.of() : persona.lines(sell ? "sell" : "stall");
        if (!lines.isEmpty()) {
            return lines.get(Math.floorMod(slice.personaId().hashCode(), lines.size()));
        }
        String name = goods.name(stall.stocked.keySet().iterator().next());
        String wares = (name == null || name.isBlank() ? "odds and ends" : name.toLowerCase(Locale.ROOT))
                + (stall.stocked.size() > 1 ? " and more" : "");
        return sell ? "clearing my bag: " + wares : wares + ", fresh from camp";
    }

    /**
     * The sales since the last look: their units are counted (living_stall_stock holds the rest), and the meso they paid
     * moves from its purse to the haul, the purse saved first (a crash in between loses that meso, never doubles it).
     */
    private void sales(BenchDutyDispatcher.Slice slice, Character agent, Stall stall) {
        List<PlayerShop.SoldItem> sold = stall.shop.getSold();
        long meso = 0L;
        for (int i = stall.salesSeen; i < sold.size(); i++) {
            PlayerShop.SoldItem sale = sold.get(i);
            meso += Math.max(0, sale.getMesos());
            stall.sold.merge(sale.getItemId(), sale.getQuantity() * stall.perBundle.getOrDefault(sale.getItemId(), 1), Integer::sum);
        }
        int count = sold.size() - stall.salesSeen;
        stall.salesSeen = sold.size();
        RosterEntry roster = count <= 0 ? null : world.store().rosterEntry(slice.ownerId(), slice.personaId());
        if (roster == null) {
            return;
        }
        world.store().saveStallStock(slice.ownerId(), slice.personaId(), stall.held());
        long taken = Math.max(0L, Math.min(meso, agent.getMeso()));
        if (taken > 0) {
            agent.gainMeso((int) -taken, false);
            agent.saveCharToDB(true);
        }
        world.store().saveRosterProgress(new RosterEntry(roster.characterId(), roster.personaId(), roster.state(),
                roster.assignment(), roster.assignmentItem(), roster.assignmentCount(), roster.campMapId(), roster.accruedExp(),
                roster.haulMeso() + taken, roster.wagesPaidUntilMs(), roster.recruitedAtMs(), roster.benchedAtMs(),
                roster.lastLedgerAtMs(), roster.farmSecondsToday(), roster.farmDay()));
        world.bench().reportSold(slice.ownerId(), slice.personaId(), taken); // the call-back report's sales (BenchReport)
        CompanionViews.invalidate(world, slice.ownerId(), slice.personaId());
        world.store().event(slice.ownerId(), slice.personaId(), "bench.stall-sale", "+" + taken + " meso, " + count + " sales");
    }

    /**
     * The unsold stock back into the haul: what was stocked less what sold, never more than its bag holds. What its bag
     * does not hold (still in escrow after a failed close) stays in living_stall_stock for its next load.
     */
    private void unstock(BenchDutyDispatcher.Slice slice, Character agent, Stall stall) {
        Map<Integer, Integer> back = stall.held();
        Map<Integer, Integer> taken = back.isEmpty() ? Map.of() : toHaul(slice.ownerId(), slice.personaId(), agent, back);
        Map<Integer, Integer> missing = new LinkedHashMap<>();
        back.forEach((itemId, units) -> {
            int left = units - taken.getOrDefault(itemId, 0);
            if (left > 0) {
                missing.put(itemId, left);
            }
        });
        world.store().saveStallStock(slice.ownerId(), slice.personaId(), missing);
        if (!missing.isEmpty()) {
            log.warn("bench stall of {} could not hand back {}", slice.personaId(), missing);
        }
    }

    /**
     * Moves up to {@code units} of each item from its bag into the haul: out of the bag and the bag saved, then into the
     * haul (a crash in between loses them, never doubles them). What moved, per item.
     */
    private Map<Integer, Integer> toHaul(int ownerId, String personaId, Character agent, Map<Integer, Integer> units) {
        Map<Integer, Integer> taken = new LinkedHashMap<>();
        units.forEach((itemId, want) -> {
            int take = Math.min(want, held(agent, itemId));
            int moved = take > 0 ? BenchSessions.takeFromBag(agent, itemId, take) : 0;
            if (moved > 0) {
                taken.put(itemId, moved);
            }
        });
        if (taken.isEmpty()) {
            return taken;
        }
        agent.saveCharToDB(true);
        taken.forEach((itemId, take) -> world.store().addHaul(ownerId, personaId, itemId, take));
        return taken;
    }

    private static int held(Character agent, int itemId) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        Inventory bag = SOLD.contains(type) ? agent.getInventory(type) : null;
        return bag == null ? 0 : bag.countById(itemId);
    }
}
