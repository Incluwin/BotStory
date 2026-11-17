package server.pvp;

import client.Character;
import config.PvpConfig;
import server.pvp.probe.PvpCalibration;

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
     * Achilles, High Defense and Combo Barrier as a factor; {@code magicGuardPercent}: 0 without Magic Guard;
     * {@code classHpRatio}: its class's median HP over the reference (even classes scale hits on it by this);
     * {@code hRef}: the reference HP of its level (a hit on it is scaled by its over the attacker's, step 2b).
     */
    public record Fighter(String name, int level, int jobId, boolean magic, int maxHp, int maxMp, double rawDps,
                          double coefficient, int accuracy, int avoid, int wdef, int mdef, double reduce,
                          int magicGuardPercent, double classHpRatio, double hRef) { }

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
        return round(a, b, config, random, config.PVP_ROUND_SECONDS);
    }

    /** As {@link #round(Fighter, Fighter, PvpConfig, Random)} with a round of {@code roundSeconds} (the coliseum's). */
    public static Outcome round(Fighter a, Fighter b, PvpConfig config, Random random, double roundSeconds) {
        Side[] sides = {
                new Side(a, random.nextDouble() * ATTACK_INTERVAL_S),
                new Side(b, random.nextDouble() * ATTACK_INTERVAL_S)};
        double window = config.PVP_HEAL_WINDOW_MS / 1000d;
        double limit = roundSeconds;
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

    /**
     * V6: one team round of {@code roundSeconds} between equal or unequal sides. Every fighter attacks in turn and goes
     * for the standing foe with the lowest HP share (focus fire, as TeamTactics calls it); the side left standing
     * wins, and at the bell the side keeping more of its HP (summed share) does. 0 (a), 1 (b) or -1 (a draw).
     */
    public static Outcome teamRound(List<Fighter> a, List<Fighter> b, PvpConfig config, Random random,
                                    double roundSeconds) {
        if (a.isEmpty() || b.isEmpty()) {
            return new Outcome(a.isEmpty() ? (b.isEmpty() ? -1 : 1) : 0, 0);
        }
        List<List<Side>> sides = List.of(new ArrayList<>(), new ArrayList<>());
        a.forEach(f -> sides.get(0).add(new Side(f, random.nextDouble() * ATTACK_INTERVAL_S)));
        b.forEach(f -> sides.get(1).add(new Side(f, random.nextDouble() * ATTACK_INTERVAL_S)));
        double window = config.PVP_HEAL_WINDOW_MS / 1000d;
        while (true) {
            int team = -1;
            Side attacker = null;
            for (int t = 0; t < 2; t++) {
                for (Side side : sides.get(t)) {
                    if (side.hp > 1d && (attacker == null || side.nextAttackS < attacker.nextAttackS)) {
                        attacker = side;
                        team = t;
                    }
                }
            }
            double now = attacker.nextAttackS;
            if (now >= roundSeconds) {
                double shareA = standingShare(sides.get(0));
                double shareB = standingShare(sides.get(1));
                return new Outcome(Math.abs(shareA - shareB) < 1e-9 ? -1 : shareA > shareB ? 0 : 1, roundSeconds);
            }
            for (List<Side> group : sides) {
                for (Side side : group) {
                    if (side.hp > 1d) {
                        drink(side, now, window, config);
                    }
                }
            }
            attacker.nextAttackS = now + ATTACK_INTERVAL_S * (0.9d + 0.2d * random.nextDouble());
            Side victim = null;
            for (Side side : sides.get(1 - team)) {
                if (side.hp > 1d && (victim == null
                        || side.hp / side.fighter.maxHp() < victim.hp / victim.fighter.maxHp())) {
                    victim = side;
                }
            }
            if (hit(attacker.fighter, victim, config, random) && standingShare(sides.get(1 - team)) <= 0d) {
                return new Outcome(team, now);
            }
        }
    }

    /** A team's standing HP share, summed (0 when every one of it is down). */
    private static double standingShare(List<Side> team) {
        double share = 0;
        for (Side side : team) {
            if (side.hp > 1d) {
                share += side.hp / side.fighter.maxHp();
            }
        }
        return share;
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
        } else if (!config.PVP_EVEN_CLASSES && f.magicGuardPercent() > 0 && side.mp < f.maxMp() * 0.5d) {
            side.mp = Math.min(f.maxMp(), side.mp + f.maxMp() * share);
            side.nextDrinkS = now + window;
        }
    }

    /** One attack; true when it knocks the victim out. */
    static boolean hit(Fighter from, Side victim, PvpConfig config, Random random) {
        Fighter to = victim.fighter;
        boolean even = config.PVP_EVEN_CLASSES;
        double chance = PvpDamageModel.hitChance(from.magic(), from.accuracy(), from.level(), to.level(), to.avoid(),
                config);
        if (random.nextDouble() > chance) {
            return false;
        }
        double roll = (ROLL_LOW + (1d - ROLL_LOW) * random.nextDouble()) / ((1d + ROLL_LOW) / 2d);
        double raw = from.rawDps() * ATTACK_INTERVAL_S * roll;
        double damage = raw * from.coefficient() * PvpDamageModel.levelFactor(from.level(), to.level(), config)
                * (from.hRef() > 0 && to.hRef() > 0 ? to.hRef() / from.hRef() : 1d)
                * PvpDamageModel.defenseFactor(from.magic() ? to.mdef() : to.wdef(), from.level(), config)
                * (even ? to.classHpRatio() : 1d)
                * (PvpMatchups.on(config) ? PvpMatchups.multiplier(from.jobId(), to.jobId()) : 1d);
        damage = Math.min(damage, to.maxHp() * config.PVP_MAX_HIT_PERCENT / 100d) * (even ? 1d : to.reduce());
        double mpLoss = 0;
        if (!even && to.magicGuardPercent() > 0) {
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

    /** A real character as the sim's fighter: its loadout under the damage model, the even-class factors and guards. */
    public static Fighter fighterOf(Character chr, PvpReference reference, PvpConfig config) {
        PvpCalibration.Sample sample = PvpCalibration.of(chr);
        boolean magic = sample.jobClass() == 2;
        int jobId = chr.getJob().getId();
        int level = chr.getLevel();
        long ownBase = Math.max(1L, sample.gearBase());
        long median = reference.gearMedian(jobId, level);
        double medianBase = median > 0 ? median : ownBase;
        PvpDamageModel.Attacker attacker = new PvpDamageModel.Attacker(level, jobId, ownBase, magic);
        // the learned rate too (PD16), so raw and coefficient share one rate as in a duel
        double rawDps = PvpDamageModel.rate(attacker, reference, config) * ownBase / medianBase;
        double coefficient = PvpDamageModel.coefficient(attacker, reference, config);
        server.combat.CombatFormulaProvider formulas = server.combat.CombatFormulaProvider.getInstance();
        int accuracy = magic ? formulas.getTotalMagicAccuracy(chr) : formulas.getTotalAccuracy(chr);
        int avoid = formulas.getTotalAvoidability(chr);
        int wdef = chr.getTotalWdef();
        int mdef = chr.getTotalMdef();
        if (config.PVP_EVEN_CLASSES) {
            // as the duel does (PD17): accuracy, avoid and defense against the class's own median at the band
            PvpHitBalance balance = PvpHitBalance.current();
            accuracy = (int) Math.round(accuracy * balance.accuracyFactor(jobId, level));
            avoid = (int) Math.round(avoid * balance.avoidFactor(jobId, level));
            wdef = (int) Math.round(wdef * balance.wdefFactor(jobId, level));
            mdef = (int) Math.round(mdef * balance.mdefFactor(jobId, level));
        }
        double reduce = server.combat.CharacterHitApplier.reduce(chr, 100_000) / 100_000d;
        return new Fighter(chr.getName(), level, jobId, magic, chr.getCurrentMaxHp(), chr.getCurrentMaxMp(),
                rawDps, coefficient, accuracy, avoid, wdef,
                mdef, reduce, magicGuardPercent(chr), reference.classHpRatio(jobId)
                * (PvpToughness.on(config)   // the sim knows no attacker here: weapon hits' value
                ? PvpToughness.multiplier(jobId, level, config, false) : 1d),
                reference.hRef(level));
    }

    /** Magic Guard's share as the character would have it buffed: the buff if on, else its learned level. */
    private static int magicGuardPercent(Character chr) {
        Integer buffed = chr.getBuffedValue(client.BuffStat.MAGIC_GUARD);
        if (buffed != null) {
            return buffed;
        }
        for (int skillId : new int[] {2001002, 12001001}) {
            client.Skill skill = client.SkillFactory.getSkill(skillId);
            int skillLevel = skill == null ? 0 : chr.getSkillLevel(skill);
            if (skillLevel > 0) {
                return skill.getEffect(skillLevel).getX();
            }
        }
        return 0;
    }

}
