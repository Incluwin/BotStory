package server.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drop browser's data: name search order over the real knowledge file with the seed drops, the chance a player
 * rolls (as MapleMap rolls it), the odds text, the table sections, and markup that a WZ name cannot break.
 */
class DropSearchTest {
    private static final int SNAIL = 100100;
    private static final int BLUE_SNAIL = 100101;
    private static final int SNAIL_SHELL = 4000019;
    private static final int BLUE_SNAIL_SHELL = 4000000;

    private final WorldKnowledge knowledge = SeedOverlay.knowledge();

    @Test
    void anExactItemNameComesBeforeLongerNamesThatContainIt() {
        DropSearch.Hits hits = DropSearch.items(knowledge, "Snail Shell");

        assertEquals(SNAIL_SHELL, hits.ids().get(0));
        assertTrue(hits.ids().contains(BLUE_SNAIL_SHELL));
        assertEquals(hits.ids().size(), hits.total());
    }

    @Test
    void itemSearchIgnoresCaseAndPunctuationAndListsOnlyDroppedItems() {
        assertEquals(DropSearch.items(knowledge, "Snail Shell").ids(), DropSearch.items(knowledge, "  snail-SHELL ").ids());
        for (int id : DropSearch.items(knowledge, "shell").ids()) {
            assertFalse(knowledge.dropSources(id).isEmpty(), id + " has no drop source");
        }
    }

    @Test
    void anExactMonsterNameComesFirstAndPrefixMatchesFollow() {
        List<Integer> ids = DropSearch.mobs(knowledge, "snail").ids();

        assertEquals(SNAIL, ids.get(0));
        assertTrue(ids.indexOf(BLUE_SNAIL) > 0);
    }

    @Test
    void anEmptyOrUnknownQueryFindsNothing() {
        assertEquals(0, DropSearch.items(knowledge, "  ").total());
        assertEquals(0, DropSearch.mobs(knowledge, "zzzz no such monster").total());
    }

    @Test
    void aBroadSearchListsTheCapAndCountsEveryMatch() {
        DropSearch.Hits hits = DropSearch.items(knowledge, "e");

        assertEquals(DropSearch.MAX_RESULTS, hits.ids().size());
        assertTrue(hits.total() > DropSearch.MAX_RESULTS);
    }

    @Test
    void theChanceUsesTheDropRateTheBossRateForBossesAndTheDropCard() {
        DropSource field = source(10_000, false, false);
        DropSource boss = source(10_000, true, false);

        assertEquals(0.02, DropSearch.chance(field, 2, 5, 1f), 1e-12);
        assertEquals(0.05, DropSearch.chance(boss, 2, 5, 1f), 1e-12);
        assertEquals(0.04, DropSearch.chance(field, 2, 5, 2f), 1e-12);
        assertEquals(1d, DropSearch.chance(source(900_000, false, false), 2, 5, 1f));
    }

    @Test
    void aGlobalDropIsRolledFlatAtAnyRate() {
        assertEquals(0.01, DropSearch.chance(source(10_000, false, true), 5, 9, 3f), 1e-12);
    }

    @Test
    void oddsShowOneInXWithMorePrecisionForRareDrops() {
        assertEquals("1/1 (100%)", DropSearch.odds(1));
        assertEquals("1/4 (25.0%)", DropSearch.odds(0.25));
        assertEquals("1/40 (2.50%)", DropSearch.odds(0.025));
        assertEquals("1/2,500 (0.040%)", DropSearch.odds(0.0004));
        assertEquals("1/50,000 (0.0020%)", DropSearch.odds(0.00002));
        assertEquals("never", DropSearch.odds(0));
    }

    @Test
    void dropsFallIntoEquipUseEtcQuestAndCardSections() {
        assertEquals(DropSearch.Section.EQUIP, DropSearch.section(item(1302000, 0)));
        assertEquals(DropSearch.Section.USE, DropSearch.section(item(2000000, 0)));
        assertEquals(DropSearch.Section.ETC, DropSearch.section(item(SNAIL_SHELL, 0)));
        assertEquals(DropSearch.Section.QUEST, DropSearch.section(item(4031013, 2001)));
        assertEquals(DropSearch.Section.CARD, DropSearch.section(item(2380000, 0)));
    }

    @Test
    void aWzNameNeverReachesTheWindowWithAHash() {
        assertEquals("Box of Parts 1", DropSearch.plain("Box of Parts #1"));
        assertEquals("Box of Parts 1", DropSearch.itemLabel(4031095, "Box of Parts #1", false));
        assertEquals("#i4031095:# #b#z4031095##k", DropSearch.itemLabel(4031095, "Box of Parts #1", true));
    }

    private static DropSource source(int chance, boolean boss, boolean global) {
        return new DropSource(SNAIL_SHELL, global ? 0 : SNAIL, chance, 1, 1, 0, boss, IntList.of(), false, 0, global, -1);
    }

    private static DropSource item(int itemId, int questId) {
        return new DropSource(itemId, SNAIL, 1000, 1, 1, questId, false, IntList.of(), false, 0, false, -1);
    }
}
