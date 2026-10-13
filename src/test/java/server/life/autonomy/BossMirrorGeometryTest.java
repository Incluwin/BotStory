package server.life.autonomy;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossMirrorGeometryTest {
    /** P15: a box lt(-800,-600) rb(400,250) whose effect falls at random spots 300 px apart (the Clock's spacing). */
    private static final BossAction.OrdinaryAttack LIGHTNING = new BossAction.OrdinaryAttack(2, 3, 0, 0, 0, true,
            new Point(-800, -600), new Point(400, 250), 0, 0, 0, false, 0, 100, 0, 0, false, 300);

    @Test
    void aRandomSpotStrikesWithinHalfWidthOfASpotFromTheBoxsLeftEdgePlusThePhase() {
        Point origin = new Point(0, 0);
        // facing left: the box runs -800..400; phase 20 puts spots at -780, -480, -180, 120
        assertTrue(BossMirrorGeometry.onRandomSpot(LIGHTNING, origin, new Point(-780, 0), false, true, 300, 20, 35));
        assertTrue(BossMirrorGeometry.onRandomSpot(LIGHTNING, origin, new Point(-445, 0), false, true, 300, 20, 35));
        assertFalse(BossMirrorGeometry.onRandomSpot(LIGHTNING, origin, new Point(-444, 0), false, true, 300, 20, 35));
        assertFalse(BossMirrorGeometry.onRandomSpot(LIGHTNING, origin, new Point(-630, 0), false, true, 300, 20, 35));
        // facing right the box flips to -400..800: spots at -380, -80, 220, 520
        assertTrue(BossMirrorGeometry.onRandomSpot(LIGHTNING, origin, new Point(220, 0), false, false, 300, 20, 35));
        assertFalse(BossMirrorGeometry.onRandomSpot(LIGHTNING, origin, new Point(120, 0), false, false, 300, 20, 35));
        // a noFlip part reads as facing left
        assertTrue(BossMirrorGeometry.onRandomSpot(LIGHTNING, origin, new Point(120, 0), true, false, 300, 20, 35));
    }

    @Test
    void noSpacingMeansNoRandomSpots() {
        assertTrue(BossMirrorGeometry.onRandomSpot(LIGHTNING, new Point(0, 0), new Point(-630, 0), false, true, 0, 20,
                35), "a boss whose rules leave random spots off");
        assertFalse(BossMirrorGeometry.onRandomSpot(LIGHTNING, null, new Point(-630, 0), false, true, 300, 20, 35));
    }

    @Test
    void theCatalogReadsTheSpacingOfAnAttackWithRandomSpotsFromWz() {
        List<BossAction.OrdinaryAttack> clock = ServerMobActionCatalog.forMob(8_500_001).attacks();
        assertEquals(300, clock.get(2).randomSpotSpacing(), "the Clock's attack3, its lightning");
        assertEquals(0, clock.get(0).randomSpotSpacing());
        assertEquals(100, ServerMobActionCatalog.forMob(8_800_000).attacks().get(1).randomSpotSpacing(),
                "Zakum attack2 has them too; its rules leave them off");
    }

    /** The altar reactor spot where every Zakum part spawns. */
    private static final Point ORIGIN = new Point(-10, -204);
    /** A valid mask needs no random draw. */
    private static final RandomGenerator NO_DRAW = () -> {
        throw new AssertionError("a valid mask must not be sampled");
    };

    @Test
    void aNoFlipPartHitsTheSameSideAtEitherFacingBit() {
        BossAction.OrdinaryAttack slam = firstAttack(8_800_004);
        assertEquals(new Point(-1_100, -600), slam.lt());
        assertEquals(new Point(800, 250), slam.rb());

        for (boolean facingLeft : new boolean[]{true, false}) {
            assertTrue(BossMirrorGeometry.hits(slam, ORIGIN, at(700), true, facingLeft, List.of()));
            assertFalse(BossMirrorGeometry.hits(
                    slam, ORIGIN, at(-1_150), true, facingLeft, List.of()));
            // These two tell the stored (left-facing) box from its mirror image.
            assertTrue(BossMirrorGeometry.hits(
                    slam, ORIGIN, at(-1_000), true, facingLeft, List.of()));
            assertFalse(BossMirrorGeometry.hits(
                    slam, ORIGIN, at(1_000), true, facingLeft, List.of()));
        }
    }

    @Test
    void aPartThatFlipsFollowsItsFacingBit() {
        BossAction.OrdinaryAttack slam = firstAttack(8_800_004);

        assertTrue(BossMirrorGeometry.hits(slam, ORIGIN, at(-1_000), false, true, List.of()));
        assertFalse(BossMirrorGeometry.hits(slam, ORIGIN, at(1_000), false, true, List.of()));
        assertFalse(BossMirrorGeometry.hits(slam, ORIGIN, at(-1_000), false, false, List.of()));
        assertTrue(BossMirrorGeometry.hits(slam, ORIGIN, at(1_000), false, false, List.of()));
    }

    @Test
    void aBoxAttackHasNoRegions() {
        BossAction.OrdinaryAttack slam = firstAttack(8_800_004);

        assertEquals(List.of(), BossMirrorGeometry.regions(slam, 0xFFFF, NO_DRAW));
        assertEquals(List.of(), BossMirrorGeometry.regions(slam, 0, NO_DRAW));
    }

    @Test
    void aValidSixBitMaskOnAnUpperArmGivesExactlyThoseRegions() {
        BossAction.OrdinaryAttack rain = firstAttack(8_800_006);
        assertEquals(-6, rain.areaStart());
        assertEquals(12, rain.areaCount());
        assertEquals(6, rain.selectedAreaCount());

        List<Integer> picked = List.of(0, 2, 4, 7, 9, 11);
        List<Integer> regions = BossMirrorGeometry.regions(rain, mask(picked), NO_DRAW);

        assertEquals(picked, regions);
        // Region i sits (i - 6) x 100 px from the part (noFlip: the stored left-facing side).
        assertTrue(BossMirrorGeometry.hits(rain, ORIGIN, at(-400), true, false, regions));
        assertTrue(BossMirrorGeometry.hits(rain, ORIGIN, at(500), true, false, regions));
        assertFalse(BossMirrorGeometry.hits(rain, ORIGIN, at(-500), true, false, regions));
        assertFalse(BossMirrorGeometry.hits(rain, ORIGIN, at(400), true, false, regions));
    }

    @Test
    void anInvalidMaskFallsBackToSixSampledRegions() {
        BossAction.OrdinaryAttack rain = firstAttack(8_800_006);
        List<Integer> masks = List.of(
                mask(List.of(3)),                   // one bit
                0,                                  // a refused attack zeroes pOption
                mask(List.of(0, 2, 4, 7, 9, 12)));  // six bits, one past the last region

        for (int mask : masks) {
            for (long seed = 0; seed < 20; seed++) {
                List<Integer> regions = BossMirrorGeometry.regions(rain, mask, new Random(seed));

                assertEquals(6, regions.size(), "mask " + mask);
                assertEquals(6, new HashSet<>(regions).size(), "mask " + mask);
                assertEquals(regions.stream().sorted().toList(), regions);
                assertTrue(regions.stream().allMatch(region -> region >= 0 && region < 12));
            }
        }
    }

    @Test
    void anAttackThatUsesEveryRegionGetsThemAll() {
        // 9 of 9: no Zakum attack has this shape, so the attack is built here.
        BossAction.OrdinaryAttack nine = new BossAction.OrdinaryAttack(
                0, 1, 0, 1_000, 1_000, false,
                new Point(-40, -100), new Point(40, 0),
                -4, 9, 9, false, 100, 0, 0, 0, false);
        List<Integer> all = IntStream.range(0, 9).boxed().toList();

        assertEquals(all, BossMirrorGeometry.regions(nine, 0, NO_DRAW));
        assertEquals(all, BossMirrorGeometry.regions(nine, mask(List.of(1)), NO_DRAW));
        assertEquals(all, BossMirrorGeometry.regions(nine, 0xFFFF, NO_DRAW));
        // Regions 0 and 8 sit 400 px out on each side; the gaps between regions miss.
        assertTrue(BossMirrorGeometry.hits(nine, ORIGIN, at(-400), false, true, all));
        assertTrue(BossMirrorGeometry.hits(nine, ORIGIN, at(400), false, true, all));
        assertFalse(BossMirrorGeometry.hits(nine, ORIGIN, at(50), false, true, all));
        assertFalse(BossMirrorGeometry.hits(nine, ORIGIN, at(450), false, true, all));
    }

    @Test
    void containsRegionsTestsEveryListedRegionAndNothingElse() {
        BossAction.OrdinaryAttack distributed = new BossAction.OrdinaryAttack(
                2, 3, 10, 2_040, 2_280, false,
                new Point(-57, -200), new Point(57, 0),
                -5, 11, 3, true, 280, 0, 0, 0, false);
        Point origin = new Point(0, 0);
        List<Integer> regions = List.of(0, 5, 10);

        for (int x = -700; x <= 700; x += 25) {
            Point target = new Point(x, -20);
            assertEquals(BossActionGeometry.contains(distributed, origin, target, true, regions),
                    BossActionGeometry.containsRegions(distributed, origin, target, true, regions),
                    "x " + x);
        }
        assertFalse(BossActionGeometry.containsRegions(distributed, origin, null, true, regions));
        assertFalse(BossActionGeometry.containsRegions(
                distributed, origin, new Point(0, -20), true, List.of()));
        assertFalse(BossActionGeometry.containsRegions(
                distributed, origin, new Point(0, -20), true, List.of(-1, 11)));
    }

    /**
     * Capt. Latanica stands a few px above his floor, and his attack1 and attack2 boxes end at y 0 and 1. His
     * client's hits on ohboy came 2 to 33 px below him (P14 live 2026-10-13): the box hits the body, not the feet.
     */
    @Test
    void aBoxThatEndsJustAboveTheFeetStillHitsTheBody() {
        List<BossAction.OrdinaryAttack> latanica = ServerMobActionCatalog.forMob(9_420_513).attacks();
        BossAction.OrdinaryAttack attack1 = latanica.get(0);
        BossAction.OrdinaryAttack attack2 = latanica.get(1);
        assertEquals(new Point(398, 0), attack1.rb());
        assertEquals(new Point(90, 1), attack2.rb());
        Point boss = new Point(-146, 223);

        // the live misses, facing right
        Point attack2Miss = new Point(boss.x + 98, boss.y + 2);
        Point attack1Miss = new Point(boss.x + 675, boss.y + 33);
        assertFalse(BossActionGeometry.contains(attack2, boss, attack2Miss, false), "the feet alone miss");
        assertTrue(BossMirrorGeometry.hits(attack2, boss, attack2Miss, false, false, List.of()));
        assertTrue(BossMirrorGeometry.hits(attack1, boss, attack1Miss, false, false, List.of()));
        // a body wholly below the box, or beside it, is not hit
        Point below = new Point(boss.x + 98, boss.y + 1 + BossMirrorGeometry.BODY_HEIGHT + 1);
        assertFalse(BossMirrorGeometry.hits(attack2, boss, below, false, false, List.of()));
        assertFalse(BossMirrorGeometry.hits(attack2, boss, new Point(boss.x + 600, boss.y + 2), false, false,
                List.of()));
    }

    private static BossAction.OrdinaryAttack firstAttack(int mobId) {
        return ServerMobActionCatalog.forMob(mobId).attacks().getFirst();
    }

    private static Point at(int dx) {
        return new Point(ORIGIN.x + dx, ORIGIN.y);
    }

    private static int mask(List<Integer> regions) {
        int mask = 0;
        for (int region : regions) {
            mask |= 1 << region;
        }
        return mask;
    }
}
