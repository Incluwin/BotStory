package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.KnownWorld.Past;
import server.knowledge.KnownWorld.Recall;

import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One bot's known world (docs/SERVER_KNOWLEDGE_PLAN.md 7.1): caps, LRU + count eviction, sources, saturation, the stored row. */
class KnownWorldTest {
    private static final IntFunction<String> VICTORIA = map -> "victoria";

    @Test
    void capsHoldAfterManyRandomTouches() {
        KnownWorld w = new KnownWorld(1);
        SplittableRandom rng = new SplittableRandom(7);
        for (int i = 0; i < 100_000; i++) {
            int id = 1 + rng.nextInt(500);
            EntityKind kind = List.of(EntityKind.MAP, EntityKind.MOB, EntityKind.ITEM).get(rng.nextInt(3));
            if (rng.nextBoolean()) {
                w.lived(kind, id, 1, "victoria");
            } else {
                w.heard(kind, id, "victoria");
            }
        }
        String row = w.encode();
        assertEquals(KnownWorld.MAPS, count(row, 'M'));
        assertEquals(KnownWorld.MOBS, count(row, 'O'));
        assertEquals(KnownWorld.ITEMS, count(row, 'I'));
        assertTrue(row.length() <= KnownWorld.ENCODED_MAX, row.length() + " chars");
    }

    @Test
    void aFullTableEvictsHeardBeforeLivedAndTheLeastCountedOfTheOldest() {
        KnownWorld w = new KnownWorld(1);
        for (int id = 1; id <= KnownWorld.MOBS; id++) {
            w.lived(EntityKind.MOB, id, 10 + id, null); // oldest first: 1..48
        }
        w.heard(EntityKind.MOB, 1000, null);     // evicts the least counted of the 8 oldest: mob 1 (count 11)
        assertFalse(w.knowsMob(1));
        assertTrue(w.knowsMob(1000));
        assertTrue(w.knowsMob(2));
        // mob 1000 (heard, count 1) is now the newest; refresh it so a heard one sits among the oldest
        KnownWorld x = new KnownWorld(2);
        x.heard(EntityKind.MOB, 500, null);    // oldest, heard
        for (int id = 1; id < KnownWorld.MOBS; id++) {
            x.lived(EntityKind.MOB, id, 1, null);  // lived, count 1 (lower than nothing: equal)
        }
        x.lived(EntityKind.MOB, 9999, 1, null);
        assertFalse(x.knowsMob(500), "the heard entry goes before an equally counted lived one");
        // the 8 oldest only: a low count outside the window survives
        KnownWorld y = new KnownWorld(3);
        for (int id = 1; id <= KnownWorld.MOBS; id++) {
            y.lived(EntityKind.MOB, id, id <= 8 ? 100 : 1, null);
        }
        y.lived(EntityKind.MOB, 7777, 1, null);
        assertTrue(y.knowsMob(9) && y.knowsMob(48), "low counts outside the 8 oldest stay");
        assertEquals(7, java.util.stream.IntStream.rangeClosed(1, 8).filter(y::knowsMob).count());
    }

    @Test
    void livedUpgradesHeardAndHeardNeverDowngrades() {
        KnownWorld w = new KnownWorld(1);
        w.heard(EntityKind.MOB, 100101, null);
        assertTrue(w.heardOnly(EntityKind.MOB, 100101));
        assertEquals(0, w.kills(100101), "hearing of a mob is no kill");
        w.lived(EntityKind.MOB, 100101, 3, null);
        assertFalse(w.heardOnly(EntityKind.MOB, 100101));
        assertEquals(3, w.kills(100101), "the count restarts at the lived ones");
        w.heard(EntityKind.MOB, 100101, null);
        assertFalse(w.heardOnly(EntityKind.MOB, 100101));
        assertTrue(w.firstHand(EntityKind.MOB, 100101));
    }

    @Test
    void countsSaturate() {
        KnownWorld w = new KnownWorld(1);
        w.lived(EntityKind.MOB, 5, 60_000, null);
        w.lived(EntityKind.MOB, 5, 60_000, null);
        assertEquals(KnownWorld.COUNT_MAX, w.kills(5));
        w.configure(Past.SYNTHESIZED, new Recall(new int[0], new int[]{5}, new int[]{KnownWorld.COUNT_MAX}, new int[0], new int[0]),
                false, false, VICTORIA);
        assertEquals(KnownWorld.COUNT_MAX, w.kills(5), "lived plus recalled saturates too");
    }

    @Test
    void encodeThenMergeRoundTripsIdsCountsSourcesAndOrder() {
        KnownWorld w = new KnownWorld(1);
        w.lived(EntityKind.MAP, 104040000, 31, "victoria");
        w.lived(EntityKind.MOB, 1210100, 412, null);
        w.heard(EntityKind.ITEM, 4000021, null);
        String row = w.encode();
        assertEquals("M104040000:31L O1210100:412L I4000021:1H", row);
        KnownWorld back = new KnownWorld(1);
        back.merge(row, VICTORIA);
        assertEquals(row, back.encode());
        assertTrue(back.knowsRegion("victoria"));
        assertTrue(back.heardOnly(EntityKind.ITEM, 4000021));
        assertEquals(412, back.kills(1210100));
    }

    @Test
    void mergeIntoAWorldWithNewerEventsKeepsThemNewerAndWithinCaps() {
        KnownWorld stored = new KnownWorld(1);
        for (int id = 1; id <= KnownWorld.MOBS; id++) {
            stored.lived(EntityKind.MOB, id, 5, null);
        }
        KnownWorld live = new KnownWorld(1);
        live.lived(EntityKind.MOB, 1, 2, null);     // also stored: counts add, stamp stays the live one
        live.lived(EntityKind.MOB, 9000, 1, null);  // new since open
        live.merge(stored.encode(), VICTORIA);
        String row = live.encode();
        assertEquals(KnownWorld.MOBS, count(row, 'O'), "the table stays at its cap");
        assertTrue(row.endsWith("O1:7L O9000:1L"), "live entries stay the newest: " + row);
        assertTrue(live.knowsMob(9000));
        assertFalse(live.knowsMob(2), "the oldest stored entry is the one that did not fit");
    }

    @Test
    void malformedTokensAndMapsWithoutARegionAreIgnored() {
        KnownWorld w = new KnownWorld(1);
        w.merge("X12:3L M5:x O-4:2L I77:3Q O0:1L I99999999999:1L M100:2L O42:9H I7:1L", map -> map == 100 ? null : "victoria");
        assertEquals("O42:9H I7:1L", w.encode());
    }

    @Test
    void regionsStayKnownAfterTheirMapsAreEvicted() {
        KnownWorld w = new KnownWorld(1);
        w.lived(EntityKind.MAP, 200000000, 1, "orbis");
        for (int id = 1; id <= KnownWorld.MAPS; id++) {
            w.lived(EntityKind.MAP, 100000000 + id, 5, "victoria");
        }
        assertFalse(w.knowsMap(200000000));
        assertTrue(w.knowsRegion("orbis"));
        assertEquals(java.util.Set.of("orbis", "victoria"), w.regions());
    }

    @Test
    void spotsAreRecalledSpotsThenLivedTrainingMaps() {
        KnownWorld w = new KnownWorld(1);
        w.configure(Past.SYNTHESIZED, new Recall(new int[]{104040000}, new int[0], new int[0], new int[0], new int[]{104040000}),
                false, false, VICTORIA);
        w.lived(EntityKind.MAP, 101010100, 29, "victoria");
        w.lived(EntityKind.MAP, 102010000, 40, "victoria");
        w.lived(EntityKind.MAP, 103000000, 35, "victoria");
        w.lived(EntityKind.MAP, 103000000, 10, "victoria");
        assertEquals(List.of(104040000, 103000000, 102010000), w.spots());
    }

    private static int count(String row, char kind) {
        int n = 0;
        for (String token : row.split(" ")) {
            n += token.charAt(0) == kind ? 1 : 0;
        }
        return n;
    }
}
