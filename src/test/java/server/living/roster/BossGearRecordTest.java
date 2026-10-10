package server.living.roster;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The record of what a companion's !bossgear kit displaced, as the living event log keeps it (BossGearRecord): slot and
 * item id pairs from the cap down, read back the same; a malformed entry is left out, and the kit coming off ends it.
 */
class BossGearRecordTest {
    private static final short CAP = -1;
    private static final short PANTS = -6;
    private static final short WEAPON = -11;

    @Test
    void theRecordReadsBackAsItWasWritten() {
        Map<Short, Integer> tamsin = Map.of(WEAPON, 1302020, CAP, 1002027, PANTS, 1060002);

        String detail = BossGearRecord.detail(tamsin);

        assertEquals("displaced: -1:1002027 -6:1060002 -11:1302020", detail);
        assertEquals(tamsin, BossGearRecord.parse(detail));
        assertEquals("displaced: none", BossGearRecord.detail(Map.of()));
        assertEquals(Map.of(), BossGearRecord.parse("displaced: none"));
    }

    @Test
    void aMalformedEntryIsLeftOutAndTheRestHolds() {
        assertEquals(Map.of(CAP, 1002027), BossGearRecord.parse("displaced: -1:1002027 x:1 -6: 5:1060002 -7:-2"));
        assertEquals(Map.of(), BossGearRecord.parse("released: 6 pieces"));
        assertEquals(Map.of(), BossGearRecord.parse(null));
    }

    @Test
    void theKitComingOffEndsTheRecordAndANewKitStartsOne() {
        FakeLivingStore store = new FakeLivingStore();
        BossGearRecord.write(store.store, 870, "tamsin", Map.of(CAP, 1002027));
        assertEquals(Map.of(CAP, 1002027), BossGearRecord.read(store.store, 870, "tamsin"));
        assertEquals(Map.of(), BossGearRecord.read(store.store, 871, "tamsin"), "another owner's record");

        store.store.event(870, "tamsin", BossGearRecord.REMOVED, "released: 6 pieces");
        assertEquals(Map.of(), BossGearRecord.read(store.store, 870, "tamsin"));

        BossGearRecord.write(store.store, 870, "tamsin", Map.of(PANTS, 1060002));
        assertEquals(Map.of(PANTS, 1060002), BossGearRecord.read(store.store, 870, "tamsin"));
    }
}
