package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live bench duties' living_world keys (docs/BENCH_LIVE_DUTIES_PLAN.md, user 2026-10-25): BENCH_STALL_MARKDOWN_STEP,
 * the share of the index a STALL takes off an item for each whole slice that sold none of it (0.05; 0 = always the
 * index), and BENCH_GATHER_AT_DROPPERS, a gatherer going to the best field for what it needs when its camp has none
 * (on). config.yaml ships both on.
 */
class LivingWorldConfigBenchLiveDutiesTest {

    @Test
    void theStallMarkdownAndTheGathererAtTheDroppersShipOn() {
        LivingWorldConfig config = new LivingWorldConfig();
        assertEquals(0.05d, config.BENCH_STALL_MARKDOWN_STEP);
        assertTrue(config.BENCH_GATHER_AT_DROPPERS);
        assertDoesNotThrow(config::validate);

        LivingWorldConfig shipped = YamlConfig.config.living_world;
        assertEquals(0.05d, shipped.BENCH_STALL_MARKDOWN_STEP);
        assertTrue(shipped.BENCH_GATHER_AT_DROPPERS);
    }

    @Test
    void aMarkdownStepIsAShareOfTheIndex() {
        LivingWorldConfig off = new LivingWorldConfig();
        off.BENCH_STALL_MARKDOWN_STEP = 0.0d;
        assertDoesNotThrow(off::validate);

        for (double bad : new double[] {-0.01d, 1.5d}) {
            LivingWorldConfig refused = new LivingWorldConfig();
            refused.BENCH_STALL_MARKDOWN_STEP = bad;
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, refused::validate);
            assertTrue(failure.getMessage().startsWith("living_world.BENCH_STALL_MARKDOWN_STEP must be within 0..1"));
        }
    }
}
