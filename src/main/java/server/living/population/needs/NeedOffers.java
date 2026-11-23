package server.living.population.needs;

/**
 * The seam between the needs board's SUPPLY offers and the learned prices (docs/WORLD_SYSTEMS_PLAN.md I2.2,
 * population PriceLearning): NeedDerivation asks it the premium a request offers over the shop price, and hears from it
 * how each request ended. With no learner installed, or one that is off, the premium is the configured one
 * (NEEDS_SUPPLY_PREMIUM) and nothing is heard, exactly as before.
 */
public final class NeedOffers {
    /** What the board asks the learner. */
    public interface Learner {
        /** The premium a SUPPLY request of {@code citizenId} for {@code itemId} offers; {@code fixed} when not learned. */
        double premium(int citizenId, int itemId, double fixed);

        /** A request ended ({@code closed} as it was closed). */
        void closed(Need closed, long nowMs);
    }

    private static volatile Learner learner;

    private NeedOffers() {
    }

    /** The learner the board asks from now on; null for none. */
    public static void install(Learner next) {
        learner = next;
    }

    static double premium(int citizenId, int itemId, double fixed) {
        Learner current = learner;
        return current == null ? fixed : current.premium(citizenId, itemId, fixed);
    }

    static void closed(Need closed, long nowMs) {
        Learner current = learner;
        if (current != null && closed != null) {
            current.closed(closed, nowMs);
        }
    }
}
