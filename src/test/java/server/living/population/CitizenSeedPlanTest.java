package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.population.CitizenGenerator.SeedPlan;
import server.living.population.PopulationContent.Career;
import server.living.population.PopulationContent.Region;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A targeted seed's plan (docs/POPULATION_GUIDE.md "Targeted seeding"): the requested region, a career, pace and backdate
 * whose life-curve level lies inside the region's band and is distributed like the citizens fast seeding settles there,
 * reproducible from the generator's Random, and no plan at all for a band no career lands in within the backdate limit.
 */
class CitizenSeedPlanTest {
    private static final int BACKDATE = 180; // config.yaml population.SEED_BACKDATE_DAYS
    private static final double LIFE_PACE = 0.8d; // config.yaml population.LIFE_PACE
    private static final PopulationContent CONTENT = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));

    @Test
    void everyReachableShippedRegionIsHomedThereAtLevelsSpreadInsideItsBand() {
        Random random = new Random(20260921L);
        int ceiling = CitizenGenerator.seedCeiling(CONTENT.careers().values(), BACKDATE, LIFE_PACE);
        int planned = 0;
        for (Region region : CONTENT.regions().values()) {
            if (!CitizenGenerator.reachable(region, CONTENT.careers().values(), BACKDATE, LIFE_PACE)) {
                assertNull(CitizenGenerator.planSeed(region, CONTENT.careers().values(), BACKDATE, LIFE_PACE, random), region.id());
                continue;
            }
            Set<Integer> levels = new TreeSet<>();
            for (int i = 0; i < 300; i++) {
                SeedPlan plan = CitizenGenerator.planSeed(region, CONTENT.careers().values(), BACKDATE, LIFE_PACE, random);
                assertNotNull(plan, region.id());
                assertEquals(region.id(), plan.region());
                assertTrue(region.fits(plan.level()), region.id() + " got lv" + plan.level());
                assertTrue(plan.level() <= ceiling, region.id() + " got lv" + plan.level() + " above the seed ceiling " + ceiling);
                assertEquals(plan.level(), LifeCurve.levelTarget(plan.backdateDays(), plan.pace(), plan.career().levelCap(), LIFE_PACE),
                        "the level birth shapes to");
                assertTrue(plan.backdateDays() >= 0 && plan.backdateDays() <= BACKDATE, "backdate " + plan.backdateDays());
                assertTrue(plan.pace() >= 0.6d && plan.pace() <= 1.5d, "pace " + plan.pace());
                levels.add(plan.level());
            }
            assertTrue(levels.size() >= 5, region.id() + " levels should spread across the band, got " + levels);
            planned++;
        }
        assertTrue(planned > 0);
    }

    @Test
    void seedsAreDistributedLikeTheCitizensFastSeedingSettlesInTheRegion() {
        Random random = new Random(11L);
        for (Region region : CONTENT.regions().values()) {
            if (!CitizenGenerator.reachable(region, CONTENT.careers().values(), BACKDATE, LIFE_PACE)) {
                continue;
            }
            List<Integer> settled = settled(region, random);
            List<Integer> seeded = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                seeded.add(CitizenGenerator.planSeed(region, CONTENT.careers().values(), BACKDATE, LIFE_PACE, random).level());
            }
            assertTrue(settled.size() >= 500, region.id() + " needs settled citizens to compare with, got " + settled.size());
            assertTrue(Math.abs(median(seeded) - median(settled)) <= 2,
                    region.id() + " seed median lv" + median(seeded) + " vs settled lv" + median(settled));
            assertTrue(shareAt(seeded, region.levelMin()) <= shareAt(settled, region.levelMin()) + 0.05d,
                    region.id() + " seeds at lv" + region.levelMin() + ": " + shareAt(seeded, region.levelMin())
                            + " vs settled " + shareAt(settled, region.levelMin()));
        }
    }

    @Test
    void theSeedCeilingIsTheFastestCareerAtTheBackdateLimit() {
        assertEquals(86, CitizenGenerator.seedCeiling(CONTENT.careers().values(), BACKDATE, LIFE_PACE), "a cap-85 cleric at pace 1.5");
        assertEquals(1, CitizenGenerator.seedCeiling(CONTENT.careers().values(), 0, LIFE_PACE));
    }

    @Test
    void aBandOnlyOneCareerReachesIsSeededWithThatCareer() {
        Career plateau = new Career("plateau", "FIGHTER", 90, 60, List.of());
        Career veteran = new Career("veteran", "CLERIC", 10, 85, List.of());
        Region summit = region(86, 90); // lv86 takes a cap-85 career at the fastest pace about all 180 days
        Random random = new Random(3);
        for (int i = 0; i < 50; i++) {
            SeedPlan plan = CitizenGenerator.planSeed(summit, List.of(plateau, veteran), BACKDATE, LIFE_PACE, random);
            assertNotNull(plan);
            assertEquals("veteran", plan.career().id());
            assertTrue(summit.fits(plan.level()), "lv" + plan.level());
        }
    }

    @Test
    void aBandNoCareerReachesIsSkipped() {
        Region summit = region(150, 200);
        assertFalse(CitizenGenerator.reachable(summit, CONTENT.careers().values(), BACKDATE, LIFE_PACE));
        assertNull(CitizenGenerator.planSeed(summit, CONTENT.careers().values(), BACKDATE, LIFE_PACE, new Random(1)));
        Region orbis = CONTENT.region("orbis");
        assertFalse(CitizenGenerator.reachable(orbis, CONTENT.careers().values(), 0, LIFE_PACE), "no backdate means level 1");
        assertNull(CitizenGenerator.planSeed(orbis, CONTENT.careers().values(), 0, LIFE_PACE, new Random(1)));
        SeedPlan newcomer = CitizenGenerator.planSeed(CONTENT.region("victoria"), CONTENT.careers().values(), 0, LIFE_PACE, new Random(1));
        assertNotNull(newcomer);
        assertEquals(1, newcomer.level());
        assertFalse(CitizenGenerator.reachable(region(60, 50), CONTENT.careers().values(), BACKDATE, LIFE_PACE), "an inverted band");
    }

    @Test
    void aBandNarrowerThanADaysLevelsIsSeededWhenSomePaceLandsInItAndRefusedWhenNone() {
        double brisk = 3.0d; // at this LIFE_PACE even the slowest citizen gains lv1 -> lv4 on its first day, lv4 -> lv7 on its second
        Region three = region(3, 3);
        assertFalse(CitizenGenerator.reachable(three, CONTENT.careers().values(), BACKDATE, brisk));
        assertNull(CitizenGenerator.planSeed(three, CONTENT.careers().values(), BACKDATE, brisk, new Random(1)));
        Region four = region(4, 4);
        assertTrue(CitizenGenerator.reachable(four, CONTENT.careers().values(), BACKDATE, brisk));
        Random random = new Random(5);
        for (int i = 0; i < 50; i++) { // a long backdate limit makes the fast-seeding draws miss it, so most take the fallback
            SeedPlan plan = CitizenGenerator.planSeed(four, CONTENT.careers().values(), 36_500, brisk, random);
            assertNotNull(plan);
            assertEquals(4, plan.level());
        }
    }

    @Test
    void anEnormousBackdateLimitStillPlansPromptly() {
        Region leafre = CONTENT.region("leafre");
        SeedPlan plan = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> CitizenGenerator.planSeed(leafre, CONTENT.careers().values(), Integer.MAX_VALUE, LIFE_PACE, new Random(9)));
        assertNotNull(plan);
        assertTrue(leafre.fits(plan.level()), "lv" + plan.level());
        assertTrue(CitizenGenerator.reachable(leafre, CONTENT.careers().values(), Integer.MAX_VALUE, LIFE_PACE));
    }

    @Test
    void plansAreReproducibleFromTheGeneratorsRandom() {
        assertEquals(plans(new Random(7)), plans(new Random(7)));
    }

    /** Levels of fast-seeded citizens whose level puts them in the band: career by popularity, pace, squared backdate. */
    private static List<Integer> settled(Region region, Random random) {
        List<Career> careers = new ArrayList<>(CONTENT.careers().values());
        int total = careers.stream().mapToInt(career -> Math.max(0, career.popularity())).sum();
        List<Integer> levels = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            int roll = random.nextInt(total);
            Career career = careers.get(0);
            for (Career candidate : careers) {
                roll -= Math.max(0, candidate.popularity());
                if (roll < 0) {
                    career = candidate;
                    break;
                }
            }
            double pace = 0.6d + random.nextDouble() * 0.9d;
            double skew = random.nextDouble();
            int level = LifeCurve.levelTarget((int) Math.round(skew * skew * BACKDATE), pace, career.levelCap(), LIFE_PACE);
            if (region.fits(level)) {
                levels.add(level);
            }
        }
        return levels;
    }

    private static int median(List<Integer> levels) {
        List<Integer> sorted = new ArrayList<>(levels);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static double shareAt(List<Integer> levels, int level) {
        return levels.stream().filter(l -> l == level).count() / (double) levels.size();
    }

    private static List<SeedPlan> plans(Random random) {
        List<SeedPlan> plans = new ArrayList<>();
        for (Region region : CONTENT.regions().values()) {
            for (int i = 0; i < 20; i++) {
                plans.add(CitizenGenerator.planSeed(region, CONTENT.careers().values(), BACKDATE, LIFE_PACE, random));
            }
        }
        return plans;
    }

    private static Region region(int levelMin, int levelMax) {
        return new Region("summit", levelMin, levelMax, List.of(100000000), List.of(), List.of(), 910000000, 1);
    }
}
