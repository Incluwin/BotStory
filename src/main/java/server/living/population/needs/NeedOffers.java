package server.living.population.needs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The seam between the needs board's SUPPLY offers and the learned prices (docs/WORLD_SYSTEMS_PLAN.md I2.2,
 * population PriceLearning): NeedDerivation asks it the premium a request offers over the shop price, and hears from it
 * how each request ended. With no learner installed, or one that is off, the premium is the configured one
 * (NEEDS_SUPPLY_PREMIUM) and nothing is heard, exactly as before.
 */
public final class NeedOffers {
    private static final Logger log = LoggerFactory.getLogger(NeedOffers.class);
    /** What the board asks the learner. */
    public interface Learner {
        /** The premium a SUPPLY request of {@code citizenId} for {@code itemId} offers; {@code fixed} when not learned. */
        double premium(int citizenId, int itemId, double fixed);

        /**
         * A request's ended row was written ({@code closed} as written). {@code committed}: on an autocommit connection;
         * false inside a transaction that may still roll back. Called on the store's thread: in memory only, no I/O.
         */
        void closed(Need closed, long nowMs, boolean committed);
    }

    private static volatile Learner learner;

    private NeedOffers() {
    }

    /** The learner the board asks from now on; null for none. */
    public static void install(Learner next) {
        learner = next;
    }

    /** The learner's premium, or {@code fixed} with none, or when it failed (logged; the board prices as before). */
    static double premium(int citizenId, int itemId, double fixed) {
        Learner current = learner;
        if (current == null) {
            return fixed;
        }
        try {
            double premium = current.premium(citizenId, itemId, fixed);
            return Double.isFinite(premium) && premium > 0.0d ? premium : fixed;
        } catch (RuntimeException failure) {
            log.warn("learned offer failed for citizen {} item {}; the configured premium holds", citizenId, itemId, failure);
            return fixed;
        }
    }

    /** Tells the learner of an ended row; never throws into the store call that wrote it. */
    static void closed(Need closed, long nowMs, boolean committed) {
        Learner current = learner;
        if (current == null || closed == null) {
            return;
        }
        try {
            current.closed(closed, nowMs, committed);
        } catch (RuntimeException failure) {
            log.warn("learned offer could not hear how need {} ended", closed.id(), failure);
        }
    }
}
