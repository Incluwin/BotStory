package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Rates;
import server.knowledge.KnownWorld.Past;
import server.knowledge.KnownWorld.Recall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The synthesized past (docs/SERVER_KNOWLEDGE_PLAN.md 7.2): deterministic, stable as a bot levels, bounded, cheap. */
class PastSynthesisTest {
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);
    private static final IntFunction<String> CITIZEN = band -> band < 10 ? "maple-island" : "victoria";

    private static PersonalKnowledge.Spec spec(long seed, int level, IntFunction<String> regionAt) {
        return new PersonalKnowledge.Spec(Past.SYNTHESIZED, seed, level, regionAt, IntList.of(100000000), List.of(), false, false);
    }

    private static Recall past(long seed, int level, IntFunction<String> regionAt) {
        return PastSynthesis.synthesize(SeedOverlay.knowledge(), spec(seed, level, regionAt), RATES);
    }

    @Test
    void sameSeedSamePast() {
        for (long seed = 1; seed <= 200; seed++) {
            Recall a = past(seed, 35, CITIZEN);
            Recall b = past(seed, 35, CITIZEN);
            assertArrayEquals(a.maps(), b.maps());
            assertArrayEquals(a.mobs(), b.mobs());
            assertArrayEquals(a.mobKills(), b.mobKills());
            assertArrayEquals(a.items(), b.items());
            assertArrayEquals(a.spots(), b.spots());
        }
        assertTrue(!Arrays.equals(past(1, 35, CITIZEN).spots(), past(2, 35, CITIZEN).spots())
                || !Arrays.equals(past(3, 35, CITIZEN).spots(), past(4, 35, CITIZEN).spots()), "seeds differ");
    }

    @Test
    void aBotKeepsItsSpotsAsItLevels() {
        int checked = 0;
        for (long seed = 1; seed <= 200; seed++) {
            Recall at45 = past(seed, 45, CITIZEN);
            Recall at50 = past(seed, 50, CITIZEN);
            if (at50.maps().length >= PastSynthesis.MAPS) {
                continue; // a full table drops its oldest bands
            }
            checked++;
            for (int map : at45.maps()) {
                assertTrue(Arrays.binarySearch(at50.maps(), map) >= 0, "seed " + seed + ": level 50 forgot " + map);
            }
            for (int spot : at45.spots()) {
                assertTrue(Arrays.stream(at50.spots()).anyMatch(s -> s == spot), "seed " + seed + ": spot " + spot);
            }
        }
        assertTrue(checked >= 100, checked + " seeds under the cap");
    }

    @Test
    void capsHoldAtLevel200() {
        for (long seed = 1; seed <= 20; seed++) {
            Recall recall = past(seed, 200, band -> band < 10 ? "maple-island" : null);
            assertTrue(recall.maps().length <= PastSynthesis.MAPS);
            assertTrue(recall.mobs().length <= PastSynthesis.MOBS);
            assertEquals(recall.mobs().length, recall.mobKills().length);
            assertTrue(recall.items().length <= PastSynthesis.ITEMS);
            assertTrue(recall.spots().length <= PastSynthesis.SPOTS);
            assertTrue(recall.spots().length > 0 && recall.items().length > 0);
        }
    }

    @Test
    void aVictoriaPastTrainsInVictoria() {
        WorldKnowledge k = SeedOverlay.knowledge();
        for (long seed = 1; seed <= 50; seed++) {
            for (int spot : past(seed, 40, CITIZEN).spots()) {
                String region = k.map(spot).map(MapFact::region).orElse(null);
                assertTrue("victoria".equals(region) || "maple-island".equals(region), seed + ": " + spot + " in " + region);
            }
        }
    }

    @Test
    void aRegionWithoutTrainingMapsFallsBackToVictoriaThenAnywhere() {
        WorldKnowledge k = SeedOverlay.knowledge();
        Recall low = past(9, 30, band -> "atlantis");
        assertTrue(low.spots().length > 0);
        for (int spot : low.spots()) {
            assertEquals("victoria", k.map(spot).map(MapFact::region).orElse(null), "spot " + spot);
        }
        assertTrue(k.trainingMaps(150, "victoria", 6).isEmpty(), "fixture: Victoria has no level-150 fields");
        Recall high = past(9, 150, band -> "atlantis");
        assertTrue(high.spots().length > 0, "anywhere, when Victoria has nothing either");
        int band150 = high.spots()[0];
        assertTrue(!"victoria".equals(k.map(band150).map(MapFact::region).orElse(null)));
    }

    @Test
    void spotsFitTheirBand() {
        WorldKnowledge k = SeedOverlay.knowledge();
        for (long seed = 1; seed <= 30; seed++) {
            for (int level : new int[]{12, 37, 64, 99}) {
                List<Integer> bands = new ArrayList<>();
                for (int band = level / 5 * 5; band >= 5; band -= 5) {
                    bands.add(band);
                }
                for (int spot : past(seed, level, CITIZEN).spots()) {
                    int mobLevel = k.map(spot).map(MapFact::mobLevel).orElse(0);
                    assertTrue(bands.stream().anyMatch(band -> mobLevel >= band - 8 && mobLevel <= band + 5),
                            "seed " + seed + " lv " + level + ": spot " + spot + " (mobs lv " + mobLevel + ") fits no band");
                }
            }
        }
    }

    @Test
    void synthesisIsCheap() {
        for (int warm = 0; warm < 3; warm++) {
            for (int level = 1; level <= 120; level++) {
                past(level, level, CITIZEN);
            }
        }
        List<Long> micros = new ArrayList<>();
        for (int level = 1; level <= 120; level++) {
            long best = Long.MAX_VALUE;
            for (int attempt = 0; attempt < 3; attempt++) { // the best of three: a busy machine slows one, not all
                long started = System.nanoTime();
                past(1000 + level, level, CITIZEN);
                best = Math.min(best, (System.nanoTime() - started) / 1_000);
            }
            micros.add(best);
        }
        Collections.sort(micros);
        long p95 = micros.get(micros.size() * 95 / 100);
        System.out.printf("past synthesis: p50 %d us, p95 %d us, max %d us (levels 1..120)%n", micros.get(micros.size() / 2), p95,
                micros.get(micros.size() - 1));
        assertTrue(p95 <= 10_000, "p95 " + p95 + " us");
    }
}
