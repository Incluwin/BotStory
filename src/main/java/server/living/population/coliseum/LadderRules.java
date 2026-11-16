package server.living.population.coliseum;

import server.pvp.Glicko2;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The coliseum ladder's pure rules (docs/PVP_PLAN.md section 7 and section 2 "Rewards"): brackets, who a human is
 * matched with, the entry fee, and what a ladder match pays. No clock, storage or game objects, so all of it is tested
 * directly.
 */
final class LadderRules {
    /** A ladder win: 3 Trophies, and 6 grind-minutes of EXP for the day's first 5 wins. A loss: 1 Trophy. */
    static final int WIN_TROPHIES = 3;
    static final int LOSS_TROPHIES = 1;
    static final double WIN_EXP_GMIN = 6d;
    static final int EXP_WINS_PER_DAY = 5;
    /** An account is paid for at most this many ladder matches a day. */
    static final int DAILY_REWARDED = 10;
    /** A win more likely than this pays the Trophies alone (as in rival challenges). */
    static final double TROPHIES_ONLY_ABOVE = 0.85d;
    /** The entry fee is this many mesos a level. */
    static final int FEE_PER_LEVEL = 20;
    /** An opponent is within this many levels. */
    static final int LEVEL_WINDOW = 5;
    /** Rounds in a match; the first to win this many takes it. */
    static final int ROUNDS = 3;
    static final int ROUNDS_TO_WIN = 2;

    private LadderRules() {
    }

    /** Ladder brackets by level. Below Lv 30 there is no bracket. */
    enum Bracket {
        ROOKIE("Rookie", 30, 59),
        VETERAN("Veteran", 60, 99),
        CHAMPION("Champion", 100, Integer.MAX_VALUE);

        final String label;
        final int minLevel;
        final int maxLevel;

        Bracket(String label, int minLevel, int maxLevel) {
            this.label = label;
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
        }

        static Optional<Bracket> of(int level) {
            for (Bracket bracket : values()) {
                if (level >= bracket.minLevel && level <= bracket.maxLevel) {
                    return Optional.of(bracket);
                }
            }
            return Optional.empty();
        }

        /** The range for a page heading: "Lv 30-59", "Lv 100+". */
        String range() {
            return maxLevel == Integer.MAX_VALUE ? "Lv " + minLevel + "+" : "Lv " + minLevel + "-" + maxLevel;
        }
    }

    /** Someone who could be matched: a character id, its level and its rating in the bracket. */
    record Candidate(int characterId, int level, Glicko2.Rating rating) {
    }

    /**
     * The opponent for a human at {@code level} rated {@code rating}: the candidate in the same bracket, within
     * {@link #LEVEL_WINDOW} levels, whose rating is nearest; ties go to the nearer level, then the lower id.
     */
    static Optional<Candidate> pick(int level, Glicko2.Rating rating, List<Candidate> candidates) {
        Optional<Bracket> bracket = Bracket.of(level);
        if (bracket.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(c -> Math.abs(c.level() - level) <= LEVEL_WINDOW)
                .filter(c -> Bracket.of(c.level()).equals(bracket))
                .min(Comparator.<Candidate>comparingDouble(c -> Math.abs(c.rating().rating() - rating.rating()))
                        .thenComparingInt(c -> Math.abs(c.level() - level))
                        .thenComparingInt(Candidate::characterId));
    }

    static long fee(int level) {
        return (long) FEE_PER_LEVEL * Math.max(1, level);
    }

    /** What one ladder match pays a human. */
    record Pay(long exp, int trophies) {
    }

    /** Every win reward is multiplied by this: 0.75 for a sure win, 1.25 for beating a sure loss. */
    static double oddsMultiplier(double p) {
        return 0.75d + 0.5d * (1d - p);
    }

    /**
     * A win at pre-match odds {@code p} (from the two ratings): 3 Trophies, plus 6 grind-minutes of odds-scaled EXP
     * while the day has paid fewer than {@link #EXP_WINS_PER_DAY} wins and {@code p} is at most
     * {@link #TROPHIES_ONLY_ABOVE}.
     */
    static Pay win(double p, int winsPaidToday, double expPerMinute) {
        if (p > TROPHIES_ONLY_ABOVE || winsPaidToday >= EXP_WINS_PER_DAY) {
            return new Pay(0, WIN_TROPHIES);
        }
        return new Pay(Math.round(WIN_EXP_GMIN * expPerMinute * oddsMultiplier(p)), WIN_TROPHIES);
    }

    static Pay loss() {
        return new Pay(0, LOSS_TROPHIES);
    }

    /** A best-of-three's score so far; {@code winner} is 0 (the human side), 1, or -1 while it is still open. */
    record Score(int humanRounds, int citizenRounds, int draws) {
        static final Score START = new Score(0, 0, 0);

        Score after(int roundWinnerSide) {
            return switch (roundWinnerSide) {
                case 0 -> new Score(humanRounds + 1, citizenRounds, draws);
                case 1 -> new Score(humanRounds, citizenRounds + 1, draws);
                default -> new Score(humanRounds, citizenRounds, draws + 1);
            };
        }

        int played() {
            return humanRounds + citizenRounds + draws;
        }

        /**
         * The match's winner side: whoever reached {@link #ROUNDS_TO_WIN}; after {@link #ROUNDS} rounds without one, the
         * side with more round wins, or -1 for a drawn match. -1 also while it is still open ({@link #over}).
         */
        int winner() {
            if (humanRounds >= ROUNDS_TO_WIN) {
                return 0;
            }
            if (citizenRounds >= ROUNDS_TO_WIN) {
                return 1;
            }
            if (played() >= ROUNDS) {
                return Integer.compare(citizenRounds, humanRounds) == 0 ? -1 : humanRounds > citizenRounds ? 0 : 1;
            }
            return -1;
        }

        boolean over() {
            return humanRounds >= ROUNDS_TO_WIN || citizenRounds >= ROUNDS_TO_WIN || played() >= ROUNDS;
        }

        @Override
        public String toString() {
            return humanRounds + "-" + citizenRounds;
        }
    }
}
