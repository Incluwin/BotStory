package server.living.bond;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player marrying their own companion (docs/BOT_LORE_COUPLES_PLAN.md, decision 4): the companion says yes to its own
 * owner from friendship Lv 7 (close friend), and no to anyone else; it keeps only the wedding garment cut for its body,
 * the four the altar scripts accept for each gender (isSuitedForWedding in 9201002.js / 9201011.js).
 */
class CompanionWeddingsTest {

    @Test
    void itSaysYesToItsOwnOwnerFromCloseFriendship() {
        assertTrue(CompanionWeddings.accepts(true, Friendship.CLOSE_FRIEND));
        assertTrue(CompanionWeddings.accepts(true, Friendship.MAX_LEVEL));
        assertFalse(CompanionWeddings.accepts(true, Friendship.CLOSE_FRIEND - 1));
        assertFalse(CompanionWeddings.accepts(false, Friendship.MAX_LEVEL), "never to someone else's ring");
    }

    @Test
    void theGarmentMustBeCutForItsBody() {
        for (int i = 0; i < 4; i++) {
            assertTrue(CompanionWeddings.garmentFor(1050131 + i, 0));
            assertTrue(CompanionWeddings.garmentFor(1051150 + i, 1));
            assertFalse(CompanionWeddings.garmentFor(1050131 + i, 1));
            assertFalse(CompanionWeddings.garmentFor(1051150 + i, 0));
        }
        assertFalse(CompanionWeddings.garmentFor(1050135, 0));
        assertFalse(CompanionWeddings.garmentFor(1051149, 1));
    }

    @Test
    void theWeddingMapsAreTheChapelsAndTheCathedrals() {
        assertTrue(CompanionWeddings.WEDDING_MAP_MIN <= 680000110 && 680000110 <= CompanionWeddings.WEDDING_MAP_MAX,
                "the chapel altar");
        assertTrue(CompanionWeddings.WEDDING_MAP_MIN <= 680000210 && 680000210 <= CompanionWeddings.WEDDING_MAP_MAX,
                "the cathedral altar");
        assertFalse(680000000 >= CompanionWeddings.WEDDING_MAP_MIN, "Amoria itself is not a wedding map");
    }
}
