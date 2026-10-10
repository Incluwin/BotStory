package server.living.roster;

import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.living.roster.BossGearRecord.Displaced;
import server.living.store.LivingStore;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * The record of what a companion's !bossgear kit displaced, as the living event log keeps it (BossGearRecord): slot,
 * item id, bag slot and print from the cap down, read back the same; a row from before the bag slot and print were kept
 * reads by the id alone; a malformed entry is left out, and the kit coming off ends it.
 */
class BossGearRecordTest {
    private static final short CAP = -1;
    private static final short PANTS = -6;
    private static final short WEAPON = -11;

    @Test
    void theRecordReadsBackAsItWasWritten() {
        Map<Short, Displaced> tamsin = Map.of(WEAPON, Displaced.of(1302020), CAP, Displaced.of(1002027), PANTS,
                Displaced.of(1060002));

        String detail = BossGearRecord.detail(tamsin);

        assertEquals("displaced: -1:1002027 -6:1060002 -11:1302020", detail);
        assertEquals(tamsin, BossGearRecord.parse(detail));
        assertEquals("displaced: none", BossGearRecord.detail(Map.of()));
        assertEquals(Map.of(), BossGearRecord.parse("displaced: none"));
    }

    @Test
    void eachPieceReadsBackWithTheBagSlotItWentToAndItsPrint() {
        Item helm = Equip.restored(1002027, (short) 12);
        Map<Short, Displaced> tamsin = Map.of(CAP, Displaced.of(helm), PANTS, new Displaced(1060002, (short) 13,
                0xfedcba98));

        String detail = BossGearRecord.detail(tamsin);

        assertEquals("displaced: -1:1002027@12#" + Integer.toHexString(BossGearRecord.print(helm))
                + " -6:1060002@13#fedcba98", detail);
        assertEquals(tamsin, BossGearRecord.parse(detail));
        assertTrue(BossGearRecord.parse(detail).get(CAP).exact());
    }

    @Test
    void aRowFromBeforeTheBagSlotAndPrintWereKeptReadsByTheIdAlone() {
        Displaced helm = BossGearRecord.parse("displaced: -1:1002027").get(CAP);

        assertEquals(Displaced.of(1002027), helm);
        assertFalse(helm.exact());
    }

    @Test
    void aMalformedEntryIsLeftOutAndTheRestHolds() {
        assertEquals(Map.of(CAP, Displaced.of(1002027)),
                BossGearRecord.parse("displaced: -1:1002027 x:1 -6: 5:1060002 -7:-2"));
        assertEquals(Map.of(CAP, Displaced.of(1002027), PANTS, Displaced.of(1060002), WEAPON, Displaced.of(1302020)),
                BossGearRecord.parse("displaced: -1:1002027@x#1f -6:1060002@13 -11:1302020@0#1f"),
                "a malformed bag slot or print: known by the id alone");
        assertEquals(Map.of(), BossGearRecord.parse("released: 6 pieces"));
        assertEquals(Map.of(), BossGearRecord.parse(null));
    }

    @Test
    void thePrintTellsCopiesOfAnIdApartByTheirStatsAndScrollsAlone() {
        Equip looted = Equip.restored(1002027, (short) 3);
        Equip alike = Equip.restored(1002027, (short) 12);
        Equip scrolled = Equip.restored(1002027, (short) 12);
        scrolled.setWdef((short) 33);
        scrolled.setLevel((byte) 3);

        assertEquals(BossGearRecord.print(looted), BossGearRecord.print(alike), "the bag slot is no part of it");
        assertNotEquals(BossGearRecord.print(looted), BossGearRecord.print(scrolled));
        assertEquals(0, BossGearRecord.print(new Item(2000000, (short) 1, (short) 5)), "no equip");
    }

    @Test
    void theKitComingOffEndsTheRecordAndANewKitStartsOne() {
        FakeLivingStore store = new FakeLivingStore();
        BossGearRecord.write(store.store, 870, "tamsin", Map.of(CAP, Displaced.of(1002027)));
        assertEquals(Map.of(CAP, Displaced.of(1002027)), BossGearRecord.read(store.store, 870, "tamsin"));
        assertEquals(Map.of(), BossGearRecord.read(store.store, 871, "tamsin"), "another owner's record");

        store.store.event(870, "tamsin", BossGearRecord.REMOVED, "released: 6 pieces");
        assertEquals(Map.of(), BossGearRecord.read(store.store, 870, "tamsin"));

        BossGearRecord.write(store.store, 870, "tamsin", Map.of(PANTS, Displaced.of(1060002)));
        assertEquals(Map.of(PANTS, Displaced.of(1060002)), BossGearRecord.read(store.store, 870, "tamsin"));
    }

    @Test
    void aFailedReadIsNoRecordForTheRefitButNeverReadsAsNone() {
        FakeLivingStore store = new FakeLivingStore();
        BossGearRecord.write(store.store, 870, "tamsin", Map.of(CAP, Displaced.of(1002027)));
        doReturn(null).when(store.store).latestEvent(anyInt(), anyString(), any()); // the database read fails

        assertEquals(Map.of(), BossGearRecord.read(store.store, 870, "tamsin"), "the refit puts the best pieces on");
        assertThrows(LivingStore.ReadFailed.class, () -> BossGearRecord.current(store.store, 870, "tamsin"),
                "a kit going onto a kit must not take it for no record");
    }
}
