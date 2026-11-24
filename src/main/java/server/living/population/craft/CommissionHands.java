package server.living.population.craft;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.processor.npc.DueyProcessor;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.economy.EconomyOperationKind;
import server.economy.EconomyTransactionCoordinator;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.economy.ledger.MesoHolder;
import server.itcg.ItcgRecipes.Stack;
import tools.Pair;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where a commission's items and meso really move, each step one COMMISSION economy transaction with the order's row:
 * the materials and the fee out of the customer's bag and purse into the order; what was made to the customer (into
 * their bag, or by Duey) and the fee to the crafter as the materials go; or everything back. The materials stay whole in
 * the order until the same commit that delivers, so nothing exists twice and nothing is lost on the way.
 */
public class CommissionHands {
    private static final Logger log = LoggerFactory.getLogger(CommissionHands.class);
    private static final short UNTRADEABLE = ItemConstants.LOCK | ItemConstants.UNTRADEABLE;
    private static final String AGAIN = "That didn't work. Try again in a moment.";

    /** The item data the hands read; apart so tests need no WZ. */
    interface Items {
        /** Whether a player may hand it over at all (not untradeable by its data, not a quest item). */
        boolean tradeable(int itemId);

        /** {@code count} of {@code itemId} as a stock forging NPC gives it: base stats, nothing added. */
        Item make(int itemId, int count);

        /** A scroll whose failure cannot destroy the piece, that fits it (CitizenScrolling's rule). */
        boolean scrolls(int scrollId, int equipId);

        /** {@code equip} after one use of {@code scrollId}, rolled as a player's own. */
        Equip scrolled(Equip equip, int scrollId);
    }

    /** The game's item data. */
    private static final class WzItems implements Items {
        @Override
        public boolean tradeable(int itemId) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return !ii.isUntradeableRestricted(itemId) && !ii.isQuestItem(itemId);
        }

        @Override
        public Item make(int itemId, int count) {
            if (ItemConstants.getInventoryType(itemId) != InventoryType.EQUIP) {
                return new Item(itemId, (short) 0, (short) count);
            }
            Item item = ItemInformationProvider.getInstance().getEquipById(itemId);
            if (item instanceof Equip equip && ItemConstants.isAccessory(itemId) && equip.getUpgradeSlots() <= 0) {
                equip.setUpgradeSlots(3); // as AbstractPlayerInteraction.gainItem gives an accessory
            }
            return item;
        }

        @Override
        public boolean scrolls(int scrollId, int equipId) {
            Map<String, Integer> info = ItemInformationProvider.getInstance().getEquipStats(scrollId);
            return scrollId / 10_000 == 204 && info != null && !ItemConstants.isChaosScroll(scrollId)
                    && !ItemConstants.isCleanSlate(scrollId) && !ItemConstants.isModifierScroll(scrollId)
                    && info.getOrDefault("success", 0) > 0 && info.getOrDefault("cursed", 0) == 0
                    && ItemInformationProvider.getInstance().canApplyScroll(scrollId, equipId);
        }

        @Override
        public Equip scrolled(Equip equip, int scrollId) {
            return (Equip) ItemInformationProvider.getInstance().scrollEquipWithId(equip, scrollId, false, 0, false);
        }
    }

    private final CommissionStore store;
    private final Items items;

    public CommissionHands(CommissionStore store) {
        this(store, new WzItems());
    }

    CommissionHands(CommissionStore store, Items items) {
        this.store = store;
        this.items = items;
    }

    // ---- what a customer carries -----------------------------------------------------------------

    /** The customer's items the order takes, a part of a stack each, or null when they lack some. */
    List<Item> materials(Character customer, List<Stack> inputs, int quantity) {
        List<Item> out = new ArrayList<>();
        for (Stack input : inputs) {
            int left = input.count() * quantity;
            for (Item item : offered(customer, input.itemId())) {
                if (left <= 0) {
                    break;
                }
                int take = Math.min(left, item.getQuantity());
                out.add(part(item, take));
                left -= take;
            }
            if (left > 0) {
                return null;
            }
        }
        return out;
    }

    /** How many of a recipe the customer's bag covers. */
    public int covers(Character customer, List<Stack> inputs) {
        int most = Integer.MAX_VALUE;
        for (Stack input : inputs) {
            int have = offered(customer, input.itemId()).stream().mapToInt(Item::getQuantity).sum();
            most = Math.min(most, have / input.count());
        }
        return most;
    }

    /** The loose items of one kind; equips plainest first (unscrolled, then the fewest stats). */
    private List<Item> offered(Character customer, int itemId) {
        List<Item> out = new ArrayList<>();
        for (Item item : customer.getInventory(ItemConstants.getInventoryType(itemId)).list()) {
            if (item.getItemId() == itemId && loose(item) && !ItemConstants.isRechargeable(itemId)) {
                out.add(item);
            }
        }
        if (!out.isEmpty() && !items.tradeable(itemId)) {
            return List.of();
        }
        out.sort(Comparator.comparingInt(item -> item instanceof Equip equip
                ? equip.getLevel() * 10_000 + equip.getStr() + equip.getDex() + equip.getInt() + equip.getLuk()
                + 4 * (equip.getWatk() + equip.getMatk()) : 0));
        return out;
    }

    private static boolean loose(Item item) {
        return item.getQuantity() > 0 && (item.getFlag() & UNTRADEABLE) == 0 && item.getExpiration() == -1L
                && item.getPetId() == -1 && item.getPosition() > 0;
    }

    /** A piece in the customer's bag and a scroll of theirs that fits it. */
    public record ScrollPair(Equip piece, Item scroll) {
    }

    /** What a scroller could work on now: each piece with slots left and each kind of scroll that fits it. */
    public List<ScrollPair> scrollPairs(Character customer) {
        List<ScrollPair> out = new ArrayList<>();
        for (Item worn : customer.getInventory(InventoryType.EQUIP).list()) {
            if (!(worn instanceof Equip piece) || piece.getUpgradeSlots() <= 0 || !loose(piece)
                    || !items.tradeable(piece.getItemId())) {
                continue;
            }
            for (Item scroll : customer.getInventory(InventoryType.USE).list()) {
                if (scroll.getItemId() / 10_000 == 204 && loose(scroll) && items.tradeable(scroll.getItemId())
                        && items.scrolls(scroll.getItemId(), piece.getItemId())
                        && out.stream().noneMatch(pair -> pair.piece() == piece
                        && pair.scroll().getItemId() == scroll.getItemId())) {
                    out.add(new ScrollPair(piece, scroll));
                }
            }
        }
        return out;
    }

    /** The two items of a scroller's order, or null when they are not there any more. */
    List<Item> scrollMaterials(Character customer, short pieceSlot, int pieceId, int scrollId) {
        for (ScrollPair pair : scrollPairs(customer)) {
            if (pair.piece().getPosition() == pieceSlot && pair.piece().getItemId() == pieceId
                    && pair.scroll().getItemId() == scrollId) {
                return List.of(part(pair.piece(), 1), part(pair.scroll(), 1));
            }
        }
        return null;
    }

    private static Item part(Item item, int quantity) {
        Item part = item.copy();
        part.setPosition(item.getPosition());
        part.setQuantity((short) quantity);
        return part;
    }

    /** A free slot for each item in its tab: maybe more than it takes, never less. */
    private static boolean roomFor(Character customer, List<Item> wanted) {
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                InventoryType.ETC}) {
            long needs = wanted.stream().filter(item -> ItemConstants.getInventoryType(item.getItemId()) == type).count();
            if (needs > 0 && customer.getInventory(type).getNumFreeSlot() < needs) {
                return false;
            }
        }
        return true;
    }

    private static void add(Character to, Item item) {
        if (!InventoryManipulator.addFromDrop(to.getClient(), item.copy(), false)) {
            throw new IllegalStateException("no room for " + item.getItemId() + " in " + to.getName() + "'s bag");
        }
    }

    // ---- the moves ----------------------------------------------------------------------------------

    /** Finds the order's materials again under the customer's own lock; null: not there any more. */
    interface Finder {
        List<Item> find();
    }

    /** The order as stored, or what to tell the customer. */
    record Placed(Commission order, String refused) {
    }

    /** The materials and the fee go from the customer into a new order. */
    Placed place(Character customer, Commission order, Finder finder) {
        AtomicReference<String> refused = new AtomicReference<>();
        AtomicInteger id = new AtomicInteger();
        try {
            EconomyTransactionCoordinator.execute(customer, null, EconomyOperationKind.COMMISSION,
                    customer.getName() + " orders " + order.quantity() + " x " + order.recipe() + " from citizen "
                            + order.crafterId() + " mesos=" + order.fee(), context -> {
                        List<Item> moving = finder.find();
                        if (moving == null || order.fee() > customer.getMeso()) {
                            refused.set(moving == null ? "You don't have the materials any more."
                                    : "You can't pay the fee.");
                            return;
                        }
                        List<Pair<Item, InventoryType>> held = take(customer, moving);
                        customer.gainMeso((int) -order.fee(), true);
                        context.enlist(con -> {
                            id.set(store.insert(con, order));
                            store.saveItems(con, id.get(), held);
                        }, () -> { });
                    });
        } catch (RuntimeException failure) {
            log.warn("order of {} by {} rolled back", order.recipe(), customer.getName(), failure);
            return new Placed(null, AGAIN);
        }
        return refused.get() != null ? new Placed(null, refused.get()) : new Placed(order.withId(id.get()), null);
    }

    /** Takes {@code moving} out of the bag; the copies, as an order holds them. */
    private static List<Pair<Item, InventoryType>> take(Character from, List<Item> moving) {
        List<Pair<Item, InventoryType>> held = new ArrayList<>();
        for (Item item : moving) {
            Item copy = item.copy();
            copy.setPosition((short) 0);
            InventoryType type = ItemConstants.getInventoryType(item.getItemId());
            InventoryManipulator.removeFromSlot(from.getClient(), type, item.getPosition(), copy.getQuantity(), false);
            held.add(new Pair<>(copy, type));
        }
        return held;
    }

    /** Everything the order holds goes back into the customer's bag and purse. Null when done, else why not. */
    String giveBack(Character customer, Commission closed) {
        List<Pair<Item, InventoryType>> held = store.items(closed.id());
        if (held == null) {
            return AGAIN;
        }
        AtomicReference<String> refused = new AtomicReference<>();
        try {
            EconomyTransactionCoordinator.execute(customer, null, EconomyOperationKind.COMMISSION,
                    customer.getName() + " cancels commission " + closed.id() + " mesos=" + closed.fee(), context -> {
                        if (!customer.canHoldMeso((int) closed.fee())) {
                            refused.set("You can't hold that many mesos.");
                            return;
                        }
                        if (!roomFor(customer, held.stream().map(Pair::getLeft).toList())) {
                            refused.set("Make some room in your bag first.");
                            return;
                        }
                        for (Pair<Item, InventoryType> entry : held) {
                            add(customer, entry.getLeft());
                        }
                        customer.gainMeso((int) closed.fee(), true);
                        context.enlist(con -> {
                            store.update(con, closed);
                            store.saveItems(con, closed.id(), List.of());
                        }, () -> { });
                    });
        } catch (RuntimeException failure) {
            log.warn("cancel of commission {} rolled back", closed.id(), failure);
            return AGAIN;
        }
        return refused.get();
    }

    /** What the order makes: the recipe's result, or the piece after its scroll; null when that cannot be told. */
    private Item made(Commission order, RecipeBook.Recipe recipe, List<Pair<Item, InventoryType>> held) {
        if (!order.scroll()) {
            return recipe == null ? null : items.make(recipe.result(), recipe.resultCount() * order.quantity());
        }
        Equip piece = null;
        int scrollId = 0;
        for (Pair<Item, InventoryType> entry : held) {
            if (entry.getLeft() instanceof Equip equip) {
                piece = equip;
            } else {
                scrollId = entry.getLeft().getItemId();
            }
        }
        return piece == null || scrollId == 0 ? null : items.scrolled((Equip) piece.copy(), scrollId);
    }

    /**
     * The order is done: the materials go, the crafter is paid, and what it made goes to the customer, into their bag
     * when {@code customer} is given and has room, else by Duey from {@code sender} with {@code note}; all in one commit
     * with the row. Null when nothing moved (it stays with the crafter and is tried again).
     */
    Delivered deliver(Character crafter, Character customer, Commission done, RecipeBook.Recipe recipe, String sender,
                      String note) {
        List<Pair<Item, InventoryType>> held = store.items(done.id());
        if (held == null || held.isEmpty() || !crafter.canHoldMeso((int) done.fee())) {
            return null;
        }
        Item made = made(done, recipe, held);
        if (made == null) {
            return null;
        }
        made.setPosition((short) 0);
        Character receiver = customer != null && roomFor(customer, List.of(made)) ? customer : null;
        AtomicBoolean byHand = new AtomicBoolean();
        try {
            EconomyTransactionCoordinator.execute(crafter, receiver, EconomyOperationKind.COMMISSION,
                    "citizen " + crafter.getId() + " delivers commission " + done.id() + " (" + done.recipe() + ") to "
                            + done.customerId() + (receiver == null ? " by duey" : "") + " mesos=" + done.fee(), context -> {
                        // again under both locks: the bag may have filled meanwhile
                        byHand.set(receiver != null && roomFor(receiver, List.of(made)));
                        if (byHand.get()) {
                            add(receiver, made);
                        }
                        crafter.gainMeso((int) done.fee(), false);
                        context.enlist(con -> {
                            store.update(con, done);
                            store.saveItems(con, done.id(), List.of());
                            if (!byHand.get()) {
                                store.mail(con, made, 0, note, sender, done.customerId());
                            }
                        }, () -> { });
                    });
            return new Delivered(made, byHand.get());
        } catch (RuntimeException failure) {
            log.warn("delivery of commission {} rolled back", done.id(), failure);
            return null;
        }
    }

    /** What was made, and whether it was handed over in person (else by Duey). */
    record Delivered(Item made, boolean byHand) {
    }

    /** Everything the order holds goes back by Duey, a package an item and one with the fee; true when it did. */
    boolean refund(Commission closed, String sender, String note) {
        List<Pair<Item, InventoryType>> held = store.items(closed.id());
        return held != null && store.inTransaction(con -> {
            store.update(con, closed);
            store.saveItems(con, closed.id(), List.of());
            for (Pair<Item, InventoryType> entry : held) {
                store.mail(con, entry.getLeft(), 0, note, sender, closed.customerId());
            }
            if (closed.fee() > 0) {
                store.mail(con, null, (int) closed.fee(), note, sender, closed.customerId());
            }
        });
    }

    void notifyDuey(Character customer) {
        DueyProcessor.dueyNotify(customer);
    }

    // ---- a citizen's want, made by a crafter -----------------------------------------------------------

    /** Stores what comes with a want's move (the request's row, its escrow, a stored purse), on the move's connection. */
    public interface Want {
        void store(Connection con, Item made) throws SQLException;
    }

    /**
     * A crafter makes {@code recipe} out of its own bag for another citizen, who pays {@code pay}: the materials go, the
     * piece goes into the loaded {@code buyer}'s bag (null: away, so {@code want} puts it in escrow and takes the meso
     * from its stored purse), and the meso into the crafter's. True when done.
     */
    public boolean makeForCitizen(Character crafter, Character buyer, int buyerId, RecipeBook.Recipe recipe, long pay,
                                  Want want) {
        Item made = items.make(recipe.result(), recipe.resultCount());
        if (made == null || pay > Integer.MAX_VALUE || !crafter.canHoldMeso((int) pay)) {
            return false;
        }
        AtomicBoolean done = new AtomicBoolean();
        try {
            EconomyTransactionCoordinator.execute(crafter, buyer, EconomyOperationKind.COMMISSION, "citizen "
                    + crafter.getId() + " makes " + recipe.id() + " for citizen " + buyerId + " mesos=" + pay, context -> {
                List<Item> moving = materials(crafter, recipe.inputs(), 1);
                if (moving == null || buyer != null && (buyer.getMeso() < pay || !roomFor(buyer, List.of(made)))) {
                    return;
                }
                take(crafter, moving);
                crafter.gainMeso((int) pay, false);
                if (buyer != null) {
                    add(buyer, made);
                    buyer.gainMeso((int) -pay, false);
                }
                context.enlist(con -> {
                    want.store(con, made);
                    if (buyer == null) {
                        MesoFlows.record(MesoAccount.COMMISSION, MesoHolder.CITIZEN, -pay);
                    }
                }, () -> { });
                done.set(true);
            });
        } catch (RuntimeException failure) {
            log.warn("citizen {} could not make {} for citizen {}", crafter.getId(), recipe.id(), buyerId, failure);
            return false;
        }
        return done.get();
    }
}
