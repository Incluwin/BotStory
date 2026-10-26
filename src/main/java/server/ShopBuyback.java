package server;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.economy.EconomyOperationKind;
import server.economy.EconomyTransactionCoordinator;
import server.mods.ServerMods;
import server.mods.features.FeatureHandshake;
import tools.PacketCreator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buy Back in the NPC shop window (the shop_buyback mod and client feature): the last {@link #MAX_ENTRIES} items a
 * player sold to NPC shops this session, bought back whole for exactly what the sale paid, so selling and buying back
 * never makes or loses mesos. The list lives in memory, survives a channel change and is cleared at login and logout.
 * Only a client with shop_buyback active fills it (Shop.sell), and bots never do: they sell through Shop.sellDirect.
 * Pets are left out (their state is tied to the database).
 *
 * <p>The client's Buy Back button asks for the list (NPC shop mode 4) and its tabs for the NPC's stock again (mode 5);
 * the list is an ordinary OPEN_NPC_SHOP whose row index is the list index, which a buy (mode 0) sends back while the
 * list shows. SHOP_BUYBACK (0x17F) tells the client which list shows and whether anything was sold.
 */
public final class ShopBuyback {
    private static final Logger log = LoggerFactory.getLogger(ShopBuyback.class);
    public static final String FEATURE = "shop_buyback";
    static final int MAX_ENTRIES = 30;

    /** A sold stack: what left the inventory and what the sale paid for it. */
    record Entry(Item item, int price) {
    }

    private static final Map<Integer, Deque<Entry>> sold = new ConcurrentHashMap<>();
    private static final Set<Integer> showing = ConcurrentHashMap.newKeySet();

    private ShopBuyback() {
    }

    /** The mod is on and this client has the Buy Back button. */
    public static boolean usable(Client c) {
        return c != null && !(c instanceof BotClient) && c.getPlayer() != null
                && ServerMods.get().isOn(ServerMods.SHOP_BUYBACK) && FeatureHandshake.get().isActive(c, FEATURE);
    }

    /** Shop.sell: remembers what a sale took and paid (a copy holding only the quantity that really left). */
    public static void recordSale(Client c, Item soldCopy, int proceeds) {
        if (!usable(c) || soldCopy == null || soldCopy.getQuantity() <= 0 || soldCopy.getPetId() >= 0) {
            return;
        }
        record(c.getPlayer().getId(), soldCopy, Math.max(0, proceeds));
    }

    static void record(int chrId, Item soldCopy, int price) {
        Deque<Entry> list = sold.computeIfAbsent(chrId, ignored -> new ArrayDeque<>());
        synchronized (list) {
            list.addFirst(new Entry(soldCopy, price));
            while (list.size() > MAX_ENTRIES) {
                list.removeLast();
            }
        }
    }

    /** Newest first, the order the list shows. */
    static List<Entry> entries(int chrId) {
        Deque<Entry> list = sold.get(chrId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /** Login and logout: a session starts with nothing to buy back. */
    public static void clear(int chrId) {
        sold.remove(chrId);
        showing.remove(chrId);
    }

    /** Whether a buy now means a Buy Back row (the list shows in this player's open shop). */
    public static boolean isShowing(Character chr) {
        return chr != null && chr.getShop() != null && showing.contains(chr.getId());
    }

    /** Shop.sendShop: the NPC's stock shows; tell the client, before the shop packet, whether Buy Back has rows. */
    public static void beforeShopSent(Client c) {
        if (c == null || c.getPlayer() == null) {
            return;
        }
        showing.remove(c.getPlayer().getId());
        if (usable(c)) {
            c.sendPacket(modePacket(false, !entries(c.getPlayer().getId()).isEmpty()));
        }
    }

    /** Mode 4: the Buy Back list in place of the NPC's stock. */
    public static void showList(Client c) {
        Character chr = c.getPlayer();
        Shop shop = chr.getShop();
        showing.add(chr.getId());
        List<Entry> list = entries(chr.getId());
        c.sendPacket(modePacket(true, !list.isEmpty()));
        c.sendPacket(listPacket(c, shop.getNpcId(), list));
    }

    /** Mode 5: the NPC's stock again. */
    public static void showStock(Client c) {
        c.getPlayer().getShop().sendShop(c);
    }

    /** After a sale: the list again while it shows, else just whether the button has rows. */
    public static void afterSale(Client c) {
        if (!usable(c)) {
            return;
        }
        if (isShowing(c.getPlayer())) {
            showList(c);
        } else {
            c.sendPacket(modePacket(false, !entries(c.getPlayer().getId()).isEmpty()));
        }
    }

    /**
     * Mode 0 while the list shows: the row at {@code index}, checked against {@code itemId}, comes back whole for what
     * its sale paid, inside one economy transaction. The row is taken off the list first, so a double click buys once.
     */
    public static void buy(Client c, int index, int itemId) {
        Character chr = c.getPlayer();
        String refusal = refusal(chr);
        Entry entry = refusal == null ? take(chr.getId(), index, itemId) : null;
        if (entry == null) {
            fail(c, refusal == null ? "That item is no longer in your Buy Back list." : refusal);
            return;
        }
        Item item = entry.item();
        if (chr.getMeso() < entry.price()) {
            putBack(chr.getId(), entry);
            c.sendPacket(PacketCreator.shopTransaction((byte) 2));
            return;
        }
        if (!InventoryManipulator.checkSpace(c, item.getItemId(), item.getQuantity(), item.getOwner())) {
            putBack(chr.getId(), entry);
            c.sendPacket(PacketCreator.shopTransaction((byte) 3));
            return;
        }
        try {
            EconomyTransactionCoordinator.execute(chr, null, EconomyOperationKind.SHOP_BUYBACK,
                    "shop=" + chr.getShop().getId() + " action=buyback item=" + item.getItemId() + " quantity="
                            + item.getQuantity() + " mesos=" + entry.price(), () -> {
                        if (!InventoryManipulator.addFromDrop(c, item.copy(), false)) {
                            throw new IllegalStateException("buyback item did not fit");
                        }
                        if (entry.price() > 0) {
                            chr.gainMeso(-entry.price(), false);
                        }
                    });
        } catch (RuntimeException e) {
            putBack(chr.getId(), entry); // the transaction restored the purse and the inventory
            log.warn("Buy back of item {} by {} rolled back", item.getItemId(), chr.getName(), e);
            return;
        }
        c.sendPacket(PacketCreator.shopTransaction((byte) 0));
        showList(c);
    }

    private static String refusal(Character chr) {
        if (chr.getTrade() != null || chr.getPlayerShop() != null || chr.getHiredMerchant() != null
                || chr.getMiniGame() != null) {
            return "Close your other windows before buying back.";
        }
        return null;
    }

    private static Entry take(int chrId, int index, int itemId) {
        Deque<Entry> list = sold.get(chrId);
        if (list == null) {
            return null;
        }
        synchronized (list) {
            if (index < 0 || index >= list.size()) {
                return null;
            }
            List<Entry> rows = new ArrayList<>(list);
            Entry entry = rows.get(index);
            if (entry.item().getItemId() != itemId) {
                return null;
            }
            list.remove(entry);
            return entry;
        }
    }

    private static void putBack(int chrId, Entry entry) {
        record(chrId, entry.item(), entry.price());
    }

    private static void fail(Client c, String why) {
        c.sendPacket(PacketCreator.shopTransaction((byte) 8));
        c.getPlayer().dropMessage(5, why);
        if (isShowing(c.getPlayer())) {
            showList(c);
        }
    }

    static Packet modePacket(boolean listShown, boolean hasSoldItems) {
        OutPacket p = OutPacket.create(SendOpcode.SHOP_BUYBACK);
        p.writeBool(listShown);
        p.writeBool(hasSoldItems);
        return p;
    }

    /** OPEN_NPC_SHOP with the Buy Back rows: each a whole stack, buyable once, at what its sale paid. */
    static Packet listPacket(Client c, int npcId, List<Entry> list) {
        OutPacket p = OutPacket.create(SendOpcode.OPEN_NPC_SHOP);
        p.writeInt(npcId);
        p.writeShort(list.size());
        for (Entry entry : list) {
            Item item = entry.item();
            p.writeInt(item.getItemId());
            p.writeInt(entry.price());
            p.writeInt(0); // Perfect Pitch
            p.writeInt(0);
            p.writeInt(0);
            if (!ItemConstants.isRechargeable(item.getItemId())) {
                p.writeShort(item.getQuantity()); // the whole stack in one purchase
                p.writeShort(1);                  // once
            } else {
                p.writeShort(0);
                p.writeInt(0);
                p.writeShort(0); // no unit price: the row price is the whole stack's
                p.writeShort(item.getQuantity());
            }
        }
        return p;
    }

}
