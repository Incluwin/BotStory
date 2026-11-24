package server.living.population.craft;

import server.living.population.Citizen;

import java.util.Locale;
import java.util.SplittableRandom;

/**
 * A citizen's trade (docs/WORLD_SYSTEMS_PLAN.md N8.1), kept as one flag in living_citizen.flags ("trade-smith"): SMITH
 * makes warrior gear (and bows, guns and common pieces) and refines ores, TAILOR gloves, shoes, mage and thief gear,
 * ALCHEMIST potions and the Maker's stat gems, and SCROLLER scrolls a customer's piece.
 */
public enum Profession {
    SMITH, TAILOR, ALCHEMIST, SCROLLER;

    private static final String FLAG_PREFIX = "trade-";
    /** About one citizen in ten has a trade. */
    static final double SHARE = 0.10d;

    public String flag() {
        return FLAG_PREFIX + word();
    }

    /** "smith", as people type it. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The trade among the citizen's flags, or null. */
    public static Profession of(Citizen citizen) {
        if (citizen != null) {
            for (Profession profession : values()) {
                if (citizen.has(profession.flag())) {
                    return profession;
                }
            }
        }
        return null;
    }

    public static Profession parse(String word) {
        for (Profession profession : values()) {
            if (profession.name().equalsIgnoreCase(word)) {
                return profession;
            }
        }
        return null;
    }

    /**
     * The trade a citizen takes up, or null for none: stable per citizen. {@code branch} is its career's (1 warrior, 2
     * magician, 3 bowman, 4 thief, 5 pirate), {@code sociability} its personality's (0..100): a citizen who likes people
     * is the likelier to work for them, so the chance runs from 0.6 x to 1.4 x {@link #SHARE}, SHARE on average at 50.
     */
    public static Profession drawn(int citizenId, int branch, int sociability) {
        SplittableRandom random = new SplittableRandom(citizenId * 0x9e3779b97f4a7c15L ^ 0x7ade5L);
        double chance = SHARE * (0.6d + 0.8d * Math.max(0, Math.min(100, sociability)) / 100.0d);
        if (random.nextDouble() >= chance) {
            return null;
        }
        int[] weights = switch (branch) { // SMITH, TAILOR, ALCHEMIST, SCROLLER
            case 1 -> new int[]{60, 10, 10, 20};
            case 2 -> new int[]{5, 25, 45, 25};
            case 3 -> new int[]{25, 40, 15, 20};
            case 4 -> new int[]{10, 45, 15, 30};
            default -> new int[]{30, 20, 25, 25};
        };
        int roll = random.nextInt(100);
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return values()[i];
            }
        }
        return SCROLLER;
    }
}
