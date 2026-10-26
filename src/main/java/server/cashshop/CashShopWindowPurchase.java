package server.cashshop;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import net.server.channel.handlers.EnterCashShopHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.CashShop;
import server.CashShop.CashItem;
import server.CashShop.CashItemFactory;
import server.ItemInformationProvider;
import server.economy.EconomyOperationKind;
import server.economy.EconomyTransactionCoordinator;
import tools.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * A Cash Shop window purchase (docs/CASH_SHOP_WINDOW.md): a cart of up to 24 items paid with NX Credit or Maple
 * Points, all or nothing. From the community release, with these changes:
 * <ul>
 *   <li>each row is a stock commodity and is delivered by its {@link CashItem#toItem()}, so it has the stock shop's
 *       serial number and duration (the release made every item permanent);</li>
 *   <li>the stock shop's gates hold: the Cash Shop entry refusals (an event, a mini-dungeon, a map that refuses the
 *       shop), level 16 for store permits, and USE_SUPPLY_RATE_COUPONS;</li>
 *   <li>every item is built before any is delivered, and delivery and payment run in one economy transaction: an item
 *       that does not go in rolls the inventory back and nothing is charged (the release kept what had gone in, free).
 *   </li>
 * </ul>
 */
public final class CashShopWindowPurchase {
    private static final Logger log = LoggerFactory.getLogger(CashShopWindowPurchase.class);

    public static final int MAX_CART = 24;

    private CashShopWindowPurchase() {
    }

    /**
     * @param code      one of {@code CashShopWindowPackets.BUY_*}
     * @param itemId    the item that stopped the cart, 0 when there is no single one
     * @param delivered how many items went in
     * @param spent     what was charged
     */
    public record Result(int code, int itemId, int delivered, int spent) {
        public boolean ok() {
            return code == CashShopWindowPackets.BUY_OK;
        }

        static Result fail(int code, int itemId) {
            return new Result(code, itemId, 0, 0);
        }
    }

    public static boolean currency(int type) {
        return type == CashShop.NX_CREDIT || type == CashShop.MAPLE_POINT;
    }

    public static Result buy(Client c, Character chr, int[] itemIds, int currency) {
        if (itemIds == null || itemIds.length == 0 || itemIds.length > MAX_CART || !currency(currency)) {
            return Result.fail(CashShopWindowPackets.BUY_BAD_CART, 0);
        }
        if (!c.tryacquireClient()) { // two carts at once could both pass the price check
            return Result.fail(CashShopWindowPackets.BUY_BUSY, 0);
        }
        try {
            return buyHeld(c, chr, itemIds, currency);
        } finally {
            c.releaseClient();
        }
    }

    private static Result buyHeld(Client c, Character chr, int[] itemIds, int currency) {
        if (EnterCashShopHandler.refusal(chr) != null) {
            return Result.fail(CashShopWindowPackets.BUY_NOT_HERE, 0);
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        CashShopWindowCatalog.Catalog catalog = CashShopWindowCatalog.get();

        // Resolve and check every line; nothing changes yet.
        List<CashItem> lines = new ArrayList<>(itemIds.length);
        Set<Integer> seen = new HashSet<>();
        for (int itemId : itemIds) {
            if (!seen.add(itemId)) {
                return Result.fail(CashShopWindowPackets.BUY_BAD_CART, itemId);
            }
            CashShopWindowCatalog.Row row = catalog.row(itemId);
            CashItem item = row == null ? null : CashItemFactory.getItem(row.sn());
            if (item == null || item.getItemId() != itemId || ii.getSlotMax(c, itemId) <= 0) {
                return Result.fail(CashShopWindowPackets.BUY_UNKNOWN_ITEM, itemId);
            }
            if (!item.isOnSale()) {
                return Result.fail(CashShopWindowPackets.BUY_NOT_ON_SALE, itemId);
            }
            if (ItemConstants.isCashStore(itemId) && chr.getLevel() < 16) {
                return Result.fail(CashShopWindowPackets.BUY_LEVEL, itemId);
            }
            if (ItemConstants.isRateCoupon(itemId) && !YamlConfig.config.server.USE_SUPPLY_RATE_COUPONS) {
                return Result.fail(CashShopWindowPackets.BUY_NOT_ON_SALE, itemId);
            }
            lines.add(item);
        }

        // The price, once for the whole cart, as a long so 24 large prices cannot wrap negative.
        long total = 0;
        for (CashItem item : lines) {
            total += item.getPrice();
        }
        CashShop cs = chr.getCashShop();
        if (total > cs.getCash(currency)) {
            return Result.fail(currency == CashShop.MAPLE_POINT
                    ? CashShopWindowPackets.BUY_NO_MAPLE_POINTS : CashShopWindowPackets.BUY_NO_NX, 0);
        }

        Result space = checkSpace(chr, lines, ii);
        if (space != null) {
            return space;
        }

        // Build everything before delivering anything: an item that cannot be built stops the cart here.
        List<Item> built = new ArrayList<>(lines.size());
        for (CashItem item : lines) {
            Item made = item.toItem();
            if (made == null) {
                log.error("Cash Shop window: commodity {} (item {}) did not build for {}", item.getSN(),
                        item.getItemId(), chr.getName());
                return Result.fail(CashShopWindowPackets.BUY_UNKNOWN_ITEM, item.getItemId());
            }
            built.add(made);
        }

        final int price = (int) total;
        boolean[] charged = {false};
        try {
            EconomyTransactionCoordinator.execute(chr, null, EconomyOperationKind.CASH_SHOP_WINDOW,
                    "cashshop=window items=" + built.size() + " currency=" + currency + " price=" + price, () -> {
                        for (Item item : built) {
                            if (!InventoryManipulator.addFromDrop(c, item, false)) {
                                throw new IllegalStateException("cash item " + item.getItemId() + " did not fit");
                            }
                        }
                        cs.gainCash(currency, -price);
                        charged[0] = true;
                    });
        } catch (RuntimeException e) {
            if (charged[0]) {
                cs.gainCash(currency, price); // the transaction rolled the inventory back after the charge
            }
            log.error("Cash Shop window: a cart of {} for {} rolled back", built.size(), chr.getName(), e);
            return Result.fail(CashShopWindowPackets.BUY_INVENTORY_FULL, 0);
        }
        log.info("{} bought {} cash item(s) for {} {} in the Cash Shop window", chr.getName(), built.size(), price,
                currency == CashShop.MAPLE_POINT ? "Maple Points" : "NX Credit");
        return new Result(CashShopWindowPackets.BUY_OK, 0, built.size(), price);
    }

    /** Room for the whole cart at once, stacking included; null when it fits. */
    private static Result checkSpace(Character chr, List<CashItem> lines, ItemInformationProvider ii) {
        List<Pair<Item, InventoryType>> probes = new LinkedList<>();
        List<Integer> uniques = new LinkedList<>();
        int petLines = 0;
        for (CashItem line : lines) {
            int itemId = line.getItemId();
            uniques.add(itemId);
            if (ItemConstants.isPet(itemId)) {
                petLines++; // each pet takes a fresh slot; the stacking probe would let it merge
                continue;
            }
            // A plain probe, not toItem(): a pet's toItem() inserts its row at once.
            probes.add(new Pair<>(new Item(itemId, (short) 0, line.getCount()),
                    ItemConstants.getInventoryType(itemId)));
        }
        if (!chr.canHoldUniques(uniques)) {
            return Result.fail(CashShopWindowPackets.BUY_BAD_CART, 0);
        }
        if (!probes.isEmpty() && !Inventory.checkSpots(chr, probes)) {
            return Result.fail(CashShopWindowPackets.BUY_INVENTORY_FULL, 0);
        }
        if (petLines > 0) {
            int cashLines = petLines; // the cart's other CASH lines counted a slot each, on the safe side
            for (CashItem line : lines) {
                if (!ItemConstants.isPet(line.getItemId())
                        && ItemConstants.getInventoryType(line.getItemId()) == InventoryType.CASH) {
                    cashLines++;
                }
            }
            if (chr.getInventory(InventoryType.CASH).isFull(cashLines - 1)) {
                return Result.fail(CashShopWindowPackets.BUY_INVENTORY_FULL, 0);
            }
        }
        return null;
    }
}
