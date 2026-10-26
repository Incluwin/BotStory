package server;

import client.inventory.Item;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopBuybackTest {
    private static final int CHR = 1_234_567;
    private static final int SLIME_STAMP = 4002003;
    private static final int BRONZE_ORE = 4010000;
    private static final int SUBI = 2070000; // a rechargeable

    @AfterEach
    void forget() {
        ShopBuyback.clear(CHR);
    }

    @Test
    void theNewestSaleComesFirstAndOnlyThirtyAreKept() {
        for (int i = 0; i < 35; i++) {
            ShopBuyback.record(CHR, new Item(BRONZE_ORE, (short) 0, (short) (i + 1)), i * 10);
        }
        List<ShopBuyback.Entry> rows = ShopBuyback.entries(CHR);
        assertEquals(ShopBuyback.MAX_ENTRIES, rows.size());
        assertEquals(35, rows.get(0).item().getQuantity(), "newest first");
        assertEquals(340, rows.get(0).price());
        assertEquals(6, rows.get(29).item().getQuantity(), "the five oldest were dropped");
    }

    @Test
    void eachRowCostsWhatItsSalePaidSoAStackWorthMillionsCannotTurnAProfit() {
        // The release charged a flat 5,000,000: selling 100 one-million-meso stamps and buying the stack back for 5M
        // made 95M a loop. Here the row costs the 100M the sale paid.
        ShopBuyback.record(CHR, new Item(SLIME_STAMP, (short) 0, (short) 100), 100_000_000);
        ShopBuyback.Entry row = ShopBuyback.entries(CHR).get(0);
        assertEquals(100_000_000, row.price());
        assertEquals(100, row.item().getQuantity());
    }

    @Test
    void theListPacketIsAnOrdinaryShopPacketPricedPerRow() {
        ShopBuyback.record(CHR, new Item(BRONZE_ORE, (short) 0, (short) 7), 210);
        ShopBuyback.record(CHR, new Item(SUBI, (short) 0, (short) 500), 1200);
        Packet packet = ShopBuyback.listPacket(null, 1012002, ShopBuyback.entries(CHR));
        InPacket in = decode(packet);
        in.readShort(); // OPEN_NPC_SHOP
        assertEquals(1012002, in.readInt(), "npc id first: no token id before it");
        assertEquals(2, in.readShort());

        assertEquals(SUBI, in.readInt());
        assertEquals(1200, in.readInt());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readShort());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readShort(), "no unit price: the row price is the stack's");
        assertEquals(500, in.readShort());

        assertEquals(BRONZE_ORE, in.readInt());
        assertEquals(210, in.readInt());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readInt());
        assertEquals(7, in.readShort(), "the whole stack per purchase");
        assertEquals(1, in.readShort(), "once");
        assertEquals(0, in.available());
    }

    @Test
    void theModePacketSaysWhichListShowsAndWhetherAnythingWasSold() {
        InPacket in = decode(ShopBuyback.modePacket(true, false));
        assertEquals(0x17F, in.readShort());
        assertEquals(1, in.readByte());
        assertEquals(0, in.readByte());
    }

    private static InPacket decode(Packet packet) {
        return new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
    }

    @Test
    void clearingTheSessionForgetsTheList() {
        ShopBuyback.record(CHR, new Item(BRONZE_ORE, (short) 0, (short) 1), 30);
        ShopBuyback.clear(CHR);
        assertTrue(ShopBuyback.entries(CHR).isEmpty());
    }
}
