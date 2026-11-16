package server.pvp;

import config.PvpConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The PvP damage model of docs/PVP_PLAN.md section 2, steps 2-7, as pure functions: a line rolled as on a defenseless
 * monster (step 1, {@code CombatFormulaProvider.rollPvpLines}) is multiplied by the attacker's coefficient and the
 * hit's factors, and the attack's lines shrink together to the per-attack cap.
 */
public final class PvpDamageModel {
    private PvpDamageModel() {
    }

    /** What the coefficient needs to know about an attacker. {@code magic}: a magician's spell base. */
    public record Attacker(int level, int jobId, long gearBase, boolean magic) { }

    /**
     * Step 2: {@code c = H_ref(L) / (TTK x D_job(L)) x G^(gamma - 1)}. A median attacker using its job's best attack
     * then knocks out a median-HP victim in TTK seconds of hitting; gear above the median deals {@code G^gamma}.
     */
    public static double coefficient(Attacker attacker, PvpReference reference, PvpConfig config) {
        return coefficient(attacker, reference, config, typicalGear(attacker, reference, config));
    }

    /** The same against {@code typical}, the median gear base the attacker is measured against (-1: none). */
    static double coefficient(Attacker attacker, PvpReference reference, PvpConfig config, long typical) {
        double hRef = reference.hRef(attacker.level());
        // a median attacker as geared today: the reference rate belongs to the reference median's gear
        double rate = rate(attacker, reference, config) * typicalShare(attacker, reference, typical);
        return hRef / (config.PVP_TTK_SECONDS * Math.max(1d, rate))
                * Math.pow(gearRatio(attacker, typical), config.PVP_GEAR_EXPONENT - 1d);
    }

    /**
     * The median gear base of the attacker's job path and band: the live one ({@link PvpGearMedians}) while
     * PVP_LIVE_GEAR_MEDIANS is on, else the reference table's (-1 when it has none).
     */
    public static long typicalGear(Attacker attacker, PvpReference reference, PvpConfig config) {
        return config.PVP_LIVE_GEAR_MEDIANS
                ? PvpGearMedians.current().median(attacker.jobId(), attacker.level(), reference)
                : reference.gearMedian(attacker.jobId(), attacker.level());
    }

    /** The typical gear over the gear the reference rate was set at (the reference median, else the attacker's own). */
    static double typicalShare(Attacker attacker, PvpReference reference, long typical) {
        if (typical <= 0) {
            return 1d;
        }
        long referenceMedian = reference.gearMedian(attacker.jobId(), attacker.level());
        return typical / (double) (referenceMedian > 0 ? referenceMedian : Math.max(1L, attacker.gearBase()));
    }

    /** The attacker's gear base over {@code typical} ({@code G}; 1 without a median), at least 0.05. */
    static double gearRatio(Attacker attacker, long typical) {
        long ownBase = Math.max(1L, attacker.gearBase());
        double gearMedian = typical > 0 ? typical : ownBase;
        return Math.max(0.05d, ownBase / gearMedian);
    }

    /** What the attacker's gear adds over a median-geared one of its job and band: {@code G^gamma}. */
    public static double gearEdge(Attacker attacker, PvpReference reference, PvpConfig config) {
        return Math.pow(gearRatio(attacker, typicalGear(attacker, reference, config)), config.PVP_GEAR_EXPONENT);
    }

    /**
     * The reference table's raw damage per second for a median-geared attacker of this job path and band: its measured
     * {@code d_job}, else its class's rate per point of base times the band's gear median.
     */
    public static double priorRate(Attacker attacker, PvpReference reference) {
        long median = reference.gearMedian(attacker.jobId(), attacker.level());
        double gearMedian = median > 0 ? median : Math.max(1L, attacker.gearBase());
        long measured = reference.measuredRate(attacker.jobId(), attacker.level());
        return measured > 0 ? measured : gearMedian * reference.ratePerBase(attacker.jobId(), attacker.magic());
    }

    /** {@link #priorRate} times what duels have taught (PD16, {@link PvpRateLearner}) while PVP_LEARN_RATES is on. */
    public static double rate(Attacker attacker, PvpReference reference, PvpConfig config) {
        double prior = priorRate(attacker, reference);
        return config.PVP_LEARN_RATES
                ? prior * PvpRateLearner.get().multiplier(attacker.jobId(), attacker.level(), config)
                : prior;
    }

    /** Step 3: the share each of {@code targets} takes of an attack that hits that many. */
    public static double areaShare(int targets, PvpConfig config) {
        return Math.max(config.PVP_AOE_FLOOR, 1d - config.PVP_AOE_FALLOFF * Math.max(0, targets - 1));
    }

    /**
     * Step 2b: the victim level's reference HP over the attacker level's. The coefficient is set for a victim of the
     * attacker's own level; against another level it was counted twice, once in the attacker's reference HP and once in
     * the victim's own HP, so a 4-level edge knocked out about 1.3x faster (practice run seven: a 3-5 level edge won
     * 79%). With this the level edge is step 4 alone.
     */
    public static double levelHpShare(int attackerLevel, int victimLevel, PvpReference reference) {
        double attacker = reference.hRef(attackerLevel);
        return attacker > 0 ? reference.hRef(victimLevel) / attacker : 1d;
    }

    /** Step 4: PVP_LEVEL_PERCENT per level the attacker is above (or below) the victim, capped. */
    public static double levelFactor(int attackerLevel, int victimLevel, PvpConfig config) {
        double percent = (double) config.PVP_LEVEL_PERCENT * (attackerLevel - victimLevel);
        percent = Math.max(-config.PVP_LEVEL_CAP_PERCENT, Math.min(config.PVP_LEVEL_CAP_PERCENT, percent));
        return 1d + percent / 100d;
    }

    /** Step 5: the victim's WDEF (physical) or MDEF (magic) cuts {@code def / (def + K x attacker level)}, capped. */
    public static double defenseFactor(int defense, int attackerLevel, PvpConfig config) {
        if (defense <= 0) {
            return 1d;
        }
        double cut = defense / (double) (defense + (long) config.PVP_DEF_K * Math.max(1, attackerLevel));
        return 1d - Math.min(config.PVP_DEF_CAP_PERCENT / 100d, cut);
    }

    /**
     * Step 6: the stock avoid formula ({@code acc / ((1.84 + 0.07 x levels below) x avoid + 1)}) with the misses
     * capped, so high avoid helps without making anyone untouchable.
     */
    public static double hitChance(boolean magic, int accuracy, int attackerLevel, int victimLevel, int avoid,
                                   PvpConfig config) {
        int capPercent = magic ? config.PVP_MAGIC_MISS_CAP_PERCENT : config.PVP_PHYSICAL_MISS_CAP_PERCENT;
        double floor = 1d - capPercent / 100d;
        if (avoid <= 0) {
            return 1d;
        }
        double levelsBelow = Math.max(0, victimLevel - attackerLevel);
        double chance = accuracy / ((1.84d + 0.07d * levelsBelow) * avoid + 1d);
        return Math.max(floor, Math.min(1d, chance));
    }

    /**
     * Scales {@code rawLines} by {@code factor}, keeps every landed line at 1 or more, then shrinks them together so
     * the attack takes at most {@code PVP_MAX_HIT_PERCENT} of {@code victimMaxHp} (step 7).
     */
    public static List<Integer> scaleLines(List<Integer> rawLines, double factor, int victimMaxHp, PvpConfig config) {
        List<Integer> scaled = new ArrayList<>(rawLines.size());
        long total = 0;
        for (int raw : rawLines) {
            int line = raw <= 0 ? 0 : (int) Math.max(1L, Math.round(raw * factor));
            scaled.add(line);
            total += line;
        }
        long cap = Math.max(1L, (long) victimMaxHp * config.PVP_MAX_HIT_PERCENT / 100L);
        if (total <= cap) {
            return scaled;
        }
        double shrink = cap / (double) total;
        List<Integer> capped = new ArrayList<>(scaled.size());
        long kept = 0;
        for (int line : scaled) {
            int smaller = line <= 0 ? 0 : (int) Math.max(1L, (long) Math.floor(line * shrink));
            capped.add(smaller);
            kept += smaller;
        }
        // flooring every line to at least 1 can leave the sum a few points over: trim the biggest lines
        while (kept > cap) {
            int biggest = 0;
            for (int i = 1; i < capped.size(); i++) {
                if (capped.get(i) > capped.get(biggest)) {
                    biggest = i;
                }
            }
            int cut = (int) Math.min(capped.get(biggest), kept - cap);
            if (cut <= 0) {
                break;
            }
            capped.set(biggest, capped.get(biggest) - cut);
            kept -= cut;
        }
        return capped;
    }
}
