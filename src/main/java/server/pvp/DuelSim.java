package server.pvp;

import config.PvpConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * {@code !pvp sim} (docs/PVP_PLAN.md V1.4): a Monte Carlo of one round between two real loadouts under the PvP damage
 * model, the heal allowance and the guards, with both sides hitting nonstop. It answers whether the model's numbers
 * hold across classes: median duel time and win rate per pairing.
 *
 * <p>What it leaves out, on purpose: movement, reach and the bots' choices (V2's practice duels measure those), and
 * each skill's real attack rate. A fighter's raw damage per second is the model's own estimate (its gear base x the
 * reference rate, or the job path's measured rate), so the sim checks the model's arithmetic and the defenses, not how
 * fast a class really swings.
 */
public final class DuelSim {
    /** A typical attack delay; it sets how big each hit is, and so how often the per-attack cap bites. */
    static final double ATTACK_INTERVAL_S = 0.75d;
    /** Lines roll between about 55% and 100% of the maximum (60% mastery), mean 0.775. */
    static final double ROLL_LOW = 0.55d;
    /** A fighter drinks an HP potion below this share of max HP, else an MP one for Magic Guard below half MP. */
    static final double DRINK_BELOW = 0.9d;

    private DuelSim() {
    }

    /**
     * One side of a simulated round, from a real character. {@code rawDps}: its sustained damage per second before
     * the PvP model (as on a defenseless monster); {@code coefficient}: the model's step 2 for it; {@code reduce}:
     * Achilles, High Defense and Combo Barrier as a factor; {@code magicGuardPercent}: 0 without Magic Guard.
     */
    public record Fighter(String name, int level, int jobId, boolean magic, int maxHp, int maxMp, double rawDps,
                          double coefficient, int accuracy, int avoid, int wdef, int mdef, double reduce,
                          int magicGuardPercent) { }

    public record Outcome(int winner, double seconds) { }

    public record Summary(int rounds, double aWinRate, double drawRate, double medianSeconds, double p10Seconds,
                          double p90Seconds) {
        @Override
        public String toString() {
            return String.format("A wins %.0f%%, draws %.0f%%, duel %.1f s (p10 %.1f, p90 %.1f), n=%d",
                    aWinRate * 100, drawRate * 100, medianSeconds, p10Seconds, p90Seconds, rounds);
        }
    }

    private static final class Side {
        final Fighter fighter;
        double hp;
        double mp;
        double nextAttackS;
        double nextDrinkS;

        Side(Fighter fighter, double firstAttackS) {
            this.fighter = fighter;
            this.hp = fighter.maxHp();
            this.mp = fighter.maxMp();
            this.nextAttackS = firstAttackS;
        }
    }

    /** {@code rounds} rounds between fighters drawn from {@code sideA} and {@code sideB}; null when a side is empty. */
    public static Summary run(List<Fighter> sideA, List<Fighter> sideB, int rounds, PvpConfig config, Random random) {
        if (sideA.isEmpty() || sideB.isEmpty() || rounds <= 0) {
            return null;
        }
        List<Double> seconds = new ArrayList<>(rounds);
        int aWins = 0;
        int draws = 0;
        for (int i = 0; i < rounds; i++) {
            Fighter a = sideA.get(random.nextInt(sideA.size()));
            Fighter b = sideB.get(random.nextInt(sideB.size()));
            Outcome outcome = round(a, b, config, random);
            seconds.add(outcome.seconds());
            if (outcome.winner() == 0) {
                aWins++;
            } else if (outcome.winner() < 0) {
                draws++;
            }
        }
        seconds.sort(Double::compare);
        return new Summary(rounds, aWins / (double) rounds, draws / (double) rounds, quantile(seconds, 0.5),
                quantile(seconds, 0.1), quantile(seconds, 0.9));
    }

    /** One round: 0 when {@code a} wins, 1 when {@code b} does, -1 for a draw. */
    public static Outcome round(Fighter a, Fighter b, PvpConfig config, Random random) {
        Side[] sides = {
                new Side(a, random.nextDouble() * ATTACK_INTERVAL_S),
                new Side(b, random.nextDouble() * ATTACK_INTERVAL_S)};
        double window = config.PVP_HEAL_WINDOW_MS / 1000d;
        double limit = config.PVP_ROUND_SECONDS;
        double now = 0;
        while (true) {
            int next = sides[0].nextAttackS <= sides[1].nextAttackS ? 0 : 1;
            now = sides[next].nextAttackS;
            if (now >= limit) {
                double shareA = sides[0].hp / a.maxHp();
                double shareB = sides[1].hp / b.maxHp();
                return new Outcome(Math.abs(shareA - shareB) < 1e-9 ? -1 : shareA > shareB ? 0 : 1, limit);
            }
            for (Side side : sides) {
                drink(side, now, window, config);
            }
            Side attacker = sides[next];
            Side victim = sides[1 - next];
            attacker.nextAttackS = now + ATTACK_INTERVAL_S * (0.9d + 0.2d * random.nextDouble());
            if (hit(attacker.fighter, victim, config, random)) {
                return new Outcome(next, now);
            }
        }
    }

    /** The victim drinks when its heal window has passed and it needs to (one potion per window, as in a duel). */
    private static void drink(Side side, double now, double window, PvpConfig config) {
        if (now < side.nextDrinkS) {
            return;
        }
        Fighter f = side.fighter;
        double share = config.PVP_HEAL_PERCENT / 100d;
        if (side.hp < f.maxHp() * DRINK_BELOW) {
            side.hp = Math.min(f.maxHp(), side.hp + f.maxHp() * share);
            side.nextDrinkS = now + window;
        } else if (f.magicGuardPercent() > 0 && side.mp < f.maxMp() * 0.5d) {
            side.mp = Math.min(f.maxMp(), side.mp + f.maxMp() * share);
            side.nextDrinkS = now + window;
        }
    }

    /** One attack; true when it knocks the victim out. */
    static boolean hit(Fighter from, Side victim, PvpConfig config, Random random) {
        Fighter to = victim.fighter;
        double chance = PvpDamageModel.hitChance(from.magic(), from.accuracy(), from.level(), to.level(), to.avoid(),
                config);
        if (random.nextDouble() > chance) {
            return false;
        }
        double roll = (ROLL_LOW + (1d - ROLL_LOW) * random.nextDouble()) / ((1d + ROLL_LOW) / 2d);
        double raw = from.rawDps() * ATTACK_INTERVAL_S * roll;
        double damage = raw * from.coefficient() * PvpDamageModel.levelFactor(from.level(), to.level(), config)
                * PvpDamageModel.defenseFactor(from.magic() ? to.mdef() : to.wdef(), from.level(), config);
        damage = Math.min(damage, to.maxHp() * config.PVP_MAX_HIT_PERCENT / 100d) * to.reduce();
        double mpLoss = 0;
        if (to.magicGuardPercent() > 0) {
            mpLoss = Math.min(victim.mp, damage * to.magicGuardPercent() / 100d * config.PVP_MAGIC_GUARD_FACTOR);
        }
        victim.mp -= mpLoss;
        victim.hp -= damage - mpLoss;
        return victim.hp <= 1d;
    }

    static double quantile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.min(sorted.size() - 1, Math.max(0, Math.round(q * (sorted.size() - 1))));
        return sorted.get(index);
    }
}
