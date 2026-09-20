package server.bot;

import config.BotEconomyConfig;
import config.YamlConfig;

import java.util.Random;

/**
 * Seedable RNG used by every RNG consumer in the bot-economy package.
 *
 * <p>Production: seeded from {@code System.nanoTime()} on boot.
 * Test mode ({@code bot_economy.TEST_MODE: true}): seeded from
 * {@code bot_economy.test.RNG_SEED} so fixture assertions are deterministic.
 *
 * <p>Tests that need a specific sequence call {@link #setForTest(Random)}
 * on startup. The server.bot package is covered by an ArchUnit rule (see
 * the test suite) that forbids direct {@code java.util.Random} construction.
 */
public final class BotRandomService {
    private static volatile Random INSTANCE = defaultInstance();

    private BotRandomService() {}

    private static Random defaultInstance() {
        BotEconomyConfig cfg = BotEconomy.cfg();
        if (cfg != null && cfg.TEST_MODE) {
            return new Random(cfg.test.RNG_SEED);
        }
        return new Random(System.nanoTime());
    }

    public static Random random() {
        return INSTANCE;
    }

    public static double nextDouble() {
        return INSTANCE.nextDouble();
    }

    public static int nextInt(int bound) {
        return INSTANCE.nextInt(bound);
    }

    /** Uniform in [min, max] inclusive-inclusive. */
    public static int nextIntInclusive(int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) return minInclusive;
        return minInclusive + INSTANCE.nextInt(maxInclusive - minInclusive + 1);
    }

    /** Uniform in [min, max). */
    public static double uniform(double min, double max) {
        if (max <= min) return min;
        return min + (max - min) * INSTANCE.nextDouble();
    }

    /** Test-only override. Production code never calls this. */
    public static void setForTest(Random replacement) {
        if (replacement == null) throw new IllegalArgumentException("replacement is null");
        INSTANCE = replacement;
    }

    /** Force re-init from current config (used on hot-reload of TEST_MODE / RNG_SEED). */
    public static void reinitialize() {
        INSTANCE = defaultInstance();
    }

    // Wrapper used across services to keep references terse.
    static class BotEconomy {
        static config.BotEconomyConfig cfg() {
            try {
                return YamlConfig.config.bot_economy;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
