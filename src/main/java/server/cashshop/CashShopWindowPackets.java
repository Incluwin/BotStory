package server.cashshop;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import server.CashShop;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cash Shop window's packets (docs/CASH_SHOP_WINDOW.md), from the community release "cashshop-window" with our
 * opcodes: C->S 0x11A CASHSHOP_WINDOW and S->C 0x180 CASHSHOP_WINDOW_SYNC. The field order is the client's
 * {@code cashshopwnd.cpp}; change both sides or neither.
 *
 * <pre>
 *   C->S  byte action
 *           0 catalogue index        3 category: byte tab, byte category
 *           1 buy: int itemId, byte currency
 *           2 cart: byte n, n x int itemId, byte currency
 *           4 the stock Cash Shop (the stage, for the locker, gifts and wish list)
 *   S->C  byte response
 *           0 open: 3 x int cash     3 cash: 3 x int cash (NX Credit, Maple Points, NX Prepaid)
 *           1 category chunk: byte clear, byte tab, byte category, short n, n x row
 *           2 buy result: byte code, int itemId
 *           4 cart result: byte code, int itemId, byte delivered, int spent
 *           5 index: short n, n x (byte tab, byte category, int count)
 *           6 close
 * </pre>
 * A row is int itemId, int price, short count, byte tab, byte category, string name.
 */
public final class CashShopWindowPackets {
    public static final int ACTION_REQUEST_CATALOG = 0;
    public static final int ACTION_BUY = 1;
    public static final int ACTION_BUY_CART = 2;
    public static final int ACTION_REQUEST_CATEGORY = 3;
    public static final int ACTION_ENTER_STAGE = 4;

    public static final int RESP_OPEN = 0;
    public static final int RESP_CATALOG = 1;
    public static final int RESP_BUY = 2;
    public static final int RESP_CASH = 3;
    public static final int RESP_BUY_CART = 4;
    public static final int RESP_INDEX = 5;
    public static final int RESP_CLOSE = 6;

    // Indexes into the client's message table: append, never renumber.
    public static final int BUY_OK = 0;
    public static final int BUY_NO_NX = 1;
    public static final int BUY_UNKNOWN_ITEM = 2;
    public static final int BUY_INVENTORY_FULL = 3;
    public static final int BUY_NOT_ON_SALE = 4;
    public static final int BUY_BUSY = 5;
    public static final int BUY_BAD_CART = 6;
    public static final int BUY_NOT_HERE = 7;
    public static final int BUY_LEVEL = 8;
    public static final int BUY_NO_MAPLE_POINTS = 9;

    /** Below the client's 64-byte name guard, which rejects a whole chunk. */
    public static final int MAX_NAME = 40;
    /** Rows per category packet: about 25 KB, well under the 64 KB packet length. */
    static final int CHUNK = 400;

    private CashShopWindowPackets() {
    }

    public static Packet open(Character chr) {
        OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_OPEN);
        writeCash(p, chr.getCashShop());
        return p;
    }

    public static Packet cash(Character chr) {
        OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_CASH);
        writeCash(p, chr.getCashShop());
        return p;
    }

    public static Packet close() {
        OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_CLOSE);
        return p;
    }

    public static Packet buyResult(int code, int itemId) {
        OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_BUY);
        p.writeByte(code);
        p.writeInt(itemId);
        return p;
    }

    /** A cart is all or nothing; the counts tell the player which happened. */
    public static Packet cartResult(int code, int itemId, int delivered, int spent) {
        OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_BUY_CART);
        p.writeByte(code);
        p.writeInt(itemId);
        p.writeByte(delivered);
        p.writeInt(spent);
        return p;
    }

    /** Which categories exist and how many rows each holds; a category's rows come when the player opens it. */
    public static Packet index(List<int[]> index) {
        OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_INDEX);
        p.writeShort(index.size());
        for (int[] entry : index) {
            p.writeByte(entry[0]);
            p.writeByte(entry[1]);
            p.writeInt(entry[2]);
        }
        return p;
    }

    /** One category's rows in chunks; the first clears the category on the client. Always at least one packet. */
    public static List<Packet> category(int tab, int category, List<CashShopWindowCatalog.Row> rows) {
        List<Packet> out = new ArrayList<>();
        int total = rows.size();
        int start = 0;
        do {
            int end = Math.min(start + CHUNK, total);
            OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
            p.writeByte(RESP_CATALOG);
            p.writeByte(start == 0 ? 1 : 0);
            p.writeByte(tab);
            p.writeByte(category);
            p.writeShort(end - start);
            for (int i = start; i < end; i++) {
                CashShopWindowCatalog.Row row = rows.get(i);
                p.writeInt(row.itemId());
                p.writeInt(row.price());
                p.writeShort(row.count());
                p.writeByte(row.tab());
                p.writeByte(row.category());
                String name = row.name();
                p.writeString(name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name);
            }
            out.add(p);
            start = end;
        } while (start < total);
        return out;
    }

    private static void writeCash(OutPacket p, CashShop cs) {
        p.writeInt(cs.getCash(CashShop.NX_CREDIT));
        p.writeInt(cs.getCash(CashShop.MAPLE_POINT));
        p.writeInt(cs.getCash(CashShop.NX_PREPAID));
    }
}
