package server.knowledge;

import org.junit.jupiter.api.Test;
import server.knowledge.ContentAnswers.Rates;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overheard answers (docs/SERVER_KNOWLEDGE_PLAN.md 7.5): a true answer to someone else's open question teaches the subject
 * and what it named; a false, mixed, negated, questioning, own, late or definition-less line teaches nothing.
 */
class HearsayTest {
    private static final Rates RATES = new Rates(10, 3, 3, 3, 4f, 4);
    private static final int CHANNEL = 1;
    private static final int HENESYS = 100_000_000;
    private static final int KERNING = 103_000_000;
    private static final int BLUE_SNAIL = 100_101;
    private static final int BLUE_SNAIL_SHELL = 4_000_000;
    private static final long NOW = 5_000_000;
    private static final long[] NOTHING = new long[0];
    private final WorldKnowledge k = SeedOverlay.knowledge();
    private final Hearsay hearsay = new Hearsay(new ContentAnswers(SeedOverlay::knowledge, world -> RATES, item -> null, 10, 70, false),
            SeedOverlay::knowledge);

    private long[] after(String question, String answer, int speaker, long delayMs) {
        hearsay.asked(CHANNEL, HENESYS, 1, 20, question, NOW);
        return hearsay.answered(CHANNEL, HENESYS, speaker, answer, NOW + delayMs);
    }

    private static boolean has(long[] keys, EntityKind kind, int id) {
        long key = Entity.simple(kind, id, "", "").key();
        return Arrays.stream(keys).anyMatch(k -> k == key);
    }

    /** A town where blue snails spawn nearby: part of the true answer. */
    private String snailTown() {
        for (int map : k.spawnMaps(BLUE_SNAIL)) {
            int town = k.townOf(map);
            if (town != 0 && "victoria".equals(k.map(town).map(MapFact::region).orElse(null))) {
                return k.names().say(EntityKind.MAP, town);
            }
        }
        throw new AssertionError("no Victoria town near blue snails");
    }

    @Test
    void aTrueAnswerTeachesTheSubjectAndWhatItNames() {
        long[] learned = after("who drops blue snail shells", "blue snails near " + snailTown(), 2, 3_000);
        assertTrue(has(learned, EntityKind.ITEM, BLUE_SNAIL_SHELL), Arrays.toString(learned));
        assertTrue(has(learned, EntityKind.MOB, BLUE_SNAIL), Arrays.toString(learned));
        assertTrue(learned.length >= 3, "and the town: " + Arrays.toString(learned));
    }

    @Test
    void wrongMixedNegatedAndOtherLinesTeachNothing() {
        assertArrayEquals(NOTHING, after("who drops blue snail shells", "orange mushrooms", 2, 1_000), "false");
        assertArrayEquals(NOTHING, after("who drops blue snail shells", "blue snails or orange mushrooms", 2, 1_000), "mixed");
        assertArrayEquals(NOTHING, after("who drops blue snail shells", "not blue snails", 2, 1_000), "negated");
        assertArrayEquals(NOTHING, after("who drops blue snail shells", "who drops blue snails?", 2, 1_000), "a question");
        assertArrayEquals(NOTHING, after("who drops blue snail shells", "blue snails", 1, 1_000), "the asker's own line");
        assertArrayEquals(NOTHING, after("who drops blue snail shells", "blue snails", 2, Hearsay.OPEN_MS + 1_000), "61 s later");
        assertArrayEquals(NOTHING, after("who drops blue snail shells", "im in " + snailTown() + " lol", 2, 1_000),
                "a place says nothing about who drops it");
        assertArrayEquals(NOTHING, after("who drops blue snail shells", "lol idk", 2, 1_000), "no name at all");
    }

    @Test
    void whatAMobDropsAndTheWayThereTeachTheirTruth() {
        long[] drops = after("what do blue snails drop", "blue snail shells mostly", 2, 2_000);
        assertTrue(has(drops, EntityKind.MOB, BLUE_SNAIL) && has(drops, EntityKind.ITEM, BLUE_SNAIL_SHELL), Arrays.toString(drops));

        List<Integer> path = k.path(KERNING, 101_000_000);
        int via = 0;
        for (int map : path) {
            if (map != KERNING && map != 101_000_000 && k.isTown(map)) {
                via = map;
            }
        }
        assertTrue(via != 0, "fixture: the walk from Kerning to Ellinia passes a town " + path);
        hearsay.asked(CHANNEL, KERNING, 1, 30, "how do i get to ellinia", NOW);
        long[] route = hearsay.answered(CHANNEL, KERNING, 2, "go thru " + k.names().say(EntityKind.MAP, via), NOW + 2_000);
        assertTrue(has(route, EntityKind.MAP, via) && has(route, EntityKind.MAP, 101_000_000), Arrays.toString(route));
        assertEquals(0, hearsay.answered(CHANNEL, KERNING, 2, "go thru orbis", NOW + 2_000).length, "not on the way");
    }
}
