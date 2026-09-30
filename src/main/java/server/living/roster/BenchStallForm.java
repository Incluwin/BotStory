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
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopEscrowSnapshot;
import server.maps.PlayerShopEscrowStore;
import server.maps.reservation.FreeMarketCharacterSpaceCatalog;
import server.maps.reservation.FreeMarketStorePlacementService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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
 * its owner's quests do not need; never anything of its owner's), the companion loads in a Free Market room, registered
 * director-idle; the goods move from its haul into its bag, and AgentFreeMarketStallCapability walks it to a free stall
 * spot and opens the shop (AgentFreeMarketStallService), titled in its voice and priced from the population market's
 * price index like the stalls around it (SELL a little under the index, where browsing citizens buy). Each tick the
 * meso of its sales goes into the haul. When the slice ends (its time, a call, a new duty, sold out) the shop closes,
 * the last sales go into the haul and the unsold stock goes back to it. Nothing is duplicated or lost: the haul gives
 * up what reached the bag, gets back what was not sold (never more than the bag holds) and the meso the sales paid.
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
    private static final long OPEN_TIMEOUT_MS = 90_000L;
    private static final int OPEN_RETRIES = 2;
    private static final String CLOSE_REASON = "BENCH_SLICE_ENDED";

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
    }

    /** The item facts a stall needs: the game's (ItemInformationProvider, from WZ), or a unit test's own. */
    interface Goods {
        /** True for what no player could put in a shop: unmerchable, quest-only (drop-restricted) or untradeable. */
        boolean restricted(int itemId);

        /** Units of the item one bag slot holds. */
        int slotMax(Character agent, int itemId);

        /** The item's name as the game shows it, or null. */
        String name(int itemId);
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
    };

    private final LivingWorld world;
    private final Goods goods;
    /** persona id -> its live stall. */
    private final Map<String, Stall> stalls = new ConcurrentHashMap<>();
    /** persona id -> unsold stock its last stall could not hand back (it was gone): taken back at its next stall. */
    private final Map<String, Map<Integer, Integer>> owed = new ConcurrentHashMap<>();

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
     * Its Free Market room while its haul has something to sell (or its last stall owes the haul stock) and there is a
     * price index; 0 otherwise, and the ledger keeps it.
     */
    @Override
    public int mapFor(RosterEntry entry, Persona persona, int characterId) {
        if (Population.get() == null || stock(entry).isEmpty() && !owed.containsKey(persona.id())) {
            return 0;
        }
        return FreeMarketCharacterSpaceCatalog.FIRST_ROOM_MAP_ID + Math.floorMod(persona.id().hashCode(), ROOMS);
    }

    @Override
    public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
        AgentRuntimeEntry entry = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
        Population population = Population.get();
        RosterEntry roster = world.store().rosterEntry(slice.ownerId(), slice.personaId());
        if (entry == null || population == null || roster == null || agent.getClient() == null) {
            return false;
        }
        reclaim(slice, agent);
        Stall stall = new Stall();
        int listed = 0;
        for (HaulItem item : stock(roster)) {
            boolean equip = ItemConstants.getInventoryType(item.itemId()) == InventoryType.EQUIP;
            int units = equip ? Math.min(item.quantity(), MAX_LISTINGS - listed)
                    : Math.min(item.quantity(), goods.slotMax(agent, item.itemId()));
            int moved = units <= 0 ? 0 : toBag(slice, agent, item.itemId(), units, equip);
            if (moved > 0) {
                stall.stocked.put(item.itemId(), moved);
                listed += equip ? moved : 1;
            }
            if (listed >= MAX_LISTINGS) {
                break;
            }
        }
        List<AgentFreeMarketStallService.Listing> listings = listings(stall, agent, population, slice.duty());
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
                + " in room " + slice.mapId());
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
            owe(slice, stall); // gone with its stock: its next stall takes it back first
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

    /** Moves {@code units} of the item from the haul into its bag: the haul gives up exactly what reached the bag. */
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

    /** One bundle size per item (so a sale's units are its bundles times it), over the bag slots that hold its stock. */
    private static List<AgentFreeMarketStallService.Listing> listings(Stall stall, Character agent, Population population,
                                                                       Assignment duty) {
        List<AgentFreeMarketStallService.Listing> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> stocked : stall.stocked.entrySet()) {
            int itemId = stocked.getKey();
            InventoryType type = ItemConstants.getInventoryType(itemId);
            int per = type == InventoryType.EQUIP ? 1 : Math.min(stocked.getValue(), type == InventoryType.ETC ? 100 : 10);
            long unit = Math.max(1L, Math.round(population.market().price(itemId) * (duty == Assignment.SELL ? QUICK_SALE : 1.0d)));
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

    /** The sales since the last look: their units are counted, and the meso they paid moves from its purse to the haul. */
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
        long taken = Math.max(0L, Math.min(meso, agent.getMeso()));
        if (taken > 0) {
            agent.gainMeso((int) -taken, false);
        }
        world.store().saveRosterProgress(new RosterEntry(roster.characterId(), roster.personaId(), roster.state(),
                roster.assignment(), roster.assignmentItem(), roster.assignmentCount(), roster.campMapId(), roster.accruedExp(),
                roster.haulMeso() + taken, roster.wagesPaidUntilMs(), roster.recruitedAtMs(), roster.benchedAtMs(),
                roster.lastLedgerAtMs(), roster.farmSecondsToday(), roster.farmDay()));
        world.bench().reportSold(slice.ownerId(), slice.personaId(), taken); // the call-back report's sales (BenchReport)
        CompanionViews.invalidate(world, slice.ownerId(), slice.personaId());
        world.store().event(slice.ownerId(), slice.personaId(), "bench.stall-sale", "+" + taken + " meso, " + count + " sales");
    }

    /** The unsold stock back into the haul: what was stocked less what sold, never more than its bag holds. */
    private void unstock(BenchDutyDispatcher.Slice slice, Character agent, Stall stall) {
        Map<Integer, Integer> missing = new HashMap<>();
        for (Map.Entry<Integer, Integer> stocked : stall.stocked.entrySet()) {
            int back = stocked.getValue() - stall.sold.getOrDefault(stocked.getKey(), 0);
            int taken = back <= 0 ? 0 : toHaul(slice, agent, stocked.getKey(), back);
            if (taken < back) {
                missing.put(stocked.getKey(), back - taken); // still in escrow (a failed close): its next stall takes it back
            }
        }
        if (!missing.isEmpty()) {
            owed.merge(slice.personaId(), missing, (was, now) -> {
                now.forEach((itemId, units) -> was.merge(itemId, units, Integer::sum));
                return was;
            });
            log.warn("bench stall of {} could not hand back {}", slice.personaId(), missing);
        }
    }

    private void owe(BenchDutyDispatcher.Slice slice, Stall stall) {
        Map<Integer, Integer> unsold = new HashMap<>();
        stall.stocked.forEach((itemId, units) -> {
            int back = units - stall.sold.getOrDefault(itemId, 0);
            if (back > 0) {
                unsold.put(itemId, back);
            }
        });
        if (!unsold.isEmpty()) {
            owed.put(slice.personaId(), unsold);
            log.warn("bench stall of {} ended without its companion; {} is taken back at its next stall", slice.personaId(), unsold);
        }
    }

    /**
     * Before a new stall: an interrupted stall's escrow (one per character, which a new shop would overwrite) returns
     * to its bag, and what its last stall owes the haul (the larger of the two counts, since both describe the same
     * goods) moves from its bag into the haul.
     */
    private void reclaim(BenchDutyDispatcher.Slice slice, Character agent) {
        Map<Integer, Integer> due = owed.remove(slice.personaId());
        due = due == null ? new HashMap<>() : due;
        try {
            Optional<PlayerShopEscrowSnapshot> escrow = PlayerShopEscrowStore.load(agent.getId());
            if (escrow.isPresent() && new AgentFreeMarketStallService().recoverInterruptedEscrow(agent)) {
                for (PlayerShopEscrowSnapshot.Listing listing : escrow.get().listings()) {
                    due.merge(listing.item().itemId(), listing.item().quantity() * listing.bundles(), Math::max);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("bench stall of {} could not recover its last escrow", slice.personaId(), failure);
        }
        due.forEach((itemId, units) -> toHaul(slice, agent, itemId, units));
    }

    /** Moves up to {@code units} of the item from its bag into the haul; how many moved. */
    private int toHaul(BenchDutyDispatcher.Slice slice, Character agent, int itemId, int units) {
        int taken = Math.min(units, held(agent, itemId));
        if (taken > 0) {
            InventoryManipulator.removeById(agent.getClient(), ItemConstants.getInventoryType(itemId), itemId, taken, false, false);
            world.store().addHaul(slice.ownerId(), slice.personaId(), itemId, taken);
        }
        return Math.max(0, taken);
    }

    private static int held(Character agent, int itemId) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        Inventory bag = SOLD.contains(type) ? agent.getInventory(type) : null;
        return bag == null ? 0 : bag.countById(itemId);
    }
}
