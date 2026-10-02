package server.knowledge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.knowledge.FarmSites.Plan;
import server.knowledge.FarmSites.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a companion farms an item and what the bench credits (docs/SERVER_KNOWLEDGE_PLAN.md 8.3), over the real file and
 * the seed rows: wild monsters it can fight, outside a quest it does not hold, at open fields nearest its anchor; every
 * other item gets its reason. The spoken places read the published snapshot, so the seed one is published here.
 */
class FarmSitesTest {
    private static final int HENESYS = 100000000;
    private static final int ELLINIA = 101000000;
    private static final int SNAIL = 100100;
    private static final int SNAIL_SHELL = 4000019;
    private static final int FAIRY_WING = 4003003;
    private static final int ZAKUM_HELMET = 1002357;
    private static final int PERFECT_PITCH = 4310000;
    private static AutoCloseable seed;

    private final WorldKnowledge k = SeedOverlay.knowledge();

    @BeforeAll
    static void publish() {
        seed = KnowledgeTestKit.publishSeed();
    }

    @AfterAll
    static void unpublish() throws Exception {
        seed.close();
    }

    @Test
    void snailShellsForALevel10FarmASnailFieldNearHenesys() {
        Plan plan = FarmSites.plan(k, SNAIL_SHELL, 10, HENESYS, false);

        assertEquals(Verdict.FARM, plan.verdict(), plan.toString());
        FarmSites.Site first = plan.sites().get(0);
        assertEquals(SNAIL, first.source().mobId(), "Mano is a script-spawned area boss");
        assertEquals(1, first.mapId() / 100_000_000, "a Victoria field, never Maple Island: " + first.mapId());
        // Victoria's snails live only around Lith Harbor: Right Around Lith Harbor / Thicket Around the Beach, 7 maps away
        assertTrue(first.mapId() == 104000100 || first.mapId() == 104000200, "site " + first.mapId());
        assertTrue(first.distance() <= 8, "distance " + first.distance());
        assertTrue(k.map(first.mapId()).orElseThrow().mobs().contains(SNAIL));
        assertNotNull(first.place());
        assertFalse(first.place().isEmpty());
        assertEquals("snails", plan.mobs());
    }

    @Test
    void kpqCouponsArePqOnlyAtAnyLevelAndNeverCredited() {
        for (int level : new int[] {25, 40}) { // its Ligator is lv32: neither too strong at 25 nor an event monster at 40
            Plan plan = FarmSites.plan(k, 4001007, level, 103000000, false);
            assertEquals(Verdict.PQ_ONLY, plan.verdict(), plan.toString());
            assertEquals("kpq", plan.npc());
            assertNull(FarmSites.creditSource(k, 4001007, level, 103000800, true), "a bench companion runs no PQ");
        }
    }

    @Test
    void fairyWingsAreScriptOnlyAndNameHappy() {
        Plan plan = FarmSites.plan(k, FAIRY_WING, 30, ELLINIA, false);

        assertEquals(Verdict.SCRIPT_ONLY, plan.verdict(), plan.toString());
        assertEquals("happy in happyville", plan.npc(), plan.toString());
        assertNull(FarmSites.creditSource(k, FAIRY_WING, 70, 209080000, true), "not even at Happy's own field");
    }

    @Test
    void hyperTeleportRockIsCash() {
        assertEquals(Verdict.CASH, FarmSites.plan(k, 5041000, 30, HENESYS, false).verdict());
    }

    @Test
    void zakumHelmetIsTooStrongAtThirtyAndABossHuntAtOneFifty() {
        // Zakum has no spawn the file knows of (the altar's reactor calls it up): the level and boss checks name it
        Plan weak = FarmSites.plan(k, ZAKUM_HELMET, 30, HENESYS, false);
        Plan strong = FarmSites.plan(k, ZAKUM_HELMET, 150, 211000000, false);

        assertEquals(Verdict.TOO_STRONG, weak.verdict(), weak.toString());
        assertEquals(Verdict.BOSS_ONLY, strong.verdict(), strong.toString());
        assertNotNull(weak.mobs());
        assertNull(FarmSites.creditSource(k, ZAKUM_HELMET, 30, 211040000, false));
        assertNull(FarmSites.creditSource(k, ZAKUM_HELMET, 150, 211040000, false));
    }

    @Test
    void pigTailsOnlyDropDuringTheQuest() {
        assertEquals(Verdict.QUEST_ONLY, FarmSites.plan(k, 4032340, 10, HENESYS, false).verdict());
        assertEquals(Verdict.FARM, FarmSites.plan(k, 4032340, 10, HENESYS, true).verdict(), "GATHER holds its owner's quest");
    }

    @Test
    void aShopOnlyScrollNamesItsShop() {
        int helmetDef = 2040000;
        assertTrue(k.dropSources(helmetDef).isEmpty(), "precondition: nothing drops it");
        assertFalse(k.shopsSelling(helmetDef).isEmpty(), "precondition: a shop sells it");

        Plan plan = FarmSites.plan(k, helmetDef, 30, HENESYS, false);

        assertEquals(Verdict.SHOP_ONLY, plan.verdict());
        assertNotNull(plan.npc());
    }

    @Test
    void perfectPitchIsAnyMonster() {
        assertEquals(Verdict.ANY_MONSTER, FarmSites.plan(k, PERFECT_PITCH, 30, HENESYS, false).verdict());
        DropSource credit = FarmSites.creditSource(k, PERFECT_PITCH, 30, 100010000, false);
        assertNotNull(credit);
        assertTrue(credit.global());
    }

    @Test
    void anUnknownItemHasNoSource() {
        assertEquals(Verdict.NO_SOURCE, FarmSites.plan(k, 7777777, 30, HENESYS, false).verdict());
    }

    @Test
    void creditPrefersTheCampsOwnDropper() {
        // Leather: Pigs and Ribbon Pigs drop it alike (the Pig sorts first); L Forest II has only Ribbon Pigs
        int leather = 4000021;
        int ribbonForest = 103020100;
        DropSource credit = FarmSites.creditSource(k, leather, 15, ribbonForest, false);

        assertNotNull(credit);
        assertTrue(k.map(ribbonForest).orElseThrow().mobs().contains(credit.mobId()), "mob " + credit.mobId());
        assertNull(FarmSites.creditSource(k, leather, 15, HENESYS, false), "FARM_ITEM grinds its camp: nothing in town drops it");
        assertEquals(1210100, FarmSites.creditSource(k, leather, 15, HENESYS, true).mobId(), "GATHER: the best fitting dropper");
    }

    @Test
    void planIsFast() {
        int[] items = {SNAIL_SHELL, FAIRY_WING, ZAKUM_HELMET, 2000000, 2070005, 4000021, PERFECT_PITCH, 2040000};
        int[] anchors = {HENESYS, ELLINIA, 102000000, 103000000, 104000000, 200000000, 211000000, 220000000};
        for (int i = 0; i < 400; i++) { // warm up: the JIT and one distance table per anchor
            FarmSites.plan(k, items[i % items.length], 10 + i % 60, anchors[i % anchors.length], false);
            FarmSites.creditSource(k, items[i % items.length], 10 + i % 60, anchors[i % anchors.length], false);
        }
        long planNs = Long.MAX_VALUE;
        long creditNs = Long.MAX_VALUE;
        for (int attempt = 0; attempt < 3; attempt++) { // the best of three: a busy machine slows one, not all
            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                FarmSites.plan(k, items[i % items.length], 10 + i % 60, anchors[i % anchors.length], false);
            }
            planNs = Math.min(planNs, (System.nanoTime() - start) / 200);
            start = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                FarmSites.creditSource(k, items[i % items.length], 10 + i % 60, anchors[i % anchors.length], false);
            }
            creditNs = Math.min(creditNs, (System.nanoTime() - start) / 200);
        }

        assertTrue(planNs < 1_000_000, "plan " + planNs + " ns");
        assertTrue(creditNs < 50_000, "creditSource " + creditNs + " ns");
    }
}
