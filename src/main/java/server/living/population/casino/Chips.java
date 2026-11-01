package server.living.population.casino;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Casino chips, ported from SoloMapling's CasinoChipConfig: four stock stamps (Etc, tradeable, droppable) stand for
 * meso amounts, and the casino's cashier sells and buys them back at the same price. Players bet by dropping chips in
 * front of themselves, as players at a player-run table drop their stake, and are paid in chips.
 */
public final class Chips {
    public static final int SNAIL = 4002000;
    public static final int BLUE_SNAIL = 4002001;
    public static final int STUMP = 4002002;
    public static final int SLIME = 4002003;
    /** Chip item id to its meso value, largest last. */
    private static final Map<Integer, Integer> VALUES = new LinkedHashMap<>();

    static {
        VALUES.put(SNAIL, 10_000);
        VALUES.put(BLUE_SNAIL, 50_000);
        VALUES.put(STUMP, 250_000);
        VALUES.put(SLIME, 1_000_000);
    }

    private Chips() {
    }

    /** Every chip, smallest first. */
    public static List<Integer> ids() {
        return new ArrayList<>(VALUES.keySet());
    }

    public static boolean isChip(int itemId) {
        return VALUES.containsKey(itemId);
    }

    /** The meso value of one chip; 0 for anything else. */
    public static int value(int itemId) {
        return VALUES.getOrDefault(itemId, 0);
    }

    /** The meso value of a pile of chips (item id to count). */
    public static long value(Map<Integer, Integer> chips) {
        long total = 0;
        for (Map.Entry<Integer, Integer> chip : chips.entrySet()) {
            total += (long) value(chip.getKey()) * chip.getValue();
        }
        return total;
    }

    /** The fewest chips worth {@code mesos} (rounded down to the smallest chip), largest chips first. */
    public static Map<Integer, Integer> change(long mesos) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        List<Integer> ids = ids();
        long left = mesos;
        for (int i = ids.size() - 1; i >= 0; i--) {
            int chip = ids.get(i);
            int count = (int) (left / value(chip));
            if (count > 0) {
                out.put(chip, count);
                left -= (long) count * value(chip);
            }
        }
        return out;
    }

    /** Chips out of {@code held} worth at most {@code mesos}, largest first; empty when not even the smallest fits. */
    public static Map<Integer, Integer> from(Map<Integer, Integer> held, long mesos) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        List<Integer> ids = ids();
        long left = mesos;
        for (int i = ids.size() - 1; i >= 0; i--) {
            int chip = ids.get(i);
            int count = (int) Math.min(held.getOrDefault(chip, 0), left / value(chip));
            if (count > 0) {
                out.put(chip, count);
                left -= (long) count * value(chip);
            }
        }
        return out;
    }

    /**
     * A natural's payout: one and a half times the bet in the fewest chips, rounded up to the smallest chip (a 10k
     * natural is paid 20k, not 10k), as a dealer with no half chips pays it.
     */
    static Map<Integer, Integer> threeToTwo(Map<Integer, Integer> bet) {
        long smallest = value(SNAIL);
        long owed = value(bet) * 3 / 2;
        return change((owed + smallest - 1) / smallest * smallest);
    }

    /** "2m", "350k": a bet as players say it. */
    public static String describe(Map<Integer, Integer> chips) {
        return mesos(value(chips));
    }

    /** "1.5m", "250k", "10k". */
    public static String mesos(long amount) {
        if (amount >= 1_000_000) {
            long tenths = amount / 100_000;
            return tenths % 10 == 0 ? tenths / 10 + "m" : tenths / 10 + "." + tenths % 10 + "m";
        }
        return amount >= 1_000 ? amount / 1_000 + "k" : String.valueOf(amount);
    }
}
