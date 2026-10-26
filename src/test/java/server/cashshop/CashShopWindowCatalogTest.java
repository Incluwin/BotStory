package server.cashshop;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import server.cashshop.CashShopWindowCatalog.Catalog;
import server.cashshop.CashShopWindowCatalog.Offer;
import server.cashshop.CashShopWindowCatalog.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CashShopWindowCatalogTest {
    private static final Map<Integer, String> NAMES = Map.of(1002000, "Blue Hat", 5370000, "Chalkboard",
            5000000, "Brown Kitty", 4031063, "Torr's Horn", 1112001, "Crush Ring", 5431000, "Maple Life",
            1802000, "Kitty Ears", 5211048, "2x EXP Card");

    private static Catalog catalog(Offer... offers) {
        return CashShopWindowCatalog.build(List.of(offers), NAMES::get);
    }

    @Test
    void itSellsTheStockOfferWithItsSerialPriceAndCategory() {
        Catalog catalog = catalog(new Offer(20000016, 1002000, 3700, 1, 90, true));

        assertEquals(new Row(20000016, 1002000, 3700, 1, 2, 0, "Blue Hat"), catalog.row(1002000));
        assertEquals(List.of(catalog.row(1002000)), catalog.bucket(2, 0));
        assertEquals(1, catalog.index().size());
        assertArrayEquals(new int[]{2, 0, 1}, catalog.index().get(0));
    }

    @Test
    void offersThatAreOffSaleFreeNamelessOrNotForTheWindowAreLeftOut() {
        Catalog catalog = catalog(
                new Offer(1, 1002000, 3700, 1, 90, false),     // off sale
                new Offer(2, 5000000, 0, 1, 90, true),         // free
                new Offer(3, 1002001, 3700, 1, 90, true),      // no name
                new Offer(4, 4031063, 1, 1, 90, true),         // a quest item
                new Offer(5, 1112001, 3000, 1, 90, true),      // a ring: the stock shop sells it with a partner
                new Offer(6, 5431000, 3000, 1, 90, true),      // Maple Life
                new Offer(7, 5211048, 2000, 1, 1, true));      // offered, so it stays (the purchase gates coupons)

        assertNull(catalog.row(1002000));
        assertNull(catalog.row(5000000));
        assertNull(catalog.row(1002001));
        assertNull(catalog.row(4031063));
        assertNull(catalog.row(1112001));
        assertNull(catalog.row(5431000));
        assertEquals(7, catalog.row(5211048).sn());
    }

    @Test
    void anItemOfferedTwiceSellsTheBetterBuyPerItemPerDay() {
        Catalog catalog = catalog(new Offer(50200068, 5370000, 1000, 1, 1, true),
                new Offer(50200067, 5370000, 6000, 1, 7, true));

        assertEquals(50200067, catalog.row(5370000).sn(), "857 a day beats 1,000 a day");
        assertEquals(1, catalog.bucket(5, 2).size());
    }

    @Test
    void itemsLandInTheWindowsCategories() {
        assertArrayEquals(new int[]{2, 0}, CashShopWindowCatalog.categorise(1002000));
        assertArrayEquals(new int[]{2, 8}, CashShopWindowCatalog.categorise(1702000));
        assertArrayEquals(new int[]{2, 9}, CashShopWindowCatalog.categorise(1032000), "earrings share Ring");
        assertArrayEquals(new int[]{6, 1}, CashShopWindowCatalog.categorise(1802000));
        assertArrayEquals(new int[]{6, 0}, CashShopWindowCatalog.categorise(5000000));
        assertArrayEquals(new int[]{5, 1}, CashShopWindowCatalog.categorise(5140000));
        assertArrayEquals(new int[]{5, 2}, CashShopWindowCatalog.categorise(5030000), "a merchant is a game item");
        assertNull(CashShopWindowCatalog.categorise(2000000));
    }

    @Test
    void aCategoryChunkCarriesTheRowsTheClientReads() {
        List<Row> rows = List.of(new Row(20000016, 1002000, 3700, 1, 2, 0, "Blue Hat"));
        List<Packet> packets = CashShopWindowPackets.category(2, 0, rows);

        assertEquals(1, packets.size());
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packets.get(0).getBytes()));
        assertEquals(SendOpcode.CASHSHOP_WINDOW_SYNC.getValue(), in.readShort());
        assertEquals(0x180, SendOpcode.CASHSHOP_WINDOW_SYNC.getValue());
        assertEquals(CashShopWindowPackets.RESP_CATALOG, in.readByte());
        assertEquals(1, in.readByte(), "the first chunk clears the category");
        assertEquals(2, in.readByte());
        assertEquals(0, in.readByte());
        assertEquals(1, in.readShort());
        assertEquals(1002000, in.readInt());
        assertEquals(3700, in.readInt());
        assertEquals(1, in.readShort());
        assertEquals(2, in.readByte());
        assertEquals(0, in.readByte());
        assertEquals("Blue Hat", in.readString());
        assertEquals(0, in.available());
    }

    @Test
    void aBigCategoryComesInChunksAndAnEmptyOneStillClears() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < CashShopWindowPackets.CHUNK + 1; i++) {
            rows.add(new Row(i, 1002000 + i, 100, 1, 2, 0, "Hat"));
        }
        assertEquals(2, CashShopWindowPackets.category(2, 0, rows).size());

        List<Packet> empty = CashShopWindowPackets.category(2, 1, List.of());
        assertEquals(1, empty.size());
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(empty.get(0).getBytes()));
        in.readShort();
        in.readByte();
        assertEquals(1, in.readByte());
        in.readByte();
        in.readByte();
        assertEquals(0, in.readShort());
    }
}
